package dev.visualengine;

import dev.visualengine.authoring.*;
import dev.visualengine.model.Settings;
import dev.visualengine.presets.PresetIO;
import java.nio.file.*;
import java.util.*;

public final class GraphEditChecks {
  private static int count;

  private static void check(boolean ok, String text) {
    count++;
    if (!ok) throw new AssertionError(text);
  }

  private static void rejects(Runnable action, String text) {
    try {
      action.run();
    } catch (IllegalArgumentException expected) {
      count++;
      return;
    }
    throw new AssertionError(text);
  }

  public static EffectGraph.Node rename(EffectGraph.Node node, String name) {
    return new EffectGraph.Node(
        name,
        node.kind(),
        node.inputs(),
        node.expression(),
        node.x(),
        node.y(),
        node.bypass(),
        node.group(),
        node.comment(),
        node.template(),
        "new-id-must-not-reseed");
  }

  public static EffectGraph renamed(EffectGraph source) {
    EffectGraph graph = source.copy();
    for (var node : source.nodes) {
      var current =
          graph.nodes.stream()
              .filter(value -> value.id().equals(node.id()))
              .findFirst()
              .orElseThrow();
      graph = GraphEdits.update(graph, node.id(), rename(current, "renamed_" + node.id()));
    }
    return graph;
  }

  public static int run() throws Exception {
    count = 0;
    EffectGraph graph = AuthoringChecks.noiseGraph();
    var original = graph.copy();
    var point = graph.nodes.get(2);
    var renamed = GraphEdits.update(graph, "point", rename(point, "position"));
    check(
        renamed.nodes.getLast().inputs().equals(List.of("position")),
        "renaming rewrites consuming input IDs");
    check(
        GraphEdits.same(graph, original) && !GraphEdits.same(graph, renamed),
        "graph edits own a candidate and preserve the original");
    check(
        renamed.nodes.get(2).seedKey().equals(point.seedKey()),
        "renaming preserves generator identity even when the candidate requests a new seed key");
    var output = GraphEdits.update(graph, "field", rename(graph.nodes.getLast(), "heightField"));
    check(
        output.output.equals("heightField") && output.nodes.getLast().seedKey().equals("field"),
        "output rename updates output while preserving its seed key");
    EffectGraph repeated = graph.copy();
    repeated.nodes.add(
        new EffectGraph.Node(
            "sum",
            EffectGraph.Kind.ADD,
            List.of("field", "field"),
            "field",
            0,
            0,
            false,
            "field",
            "field"));
    repeated.output = "sum";
    var repeatedRename =
        GraphEdits.update(repeated, "field", rename(repeated.nodes.get(3), "heightField"));
    var sum = repeatedRename.nodes.getLast();
    check(
        sum.inputs().equals(List.of("heightField", "heightField"))
            && sum.expression().equals("field")
            && sum.group().equals("field")
            && sum.comment().equals("field"),
        "rename rewrites ordered links without replacing expression or metadata text");
    rejects(
        () -> GraphEdits.update(graph, "point", rename(point, "field")),
        "duplicate ID must fail atomically");
    rejects(
        () -> GraphEdits.update(graph, "point", rename(point, "bad id")),
        "invalid ID must fail atomically");
    rejects(
        () -> GraphEdits.update(graph, "point", rename(point, "x".repeat(49))),
        "root node ID length remains bounded");
    rejects(
        () -> GraphEdits.update(graph, "absent", rename(point, "position")),
        "missing selection must fail atomically");
    check(
        GraphEdits.same(graph, original), "rejected rename leaves all nodes and references intact");
    EffectGraph grouped = GraphTemplates.capture(graph, "local.noise", "1.0.0", List.of());
    var groupRename = renamed(grouped);
    check(
        groupRename.groups.equals(grouped.groups)
            && groupRename.nodes.getFirst().template().equals("local.noise@1.0.0"),
        "instance rename leaves embedded pinned revisions and references unchanged");
    check(
        GraphCompiler.compile(renamed(graph)).types().containsKey("renamed_field"),
        "all renamed connections form a valid typed graph");
    check(
        GraphCompiler.compile(groupRename).types().containsKey("renamed_instance"),
        "renamed group instance expands and type checks");
    Settings before = new Settings();
    before.recipe = Settings.Recipe.CUSTOM;
    before.graphEnabled = true;
    before.graph = graph;
    before.grid = 65;
    before.particles = 257;
    before.historyLayers = 3;
    before.feedbackEnabled = before.pigmentEnabled = true;
    Settings after = before.copy();
    after.graph = renamed(graph);
    Settings roundtrip = PresetIO.parse(PresetIO.JSON.toJson(PresetIO.document(after, 0)));
    check(
        GraphEdits.same(after.graph, roundtrip.graph),
        "renamed IDs, links and seed keys persist in presets");
    var fixture = new com.google.gson.JsonObject();
    fixture.add("original", PresetIO.document(before, 0));
    fixture.add("renamed", PresetIO.document(after, 0));
    before.graph = grouped;
    after.graph = groupRename;
    fixture.add("groupOriginal", PresetIO.document(before, 0));
    fixture.add("groupRenamed", PresetIO.document(after, 0));
    PresetIO.atomicWrite(
        Path.of("build/reports/graph-edit-fixture.json"), PresetIO.JSON.toJson(fixture));
    return count;
  }
}
