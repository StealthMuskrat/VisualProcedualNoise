package dev.visualengine.authoring;

import java.util.*;

/**
 * Explicit scalar interpretations of typed outputs, represented by ordinary reversible graph nodes.
 */
public final class GraphPreviews {
  public enum Mode {
    SCALAR("Scalar field"),
    X("X component"),
    Y("Y component"),
    Z("Z component"),
    W("W component"),
    LENGTH("Vector length"),
    DISTANCE_MASK("Distance mask"),
    REGION_HASH("Region hash");
    private final String label;

    Mode(String label) {
      this.label = label;
    }

    @Override
    public String toString() {
      return label;
    }
  }

  private GraphPreviews() {}

  public static EffectGraph prepare(
      EffectGraph graph, String selected, Mode mode, List<EffectControl> controls) {
    Objects.requireNonNull(graph);
    var source =
        graph.nodes.stream()
            .filter(n -> n.id().equals(selected))
            .findFirst()
            .orElseThrow(() -> problem(selected, "Select an existing node to preview"));
    if (mode == null) throw problem(selected, "Choose a preview interpretation");
    var candidate = graph.copy();
    candidate.output = selected;
    if (mode == Mode.SCALAR) {
      GraphCompiler.compile(candidate, controls);
      return candidate;
    }
    var type = GraphCompiler.rootTypes(candidate, controls).get(selected);
    int width =
        switch (type) {
          case VECTOR2 -> 2;
          case VECTOR3 -> 3;
          case VECTOR4 -> 4;
          default -> 0;
        };
    EffectGraph.Kind kind = EffectGraph.Kind.EXPRESSION;
    String expression;
    if (mode == Mode.DISTANCE_MASK) {
      if (type != GraphCompiler.Port.DISTANCE)
        throw problem(selected, "Distance mask requires DISTANCE");
      kind = EffectGraph.Kind.MASK;
      expression = "0";
    } else if (mode == Mode.REGION_HASH) {
      if (type != GraphCompiler.Port.REGION_ID)
        throw problem(selected, "Region hash requires REGION_ID");
      kind = EffectGraph.Kind.HASH_ID;
      expression = "0";
    } else if (mode == Mode.LENGTH) {
      if (width == 0) throw problem(selected, "Vector length requires VECTOR2, VECTOR3 or VECTOR4");
      expression = "length(input0)";
    } else {
      int component =
          switch (mode) {
            case X -> 0;
            case Y -> 1;
            case Z -> 2;
            case W -> 3;
            default -> throw new AssertionError(mode);
          };
      if (width <= component)
        throw problem(selected, mode + " requires a vector containing that component");
      expression = "input0." + "xyzw".charAt(component);
    }
    String output = null;
    for (var node : graph.nodes)
      if (!node.bypass()
          && node.kind() == kind
          && node.inputs().equals(List.of(selected))
          && node.expression().equals(expression)) {
        output = node.id();
        break;
      }
    if (output == null) {
      if (graph.nodes.size() >= 64)
        throw problem(selected, "Preview conversion would exceed 64 root nodes");
      Set<String> ids = new HashSet<>();
      graph.nodes.forEach(n -> ids.add(n.id()));
      output = "preview";
      int index = 1;
      while (ids.contains(output)) output = "preview" + (++index);
      double x = source.x() + 180;
      if (!Double.isFinite(x)) x = source.x();
      candidate.nodes.add(
          new EffectGraph.Node(
              output,
              kind,
              List.of(selected),
              expression,
              x,
              source.y(),
              false,
              "",
              "Preview " + mode + " of " + selected));
    }
    candidate.output = output;
    GraphCompiler.compile(candidate, controls);
    return candidate;
  }

  private static AuthoringDiagnostic problem(String selected, String text) {
    return AuthoringDiagnostic.inNodes(
        List.of(String.valueOf(selected)), new IllegalArgumentException(text));
  }
}
