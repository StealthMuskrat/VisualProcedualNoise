package dev.visualengine.export;

import com.google.gson.JsonObject;
import dev.visualengine.gpu.GpuTimer;
import dev.visualengine.model.Settings;
import dev.visualengine.presets.PresetIO;
import java.util.*;

/** Frame wall times and uniquely consumed, frame-tagged elapsed-query results. */
public final class Benchmark {
  public static final int MAX_FRAMES = 100000;
  private final int warmup;
  private int frames;
  private final List<Double> cpu = new ArrayList<>();
  private final Stream render = new Stream(), tick = new Stream();

  public Benchmark() {
    this(30);
  }

  public Benchmark(int warmup) {
    if (warmup < 0 || warmup >= MAX_FRAMES)
      throw new IllegalArgumentException("Invalid benchmark warmup");
    this.warmup = warmup;
  }

  public void recordFrame(long frame, double cpuMs) {
    if (frame != frames || frames >= MAX_FRAMES || !Double.isFinite(cpuMs) || cpuMs < 0)
      throw new IllegalArgumentException("Benchmark frames must be contiguous, finite and bounded");
    frames++;
    if (frame >= warmup) cpu.add(cpuMs);
  }

  public void accept(GpuTimer.Batch rendering, GpuTimer.Batch ticking) {
    render.accept(rendering);
    tick.accept(ticking);
  }

  public JsonObject report(
      Settings settings, long referenceTick, int width, int height, String device, long bytes) {
    JsonObject report = PresetIO.document(settings, referenceTick);
    report.addProperty("benchmarkVersion", 2);
    report.addProperty("device", device);
    report.addProperty("width", width);
    report.addProperty("height", height);
    report.addProperty("allocatedBytes", bytes);
    report.addProperty("warmupFrames", Math.min(frames, warmup));
    report.addProperty("samples", cpu.size());
    report.addProperty("frames", frames);
    report.addProperty(
        "cpuMeasurement",
        "frame wall time through poll/ticks/render/swap and nonblocking timing collection; includes"
            + " command submission, driver waits and instrumentation, not CPU-only execution");
    report.addProperty(
        "gpuMeasurement",
        "each completed tagged elapsed query consumed once; warmup tags excluded; render and"
            + " individual fixed-tick scopes separated; blocking tail drain occurs outside measured"
            + " frames");
    report.addProperty(
        "percentileMethod",
        "nearest rank: sorted[ceil(p*N)-1]; missing samples have count 0 and no percentiles");
    report.add("cpuFrameMs", summary(cpu));
    report.add("gpuRenderMs", summary(render.values));
    report.add("gpuTickMs", summary(tick.values));
    report.add("renderCoverage", render.coverage());
    report.add("tickCoverage", tick.coverage());
    return report;
  }

  public static JsonObject summary(List<Double> values) {
    var s = values.stream().mapToDouble(Double::doubleValue).sorted().toArray();
    for (double value : s)
      if (!Double.isFinite(value) || value < 0)
        throw new IllegalArgumentException("Invalid timing value");
    JsonObject j = new JsonObject();
    j.addProperty("count", s.length);
    if (s.length > 0) {
      j.addProperty("p50", s[(int) Math.ceil(.5 * s.length) - 1]);
      j.addProperty("p95", s[(int) Math.ceil(.95 * s.length) - 1]);
      j.addProperty("p99", s[(int) Math.ceil(.99 * s.length) - 1]);
      j.addProperty("minimum", s[0]);
      j.addProperty("maximum", s[s.length - 1]);
    }
    return j;
  }

  private final class Stream {
    final Set<Long> seen = new HashSet<>();
    final List<Double> values = new ArrayList<>();
    int completedWarmup, skippedWarmup, skippedMeasured, pending;

    void accept(GpuTimer.Batch batch) {
      for (var sample : batch.samples()) {
        if (sample.frame() >= frames)
          throw new IllegalArgumentException("GPU result refers to an unrecorded frame");
        if (!seen.add(sample.sequence())) continue;
        if (seen.size() > MAX_FRAMES * 8)
          throw new IllegalArgumentException("GPU timing sample limit exceeded");
        if (sample.frame() < warmup) completedWarmup++;
        else values.add(sample.milliseconds());
      }
      for (long frame : batch.skippedFrames()) {
        if (frame < 0 || frame >= frames)
          throw new IllegalArgumentException("Skipped query refers to an unrecorded frame");
        if (frame < warmup) skippedWarmup++;
        else skippedMeasured++;
      }
      pending = batch.pending();
    }

    JsonObject coverage() {
      JsonObject json = new JsonObject();
      json.addProperty("completedMeasured", values.size());
      json.addProperty("completedWarmup", completedWarmup);
      json.addProperty("skippedMeasured", skippedMeasured);
      json.addProperty("skippedWarmup", skippedWarmup);
      json.addProperty("pending", pending);
      return json;
    }
  }
}
