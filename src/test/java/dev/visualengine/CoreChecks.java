package dev.visualengine;

import dev.visualengine.authoring.*;
import dev.visualengine.gpu.PassScheduler;
import dev.visualengine.model.*;
import dev.visualengine.presets.PresetIO;
import dev.visualengine.time.*;
import java.util.*;

/** Dependency-free contract checks runnable with a JDK 25 on any platform. */
public final class CoreChecks {
  private static int count;

  private static void check(boolean condition, String label) {
    count++;
    if (!condition) throw new AssertionError(label);
  }

  private static void rejects(Runnable action, String label) {
    try {
      action.run();
    } catch (IllegalArgumentException e) {
      count++;
      return;
    }
    throw new AssertionError("Accepted " + label);
  }

  public static void main(String[] args) throws Exception {
    var f = Formula.compile("sin(p.x * frequency) + cos(p.y)");
    check(f.root().type() == Formula.Type.FLOAT && f.analyticGradient(), "smooth scalar AD");
    check(Formula.compile("noise3d(vec3(p, t))").dependencies().contains("t"), "time dependency");
    check(
        !Formula.compile("sin(length(p))").analyticGradient(),
        "complete-field fallback for unsupported gradient");
    check(
        Formula.compile("2 + 3 * 4").expression().equals("14.0"),
        "constant folding with precedence");
    rejects(() -> Formula.compile("vec2(1) + vec3(1)"), "vector mismatch");
    rejects(() -> Formula.compile("javaExec(p)"), "unknown function");
    rejects(() -> Formula.compile("sin(p.x); discard"), "source injection");
    rejects(() -> Formula.compile("(".repeat(40) + "1" + ")".repeat(40)), "excess nesting");
    rejects(() -> Formula.compile("p.z"), "invalid swizzle");
    rejects(() -> Formula.compile("1e999"), "nonfinite literal");
    EffectGraph graph = new EffectGraph();
    graph.nodes.add(
        new EffectGraph.Node(
            "unused", EffectGraph.Kind.FORMULA, List.of(), "2", 0, 0, false, "", ""));
    check(
        !GraphCompiler.compile(graph).order().contains("unused"), "unreachable execution removed");
    graph.nodes =
        new ArrayList<>(
            List.of(
                node("a", EffectGraph.Kind.FORMULA, List.of(), "sin(p.x)"),
                node("b", EffectGraph.Kind.FORMULA, List.of(), "sin(p.x)"),
                node("sum", EffectGraph.Kind.ADD, List.of("a", "b"), "0")));
    graph.output = "sum";
    String source = GraphCompiler.compile(graph).function();
    check(source.indexOf("sin(") == source.lastIndexOf("sin("), "pure expression shared");
    EffectGraph cycle = new EffectGraph();
    cycle.nodes =
        new ArrayList<>(
            List.of(
                node("a", EffectGraph.Kind.ADD, List.of("b", "b"), "0"),
                node("b", EffectGraph.Kind.ADD, List.of("a", "a"), "0")));
    cycle.output = "a";
    rejects(() -> GraphCompiler.compile(cycle), "same-step cycle");
    EffectGraph missing = new EffectGraph();
    missing.output = "missing";
    rejects(() -> GraphCompiler.compile(missing), "missing output");
    Settings s = new Settings();
    s.seed = Long.MIN_VALUE;
    s.recipe = Settings.Recipe.CUSTOM;
    s.graph = graph;
    s.graphEnabled = true;
    s.set("warp", 1.25);
    s.locks.add("warp");
    Settings decoded = PresetIO.parse(PresetIO.JSON.toJson(PresetIO.document(s, 1234)));
    check(
        decoded.seed == Long.MIN_VALUE
            && decoded.get("warp") == 1.25
            && decoded.graph.nodes.equals(s.graph.nodes)
            && decoded.locks.contains("warp"),
        "lossless preset roundtrip");
    var legacy = PresetIO.document(s, 0);
    legacy.addProperty("schemaVersion", 1);
    for (String id : List.of("frequency", "drift", "stripeRate"))
      legacy.getAsJsonObject("settings").getAsJsonObject("values").remove(id);
    check(
        PresetIO.parse(legacy.toString()).get("frequency") == 2, "explicit v1 authoring migration");
    var bad = PresetIO.document(s, 0);
    bad.getAsJsonObject("settings").getAsJsonObject("values").addProperty("feed", .9);
    rejects(() -> PresetIO.parse(bad.toString()), "invalid solver range");
    check(
        Seeds.derive(7, "flow", "x") != Seeds.derive(7, "flow", "y"),
        "independent stable channels");
    long seed = Seeds.derive(7, "flow", "x");
    Seeds.derive(7, "new node", "x");
    check(seed == Seeds.derive(7, "flow", "x"), "node addition leaves seed stable");
    Playback clock = new Playback();
    check(clock.accumulate(.1, 1, false) == 8, "bounded interactive catchup");
    check(clock.accumulate(10, 1, true) == 0, "paused accumulator");
    for (int i = 0; i < 120; i++) clock.advance();
    check(Math.abs(clock.seconds() - 1) < 1e-12, "integer-tick time");
    rejects(
        () ->
            PassScheduler.schedule(
                List.of(
                    new PassScheduler.Pass("a", Set.of("b"), () -> {}, Set.of()),
                    new PassScheduler.Pass("b", Set.of("a"), () -> {}, Set.of()))),
        "pass cycle");
    Settings modulated = new Settings();
    Modulation sine =
        new Modulation(
            "wave",
            "warp",
            Modulation.Source.SINE,
            Modulation.Mode.REPLACE,
            4,
            0,
            1,
            0,
            new double[] {0, 0, 1, 1});
    sine.validate();
    modulated.modulations.add(sine);
    check(Math.abs(sine.evaluate(1, 7, .5) - 1) < 1e-12, "sine period and range");
    Modulation random =
        new Modulation(
            "random",
            "flow",
            Modulation.Source.SMOOTH_RANDOM,
            Modulation.Mode.REPLACE,
            2,
            0,
            1,
            0,
            new double[] {0, 0, 1, 1});
    check(
        random.evaluate(1.2, 7, .5) == random.evaluate(1.2, 7, .5),
        "random modulation reproducible");
    check(
        Math.abs(random.evaluate(2 - 1e-6, 7, .5) - random.evaluate(2 + 1e-6, 7, .5)) < 1e-8,
        "random modulation continuous at interval boundary");
    ControlSignals signals = new ControlSignals();
    Settings resolved = signals.tick(modulated, Playback.DT);
    double phase = resolved.motionClock;
    modulated.set("motion", 1);
    resolved = signals.tick(modulated, 2 * Playback.DT);
    check(
        Math.abs(resolved.motionClock - phase - Playback.DT) < 1e-12,
        "rate edits integrate phase continuously");
    check(modulated.get("warp") == .65, "modulation leaves base controls unchanged");
    ReplayLog replay = ReplayLog.begin(new Settings(), 320, 200);
    replay.recordPointer(1, .2, .3, .1, true);
    replay.recordControl(2, modulated);
    replay.recordPointer(2, .3, .4, .1, false);
    replay.validate();
    check(
        replay.at(1).size() == 1 && replay.at(2).size() == 2 && replay.at(3).isEmpty(),
        "replay event ordering and cursor");
    replay.rewind();
    check(replay.at(2).size() == 3, "replay rewind");
    replay.events.add(new ReplayLog.Event(1, "pointer", 0, 0, .1, 0, null));
    rejects(replay::validate, "out-of-order replay");
    Settings tile = new Settings();
    tile.tileMode = true;
    tile.validate();
    tile.pigmentEnabled = true;
    rejects(tile::validate, "stateful tile claim");
    Settings loop = new Settings();
    loop.recipe = Settings.Recipe.CUSTOM;
    loop.loopSeconds = 4;
    loop.expression = "sin(t)";
    rejects(() -> EffectPackage.resolveResources(loop), "unbounded-time loop claim");
    rejects(
        () -> dev.visualengine.gpu.Budget.require(dev.visualengine.gpu.Budget.LIMIT + 1),
        "excess resource budget");
    Settings fixtureSettings = new Settings();
    fixtureSettings.grid = 65;
    fixtureSettings.particles = 257;
    fixtureSettings.historyLayers = 3;
    Recipes.apply(fixtureSettings, Settings.Recipe.FLUID_PALETTE);
    ReplayLog fixture = ReplayLog.begin(fixtureSettings, 320, 200);
    fixture.recordPointer(1, -.2, .1, .12, true);
    fixture.recordPointer(3, .2, .3, .12, true);
    fixture.recordPointer(5, .2, .3, .12, false);
    fixtureSettings.set("flow", .7);
    fixture.recordControl(6, fixtureSettings);
    fixture.endTick = 12;
    fixture.save(java.nio.file.Path.of("build/reports/replay-fixture.json"));
    count += AuthoringChecks.run();
    count += AuthoringDiagnosticChecks.run();
    count += SourceBoundsChecks.run();
    count += SceneHistoryChecks.run();
    count += GraphNavigationChecks.run();
    count += GroupCaptureChecks.run();
    count += GroupUnpackChecks.run();
    count += GraphPreviewChecks.run();
    count += GraphConnectionChecks.run();
    count += GraphEditChecks.run();
    count += GraphEditUiChecks.run();
    count += GraphNavigationUiChecks.run();
    count += GraphConnectionUiChecks.run();
    count += GroupCaptureUiChecks.run();
    count += GroupUnpackUiChecks.run();
    count += GraphPreviewUiChecks.run();
    count += AuthoringUiChecks.run();
    count += CheckpointChecks.run();
    count += QualityChecks.run();
    count += CaptureChecks.run();
    count += DiscoveryChecks.run();
    count += DiscoveryUiChecks.run();
    count += TextureMapChecks.run();
    count += TextureMapUiChecks.run();
    count += MorphChecks.run();
    count += MorphUiChecks.run();
    count += GaborChecks.run();
    count += DirectionalChecks.run();
    count += PeriodicityChecks.run();
    count += InspectionUiChecks.run();
    count += BenchmarkChecks.run();
    count += BenchmarkCliChecks.run();
    count += dev.visualengine.export.AcceptanceChecks.run();
    count += PresentationChecks.run();
    count += PresentationUiChecks.run();
    count += DesktopChecks.run();
    count += DeviceChecks.run();
    count += GpuReportChecks.run();
    count += ShaderCacheChecks.run();
    count += PeakBudgetChecks.run();
    count += dev.visualengine.export.OutputPairChecks.run();
    count += FileJobsChecks.run();
    count += EffectModulationChecks.run();
    count += ModulationUiChecks.run();
    count += RedrawChecks.run();
    System.out.println("Core checks passed: " + count);
  }

  private static EffectGraph.Node node(
      String id, EffectGraph.Kind kind, List<String> inputs, String expression) {
    return new EffectGraph.Node(id, kind, inputs, expression, 0, 0, false, "", "");
  }
}
