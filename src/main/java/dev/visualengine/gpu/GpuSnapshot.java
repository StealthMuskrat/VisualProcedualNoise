package dev.visualengine.gpu;

import dev.visualengine.model.Settings;
import dev.visualengine.simulation.Simulation;
import java.nio.*;
import java.util.*;

/** Immutable metadata with owned byte arrays; only initialized resources are included. */
public record GpuSnapshot(
    String device,
    String signature,
    Simulation.State simulation,
    Reference reference,
    Map<String, byte[]> blobs) {
  public record Reference(int width, int height) {
    public int bytes() {
      if (width < 1
          || height < 1
          || width > 8192
          || height > 8192
          || (long) width * height > 33_554_432)
        throw new IllegalArgumentException("Invalid checkpoint reference dimensions");
      return Math.multiplyExact(Math.multiplyExact(width, height), 4);
    }
  }

  public GpuSnapshot {
    blobs = blobs == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(blobs));
  }

  public static Map<String, Integer> layout(
      Settings settings, Simulation.State state, Reference reference, long tick) {
    settings.validate();
    if (state == null) throw new IllegalArgumentException("Missing simulation metadata");
    state.validate(settings, tick);
    int pixels = Math.multiplyExact(settings.grid, settings.grid);
    Map<String, Integer> result = new LinkedHashMap<>();
    result.put("flow", pixels * 8);
    for (String name : List.of("pigment", "feedback", "chemicals", "age", "trails"))
      for (int side = 0; side < 2; side++)
        result.put(name + "." + side, pixels * (name.equals("age") ? 4 : 8));
    result.put("particles", state.particles().initialize() ? 0 : settings.particles * 32);
    int historyPixels = Math.min(settings.grid, 256);
    for (int layer : state.history().validLayers(settings.historyLayers, tick))
      result.put("history." + layer, historyPixels * historyPixels * 8);
    if (reference != null) result.put("reference", reference.bytes());
    if (result.values().stream().mapToLong(Integer::longValue).sum() > Budget.LIMIT)
      throw new IllegalArgumentException("Checkpoint exceeds raw state budget");
    return Map.copyOf(result);
  }

  public void validate(Settings settings, long tick) {
    if (ByteOrder.nativeOrder() != ByteOrder.LITTLE_ENDIAN)
      throw new IllegalArgumentException("Checkpoint backend requires little-endian storage");
    if (device == null
        || device.isBlank()
        || device.length() > 2048
        || signature == null
        || !signature.matches("[a-f0-9]{64}"))
      throw new IllegalArgumentException("Invalid checkpoint compatibility metadata");
    var expected = layout(settings, simulation, reference, tick);
    if (!expected.keySet().equals(blobs.keySet()))
      throw new IllegalArgumentException("Checkpoint resource set is incomplete");
    for (var item : expected.entrySet()) {
      byte[] bytes = blobs.get(item.getKey());
      if (bytes == null || bytes.length != item.getValue())
        throw new IllegalArgumentException("Checkpoint size mismatch: " + item.getKey());
      validateNumbers(item.getKey(), bytes);
    }
  }

  private static void validateNumbers(String name, byte[] bytes) {
    if (name.equals("reference")) return;
    ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
    if (name.equals("particles")) {
      for (int offset = 0; offset < bytes.length; offset += 32)
        for (int channel = 0; channel < 4; channel++) {
          float f = buffer.getFloat(offset + channel * 4);
          if (!Float.isFinite(f)
              || Math.abs(f) > 1e12
              || channel >= 2 && f < 0
              || channel == 3 && f <= 0)
            throw new IllegalArgumentException("Invalid particle checkpoint state");
        }
      return;
    }
    if (name.equals("flow") || name.startsWith("chemicals.") || name.startsWith("age.")) {
      while (buffer.hasRemaining()) {
        float f = buffer.getFloat();
        if (!Float.isFinite(f)
            || Math.abs(f) > 1e12
            || name.startsWith("age.") && f < 0
            || name.startsWith("chemicals.") && (f < 0 || f > 1))
          throw new IllegalArgumentException("Invalid numeric checkpoint state: " + name);
      }
    } else
      while (buffer.hasRemaining())
        if ((Short.toUnsignedInt(buffer.getShort()) & 0x7c00) == 0x7c00)
          throw new IllegalArgumentException("Nonfinite half-float checkpoint state: " + name);
  }
}
