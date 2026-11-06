package dev.visualengine;

import dev.visualengine.authoring.*;
import dev.visualengine.model.Settings;
import dev.visualengine.presets.PresetIO;
import dev.visualengine.time.ControlSignals;
import java.util.*;

public final class AuthoringChecks {
  private static int count;

  private static void check(boolean ok, String message) {
    count++;
    if (!ok) throw new AssertionError(message);
  }

  private static void rejects(Runnable action, String message) {
    try {
      action.run();
    } catch (IllegalArgumentException e) {
      count++;
      return;
    }
    throw new AssertionError(message);
  }

  public static EffectControl scalar() {
    return new EffectControl(
        "amplitude",
        "Amplitude",
        Formula.Type.FLOAT,
        "domain",
        List.of(1.),
        .1,
        3,
        .01,
        EffectControl.Scale.LOGARITHMIC,
        EffectControl.Update.SMOOTHED);
  }

  public static EffectControl vector() {
    return new EffectControl(
        "tint",
        "Tint",
        Formula.Type.VEC3,
        "linear",
        List.of(.2, .4, .6),
        0,
        1,
        .01,
        EffectControl.Scale.LINEAR,
        EffectControl.Update.UNIFORM);
  }

  public static int run() throws Exception {
    count = 0;
    var controls = List.of(scalar(), vector());
    Settings s = new Settings();
    s.installEffectControls(controls, Map.of());
    s.recipe = Settings.Recipe.CUSTOM;
    s.expression = "amplitude*sin(p.x)+dot(tint,vec3(.2,.3,.5))";
    var f = EffectControl.compile(s.expression, controls);
    check(
        f.expression().contains("user_amplitude") && f.expression().contains("user_tint"),
        "custom symbols are generated uniforms");
    check(f.root().type() == Formula.Type.FLOAT, "vector controls type check");
    rejects(
        () -> EffectControl.compile("tint + vec2(1)", controls),
        "vector mismatch must fail before linking");
    rejects(
        () -> EffectControl.validateSchema(List.of(scalar(), scalar())), "duplicate descriptor");
    rejects(
        () ->
            new EffectControl(
                    "p",
                    "P",
                    Formula.Type.FLOAT,
                    "",
                    List.of(1.),
                    0,
                    2,
                    .1,
                    EffectControl.Scale.LINEAR,
                    EffectControl.Update.UNIFORM)
                .validate(),
        "reserved symbol");
    rejects(() -> s.setEffectValue("tint", List.of(1., 2., 3.)), "vector bounds");
    rejects(() -> s.setEffectValue("amplitude", List.of(Double.NaN)), "nonfinite value");
    rejects(
        () -> s.installEffectControls(controls, Map.of("missing", List.of(1.))),
        "unknown resolved control");
    s.setEffectValue("amplitude", List.of(2.));
    Settings clone = s.copy();
    s.setEffectValue("amplitude", List.of(1.));
    check(
        clone.effectValues.get("amplitude").equals(List.of(2.)),
        "snapshots own their resolved values");
    Settings loaded = PresetIO.parse(PresetIO.JSON.toJson(PresetIO.document(clone, 42)));
    check(
        loaded.effectControls.equals(controls) && loaded.effectValues.equals(clone.effectValues),
        "custom schema and values survive preset roundtrip");
    ControlSignals signals = new ControlSignals();
    signals.tick(s, 0);
    s.setEffectValue("amplitude", List.of(3.));
    var smooth = signals.tick(s, 1. / 120);
    check(
        smooth.effectValues.get("amplitude").getFirst() > 1
            && smooth.effectValues.get("amplitude").getFirst() < 3,
        "custom smoothing is tick based");
    EffectPackage pack = new EffectPackage();
    pack.controls = controls;
    pack.controlValues = new LinkedHashMap<>(clone.effectValues);
    pack.expression = clone.expression;
    pack.pigmentEnabled = true;
    pack.definitionHash = pack.contentHash();
    pack.validate();
    EffectPackage decoded = PresetIO.JSON.fromJson(PresetIO.JSON.toJson(pack), EffectPackage.class);
    decoded.validate();
    Settings resolved = new Settings();
    decoded.apply(resolved);
    check(
        resolved.pigmentEnabled && resolved.effectValues.equals(clone.effectValues),
        "effect package restores custom values and state requirements");
    decoded.expression = "0";
    rejects(decoded::validate, "definition hash pins effect content");
    EffectPackage legacy = new EffectPackage();
    legacy.effectApiVersion = 1;
    legacy.validate();
    check(legacy.controls.isEmpty(), "API 1 compatibility");
    EffectGraph grouped = groupGraph();
    var compiledGroup = GraphCompiler.compile(grouped, controls);
    check(
        compiledGroup.function().contains("user_amplitude")
            && !compiledGroup.function().contains("user_input0"),
        "typed expression ports bind generated values");
    EffectGraph wrong = grouped.copy();
    wrong.nodes.set(
        0,
        new EffectGraph.Node(
            "coord", EffectGraph.Kind.FORMULA, List.of(), "1", 0, 0, false, "", ""));
    rejects(() -> GraphCompiler.compile(wrong, controls), "group input type mismatch");
    EffectGraph missing = grouped.copy();
    missing.groups.clear();
    rejects(() -> GraphCompiler.compile(missing, controls), "missing pinned revision");
    EffectGraph duplicate = grouped.copy();
    duplicate.groups.add(duplicate.groups.getFirst());
    rejects(() -> GraphCompiler.compile(duplicate, controls), "duplicate group revision");
    EffectGraph recursive = grouped.copy();
    var first = recursive.groups.getFirst();
    recursive.groups =
        new ArrayList<>(
            List.of(
                new GraphTemplates.Group(
                    first.id(),
                    first.version(),
                    first.inputs(),
                    List.of(
                        new EffectGraph.Node(
                            "domain",
                            EffectGraph.Kind.INPUT,
                            List.of(),
                            "domain",
                            0,
                            0,
                            false,
                            "",
                            ""),
                        new EffectGraph.Node(
                            "bias", EffectGraph.Kind.INPUT, List.of(), "bias", 0, 0, false, "", ""),
                        new EffectGraph.Node(
                            "loop",
                            EffectGraph.Kind.GROUP,
                            List.of("domain", "bias"),
                            "0",
                            0,
                            0,
                            false,
                            "",
                            "",
                            first.key())),
                    "loop",
                    GraphCompiler.Port.SCALAR)));
    rejects(() -> GraphCompiler.compile(recursive, controls), "recursive reusable group");
    EffectGraph large = new EffectGraph();
    List<EffectGraph.Node> body = new ArrayList<>();
    for (int i = 0; i < 32; i++)
      body.add(
          new EffectGraph.Node(
              "n" + i, EffectGraph.Kind.FORMULA, List.of(), "0", 0, 0, false, "", ""));
    large.groups.add(
        new GraphTemplates.Group(
            "local.large", "1.0.0", List.of(), body, "n0", GraphCompiler.Port.SCALAR));
    large.nodes.clear();
    for (int i = 0; i < 9; i++)
      large.nodes.add(
          new EffectGraph.Node(
              "g" + i,
              EffectGraph.Kind.GROUP,
              List.of(),
              "0",
              0,
              0,
              false,
              "",
              "",
              "local.large@1.0.0"));
    large.output = "g0";
    rejects(() -> GraphCompiler.compile(large), "expanded instance budget");
    EffectGraph noise = noiseGraph();
    EffectGraph captured = GraphTemplates.capture(noise, "local.noise", "1.0.0", List.of());
    check(
        captured.groups.getFirst().nodes().getLast().seedKey().equals("field"),
        "grouping retains generator seed identity");
    rejects(
        () -> GraphTemplates.capture(captured, "local.noise", "1.0.0", List.of()),
        "pinned revisions cannot be overwritten");
    clone.graph = grouped;
    clone.graphEnabled = true;
    Settings groupRoundtrip = PresetIO.parse(PresetIO.JSON.toJson(PresetIO.document(clone, 0)));
    check(
        groupRoundtrip.graph.groups.equals(grouped.groups),
        "group definitions are embedded in presets");
    check(
        EffectPackage.load(java.nio.file.Path.of("src/test/resources/legacy-effect-v2.json"))
            .expression
            .equals("sin(p.x)"),
        "older hashed manifests migrate from original JSON");
    EffectPackage example =
        EffectPackage.load(java.nio.file.Path.of("examples/effects/ported-ripple.json"));
    example.apply(resolved);
    check(
        GraphCompiler.compile(resolved.graph, resolved.effectControls)
            .types()
            .containsKey("ripple"),
        "shipped typed group example validates");
    resolved.grid = 65;
    resolved.particles = 257;
    resolved.historyLayers = 3;
    var groupReplay = dev.visualengine.time.ReplayLog.begin(resolved, 320, 200);
    resolved.setEffectValue("amplitude", List.of(1.2));
    groupReplay.recordControl(4, resolved);
    groupReplay.endTick = 12;
    groupReplay.save(java.nio.file.Path.of("build/reports/group-replay-fixture.json"));
    return count;
  }

  public static EffectGraph groupGraph() {
    EffectGraph graph = new EffectGraph();
    graph.nodes =
        new ArrayList<>(
            List.of(
                new EffectGraph.Node(
                    "coord", EffectGraph.Kind.COORDINATES, List.of(), "0", 0, 0, false, "", ""),
                new EffectGraph.Node(
                    "offset", EffectGraph.Kind.FORMULA, List.of(), "0.1", 0, 0, false, "", ""),
                new EffectGraph.Node(
                    "instance",
                    EffectGraph.Kind.GROUP,
                    List.of("coord", "offset"),
                    "0",
                    0,
                    0,
                    false,
                    "",
                    "",
                    "local.ripple@1.0.0")));
    graph.output = "instance";
    graph.groups.add(
        new GraphTemplates.Group(
            "local.ripple",
            "1.0.0",
            List.of(
                new GraphTemplates.Input("domain", GraphCompiler.Port.VECTOR2),
                new GraphTemplates.Input("bias", GraphCompiler.Port.SCALAR)),
            List.of(
                new EffectGraph.Node(
                    "domain", EffectGraph.Kind.INPUT, List.of(), "domain", 0, 0, false, "", ""),
                new EffectGraph.Node(
                    "bias", EffectGraph.Kind.INPUT, List.of(), "bias", 0, 0, false, "", ""),
                new EffectGraph.Node(
                    "field",
                    EffectGraph.Kind.EXPRESSION,
                    List.of("domain", "bias"),
                    "sin(length(input0)*frequency-phase)+input1*amplitude",
                    0,
                    0,
                    false,
                    "",
                    "")),
            "field",
            GraphCompiler.Port.SCALAR));
    return graph;
  }

  public static EffectGraph noiseGraph() {
    EffectGraph graph = new EffectGraph();
    graph.nodes =
        new ArrayList<>(
            List.of(
                new EffectGraph.Node(
                    "coord", EffectGraph.Kind.COORDINATES, List.of(), "0", 0, 0, false, "", ""),
                new EffectGraph.Node(
                    "time", EffectGraph.Kind.TIME, List.of(), "0", 0, 0, false, "", ""),
                new EffectGraph.Node(
                    "point",
                    EffectGraph.Kind.VECTOR3,
                    List.of("coord", "time"),
                    "0",
                    0,
                    0,
                    false,
                    "",
                    ""),
                new EffectGraph.Node(
                    "field", EffectGraph.Kind.NOISE, List.of("point"), "0", 0, 0, false, "", "")));
    return graph;
  }
}
