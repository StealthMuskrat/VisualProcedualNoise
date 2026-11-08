package dev.visualengine.export;

import com.google.gson.*;
import dev.visualengine.presets.PresetIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;

public final class AcceptanceVerifierChecks {
  private static int count;
  private static Path root, baseline;

  private interface Edit {
    void apply(Path path, JsonObject report) throws Exception;
  }

  private static void check(boolean ok, String message) {
    count++;
    if (!ok) throw new AssertionError(message);
  }

  private static JsonObject read(Path path) throws IOException {
    return EvidenceJson.read(path);
  }

  private static void write(Path path, JsonObject value) throws IOException {
    Files.writeString(path, PresetIO.JSON.toJson(value));
  }

  private static Path copy(String name) throws IOException {
    Path destination = root.resolve(name);
    try (var paths = Files.walk(baseline)) {
      for (var path : paths.toList()) {
        Path target = destination.resolve(baseline.relativize(path));
        if (Files.isDirectory(path)) Files.createDirectories(target);
        else Files.copy(path, target);
      }
    }
    return destination;
  }

  private static void reject(String label, Edit edit) throws Exception {
    Path path = copy("invalid-" + count);
    var report = read(path.resolve("results.json"));
    edit.apply(path, report);
    write(path.resolve("results.json"), report);
    try {
      AcceptanceVerifier.verify(path, null);
    } catch (IOException expected) {
      count++;
      return;
    }
    throw new AssertionError("Accepted " + label);
  }

  private static void reindex(Path path, JsonObject report) throws IOException {
    report.add("immutableEvidence", AcceptanceEvidence.artifacts(path));
  }

  private static final class Backend implements AcceptanceRun.Checks {
    final String failure;

    Backend(String failure) {
      this.failure = failure;
    }

    public String installation() throws Exception {
      if (failure.equals("installation")) throw new IOException("fixture installation failure");
      return "fixture installation";
    }

    public void gpu(Path path) throws Exception {
      var gpu = GpuReport.document(null, new IOException("fixture unavailable device"));
      if (!failure.equals("gpu")) {
        gpu.addProperty("status", "compatible");
        gpu.add("capabilities", new JsonObject());
        gpu.add("baselineFailures", new JsonArray());
        gpu.remove("failure");
      }
      Files.writeString(path, GpuReport.encode(gpu));
      if (failure.equals("gpu")) throw new IOException("fixture unavailable device");
    }

    public void benchmark(Path path, BenchmarkWorkloads.Profile profile) throws Exception {
      if (failure.equals("benchmark")) throw new IOException("fixture workload failure");
      Files.createDirectory(path);
      var suite = new JsonObject();
      suite.addProperty("suiteVersion", 1);
      suite.addProperty("engineVersion", PresetIO.ENGINE_VERSION);
      suite.addProperty("algorithmRevision", PresetIO.REVISION);
      suite.addProperty("profile", profile.name().toLowerCase(Locale.ROOT));
      suite.add("deviceCapabilities", new JsonObject());
      var cases = new JsonArray();
      for (var work : BenchmarkWorkloads.create(profile)) {
        Path directory = Files.createDirectory(path.resolve(work.id()));
        write(directory.resolve("workload.json"), work.contract());
        write(directory.resolve("initial.json"), PresetIO.document(work.initial(), 0));
        work.input().save(directory.resolve("input.replay.json"));
        ImageIO.write(
            new BufferedImage(profile.width, profile.height, BufferedImage.TYPE_INT_RGB),
            "PNG",
            directory.resolve("final.png").toFile());
        var report = PresetIO.document(work.initial(), profile.ticks());
        report.addProperty("id", work.id());
        report.addProperty("workloadHash", work.hash());
        report.addProperty("width", profile.width);
        report.addProperty("height", profile.height);
        report.addProperty("warmupFrames", profile.warmup);
        report.addProperty("samples", profile.samples);
        report.addProperty("frames", profile.frames());
        report.addProperty("ticksPerFrame", BenchmarkWorkloads.TICKS_PER_FRAME);
        report.addProperty("imageHash", AcceptanceEvidence.sha256(directory.resolve("final.png")));
        write(directory.resolve("report.json"), report);
        write(directory.resolve("final.png.json"), report);
        cases.add(report);
      }
      suite.add("cases", cases);
      write(path.resolve("suite.json"), suite);
    }
  }

  public static int run() throws Exception {
    count = 0;
    root = Files.createTempDirectory(Path.of("build/reports"), "acceptance-verifier-contracts-");
    try {
      baseline = root.resolve("baseline");
      AcceptanceRun.run(
          baseline,
          BenchmarkWorkloads.Profile.SMOKE,
          AcceptanceRun.Environment.PHYSICAL,
          new Backend(""));
      String before = AcceptanceEvidence.sha256(baseline.resolve("results.json"));
      var verified = AcceptanceVerifier.verify(baseline, before.toUpperCase(Locale.ROOT));
      check(
          verified.get("verificationStatus").getAsString().equals("consistent")
              && verified.get("expectedManifestHashChecked").getAsBoolean()
              && verified.get("manifestSha256").getAsString().equals(before)
              && verified.get("immutableFiles").getAsInt() == 44,
          "valid complete evidence and independently supplied digest");
      check(
          verified.get("manualReview").getAsString().equals("not_run")
              && verified.get("hardwareAcceptance").getAsString().equals("not_established")
              && verified.get("releaseAcceptance").getAsString().equals("manual_review_required"),
          "integrity never becomes human/hardware acceptance");
      check(
          AcceptanceEvidence.sha256(baseline.resolve("results.json")).equals(before),
          "verification preserves input manifest bytes");
      Path edited = copy("human-notes");
      Files.writeString(
          edited.resolve("manual-review.md"), "- [x] Human observation; review remains separate");
      Files.createDirectory(edited.resolve("review"));
      Files.writeString(edited.resolve("review/observations.txt"), "operator evidence");
      var notes = AcceptanceVerifier.verify(edited, before);
      check(
          notes.get("unhashedReviewFiles").getAsInt() == 1
              && notes.get("manualReview").getAsString().equals("not_run"),
          "manual notes and supporting review files stay uncertified");
      for (String failure : List.of("installation", "gpu", "benchmark")) {
        Path path = root.resolve(failure);
        AcceptanceRun.run(
            path,
            BenchmarkWorkloads.Profile.SMOKE,
            AcceptanceRun.Environment.HOSTED,
            new Backend(failure));
        var result = AcceptanceVerifier.verify(path, null);
        check(
            result
                    .get("automatedStatus")
                    .getAsString()
                    .equals(failure.equals("gpu") ? "blocked" : "failed")
                && result.get("releaseAcceptance").getAsString().equals("blocked"),
            "consistent failed/blocked evidence retains its outcome");
      }
      Path previous = copy("previous-build");
      try (var paths = Files.walk(previous)) {
        for (var file : paths.filter(p -> p.toString().endsWith(".json")).toList())
          Files.writeString(
              file, Files.readString(file).replace(PresetIO.ENGINE_VERSION, "0.29.0"));
      }
      var previousReport = read(previous.resolve("results.json"));
      reindex(previous, previousReport);
      write(previous.resolve("results.json"), previousReport);
      check(
          AcceptanceVerifier.verify(previous, null)
              .get("recordedEngineVersion")
              .getAsString()
              .equals("0.29.0"),
          "supported old engine evidence is independent of this installation");
      Path archives = Files.createDirectory(root.resolve("archives"));
      Files.writeString(archives.resolve("app.jar"), "main");
      Files.writeString(archives.resolve("native.jar"), "native");
      Path packaged = copy("packaged-identity");
      var packagedReport = read(packaged.resolve("results.json"));
      packagedReport.add(
          "application", AcceptanceEvidence.application(archives.resolve("app.jar")));
      write(packaged.resolve("results.json"), packagedReport);
      check(
          AcceptanceVerifier.verify(packaged, null)
              .get("applicationKind")
              .getAsString()
              .equals("jar"),
          "recorded archive-set identity verifies without original JARs");
      try {
        AcceptanceVerifier.verify(baseline, "0".repeat(64));
        throw new AssertionError("accepted wrong trusted digest");
      } catch (IOException expected) {
        count++;
      }
      try {
        AcceptanceVerifier.verify(baseline, "invalid");
        throw new AssertionError("accepted malformed digest");
      } catch (IOException expected) {
        count++;
      }
      reject(
          "modified immutable bytes",
          (p, r) -> Files.writeString(p.resolve("benchmark/opal/final.png"), "changed"));
      reject(
          "missing immutable file",
          (p, r) -> Files.delete(p.resolve("benchmark/opal/initial.json")));
      reject(
          "unlisted extra file", (p, r) -> Files.writeString(p.resolve("extra.txt"), "untracked"));
      reject("omitted inventory record", (p, r) -> r.getAsJsonArray("immutableEvidence").remove(0));
      reject(
          "duplicated inventory record",
          (p, r) ->
              r.getAsJsonArray("immutableEvidence")
                  .add(r.getAsJsonArray("immutableEvidence").get(0).deepCopy()));
      for (String invalid :
          List.of(
              "../outside",
              "/absolute",
              "C:/absolute",
              "benchmark\\file",
              "gpu.json ",
              "CON.txt",
              "review/unhashed.txt",
              "manual-review.md",
              "results.json"))
        reject(
            "unsafe/mutable inventory path",
            (p, r) ->
                r.getAsJsonArray("immutableEvidence")
                    .get(0)
                    .getAsJsonObject()
                    .addProperty("path", invalid));
      reject(
          "fractional byte count",
          (p, r) ->
              r.getAsJsonArray("immutableEvidence")
                  .get(0)
                  .getAsJsonObject()
                  .addProperty("bytes", 0.5));
      reject(
          "oversized byte count",
          (p, r) ->
              r.getAsJsonArray("immutableEvidence")
                  .get(0)
                  .getAsJsonObject()
                  .addProperty("bytes", Long.MAX_VALUE));
      reject(
          "invalid SHA",
          (p, r) ->
              r.getAsJsonArray("immutableEvidence")
                  .get(0)
                  .getAsJsonObject()
                  .addProperty("sha256", "x".repeat(64)));
      reject("invented human review", (p, r) -> r.addProperty("manualReview", "passed"));
      reject("invented hardware pass", (p, r) -> r.addProperty("hardwareAcceptance", "passed"));
      reject("invented release pass", (p, r) -> r.addProperty("releaseAcceptance", "accepted"));
      reject("unknown report version", (p, r) -> r.addProperty("reportVersion", 2));
      reject("unknown algorithm", (p, r) -> r.addProperty("algorithmRevision", "future"));
      reject("unknown profile", (p, r) -> r.addProperty("profile", "future"));
      reject("unknown environment", (p, r) -> r.addProperty("environmentDeclaration", "certified"));
      reject(
          "incorrect host flag",
          (p, r) ->
              r.addProperty(
                  "hostMatchesWindowsX64", !r.get("hostMatchesWindowsX64").getAsBoolean()));
      reject(
          "inconsistent probe runtime",
          (p, r) -> {
            Path path = p.resolve("gpu.json");
            var gpu = read(path);
            gpu.addProperty("os", "different OS");
            write(path, gpu);
            reindex(p, r);
          });
      reject("time reversal", (p, r) -> r.addProperty("completedAt", "1970-01-01T00:00:00Z"));
      reject(
          "incorrect step order",
          (p, r) ->
              r.getAsJsonObject("steps").getAsJsonObject("gpu").addProperty("status", "not_run"));
      reject(
          "negative step time",
          (p, r) -> r.getAsJsonObject("steps").getAsJsonObject("gpu").addProperty("wallMs", -1));
      reject(
          "classes invent archive fingerprint",
          (p, r) ->
              r.getAsJsonObject("application").addProperty("archiveSetSha256", "0".repeat(64)));
      reject(
          "archive aggregate mismatch",
          (p, r) -> {
            r.add("application", AcceptanceEvidence.application(archives.resolve("app.jar")));
            r.getAsJsonObject("application").addProperty("archiveSetSha256", "0".repeat(64));
          });
      for (String name :
          List.of(
              "gpu.json",
              "benchmark/suite.json",
              "benchmark/opal/report.json",
              "benchmark/opal/workload.json",
              "benchmark/opal/initial.json",
              "benchmark/opal/input.replay.json",
              "benchmark/opal/final.png.json"))
        reject(
            "rehashed inconsistent " + name,
            (p, r) -> {
              Path path = p.resolve(name);
              var data = read(path);
              if (name.endsWith("workload.json")) data.addProperty("warmupFrames", 99);
              else if (name.endsWith("initial.json")) data.addProperty("rootSeed", "99");
              else if (name.endsWith("replay.json")) data.addProperty("endTick", 99);
              else if (name.endsWith("png.json")) data.addProperty("width", 99);
              else data.addProperty("engineVersion", "different");
              write(path, data);
              reindex(p, r);
            });
      reject(
          "rehashed PNG header",
          (p, r) -> {
            Path path = p.resolve("benchmark/opal/final.png");
            byte[] png = Files.readAllBytes(path);
            png[0] = 0;
            Files.write(path, png);
            var suite = read(p.resolve("benchmark/suite.json"));
            var item = suite.getAsJsonArray("cases").get(0).getAsJsonObject();
            item.addProperty("imageHash", AcceptanceEvidence.sha256(path));
            write(p.resolve("benchmark/opal/report.json"), item);
            write(p.resolve("benchmark/suite.json"), suite);
            reindex(p, r);
          });
      Path malformed = copy("malformed-json");
      for (String text :
          List.of(
              "{\"reportVersion\":1,\"reportVersion\":1}",
              "{} trailing",
              "{/*comment*/}",
              "{\"deep\":" + "[".repeat(34) + "0" + "]".repeat(34) + "}",
              "{\"reportVersion\":\"1\"}")) {
        Files.writeString(malformed.resolve("results.json"), text);
        try {
          AcceptanceVerifier.verify(malformed, null);
          throw new AssertionError("accepted malformed evidence JSON");
        } catch (IOException expected) {
          count++;
        }
      }
      Files.write(malformed.resolve("results.json"), new byte[EvidenceJson.MAX_BYTES + 1]);
      try {
        AcceptanceVerifier.verify(malformed, null);
        throw new AssertionError("accepted oversized manifest");
      } catch (IOException expected) {
        count++;
      }
      Path pending = copy(".acceptance-pending-test");
      try {
        AcceptanceVerifier.verify(pending, null);
        throw new AssertionError("accepted unpublished pending run");
      } catch (IOException expected) {
        count++;
      }
      Path linked = copy("linked-evidence");
      Path linkedFile = linked.resolve("benchmark/opal/initial.json");
      Files.delete(linkedFile);
      try {
        Files.createSymbolicLink(
            linkedFile, baseline.resolve("benchmark/opal/initial.json").toAbsolutePath());
        try {
          AcceptanceVerifier.verify(linked, null);
          throw new AssertionError("accepted symbolic evidence link");
        } catch (IOException expected) {
          count++;
        }
      } catch (UnsupportedOperationException | java.nio.file.FileSystemException unavailable) {
        // Windows symlink creation can require a privilege unavailable to the hosted runner.
      }
    } finally {
      try (var paths = Files.walk(root)) {
        for (var path : paths.sorted(Comparator.reverseOrder()).toList())
          Files.deleteIfExists(path);
      }
    }
    return count;
  }
}
