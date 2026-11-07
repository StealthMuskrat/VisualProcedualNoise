package dev.visualengine;

import dev.visualengine.authoring.*;
import dev.visualengine.model.Settings;
import dev.visualengine.presets.PresetIO;
import java.nio.file.Path;
import java.util.*;

public final class GraphConnectionChecks {
  private static int count;

  private static void check(boolean ok, String text) {
    count++;
    if (!ok) throw new AssertionError(text);
  }

  private static void rejects(Runnable action, String node) {
    try {
      action.run();
    } catch (AuthoringDiagnostic error) {
      check(error.nodePath().equals(List.of(node)), "connection rejection locates its target");
      return;
    }
    throw new AssertionError("Expected connection rejection");
  }

  public static EffectGraph graph() {
    var graph = AuthoringChecks.noiseGraph();
    graph.nodes.add(
        new EffectGraph.Node(
            "shifted",
            EffectGraph.Kind.EXPRESSION,
            List.of("coord"),
            "vec2(input0.y, -input0.x) * 1.7 + vec2(0.4)",
            40,
            300,
            false,
            "Domain",
            "Alternate coordinates"));
    return graph;
  }

  public static int run() throws Exception {
    count = 0;
    var graph = graph();
    var snapshot = graph.copy();
    var rewired = GraphEdits.replaceInput(graph, "point", 0, "shifted");
    check(
        rewired.nodes.get(2).inputs().equals(List.of("shifted", "time")),
        "replacement changes only the chosen ordinal");
    check(
        GraphEdits.same(graph, snapshot),
        "accepted connection edits leave their source graph unchanged");
    check(
        rewired.nodes.get(2).seedKey().equals(graph.nodes.get(2).seedKey())
            && rewired.output.equals(graph.output)
            && rewired.groups.equals(graph.groups),
        "connection edits preserve seed keys, output and definitions");
    check(
        rewired
            .nodes
            .get(2)
            .moved(0, 0)
            .equals(
                new EffectGraph.Node(
                    "point",
                    EffectGraph.Kind.VECTOR3,
                    List.of("shifted", "time"),
                    graph.nodes.get(2).expression(),
                    0,
                    0,
                    false,
                    graph.nodes.get(2).group(),
                    graph.nodes.get(2).comment(),
                    graph.nodes.get(2).template(),
                    graph.nodes.get(2).seedKey())),
        "connection edits retain target properties");
    check(
        !GraphCompiler.compile(rewired).function().equals(GraphCompiler.compile(graph).function()),
        "rerouting changes executable graph dependencies");
    var restored = GraphEdits.replaceInput(rewired, "point", 0, "coord");
    check(
        GraphEdits.same(graph, restored)
            && GraphCompiler.compile(graph)
                .function()
                .equals(GraphCompiler.compile(restored).function()),
        "restoring a connection restores exact graph and shader source");
    var appended = GraphEdits.appendInput(graph, "point", "coord");
    check(
        appended.nodes.get(2).inputs().equals(List.of("coord", "time", "coord")),
        "append retains repeated ordered links");
    check(
        GraphEdits.removeInput(appended, "point", 0)
            .nodes
            .get(2)
            .inputs()
            .equals(List.of("time", "coord")),
        "removal affects one occurrence of a repeated link");
    check(
        GraphEdits.moveInput(appended, "point", 0, 2)
            .nodes
            .get(2)
            .inputs()
            .equals(List.of("time", "coord", "coord")),
        "move preserves the relative order of other slots");
    check(
        GraphEdits.same(graph, GraphEdits.replaceInput(graph, "point", 0, "coord"))
            && GraphEdits.same(graph, GraphEdits.moveInput(graph, "point", 0, 0)),
        "unchanged replace and move are structural no-ops");
    rejects(() -> GraphEdits.appendInput(graph, "absent", "coord"), "absent");
    rejects(() -> GraphEdits.appendInput(graph, "point", "absent"), "point");
    rejects(() -> GraphEdits.replaceInput(graph, "point", -1, "coord"), "point");
    rejects(() -> GraphEdits.replaceInput(graph, "point", 2, "coord"), "point");
    rejects(() -> GraphEdits.removeInput(graph, "point", 2), "point");
    rejects(() -> GraphEdits.moveInput(graph, "point", 0, 2), "point");
    check(GraphEdits.same(graph, snapshot), "rejected operations retain all authored data");
    var dangling = graph.copy();
    dangling.nodes.set(
        2,
        new EffectGraph.Node(
            "point",
            EffectGraph.Kind.VECTOR3,
            List.of("missing", "time"),
            "0",
            0,
            0,
            false,
            "",
            ""));
    check(
        GraphCompiler.compile(GraphEdits.replaceInput(dangling, "point", 0, "coord"))
            .types()
            .containsKey("field"),
        "a missing input can be repaired without first compiling its draft");
    check(
        GraphEdits.removeInput(dangling, "point", 0).nodes.get(2).inputs().equals(List.of("time")),
        "a dangling input can be removed directly");
    var cycle = GraphEdits.replaceInput(graph, "point", 0, "field");
    boolean rejected = false;
    try {
      GraphCompiler.compile(cycle);
    } catch (AuthoringDiagnostic expected) {
      rejected = expected.getMessage().contains("Same-step cycle");
    }
    check(
        rejected && GraphEdits.same(graph, snapshot),
        "cycle drafts are located at validation while retaining the source graph");
    var grouped = GraphTemplates.capture(graph, "local.connected", "1.0.0", List.of());
    var connectedGroup = GraphEdits.appendInput(grouped, "instance", "instance");
    check(
        connectedGroup.groups.equals(grouped.groups)
            && connectedGroup
                .nodes
                .getFirst()
                .template()
                .equals(grouped.nodes.getFirst().template()),
        "root connection edits cannot mutate embedded revisions");
    Settings before = new Settings();
    before.recipe = Settings.Recipe.CUSTOM;
    before.graphEnabled = true;
    before.grid = 65;
    before.particles = 257;
    before.historyLayers = 3;
    before.feedbackEnabled = before.pigmentEnabled = true;
    before.graph = graph;
    Settings after = before.copy();
    after.graph = rewired;
    Settings undo = before.copy();
    undo.graph = restored;
    var roundtrip = PresetIO.parse(PresetIO.JSON.toJson(PresetIO.document(after, 0)));
    check(
        GraphEdits.same(rewired, roundtrip.graph),
        "connection order and seed identity persist in presets");
    var fixture = new com.google.gson.JsonObject();
    fixture.add("original", PresetIO.document(before, 0));
    fixture.add("rewired", PresetIO.document(after, 0));
    fixture.add("restored", PresetIO.document(undo, 0));
    PresetIO.atomicWrite(
        Path.of("build/reports/graph-connection-fixture.json"), PresetIO.JSON.toJson(fixture));
    return count;
  }
}
