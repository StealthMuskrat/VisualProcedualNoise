package dev.visualengine.export;

import com.google.gson.*;
import dev.visualengine.presets.PresetIO;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

public final class AcceptanceChecks {
  private static int count;

  private static void check(boolean ok, String text) {
    count++;
    if (!ok) throw new AssertionError(text);
  }

  private static JsonObject read(Path path) throws IOException {
    return JsonParser.parseString(Files.readString(path)).getAsJsonObject();
  }

  private static class Fake implements AcceptanceRun.Checks {
    int calls;
    String fail = "";

    public String installation() {
      calls++;
      if (fail.equals("installation")) throw new UnsatisfiedLinkError("native failure");
      return "fixture installation";
    }

    public void gpu(Path path) throws Exception {
      calls++;
      var probe = new JsonObject();
      probe.addProperty("status", fail.equals("gpu") ? "failed" : "compatible");
      probe.add("capabilities", new JsonObject());
      if (fail.equals("probe-write")) throw new IOException("report write failed");
      PresetIO.atomicWrite(path, PresetIO.JSON.toJson(probe));
      if (fail.equals("gpu")) throw new IllegalStateException("unavailable context");
    }

    public void benchmark(Path path, BenchmarkWorkloads.Profile profile) throws Exception {
      calls++;
      if (fail.equals("benchmark")) throw new IOException("benchmark failed");
      Files.createDirectory(path);
      var suite = new JsonObject();
      suite.addProperty("suiteVersion", 1);
      suite.addProperty("engineVersion", PresetIO.ENGINE_VERSION);
      suite.addProperty("algorithmRevision", PresetIO.REVISION);
      suite.addProperty("profile", profile.name().toLowerCase(Locale.ROOT));
      suite.add("deviceCapabilities", new JsonObject());
      var cases = new JsonArray();
      for (var workload : BenchmarkWorkloads.create(profile)) {
        Path child = Files.createDirectory(path.resolve(workload.id()));
        Files.writeString(child.resolve("final.png"), "fixture image " + workload.id());
        var item = new JsonObject();
        item.addProperty("id", workload.id());
        item.addProperty("workloadHash", workload.hash());
        item.addProperty("referenceTick", profile.ticks());
        item.addProperty("width", profile.width);
        item.addProperty("height", profile.height);
        item.addProperty("imageHash", AcceptanceEvidence.sha256(child.resolve("final.png")));
        PresetIO.atomicWrite(child.resolve("report.json"), PresetIO.JSON.toJson(item));
        cases.add(item);
      }
      suite.add("cases", cases);
      if (fail.equals("missing-case")) cases.remove(0);
      if (fail.equals("identity")) suite.addProperty("engineVersion", "another-build");
      PresetIO.atomicWrite(path.resolve("suite.json"), PresetIO.JSON.toJson(suite));
      if (fail.equals("tamper")) Files.writeString(path.resolve("opal/final.png"), "changed image");
    }
  }

  public static int run() throws Exception {
    count = 0;
    Path root = Files.createTempDirectory(Path.of("build/reports"), "acceptance-contracts-");
    try {
      var backend = new Fake();
      Path success = root.resolve("success");
      var outcome =
          AcceptanceRun.run(
              success,
              BenchmarkWorkloads.Profile.SMOKE,
              AcceptanceRun.Environment.PHYSICAL,
              backend);
      var report = read(success.resolve("results.json"));
      check(
          outcome.exitCode() == 0
              && outcome.automatedStatus().equals("passed")
              && backend.calls == 3,
          "acceptance executes each required step once");
      check(
          report.get("manualReview").getAsString().equals("not_run")
              && report.get("releaseAcceptance").getAsString().equals("manual_review_required")
              && report.get("hardwareAcceptance").getAsString().equals("not_established"),
          "physical declaration cannot manufacture manual or hardware acceptance");
      check(
          report.get("environmentDeclaration").getAsString().equals("physical")
              && report.getAsJsonObject("host").has("java.runtime.version"),
          "declaration and observed runtime are recorded separately");
      String manual = Files.readString(success.resolve("manual-review.md"));
      check(
          manual.contains("- [ ]")
              && !manual.contains("- [x]")
              && manual.contains("winver")
              && manual.contains("fullhd"),
          "manual acceptance starts wholly unrun with hardware and performance fields");
      check(
          report.getAsJsonArray("immutableEvidence").size() == 16,
          "all raw GPU and benchmark files are inventoried");
      for (var value : report.getAsJsonArray("immutableEvidence")) {
        var item = value.getAsJsonObject();
        Path file = success.resolve(item.get("path").getAsString());
        if (!AcceptanceEvidence.sha256(file).equals(item.get("sha256").getAsString()))
          throw new AssertionError("Incorrect evidence hash");
      }
      check(
          report.getAsJsonArray("immutableEvidence").asList().stream()
              .noneMatch(
                  v -> v.getAsJsonObject().get("path").getAsString().equals("manual-review.md")),
          "editable manual observations are excluded from immutable evidence");
      Files.writeString(success.resolve("manual-review.md"), manual + "review notes");
      check(
          read(success.resolve("results.json")).equals(report),
          "manual notes do not rewrite automated results");
      var never = new Fake();
      boolean rejected = false;
      try {
        AcceptanceRun.run(
            success, BenchmarkWorkloads.Profile.SMOKE, AcceptanceRun.Environment.HOSTED, never);
      } catch (IllegalArgumentException expected) {
        rejected = true;
      }
      check(
          rejected && never.calls == 0 && read(success.resolve("results.json")).equals(report),
          "occupied destination is preserved before any check runs");
      for (String failure :
          List.of(
              "installation",
              "gpu",
              "probe-write",
              "benchmark",
              "missing-case",
              "identity",
              "tamper")) {
        var fake = new Fake();
        fake.fail = failure;
        Path destination = root.resolve(failure);
        var result =
            AcceptanceRun.run(
                destination,
                BenchmarkWorkloads.Profile.SMOKE,
                AcceptanceRun.Environment.HOSTED,
                fake);
        var failed = read(destination.resolve("results.json"));
        check(
            result.exitCode() == (failure.equals("gpu") ? 2 : 1)
                && !result.automatedStatus().equals("passed"),
            "failed evidence remains non-passing: " + failure);
        check(
            failed.get("manualReview").getAsString().equals("not_run")
                && failed.get("releaseAcceptance").getAsString().equals("blocked"),
            "failure cannot pass release review: " + failure);
        if (failure.equals("installation"))
          check(
              fake.calls == 1
                  && failed
                      .getAsJsonObject("steps")
                      .getAsJsonObject("gpu")
                      .get("status")
                      .getAsString()
                      .equals("not_run"),
              "native installation failure prevents GPU work");
        if (failure.equals("gpu"))
          check(
              fake.calls == 2
                  && failed
                      .getAsJsonObject("steps")
                      .getAsJsonObject("benchmark")
                      .get("status")
                      .getAsString()
                      .equals("not_run")
                  && Files.exists(destination.resolve("gpu.json")),
              "blocked GPU retains diagnostics and skips performance work");
        if (failure.equals("probe-write"))
          check(
              result.automatedStatus().equals("failed"),
              "missing probe output is a failure rather than fabricated device unavailability");
      }
      try (var children = Files.list(root)) {
        check(
            children.noneMatch(p -> p.getFileName().toString().startsWith(".acceptance-pending-")),
            "completed success/failure bundles leave no owned staging directories");
      }
      Path archives = Files.createDirectory(root.resolve("archives"));
      Files.writeString(archives.resolve("app.jar"), "main");
      Files.writeString(archives.resolve("native.jar"), "native");
      var identity = AcceptanceEvidence.application(archives.resolve("app.jar"));
      check(
          identity.get("kind").getAsString().equals("jar")
              && identity.getAsJsonArray("archives").size() == 2,
          "application identity covers main and sibling dependency archives");
      Path copy = Files.createDirectory(root.resolve("copy"));
      Files.copy(archives.resolve("app.jar"), copy.resolve("app.jar"));
      Files.copy(archives.resolve("native.jar"), copy.resolve("native.jar"));
      check(
          AcceptanceEvidence.application(copy.resolve("app.jar"))
              .get("archiveSetSha256")
              .equals(identity.get("archiveSetSha256")),
          "archive identity is independent of installation directory");
      Files.writeString(copy.resolve("native.jar"), "changed");
      check(
          !AcceptanceEvidence.application(copy.resolve("app.jar"))
              .get("archiveSetSha256")
              .equals(identity.get("archiveSetSha256")),
          "native/dependency archive changes alter build identity");
      check(
          AcceptanceEvidence.application(archives).get("kind").getAsString().equals("classes"),
          "development classes are distinguished from a packaged archive set");
      var fullhd = BenchmarkWorkloads.create(BenchmarkWorkloads.Profile.FULLHD);
      check(
          fullhd.size() == 7
              && fullhd.stream()
                  .allMatch(
                      w ->
                          w.initial().grid == 257
                              && w.input().width == 1920
                              && w.input().height == 1080
                              && w.input().endTick == 480),
          "fullhd pins real 1920x1080 output and odd simulation-grid workloads");
      check(
          fullhd
                  .getFirst()
                  .hash()
                  .equals(
                      BenchmarkWorkloads.create(BenchmarkWorkloads.Profile.FULLHD)
                          .getFirst()
                          .hash())
              && !fullhd
                  .getFirst()
                  .hash()
                  .equals(
                      BenchmarkWorkloads.create(BenchmarkWorkloads.Profile.STANDARD)
                          .getFirst()
                          .hash()),
          "fullhd contracts repeat exactly and differ from the 720p profile");
    } finally {
      try (var paths = Files.walk(root)) {
        for (var path : paths.sorted(Comparator.reverseOrder()).toList())
          Files.deleteIfExists(path);
      }
    }
    return count;
  }
}
