package dev.visualengine;

import com.google.gson.JsonParser;
import dev.visualengine.app.Options;
import dev.visualengine.gpu.*;
import dev.visualengine.model.*;
import dev.visualengine.presets.*;
import dev.visualengine.simulation.*;
import dev.visualengine.time.*;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Pure JVM archive contracts complement actual state continuation in GpuChecks. */
public final class CheckpointChecks {
  private static int count;

  private interface Action {
    void run() throws Exception;
  }

  private static void check(boolean value, String label) {
    count++;
    if (!value) throw new AssertionError(label);
  }

  private static void rejects(Action action, String label) throws Exception {
    try {
      action.run();
    } catch (IOException | IllegalArgumentException e) {
      count++;
      return;
    }
    throw new AssertionError("Accepted " + label);
  }

  public static int run() throws Exception {
    Settings s = new Settings();
    s.grid = 32;
    s.particles = 1;
    s.historyLayers = 2;
    s.installEffectControls(List.of(AuthoringChecks.scalar()), Map.of());
    ControlSignals signals = new ControlSignals();
    signals.tick(s, Playback.DT);
    s.set("warp", 1.8);
    s.setEffectValue("amplitude", List.of(2.));
    signals.tick(s, 2 * Playback.DT);
    var state =
        new Simulation.State(
            1,
            0,
            1,
            1,
            true,
            .2,
            -.1,
            true,
            new Particles.State(false, 1),
            new History.State(1, 0, new long[] {8, -1}));
    var reference = new GpuSnapshot.Reference(32, 32);
    Map<String, byte[]> blobs = new LinkedHashMap<>();
    GpuSnapshot.layout(s, state, reference, 17)
        .forEach((name, size) -> blobs.put(name, new byte[size]));
    ByteBuffer.wrap(blobs.get("flow")).order(ByteOrder.LITTLE_ENDIAN).putFloat(.2f);
    ByteBuffer.wrap(blobs.get("particles"))
        .order(ByteOrder.LITTLE_ENDIAN)
        .putFloat(.1f)
        .putFloat(.2f)
        .putFloat(1)
        .putFloat(3)
        .putInt(7);
    Arrays.fill(blobs.get("reference"), (byte) 0xab);
    var gpu = new GpuSnapshot("test GPU / driver", "a".repeat(64), state, reference, blobs);
    ReplayLog replay = ReplayLog.begin(s, 320, 200);
    replay.recordPointer(1, .2, -.1, .12, true);
    replay.recordPointer(25, .3, .1, .12, false);
    replay.at(17);
    var snapshot =
        new CheckpointIO.Snapshot(
            s,
            17,
            320,
            200,
            signals.snapshot(s),
            new CheckpointIO.Pointer(.2, -.1, true),
            gpu,
            CheckpointIO.ReplayState.capture(replay, new CheckpointIO.Pointer(.2, -.1, true)));
    Path root = Files.createTempDirectory("checkpoint-contract-");
    try {
      Path valid = root.resolve("valid.vstate");
      CheckpointIO.save(valid, snapshot);
      var loaded = CheckpointIO.load(valid);
      check(
          loaded.tick() == 17
              && loaded.width() == 320
              && loaded.height() == 200
              && loaded.gpu().simulation().pigmentIndex() == 1
              && loaded.gpu().simulation().previousBrush()
              && Arrays.equals(
                  state.history().ticks(), loaded.gpu().simulation().history().ticks()),
          "checkpoint metadata round trip");
      check(
          blobs.entrySet().stream()
              .allMatch(e -> Arrays.equals(e.getValue(), loaded.gpu().blobs().get(e.getKey()))),
          "checkpoint binary resource round trip");
      check(
          loaded.signals().equals(snapshot.signals())
              && loaded.settings().seed == s.seed
              && loaded.settings().effectValues.equals(s.effectValues),
          "targets and resolved signals round trip");
      var restored = new ControlSignals();
      restored.restore(loaded.signals(), loaded.settings());
      check(
          restored.tick(s, 18 * Playback.DT).get("warp")
              == signals.tick(s, 18 * Playback.DT).get("warp"),
          "unfinished smoothing continues after deserialization");
      loaded.replay().log().restoreCursor(loaded.replay().cursor());
      check(
          loaded.replay().log().at(18).isEmpty() && loaded.replay().log().at(25).size() == 1,
          "checkpoint resumes replay cursor without reapplying earlier events");
      Playback clock = new Playback();
      clock.accumulate(.001, 1, false);
      clock.restore(17);
      check(
          clock.tick() == 17 && clock.accumulate(.008, 1, false) == 0,
          "restoring tick discards wall clock remainder");
      rejects(() -> clock.restore(-1), "negative checkpoint tick");
      rejects(
          () -> new History.State(1, 0, new long[] {9, -1}).validLayers(2, 17),
          "off-cadence history");
      rejects(() -> CheckpointIO.requireAspect(320, 240, 320, 200), "incompatible viewport aspect");
      rejects(
          () -> Options.parse(new String[] {"--checkpoint", "a", "--seed", "1"}),
          "checkpoint scene overrides");
      check(
          Options.parse(new String[] {"--checkpoint", "a", "--size", "640x400"}).explicitSize,
          "checkpoint accepts explicit output sizing");
      Map<String, byte[]> entries = archive(valid);
      Path bad = root.resolve("bad.vstate");
      var corrupt = copy(entries);
      corrupt.get("state/flow.bin")[0] ^= 1;
      write(bad, corrupt);
      rejects(() -> CheckpointIO.load(bad), "resource checksum mismatch");
      corrupt = copy(entries);
      corrupt.remove("state/age.0.bin");
      write(bad, corrupt);
      rejects(() -> CheckpointIO.load(bad), "missing resource");
      corrupt = copy(entries);
      corrupt.put("../outside.bin", new byte[1]);
      write(bad, corrupt);
      rejects(() -> CheckpointIO.load(bad), "unexpected archive path");
      corrupt = copy(entries);
      var header =
          JsonParser.parseString(
                  new String(corrupt.get("manifest.json"), java.nio.charset.StandardCharsets.UTF_8))
              .getAsJsonObject();
      header.addProperty("checkpointVersion", 999);
      corrupt.put(
          "manifest.json",
          PresetIO.JSON.toJson(header).getBytes(java.nio.charset.StandardCharsets.UTF_8));
      write(bad, corrupt);
      rejects(() -> CheckpointIO.load(bad), "unsupported checkpoint version");
      byte[] truncated = Files.readAllBytes(valid);
      Files.write(bad, Arrays.copyOf(truncated, truncated.length - 20));
      rejects(() -> CheckpointIO.load(bad), "truncated archive central directory");
      var badState =
          new Simulation.State(2, 0, 0, 0, false, 0, 0, false, state.particles(), state.history());
      var invalid =
          new CheckpointIO.Snapshot(
              s,
              17,
              320,
              200,
              snapshot.signals(),
              snapshot.pointer(),
              new GpuSnapshot(gpu.device(), gpu.signature(), badState, reference, blobs),
              snapshot.replay());
      byte[] original = Files.readAllBytes(valid);
      rejects(() -> CheckpointIO.save(valid, invalid), "invalid state save");
      check(
          Arrays.equals(original, Files.readAllBytes(valid)),
          "failed save preserves existing archive");
      Path occupied = root.resolve("occupied");
      Files.createDirectories(occupied);
      Files.writeString(occupied.resolve("keep.txt"), "keep");
      rejects(() -> CheckpointIO.save(occupied, snapshot), "failed atomic replacement");
      try (var paths = Files.list(root)) {
        check(
            paths.noneMatch(p -> p.getFileName().toString().endsWith(".tmp"))
                && Files.readString(occupied.resolve("keep.txt")).equals("keep"),
            "failed replacement cleans temporary file and preserves destination");
      }
    } finally {
      try (var paths = Files.walk(root)) {
        for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
      }
    }
    Settings fixture = s.copy();
    fixture.grid = 65;
    fixture.particles = 257;
    fixture.historyLayers = 3;
    Recipes.apply(fixture, Settings.Recipe.TIME_EXCAVATION);
    fixture.feedbackEnabled =
        fixture.pigmentEnabled = fixture.reactionEnabled = fixture.particlesEnabled = true;
    fixture.graphEnabled = true; // Drives age alongside the stateful material.
    PresetIO.save(Path.of("build/reports/checkpoint-fixture.json"), fixture, 0);
    return count;
  }

  private static Map<String, byte[]> archive(Path path) throws IOException {
    Map<String, byte[]> entries = new LinkedHashMap<>();
    try (ZipFile zip = new ZipFile(path.toFile())) {
      var iterator = zip.entries();
      while (iterator.hasMoreElements()) {
        var entry = iterator.nextElement();
        try (var input = zip.getInputStream(entry)) {
          entries.put(entry.getName(), input.readAllBytes());
        }
      }
    }
    return entries;
  }

  private static Map<String, byte[]> copy(Map<String, byte[]> source) {
    Map<String, byte[]> copy = new LinkedHashMap<>();
    source.forEach((name, bytes) -> copy.put(name, bytes.clone()));
    return copy;
  }

  private static void write(Path path, Map<String, byte[]> entries) throws IOException {
    try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(path))) {
      for (var entry : entries.entrySet()) {
        zip.putNextEntry(new ZipEntry(entry.getKey()));
        zip.write(entry.getValue());
        zip.closeEntry();
      }
    }
  }
}
