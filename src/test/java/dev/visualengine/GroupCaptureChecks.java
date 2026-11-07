package dev.visualengine;

import dev.visualengine.authoring.*;
import dev.visualengine.model.Settings;
import dev.visualengine.presets.PresetIO;
import java.nio.file.Path;
import java.util.*;

public final class GroupCaptureChecks {
  private static int count;

  private static void check(boolean ok, String text) {
    count++;
    if (!ok) throw new AssertionError(text);
  }

  private static void rejects(Runnable action, String text) {
    try {
      action.run();
    } catch (IllegalArgumentException expected) {
      check(expected.getMessage().contains(text), "capture rejection: " + text);
      return;
    }
    throw new AssertionError("Expected capture rejection: " + text);
  }

  public static List<GraphTemplates.Exposure> ports() {
    return List.of(
        new GraphTemplates.Exposure("coord", "domain"),
        new GraphTemplates.Exposure("time", "clock"));
  }

  public static int run() throws Exception {
    count = 0;
    var graph = AuthoringChecks.noiseGraph();
    var before = graph.copy();
    var captured = GraphTemplates.capture(graph, "local.ported", "1.0.0", List.of(), ports());
    var definition = captured.groups.getLast();
    check(
        definition
            .inputs()
            .equals(
                List.of(
                    new GraphTemplates.Input("domain", GraphCompiler.Port.VECTOR2),
                    new GraphTemplates.Input("clock", GraphCompiler.Port.SCALAR))),
        "capture infers ordered numeric port types");
    check(
        captured.nodes.stream()
            .map(EffectGraph.Node::id)
            .toList()
            .equals(List.of("coord", "time", "instance")),
        "exposed root sources remain outside the group");
    check(
        captured.nodes.getLast().inputs().equals(List.of("coord", "time"))
            && captured.output.equals("instance"),
        "new instance binds ports in declared order");
    check(
        definition.nodes().getFirst().kind() == EffectGraph.Kind.INPUT
            && definition.nodes().getFirst().expression().equals("domain")
            && definition.nodes().getFirst().inputs().isEmpty(),
        "internal source becomes a named boundary");
    check(
        definition.nodes().getLast().seedKey().equals("field")
            && captured.nodes.getFirst().equals(graph.nodes.getFirst()),
        "capture preserves internal and external seed identities and properties");
    check(
        GraphEdits.same(graph, before),
        "capture owns a candidate and leaves the original graph intact");
    check(
        GraphCompiler.compile(captured).types().get("instance") == GraphCompiler.Port.SCALAR,
        "captured ports expand into a valid typed scalar field");
    var transitive =
        GraphTemplates.capture(
            graph,
            "local.point",
            "1.0.0",
            List.of(),
            List.of(new GraphTemplates.Exposure("point", "sample")));
    check(
        transitive.nodes.stream()
            .map(EffectGraph.Node::id)
            .toList()
            .equals(List.of("coord", "time", "point", "instance")),
        "external sources retain their complete dependency closure");
    check(
        transitive.groups.getLast().inputs().getFirst().type() == GraphCompiler.Port.VECTOR3,
        "vector3 source type is inferred without flattening its dependencies");
    var output =
        GraphTemplates.capture(
            graph,
            "local.field",
            "1.0.0",
            List.of(),
            List.of(new GraphTemplates.Exposure("field", "height")));
    check(
        output.nodes.size() == 5 && GraphCompiler.compile(output).order().contains("field"),
        "the active scalar output may itself be exposed without a cycle");
    var collision =
        GraphEdits.update(
            graph, "coord", GraphEditChecks.rename(graph.nodes.getFirst(), "instance"));
    var collisionGroup =
        GraphTemplates.capture(
            collision,
            "local.collision",
            "1.0.0",
            List.of(),
            List.of(new GraphTemplates.Exposure("instance", "domain")));
    check(
        collisionGroup.output.equals("instance2")
            && collisionGroup.nodes.getLast().inputs().equals(List.of("instance")),
        "instance naming avoids retained source IDs");
    rejects(
        () ->
            GraphTemplates.capture(
                graph,
                "local.ported",
                "1.0.0",
                List.of(),
                List.of(new GraphTemplates.Exposure("absent", "value"))),
        "contribute");
    var disconnected = graph.copy();
    disconnected.nodes.add(
        new EffectGraph.Node("unused", EffectGraph.Kind.TIME, List.of(), "0", 0, 0, false, "", ""));
    rejects(
        () ->
            GraphTemplates.capture(
                disconnected,
                "local.ported",
                "1.0.0",
                List.of(),
                List.of(new GraphTemplates.Exposure("unused", "value"))),
        "contribute");
    rejects(
        () ->
            GraphTemplates.capture(
                graph,
                "local.ported",
                "1.0.0",
                List.of(),
                List.of(new GraphTemplates.Exposure("coord", "bad name"))),
        "Input names");
    rejects(
        () ->
            GraphTemplates.validateExposures(
                List.of(
                    new GraphTemplates.Exposure("coord", "same"),
                    new GraphTemplates.Exposure("time", "same"))),
        "already exists");
    rejects(
        () ->
            GraphTemplates.validateExposures(
                List.of(
                    new GraphTemplates.Exposure("coord", "a"),
                    new GraphTemplates.Exposure("coord", "b"))),
        "only once");
    rejects(
        () ->
            GraphTemplates.validateExposures(
                java.util.stream.IntStream.range(0, 9)
                    .mapToObj(i -> new GraphTemplates.Exposure("n" + i, "p" + i))
                    .toList()),
        "eight");
    rejects(
        () -> GraphTemplates.capture(captured, "local.ported", "1.0.0", List.of()),
        "already exists");
    rejects(
        () -> GraphTemplates.capture(graph, "bad id", "1.0.0", List.of(), ports()),
        "group revision");
    var nested =
        GraphTemplates.capture(
            captured,
            "local.ported",
            "1.1.0",
            List.of(),
            List.of(new GraphTemplates.Exposure("coord", "domain")));
    check(
        nested.groups.size() == 2
            && nested.groups.getFirst().equals(definition)
            && nested.groups.getLast().version().equals("1.1.0"),
        "new revisions preserve pinned prior definitions");
    var revised = GraphEdits.replaceInput(captured, "instance", 0, "time");
    rejects(() -> GraphCompiler.compile(revised), "Expected VECTOR2");
    check(
        captured.groups.getFirst().equals(definition) && GraphEdits.same(graph, before),
        "rejected captures and invalid reroutes preserve prior graphs and definitions");
    for (var semantic : List.of(GraphCompiler.Port.DISTANCE, GraphCompiler.Port.REGION_ID)) {
      var typed = new EffectGraph();
      typed.nodes =
          new ArrayList<>(
              List.of(
                  graph.nodes.getFirst(),
                  new EffectGraph.Node(
                      "semantic",
                      semantic == GraphCompiler.Port.DISTANCE
                          ? EffectGraph.Kind.DISTANCE
                          : EffectGraph.Kind.CELL_ID,
                      List.of("coord"),
                      "0",
                      0,
                      0,
                      false,
                      "",
                      ""),
                  new EffectGraph.Node(
                      "field",
                      semantic == GraphCompiler.Port.DISTANCE
                          ? EffectGraph.Kind.MASK
                          : EffectGraph.Kind.HASH_ID,
                      List.of("semantic"),
                      "0",
                      0,
                      0,
                      false,
                      "",
                      "")));
      var grouped =
          GraphTemplates.capture(
              typed,
              "local.semantic",
              "1.0.0",
              List.of(),
              List.of(new GraphTemplates.Exposure("semantic", "value")));
      check(
          grouped.groups.getLast().inputs().getFirst().type() == semantic,
          "capture preserves semantic input type " + semantic);
    }
    var large = graph.copy();
    for (int i = 0; i < 29; i++)
      large.nodes.add(
          new EffectGraph.Node(
              "extra" + i, EffectGraph.Kind.TIME, List.of(), "0", 0, 0, false, "", ""));
    rejects(
        () -> GraphTemplates.capture(large, "local.large", "1.0.0", List.of(), ports()),
        "group revision");
    var full = graph.copy();
    for (int i = 0; i < 16; i++)
      full.groups.add(
          new GraphTemplates.Group(
              "local.g" + i,
              "1.0.0",
              List.of(),
              List.of(
                  new EffectGraph.Node(
                      "field", EffectGraph.Kind.FORMULA, List.of(), "0", 0, 0, false, "", "")),
              "field",
              GraphCompiler.Port.SCALAR));
    rejects(
        () -> GraphTemplates.capture(full, "local.full", "1.0.0", List.of(), ports()),
        "16 group revisions");
    check(
        large.groups.isEmpty() && large.nodes.size() == 33 && full.groups.size() == 16,
        "rejected capture limits preserve original node and revision collections");
    Settings plain = new Settings();
    plain.recipe = Settings.Recipe.CUSTOM;
    plain.graphEnabled = true;
    plain.graph = graph;
    plain.grid = 65;
    plain.particles = 257;
    plain.historyLayers = 3;
    plain.feedbackEnabled = plain.pigmentEnabled = true;
    Settings ported = plain.copy();
    ported.graph = captured;
    var roundtrip = PresetIO.parse(PresetIO.JSON.toJson(PresetIO.document(ported, 0)));
    check(
        GraphEdits.same(captured, roundtrip.graph),
        "named ports, bindings and pinned definitions round trip through presets");
    var fixture = new com.google.gson.JsonObject();
    fixture.add("original", PresetIO.document(plain, 0));
    fixture.add("captured", PresetIO.document(ported, 0));
    Settings retargeted = ported.copy();
    retargeted.graph.nodes.add(
        new EffectGraph.Node(
            "alternate",
            EffectGraph.Kind.EXPRESSION,
            List.of("coord"),
            "input0 * 1.7 + vec2(0.4)",
            0,
            0,
            false,
            "",
            "Alternate domain"));
    retargeted.graph =
        GraphEdits.replaceInput(retargeted.graph, retargeted.graph.output, 0, "alternate");
    check(
        retargeted.graph.groups.equals(captured.groups)
            && GraphCompiler.compile(retargeted.graph).types().get("instance")
                == GraphCompiler.Port.SCALAR,
        "captured ports can be rerouted without changing their pinned definition");
    fixture.add("retargeted", PresetIO.document(retargeted, 0));
    Settings second = plain.copy();
    second.graph = nested;
    fixture.add("nested", PresetIO.document(second, 0));
    PresetIO.atomicWrite(
        Path.of("build/reports/group-capture-fixture.json"), PresetIO.JSON.toJson(fixture));
    return count;
  }
}
