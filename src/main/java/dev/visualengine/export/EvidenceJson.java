package dev.visualengine.export;

import com.google.gson.*;
import com.google.gson.stream.*;
import java.io.*;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/** Strict, bounded JSON for imported acceptance evidence. */
final class EvidenceJson {
  static final int MAX_BYTES = 8 * 1024 * 1024;
  private int values;

  record Document(JsonObject value, String sha256) {}

  static JsonObject read(Path path) throws IOException {
    return document(path).value();
  }

  static Document document(Path path) throws IOException {
    byte[] bytes;
    try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
      bytes = input.readNBytes(MAX_BYTES + 1);
    }
    if (bytes.length > MAX_BYTES) throw new IOException("Evidence JSON exceeds 8 MiB");
    String text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
    try (var reader = new JsonReader(new StringReader(text))) {
      reader.setStrictness(Strictness.STRICT);
      JsonElement result = new EvidenceJson().value(reader, 0);
      if (!result.isJsonObject() || reader.peek() != JsonToken.END_DOCUMENT)
        throw new IOException("Expected one evidence JSON object");
      return new Document(result.getAsJsonObject(), BenchmarkWorkloads.sha256(bytes));
    } catch (RuntimeException error) {
      throw new IOException("Invalid evidence JSON: " + path.getFileName(), error);
    }
  }

  private JsonElement value(JsonReader reader, int depth) throws IOException {
    if (depth > 32 || ++values > 100_000)
      throw new IOException("Evidence JSON exceeds depth/value bounds");
    return switch (reader.peek()) {
      case BEGIN_OBJECT -> {
        var object = new JsonObject();
        reader.beginObject();
        while (reader.hasNext()) {
          String key = reader.nextName();
          if (object.has(key)) throw new IOException("Duplicate evidence JSON key: " + key);
          object.add(key, value(reader, depth + 1));
        }
        reader.endObject();
        yield object;
      }
      case BEGIN_ARRAY -> {
        var array = new JsonArray();
        reader.beginArray();
        while (reader.hasNext()) array.add(value(reader, depth + 1));
        reader.endArray();
        yield array;
      }
      case STRING -> new JsonPrimitive(reader.nextString());
      case NUMBER -> {
        String number = reader.nextString();
        if (number.length() > 128) throw new IOException("Evidence number exceeds 128 characters");
        yield new JsonPrimitive(new BigDecimal(number));
      }
      case BOOLEAN -> new JsonPrimitive(reader.nextBoolean());
      case NULL -> {
        reader.nextNull();
        yield JsonNull.INSTANCE;
      }
      default -> throw new IOException("Unexpected evidence JSON token");
    };
  }
}
