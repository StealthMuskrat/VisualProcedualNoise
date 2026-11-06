package dev.visualengine.export;

import com.google.gson.JsonObject;
import dev.visualengine.model.Settings;
import dev.visualengine.presets.PresetIO;
import java.util.*;

public final class Benchmark {
  private final List<Double> cpu = new ArrayList<>(),
      render = new ArrayList<>(),
      tick = new ArrayList<>();
  private int frames;

  public void record(double cpuMs, double renderMs, double tickMs) {
    if (frames++ < 30) return;
    cpu.add(cpuMs);
    if (renderMs > 0) render.add(renderMs);
    if (tickMs > 0) tick.add(tickMs);
  }

  public JsonObject report(Settings settings, int width, int height, String device, long bytes) {
    JsonObject report = PresetIO.document(settings, 0);
    report.addProperty("device", device);
    report.addProperty("width", width);
    report.addProperty("height", height);
    report.addProperty("allocatedBytes", bytes);
    report.addProperty("warmupFrames", Math.min(frames, 30));
    report.addProperty("samples", cpu.size());
    report.addProperty(
        "cpuMeasurement", "poll-to-swap frame CPU wall time; headless swap interval zero");
    report.addProperty(
        "gpuMeasurement",
        "delayed nonblocking elapsed queries; rendering and complete fixed-tick workload measured"
            + " separately; latest available query sampled per frame");
    report.add("cpuFrameMs", summary(cpu));
    report.add("gpuRenderMs", summary(render));
    report.add("gpuTickMs", summary(tick));
    return report;
  }

  private static JsonObject summary(List<Double> values) {
    var s = values.stream().mapToDouble(Double::doubleValue).sorted().toArray();
    JsonObject j = new JsonObject();
    if (s.length > 0) {
      j.addProperty("p50", s[(s.length - 1) / 2]);
      j.addProperty("p95", s[(int) ((s.length - 1) * .95)]);
      j.addProperty("p99", s[(int) ((s.length - 1) * .99)]);
    }
    return j;
  }
}
