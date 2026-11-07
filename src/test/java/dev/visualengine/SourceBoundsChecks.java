package dev.visualengine;

import dev.visualengine.authoring.*;
import dev.visualengine.gpu.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class SourceBoundsChecks {
  private static int count;
  public static final String CONTROL = "a".repeat(48);

  private static void check(boolean ok, String text) {
    count++;
    if (!ok) throw new AssertionError(text);
  }

  private static IllegalArgumentException rejects(Runnable action, String text) {
    try {
      action.run();
    } catch (IllegalArgumentException e) {
      check(e.getMessage().contains(text), "source bound diagnostic: " + text);
      return e;
    }
    throw new AssertionError("Accepted " + text);
  }

  public static List<EffectControl> controls() {
    return List.of(
        new EffectControl(
            CONTROL,
            "Denominator",
            Formula.Type.FLOAT,
            "",
            List.of(1.),
            .5,
            2,
            .01,
            EffectControl.Scale.LINEAR,
            EffectControl.Update.UNIFORM));
  }

  public static String expression(int divisions) {
    return "p.x" + ("/((" + CONTROL + "+" + CONTROL + ")+" + CONTROL + ")").repeat(divisions);
  }

  public static EffectGraph graph(String expression, boolean expressionNode) {
    EffectGraph graph = new EffectGraph();
    graph.nodes = new ArrayList<>();
    if (expressionNode)
      graph.nodes.add(
          new EffectGraph.Node(
              "base", EffectGraph.Kind.FORMULA, List.of(), "0", 0, 0, false, "", ""));
    graph.nodes.add(
        new EffectGraph.Node(
            "field",
            expressionNode ? EffectGraph.Kind.EXPRESSION : EffectGraph.Kind.FORMULA,
            expressionNode ? List.of("base") : List.of(),
            expression,
            70,
            70,
            false,
            "",
            ""));
    return graph;
  }

  public static int run() throws Exception {
    count = 0;
    var small = EffectControl.compileField(expression(24), controls());
    check(
        small.analyticGradient() && small.expression().length() + small.gradient().length() < 65536,
        "large supported derivative fits the combined source budget");
    String large = expression(26);
    var error =
        rejects(
            () -> EffectControl.compileField(large, controls()),
            "expression and gradient exceed 64 KiB");
    check(
        error instanceof AuthoringDiagnostic located
            && located.span().start() == 0
            && located.span().end() == large.length(),
        "derivative bound selects the original complete formula");
    var value = Formula.compileValue(large, EffectControl.symbols(controls()));
    check(
        value.gradient() == null
            && !value.analyticGradient()
            && value.expression().length() < 65536
            && value.dependencies().contains(CONTROL),
        "value-only compilation omits unused derivative work without changing dependencies");
    var fallback = EffectControl.compileField("abs(" + large + ")", controls());
    check(
        !fallback.analyticGradient() && fallback.gradient() == null,
        "unsupported composition uses the existing full-field fallback without building a discarded"
            + " derivative");
    check(
        GraphCompiler.compile(graph(large, false), controls()).function().contains("node_field"),
        "graph formula compiles only its requested value");
    check(
        GraphCompiler.compile(graph(large, true), controls()).function().contains("node_field"),
        "graph expression compiles only its requested value");
    EffectGraph bounded =
        graph(
            "gabor(p,0.5,frequency,1.) + gabor(p,1.,frequency,1.) + gabor(p,1.5,frequency,1.) +"
                + " gabor(p,2.,frequency,1.)",
            false);
    rejects(() -> GraphCompiler.compile(bounded), "cost");
    rejects(
        () -> Formula.compileValue("(".repeat(40) + "1" + ")".repeat(40), Formula.SYMBOLS),
        "nesting");
    String exact = "x".repeat(ShaderSources.EXPANDED_BYTES);
    check(
        ShaderSources.sourceBytes(exact) == ShaderSources.EXPANDED_BYTES,
        "final stage accepts its exact byte limit");
    rejects(() -> ShaderSources.sourceBytes(exact + "x"), "exceeds 1 MiB");
    String unicode = "x".repeat(ShaderSources.EXPANDED_BYTES - 6) + "é😀";
    check(
        ShaderSources.sourceBytes(unicode) == unicode.getBytes(StandardCharsets.UTF_8).length
            && ShaderSources.sourceBytes("é😀") == 6,
        "final stage counts UTF-8 bytes rather than Java characters");
    rejects(() -> ShaderSources.sourceBytes(unicode + "x"), "exceeds 1 MiB");
    rejects(() -> ShaderSources.sourceBytes("a\0b"), "NUL");
    rejects(() -> ShaderSources.sourceBytes(null), "Missing");
    rejects(() -> ShaderSources.sourceBytes("\ud800"), "Unicode");
    rejects(() -> ShaderSources.sourceBytes("\udc00"), "Unicode");
    String inserted =
        ShaderSources.replace("prefix@", "@", "x".repeat(ShaderSources.EXPANDED_BYTES - 6));
    check(
        ShaderSources.sourceBytes(inserted) == ShaderSources.EXPANDED_BYTES
            && inserted.startsWith("prefix"),
        "insertion accepts the exact final bound including retained library text");
    rejects(() -> ShaderSources.replace("prefix@", "@", exact), "after insertion");
    rejects(
        () -> ShaderSources.replace("@@", "@", "x".repeat(ShaderSources.EXPANDED_BYTES / 2 + 1)),
        "after insertion");
    check(
        ShaderSources.replace("@@", "@", "é").equals("éé")
            && ShaderSources.replace("no marker", "@", exact + "x").equals("no marker"),
        "replacement preserves all occurrences and leaves absent markers unchanged");
    rejects(() -> ShaderSources.replace("abc", "", "x"), "ASCII marker");
    rejects(() -> ShaderSources.replace("abc", "é", "x"), "ASCII marker");
    rejects(() -> new Program("vertex", exact + "x"), "exceeds 1 MiB");
    rejects(() -> Program.compute(exact + "x"), "exceeds 1 MiB");
    rejects(() -> new Program("vertex", "fragment\0"), "NUL");
    return count;
  }
}
