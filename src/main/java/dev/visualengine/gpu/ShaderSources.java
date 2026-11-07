package dev.visualengine.gpu;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/** Root-bounded includes with bounded reads and expansion. Driver #line mappings are retained. */
public final class ShaderSources {
  public static final int FILE_BYTES = 262144,
      EXPANDED_BYTES = 1048576,
      MAX_FILES = 128,
      MAX_DEPTH = 16;
  private static final Pattern INCLUDE = Pattern.compile("\\s*#include \\\"([^\\\"]+)\\\"\\s*");
  private final Path root;

  public ShaderSources(Path root) {
    this.root = root == null ? null : root.toAbsolutePath().normalize();
  }

  public String read(String name) {
    Expansion expansion = new Expansion();
    expand(name, expansion);
    return expansion.text.toString();
  }

  /** Final stage limit, including generated insertions; validate before hashing or native calls. */
  public static int sourceBytes(String source) {
    if (source == null) throw new IllegalArgumentException("Missing shader source");
    int bytes = 0;
    for (int i = 0; i < source.length(); i++) {
      char c = source.charAt(i);
      if (c == 0) throw new IllegalArgumentException("Shader source contains NUL");
      if (Character.isHighSurrogate(c)) {
        if (i + 1 >= source.length() || !Character.isLowSurrogate(source.charAt(i + 1)))
          throw new IllegalArgumentException("Shader source contains invalid Unicode");
        i++;
        bytes += 4;
      } else if (Character.isLowSurrogate(c))
        throw new IllegalArgumentException("Shader source contains invalid Unicode");
      else bytes += c <= 0x7f ? 1 : c <= 0x7ff ? 2 : 3;
      if (bytes > EXPANDED_BYTES)
        throw new IllegalArgumentException("Final shader source exceeds 1 MiB");
    }
    return bytes;
  }

  /** ASCII markers cannot split a Unicode code point. Bound every occurrence before replacing. */
  public static String replace(String source, String marker, String replacement) {
    int bytes = sourceBytes(source);
    if (marker == null || marker.isEmpty() || marker.chars().anyMatch(c -> c < 32 || c > 126))
      throw new IllegalArgumentException("Shader insertion requires a nonempty ASCII marker");
    int count = 0, at = 0;
    while ((at = source.indexOf(marker, at)) >= 0) {
      count++;
      at += marker.length();
    }
    if (count == 0) return source;
    int inserted = sourceBytes(replacement);
    long total = bytes + (long) count * (inserted - marker.length());
    if (total > EXPANDED_BYTES)
      throw new IllegalArgumentException("Final shader source exceeds 1 MiB after insertion");
    return source.replace(marker, replacement);
  }

  private static final class Expansion {
    final StringBuilder text = new StringBuilder();
    final Set<String> active = new LinkedHashSet<>();
    final Map<String, Integer> ids = new LinkedHashMap<>();
    final Map<String, String> files = new HashMap<>();
    int bytes, count;

    void append(String value) {
      int size = value.getBytes(StandardCharsets.UTF_8).length;
      if (size > EXPANDED_BYTES - bytes)
        throw new IllegalArgumentException("Expanded shader exceeds 1 MiB");
      bytes += size;
      text.append(value);
    }
  }

  private void expand(String name, Expansion expansion) {
    Path relative = Path.of(name.replace('\\', '/')).normalize();
    String key = relative.toString();
    if (relative.isAbsolute()
        || relative.startsWith("..")
        || expansion.active.size() >= MAX_DEPTH
        || !expansion.active.add(key))
      throw new IllegalArgumentException("Invalid or cyclic shader include: " + name);
    if (++expansion.count > MAX_FILES)
      throw new IllegalArgumentException("Shader exceeds 128 file expansions");
    try {
      String text = expansion.files.get(key);
      if (text == null) {
        try (InputStream input = open(relative)) {
          byte[] data = input.readNBytes(FILE_BYTES + 1);
          if (data.length > FILE_BYTES)
            throw new IOException("Shader source exceeds 256 KiB: " + name);
          text =
              StandardCharsets.UTF_8
                  .newDecoder()
                  .onMalformedInput(CodingErrorAction.REPORT)
                  .onUnmappableCharacter(CodingErrorAction.REPORT)
                  .decode(ByteBuffer.wrap(data))
                  .toString();
          if (text.indexOf('\0') >= 0) throw new IOException("Shader source contains NUL: " + name);
          expansion.files.put(key, text);
        }
      }
      int id = expansion.ids.computeIfAbsent(key, k -> expansion.ids.size() + 1), line = 0;
      for (String value : text.split("\n", -1)) {
        line++;
        Matcher include = INCLUDE.matcher(value);
        if (include.matches()) {
          String child =
              relative.getParent() == null
                  ? include.group(1)
                  : relative.getParent().resolve(include.group(1)).normalize().toString();
          expansion.append("\n// " + child + "\n");
          expand(child, expansion);
          expansion.append("\n#line " + (line + 1) + " " + id + "\n");
        } else expansion.append(value + "\n");
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } finally {
      expansion.active.remove(key);
    }
  }

  private InputStream open(Path relative) throws IOException {
    if (root != null) {
      Path file = root.resolve(relative).normalize();
      if (!file.startsWith(root) || !file.toRealPath().startsWith(root.toRealPath()))
        throw new IOException("Include escapes shader root");
      return Files.newInputStream(file);
    }
    InputStream input =
        ShaderSources.class.getResourceAsStream(
            "/shaders/" + relative.toString().replace('\\', '/'));
    if (input == null) throw new IOException("Missing shader " + relative);
    return input;
  }
}
