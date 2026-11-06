package dev.visualengine;

import dev.visualengine.authoring.*;
import java.util.*;

public final class GaborChecks {
  private static int count;

  private static void check(boolean ok, String label) {
    count++;
    if (!ok) throw new AssertionError(label);
  }

  private static void rejects(String expression) {
    try {
      Formula.compile(expression);
    } catch (IllegalArgumentException expected) {
      count++;
      return;
    }
    throw new AssertionError("Accepted invalid Gabor expression: " + expression);
  }

  static int run() {
    var compiled = Formula.compile("gabor(p, phase, frequency, 1.0)");
    check(
        compiled.root().type() == Formula.Type.FLOAT
            && !compiled.analyticGradient()
            && compiled.dependencies().containsAll(Set.of("p", "phase", "frequency")),
        "Gabor typing, dependencies and complete-field derivative fallback");
    rejects("gabor(p, 0, 2)");
    rejects("gabor(vec3(p,0),0,2,1)");
    rejects("gabor(p,vec2(0),2,1)");
    rejects("gabor(p,0,vec2(2),1)");
    rejects("gabor(p,0,2,vec2(1))");
    rejects(String.join("+", Collections.nCopies(4, "gabor(p,0,2,1)")));
    var three = Formula.compile(String.join("+", Collections.nCopies(3, "gabor(p,0,2,1)")));
    check(
        three.root().cost() < 8192 && three.root().cost() > 6000,
        "Gabor calls consume bounded expansion budget");
    EffectGraph graph = new EffectGraph();
    graph.nodes =
        new ArrayList<>(
            List.of(
                new EffectGraph.Node(
                    "field",
                    EffectGraph.Kind.FORMULA,
                    List.of(),
                    "gabor(p,0.4,3,1)",
                    0,
                    0,
                    false,
                    "",
                    "")));
    graph.output = "field";
    check(
        GraphCompiler.compile(graph).function().contains("gabor("),
        "Gabor accepted in typed graph formulas");
    return count;
  }

  /** Double-precision mathematical reference, independent of rasterization and GLSL float math. */
  static double reference(
      double px, double py, double angle, double frequency, double bandwidth, int seed) {
    double a = Math.clamp(bandwidth, .25, 4), f = Math.clamp(frequency, 0, 16);
    double qx = Math.clamp(px, -262144, 262144) * a, qy = Math.clamp(py, -262144, 262144) * a;
    int cx = (int) Math.floor(qx), cy = (int) Math.floor(qy);
    double sum = 0;
    for (int y = -2; y <= 2; y++)
      for (int x = -2; x <= 2; x++) {
        int base = hash((cx + x) ^ hash(cy + y) ^ hash(1195463250) ^ seed);
        for (int i = 0; i < 2; i++) {
          int hx = hash(base ^ ((i + 1) * 0x9e3779b9)), hy = hash(hx ^ 0x68bc21eb);
          double dx = x + Integer.toUnsignedLong(hx >>> 8) / 16777216. - (qx - cx);
          double dy = y + Integer.toUnsignedLong(hy >>> 8) / 16777216. - (qy - cy);
          double r2 = dx * dx + dy * dy;
          if (r2 < 4) {
            double t = Math.clamp((r2 - 2.25) / 1.75, 0, 1), taper = 1 - t * t * (3 - 2 * t);
            double weight = (hash(hy ^ 0x02e5be93) & 1) == 0 ? -1 : 1;
            sum +=
                weight
                    * Math.exp(-Math.PI * r2)
                    * taper
                    * Math.cos(
                        2 * Math.PI * (f / a) * (dx * Math.cos(angle) + dy * Math.sin(angle)));
          }
        }
      }
    return .5 * sum;
  }

  private static int hash(int n) {
    n ^= n >>> 16;
    n *= 0x7feb352d;
    n ^= n >>> 15;
    n *= 0x846ca68b;
    return n ^ (n >>> 16);
  }
}
