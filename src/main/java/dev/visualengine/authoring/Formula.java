package dev.visualengine.authoring;

import java.util.*;

/** Bounded expression parser. Only typed AST nodes, never original text, reach code generation. */
public final class Formula {
  public enum Type {
    FLOAT(1, "float"),
    VEC2(2, "vec2"),
    VEC3(3, "vec3"),
    VEC4(4, "vec4");
    public final int width;
    public final String glsl;

    Type(int width, String glsl) {
      this.width = width;
      this.glsl = glsl;
    }
  }

  public sealed interface Node permits NumberNode, Symbol, Unary, Binary, Call, Swizzle, Reference {
    Type type();

    String code();

    int cost();

    int depth();
  }

  public record Reference(String identifier, Type type) implements Node {
    public Reference {
      if (identifier == null || !identifier.matches("node_[A-Za-z0-9_]+") || type == null)
        throw new IllegalArgumentException("Invalid generated graph reference");
    }

    public String code() {
      return identifier;
    }

    public int cost() {
      return 1;
    }

    public int depth() {
      return 1;
    }
  }

  public static Node bind(Node n, Map<String, Reference> references) {
    return switch (n) {
      case Symbol s -> references.containsKey(s.name()) ? references.get(s.name()) : s;
      case Unary u -> new Unary(u.op(), bind(u.child(), references));
      case Binary b ->
          new Binary(b.op(), bind(b.left(), references), bind(b.right(), references), b.type());
      case Call c ->
          new Call(c.name(), c.args().stream().map(a -> bind(a, references)).toList(), c.type());
      case Swizzle s -> new Swizzle(bind(s.child(), references), s.components(), s.type());
      default -> n;
    };
  }

  public record NumberNode(double value) implements Node {
    public Type type() {
      return Type.FLOAT;
    }

    public String code() {
      return Double.toString(value);
    }

    public int cost() {
      return 1;
    }

    public int depth() {
      return 1;
    }
  }

  public record Symbol(String name, Type type) implements Node {
    public String code() {
      return switch (name) {
        case "t" -> "time";
        case "phase" -> "motionPhase.x";
        default -> SYMBOLS.containsKey(name) ? name : "user_" + name;
      };
    }

    public int cost() {
      return 1;
    }

    public int depth() {
      return 1;
    }
  }

  public record Unary(char op, Node child) implements Node {
    public Type type() {
      return child.type();
    }

    public String code() {
      return "(" + op + child.code() + ")";
    }

    public int cost() {
      return child.cost() + 1;
    }

    public int depth() {
      return child.depth() + 1;
    }
  }

  public record Binary(char op, Node left, Node right, Type type) implements Node {
    public String code() {
      return "(" + left.code() + op + right.code() + ")";
    }

    public int cost() {
      return left.cost() + right.cost() + 1;
    }

    public int depth() {
      return Math.max(left.depth(), right.depth()) + 1;
    }
  }

  public record Call(String name, List<Node> args, Type type) implements Node {
    public String code() {
      boolean broadcast =
          type != Type.FLOAT
              && Set.of("min", "max", "safeDiv", "powSafe", "mix", "clamp", "smoothstep")
                  .contains(name);
      return name
          + "("
          + String.join(
              ",",
              args.stream()
                  .map(
                      n ->
                          broadcast && n.type() == Type.FLOAT
                              ? type.glsl + "(" + n.code() + ")"
                              : n.code())
                  .toList())
          + ")";
    }

    public int cost() {
      return args.stream().mapToInt(Node::cost).sum()
          + switch (name) {
            case "fbm" -> 320;
            case "noise3d" -> 64;
            case "cellular" -> 180;
            case "gabor" -> 2048;
            default -> 2;
          };
    }

    public int depth() {
      return args.stream().mapToInt(Node::depth).max().orElse(0) + 1;
    }
  }

  public record Swizzle(Node child, String components, Type type) implements Node {
    public String code() {
      return "(" + child.code() + ")." + components;
    }

    public int cost() {
      return child.cost() + 1;
    }

    public int depth() {
      return child.depth() + 1;
    }
  }

  public record Compiled(
      Node root,
      String expression,
      String gradient,
      boolean analyticGradient,
      Set<String> dependencies) {}

  public static final Map<String, Type> SYMBOLS =
      Map.of(
          "p",
          Type.VEC2,
          "t",
          Type.FLOAT,
          "phase",
          Type.FLOAT,
          "frequency",
          Type.FLOAT,
          "drift",
          Type.FLOAT,
          "stripeRate",
          Type.FLOAT,
          "pi",
          Type.FLOAT);
  private final String input;
  private final Map<String, Type> symbols;
  private final Map<Node, int[]> spans = new IdentityHashMap<>();
  private int offset, nodes, recursion;

  private Formula(String input, Map<String, Type> symbols) {
    this.input = input;
    this.symbols = symbols;
  }

  public static Compiled compile(String source) {
    return compile(source, SYMBOLS);
  }

  public static Compiled compile(String source, Map<String, Type> symbols) {
    return compile(source, symbols, true);
  }

  /** Graph values use differences of the completed graph; do not generate unused gradients. */
  public static Compiled compileValue(String source, Map<String, Type> symbols) {
    return compile(source, symbols, false);
  }

  private static Compiled compile(String source, Map<String, Type> symbols, boolean derivatives) {
    if (source == null || source.length() > 8192)
      throw new IllegalArgumentException("Formula exceeds 8192 characters");
    Formula p = new Formula(source, symbols);
    Node root = p.expression();
    p.space();
    if (p.offset != source.length()) throw p.error("Unexpected token");
    if (root.cost() > 8192 || root.depth() > 32)
      throw p.error(root, "Expanded formula cost or depth exceeds limit");
    String expression = root.code();
    int expressionBytes = GeneratedSource.bytes(expression);
    if (expressionBytes > GeneratedSource.LIMIT)
      throw p.error(root, "Generated expression exceeds 64 KiB");
    Set<String> dependencies = new TreeSet<>();
    collect(root, dependencies);
    String derivative = null;
    try {
      if (derivatives && supportsGradient(root))
        derivative = gradient(root, GeneratedSource.LIMIT - expressionBytes);
    } catch (GeneratedSource.Limit limit) {
      throw p.error(root, limit.getMessage());
    }
    return new Compiled(root, expression, derivative, derivative != null, Set.copyOf(dependencies));
  }

  private Node mark(Node n, int start) {
    spans.put(n, new int[] {start, offset});
    return n;
  }

  private Node add(Node n, int start) {
    return add(n, start, offset);
  }

  private Node add(Node n, int start, int end) {
    if (++nodes > 256) throw error(start, end, "Formula exceeds 256 syntax nodes");
    spans.put(n, new int[] {start, end});
    return n;
  }

  private int start(Node n) {
    return spans.get(n)[0];
  }

  private Node expression() {
    if (++recursion > 32) throw error("Formula nesting exceeds 32");
    Node n = product();
    while (true) {
      if (take('+')) {
        int at = offset - 1;
        n = binary('+', n, product(), at);
      } else if (take('-')) {
        int at = offset - 1;
        n = binary('-', n, product(), at);
      } else break;
    }
    recursion--;
    return n;
  }

  private Node product() {
    Node n = unary();
    while (true) {
      if (take('*')) {
        int at = offset - 1;
        n = binary('*', n, unary(), at);
      } else if (take('/')) {
        int at = offset - 1;
        n = binary('/', n, unary(), at);
      } else return n;
    }
  }

  private Node unary() {
    space();
    int start = offset;
    if (take('+')) return unaryNode('+', start);
    if (take('-')) return unaryNode('-', start);
    return postfix();
  }

  private Node unaryNode(char op, int start) {
    Node child = unaryBounded();
    return add(new Unary(op, child), start, spans.get(child)[1]);
  }

  private Node unaryBounded() {
    if (++recursion > 32) throw error("Formula nesting exceeds 32");
    Node n = unary();
    recursion--;
    return n;
  }

  private Node binary(char op, Node a, Node b, int at) {
    Type type;
    try {
      type = compatible(a.type(), b.type());
    } catch (IllegalArgumentException e) {
      throw error(at, at + 1, e.getMessage());
    }
    if (a instanceof NumberNode x && b instanceof NumberNode y) {
      double v =
          switch (op) {
            case '+' -> x.value() + y.value();
            case '-' -> x.value() - y.value();
            case '*' -> x.value() * y.value();
            default -> x.value() / y.value();
          };
      if (Double.isFinite(v)) return add(new NumberNode(v), start(a), spans.get(b)[1]);
    }
    return add(new Binary(op, a, b, type), start(a), spans.get(b)[1]);
  }

  private static Type compatible(Type a, Type b) {
    if (a == b) return a;
    if (a == Type.FLOAT) return b;
    if (b == Type.FLOAT) return a;
    throw new IllegalArgumentException("Scalar/vector size mismatch: " + a + " and " + b);
  }

  private Type compatible(Node a, Node b) {
    try {
      return compatible(a.type(), b.type());
    } catch (IllegalArgumentException e) {
      throw error(b, e.getMessage());
    }
  }

  private Node postfix() {
    Node n = primary();
    while (take('.')) {
      space();
      int at = offset;
      String sw = identifier();
      if (sw.length() < 1 || sw.length() > 4) throw error(at, offset, "Invalid swizzle");
      for (char c : sw.toCharArray())
        if ("xyzw".indexOf(c) < 0 || "xyzw".indexOf(c) >= n.type().width)
          throw error(at, offset, "Swizzle exceeds vector width");
      n = add(new Swizzle(n, sw, Type.values()[sw.length() - 1]), start(n));
    }
    return n;
  }

  private Node primary() {
    space();
    int start = offset;
    if (take('(')) {
      Node n = expression();
      expect(')');
      return mark(n, start);
    }
    if (offset < input.length()
        && (Character.isDigit(input.charAt(offset)) || input.charAt(offset) == '.')) {
      boolean exponent = false;
      while (offset < input.length()) {
        char c = input.charAt(offset);
        if (Character.isDigit(c) || c == '.') {
          offset++;
          continue;
        }
        if ((c == 'e' || c == 'E') && !exponent) {
          exponent = true;
          offset++;
          if (offset < input.length()
              && (input.charAt(offset) == '+' || input.charAt(offset) == '-')) offset++;
          continue;
        }
        break;
      }
      try {
        double v = Double.parseDouble(input.substring(start, offset));
        if (!Double.isFinite(v) || Math.abs(v) > 1e12)
          throw error(start, offset, "Numeric literal is outside range");
        return add(new NumberNode(v), start);
      } catch (NumberFormatException e) {
        throw error(start, offset, "Invalid number");
      }
    }
    String name = identifier();
    int nameEnd = offset;
    if (take('(')) {
      List<Node> args = new ArrayList<>();
      if (!take(')')) {
        do {
          args.add(expression());
          if (args.size() > 4) throw error(args.getLast(), "Too many function arguments");
        } while (take(','));
        expect(')');
      }
      return call(name, args, start, nameEnd);
    }
    if (name.equals("pi")) return add(new NumberNode(Math.PI), start, nameEnd);
    Type type = symbols.get(name);
    if (type == null) throw error(start, nameEnd, "Unknown symbol: " + name);
    return add(new Symbol(name, type), start, nameEnd);
  }

  private Node call(String name, List<Node> args, int start, int nameEnd) {
    Type type;
    switch (name) {
      case "vec2", "vec3", "vec4" -> {
        int width = name.charAt(3) - '0', total = args.stream().mapToInt(n -> n.type().width).sum();
        if (!(total == width || args.size() == 1 && args.getFirst().type() == Type.FLOAT))
          throw error(start, offset, "Invalid vector constructor");
        type = Type.values()[width - 1];
      }
      case "sin", "cos", "abs", "floor", "fract", "exp", "sqrtSafe", "normalizeSafe" -> {
        arity(args, 1, start);
        type = args.getFirst().type();
      }
      case "length" -> {
        arity(args, 1, start);
        type = Type.FLOAT;
      }
      case "noise3d", "fbm" -> {
        arity(args, 1, start);
        require(args.getFirst(), Type.VEC3);
        type = Type.FLOAT;
      }
      case "cellular" -> {
        arity(args, 1, start);
        require(args.getFirst(), Type.VEC2);
        type = Type.VEC3;
      }
      case "gabor" -> {
        arity(args, 4, start);
        require(args.getFirst(), Type.VEC2);
        for (int i = 1; i < 4; i++) require(args.get(i), Type.FLOAT);
        type = Type.FLOAT;
      }
      case "dot" -> {
        arity(args, 2, start);
        if (args.get(0).type() != args.get(1).type())
          throw error(args.get(1), "dot requires equal widths");
        type = Type.FLOAT;
      }
      case "min", "max", "safeDiv", "powSafe" -> {
        arity(args, 2, start);
        type = compatible(args.get(0), args.get(1));
      }
      case "mix", "clamp", "smoothstep" -> {
        arity(args, 3, start);
        type = compatible(args.get(0), args.get(1));
        try {
          type = compatible(type, args.get(2).type());
        } catch (IllegalArgumentException e) {
          throw error(args.get(2), e.getMessage());
        }
      }
      default -> throw error(start, nameEnd, "Unknown function: " + name);
    }
    return add(new Call(name, List.copyOf(args), type), start);
  }

  private void arity(List<Node> args, int count, int start) {
    if (args.size() != count) throw error(start, offset, "Expected " + count + " arguments");
  }

  private void require(Node n, Type type) {
    if (n.type() != type) throw error(n, "Expected " + type + ", found " + n.type());
  }

  private void space() {
    while (offset < input.length() && Character.isWhitespace(input.charAt(offset))) offset++;
  }

  private boolean take(char c) {
    space();
    if (offset < input.length() && input.charAt(offset) == c) {
      offset++;
      return true;
    }
    return false;
  }

  private void expect(char c) {
    if (!take(c)) throw error("Expected '" + c + "'");
  }

  private String identifier() {
    space();
    int start = offset;
    while (offset < input.length()
        && (Character.isLetterOrDigit(input.charAt(offset)) || input.charAt(offset) == '_'))
      offset++;
    if (start == offset || !Character.isLetter(input.charAt(start)))
      throw error(start, Math.max(offset, Math.min(input.length(), start + 1)), "Expected a name");
    return input.substring(start, offset);
  }

  private AuthoringDiagnostic error(Node n, String text) {
    int[] span = spans.get(n);
    return error(span[0], span[1], text);
  }

  private AuthoringDiagnostic error(int start, int end, String text) {
    return AuthoringDiagnostic.at(input, start, end, text);
  }

  private AuthoringDiagnostic error(String text) {
    return error(offset, Math.min(input.length(), offset + 1), text);
  }

  private static void collect(Node n, Set<String> out) {
    switch (n) {
      case Symbol s -> out.add(s.name());
      case Unary u -> collect(u.child(), out);
      case Binary b -> {
        collect(b.left(), out);
        collect(b.right(), out);
      }
      case Call c -> c.args().forEach(a -> collect(a, out));
      case Swizzle s -> collect(s.child(), out);
      default -> {}
    }
  }

  /** Conservative scalar AD subset. Check support before generating any derivative source. */
  private static boolean supportsGradient(Node n) {
    if (n.type() != Type.FLOAT) return false;
    return switch (n) {
      case NumberNode ignored -> true;
      case Symbol ignored -> true;
      case Reference ignored -> false;
      case Swizzle sw -> sw.child() instanceof Symbol symbol && symbol.name().equals("p");
      case Unary unary -> supportsGradient(unary.child());
      case Binary binary -> supportsGradient(binary.left()) && supportsGradient(binary.right());
      case Call call ->
          call.args().size() == 1
              && Set.of("sin", "cos").contains(call.name())
              && supportsGradient(call.args().getFirst());
    };
  }

  private static String gradient(Node n, int remainingBytes) {
    return switch (n) {
      case NumberNode ignored -> GeneratedSource.join(remainingBytes, "vec2(0.)");
      case Symbol ignored -> GeneratedSource.join(remainingBytes, "vec2(0.)");
      case Swizzle sw ->
          GeneratedSource.join(
              remainingBytes, sw.components().equals("x") ? "vec2(1.,0.)" : "vec2(0.,1.)");
      case Unary unary ->
          GeneratedSource.join(
              remainingBytes,
              "(",
              String.valueOf(unary.op()),
              gradient(unary.child(), remainingBytes),
              ")");
      case Binary binary -> {
        String left = gradient(binary.left(), remainingBytes);
        String right = gradient(binary.right(), remainingBytes);
        yield switch (binary.op()) {
          case '+', '-' ->
              GeneratedSource.join(
                  remainingBytes, "(", left, String.valueOf(binary.op()), right, ")");
          case '*' ->
              GeneratedSource.join(
                  remainingBytes,
                  "(",
                  left,
                  "*",
                  binary.right().code(),
                  "+",
                  right,
                  "*",
                  binary.left().code(),
                  ")");
          case '/' ->
              GeneratedSource.join(
                  remainingBytes,
                  "((",
                  left,
                  "*",
                  binary.right().code(),
                  "-",
                  right,
                  "*",
                  binary.left().code(),
                  ")/(",
                  binary.right().code(),
                  "*",
                  binary.right().code(),
                  "))");
          default -> throw new IllegalStateException("Unsupported gradient operator");
        };
      }
      case Call call -> {
        String child = call.args().getFirst().code();
        String derivative = gradient(call.args().getFirst(), remainingBytes);
        yield call.name().equals("sin")
            ? GeneratedSource.join(remainingBytes, "(cos(", child, ")*", derivative, ")")
            : GeneratedSource.join(remainingBytes, "(-sin(", child, ")*", derivative, ")");
      }
      default -> throw new IllegalStateException("Unsupported gradient node");
    };
  }
}
