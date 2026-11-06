package dev.visualengine.authoring;

import java.util.*;

public final class EffectGraph {
  public enum Kind {
    FORMULA,
    COORDINATES,
    TIME,
    VECTOR2,
    VECTOR3,
    NOISE,
    ADD,
    MULTIPLY,
    MIX,
    DISTANCE,
    MASK,
    CELL_ID,
    HASH_ID,
    PREVIOUS_COLOR,
    PIGMENT,
    AGE,
    GROUP,
    INPUT,
    ALIAS,
    CHECK_SOURCE,
    EXPRESSION
  }

  public record Node(
      String id,
      Kind kind,
      List<String> inputs,
      String expression,
      double x,
      double y,
      boolean bypass,
      String group,
      String comment,
      String template,
      String seedKey) {
    public Node {
      inputs = inputs == null ? List.of() : List.copyOf(inputs);
      expression = expression == null ? "0" : expression;
      group = group == null ? "" : group;
      comment = comment == null ? "" : comment;
      template = template == null ? "" : template;
      seedKey = seedKey == null ? id : seedKey;
    }

    public Node(
        String id,
        Kind kind,
        List<String> inputs,
        String expression,
        double x,
        double y,
        boolean bypass,
        String group,
        String comment) {
      this(id, kind, inputs, expression, x, y, bypass, group, comment, "", id);
    }

    public Node(
        String id,
        Kind kind,
        List<String> inputs,
        String expression,
        double x,
        double y,
        boolean bypass,
        String group,
        String comment,
        String template) {
      this(id, kind, inputs, expression, x, y, bypass, group, comment, template, id);
    }

    public Node moved(double x, double y) {
      return new Node(
          id, kind, inputs, expression, x, y, bypass, group, comment, template, seedKey);
    }
  }

  public String id = "local.graph", version = "1.0.0", output = "field";
  public List<GraphTemplates.Group> groups = new ArrayList<>();
  public List<Node> nodes =
      new ArrayList<>(
          List.of(
              new Node(
                  "field",
                  Kind.FORMULA,
                  List.of(),
                  "sin(length(p) * frequency - phase)",
                  70,
                  70,
                  false,
                  "",
                  "Radial field")));

  public EffectGraph copy() {
    EffectGraph g = new EffectGraph();
    g.id = id;
    g.version = version;
    g.output = output;
    g.nodes = new ArrayList<>(nodes);
    g.groups = new ArrayList<>(groups);
    return g;
  }
}
