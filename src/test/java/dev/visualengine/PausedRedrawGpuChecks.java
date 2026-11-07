package dev.visualengine;

import static org.lwjgl.opengl.GL43C.*;

import dev.visualengine.export.*;
import dev.visualengine.gpu.*;
import dev.visualengine.model.Settings;
import dev.visualengine.platform.*;
import dev.visualengine.presets.PresetIO;
import dev.visualengine.time.*;
import java.util.*;

public final class PausedRedrawGpuChecks {
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

  private static void draw(
      RedrawDemand demand,
      Renderer renderer,
      Settings active,
      double time,
      DesktopInput.Pointer pointer) {
    long revision = demand.beginFrame();
    renderer.render(active, time, 96, 64, pointer.x(), pointer.y(), pointer.brush());
    demand.presented(revision, 96, 64, pointer);
  }

  public static int run(Window window) {
    Settings s = EffectModulationChecks.settings();
    ControlSignals signals = new ControlSignals();
    RedrawDemand demand = new RedrawDemand();
    try (Renderer renderer = new Renderer(new ShaderSources(null));
        Renderer baseline = new Renderer(new ShaderSources(null))) {
      renderer.reset(s);
      baseline.reset(s);
      Settings active = null;
      for (int tick = 1; tick <= 16; tick++) {
        active = signals.tick(s, tick * Playback.DT);
        renderer.tick(active, tick * Playback.DT, 1.5, .2, -.1, true);
        baseline.tick(active, tick * Playback.DT, 1.5, .2, -.1, true);
      }
      double time = 16 * Playback.DT;
      draw(demand, renderer, active, time, DesktopInput.NONE);
      int[] original = image();
      GpuSnapshot before = renderer.snapshot(active, 16);
      var controls = signals.snapshot(s);
      var cache = renderer.shaderCacheStats();
      long memory = renderer.bytes();
      for (int i = 0; i < 40; i++) {
        demand.pointerChanged(new DesktopInput.Pointer(i / 50., -.2, false));
        if (demand.needsFrame(false, true, false, 96, 64))
          draw(demand, renderer, signals.frame(s), time, DesktopInput.NONE);
        else demand.idleWait();
      }
      check(
          demand.snapshot().presentedFrames() == 1
              && demand.snapshot().idleWaits() == 40
              && Arrays.equals(original, image())
              && same(before, renderer.snapshot(active, 16))
              && controls.equals(signals.snapshot(s))
              && cache.equals(renderer.shaderCacheStats())
              && renderer.bytes() == memory,
          "idle hover skips rendering while preserving finished pixels, every GPU byte, clocks,"
              + " cache and storage");
      var brush = new DesktopInput.Pointer(.2, -.1, true);
      demand.pointerChanged(brush);
      check(
          demand.needsFrame(false, true, false, 96, 64),
          "visible held brush input requests rendering");
      draw(demand, renderer, signals.frame(s), time, brush);
      check(
          !Arrays.equals(original, image())
              && same(before, renderer.snapshot(active, 16))
              && controls.equals(signals.snapshot(s)),
          "paused pointer preview changes the image without injecting pigment or advancing clocks");
      demand.pointerChanged(DesktopInput.NONE);
      draw(demand, renderer, signals.frame(s), time, DesktopInput.NONE);
      check(
          Arrays.equals(original, image()) && same(before, renderer.snapshot(active, 16)),
          "release redraw restores the held image with exact simulation state");

      window.consumeRedrawRequest();
      window.consumePointerChange();
      window.consumeTimingReset();
      window.resize();
      check(
          !window.consumeRedrawRequest() && !window.consumePointerChange(),
          "unchanged native framebuffer queries do not create recurring idle redraw requests");
      window.requestRedraw();
      window.requestRedraw();
      check(
          window.consumeRedrawRequest()
              && !window.consumeRedrawRequest()
              && Arrays.equals(original, image()),
          "native refresh requests coalesce without drawing in the callback path");
      demand.invalidate();
      draw(demand, renderer, signals.frame(s), time, DesktopInput.NONE);
      check(
          Arrays.equals(original, image())
              && same(before, renderer.snapshot(active, 16))
              && renderer.shaderCacheStats().equals(cache),
          "explicit refresh redraw preserves held image, GPU state and material reuse");
      window.interruptInput();
      check(
          window.consumePointerChange()
              && !window.consumePointerChange()
              && window.consumeRedrawRequest()
              && !window.consumeRedrawRequest()
              && window.consumeTimingReset()
              && !window.consumeTimingReset(),
          "input/display interruption retains independent one-shot pointer refresh and timing"
              + " signals");

      check(
          demand.needsFrame(false, true, true, 96, 64),
          "queued capture can redraw an otherwise idle scene");
      draw(demand, renderer, signals.frame(s), time, DesktopInput.NONE);
      renderer.captureStill(signals.frame(s), time, new CaptureSize(96, 64), 0, 0, false);
      check(
          Arrays.equals(original, image())
              && same(before, renderer.snapshot(active, 16))
              && controls.equals(signals.snapshot(s)),
          "held capture work preserves frame, clocks and every persistent resource");
      active = signals.tick(s, 17 * Playback.DT);
      renderer.tick(active, 17 * Playback.DT, 1.5, .2, -.1, false);
      baseline.tick(active, 17 * Playback.DT, 1.5, .2, -.1, false);
      demand.invalidate();
      draw(demand, renderer, active, 17 * Playback.DT, DesktopInput.NONE);
      int[] stepped = image();
      baseline.render(active, 17 * Playback.DT, 96, 64, 0, 0, false);
      check(
          Arrays.equals(stepped, image())
              && same(renderer.snapshot(active, 17), baseline.snapshot(active, 17))
              && !same(before, renderer.snapshot(active, 17)),
          "one requested tick after idle matches direct fixed-tick simulation without hidden"
              + " catch-up");
    }
    check(glGetError() == GL_NO_ERROR, "paused redraw checks leave no GL errors");
    return count;
  }
}
