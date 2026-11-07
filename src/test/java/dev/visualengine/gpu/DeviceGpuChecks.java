package dev.visualengine.gpu;

import static org.lwjgl.opengl.GL43C.*;

import dev.visualengine.DeviceChecks;
import dev.visualengine.export.*;
import dev.visualengine.model.*;
import dev.visualengine.presets.PresetIO;
import dev.visualengine.time.Playback;
import java.util.*;
import java.util.concurrent.*;

public final class DeviceGpuChecks {
  private static int count;

  private static void check(boolean ok, String label) {
    count++;
    if (!ok) throw new AssertionError(label);
  }

  private static void rejects(Runnable action, String text) {
    try {
      action.run();
    } catch (IllegalArgumentException expected) {
      check(expected.getMessage().contains(text), "GPU preflight diagnostic: " + text);
      return;
    }
    throw new AssertionError("Accepted constrained device request: " + text);
  }

  public static int run() throws Exception {
    DeviceCapabilities actual = GpuDevice.current();
    int read = glGetInteger(GL_READ_FRAMEBUFFER_BINDING),
        draw = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING),
        program = glGetInteger(GL_CURRENT_PROGRAM),
        active = glGetInteger(GL_ACTIVE_TEXTURE);
    int[] viewport = new int[4];
    glGetIntegerv(GL_VIEWPORT, viewport);
    DeviceCapabilities queried = GpuDevice.query();
    int[] after = new int[4];
    glGetIntegerv(GL_VIEWPORT, after);
    check(
        queried.equals(actual)
            && read == glGetInteger(GL_READ_FRAMEBUFFER_BINDING)
            && draw == glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING)
            && program == glGetInteger(GL_CURRENT_PROGRAM)
            && active == glGetInteger(GL_ACTIVE_TEXTURE)
            && Arrays.equals(viewport, after),
        "real capability queries agree with cached support and preserve current rendering"
            + " bindings");
    var report = GpuReport.document(actual, null);
    check(
        report.get("status").getAsString().equals("compatible")
            && report.getAsJsonArray("baselineFailures").isEmpty()
            && report.getAsJsonObject("capabilities").equals(PresetIO.JSON.toJsonTree(actual)),
        "GPU report records the actual queried device without changing support semantics");
    check(
        CompletableFuture.supplyAsync(
                () -> {
                  try {
                    GpuDevice.current();
                    return false;
                  } catch (IllegalStateException expected) {
                    return expected.getMessage().contains("owning context");
                  }
                })
            .get(5, TimeUnit.SECONDS),
        "worker threads cannot reuse the context thread's cached capabilities");

    DeviceCapabilities constrained = actual;
    for (var e :
        Map.of(
                "GL_MAX_TEXTURE_SIZE",
                96L,
                "GL_MAX_ARRAY_TEXTURE_LAYERS",
                3L,
                "GL_MAX_SHADER_STORAGE_BLOCK_SIZE",
                8224L,
                "GL_MAX_VIEWPORT_WIDTH",
                96L,
                "GL_MAX_VIEWPORT_HEIGHT",
                64L)
            .entrySet()) constrained = DeviceChecks.limited(constrained, e.getKey(), e.getValue());
    Settings s = new Settings();
    Recipes.apply(s, Settings.Recipe.TIME_EXCAVATION);
    s.pigmentEnabled = true;
    s.grid = 65;
    s.particles = 257;
    s.historyLayers = 3;
    try (Renderer renderer = new Renderer(new ShaderSources(null), constrained)) {
      renderer.reset(s);
      for (int tick = 1; tick <= 16; tick++)
        renderer.tick(s, tick * Playback.DT, 1.5, .2, -.1, true);
      renderer.render(s, 16 * Playback.DT, 96, 64, .2, -.1, true);
      GpuSnapshot before = renderer.snapshot(s, 16);
      int[] image = Capture.read(96, 64).getRGB(0, 0, 96, 64, null, 0, 96);
      long bytes = renderer.bytes();
      int id = renderer.materialProgramId();
      Settings bad = s.copy();
      bad.grid = 129;
      rejects(() -> renderer.configure(bad), "device texture limits");
      Settings layers = s.copy();
      layers.historyLayers = 4;
      rejects(() -> renderer.configure(layers), "4 layers");
      Settings particles = s.copy();
      particles.particles = 258;
      rejects(() -> renderer.configure(particles), "8256 bytes");
      rejects(
          () -> renderer.captureStill(s, 16 * Playback.DT, new CaptureSize(128, 64), 0, 0, false),
          "device texture limits");
      rejects(
          () ->
              renderer.captureMap(
                  s,
                  16 * Playback.DT,
                  TextureMap.Kind.HEIGHT,
                  new CaptureSize(128, 64),
                  new TextureMap.Range(-2, 2),
                  0,
                  0,
                  false),
          "device texture limits");
      rejects(() -> renderer.render(s, 16 * Playback.DT, 96, 65, 0, 0, false), "viewport limits");
      GpuSnapshot preserved = renderer.snapshot(s, 16);
      check(
          renderer.bytes() == bytes
              && renderer.materialProgramId() == id
              && same(before, preserved)
              && Arrays.equals(image, Capture.read(96, 64).getRGB(0, 0, 96, 64, null, 0, 96)),
          "device-bound configuration and capture failures preserve programs, finished image and"
              + " every GPU state byte");
      int current = glGetInteger(GL_CURRENT_PROGRAM);
      GpuReport.document(actual, null);
      check(
          current == glGetInteger(GL_CURRENT_PROGRAM) && same(preserved, renderer.snapshot(s, 16)),
          "saving cached capability metadata requires no GPU work or state mutation");
    }
    check(glGetError() == GL_NO_ERROR, "capability preflight issues no invalid GL calls");
    return count;
  }

  private static boolean same(GpuSnapshot a, GpuSnapshot b) {
    return a.blobs().keySet().equals(b.blobs().keySet())
        && a.blobs().entrySet().stream()
            .allMatch(e -> Arrays.equals(e.getValue(), b.blobs().get(e.getKey())))
        && PresetIO.JSON
            .toJsonTree(a.simulation())
            .equals(PresetIO.JSON.toJsonTree(b.simulation()));
  }
}
