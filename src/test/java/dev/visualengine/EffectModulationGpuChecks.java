package dev.visualengine;

import static org.lwjgl.opengl.GL43C.*;

import dev.visualengine.export.*;
import dev.visualengine.gpu.*;
import dev.visualengine.model.Settings;
import dev.visualengine.presets.PresetIO;
import dev.visualengine.time.*;
import java.util.*;

public final class EffectModulationGpuChecks {
  private static int count;

  private static void check(boolean ok, String text) {
    count++;
    if (!ok) throw new AssertionError(text);
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
    Settings s = EffectModulationChecks.settings();
    ControlSignals signals = new ControlSignals();
    try (Renderer mapped = new Renderer(new ShaderSources(null));
        Renderer explicit = new Renderer(new ShaderSources(null))) {
      mapped.reset(s);
      explicit.reset(s);
      int program = mapped.materialProgramId();
      var cache = mapped.shaderCacheStats();
      Settings active = null;
      int[] previous = null, finalImage = null;
      for (int tick = 1; tick <= 24; tick++) {
        if (tick == 9) s = EffectModulationChecks.edited(s, 1);
        if (tick == 17) s = EffectModulationChecks.edited(s, 2);
        active = signals.tick(s, tick * Playback.DT);
        Settings resolved = active.copy();
        resolved.modulations.clear();
        mapped.tick(active, tick * Playback.DT, 1.5, .2, -.1, true);
        explicit.tick(resolved, tick * Playback.DT, 1.5, .2, -.1, true);
        if (tick % 8 == 0) {
          mapped.render(active, tick * Playback.DT, 96, 64, .2, -.1, true);
          finalImage = image();
          explicit.render(resolved, tick * Playback.DT, 96, 64, .2, -.1, true);
          check(
              Arrays.equals(finalImage, image())
                  && same(mapped.snapshot(active, tick), explicit.snapshot(resolved, tick)),
              "custom modulation equals explicit resolved uniforms in image and every GPU byte at"
                  + " tick "
                  + tick);
          if (tick == 8) previous = finalImage;
        }
      }
      check(
          mapped.materialProgramId() == program && mapped.shaderCacheStats().equals(cache),
          "modulated numeric values and mapping edits never look up or rebuild a material program");
      check(!Arrays.equals(previous, finalImage), "custom sources animate the rendered material");
      mapped.render(signals.frame(s), 24 * Playback.DT, 96, 64, .2, -.1, true);
      check(
          Arrays.equals(finalImage, image()),
          "held custom modulation values preserve the paused image");
      GpuSnapshot before = mapped.snapshot(active, 24);
      mapped.captureMap(
          active,
          24 * Playback.DT,
          TextureMap.Kind.HEIGHT,
          new CaptureSize(96, 64),
          TextureMap.Kind.HEIGHT.initialRange,
          .2,
          -.1,
          true);
      check(
          same(before, mapped.snapshot(active, 24)) && Arrays.equals(finalImage, image()),
          "map capture preserves modulated scene state and finished framebuffer");
      Settings bad = s.copy();
      bad.modulations.set(
          0,
          EffectModulationChecks.mapping(
              "bad", "effect:offset.w", Modulation.Source.SINE, Modulation.Mode.ADD, 4, 0, 1, 0));
      boolean rejected = false;
      try {
        mapped.configure(bad);
      } catch (IllegalArgumentException expected) {
        rejected = true;
      }
      check(
          rejected
              && mapped.materialProgramId() == program
              && mapped.shaderCacheStats().equals(cache)
              && same(before, mapped.snapshot(active, 24))
              && Arrays.equals(finalImage, image()),
          "invalid contextual destination preserves the working program, cache, GPU state and"
              + " image");
    }
    check(
        glGetError() == GL_NO_ERROR,
        "custom modulation and explicit-uniform comparison leave no GL errors");
    return count;
  }
}
