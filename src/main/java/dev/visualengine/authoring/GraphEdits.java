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

  /** Connection edits accept incomplete typed drafts; compilation remains the apply boundary. */
  public static EffectGraph appendInput(EffectGraph graph, String target, String source) {
    var node = selectedNode(graph, target);
    requireSource(graph, target, source);
    var links = new ArrayList<>(node.inputs());
    links.add(source);
    return withInputs(graph, node, links);
  }

  public static EffectGraph replaceInput(
      EffectGraph graph, String target, int index, String source) {
    var node = selectedNode(graph, target);
    requireIndex(node, index);
    requireSource(graph, target, source);
    var links = new ArrayList<>(node.inputs());
    links.set(index, source);
    return withInputs(graph, node, links);
  }

  public static EffectGraph removeInput(EffectGraph graph, String target, int index) {
    var node = selectedNode(graph, target);
    requireIndex(node, index);
    var links = new ArrayList<>(node.inputs());
    links.remove(index);
    return withInputs(graph, node, links);
  }

  public static EffectGraph moveInput(EffectGraph graph, String target, int from, int to) {
    var node = selectedNode(graph, target);
    requireIndex(node, from);
    requireIndex(node, to);
    var links = new ArrayList<>(node.inputs());
    links.add(to, links.remove(from));
    return withInputs(graph, node, links);
  }

  private static EffectGraph.Node selectedNode(EffectGraph graph, String target) {
    Objects.requireNonNull(graph);
    return graph.nodes.stream()
        .filter(n -> n.id().equals(target))
        .findFirst()
        .orElseThrow(() -> problem(target, "Select an existing connection target"));
  }

  private static void requireSource(EffectGraph graph, String target, String source) {
    if (graph.nodes.stream().noneMatch(n -> n.id().equals(source)))
      throw problem(target, "Select an existing source node");
  }

  private static void requireIndex(EffectGraph.Node node, int index) {
    if (index < 0 || index >= node.inputs().size())
      throw problem(node.id(), "Select an existing input connection");
  }

  private static EffectGraph withInputs(
      EffectGraph graph, EffectGraph.Node node, List<String> links) {
    return update(
        graph,
        node.id(),
        new EffectGraph.Node(
            node.id(),
            node.kind(),
            links,
            node.expression(),
            node.x(),
            node.y(),
            node.bypass(),
            node.group(),
            node.comment(),
            node.template(),
            node.seedKey()));
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
