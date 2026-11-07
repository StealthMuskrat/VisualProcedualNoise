package dev.visualengine.platform;

import com.google.gson.*;
import dev.visualengine.presets.PresetIO;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Set;

/** Small versioned desktop-only preference document. */
public final class PresentationIO {
  private static final int LIMIT = 16384;

  private PresentationIO() {}

  public static Presentation load(Path path) throws IOException {
    if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return Presentation.DEFAULT;
    byte[] bytes;
    try (var input = Files.newInputStream(path)) {
      bytes = input.readNBytes(LIMIT + 1);
    }
    if (bytes.length > LIMIT) throw new IOException("Presentation preferences exceed 16 KiB");
    try {
      JsonObject json =
          JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
      int version = integer(json, "schemaVersion");
      if (version != 1 && version != 2
          || !json.keySet()
              .equals(
                  version == 1
                      ? Set.of("schemaVersion", "vsync", "frameLimit")
                      : Set.of("schemaVersion", "vsync", "frameLimit", "suspendWhenMinimized")))
        throw new IllegalArgumentException("Unsupported presentation preferences");
      return new Presentation(
          bool(json, "vsync"),
          integer(json, "frameLimit"),
          version == 1 || bool(json, "suspendWhenMinimized"));
    } catch (RuntimeException e) {
      throw new IOException("Invalid presentation preferences: " + e.getMessage(), e);
    }
  }

  private static int integer(JsonObject json, String name) {
    var primitive = json.getAsJsonPrimitive(name);
    if (primitive == null || !primitive.isNumber())
      throw new IllegalArgumentException("Expected integer " + name);
    return primitive.getAsBigDecimal().intValueExact();
  }

  private static boolean bool(JsonObject json, String name) {
    var primitive = json.getAsJsonPrimitive(name);
    if (primitive == null || !primitive.isBoolean())
      throw new IllegalArgumentException("Expected boolean " + name);
    return primitive.getAsBoolean();
  }

  public static void save(Path path, Presentation presentation) throws IOException {
    JsonObject json = new JsonObject();
    json.addProperty("schemaVersion", 2);
    json.addProperty("vsync", presentation.vsync());
    json.addProperty("frameLimit", presentation.frameLimit());
    json.addProperty("suspendWhenMinimized", presentation.suspendWhenMinimized());
    PresetIO.atomicWrite(path, PresetIO.JSON.toJson(json));
  }
}
