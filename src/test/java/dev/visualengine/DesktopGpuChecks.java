package dev.visualengine;

import dev.visualengine.export.Capture;
import dev.visualengine.gpu.*;
import dev.visualengine.model.*;
import dev.visualengine.platform.DesktopInput;
import dev.visualengine.presets.PresetIO;
import dev.visualengine.time.*;
import java.nio.file.*;
import java.util.*;

/** Compare interrupted live painting with an explicit released stroke and its recorded replay. */
public final class DesktopGpuChecks {
  private static int count;

  private static void check(boolean ok, String label) {
    count++;
    if (!ok) throw new AssertionError(label);
  }

  private record Result(GpuSnapshot gpu, int[] image) {}

  static int run() throws Exception {
    Settings settings = new Settings();
    Recipes.apply(settings, Settings.Recipe.FLUID_PALETTE);
    settings.grid = 65;
    settings.particles = 257;
    settings.historyLayers = 3;
    settings.set("radius", .08);
    ReplayLog log = ReplayLog.begin(settings, 96, 64);
    try (Renderer renderer = new Renderer(new ShaderSources(null))) {
      Result live = scene(renderer, settings, 0, log);
      Result released = scene(renderer, settings, 1, null);
      Result stale = scene(renderer, settings, 2, null);
      check(
          same(live.gpu, released.gpu) && Arrays.equals(live.image, released.image),
          "focus-interrupted pigment matches explicit tick release in every GPU byte and finished"
              + " pixel");
      check(
          !Arrays.equals(live.gpu.blobs().get("pigment.0"), stale.gpu.blobs().get("pigment.0")),
          "a stale held stroke would measurably bridge the old and new pigment positions");
      Path fixture = Path.of("build/reports/desktop-replay-fixture.json");
      log.save(fixture);
      ReplayLog loaded = ReplayLog.load(fixture);
      Result replay = scene(renderer, settings, 3, loaded);
      check(
          same(live.gpu, replay.gpu)
              && Arrays.equals(live.image, replay.image)
              && loaded.cursor() == loaded.events.size(),
          "ordinary replay reproduces a desktop interruption without adding platform events or a"
              + " new format");
    }
    return count;
  }

  // 0: gated live input, 1: explicit release, 2: old held-stroke behavior, 3: serialized replay.
  private static Result scene(Renderer renderer, Settings settings, int mode, ReplayLog log) {
    renderer.reset(settings);
    DesktopInput input = new DesktopInput();
    input.focus(true);
    input.release();
    for (int tick = 1; tick <= 6; tick++) {
      double x = tick < 5 ? -.8 : .8;
      boolean brush = mode != 1 || tick != 5;
      if (mode == 0) {
        if (tick == 5) {
          input.focus(false);
          input.release(); // GLFW's synthetic release while unfocused.
          input.focus(true);
          input.release(); // A deliberate release/repress between capped frames.
        }
        brush = input.tickBrush(true);
        log.recordPointer(tick, x, 0, settings.get("radius"), brush);
      } else if (mode == 3) {
        var event = log.at(tick).getFirst();
        x = event.x();
        brush = event.strength() > 0;
      }
      renderer.tick(settings, tick * Playback.DT, 1.5, x, 0, brush);
    }
    renderer.render(settings, 6 * Playback.DT, 96, 64, .8, 0, true);
    return new Result(
        renderer.snapshot(settings, 6), Capture.read(96, 64).getRGB(0, 0, 96, 64, null, 0, 96));
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
