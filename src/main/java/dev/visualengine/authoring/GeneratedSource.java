package dev.visualengine.authoring;

import java.nio.charset.StandardCharsets;

/** Checks output fragments before concatenation, including repeated chain-rule terms. */
final class GeneratedSource {
  static final int LIMIT = 65536;

  static final class Limit extends IllegalArgumentException {
    Limit() {
      super("Generated expression and gradient exceed 64 KiB");
    }
  }

  static int bytes(String source) {
    return source.getBytes(StandardCharsets.UTF_8).length;
  }

  static String join(int remainingBytes, String... parts) {
    long size = 0;
    for (String part : parts) {
      size += bytes(part);
      if (size > remainingBytes) throw new Limit();
    }
    StringBuilder result = new StringBuilder((int) size);
    for (String part : parts) result.append(part);
    return result.toString();
  }
}
