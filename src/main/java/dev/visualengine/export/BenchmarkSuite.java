package dev.visualengine.export;

import static org.lwjgl.glfw.GLFW.*;

import com.google.gson.*;
import dev.visualengine.gpu.*;
import dev.visualengine.model.Settings;
import dev.visualengine.platform.Window;
import dev.visualengine.presets.PresetIO;
import dev.visualengine.time.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;

/**
 * Isolated offline workload execution. A result directory is published only after all cases finish.
 */
public final class BenchmarkSuite {
  private BenchmarkSuite() {}

  public static void run(Path destination, BenchmarkWorkloads.Profile profile, Path shaders)
      throws Exception {
    Path target = destination.toAbsolutePath().normalize();
    if (Files.exists(target, LinkOption.NOFOLLOW_LINKS))
      throw new IllegalArgumentException("Benchmark destination must not already exist: " + target);
    Files.createDirectories(target.getParent());
    Path pending = null;
    boolean published = false;
    try {
      JsonObject suite = new JsonObject();
      suite.addProperty("suiteVersion", 1);
      suite.addProperty("engineVersion", PresetIO.ENGINE_VERSION);
      suite.addProperty("algorithmRevision", PresetIO.REVISION);
      suite.addProperty("profile", profile.name().toLowerCase(Locale.ROOT));
      suite.addProperty("javaVersion", System.getProperty("java.version"));
      suite.addProperty("os", System.getProperty("os.name"));
      suite.addProperty("architecture", System.getProperty("os.arch"));
      suite.addProperty(
          "execution",
          "single process/context, sequential fresh renderer per case, headless swap interval zero;"
              + " driver caches are uncontrolled");
      suite.addProperty(
          "timingScope",
          "nonblocking queries in frames; explicit synchronization after setup, at the warmup"
              + " boundary and after measured frames; readback/hashing/encoding excluded from"
              + " measured frame intervals");
      JsonArray cases = new JsonArray();
      List<CaseResult> results = new ArrayList<>();
      long contextStart = System.nanoTime();
      try (Window window = new Window(profile.width, profile.height, true)) {
        suite.addProperty("contextSetupWallMs", (System.nanoTime() - contextStart) / 1e6);
        suite.addProperty("device", Renderer.deviceSignature());
        suite.add("deviceCapabilities", PresetIO.JSON.toJsonTree(window.capabilities()));
        for (var workload : BenchmarkWorkloads.create(profile)) {
          validateWindow(window, profile);
          CaseResult result = runCase(workload, profile, window, shaders);
          results.add(result);
          JsonObject report = result.report();
          System.out.printf(
              Locale.ROOT,
              "Benchmark %s complete: %d frames, CPU p50 %.3f ms%n",
              workload.id(),
              profile.samples,
              report.getAsJsonObject("cpuFrameMs").get("p50").getAsDouble());
        }
      }
      // Keep only seven bounded final images until graphics succeeds; shader errors create no
      // files.
      pending = Files.createTempDirectory(target.getParent(), ".benchmark-pending-");
      for (CaseResult result : results) {
        writeCase(pending.resolve(result.workload().id()), result, profile);
        cases.add(result.report());
      }
      suite.add("cases", cases);
      PresetIO.atomicWrite(pending.resolve("suite.json"), PresetIO.JSON.toJson(suite));
      // No replacement flag: an existing destination is never intentionally overwritten.
      Files.move(pending, target);
      published = true;
      System.out.println("Benchmark suite saved: " + target);
    } finally {
      if (!published && pending != null) {
        try (var paths = Files.walk(pending)) {
          for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
            Files.deleteIfExists(path);
          }
        } catch (NoSuchFileException alreadyRemoved) {
          /* No incomplete result remains. */
        }
      }
    }
  }

  private static void validateWindow(Window window, BenchmarkWorkloads.Profile profile) {
    window.resize();
    if (window.closing())
      throw new java.util.concurrent.CancellationException("Benchmark cancelled");
    if (window.width != profile.width || window.height != profile.height)
      throw new IllegalArgumentException(
          "Benchmark requires exact framebuffer "
              + profile.width
              + "x"
              + profile.height
              + "; actual "
              + window.width
              + "x"
              + window.height);
  }

  private record CaseResult(
      BenchmarkWorkloads.Workload workload,
      Settings resolved,
      BufferedImage image,
      JsonObject report,
      long tick) {}

  private static CaseResult runCase(
      BenchmarkWorkloads.Workload workload,
      BenchmarkWorkloads.Profile profile,
      Window window,
      Path shaders)
      throws Exception {
    Settings settings = workload.initial().copy();
    ControlSignals signals = new ControlSignals();
    Benchmark benchmark = new Benchmark(profile.warmup);
    long setupStart = System.nanoTime(), tick = 0;
    double x = 0, y = 0;
    boolean brush = false;
    try (Renderer renderer = new Renderer(new ShaderSources(shaders))) {
      renderer.reset(settings);
      renderer.render(signals.frame(settings), 0, profile.width, profile.height, 0, 0, false);
      renderer.finishMeasurements();
      double setupMs = (System.nanoTime() - setupStart) / 1e6;
      for (int frame = 0; frame < profile.frames(); frame++) {
        if (frame == profile.warmup) {
          var barrier = renderer.finishMeasurements();
          benchmark.accept(barrier.render(), barrier.tick());
        }
        long start = System.nanoTime();
        glfwPollEvents();
        validateWindow(window, profile);
        renderer.measurementFrame(frame);
        for (int step = 0; step < BenchmarkWorkloads.TICKS_PER_FRAME; step++) {
          tick++;
          for (var event : workload.input().at(tick)) {
            x = event.x();
            y = event.y();
            brush = event.strength() > 0;
            settings.set("radius", event.radius());
          }
          renderer.tick(
              signals.tick(settings, tick * Playback.DT),
              tick * Playback.DT,
              (double) profile.width / profile.height,
              x,
              y,
              brush);
        }
        renderer.render(
            signals.frame(settings),
            tick * Playback.DT,
            profile.width,
            profile.height,
            x,
            y,
            brush);
        glfwSwapBuffers(window.handle);
        var collected = renderer.collectMeasurements();
        benchmark.recordFrame(frame, (System.nanoTime() - start) / 1e6);
        benchmark.accept(collected.render(), collected.tick());
      }
      var tail = renderer.finishMeasurements();
      benchmark.accept(tail.render(), tail.tick());
      // A swap need not preserve its back buffer. Re-render the held final tick outside timing.
      renderer.measurementFrame(-1);
      var active = signals.frame(settings);
      renderer.render(active, tick * Playback.DT, profile.width, profile.height, x, y, brush);
      var image = Capture.read(profile.width, profile.height);
      var gpu = renderer.snapshot(settings, tick);
      Settings resolved = settings.copy();
      resolved.motionClock = active.motionClock;
      resolved.colorClock = active.colorClock;
      JsonObject report =
          benchmark.report(
              resolved,
              tick,
              profile.width,
              profile.height,
              Renderer.deviceSignature(),
              renderer.bytes());
      report.addProperty("id", workload.id());
      report.addProperty("workloadHash", workload.hash());
      report.addProperty("caseSetupWallMs", setupMs);
      report.addProperty(
          "setupScope",
          "fresh renderer/program/resources and held tick-0 render in the existing context;"
              + " synchronized completion; driver caches uncontrolled");
      report.addProperty(
          "firstMeasuredTick", profile.warmup * BenchmarkWorkloads.TICKS_PER_FRAME + 1);
      report.addProperty(
          "firstMeasuredRenderTick", (profile.warmup + 1) * BenchmarkWorkloads.TICKS_PER_FRAME);
      report.addProperty("ticksPerFrame", BenchmarkWorkloads.TICKS_PER_FRAME);
      report.addProperty("programSignature", renderer.programSignature());
      report.add("shaderCache", PresetIO.JSON.toJsonTree(renderer.shaderCacheStats()));
      report.addProperty(
          "imageHashEncoding",
          "SHA-256 of the final PNG file, re-rendered at held final tick outside timing");
      report.add("simulation", PresetIO.JSON.toJsonTree(gpu.simulation()));
      report.add("signals", PresetIO.JSON.toJsonTree(signals.snapshot(settings)));
      Map<String, String> hashes = new TreeMap<>();
      gpu.blobs().forEach((name, data) -> hashes.put(name, BenchmarkWorkloads.sha256(data)));
      report.add("stateHashes", PresetIO.JSON.toJsonTree(hashes));
      return new CaseResult(workload, resolved, image, report, tick);
    }
  }

  private static void writeCase(
      Path directory, CaseResult result, BenchmarkWorkloads.Profile profile) throws Exception {
    var workload = result.workload();
    var report = result.report();
    Files.createDirectory(directory);
    PresetIO.atomicWrite(
        directory.resolve("workload.json"), PresetIO.JSON.toJson(workload.contract()));
    PresetIO.save(directory.resolve("initial.json"), workload.initial(), 0);
    workload.input().save(directory.resolve("input.replay.json"));
    Capture.write(
        directory.resolve("final.png"),
        result.image(),
        result.resolved(),
        result.tick(),
        report.get("device").getAsString(),
        new Capture.View(profile.width, profile.height, false));
    report.addProperty(
        "imageHash", BenchmarkWorkloads.sha256(Files.readAllBytes(directory.resolve("final.png"))));
    PresetIO.atomicWrite(directory.resolve("report.json"), PresetIO.JSON.toJson(report));
  }
}
