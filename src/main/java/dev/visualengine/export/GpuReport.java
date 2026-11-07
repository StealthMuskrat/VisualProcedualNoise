package dev.visualengine.export;

import com.google.gson.*;
import dev.visualengine.gpu.DeviceCapabilities;
import dev.visualengine.platform.Window;
import dev.visualengine.presets.PresetIO;
import java.nio.file.Path;

/** Local advertised-support report; probing does not render or certify a workload. */
public final class GpuReport {
  private static final Gson JSON = PresetIO.JSON.newBuilder().serializeNulls().create();

  private GpuReport() {}

  public static String encode(JsonObject report) {
    return JSON.toJson(report);
  }

  public static JsonObject document(DeviceCapabilities capabilities, Throwable failure) {
    JsonObject report = new JsonObject();
    report.addProperty("reportVersion", 1);
    report.addProperty("engineVersion", PresetIO.ENGINE_VERSION);
    report.addProperty("algorithmRevision", PresetIO.REVISION);
    report.addProperty("javaVersion", System.getProperty("java.version"));
    report.addProperty("os", System.getProperty("os.name"));
    report.addProperty("architecture", System.getProperty("os.arch"));
    report.addProperty(
        "status",
        failure == null && capabilities != null && capabilities.baselineFailures().isEmpty()
            ? "compatible"
            : "failed");
    report.addProperty(
        "scope",
        "Advertised OpenGL support and engine baseline requirements. This is not a workload,"
            + " free-memory or performance test.");
    report.add(
        "capabilities",
        capabilities == null ? JsonNull.INSTANCE : PresetIO.JSON.toJsonTree(capabilities));
    if (capabilities != null)
      report.add("baselineFailures", PresetIO.JSON.toJsonTree(capabilities.baselineFailures()));
    if (failure != null) {
      JsonObject detail = new JsonObject();
      detail.addProperty("type", failure.getClass().getName());
      String message = String.valueOf(failure.getMessage());
      detail.addProperty("message", message.substring(0, Math.min(message.length(), 8192)));
      report.add("failure", detail);
    }
    return report;
  }

  public static void probe(Path path) throws Exception {
    DeviceCapabilities capabilities = null;
    Exception failure = null;
    try (Window window = new Window(96, 64, true)) {
      capabilities = window.capabilities();
    } catch (Exception e) {
      failure = e;
      if (e instanceof DeviceCapabilities.Unsupported unsupported)
        capabilities = unsupported.capabilities();
    }
    JsonObject report = document(capabilities, failure);
    try {
      PresetIO.atomicWrite(path, encode(report));
    } catch (Exception write) {
      if (failure != null) write.addSuppressed(failure);
      throw write;
    }
    System.out.println("GPU capability report saved: " + path);
    if (failure != null) throw failure;
  }
}
