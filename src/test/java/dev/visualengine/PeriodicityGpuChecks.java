package dev.visualengine;

import static org.lwjgl.opengl.GL43C.*;

import dev.visualengine.authoring.DirectionalEffects;
import dev.visualengine.export.*;
import dev.visualengine.gpu.*;
import dev.visualengine.model.*;
import dev.visualengine.presets.PresetIO;
import dev.visualengine.time.*;
import java.util.*;

public final class PeriodicityGpuChecks {
  private static int count;

  private static void check(boolean ok, String label) {
    count++;
    if (!ok) throw new AssertionError(label);
  }

  private static int[] pixels() {
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

  static int run() {
    Settings target = new Settings();
    target.grid = 65;
    target.particles = 257;
    target.historyLayers = 3;
    Recipes.apply(target, Settings.Recipe.TIME_EXCAVATION);
    target.feedbackEnabled =
        target.pigmentEnabled = target.reactionEnabled = target.particlesEnabled = true;
    ControlSignals signals = new ControlSignals();
    try (Renderer renderer = new Renderer(new ShaderSources(null))) {
      renderer.reset(target);
      for (int tick = 1; tick <= 32; tick++)
        renderer.tick(
            signals.tick(target, tick * Playback.DT), tick * Playback.DT, 1.5, .1, .2, true);
      Settings active = signals.frame(target);
      double time = 32 * Playback.DT;
      renderer.render(active, time, 96, 64, .1, .2, true);
      renderer.freezeFinished(96, 64);
      target.debug = 8;
      target.tilePreview = true;
      active = signals.frame(target);
      renderer.render(active, time, 96, 64, .1, .2, true);
      int[] beforeImage = pixels();
      GpuSnapshot before = renderer.snapshot(target, 32);
      var beforeSignals = signals.snapshot(target);
      long bytes = renderer.bytes();
      int program = renderer.materialProgramId();
      int read = glGetInteger(GL_READ_FRAMEBUFFER_BINDING),
          draw = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
      int[] viewport = new int[4];
      glGetIntegerv(GL_VIEWPORT, viewport);
      var first = renderer.captureInspection(active, time, new CaptureSize(127, 95), .1, .2, true);
      var context =
          new Periodicity.Frame(
              target,
              active,
              32,
              time,
              Renderer.deviceSignature(),
              renderer.programSignature(),
              .1,
              .2,
              true);
      var report = Periodicity.analyze(context, first, null);
      check(
          !report.loop().sampled() && first.getWidth() == 127 && first.getHeight() == 95,
          "stateful spatial inspection uses explicit independent extent");
      int[] afterViewport = new int[4];
      glGetIntegerv(GL_VIEWPORT, afterViewport);
      check(
          read == glGetInteger(GL_READ_FRAMEBUFFER_BINDING)
              && draw == glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING)
              && Arrays.equals(viewport, afterViewport),
          "inspection restores framebuffer bindings and viewport");
      check(
          same(before, renderer.snapshot(target, 32))
              && beforeSignals.equals(signals.snapshot(target))
              && bytes == renderer.bytes()
              && program == renderer.materialProgramId(),
          "inspection preserves GPU bytes, metadata, reference, clocks and allocation");
      check(
          Arrays.equals(beforeImage, pixels()),
          "inspection readback preserves already rendered live framebuffer");
      renderer.render(active, time, 96, 64, .1, .2, true);
      check(
          Arrays.equals(beforeImage, pixels()),
          "live diagnostic and A/B frame reproduce after inspection");
      try (Renderer expected = renderer.stageRestore(target, before, 32, 96, 64)) {
        expected.clearReference();
        Settings creative = active.copy();
        creative.debug = 0;
        creative.tilePreview = false;
        var reference =
            expected.captureStill(creative, time, new CaptureSize(127, 95), .1, .2, true);
        check(
            Periodicity.difference(first, reference).changedPairs() == 0,
            "inspection excludes A/B, diagnostics and wrap preview without mutating the live"
                + " scene");
      }
      boolean rejected = false;
      try {
        renderer.captureInspection(active, time, new CaptureSize(4096, 4096), 0, 0, false);
      } catch (IllegalArgumentException expected) {
        rejected = true;
      }
      renderer.render(active, time, 96, 64, .1, .2, true);
      check(
          rejected && renderer.bytes() == bytes && Arrays.equals(beforeImage, pixels()),
          "over-budget inspection preserves resources and live image");
      Settings loop = new Settings();
      DirectionalEffects.create(DirectionalEffects.Style.FIBERS).apply(loop);
      loop.grid = 65;
      loop.particles = 0;
      loop.historyLayers = 0;
      loop.loopSeconds = 8;
      loop.tileMode = true;
      loop.tilePreview = true;
      loop.debug = 8;
      renderer.reset(loop);
      var start = renderer.captureInspection(loop, 0, new CaptureSize(96, 64), 0, 0, false);
      var endpoint = renderer.captureInspection(loop, 8, new CaptureSize(96, 64), 0, 0, false);
      var middle = renderer.captureInspection(loop, 2, new CaptureSize(96, 64), 0, 0, false);
      report =
          Periodicity.analyze(
              new Periodicity.Frame(
                  loop,
                  loop,
                  0,
                  0,
                  Renderer.deviceSignature(),
                  renderer.programSignature(),
                  0,
                  0,
                  false),
              start,
              endpoint);
      check(
          report.loop().sampled() && report.loop().difference().changedPairs() == 0,
          "configured analytic endpoints are measured as identical rendered pixels");
      check(
          Periodicity.difference(start, middle).changedPairs() > 0,
          "endpoint check does not hide actual animation through diagnostic or reference overlays");
    }
    return count;
  }
}
