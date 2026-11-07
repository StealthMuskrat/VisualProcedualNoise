package dev.visualengine;

import dev.visualengine.gpu.DeviceCapabilities;
import dev.visualengine.gpu.DeviceCapabilities.*;
import dev.visualengine.model.Settings;
import dev.visualengine.platform.BoundedLog;
import java.io.*;
import java.nio.file.*;
import java.util.*;

public final class DeviceChecks {
  private static int count;

  private static void check(boolean ok, String label) {
    count++;
    if (!ok) throw new AssertionError(label);
  }

  private static void rejects(Runnable action, String message) {
    try {
      action.run();
    } catch (IllegalArgumentException | IllegalStateException e) {
      check(e.getMessage().contains(message), "actionable device rejection: " + message);
      return;
    }
    throw new AssertionError("Accepted device request: " + message);
  }

  public static DeviceCapabilities capable() {
    Map<String, Long> limits = new HashMap<>();
    for (String name :
        List.of(
            "GL_MAX_TEXTURE_SIZE",
            "GL_MAX_VIEWPORT_WIDTH",
            "GL_MAX_VIEWPORT_HEIGHT",
            "GL_MAX_FRAMEBUFFER_WIDTH",
            "GL_MAX_FRAMEBUFFER_HEIGHT")) limits.put(name, 8192L);
    for (String name :
        List.of(
            "GL_MAX_TEXTURE_IMAGE_UNITS",
            "GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS",
            "GL_MAX_COMPUTE_TEXTURE_IMAGE_UNITS",
            "GL_MAX_IMAGE_UNITS",
            "GL_MAX_COMPUTE_IMAGE_UNIFORMS",
            "GL_MAX_SHADER_STORAGE_BUFFER_BINDINGS",
            "GL_MAX_VERTEX_SHADER_STORAGE_BLOCKS",
            "GL_MAX_COMPUTE_SHADER_STORAGE_BLOCKS",
            "GL_MAX_COMBINED_SHADER_STORAGE_BLOCKS")) limits.put(name, 16L);
    limits.put("GL_MAX_SHADER_STORAGE_BLOCK_SIZE", 1L << 27);
    limits.put("GL_MAX_ARRAY_TEXTURE_LAYERS", 256L);
    limits.put("GL_MAX_COMPUTE_WORK_GROUP_INVOCATIONS", 1024L);
    for (String axis : List.of("X", "Y", "Z")) {
      limits.put("GL_MAX_COMPUTE_WORK_GROUP_SIZE_" + axis, 1024L);
      limits.put("GL_MAX_COMPUTE_WORK_GROUP_COUNT_" + axis, 65535L);
    }
    Map<String, Format> formats = new HashMap<>();
    for (String name : List.of("R32F", "RG32F", "RGBA16F", "RGBA8", "RGBA32F", "RGBA16F_ARRAY"))
      formats.put(
          name,
          new Format(
              true,
              Support.FULL,
              Support.FULL,
              Support.FULL,
              Support.FULL,
              Support.FULL,
              Support.FULL,
              8192,
              8192,
              256));
    return new DeviceCapabilities("test vendor", "test renderer", "4.3", "4.30", limits, formats);
  }

  public static DeviceCapabilities limited(DeviceCapabilities source, String name, long value) {
    Map<String, Long> limits = new HashMap<>(source.limits());
    limits.put(name, value);
    return new DeviceCapabilities(
        source.vendor(),
        source.renderer(),
        source.glVersion(),
        source.glslVersion(),
        limits,
        source.formats());
  }

  static int run() throws Exception {
    var device = capable();
    device.requireBaseline();
    check(
        device.baselineFailures().isEmpty(),
        "baseline uses declared operation requirements without a VRAM/performance guess");
    for (var item :
        Map.of(
                "GL_MAX_VERTEX_SHADER_STORAGE_BLOCKS",
                0L,
                "GL_MAX_COMPUTE_WORK_GROUP_SIZE_X",
                255L,
                "GL_MAX_COMPUTE_WORK_GROUP_INVOCATIONS",
                255L,
                "GL_MAX_TEXTURE_IMAGE_UNITS",
                7L)
            .entrySet())
      rejects(
          () -> limited(device, item.getKey(), item.getValue()).requireBaseline(), item.getKey());
    Map<String, Format> formats = new HashMap<>(device.formats());
    formats.remove("RGBA16F_ARRAY");
    rejects(
        () ->
            new DeviceCapabilities("v", "r", "4.3", "4.30", device.limits(), formats)
                .requireBaseline(),
        "RGBA16F_ARRAY");
    // Missing format diagnostics use the exact queried name, so test the named failure directly.
    check(
        new DeviceCapabilities("v", "r", "4.3", "4.30", device.limits(), formats)
            .baselineFailures().stream().anyMatch(s -> s.contains("RGBA16F_ARRAY")),
        "missing target/format appears in diagnostics");
    Format f = device.formats().get("RGBA16F");
    formats.put("RGBA16F_ARRAY", device.formats().get("RGBA16F_ARRAY"));
    formats.put(
        "RGBA16F",
        new Format(
            true,
            Support.CAVEAT,
            Support.CAVEAT,
            Support.CAVEAT,
            Support.CAVEAT,
            Support.CAVEAT,
            Support.CAVEAT,
            f.maxWidth(),
            f.maxHeight(),
            f.maxLayers()));
    check(
        new DeviceCapabilities("v", "r", "4.3", "4.30", device.limits(), formats)
            .baselineFailures()
            .isEmpty(),
        "caveat support is retained as advertised support instead of silently disabling a feature");
    Settings s = new Settings();
    s.grid = 129;
    s.particles = 257;
    s.historyLayers = 3;
    device.requireScene(s);
    rejects(
        () -> limited(device, "GL_MAX_COMPUTE_WORK_GROUP_COUNT_X", 16).requireScene(s), "17x17");
    rejects(
        () -> limited(device, "GL_MAX_COMPUTE_WORK_GROUP_COUNT_Y", 16).requireScene(s), "17x17");
    rejects(
        () -> limited(device, "GL_MAX_SHADER_STORAGE_BLOCK_SIZE", 8223).requireParticles(257),
        "8224");
    limited(device, "GL_MAX_SHADER_STORAGE_BLOCK_SIZE", 8224).requireParticles(257);
    check(true, "aligned 32-byte particle records fit exactly at the SSBO limit");
    rejects(
        () -> limited(device, "GL_MAX_COMPUTE_WORK_GROUP_COUNT_X", 1).requireParticles(257),
        "2 X groups");
    limited(device, "GL_MAX_COMPUTE_WORK_GROUP_COUNT_X", 1).requireParticles(256);
    check(true, "particle dispatch rounds only the odd tail into an extra group");
    rejects(
        () -> limited(device, "GL_MAX_ARRAY_TEXTURE_LAYERS", 2).requireHistory(3, 65), "3 layers");
    limited(device, "GL_MAX_ARRAY_TEXTURE_LAYERS", 1).requireHistory(0, 65);
    check(true, "disabled history still validates its one allocated array layer");
    rejects(
        () -> limited(device, "GL_MAX_TEXTURE_SIZE", 64).requireScene(s), "device texture limits");
    rejects(
        () -> limited(device, "GL_MAX_VIEWPORT_HEIGHT", 63).requireViewport(96, 64),
        "viewport limits");
    rejects(() -> device.requireTexture("R32F", 0, 64), "device texture limits");
    rejects(() -> device.requireParticles(Integer.MAX_VALUE), "68719476704");
    Map<String, Long> owned = new HashMap<>(device.limits());
    var snapshot = new DeviceCapabilities("v", "r", "4.3", "4.30", owned, device.formats());
    owned.clear();
    check(!snapshot.limits().isEmpty(), "capability snapshot owns its limit map");
    boolean immutable = false;
    try {
      snapshot.limits().clear();
    } catch (UnsupportedOperationException expected) {
      immutable = true;
    }
    check(immutable, "device limits cannot mutate after preflight");
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (var log = new BoundedLog(bytes, 128)) {
      log.write("x".repeat(1000).getBytes(java.nio.charset.StandardCharsets.UTF_8));
      log.write("later".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    check(
        bytes.size() == 128
            && bytes
                .toString(java.nio.charset.StandardCharsets.UTF_8)
                .endsWith("messages omitted]\n"),
        "log flood stays bounded with one visible truncation marker");
    Path root = Files.createTempDirectory(Path.of("build/reports"), "log-contract-");
    try {
      Files.writeString(root.resolve("engine.log"), "old failure");
      try (var log = BoundedLog.open(root)) {
        log.println("new attempt");
      }
      check(
          Files.readString(root.resolve("engine.previous.log")).equals("old failure")
              && Files.readString(root.resolve("engine.log")).contains("new attempt"),
          "new startup preserves the previous session log");
      byte[] legacy = new byte[BoundedLog.LIMIT + 100];
      Arrays.fill(legacy, (byte) 'a');
      legacy[legacy.length - 1] = 'z';
      Files.write(root.resolve("engine.log"), legacy);
      try (var log = BoundedLog.open(root)) {
        log.println("bounded");
      }
      byte[] previous = Files.readAllBytes(root.resolve("engine.previous.log"));
      check(
          previous.length == BoundedLog.LIMIT && previous[previous.length - 1] == 'z',
          "legacy unbounded log keeps a bounded diagnostic tail");
      Files.delete(root.resolve("engine.previous.log"));
      Files.createDirectory(root.resolve("engine.previous.log"));
      boolean failed = false;
      try (var ignored = BoundedLog.open(root)) {
      } catch (IOException expected) {
        failed = true;
      }
      check(
          failed && Files.readString(root.resolve("engine.log")).contains("bounded"),
          "failed log rotation preserves current diagnostics");
    } finally {
      Files.deleteIfExists(root.resolve("engine.log"));
      Files.deleteIfExists(root.resolve("engine.previous.log"));
      Files.delete(root);
    }
    return count;
  }
}
