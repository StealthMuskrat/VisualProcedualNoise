package dev.visualengine;

import static org.lwjgl.opengl.GL43C.*;

import dev.visualengine.export.Capture;
import dev.visualengine.gpu.*;
import dev.visualengine.model.Settings;
import dev.visualengine.presets.PresetIO;
import dev.visualengine.time.*;
import java.nio.file.*;
import java.util.*;

public final class GraphPreviewGpuChecks {
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

  public static int run() throws Exception {
    count = 0;
    var fixture =
        com.google.gson.JsonParser.parseString(
                Files.readString(Path.of("build/reports/graph-preview-fixture.json")))
            .getAsJsonObject();
    for (String mode : GraphPreviewChecks.modes().keySet()) {
      Settings original = PresetIO.parse(fixture.get("original").toString());
      Settings preview = PresetIO.parse(fixture.get(mode).toString());
      Settings expected = PresetIO.parse(fixture.get(mode + "Expected").toString());
      var signals = new ControlSignals();
      try (Renderer renderer = new Renderer(new ShaderSources(null))) {
        renderer.reset(original);
        Settings active = null;
        for (int tick = 1; tick <= 16; tick++) {
          active = signals.tick(original, tick * Playback.DT);
          renderer.tick(active, tick * Playback.DT, 1.5, .2, -.1, true);
        }
        renderer.render(active, 16 * Playback.DT, 96, 64, .2, -.1, true);
        var state = renderer.snapshot(active, 16);
        var controls = signals.snapshot(original);
        long memory = renderer.bytes();
        int originalProgram = renderer.materialProgramId();
        Settings previewActive = signals.frame(preview);
        renderer.render(previewActive, 16 * Playback.DT, 96, 64, .2, -.1, true);
        int[] pixels = image();
        check(
            same(state, renderer.snapshot(previewActive, 16))
                && controls.equals(signals.snapshot(preview))
                && memory == renderer.bytes(),
            mode + " preview preserves held GPU state, controls and storage");
        try (Renderer baseline = renderer.stageRestore(active, state, 16, 96, 64)) {
          Settings expectedActive = signals.frame(expected);
          baseline.render(expectedActive, 16 * Playback.DT, 96, 64, .2, -.1, true);
          check(Arrays.equals(pixels, image()), mode + " preview matches its direct scalar field");
          previewActive = signals.tick(preview, 17 * Playback.DT);
          expectedActive = signals.frame(expected);
          renderer.tick(previewActive, 17 * Playback.DT, 1.5, .2, -.1, true);
          renderer.render(previewActive, 17 * Playback.DT, 96, 64, .2, -.1, true);
          int[] continued = image();
          baseline.tick(expectedActive, 17 * Playback.DT, 1.5, .2, -.1, true);
          baseline.render(expectedActive, 17 * Playback.DT, 96, 64, .2, -.1, true);
          check(
              Arrays.equals(continued, image())
                  && same(
                      renderer.snapshot(previewActive, 17), baseline.snapshot(expectedActive, 17)),
              mode + " preview evolves exactly like the direct field");
          Settings returned = signals.frame(original);
          baseline.render(returned, 17 * Playback.DT, 96, 64, .2, -.1, true);
          int[] returnedPixels = image();
          renderer.render(returned, 17 * Playback.DT, 96, 64, .2, -.1, true);
          check(
              renderer.materialProgramId() == originalProgram
                  && Arrays.equals(returnedPixels, image()),
              mode + " preview can return to the cached original field");
        }
      }
    }
    check(glGetError() == GL_NO_ERROR, "typed previews leave no GL errors");
    return count;
  }
}
