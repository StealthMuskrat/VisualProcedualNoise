package dev.visualengine;

import dev.visualengine.app.Options;
import dev.visualengine.export.GpuReport;
import dev.visualengine.presets.PresetIO;

public final class GpuReportChecks {
  private static int count;

  private static void check(boolean ok, String label) {
    count++;
    if (!ok) throw new AssertionError(label);
  }

  private static void rejects(String... args) {
    try {
      Options.parse(args);
    } catch (IllegalArgumentException expected) {
      count++;
      return;
    }
    throw new AssertionError("Accepted combined GPU diagnostic options");
  }

  static int run() {
    var options = Options.parse(new String[] {"--diagnose-gpu", "gpu.json"});
    check(
        options.headless && options.gpuDiagnostic.toString().equals("gpu.json"),
        "diagnostic mode uses a standalone hidden context");
    rejects("--diagnose-gpu", "gpu.json", "--frames", "1");
    rejects("--diagnose-gpu", "gpu.json", "--benchmark-suite", "suite");
    rejects("--diagnose-gpu", "gpu.json", "--preset", "study.json");
    rejects("--diagnose-gpu", "gpu.json", "--vsync", "on");
    var report = GpuReport.document(DeviceChecks.capable(), null);
    check(
        report.get("reportVersion").getAsInt() == 1
            && report.get("engineVersion").getAsString().equals(PresetIO.ENGINE_VERSION)
            && report.get("status").getAsString().equals("compatible")
            && report.getAsJsonArray("baselineFailures").isEmpty(),
        "report versions and compatibility derive from the queried capability snapshot");
    var failed = GpuReport.document(null, new IllegalStateException("No graphics context"));
    check(
        failed.get("capabilities").isJsonNull()
            && failed.get("status").getAsString().equals("failed")
            && failed
                .getAsJsonObject("failure")
                .get("message")
                .getAsString()
                .equals("No graphics context"),
        "context creation failure is recorded without invented device data");
    var oversized =
        GpuReport.document(DeviceChecks.capable(), new IllegalStateException("x".repeat(10000)));
    check(
        com.google.gson.JsonParser.parseString(GpuReport.encode(failed))
            .getAsJsonObject()
            .get("capabilities")
            .isJsonNull(),
        "serialized context failure retains an explicit unknown-capabilities field");
    check(
        oversized.getAsJsonObject("failure").get("message").getAsString().length() == 8192,
        "diagnostic failure text has a bounded size");
    return count;
  }
}
