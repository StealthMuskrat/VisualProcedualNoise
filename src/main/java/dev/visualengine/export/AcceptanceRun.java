package dev.visualengine.export;

import com.google.gson.*;
import dev.visualengine.app.Installation;
import dev.visualengine.presets.PresetIO;
import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Finished automated evidence plus an explicitly unrun manual acceptance review. */
public final class AcceptanceRun {
  public enum Environment {
    UNSPECIFIED,
    PHYSICAL,
    HOSTED
  }

  public record Outcome(String automatedStatus, int exitCode) {}

  interface Checks {
    String installation() throws Exception;

    void gpu(Path output) throws Exception;

    void benchmark(Path output, BenchmarkWorkloads.Profile profile) throws Exception;
  }

  private interface Action {
    void run() throws Exception;
  }

  private AcceptanceRun() {}

  public static Outcome run(
      Path destination, BenchmarkWorkloads.Profile profile, Environment environment)
      throws IOException {
    return run(
        destination,
        profile,
        environment,
        new Checks() {
          public String installation() {
            return Installation.verify();
          }

          public void gpu(Path path) throws Exception {
            GpuReport.probe(path);
          }

          public void benchmark(Path path, BenchmarkWorkloads.Profile selected) throws Exception {
            BenchmarkSuite.run(path, selected, null);
          }
        });
  }

  static Outcome run(
      Path destination, BenchmarkWorkloads.Profile profile, Environment environment, Checks checks)
      throws IOException {
    Objects.requireNonNull(profile);
    Objects.requireNonNull(environment);
    Objects.requireNonNull(checks);
    Path target = destination.toAbsolutePath().normalize();
    if (Files.exists(target, LinkOption.NOFOLLOW_LINKS))
      throw new IllegalArgumentException(
          "Acceptance destination must not already exist: " + target);
    Files.createDirectories(target.getParent());
    Path pending = Files.createTempDirectory(target.getParent(), ".acceptance-pending-");
    boolean published = false;
    try {
      var report = new JsonObject();
      report.addProperty("reportVersion", 1);
      report.addProperty("engineVersion", PresetIO.ENGINE_VERSION);
      report.addProperty("algorithmRevision", PresetIO.REVISION);
      report.addProperty("startedAt", Instant.now().toString());
      report.addProperty("profile", profile.name().toLowerCase(Locale.ROOT));
      report.addProperty("environmentDeclaration", environment.name().toLowerCase(Locale.ROOT));
      var host = new JsonObject();
      for (String key :
          List.of(
              "os.name",
              "os.version",
              "os.arch",
              "java.version",
              "java.runtime.version",
              "java.vm.name",
              "java.vendor")) host.addProperty(key, System.getProperty(key));
      host.addProperty("availableProcessors", Runtime.getRuntime().availableProcessors());
      host.addProperty("maxHeapBytes", Runtime.getRuntime().maxMemory());
      report.add("host", host);
      String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT),
          arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
      report.addProperty(
          "hostMatchesWindowsX64",
          os.startsWith("windows") && Set.of("amd64", "x86_64").contains(arch));
      report.addProperty("hardwareAcceptance", "not_established");
      report.addProperty(
          "scope",
          "Local installation, advertised GPU support and pinned workloads. Environment is"
              + " caller-declared; manual desktop, artistic, soak and hardware acceptance remain"
              + " unrun. Timings do not automatically certify a frame-rate target.");
      report.add("application", AcceptanceEvidence.application());
      var steps = new JsonObject();
      for (String name : List.of("installation", "gpu", "benchmark")) {
        var step = new JsonObject();
        step.addProperty("status", "not_run");
        steps.add(name, step);
      }
      report.add("steps", steps);
      var installation = steps.getAsJsonObject("installation");
      boolean installed =
          attempt(installation, () -> installation.addProperty("detail", checks.installation()));
      String status = "failed";
      if (installed) {
        var gpu = steps.getAsJsonObject("gpu");
        Path gpuPath = pending.resolve("gpu.json");
        boolean compatible =
            attempt(
                gpu,
                () -> {
                  checks.gpu(gpuPath);
                  var probe = JsonParser.parseString(Files.readString(gpuPath)).getAsJsonObject();
                  if (!"compatible".equals(probe.get("status").getAsString()))
                    throw new IllegalStateException(
                        "GPU probe did not establish baseline compatibility");
                });
        if (!compatible) {
          if (Files.isRegularFile(gpuPath)) {
            try {
              if (JsonParser.parseString(Files.readString(gpuPath))
                  .getAsJsonObject()
                  .get("status")
                  .getAsString()
                  .equals("failed")) {
                gpu.addProperty("status", "blocked");
                status = "blocked";
              }
            } catch (RuntimeException malformed) {
              /* Keep the probe step failed; never manufacture compatibility. */
            }
          }
        } else {
          boolean completed =
              attempt(
                  steps.getAsJsonObject("benchmark"),
                  () -> {
                    checks.benchmark(pending.resolve("benchmark"), profile);
                    validateSuite(pending.resolve("benchmark"), gpuPath, profile);
                  });
          status = completed ? "passed" : "failed";
        }
      }
      report.addProperty("automatedStatus", status);
      report.addProperty(
          "releaseAcceptance", status.equals("passed") ? "manual_review_required" : "blocked");
      report.addProperty("manualReview", "not_run");
      report.addProperty("manualReviewFile", "manual-review.md");
      report.addProperty("completedAt", Instant.now().toString());
      Files.writeString(pending.resolve("manual-review.md"), manualReview(profile, environment));
      report.add("immutableEvidence", AcceptanceEvidence.artifacts(pending));
      PresetIO.atomicWrite(pending.resolve("results.json"), PresetIO.JSON.toJson(report));
      Files.move(pending, target);
      published = true;
      System.out.println(
          "Acceptance evidence saved: "
              + target
              + " | automated "
              + status
              + " | manual review not run");
      return new Outcome(status, status.equals("passed") ? 0 : status.equals("blocked") ? 2 : 1);
    } finally {
      if (!published)
        try (var paths = Files.walk(pending)) {
          for (var path : paths.sorted(Comparator.reverseOrder()).toList())
            Files.deleteIfExists(path);
        }
    }
  }

  private static void validateSuite(
      Path directory, Path gpuPath, BenchmarkWorkloads.Profile profile) throws IOException {
    var suite =
        JsonParser.parseString(Files.readString(directory.resolve("suite.json"))).getAsJsonObject();
    var probe = JsonParser.parseString(Files.readString(gpuPath)).getAsJsonObject();
    if (suite.get("suiteVersion").getAsInt() != 1
        || !suite.get("engineVersion").getAsString().equals(PresetIO.ENGINE_VERSION)
        || !suite.get("algorithmRevision").getAsString().equals(PresetIO.REVISION)
        || !suite.get("profile").getAsString().equals(profile.name().toLowerCase(Locale.ROOT))
        || !Objects.equals(suite.get("deviceCapabilities"), probe.get("capabilities")))
      throw new IOException("Benchmark context/profile/build differs from the acceptance run");
    var cases = suite.getAsJsonArray("cases");
    var expected = BenchmarkWorkloads.create(profile);
    if (cases.size() != expected.size())
      throw new IOException("Acceptance requires all seven benchmark cases");
    Set<String> ids = new HashSet<>();
    for (int i = 0; i < expected.size(); i++) {
      var item = cases.get(i).getAsJsonObject();
      var workload = expected.get(i);
      if (!item.get("id").getAsString().equals(workload.id())
          || !ids.add(workload.id())
          || !item.get("workloadHash").getAsString().equals(workload.hash())
          || item.get("referenceTick").getAsInt() != profile.ticks()
          || item.get("width").getAsInt() != profile.width
          || item.get("height").getAsInt() != profile.height
          || !item.equals(
              JsonParser.parseString(
                  Files.readString(directory.resolve(workload.id()).resolve("report.json"))))
          || !item.get("imageHash")
              .getAsString()
              .equals(
                  AcceptanceEvidence.sha256(directory.resolve(workload.id()).resolve("final.png"))))
        throw new IOException("Incomplete or inconsistent acceptance case: " + workload.id());
    }
  }

  private static boolean attempt(JsonObject step, Action action) {
    long start = System.nanoTime();
    try {
      action.run();
      step.addProperty("status", "passed");
      return true;
    } catch (Exception | LinkageError error) {
      step.addProperty("status", "failed");
      var failure = new JsonObject();
      failure.addProperty("type", error.getClass().getName());
      String message = String.valueOf(error.getMessage());
      failure.addProperty("message", message.substring(0, Math.min(8192, message.length())));
      step.add("failure", failure);
      return false;
    } finally {
      step.addProperty("wallMs", Math.max(0, (System.nanoTime() - start) / 1e6));
    }
  }

  static String manualReview(BenchmarkWorkloads.Profile profile, Environment environment) {
    return """
    # Windows release acceptance review

    Automated results are in results.json. All checks below start unrun. Record observations,
    evidence filenames and failures here; do not change the machine-generated results.
    This editable review is excluded from immutableEvidence hashes. Keep raw evidence beside it.

    - Environment declaration: %s (caller supplied; not independently verified)
    - Workload profile: %s
    - Tester / date:
    - Windows edition, version and build from winver:
    - Physical GPU, driver version and display connection:
    - CPU/RAM and power profile:
    - Display resolutions, scaling and monitor arrangement:
    - Build/archive identity reviewed against results.json:

    For the shader-reload check, copy the distribution's acceptance-shaders directory to
    a separate manual-test directory and launch the app with --shaders pointing to that copy.
    Record paths and edits below. See docs/WINDOWS_ACCEPTANCE.md in the distribution.

    ## Desktop and installation

    - [ ] Confirm this is physical Windows hardware; record virtualization or software rendering.
    - [ ] Start the entire extracted app folder on a clean account without separately installed Java.
    - [ ] Verify unsupported-device messages and saved GPU diagnostics where applicable.
    - [ ] Resize and change DPI/monitor scaling; check aspect, pointer alignment and readable controls.
    - [ ] Move across monitors; test fullscreen entry/exit, restoration and monitor removal if available.
    - [ ] Release/focus-loss/repress while painting; confirm injection stops and no stray stroke bridge appears.
    - [ ] Minimize/restore; verify pause policy and absence of unbounded catch-up.
    - [ ] Pause, single-step and edit controls; verify clocks, smoothing and state behavior.
    - [ ] Repeatedly switch presets and quality; record memory/resource growth and failures.
    - [ ] Apply an invalid formula/graph and reload a broken development shader; verify the working scene survives.

    ## Artwork, persistence and endurance

    - [ ] Review all sixteen recipes for motion hierarchy, color, thin-detail aliasing and useful parameter extremes.
    - [ ] Exercise graph connections, groups/revisions, typed previews, modulation and undo through the desktop UI.
    - [ ] Verify still/sequence/map orientation, color, dimensions and metadata at representative resolutions.
    - [ ] Save/reopen presets, replay and checkpoints; observe their documented restarting/resuming behavior.
    - [ ] Run a long session; record duration, workload, memory, stability and any nonfinite artifacts.

    ## Performance and release decision

    - [ ] Review the seven raw benchmark cases, query coverage, workload hashes and images.
    - [ ] Repeat measurements without competing workloads; record hardware, power mode and variance.
    - [ ] Use fullhd for the pinned 1920x1080 workload; evaluate practical quality and performance targets.
    - [ ] Record tested Windows/driver combinations and proposed minimum requirements.
    - [ ] Record outstanding issues and the human release decision; automated success alone is not acceptance.

    Observations / failures / evidence paths:

    Release decision and reviewer:
    """
        .formatted(
            environment.name().toLowerCase(Locale.ROOT), profile.name().toLowerCase(Locale.ROOT));
  }
}
