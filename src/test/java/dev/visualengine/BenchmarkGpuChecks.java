package dev.visualengine;

import static org.lwjgl.opengl.GL43C.*;

import dev.visualengine.export.*;
import dev.visualengine.gpu.*;
import dev.visualengine.model.Settings;
import dev.visualengine.presets.PresetIO;
import dev.visualengine.time.*;
import java.util.*;

public final class BenchmarkGpuChecks {
  private static int count;

  private static void check(boolean ok, String label) {
    count++;
    if (!ok) throw new AssertionError(label);
  }

  static int run() {
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    try (GpuTimer timer = new GpuTimer()) {
      timer.begin();
      glClear(GL_COLOR_BUFFER_BIT);
      timer.end();
      var untagged = timer.finish();
      check(
          untagged.samples().isEmpty() && untagged.pending() == 0,
          "ordinary GPU timers retain status without accumulating benchmark samples");
      List<GpuTimer.Sample> samples = new ArrayList<>();
      List<Long> skipped = new ArrayList<>();
      for (int frame = 0; frame < 40; frame++) {
        timer.begin(frame);
        glClear(GL_COLOR_BUFFER_BIT);
        timer.end();
        var ready = timer.drain();
        samples.addAll(ready.samples());
        skipped.addAll(ready.skippedFrames());
      }
      var tail = timer.finish();
      samples.addAll(tail.samples());
      skipped.addAll(tail.skippedFrames());
      check(
          samples.size() + skipped.size() == 40
              && samples.stream().map(GpuTimer.Sample::sequence).distinct().count()
                  == samples.size()
              && samples.stream()
                  .allMatch(
                      s -> s.frame() >= 0 && s.frame() < 40 && Double.isFinite(s.milliseconds())),
          "actual elapsed query results are unique, finite and attributed to their submitted"
              + " frames");
      check(
          timer.drain().samples().isEmpty() && timer.finish().samples().isEmpty(),
          "query drains cannot repeat completed measurements");
    }
    Result expected = scene(false), measured = scene(true);
    check(
        Arrays.equals(expected.image, measured.image)
            && same(expected.gpu, measured.gpu)
            && expected.signals.equals(measured.signals),
        "timing instrumentation preserves rendered image, clocks and every persistent GPU byte");
    var report = measured.report;
    check(
        report.getAsJsonObject("renderCoverage").get("completedMeasured").getAsInt()
                + report.getAsJsonObject("renderCoverage").get("skippedMeasured").getAsInt()
            == 8,
        "render query coverage accounts for every measured frame");
    check(
        report.getAsJsonObject("tickCoverage").get("completedMeasured").getAsInt()
                    + report.getAsJsonObject("tickCoverage").get("skippedMeasured").getAsInt()
                == 16
            && report.getAsJsonObject("tickCoverage").get("pending").getAsInt() == 0
            && report.getAsJsonObject("renderCoverage").get("pending").getAsInt() == 0,
        "individual fixed-tick queries and final tail collection are complete");
    return count;
  }

  private record Result(
      int[] image,
      GpuSnapshot gpu,
      ControlSignals.State signals,
      com.google.gson.JsonObject report) {}

  private static Result scene(boolean measure) {
    var workload = BenchmarkWorkloads.create(BenchmarkWorkloads.Profile.SMOKE).get(6);
    Settings settings = workload.initial();
    ControlSignals signals = new ControlSignals();
    Benchmark timing = new Benchmark(4);
    try (Renderer renderer = new Renderer(new ShaderSources(null))) {
      renderer.reset(settings);
      for (int frame = 0; frame < 12; frame++) {
        if (measure) renderer.measurementFrame(frame);
        for (int step = 1; step <= 2; step++) {
          int tick = frame * 2 + step;
          var event = workload.input().at(tick).getFirst();
          renderer.tick(
              signals.tick(settings, tick * Playback.DT),
              tick * Playback.DT,
              1.5,
              event.x(),
              event.y(),
              event.strength() > 0);
        }
        renderer.render(
            signals.frame(settings), (frame + 1) * 2 * Playback.DT, 96, 64, 0, 0, false);
        timing.recordFrame(frame, 1);
        if (measure) {
          var batch = renderer.collectMeasurements();
          timing.accept(batch.render(), batch.tick());
        }
      }
      if (measure) {
        var tail = renderer.finishMeasurements();
        timing.accept(tail.render(), tail.tick());
      }
      return new Result(
          Capture.read(96, 64).getRGB(0, 0, 96, 64, null, 0, 96),
          renderer.snapshot(settings, 24),
          signals.snapshot(settings),
          timing.report(settings, 24, 96, 64, Renderer.deviceSignature(), renderer.bytes()));
    }
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
