package dev.visualengine.authoring;

import dev.visualengine.time.Seeds;
import java.util.*;

public final class GraphCompiler {
  public enum Port {
    SCALAR,
    VECTOR2,
    VECTOR3,
    VECTOR4,
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

  private Map<String, List<String>> nodePaths = Map.of();
  private final Map<String, EffectGraph.Node> nodes = new LinkedHashMap<>();
  private final Map<String, Value> values = new LinkedHashMap<>();
  private final Map<String, String> shared = new HashMap<>();
  private final Set<String> active = new HashSet<>(), resources = new TreeSet<>();
  private final List<String> lines = new ArrayList<>(), order = new ArrayList<>();
  private int expandedCost;
  private List<EffectControl> controls = List.of();

  private GraphCompiler() {}

  public static Compiled compile(EffectGraph graph) {
    return compile(graph, List.of());
  }

  public static Compiled compile(EffectGraph graph, List<EffectControl> controls) {
    GraphCompiler c = analyze(graph, controls);
    c.values.clear();
    c.shared.clear();
    c.lines.clear();
    c.order.clear();
    c.resources.clear();
    c.expandedCost = 0;
    Value output = c.visit(graph.output);
    if (output.port() != Port.SCALAR)
      throw AuthoringDiagnostic.inNodes(
          c.nodePaths.get(graph.output),
          new IllegalArgumentException(
              "Graph output must be scalar; use MASK or HASH_ID to convert semantic types"));
    String function =
        "float customField(vec2 p) {\n"
            + String.join("\n", c.lines)
            + "\nreturn "
            + output.code()
            + ";\n}\n";
    if (GeneratedSource.bytes(function) > GeneratedSource.LIMIT)
      throw new IllegalArgumentException("Generated graph source exceeds 64 KiB");
    Map<String, Port> types = new LinkedHashMap<>();
    c.values.forEach((id, v) -> types.put(id, v.port()));
    return new Compiled(
        function, Set.copyOf(c.resources), Map.copyOf(types), List.copyOf(c.order), function);
  }

  /**
   * Validated types for every authored root, including disconnected fragments and vector outputs.
   */
  public static Map<String, Port> rootTypes(EffectGraph graph, List<EffectControl> controls) {
    var compiler = analyze(graph, controls);
    Map<String, Port> types = new LinkedHashMap<>();
    for (var node : graph.nodes) types.put(node.id(), compiler.values.get(node.id()).port());
    return Collections.unmodifiableMap(types);
  }

  private static GraphCompiler analyze(EffectGraph graph, List<EffectControl> controls) {
    EffectControl.validateSchema(controls);
    var expanded = GraphTemplates.expandWithOrigins(graph);
    graph = expanded.graph();
    if (graph == null
        || graph.nodes == null
        || graph.nodes.isEmpty()
        || graph.nodes.size() > 256
        || graph.output == null)
      throw new IllegalArgumentException("Expanded graph requires 1..256 nodes and an output");
    GraphCompiler c = new GraphCompiler();
    c.controls = List.copyOf(controls);
    c.nodePaths = expanded.nodePaths();
    for (var n : graph.nodes) {
      if (n == null
          || n.kind() == null
          || n.id() == null
          || !n.id().matches("[A-Za-z][A-Za-z0-9_]{0,127}")
          || !Double.isFinite(n.x())
          || !Double.isFinite(n.y())
          || n.seedKey() == null
          || n.seedKey().length() > 128
          || n.comment().length() > 2048
          || n.group().length() > 128
          || c.nodes.putIfAbsent(n.id(), n) != null)
        throw AuthoringDiagnostic.inNodes(
            n == null
                ? List.of()
                : c.nodePaths.getOrDefault(n.id(), List.of(String.valueOf(n.id()))),
            new IllegalArgumentException("Invalid or duplicate graph node ID"));
    }
    // Validate every node, including disconnected authoring fragments.
    for (var n : graph.nodes) c.visit(n.id());
    return c;
  }

  private Value visit(String id) {
    try {
      return visitNode(id);
    } catch (IllegalArgumentException e) {
      throw AuthoringDiagnostic.inNodes(nodePaths.getOrDefault(id, List.of(id)), e);
    }
  }

  private Value visitNode(String id) {
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
      case ALIAS -> {
        arity(n, in, 1);
        return in.getFirst();
      }
      case CHECK_SOURCE -> {
        arity(n, in, 1);
        Port expected;
        try {
          expected = Port.valueOf(n.expression());
        } catch (Exception e) {
          throw new IllegalArgumentException("Invalid group port type");
        }
        require(in.getFirst(), expected);
        return in.getFirst();
      }
      case EXPRESSION -> {
        if (in.isEmpty() || in.size() > 4)
          throw new IllegalArgumentException("Expression nodes require 1..4 named inputs");
        Map<String, Formula.Type> symbols = new LinkedHashMap<>(EffectControl.symbols(controls));
        Map<String, Formula.Reference> refs = new LinkedHashMap<>();
        for (int i = 0; i < in.size(); i++) {
          var value = in.get(i);
          Formula.Type type =
              switch (value.port()) {
                case SCALAR -> Formula.Type.FLOAT;
                case VECTOR2 -> Formula.Type.VEC2;
                case VECTOR3 -> Formula.Type.VEC3;
                case VECTOR4 -> Formula.Type.VEC4;
                default ->
                    throw new IllegalArgumentException(
                        "Convert semantic IDs/distances before using them in expressions");
              };
          String name = "input" + i;
          symbols.put(name, type);
          refs.put(name, new Formula.Reference(value.code(), type));
        }
        var f = Formula.compileValue(n.expression(), symbols);
        expandedCost += f.root().cost();
        code = Formula.bind(f.root(), refs).code();
        port =
            switch (f.root().type()) {
              case FLOAT -> Port.SCALAR;
              case VEC2 -> Port.VECTOR2;
              case VEC3 -> Port.VECTOR3;
              case VEC4 -> Port.VECTOR4;
            };
      }
      case FORMULA -> {
        arity(n, in, 0);
        var f = EffectControl.compileFieldValue(n.expression(), controls);
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
        long offset = Seeds.derive(0, n.seedKey(), "domain") & 65535;
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
      case VECTOR4 -> "vec4";
      case REGION_ID -> "uint";
    };
  }
}
