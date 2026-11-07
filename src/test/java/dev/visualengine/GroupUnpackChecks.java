package dev.visualengine;

import dev.visualengine.authoring.*;
import dev.visualengine.model.Settings;
import dev.visualengine.presets.PresetIO;
import java.nio.file.Path;
import java.util.*;

public final class GroupUnpackChecks {
  private static int count;

  private static void check(boolean ok, String text) {
    count++;
    if (!ok) throw new AssertionError(text);
  }

  private static void rejects(Runnable action, String text) {
    try {
      action.run();
    } catch (IllegalArgumentException expected) {
      check(expected.getMessage().contains(text), "unpack rejection: " + text);
      return;
    }
    throw new AssertionError(text);
  }

  public static EffectGraph packed() {
    return GraphTemplates.capture(
        AuthoringChecks.noiseGraph(),
        "local.unpack",
        "1.0.0",
        List.of(),
        GroupCaptureChecks.ports());
  }

  public static int run() throws Exception {
    count = 0;
    var packed = packed();
    var before = packed.copy();
    var unpacked = GraphTemplates.unpack(packed, packed.output, List.of());
    check(
        unpacked.nodes.size() == 9 && unpacked.output.equals(packed.output),
        "unpack retains output identity while adding members and typed inputs");
    check(
        unpacked.nodes.stream()
            .noneMatch(
                n -> n.kind() == EffectGraph.Kind.GROUP || n.kind() == EffectGraph.Kind.INPUT),
        "one-level non-nested capture becomes editable root operations");
    check(
        unpacked.groups.equals(packed.groups) && GraphEdits.same(packed, before),
        "unpack owns its candidate and preserves pinned definitions");
    var output =
        unpacked.nodes.stream().filter(n -> n.id().equals(packed.output)).findFirst().orElseThrow();
    check(
        output.kind() == EffectGraph.Kind.CHECK_SOURCE
            && output.expression().equals("SCALAR")
            && output.seedKey().equals(packed.nodes.getLast().seedKey()),
        "instance ID becomes an explicit declared output-type boundary");
    var domain =
        unpacked.nodes.stream()
            .filter(
                n -> n.kind() == EffectGraph.Kind.CHECK_SOURCE && n.expression().equals("VECTOR2"))
            .findFirst()
            .orElseThrow();
    check(
        domain.inputs().equals(List.of("coord")),
        "named domain binding retains its explicit type check");
    check(
        unpacked.nodes.stream()
            .anyMatch(n -> n.kind() == EffectGraph.Kind.NOISE && n.seedKey().equals("field")),
        "seeded members retain original generator identities");
    check(
        unpacked.nodes.getFirst().equals(packed.nodes.getFirst())
            && unpacked.nodes.get(1).equals(packed.nodes.get(1)),
        "external source properties and order stay intact");
    check(
        GraphCompiler.compile(unpacked).types().get(packed.output) == GraphCompiler.Port.SCALAR,
        "unpacked root graph remains typed");
    check(
        GraphEdits.same(unpacked, GraphTemplates.unpack(packed, packed.output, List.of())),
        "unpack allocates names deterministically");
    rejects(
        () -> GraphCompiler.compile(GraphEdits.replaceInput(unpacked, domain.id(), 0, "time")),
        "Expected VECTOR2");
    var consumer = packed.copy();
    consumer.nodes.add(
        new EffectGraph.Node(
            "sum",
            EffectGraph.Kind.ADD,
            List.of(packed.output, packed.output),
            "0",
            0,
            0,
            false,
            "",
            ""));
    consumer.output = "sum";
    var consumed = GraphTemplates.unpack(consumer, packed.output, List.of());
    check(
        consumed.nodes.get(3).inputs().equals(List.of(packed.output, packed.output))
            && consumed.output.equals("sum"),
        "consumer links, repeated inputs and another active output remain unchanged");
    var collision = packed.copy();
    collision.nodes.add(
        new EffectGraph.Node(
            "instance_input0", EffectGraph.Kind.TIME, List.of(), "0", 0, 0, false, "", ""));
    var unique = GraphTemplates.unpack(collision, collision.output, List.of());
    check(
        unique.nodes.stream().map(EffectGraph.Node::id).distinct().count() == unique.nodes.size()
            && unique.nodes.stream().anyMatch(n -> n.id().equals("instance_input0_2")),
        "generated root IDs avoid existing port/member collisions");
    var longName =
        GraphEdits.update(
            packed, packed.output, GraphEditChecks.rename(packed.nodes.getLast(), "i".repeat(48)));
    var clipped = GraphTemplates.unpack(longName, longName.output, List.of());
    check(
        clipped.nodes.stream().allMatch(n -> n.id().matches("[A-Za-z][A-Za-z0-9_]{0,47}"))
            && clipped.nodes.stream().map(EffectGraph.Node::id).distinct().count()
                == clipped.nodes.size(),
        "long instance names yield unique IDs inside the root limit");
    rejects(() -> GraphTemplates.unpack(packed, "absent", List.of()), "non-bypassed GROUP");
    rejects(() -> GraphTemplates.unpack(packed, "coord", List.of()), "non-bypassed GROUP");
    var bypassed = packed.copy();
    var old = bypassed.nodes.getLast();
    bypassed.nodes.set(
        2,
        new EffectGraph.Node(
            old.id(),
            old.kind(),
            old.inputs(),
            old.expression(),
            old.x(),
            old.y(),
            true,
            old.group(),
            old.comment(),
            old.template(),
            old.seedKey()));
    rejects(
        () -> GraphTemplates.unpack(bypassed, bypassed.output, List.of()), "non-bypassed GROUP");
    var boundary = packed.copy();
    for (int i = 0; i < 55; i++)
      boundary.nodes.add(
          new EffectGraph.Node(
              "extra" + i, EffectGraph.Kind.TIME, List.of(), "0", 0, 0, false, "", ""));
    check(
        GraphTemplates.unpack(boundary, boundary.output, List.of()).nodes.size() == 64,
        "unpacking can fill the exact root-node bound");
    boundary.nodes.add(
        new EffectGraph.Node(
            "oneMore", EffectGraph.Kind.TIME, List.of(), "0", 0, 0, false, "", ""));
    rejects(() -> GraphTemplates.unpack(boundary, boundary.output, List.of()), "64 root nodes");
    check(
        boundary.nodes.size() == 59 && boundary.groups.equals(packed.groups),
        "rejected root growth preserves original collections");
    var nested =
        GraphTemplates.capture(
            packed,
            "local.unpack",
            "1.1.0",
            List.of(),
            List.of(new GraphTemplates.Exposure("coord", "domain")));
    var nestedUnpacked = GraphTemplates.unpack(nested, nested.output, List.of());
    var inner =
        nestedUnpacked.nodes.stream()
            .filter(n -> n.kind() == EffectGraph.Kind.GROUP)
            .findFirst()
            .orElseThrow();
    check(
        inner.template().equals("local.unpack@1.0.0")
            && nestedUnpacked.groups.equals(nested.groups),
        "unpacking one level retains nested instances and their pinned revisions");
    var fully = GraphTemplates.unpack(nestedUnpacked, inner.id(), List.of());
    check(
        fully.nodes.stream().noneMatch(n -> n.kind() == EffectGraph.Kind.GROUP)
            && fully.groups.equals(nested.groups),
        "nested instances can be unpacked individually without rewriting definitions");
    var semantic = new EffectGraph();
    semantic.groups.add(
        new GraphTemplates.Group(
            "local.distance",
            "1.0.0",
            List.of(new GraphTemplates.Input("domain", GraphCompiler.Port.VECTOR2)),
            List.of(
                new EffectGraph.Node(
                    "domain", EffectGraph.Kind.INPUT, List.of(), "domain", 0, 0, false, "", ""),
                new EffectGraph.Node(
                    "shape",
                    EffectGraph.Kind.DISTANCE,
                    List.of("domain"),
                    "0",
                    0,
                    0,
                    false,
                    "",
                    "")),
            "shape",
            GraphCompiler.Port.DISTANCE));
    semantic.nodes =
        new ArrayList<>(
            List.of(
                new EffectGraph.Node(
                    "coord", EffectGraph.Kind.COORDINATES, List.of(), "0", 0, 0, false, "", ""),
                new EffectGraph.Node(
                    "shape",
                    EffectGraph.Kind.GROUP,
                    List.of("coord"),
                    "0",
                    0,
                    0,
                    false,
                    "",
                    "",
                    "local.distance@1.0.0"),
                new EffectGraph.Node(
                    "field", EffectGraph.Kind.MASK, List.of("shape"), "0", 0, 0, false, "", "")));
    var semanticUnpacked = GraphTemplates.unpack(semantic, "shape", List.of());
    check(
        GraphCompiler.compile(semanticUnpacked).types().get("shape") == GraphCompiler.Port.DISTANCE
            && semanticUnpacked.nodes.get(1).expression().equals("DISTANCE"),
        "unpack preserves non-scalar semantic output boundaries");
    Settings settings = new Settings();
    settings.recipe = Settings.Recipe.CUSTOM;
    settings.graphEnabled = true;
    settings.grid = 65;
    settings.particles = 257;
    settings.historyLayers = 3;
    settings.feedbackEnabled = settings.pigmentEnabled = true;
    settings.graph = unpacked;
    check(
        GraphEdits.same(
            unpacked, PresetIO.parse(PresetIO.JSON.toJson(PresetIO.document(settings, 0))).graph),
        "unpacked aliases, type boundaries and pinned definitions persist in presets");
    var fixture = new com.google.gson.JsonObject();
    for (var entry :
        Map.of(
                "packed",
                packed,
                "unpacked",
                unpacked,
                "nestedPacked",
                nested,
                "nestedUnpacked",
                nestedUnpacked,
                "fullyUnpacked",
                fully)
            .entrySet()) {
      var value = settings.copy();
      value.graph = entry.getValue();
      fixture.add(entry.getKey(), PresetIO.document(value, 0));
    }
    PresetIO.atomicWrite(
        Path.of("build/reports/group-unpack-fixture.json"), PresetIO.JSON.toJson(fixture));
    return count;
  }
}
