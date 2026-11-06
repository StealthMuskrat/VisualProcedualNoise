package dev.visualengine.presets;

import com.google.gson.JsonObject;
import dev.visualengine.gpu.*;
import dev.visualengine.model.Settings;
import dev.visualengine.simulation.Simulation;
import dev.visualengine.time.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.zip.*;

/** Versioned, bounded archive. No entry is extracted to a caller-supplied filesystem path. */
public final class CheckpointIO {
  public static final int VERSION = 1;
  private static final int MANIFEST_LIMIT = 72 * 1024 * 1024;
  private static final long FILE_LIMIT = 640L * 1024 * 1024;

  private CheckpointIO() {}

  public record Pointer(double x, double y, boolean brush) {
    public void validate() {
      if (!Double.isFinite(x) || !Double.isFinite(y) || Math.abs(x) > 256 || Math.abs(y) > 256)
        throw new IllegalArgumentException("Invalid checkpoint pointer");
    }
  }

  public record ReplayState(ReplayLog log, int cursor, Pointer pointer) {
    public static ReplayState capture(ReplayLog log, Pointer pointer) {
      if (log == null) return null;
      return new ReplayState(
          PresetIO.JSON.fromJson(PresetIO.JSON.toJson(log), ReplayLog.class),
          log.cursor(),
          pointer);
    }

    private void validate(long tick, int width, int height) {
      if (log == null || pointer == null)
        throw new IllegalArgumentException("Missing checkpoint replay");
      log.validate();
      pointer.validate();
      if (cursor < 0
          || cursor > log.events.size()
          || cursor > 0 && log.events.get(cursor - 1).tick() > tick
          || tick > 0 && cursor < log.events.size() && log.events.get(cursor).tick() <= tick
          || tick > log.endTick && pointer.brush())
        throw new IllegalArgumentException("Checkpoint replay cursor does not match tick");
      requireAspect(width, height, log.width, log.height);
    }
  }

  public record Snapshot(
      Settings settings,
      long tick,
      int width,
      int height,
      ControlSignals.State signals,
      Pointer pointer,
      GpuSnapshot gpu,
      ReplayState replay) {
    public Snapshot {
      settings = settings == null ? null : settings.copy();
    }

    public void validate() {
      validateContext(settings, tick, width, height, signals, pointer, replay);
      if (gpu == null) throw new IllegalArgumentException("Missing checkpoint GPU state");
      gpu.validate(settings, tick);
    }
  }

  private static void validateContext(
      Settings settings,
      long tick,
      int width,
      int height,
      ControlSignals.State signals,
      Pointer pointer,
      ReplayState replay) {
    if (settings == null
        || signals == null
        || pointer == null
        || tick < 0
        || tick > (1L << 52)
        || width < 32
        || height < 32
        || width > 8192
        || height > 8192
        || (long) width * height > 33_554_432)
      throw new IllegalArgumentException("Invalid checkpoint header");
    settings.validate();
    signals.validate(settings);
    pointer.validate();
    if (replay != null) replay.validate(tick, width, height);
  }

  private record Resource(int length, String sha256) {}

  private record Header(
      int checkpointVersion,
      String engineVersion,
      String algorithmRevision,
      String byteOrder,
      long tick,
      int width,
      int height,
      JsonObject preset,
      ControlSignals.State signals,
      Pointer pointer,
      String device,
      String programSignature,
      Simulation.State simulation,
      GpuSnapshot.Reference reference,
      ReplayState replay,
      Map<String, Resource> resources) {}

  public static void requireAspect(int width, int height, int savedWidth, int savedHeight) {
    if (height <= 0
        || savedHeight <= 0
        || Math.abs((double) width / height - (double) savedWidth / savedHeight) > 1e-6)
      throw new IllegalArgumentException(
          "Checkpoint requires the saved viewport aspect ratio: " + savedWidth + "x" + savedHeight);
  }

  private static String hash(byte[] data) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    } catch (NoSuchAlgorithmException e) {
      throw new AssertionError(e);
    }
  }

  public static void save(Path path, Snapshot snapshot) throws IOException {
    snapshot.validate();
    var gpu = snapshot.gpu();
    Map<String, Resource> resources = new TreeMap<>();
    gpu.blobs()
        .forEach((name, bytes) -> resources.put(name, new Resource(bytes.length, hash(bytes))));
    Header header =
        new Header(
            VERSION,
            PresetIO.ENGINE_VERSION,
            PresetIO.REVISION,
            "little-endian",
            snapshot.tick(),
            snapshot.width(),
            snapshot.height(),
            PresetIO.document(snapshot.settings(), snapshot.tick()),
            snapshot.signals(),
            snapshot.pointer(),
            gpu.device(),
            gpu.signature(),
            gpu.simulation(),
            gpu.reference(),
            snapshot.replay(),
            resources);
    byte[] manifest = PresetIO.JSON.toJson(header).getBytes(StandardCharsets.UTF_8);
    if (manifest.length > MANIFEST_LIMIT)
      throw new IOException("Checkpoint manifest exceeds 72 MiB");
    Path target = path.toAbsolutePath();
    Files.createDirectories(target.getParent());
    Path temp = Files.createTempFile(target.getParent(), ".visual-checkpoint-", ".tmp");
    try {
      try (ZipOutputStream zip =
          new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(temp)))) {
        entry(zip, "manifest.json", manifest);
        for (String name : resources.keySet())
          entry(zip, "state/" + name + ".bin", gpu.blobs().get(name));
      }
      if (Files.size(temp) > FILE_LIMIT)
        throw new IOException("Checkpoint archive exceeds 640 MiB");
      try {
        Files.move(
            temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      } catch (AtomicMoveNotSupportedException e) {
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
      }
    } finally {
      Files.deleteIfExists(temp);
    }
  }

  private static void entry(ZipOutputStream zip, String name, byte[] bytes) throws IOException {
    ZipEntry entry = new ZipEntry(name);
    entry.setTime(0);
    zip.putNextEntry(entry);
    zip.write(bytes);
    zip.closeEntry();
  }

  private static byte[] read(ZipFile zip, ZipEntry entry, int limit, boolean exact)
      throws IOException {
    if (entry == null
        || entry.isDirectory()
        || entry.getSize() < 0
        || entry.getSize() > limit
        || exact && entry.getSize() != limit)
      throw new IOException("Checkpoint entry size mismatch");
    // Trust neither the central directory's size nor compressed size when bounding inflation.
    try (InputStream input = zip.getInputStream(entry)) {
      byte[] bytes = input.readNBytes((int) entry.getSize());
      if (bytes.length != entry.getSize() || input.read() != -1)
        throw new IOException("Truncated or oversized checkpoint entry");
      CRC32 crc = new CRC32();
      crc.update(bytes);
      if (crc.getValue() != entry.getCrc()) throw new IOException("Checkpoint entry CRC mismatch");
      return bytes;
    }
  }

  public static Snapshot load(Path path) throws IOException {
    if (Files.size(path) > FILE_LIMIT) throw new IOException("Checkpoint archive exceeds 640 MiB");
    try (ZipFile zip = new ZipFile(path.toFile())) {
      Map<String, ZipEntry> entries = new LinkedHashMap<>();
      var iterator = zip.entries();
      while (iterator.hasMoreElements()) {
        ZipEntry entry = iterator.nextElement();
        if (entries.size() >= 64 || entries.putIfAbsent(entry.getName(), entry) != null)
          throw new IOException("Duplicate or excessive checkpoint entries");
      }
      Header h =
          PresetIO.JSON.fromJson(
              new String(
                  read(zip, entries.get("manifest.json"), MANIFEST_LIMIT, false),
                  StandardCharsets.UTF_8),
              Header.class);
      if (h == null
          || h.checkpointVersion() != VERSION
          || !PresetIO.ENGINE_VERSION.equals(h.engineVersion())
          || !PresetIO.REVISION.equals(h.algorithmRevision())
          || !"little-endian".equals(h.byteOrder()))
        throw new IOException("Checkpoint version or algorithm is incompatible with this build");
      Settings settings = PresetIO.parse(h.preset().toString());
      validateContext(
          settings, h.tick(), h.width(), h.height(), h.signals(), h.pointer(), h.replay());
      Map<String, Integer> layout =
          GpuSnapshot.layout(settings, h.simulation(), h.reference(), h.tick());
      if (h.resources() == null || !layout.keySet().equals(h.resources().keySet()))
        throw new IOException("Checkpoint resource manifest is incomplete");
      Set<String> names = new HashSet<>(Set.of("manifest.json"));
      layout.keySet().forEach(n -> names.add("state/" + n + ".bin"));
      if (!entries.keySet().equals(names))
        throw new IOException("Missing or unexpected checkpoint entries");
      Map<String, byte[]> blobs = new LinkedHashMap<>();
      for (var item : layout.entrySet()) {
        var resource = h.resources().get(item.getKey());
        if (resource == null || resource.length() != item.getValue())
          throw new IOException("Checkpoint resource size mismatch: " + item.getKey());
        byte[] bytes =
            read(zip, entries.get("state/" + item.getKey() + ".bin"), item.getValue(), true);
        if (!hash(bytes).equals(resource.sha256()))
          throw new IOException("Checkpoint checksum mismatch: " + item.getKey());
        blobs.put(item.getKey(), bytes);
      }
      Snapshot snapshot =
          new Snapshot(
              settings,
              h.tick(),
              h.width(),
              h.height(),
              h.signals(),
              h.pointer(),
              new GpuSnapshot(
                  h.device(), h.programSignature(), h.simulation(), h.reference(), blobs),
              h.replay());
      snapshot.validate();
      return snapshot;
    } catch (RuntimeException e) {
      throw new IOException("Invalid checkpoint: " + e.getMessage(), e);
    }
  }
}
