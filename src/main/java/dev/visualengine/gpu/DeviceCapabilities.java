package dev.visualengine.gpu;

import dev.visualengine.model.Settings;
import java.util.*;

/** Immutable advertised device support. Limits describe capacity, not free VRAM or performance. */
public record DeviceCapabilities(
    String vendor,
    String renderer,
    String glVersion,
    String glslVersion,
    Map<String, Long> limits,
    Map<String, Format> formats) {
  public enum Support {
    NONE,
    FULL,
    CAVEAT
  }

  public record Format(
      boolean supported,
      Support framebuffer,
      Support filter,
      Support fragmentSampling,
      Support computeSampling,
      Support imageStore,
      Support blending,
      int maxWidth,
      int maxHeight,
      int maxLayers) {}

  public DeviceCapabilities {
    limits = Collections.unmodifiableMap(new TreeMap<>(limits));
    formats = Collections.unmodifiableMap(new TreeMap<>(formats));
  }

  public long limit(String name) {
    return limits.getOrDefault(name, 0L);
  }

  public List<String> baselineFailures() {
    List<String> failures = new ArrayList<>();
    Map<String, Integer> required = new LinkedHashMap<>();
    required.put("GL_MAX_TEXTURE_SIZE", 32);
    required.put("GL_MAX_ARRAY_TEXTURE_LAYERS", 1);
    required.put("GL_MAX_TEXTURE_IMAGE_UNITS", 8);
    required.put("GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS", 8);
    required.put("GL_MAX_COMPUTE_TEXTURE_IMAGE_UNITS", 3);
    required.put("GL_MAX_IMAGE_UNITS", 1);
    required.put("GL_MAX_COMPUTE_IMAGE_UNIFORMS", 1);
    required.put("GL_MAX_SHADER_STORAGE_BUFFER_BINDINGS", 1);
    required.put("GL_MAX_VERTEX_SHADER_STORAGE_BLOCKS", 1);
    required.put("GL_MAX_COMPUTE_SHADER_STORAGE_BLOCKS", 1);
    required.put("GL_MAX_COMBINED_SHADER_STORAGE_BLOCKS", 1);
    required.put("GL_MAX_SHADER_STORAGE_BLOCK_SIZE", 32);
    required.put("GL_MAX_COMPUTE_WORK_GROUP_INVOCATIONS", 256);
    required.put("GL_MAX_COMPUTE_WORK_GROUP_SIZE_X", 256);
    required.put("GL_MAX_COMPUTE_WORK_GROUP_SIZE_Y", 8);
    required.put("GL_MAX_COMPUTE_WORK_GROUP_SIZE_Z", 1);
    for (String axis : List.of("X", "Y", "Z"))
      required.put("GL_MAX_COMPUTE_WORK_GROUP_COUNT_" + axis, 1);
    required.put("GL_MAX_VIEWPORT_WIDTH", 32);
    required.put("GL_MAX_VIEWPORT_HEIGHT", 32);
    required.put("GL_MAX_FRAMEBUFFER_WIDTH", 32);
    required.put("GL_MAX_FRAMEBUFFER_HEIGHT", 32);
    required.forEach((name, value) -> {
      if (limit(name) < value)
        failures.add(name + " requires " + value + "; device reports " + limit(name));
    });
    for (String name : List.of("R32F", "RG32F", "RGBA16F", "RGBA8", "RGBA32F", "RGBA16F_ARRAY")) {
      Format f = formats.get(name);
      if (f == null || !f.supported()) {
        failures.add(name + " texture storage is unavailable");
        continue;
      }
      if (f.framebuffer() == Support.NONE)
        failures.add(name + " framebuffer attachment is unavailable");
      if (f.maxWidth() < 32 || f.maxHeight() < 32)
        failures.add(name + " cannot store a 32x32 image");
      if (!name.equals("RGBA32F")
          && (f.filter() == Support.NONE || f.fragmentSampling() == Support.NONE))
        failures.add(name + " filtered fragment sampling is unavailable");
      if (List.of("R32F", "RG32F", "RGBA16F").contains(name)
          && (f.computeSampling() == Support.NONE || f.imageStore() == Support.NONE))
        failures.add(name + " compute sampling/image stores are unavailable");
      if (name.equals("RGBA16F") && f.blending() == Support.NONE)
        failures.add("RGBA16F particle trail blending is unavailable");
      if (name.equals("RGBA16F_ARRAY") && f.maxLayers() < 1)
        failures.add("RGBA16F history array storage is unavailable");
    }
    return List.copyOf(failures);
  }

  public void requireBaseline() {
    var failures = baselineFailures();
    if (!failures.isEmpty()) throw new Unsupported(this, String.join("\n", failures));
  }

  public static final class Unsupported extends IllegalStateException {
    private final DeviceCapabilities capabilities;

    public Unsupported(DeviceCapabilities capabilities, String message) {
      super("GPU backend requirements are unavailable:\n" + message);
      this.capabilities = capabilities;
    }

    public DeviceCapabilities capabilities() {
      return capabilities;
    }
  }

  public void requireTexture(String name, int width, int height) {
    Format f = formats.get(name);
    if (f == null || !f.supported())
      throw new IllegalArgumentException("Unsupported GPU format " + name);
    require(
        width > 0
            && height > 0
            && width <= Math.min(limit("GL_MAX_TEXTURE_SIZE"), f.maxWidth())
            && height <= Math.min(limit("GL_MAX_TEXTURE_SIZE"), f.maxHeight()),
        name
            + " extent "
            + width
            + "x"
            + height
            + " exceeds device texture limits ("
            + Math.min(limit("GL_MAX_TEXTURE_SIZE"), f.maxWidth())
            + "x"
            + Math.min(limit("GL_MAX_TEXTURE_SIZE"), f.maxHeight())
            + ")");
  }

  public void requireViewport(int width, int height) {
    if (width <= 0
        || height <= 0
        || width > limit("GL_MAX_VIEWPORT_WIDTH")
        || height > limit("GL_MAX_VIEWPORT_HEIGHT")
        || width > limit("GL_MAX_FRAMEBUFFER_WIDTH")
        || height > limit("GL_MAX_FRAMEBUFFER_HEIGHT"))
      throw new IllegalArgumentException(
          "Output viewport "
              + width
              + "x"
              + height
              + " exceeds device framebuffer/viewport limits");
  }

  public void requireHistory(int layers, int resolution) {
    requireTexture("RGBA16F_ARRAY", resolution, resolution);
    long maximum =
        Math.min(limit("GL_MAX_ARRAY_TEXTURE_LAYERS"), formats.get("RGBA16F_ARRAY").maxLayers());
    require(
        layers >= 0 && Math.max(1L, layers) <= maximum,
        "History requires " + Math.max(1L, layers) + " layers; device supports " + maximum);
  }

  public void requireParticles(int count) {
    long bytes = Math.max(32L, count * 32L), groups = (count + 255L) / 256;
    require(
        count >= 0 && bytes <= limit("GL_MAX_SHADER_STORAGE_BLOCK_SIZE"),
        "Particle storage requires "
            + bytes
            + " bytes; GL_MAX_SHADER_STORAGE_BLOCK_SIZE is "
            + limit("GL_MAX_SHADER_STORAGE_BLOCK_SIZE"));
    require(
        groups <= limit("GL_MAX_COMPUTE_WORK_GROUP_COUNT_X"),
        "Particle dispatch requires "
            + groups
            + " X groups; device supports "
            + limit("GL_MAX_COMPUTE_WORK_GROUP_COUNT_X"));
  }

  public void requireScene(Settings settings) {
    requireTexture("RG32F", settings.grid, settings.grid);
    requireTexture("RGBA16F", settings.grid, settings.grid);
    requireTexture("R32F", settings.grid, settings.grid);
    long groups = (settings.grid + 7L) / 8;
    require(
        groups <= limit("GL_MAX_COMPUTE_WORK_GROUP_COUNT_X")
            && groups <= limit("GL_MAX_COMPUTE_WORK_GROUP_COUNT_Y"),
        "Simulation dispatch requires "
            + groups
            + "x"
            + groups
            + " work groups; device supports "
            + limit("GL_MAX_COMPUTE_WORK_GROUP_COUNT_X")
            + "x"
            + limit("GL_MAX_COMPUTE_WORK_GROUP_COUNT_Y"));
    requireHistory(settings.historyLayers, Math.min(settings.grid, 256));
    requireParticles(settings.particles);
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new IllegalArgumentException(message);
  }
}
