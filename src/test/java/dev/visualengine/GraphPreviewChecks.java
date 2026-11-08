package dev.visualengine;

import dev.visualengine.authoring.*;
import dev.visualengine.model.Settings;
import dev.visualengine.presets.PresetIO;
import java.nio.file.Path;
import java.util.*;

public final class GraphPreviewChecks {
  private static int count;

  private static void check(boolean ok, String text) {
    count++;
    if (!ok) throw new AssertionError(text);
  }

  private static void rejects(Runnable action, String text) {
    try {
      action.run();
    } catch (IllegalArgumentException expected) {
      check(expected.getMessage().contains(text), "preview rejection: " + text);
      return;
    }
    throw new AssertionError(text);
  }

  public static EffectGraph graph() {
    var graph = AuthoringChecks.noiseGraph();
    graph.nodes.add(
        new EffectGraph.Node(
            "color",
            EffectGraph.Kind.EXPRESSION,
            List.of("point"),
            "vec4(input0, 1.0)",
            650,
            60,
            false,
            "",
            "Vector4 output"));
    graph.nodes.add(
        new EffectGraph.Node(
            "distance",
            EffectGraph.Kind.DISTANCE,
            List.of("coord"),
            "0",
            250,
            180,
            false,
            "",
            "Distance output"));
    graph.nodes.add(
        new EffectGraph.Node(
            "region",
            EffectGraph.Kind.CELL_ID,
            List.of("coord"),
            "0",
            460,
            180,
            false,
            "",
            "Region output"));
    return graph;
  }

  public static Map<String, GraphPreviews.Mode> modes() {
    Map<String, GraphPreviews.Mode> modes = new LinkedHashMap<>();
    modes.put("x", GraphPreviews.Mode.X);
    modes.put("y", GraphPreviews.Mode.Y);
    modes.put("z", GraphPreviews.Mode.Z);
    modes.put("w", GraphPreviews.Mode.W);
    modes.put("length", GraphPreviews.Mode.LENGTH);
    modes.put("distance", GraphPreviews.Mode.DISTANCE_MASK);
    modes.put("region", GraphPreviews.Mode.REGION_HASH);
    return modes;
  }

  public static String source(String mode) {
    return switch (mode) {
      case "z" -> "point";
      case "w" -> "color";
      case "distance" -> "distance";
      case "region" -> "region";
      default -> "coord";
    };
  }

  public static int run() throws Exception {
    count = 0;
    var graph = graph();
    var before = graph.copy();
    String program = GraphCompiler.compile(graph).function();
    var types = GraphCompiler.rootTypes(graph, List.of());
    check(
        types.size() == 7
            && types.get("color") == GraphCompiler.Port.VECTOR4
            && types.get("distance") == GraphCompiler.Port.DISTANCE
            && types.get("region") == GraphCompiler.Port.REGION_ID,
        "root inspection retains disconnected numeric and semantic types");
    check(
        !GraphCompiler.compile(graph).types().containsKey("color"),
        "compiled types keep their prior reachable-output contract");
    boolean immutable = false;
    try {
      types.clear();
    } catch (UnsupportedOperationException expected) {
      immutable = true;
    }
    check(immutable, "inspected type maps are immutable");
    var direct = GraphPreviews.prepare(graph, "time", GraphPreviews.Mode.SCALAR, List.of());
    check(
        direct.output.equals("time") && direct.nodes.equals(graph.nodes),
        "scalar preview changes only the active output");
    rejects(
        () -> GraphPreviews.prepare(graph, "coord", GraphPreviews.Mode.SCALAR, List.of()),
        "must be scalar");
    rejects(
        () -> GraphPreviews.prepare(graph, "coord", GraphPreviews.Mode.Z, List.of()),
        "requires a vector");
    rejects(
        () -> GraphPreviews.prepare(graph, "point", GraphPreviews.Mode.W, List.of()),
        "requires a vector");
    rejects(
        () -> GraphPreviews.prepare(graph, "field", GraphPreviews.Mode.LENGTH, List.of()),
        "requires VECTOR2");
    rejects(
        () -> GraphPreviews.prepare(graph, "coord", GraphPreviews.Mode.DISTANCE_MASK, List.of()),
        "requires DISTANCE");
    rejects(
        () -> GraphPreviews.prepare(graph, "distance", GraphPreviews.Mode.REGION_HASH, List.of()),
        "requires REGION_ID");
    rejects(
        () -> GraphPreviews.prepare(graph, "absent", GraphPreviews.Mode.X, List.of()),
        "existing node");
    rejects(() -> GraphPreviews.prepare(graph, "coord", null, List.of()), "interpretation");
    var x = GraphPreviews.prepare(graph, "coord", GraphPreviews.Mode.X, List.of());
    check(
        x.nodes.getLast().kind() == EffectGraph.Kind.EXPRESSION
            && x.nodes.getLast().expression().equals("input0.x")
            && x.nodes.getLast().inputs().equals(List.of("coord")),
        "component preview uses an explicit typed scalar adapter");
    check(
        GraphEdits.same(x, GraphPreviews.prepare(x, "coord", GraphPreviews.Mode.X, List.of())),
        "repeated previews reuse the same adapter without graph growth");
    check(
        GraphPreviews.prepare(graph, "distance", GraphPreviews.Mode.DISTANCE_MASK, List.of())
                .nodes
                .getLast()
                .kind()
            == EffectGraph.Kind.MASK,
        "distance preview retains explicit semantic conversion");
    check(
        GraphPreviews.prepare(graph, "region", GraphPreviews.Mode.REGION_HASH, List.of())
                .nodes
                .getLast()
                .kind()
            == EffectGraph.Kind.HASH_ID,
        "region preview hashes rather than implicitly casting identity");
    var collision = graph.copy();
    collision.nodes.add(
        new EffectGraph.Node(
            "preview", EffectGraph.Kind.TIME, List.of(), "0", 0, 0, false, "", ""));
    check(
        GraphPreviews.prepare(collision, "color", GraphPreviews.Mode.W, List.of())
            .output
            .equals("preview2"),
        "preview naming avoids existing IDs");
    var full = x.copy();
    for (int i = 0; full.nodes.size() < 64; i++)
      full.nodes.add(
          new EffectGraph.Node(
              "extra" + i, EffectGraph.Kind.TIME, List.of(), "0", 0, 0, false, "", ""));
    check(
        GraphEdits.same(
            full, GraphPreviews.prepare(full, "coord", GraphPreviews.Mode.X, List.of())),
        "existing adapter can be reused at the root-node bound");
    rejects(
        () -> GraphPreviews.prepare(full, "coord", GraphPreviews.Mode.Y, List.of()),
        "64 root nodes");
    var missingOutput = graph.copy();
    missingOutput.output = "absent";
    check(
        GraphCompiler.compile(
                GraphPreviews.prepare(missingOutput, "color", GraphPreviews.Mode.W, List.of()))
            .types()
            .containsKey("color"),
        "explicit preview can repair a missing active output");
    var vectorOutput = graph.copy();
    vectorOutput.output = "color";
    check(
        GraphCompiler.rootTypes(vectorOutput, List.of()).get("color") == GraphCompiler.Port.VECTOR4,
        "root inspection permits a typed non-scalar active output");
    var broken = graph.copy();
    broken.nodes.set(
        2,
        new EffectGraph.Node(
            "point", EffectGraph.Kind.VECTOR3, List.of("coord"), "0", 0, 0, false, "", ""));
    rejects(
        () -> GraphPreviews.prepare(broken, "coord", GraphPreviews.Mode.X, List.of()),
        "requires 2 inputs");
    check(
        GraphEdits.same(graph, before) && GraphCompiler.compile(graph).function().equals(program),
        "inspection and accepted/rejected previews leave original graph, source and seeds intact");
    var controlled = graph.copy();
    controlled.nodes.add(
        new EffectGraph.Node(
            "controlled",
            EffectGraph.Kind.EXPRESSION,
            List.of("coord"),
            "vec4(tint, 1.0)",
            0,
            0,
            false,
            "",
            ""));
    check(
        GraphCompiler.compile(
                    GraphPreviews.prepare(
                        controlled,
                        "controlled",
                        GraphPreviews.Mode.W,
                        List.of(AuthoringChecks.vector())),
                    List.of(AuthoringChecks.vector()))
                .types()
                .get("controlled")
            == GraphCompiler.Port.VECTOR4,
        "vector previews compile with the declared custom control schema");
    var grouped = graph.copy();
    grouped.groups.add(
        new GraphTemplates.Group(
            "local.vector",
            "1.0.0",
            List.of(),
            List.of(
                new EffectGraph.Node(
                    "domain", EffectGraph.Kind.COORDINATES, List.of(), "0", 0, 0, false, "", "")),
            "domain",
            GraphCompiler.Port.VECTOR2));
    grouped.nodes.add(
        new EffectGraph.Node(
            "vectorGroup",
            EffectGraph.Kind.GROUP,
            List.of(),
            "0",
            0,
            0,
            false,
            "",
            "",
            "local.vector@1.0.0"));
    var groupPreview =
        GraphPreviews.prepare(grouped, "vectorGroup", GraphPreviews.Mode.Y, List.of());
    check(
        groupPreview.groups.equals(grouped.groups)
            && GraphCompiler.rootTypes(groupPreview, List.of()).get("vectorGroup")
                == GraphCompiler.Port.VECTOR2,
        "group output inspection preserves pinned vector revisions");
    var fixture = new com.google.gson.JsonObject();
    Settings initial = new Settings();
    initial.recipe = Settings.Recipe.CUSTOM;
    initial.graphEnabled = true;
    initial.graph = graph;
    initial.grid = 65;
    initial.particles = 257;
    initial.historyLayers = 3;
    initial.feedbackEnabled = initial.pigmentEnabled = true;
    fixture.add("original", PresetIO.document(initial, 0));
    for (var entry : modes().entrySet()) {
      Settings preview = initial.copy();
      preview.graph =
          GraphPreviews.prepare(graph, source(entry.getKey()), entry.getValue(), List.of());
      Settings expected = initial.copy();
      var old = expected.graph.nodes.get(3);
      String expression =
          switch (entry.getKey()) {
            case "x" -> "p.x";
            case "y" -> "p.y";
            case "z" -> "t";
            case "w" -> "1.0";
            case "length" -> "length(p)";
            case "distance" -> "1.0 - smoothstep(-0.03, 0.03, length(p) - 0.5)";
            default -> "0";
          };
      expected.graph.nodes.set(
          3,
          new EffectGraph.Node(
              "field",
              entry.getKey().equals("region") ? EffectGraph.Kind.HASH_ID : EffectGraph.Kind.FORMULA,
              entry.getKey().equals("region") ? List.of("region") : List.of(),
              expression,
              old.x(),
              old.y(),
              false,
              "",
              "",
              "",
              old.seedKey()));
      check(
          GraphEdits.same(
              preview.graph,
              PresetIO.parse(PresetIO.JSON.toJson(PresetIO.document(preview, 0))).graph),
          "preview adapter persists: " + entry.getKey());
      fixture.add(entry.getKey(), PresetIO.document(preview, 0));
      fixture.add(entry.getKey() + "Expected", PresetIO.document(expected, 0));
    }
    PresetIO.atomicWrite(
        Path.of("build/reports/graph-preview-fixture.json"), PresetIO.JSON.toJson(fixture));
    return count;
  }
}
