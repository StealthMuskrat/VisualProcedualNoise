package dev.visualengine.export;

import com.google.gson.*;
import dev.visualengine.presets.PresetIO;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Read-only consistency checks; no GPU, build authenticity or human acceptance is inferred. */
public final class AcceptanceVerifier {
  private static final long MAX_FILE = 64L * 1024 * 1024, MAX_TOTAL = 512L * 1024 * 1024;
  private static final String MANIFEST = "results.json", MANUAL = "manual-review.md";
  private final Path root, realRoot;
  private final Map<String, JsonObject> inventory = new HashMap<>();

  private AcceptanceVerifier(Path root) throws IOException {
    this.root = root;
    this.realRoot = root.toRealPath();
  }

  public static JsonObject verify(Path directory, String expectedManifestHash) throws IOException {
    try {
      Path root = directory.toAbsolutePath().normalize();
      require(
          Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS),
          "Acceptance root must be a directory");
      require(
          !root.getFileName().toString().startsWith(".acceptance-pending-"),
          "A pending directory is not a published acceptance run");
      return new AcceptanceVerifier(root).inspect(expectedManifestHash);
    } catch (RuntimeException error) {
      throw new IOException("Invalid acceptance evidence: " + error.getMessage(), error);
    }
  }

  private JsonObject inspect(String expectedHash) throws IOException {
    Path manifest = file(MANIFEST);
    var document = EvidenceJson.document(manifest);
    String manifestHash = document.sha256();
    if (expectedHash != null) {
      require(expectedHash.matches("(?i)[0-9a-f]{64}"), "Expected manifest hash must be SHA-256");
      require(manifestHash.equalsIgnoreCase(expectedHash), "Acceptance manifest SHA-256 mismatch");
    }
    JsonObject report = document.value();
    require(number(report, "reportVersion") == 1, "Unsupported acceptance report version");
    String engine = string(report, "engineVersion"), revision = string(report, "algorithmRevision");
    require(revision.equals(PresetIO.REVISION), "Unsupported acceptance algorithm revision");
    var profile =
        BenchmarkWorkloads.Profile.valueOf(string(report, "profile").toUpperCase(Locale.ROOT));
    require(
        string(report, "profile").equals(profile.name().toLowerCase(Locale.ROOT)),
        "Invalid profile spelling");
    String environment = string(report, "environmentDeclaration");
    require(
        Set.of("unspecified", "physical", "hosted").contains(environment),
        "Invalid environment declaration");
    require(
        !Instant.parse(string(report, "completedAt"))
            .isBefore(Instant.parse(string(report, "startedAt"))),
        "Acceptance completion precedes its start");
    require(
        string(report, "manualReview").equals("not_run")
            && string(report, "hardwareAcceptance").equals("not_established")
            && string(report, "manualReviewFile").equals(MANUAL),
        "Evidence invents manual/hardware acceptance");
    file(MANUAL);
    String status = string(report, "automatedStatus");
    require(Set.of("passed", "blocked", "failed").contains(status), "Unknown automated outcome");
    require(
        string(report, "releaseAcceptance")
            .equals(status.equals("passed") ? "manual_review_required" : "blocked"),
        "Release outcome contradicts automated checks");
    statuses(report.getAsJsonObject("steps"), status);
    var host = report.getAsJsonObject("host");
    String os = string(host, "os.name"), arch = string(host, "os.arch");
    string(host, "java.version");
    var windows = report.getAsJsonPrimitive("hostMatchesWindowsX64");
    require(
        windows != null
            && windows.isBoolean()
            && windows.getAsBoolean()
                == (os.toLowerCase(Locale.ROOT).startsWith("windows")
                    && Set.of("amd64", "x86_64").contains(arch.toLowerCase(Locale.ROOT))),
        "Observed host and Windows/x64 flag disagree");
    JsonObject app = report.getAsJsonObject("application");
    application(app);
    JsonArray entries = report.getAsJsonArray("immutableEvidence");
    require(entries != null && entries.size() <= 128, "Evidence inventory exceeds 128 files");
    Set<String> folded = new HashSet<>();
    long total = 0;
    for (JsonElement entry : entries) {
      var item = entry.getAsJsonObject();
      String name = string(item, "path");
      require(
          !name.equals(MANIFEST) && !name.equals(MANUAL) && !name.startsWith("review/"),
          "Mutable review/manifest may not be immutable evidence");
      require(folded.add(name.toLowerCase(Locale.ROOT)), "Repeated/colliding evidence path");
      Path path = file(name);
      long bytes = number(item, "bytes");
      require(
          bytes >= 0 && bytes <= MAX_FILE && bytes <= MAX_TOTAL - total,
          "Evidence exceeds 64 MiB per file or 512 MiB total");
      total += bytes;
      require(
          Files.size(path) == bytes
              && AcceptanceEvidence.sha256(path, bytes).equals(digest(item, "sha256")),
          "Evidence size/hash mismatch: " + name);
      inventory.put(name, item);
    }
    int reviewFiles = tree();
    String gpuStatus = string(report.getAsJsonObject("steps").getAsJsonObject("gpu"), "status");
    if (gpuStatus.equals("passed") || gpuStatus.equals("blocked")) {
      require(inventory.containsKey("gpu.json"), "GPU evidence is missing");
      var gpu = EvidenceJson.read(file("gpu.json"));
      require(number(gpu, "reportVersion") == 1, "Unsupported GPU report version");
      identity(gpu, engine, revision);
      require(
          string(gpu, "os").equals(os)
              && string(gpu, "architecture").equals(arch)
              && string(gpu, "javaVersion").equals(string(host, "java.version")),
          "GPU report and observed runtime disagree");
      if (gpuStatus.equals("blocked")) {
        require(
            string(gpu, "status").equals("failed")
                && !Files.exists(root.resolve("benchmark"), LinkOption.NOFOLLOW_LINKS)
                && inventory.keySet().stream().noneMatch(p -> p.startsWith("benchmark/")),
            "Blocked probe invents compatible GPU/workload results");
      } else {
        require(
            string(gpu, "status").equals("compatible")
                && gpu.get("capabilities").isJsonObject()
                && gpu.getAsJsonArray("baselineFailures").isEmpty(),
            "Passing run lacks compatible GPU evidence");
        if (status.equals("passed")) suite(profile, engine, revision, gpu);
      }
    }
    require(
        AcceptanceEvidence.sha256(manifest, EvidenceJson.MAX_BYTES).equals(manifestHash),
        "Acceptance manifest changed during verification");
    var summary = new JsonObject();
    summary.addProperty("verificationVersion", 1);
    summary.addProperty("verificationStatus", "consistent");
    summary.addProperty("checkerEngineVersion", PresetIO.ENGINE_VERSION);
    summary.addProperty("recordedEngineVersion", engine);
    summary.addProperty("manifestSha256", manifestHash);
    summary.addProperty("expectedManifestHashChecked", expectedHash != null);
    summary.addProperty("automatedStatus", status);
    summary.add("releaseAcceptance", report.get("releaseAcceptance"));
    summary.addProperty("manualReview", "not_run");
    summary.addProperty("hardwareAcceptance", "not_established");
    summary.addProperty("environmentDeclaration", environment);
    summary.addProperty("profile", profile.name().toLowerCase(Locale.ROOT));
    summary.addProperty("immutableFiles", inventory.size());
    summary.addProperty("immutableBytes", total);
    summary.addProperty("unhashedReviewFiles", reviewFiles);
    summary.addProperty("applicationKind", string(app, "kind"));
    if (app.has("archiveSetSha256"))
      summary.add("recordedArchiveSetSha256", app.get("archiveSetSha256"));
    summary.addProperty(
        "scope",
        "Read-only file integrity and supported report/workload consistency. Manifest hashes need"
            + " an independently trusted reference for authenticity. Recorded archives are not"
            + " compared with this installation. Manual notes/review files are not certified;"
            + " device, artistic, endurance and performance acceptance remain unestablished.");
    return summary;
  }

  private static void statuses(JsonObject steps, String outcome) throws IOException {
    require(
        steps != null && steps.keySet().equals(Set.of("installation", "gpu", "benchmark")),
        "Invalid acceptance steps");
    List<String> state = new ArrayList<>();
    for (String name : List.of("installation", "gpu", "benchmark")) {
      var step = steps.getAsJsonObject(name);
      String status = string(step, "status");
      state.add(status);
      if (!status.equals("not_run")) {
        double wall = step.get("wallMs").getAsDouble();
        require(
            step.getAsJsonPrimitive("wallMs").isNumber() && Double.isFinite(wall) && wall >= 0,
            "Invalid step duration");
        if (status.equals("failed") || status.equals("blocked")) {
          string(step.getAsJsonObject("failure"), "type");
          require(
              string(step.getAsJsonObject("failure"), "message").length() <= 8192,
              "Failure detail exceeds its bound");
        }
      }
    }
    require(
        switch (outcome) {
          case "passed" -> state.equals(List.of("passed", "passed", "passed"));
          case "blocked" -> state.equals(List.of("passed", "blocked", "not_run"));
          default ->
              state.equals(List.of("failed", "not_run", "not_run"))
                  || state.equals(List.of("passed", "failed", "not_run"))
                  || state.equals(List.of("passed", "passed", "failed"));
        },
        "Step order contradicts the automated outcome");
  }

  private static void application(JsonObject app) throws IOException {
    String kind = string(app, "kind");
    JsonArray archives = app.getAsJsonArray("archives");
    require(archives != null, "Missing archive identity");
    if (kind.equals("classes")) {
      require(
          archives.isEmpty() && !app.has("archiveSetSha256") && !app.has("mainArchive"),
          "Class-directory run invents packaged identity");
      return;
    }
    require(
        kind.equals("jar") && !archives.isEmpty() && archives.size() <= 64,
        "Invalid packaged archive set");
    Set<String> names = new HashSet<>();
    String previous = "";
    boolean main = false;
    for (var entry : archives) {
      var item = entry.getAsJsonObject();
      String name = string(item, "path");
      portable(name);
      require(
          !name.contains("/")
              && name.endsWith(".jar")
              && previous.compareTo(name) < 0
              && names.add(name.toLowerCase(Locale.ROOT))
              && number(item, "bytes") >= 0,
          "Invalid/unsorted archive identity");
      digest(item, "sha256");
      main |= name.equals(string(app, "mainArchive"));
      previous = name;
    }
    require(
        main && BenchmarkWorkloads.hash(archives).equals(digest(app, "archiveSetSha256")),
        "Archive-set fingerprint is inconsistent");
  }

  private void suite(
      BenchmarkWorkloads.Profile profile, String engine, String revision, JsonObject gpu)
      throws IOException {
    require(inventory.containsKey("benchmark/suite.json"), "Benchmark manifest is missing");
    var suite = EvidenceJson.read(file("benchmark/suite.json"));
    identity(suite, engine, revision);
    require(
        number(suite, "suiteVersion") == 1
            && string(suite, "profile").equals(profile.name().toLowerCase(Locale.ROOT))
            && Objects.equals(suite.get("deviceCapabilities"), gpu.get("capabilities")),
        "Inconsistent suite context/profile");
    var expected = BenchmarkWorkloads.create(profile);
    var cases = suite.getAsJsonArray("cases");
    require(cases != null && cases.size() == expected.size(), "Seven workload cases are required");
    for (int i = 0; i < expected.size(); i++) {
      var work = expected.get(i);
      String prefix = "benchmark/" + work.id() + "/";
      for (String name :
          List.of(
              "workload.json",
              "initial.json",
              "input.replay.json",
              "report.json",
              "final.png",
              "final.png.json"))
        require(
            inventory.containsKey(prefix + name), "Missing workload evidence: " + prefix + name);
      var item = cases.get(i).getAsJsonObject();
      identity(item, engine, revision);
      require(
          string(item, "id").equals(work.id())
              && digest(item, "workloadHash").equals(work.hash())
              && number(item, "referenceTick") == profile.ticks()
              && number(item, "width") == profile.width
              && number(item, "height") == profile.height
              && number(item, "warmupFrames") == profile.warmup
              && number(item, "samples") == profile.samples
              && number(item, "frames") == profile.frames()
              && number(item, "ticksPerFrame") == BenchmarkWorkloads.TICKS_PER_FRAME
              && item.equals(EvidenceJson.read(file(prefix + "report.json")))
              && digest(item, "imageHash")
                  .equals(string(inventory.get(prefix + "final.png"), "sha256")),
          "Inconsistent workload report: " + work.id());
      require(
          EvidenceJson.read(file(prefix + "workload.json")).equals(work.contract()),
          "Workload contract differs from the pinned profile: " + work.id());
      var initial = EvidenceJson.read(file(prefix + "initial.json"));
      var expectedInitial = PresetIO.document(work.initial(), 0);
      withoutEngine(initial, engine);
      expectedInitial.remove("engineVersion");
      require(initial.equals(expectedInitial), "Initial preset differs from the workload");
      var replay = EvidenceJson.read(file(prefix + "input.replay.json"));
      var expectedReplay = PresetIO.JSON.toJsonTree(work.input()).getAsJsonObject();
      withoutEngine(replay.getAsJsonObject("initialPreset"), engine);
      expectedReplay.getAsJsonObject("initialPreset").remove("engineVersion");
      require(replay.equals(expectedReplay), "Input replay differs from the workload");
      var sidecar = EvidenceJson.read(file(prefix + "final.png.json"));
      identity(sidecar, engine, revision);
      for (String key :
          List.of(
              "schemaVersion",
              "rootSeed",
              "referenceTick",
              "fixedTickSeconds",
              "settings",
              "referenceMotionClock",
              "referenceColorClock",
              "device"))
        require(
            Objects.equals(sidecar.get(key), item.get(key)),
            "Image metadata contradicts its case report: " + key);
      require(
          number(sidecar, "width") == profile.width
              && number(sidecar, "height") == profile.height
              && number(sidecar, "referenceTick") == profile.ticks(),
          "Image metadata differs from the workload");
      byte[] header;
      try (var input =
          Files.newInputStream(file(prefix + "final.png"), LinkOption.NOFOLLOW_LINKS)) {
        header = input.readNBytes(24);
      }
      require(
          header.length == 24
              && Arrays.equals(
                  Arrays.copyOf(header, 8), new byte[] {(byte) 137, 80, 78, 71, 13, 10, 26, 10})
              && ByteBuffer.wrap(header, 8, 4).getInt() == 13
              && ByteBuffer.wrap(header, 12, 4).getInt() == 0x49484452
              && ByteBuffer.wrap(header, 16, 4).getInt() == profile.width
              && ByteBuffer.wrap(header, 20, 4).getInt() == profile.height,
          "Invalid PNG header/dimensions");
    }
  }

  private static void withoutEngine(JsonObject value, String engine) throws IOException {
    require(
        string(value, "engineVersion").equals(engine),
        "Embedded build differs from the recorded build");
    value.remove("engineVersion");
  }

  private static void identity(JsonObject value, String engine, String revision)
      throws IOException {
    require(
        string(value, "engineVersion").equals(engine)
            && string(value, "algorithmRevision").equals(revision),
        "Evidence build/algorithm differs from the manifest");
  }

  private int tree() throws IOException {
    Set<String> seen = new HashSet<>(), folded = new HashSet<>();
    int count = 0, review = 0;
    try (var paths = Files.walk(root, 9)) {
      for (var iterator = paths.iterator(); iterator.hasNext(); ) {
        Path path = iterator.next();
        if (path.equals(root)) continue;
        require(++count <= 512, "Evidence tree exceeds 512 entries");
        String name = root.relativize(path).toString().replace(java.io.File.separatorChar, '/');
        portable(name);
        require(
            folded.add(name.toLowerCase(Locale.ROOT)) && !Files.isSymbolicLink(path),
            "Evidence contains a symbolic link or colliding path");
        require(
            path.toRealPath().equals(realRoot.resolve(root.relativize(path))),
            "Evidence may not traverse filesystem aliases");
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) continue;
        require(
            Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS),
            "Evidence contains a nonregular file");
        if (name.startsWith("review/")) review++;
        else if (!name.equals(MANIFEST) && !name.equals(MANUAL)) {
          require(inventory.containsKey(name), "Unlisted evidence file: " + name);
          seen.add(name);
        }
      }
    }
    require(seen.equals(inventory.keySet()), "Evidence inventory is incomplete");
    return review;
  }

  private Path file(String name) throws IOException {
    portable(name);
    Path result = root;
    for (String part : name.split("/")) {
      result = result.resolve(part);
      require(!Files.isSymbolicLink(result), "Evidence may not traverse symbolic links");
      require(
          result.toRealPath().equals(realRoot.resolve(root.relativize(result))),
          "Evidence may not traverse filesystem aliases");
    }
    require(
        Files.isRegularFile(result, LinkOption.NOFOLLOW_LINKS),
        "Evidence file is missing: " + name);
    return result;
  }

  private static void portable(String name) throws IOException {
    require(name.length() <= 240 && !name.isEmpty(), "Invalid evidence path length");
    String[] parts = name.split("/", -1);
    require(parts.length <= 8, "Evidence path exceeds eight components");
    for (String part : parts) {
      require(
          !part.isEmpty()
              && !part.equals(".")
              && !part.equals("..")
              && part.length() <= 120
              && !part.endsWith(".")
              && !part.endsWith(" ")
              && part.chars().noneMatch(c -> c < 32 || "<>:\\|?*\"".indexOf(c) >= 0)
              && !part.toUpperCase(Locale.ROOT)
                  .matches("(?:CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\\..*)?"),
          "Unsafe/nonportable evidence path");
    }
  }

  private static String string(JsonObject object, String key) throws IOException {
    JsonElement element = object == null ? null : object.get(key);
    require(
        element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isString(),
        "Expected evidence string: " + key);
    String value = element.getAsString();
    require(
        value.length() <= 8192 && (!value.isEmpty() || key.equals("message")),
        "Invalid evidence string length: " + key);
    return value;
  }

  private static String digest(JsonObject object, String key) throws IOException {
    String hash = string(object, key);
    require(hash.matches("[0-9a-f]{64}"), "Invalid SHA-256: " + key);
    return hash;
  }

  private static long number(JsonObject object, String key) throws IOException {
    JsonElement element = object.get(key);
    require(
        element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber(),
        "Expected evidence integer: " + key);
    return new BigDecimal(element.getAsString()).longValueExact();
  }

  private static void require(boolean condition, String message) throws IOException {
    if (!condition) throw new IOException(message);
  }
}
