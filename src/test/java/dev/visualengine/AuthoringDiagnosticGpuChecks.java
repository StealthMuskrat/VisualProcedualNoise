package dev.visualengine;

import static org.lwjgl.opengl.GL43C.*;

import dev.visualengine.authoring.*;
import dev.visualengine.export.Capture;
import dev.visualengine.gpu.*;
import dev.visualengine.model.Settings;
import dev.visualengine.presets.PresetIO;
import dev.visualengine.time.*;
import java.util.*;

public final class AuthoringDiagnosticGpuChecks {
  private static int count;

  private static void check(boolean ok, String message) {
    count++;
    if (!ok) throw new AssertionError(message);
  }

  private static int[] image() {
    return Capture.read(96, 64).getRGB(0, 0, 96, 64, null, 0, 96);
  }

  private static boolean same(GpuSnapshot a, GpuSnapshot b) {
    return a.blobs().keySet().equals(b.blobs().keySet())
        && a.blobs().entrySet().stream()
            .allMatch(e -> Arrays.equals(e.getValue(), b.blobs().get(e.getKey())))
        && PresetIO.JSON
            .toJsonTree(a.simulation())
            .equals(PresetIO.JSON.toJsonTree(b.simulation()));
  }

  public static int run() {
    count = 0;
    Settings settings = EffectModulationChecks.settings();
    ControlSignals signals = new ControlSignals();
    try (Renderer renderer = new Renderer(new ShaderSources(null))) {
      renderer.reset(settings);
      Settings active = null;
      for (int tick = 1; tick <= 16; tick++) {
        active = signals.tick(settings, tick * Playback.DT);
        renderer.tick(active, tick * Playback.DT, 1.5, .2, -.1, true);
      }
      renderer.render(active, 16 * Playback.DT, 96, 64, .2, -.1, true);
      int[] pixels = image();
      var state = renderer.snapshot(active, 16);
      var controls = signals.snapshot(settings);
      var cache = renderer.shaderCacheStats();
      int program = renderer.materialProgramId();
      long bytes = renderer.bytes();
      for (int i = 0; i < 3; i++) {
        Settings bad = active.copy();
        if (i == 0) bad.expression = "sin(p.x)\n + missing";
        else if (i == 1) bad.expression = "vec2(1)";
        else {
          bad.graphEnabled = true;
          bad.graph = new EffectGraph();
          bad.graph.nodes =
              new ArrayList<>(
                  List.of(
                      new EffectGraph.Node(
                          "field",
                          EffectGraph.Kind.FORMULA,
                          List.of(),
                          "noise3d(p)",
                          0,
                          0,
                          false,
                          "",
                          "")));
        }
        AuthoringDiagnostic problem = null;
        try {
          renderer.configure(bad);
        } catch (AuthoringDiagnostic e) {
          problem = e;
        }
        check(
            problem != null
                && problem.span() != null
                && (i != 2 || problem.nodePath().equals(List.of("field"))),
            "candidate failure exposes the source/node diagnostic " + i);
        check(
            renderer.materialProgramId() == program
                && cache.equals(renderer.shaderCacheStats())
                && renderer.bytes() == bytes
                && Arrays.equals(pixels, image())
                && same(state, renderer.snapshot(active, 16))
                && controls.equals(signals.snapshot(settings)),
            "rejected authoring preserves image, every GPU byte, resolved controls, material and"
                + " storage "
                + i);
      }
      renderer.render(active, 16 * Playback.DT, 96, 64, .2, -.1, true);
      check(
          Arrays.equals(pixels, image()) && same(state, renderer.snapshot(active, 16)),
          "last good scene still draws after repeated located failures");
      Settings corrected = active.copy();
      corrected.expression = "sin(p.x * amplitude + phase)";
      renderer.render(corrected, 16 * Playback.DT, 96, 64, .2, -.1, true);
      check(
          !Arrays.equals(pixels, image()) && same(state, renderer.snapshot(active, 16)),
          "corrected source activates while persistent simulation stays exact");
    }
    check(glGetError() == GL_NO_ERROR, "authoring rejection and correction leave no GL errors");
    return count;
  }
}
