package dev.visualengine.authoring;

import java.util.*;

/**
 * Reversible root-graph edits. Embedded group revisions and generator seed identities stay owned.
 */
public final class GraphEdits {
  private GraphEdits() {}

  public static EffectGraph update(
      EffectGraph graph, String previousId, EffectGraph.Node replacement) {
    Objects.requireNonNull(graph);
    var previous =
        graph.nodes.stream()
            .filter(node -> node.id().equals(previousId))
            .findFirst()
            .orElseThrow(() -> problem(previousId, "Select an existing node before updating it"));
    if (replacement == null
        || replacement.kind() == null
        || replacement.id() == null
        || !replacement.id().matches("[A-Za-z][A-Za-z0-9_]{0,47}"))
      throw problem(
          previousId,
          "Node ID must start with a letter and contain 1..48 letters, digits or underscores");
    if (!replacement.id().equals(previousId)
        && graph.nodes.stream().anyMatch(node -> node.id().equals(replacement.id())))
      throw problem(previousId, "Node ID already exists: " + replacement.id());
    EffectGraph result = graph.copy();
    List<EffectGraph.Node> nodes = new ArrayList<>();
    for (var original : graph.nodes) {
      var node = original.id().equals(previousId) ? replacement : original;
      List<String> inputs =
          node.inputs().stream()
              .map(input -> input.equals(previousId) ? replacement.id() : input)
              .toList();
      nodes.add(
          new EffectGraph.Node(
              node.id(),
              node.kind(),
              inputs,
              node.expression(),
              node.x(),
              node.y(),
              node.bypass(),
              node.group(),
              node.comment(),
              node.template(),
              original.id().equals(previousId) ? previous.seedKey() : node.seedKey()));
    }
    result.nodes = nodes;
    if (Objects.equals(result.output, previousId)) result.output = replacement.id();
    return result;
  }

  public static boolean same(EffectGraph a, EffectGraph b) {
    return Objects.equals(a.id, b.id)
        && Objects.equals(a.version, b.version)
        && Objects.equals(a.output, b.output)
        && Objects.equals(a.nodes, b.nodes)
        && Objects.equals(a.groups, b.groups);
  }

  private static AuthoringDiagnostic problem(String id, String text) {
    return AuthoringDiagnostic.inNodes(
        List.of(String.valueOf(id)), new IllegalArgumentException(text));
  }
}
