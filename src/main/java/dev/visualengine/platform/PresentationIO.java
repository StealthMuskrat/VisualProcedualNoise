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
      if (!json.keySet().equals(Set.of("schemaVersion", "vsync", "frameLimit"))
          || integer(json, "schemaVersion") != 1
          || !json.get("vsync").isJsonPrimitive()
          || !json.getAsJsonPrimitive("vsync").isBoolean())
        throw new IllegalArgumentException("Unsupported presentation preferences");
      return new Presentation(json.get("vsync").getAsBoolean(), integer(json, "frameLimit"));
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

  public static void save(Path path, Presentation presentation) throws IOException {
    JsonObject json = new JsonObject();
    json.addProperty("schemaVersion", 1);
    json.addProperty("vsync", presentation.vsync());
    json.addProperty("frameLimit", presentation.frameLimit());
    PresetIO.atomicWrite(path, PresetIO.JSON.toJson(json));
  }
}
