package dev.visualengine;

import dev.visualengine.authoring.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

public final class AuthoringDiagnosticChecks {
  private static int count;

  private static void check(boolean ok, String message) {
    count++;
    if (!ok) throw new AssertionError(message);
  }

  private static AuthoringDiagnostic failure(Runnable action) {
    try {
      action.run();
    } catch (AuthoringDiagnostic e) {
      return e;
    }
    throw new AssertionError("Expected a located authoring failure");
  }

  private static void span(String source, String token, String detail) {
    var error = failure(() -> Formula.compile(source));
    int start = source.indexOf(token);
    check(
        error.span().start() == start
            && error.span().end() == start + token.length()
            && error.detail().contains(detail)
            && error.nodePath().isEmpty(),
        "exact formula span: " + source);
  }

  private static EffectGraph.Node node(
      String id, EffectGraph.Kind kind, List<String> inputs, String expression) {
    return new EffectGraph.Node(id, kind, inputs, expression, 0, 0, false, "", "");
  }

  private static EffectGraph graph(String output, EffectGraph.Node... nodes) {
    EffectGraph result = new EffectGraph();
    result.output = output;
    result.nodes = new ArrayList<>(List.of(nodes));
    return result;
  }

  private static String hash(String value) throws Exception {
    return HexFormat.of()
        .formatHex(
            MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
  }

  public static int run() throws Exception {
    count = 0;
    span("sin(p.x) + missing  ", "missing", "Unknown symbol");
    span("missingFunction(p)", "missingFunction", "Unknown function");
    span("p.z", "z", "vector width");
    span("p.xyzxy", "xyzxy", "Invalid swizzle");
    span("1e999", "1e999", "outside range");
    span("1.2.3", "1.2.3", "Invalid number");
    span("vec2(1) + vec3(1)", "+", "size mismatch");
    span("noise3d(p   )", "p", "Expected VEC3");
    span("dot(p, vec3(1))", "vec3(1)", "equal widths");
    span("min(p, vec3(1))", "vec3(1)", "size mismatch");
    span("mix(p, p, vec3(1))", "vec3(1)", "size mismatch");
    span("sin(1, 2)", "sin(1, 2)", "Expected 1 arguments");
    span("vec2(1,2,3)", "vec2(1,2,3)", "vector constructor");
    span("sin(noise3d(vec2(1)))", "vec2(1)", "Expected VEC3");
    span("sin(p.x); discard", ";", "Unexpected token");
    var eof = failure(() -> Formula.compile("sin(p.x"));
    check(
        eof.span().start() == 7 && eof.span().end() == 7,
        "missing delimiter marks EOF without exceeding source");
    var multiline = failure(() -> Formula.compile("sin(p.x)\r\n + missing"));
    check(
        multiline.span().line() == 2
            && multiline.span().column() == 4
            && multiline.getMessage().contains("line 2, column 4"),
        "CRLF lines are counted once");
    var carriage = failure(() -> Formula.compile("1\r+ nope"));
    check(
        carriage.span().line() == 2 && carriage.span().column() == 3,
        "standalone carriage return begins a line");
    var nested = failure(() -> Formula.compile("(".repeat(40) + "1" + ")".repeat(40)));
    check(
        nested.span().start() < 40 && nested.span().end() <= 81,
        "nesting errors remain bounded and located");
    var folded = failure(() -> Formula.compile("noise3d(2 + 3 * 4)"));
    check(
        folded.span().start() == 8 && folded.span().end() == 17,
        "constant folding retains the complete operand span");
    var scalar = failure(() -> EffectControl.compileField("vec2(1)", List.of()));
    check(
        scalar.span().start() == 0
            && scalar.span().end() == 7
            && scalar.detail().contains("scalar"),
        "field output type failure locates the entire expression");
    EffectGraph bad =
        graph("field", node("field", EffectGraph.Kind.FORMULA, List.of(), "sin(p.x) + missing"));
    var direct = failure(() -> GraphCompiler.compile(bad));
    check(
        direct.nodePath().equals(List.of("field"))
            && direct.span().start() == 11
            && direct.getCause() instanceof AuthoringDiagnostic,
        "graph formula keeps original token and node context");
    EffectGraph links =
        graph("sum", node("sum", EffectGraph.Kind.ADD, List.of("gone", "gone"), "0"));
    var missing = failure(() -> GraphCompiler.compile(links));
    check(
        missing.nodePath().equals(List.of("sum"))
            && missing.span() == null
            && missing.detail().contains("gone"),
        "missing links locate their consumer without inventing a formula span");
    EffectGraph cycle =
        graph(
            "a",
            node("a", EffectGraph.Kind.ALIAS, List.of("b"), "0"),
            node("b", EffectGraph.Kind.ALIAS, List.of("a"), "0"));
    check(
        failure(() -> GraphCompiler.compile(cycle)).nodePath().equals(List.of("a")),
        "cycle reports a member node");
    EffectGraph vector =
        graph("point", node("point", EffectGraph.Kind.COORDINATES, List.of(), "0"));
    check(
        failure(() -> GraphCompiler.compile(vector)).nodePath().equals(List.of("point")),
        "non-scalar graph output is located");
    EffectGraph disconnected =
        graph("good", node("good", EffectGraph.Kind.FORMULA, List.of(), "0"), bad.nodes.getFirst());
    check(
        failure(() -> GraphCompiler.compile(disconnected)).nodePath().equals(List.of("field")),
        "disconnected authoring fragments retain validation and diagnostics");
    EffectGraph grouped = AuthoringChecks.groupGraph();
    grouped.nodes.set(0, node("coord", EffectGraph.Kind.FORMULA, List.of(), "1"));
    check(
        failure(
                () ->
                    GraphCompiler.compile(
                        grouped, List.of(AuthoringChecks.scalar(), AuthoringChecks.vector())))
            .nodePath()
            .equals(List.of("instance")),
        "generated group argument checks point to the original instance");
    EffectGraph inner =
        graph(
            "outer",
            new EffectGraph.Node(
                "outer",
                EffectGraph.Kind.GROUP,
                List.of(),
                "0",
                0,
                0,
                false,
                "",
                "",
                "local.outer@1.0.0"));
    inner.groups.add(
        new GraphTemplates.Group(
            "local.inner", "1.0.0", List.of(), bad.nodes, "field", GraphCompiler.Port.SCALAR));
    inner.groups.add(
        new GraphTemplates.Group(
            "local.outer",
            "1.0.0",
            List.of(),
            List.of(
                new EffectGraph.Node(
                    "inner",
                    EffectGraph.Kind.GROUP,
                    List.of(),
                    "0",
                    0,
                    0,
                    false,
                    "",
                    "",
                    "local.inner@1.0.0")),
            "inner",
            GraphCompiler.Port.SCALAR));
    var groupFailure = failure(() -> GraphCompiler.compile(inner));
    check(
        groupFailure.nodePath().equals(List.of("outer", "inner", "field"))
            && groupFailure.span().start() == 11
            && groupFailure.getMessage().contains("outer / inner / field"),
        "nested groups report original instance path and inner expression span");
    try {
      groupFailure.nodePath().add("mutated");
      throw new AssertionError("Mutable diagnostic path");
    } catch (UnsupportedOperationException expected) {
      count++;
    }
    var baseline =
        com.google.gson.JsonParser.parseString(
                Files.readString(Path.of("src/test/resources/authoring-source-baseline.json")))
            .getAsJsonObject();
    check(
        baseline.get("engineVersion").getAsString().equals("0.19.0"),
        "source reference comes from the previous release");
    for (var item : baseline.getAsJsonArray("formulas")) {
      var entry = item.getAsJsonObject();
      var f = Formula.compile(entry.get("source").getAsString());
      check(
          hash(f.expression()
                  + "\n"
                  + f.gradient()
                  + "\n"
                  + f.analyticGradient()
                  + "\n"
                  + String.join(",", new TreeSet<>(f.dependencies())))
              .equals(entry.get("sha256").getAsString()),
          "formula source, gradient and dependency identity match v0.19");
    }
    var pack = EffectPackage.load(Path.of("examples/effects/ported-ripple.json"));
    check(
        hash(GraphCompiler.compile(pack.graph, pack.controls).function())
            .equals(baseline.get("groupFunctionSha256").getAsString()),
        "expanded group source identity matches v0.19");
    return count;
  }
}
