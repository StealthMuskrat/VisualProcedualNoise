package dev.visualengine.authoring;

import dev.visualengine.time.Seeds;
import java.util.*;

public final class GraphCompiler {
  public enum Port {
    SCALAR,
    VECTOR2,
    VECTOR3,
    DISTANCE,
    REGION_ID
  }

  private record Value(Port port, String code) {}

  public record Compiled(
      String function,
      Set<String> resources,
      Map<String, Port> types,
      List<String> order,
      String structuralKey) {}

  private final Map<String, EffectGraph.Node> nodes = new LinkedHashMap<>();
  private final Map<String, Value> values = new LinkedHashMap<>();
  private final Map<String, String> shared = new HashMap<>();
  private final Set<String> active = new HashSet<>(), resources = new TreeSet<>();
  private final List<String> lines = new ArrayList<>(), order = new ArrayList<>();
  private int expandedCost;

  private GraphCompiler() {}

  public static Compiled compile(EffectGraph graph) {
    if (graph == null
        || graph.nodes == null
        || graph.nodes.isEmpty()
        || graph.nodes.size() > 64
        || graph.output == null)
      throw new IllegalArgumentException("Graph requires 1..64 nodes and an output");
    GraphCompiler c = new GraphCompiler();
    for (var n : graph.nodes) {
      if (n == null
          || n.kind() == null
          || n.id() == null
          || !n.id().matches("[A-Za-z][A-Za-z0-9_]{0,47}")
          || !Double.isFinite(n.x())
          || !Double.isFinite(n.y())
          || n.comment().length() > 2048
          || n.group().length() > 128
          || c.nodes.putIfAbsent(n.id(), n) != null)
        throw new IllegalArgumentException("Invalid or duplicate graph node ID");
    }
    // Validate every node, including disconnected authoring fragments, then prune execution to the
    // output.
    for (var n : graph.nodes) c.visit(n.id());
    c.values.clear();
    c.shared.clear();
    c.lines.clear();
    c.order.clear();
    c.resources.clear();
    c.expandedCost = 0;
    Value output = c.visit(graph.output);
    if (output.port() != Port.SCALAR)
      throw new IllegalArgumentException(
          "Graph output must be scalar; use MASK or HASH_ID to convert semantic types");
    String function =
        "float customField(vec2 p) {\n"
            + String.join("\n", c.lines)
            + "\nreturn "
            + output.code()
            + ";\n}\n";
    if (function.length() > 65536)
      throw new IllegalArgumentException("Generated graph source exceeds 64 KiB");
    Map<String, Port> types = new LinkedHashMap<>();
    c.values.forEach((id, v) -> types.put(id, v.port()));
    return new Compiled(
        function, Set.copyOf(c.resources), Map.copyOf(types), List.copyOf(c.order), function);
  }

  private Value visit(String id) {
    Value old = values.get(id);
    if (old != null) return old;
    EffectGraph.Node n = nodes.get(id);
    if (n == null) throw new IllegalArgumentException("Missing graph node: " + id);
    if (!active.add(id))
      throw new IllegalArgumentException(
          "Same-step cycle at " + id + ". Use an explicit previous-state resource.");
    List<Value> in = new ArrayList<>();
    for (String source : n.inputs()) in.add(visit(source));
    Value result;
    if (n.bypass()) {
      if (in.isEmpty()) throw new IllegalArgumentException("Bypass requires an input: " + id);
      result = in.getFirst();
    } else result = lower(n, in);
    String key = result.port() + ":" + result.code();
    String variable = shared.get(key);
    if (variable == null) {
      variable = "node_" + id;
      shared.put(key, variable);
      lines.add(glsl(result.port()) + " " + variable + " = " + result.code() + ";");
    }
    Value value = new Value(result.port(), variable);
    values.put(id, value);
    active.remove(id);
    order.add(id);
    return value;
  }

  private Value lower(EffectGraph.Node n, List<Value> in) {
    String code;
    Port port;
    String a = in.isEmpty() ? "" : in.getFirst().code();
    switch (n.kind()) {
      case FORMULA -> {
        arity(n, in, 0);
        var f = Formula.compile(n.expression());
        if (f.root().type() != Formula.Type.FLOAT)
          throw new IllegalArgumentException(n.id() + ": formula field must be scalar");
        expandedCost += f.root().cost();
        code = f.expression();
        port = Port.SCALAR;
      }
      case COORDINATES -> {
        arity(n, in, 0);
        code = "p";
        port = Port.VECTOR2;
      }
      case TIME -> {
        arity(n, in, 0);
        code = "time";
        port = Port.SCALAR;
      }
      case VECTOR2 -> {
        arity(n, in, 2);
        require(in, Port.SCALAR);
        code = "vec2(" + a + "," + in.get(1).code() + ")";
        port = Port.VECTOR2;
      }
      case VECTOR3 -> {
        arity(n, in, 2);
        require(in.get(0), Port.VECTOR2);
        require(in.get(1), Port.SCALAR);
        code = "vec3(" + a + "," + in.get(1).code() + ")";
        port = Port.VECTOR3;
      }
      case NOISE -> {
        arity(n, in, 1);
        require(in.getFirst(), Port.VECTOR3);
        long offset = Seeds.derive(0, n.id(), "domain") & 65535;
        code = "noise3d(" + a + "+vec3(" + (offset / 1024.) + "))";
        port = Port.SCALAR;
        expandedCost += 64;
      }
      case ADD, MULTIPLY -> {
        arity(n, in, 2);
        if (in.get(0).port() != in.get(1).port()
            || in.get(0).port() == Port.REGION_ID
            || in.get(0).port() == Port.DISTANCE)
          throw new IllegalArgumentException(n.id() + ": equal numeric ports required");
        code = "(" + a + (n.kind() == EffectGraph.Kind.ADD ? "+" : "*") + in.get(1).code() + ")";
        port = in.get(0).port();
      }
      case MIX -> {
        arity(n, in, 3);
        require(in, Port.SCALAR);
        code = "mix(" + a + "," + in.get(1).code() + ",clamp(" + in.get(2).code() + ",0.,1.))";
        port = Port.SCALAR;
      }
      case DISTANCE -> {
        arity(n, in, 1);
        require(in.getFirst(), Port.VECTOR2);
        code = "(length(" + a + ")-.5)";
        port = Port.DISTANCE;
      }
      case MASK -> {
        arity(n, in, 1);
        require(in.getFirst(), Port.DISTANCE);
        code = "(1.-smoothstep(-.03,.03," + a + "))";
        port = Port.SCALAR;
      }
      case CELL_ID -> {
        arity(n, in, 1);
        require(in.getFirst(), Port.VECTOR2);
        code = "uint(cellular(" + a + ").z*1023.)";
        port = Port.REGION_ID;
        expandedCost += 180;
      }
      case HASH_ID -> {
        arity(n, in, 1);
        require(in.getFirst(), Port.REGION_ID);
        code = "(float(hashWord(" + a + ")&65535u)/65535.)";
        port = Port.SCALAR;
      }
      case PREVIOUS_COLOR -> {
        arity(n, in, 0);
        code =
            "dot(texture(feedbackTexture,p/vec2(2.*viewport.x/viewport.y,2.)+.5).rgb,vec3(.333333))";
        port = Port.SCALAR;
        resources.add("previousVisualColor");
      }
      case PIGMENT -> {
        arity(n, in, 0);
        code = "texture(pigmentTexture,p/vec2(2.*viewport.x/viewport.y,2.)+.5).g";
        port = Port.SCALAR;
        resources.add("pigment");
      }
      case AGE -> {
        arity(n, in, 0);
        code = "texture(ageTexture,p/vec2(2.*viewport.x/viewport.y,2.)+.5).r";
        port = Port.SCALAR;
        resources.add("localAge");
      }
      default -> throw new IllegalArgumentException("Unsupported node: " + n.kind());
    }
    if (++expandedCost > 8192)
      throw new IllegalArgumentException("Graph expanded cost exceeds limit");
    return new Value(port, code);
  }

  private static void arity(EffectGraph.Node n, List<Value> values, int arity) {
    if (values.size() != arity)
      throw new IllegalArgumentException(n.id() + " requires " + arity + " inputs");
  }

  private static void require(Value value, Port port) {
    if (value.port() != port)
      throw new IllegalArgumentException("Expected " + port + ", found " + value.port());
  }

  private static void require(List<Value> values, Port port) {
    values.forEach(v -> require(v, port));
  }

  private static String glsl(Port p) {
    return switch (p) {
      case SCALAR, DISTANCE -> "float";
      case VECTOR2 -> "vec2";
      case VECTOR3 -> "vec3";
      case REGION_ID -> "uint";
    };
  }
}
