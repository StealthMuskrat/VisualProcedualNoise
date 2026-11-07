package dev.visualengine.platform;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/** One bounded stderr log plus one previous session, with an explicit truncation marker. */
public final class BoundedLog extends OutputStream {
  public static final int LIMIT = 2 * 1024 * 1024;
  private static final byte[] MARKER =
      "\n[engine log limit reached; further messages omitted]\n".getBytes(StandardCharsets.UTF_8);
  private final OutputStream target;
  private int remaining;
  private boolean truncated;

  public BoundedLog(OutputStream target, int limit) {
    if (limit < MARKER.length) throw new IllegalArgumentException("Log limit is too small");
    this.target = target;
    remaining = limit - MARKER.length;
  }

  public static PrintStream open(Path directory) throws IOException {
    Files.createDirectories(directory);
    Path current = directory.resolve("engine.log"),
        previous = directory.resolve("engine.previous.log");
    if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
      if (!Files.isRegularFile(current, LinkOption.NOFOLLOW_LINKS))
        throw new IOException("Engine log is not a regular file: " + current);
      if (Files.exists(previous, LinkOption.NOFOLLOW_LINKS)
          && !Files.isRegularFile(previous, LinkOption.NOFOLLOW_LINKS))
        throw new IOException("Previous engine log is not a regular file: " + previous);
      // Bound a legacy unbounded log while preserving its most recent diagnostic tail.
      try (var in = Files.newInputStream(current);
          var out = Files.newOutputStream(previous)) {
        in.skipNBytes(Math.max(0, Files.size(current) - LIMIT));
        out.write(in.readNBytes(LIMIT));
      }
    }
    return new PrintStream(
        new BoundedLog(Files.newOutputStream(current), LIMIT), true, StandardCharsets.UTF_8);
  }

  @Override
  public synchronized void write(int value) throws IOException {
    write(new byte[] {(byte) value}, 0, 1);
  }

  @Override
  public synchronized void write(byte[] bytes, int offset, int length) throws IOException {
    java.util.Objects.checkFromIndexSize(offset, length, bytes.length);
    int accepted = Math.min(remaining, length);
    target.write(bytes, offset, accepted);
    remaining -= accepted;
    if (accepted < length && !truncated) {
      target.write(MARKER);
      truncated = true;
    }
  }

  @Override
  public synchronized void flush() throws IOException {
    target.flush();
  }

  @Override
  public synchronized void close() throws IOException {
    target.close();
  }
}
