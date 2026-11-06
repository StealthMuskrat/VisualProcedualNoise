package dev.visualengine;

import dev.visualengine.export.*;
import dev.visualengine.gpu.GpuTimer;
import dev.visualengine.model.Settings;
import java.util.*;

public final class BenchmarkChecks {
  private static int count;

  private static void check(boolean ok, String label) {
    count++;
    if (!ok) throw new AssertionError(label);
  }

  private static void rejects(Runnable action, String label) {
    try {
      action.run();
    } catch (IllegalArgumentException expected) {
      count++;
      return;
    }
    throw new AssertionError("Accepted " + label);
  }

  private static GpuTimer.Batch batch(GpuTimer.Sample... samples) {
    return new GpuTimer.Batch(List.of(samples), List.of(), 0);
  }

  static int run() {
    Benchmark timing = new Benchmark(2);
    for (int frame = 0; frame < 4; frame++) timing.recordFrame(frame, frame + 1);
    var delayed =
        batch(
            new GpuTimer.Sample(2, 1, 99),
            new GpuTimer.Sample(3, 2, 0),
            new GpuTimer.Sample(4, 3, 4));
    timing.accept(delayed, batch(new GpuTimer.Sample(1, 2, 2), new GpuTimer.Sample(2, 2, 3)));
    timing.accept(delayed, batch());
    timing.accept(new GpuTimer.Batch(List.of(), List.of(0L, 3L), 1), batch());
    var report = timing.report(new Settings(), 8, 96, 64, "test", 1234);
    check(
        report.getAsJsonObject("gpuRenderMs").get("count").getAsInt() == 2
            && report.getAsJsonObject("gpuRenderMs").get("minimum").getAsDouble() == 0,
        "completed query results counted once, including valid zero durations");
    check(
        report.getAsJsonObject("renderCoverage").get("completedWarmup").getAsInt() == 1
            && report.getAsJsonObject("renderCoverage").get("skippedMeasured").getAsInt() == 1
            && report.getAsJsonObject("renderCoverage").get("pending").getAsInt() == 1,
        "delayed warmup results and query coverage remain separate");
    check(
        report.getAsJsonObject("cpuFrameMs").get("count").getAsInt() == 2
            && report.getAsJsonObject("gpuTickMs").get("count").getAsInt() == 2
            && report.get("referenceTick").getAsLong() == 8,
        "frame and individual-tick measurements retain their own sample counts and tick context");
    check(!Benchmark.summary(List.of()).has("p50"), "missing timing data is not reported as zero");
    var percentiles =
        Benchmark.summary(
            java.util.stream.IntStream.rangeClosed(1, 100).mapToObj(x -> (double) x).toList());
    check(
        percentiles.get("p50").getAsInt() == 50
            && percentiles.get("p95").getAsInt() == 95
            && percentiles.get("p99").getAsInt() == 99,
        "timing percentiles use declared nearest-rank convention");
    rejects(() -> timing.recordFrame(4, Double.NaN), "nonfinite CPU timing");
    rejects(() -> timing.recordFrame(7, 1), "missing CPU frames");
    rejects(
        () -> timing.accept(batch(new GpuTimer.Sample(7, 9, 1)), batch()),
        "query referring to future frame");
    rejects(() -> Benchmark.summary(List.of(-1.)), "negative timing data");
    rejects(() -> new Benchmark(-1), "negative warmup");
    var smoke = BenchmarkWorkloads.create(BenchmarkWorkloads.Profile.SMOKE);
    var repeat = BenchmarkWorkloads.create(BenchmarkWorkloads.Profile.SMOKE);
    check(
        smoke.size() == 7
            && smoke.stream().map(BenchmarkWorkloads.Workload::hash).distinct().count() == 7,
        "suite has seven distinct pinned workload contracts");
    check(
        java.util.stream.IntStream.range(0, 7)
            .allMatch(i -> smoke.get(i).hash().equals(repeat.get(i).hash())),
        "suite contracts and inputs regenerate deterministically");
    var quick = BenchmarkWorkloads.create(BenchmarkWorkloads.Profile.QUICK);
    check(
        java.util.stream.IntStream.range(0, 7)
            .allMatch(i -> !smoke.get(i).hash().equals(quick.get(i).hash())),
        "resolution and layout profiles have distinct workload hashes");
    check(
        smoke.stream()
            .allMatch(w ->
                w.input().events.size() == 48
                    && w.input().endTick == 48
                    && w.initial().grid == 65
                    && w.initial().displayScale == 1),
        "smoke workloads pin tick count, inputs and quality");
    check(
        smoke.get(3).input().events.stream().anyMatch(e -> e.strength() > 0)
            && smoke.get(0).input().events.stream().noneMatch(e -> e.strength() > 0),
        "stateful workload strokes are explicit while analytic cases keep the pointer inactive");
    smoke.get(0).initial().set("warp", 1.8);
    check(repeat.get(0).initial().get("warp") == .65, "workload factories own mutable settings");
    return count;
  }
}
