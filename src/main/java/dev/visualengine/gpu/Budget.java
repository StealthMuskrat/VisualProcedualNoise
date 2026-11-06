package dev.visualengine.gpu;

import dev.visualengine.model.Settings;

public final class Budget {
  public static final long LIMIT = 512L * 1024 * 1024;

  private Budget() {}

  public static long persistent(Settings s) {
    long history = Math.min(s.grid, 256);
    return (long) s.grid * s.grid * 88
        + Math.max(32, s.particles * 32L)
        + history * history * (Math.max(1, s.historyLayers) + 1) * 8;
  }

  public static long display(int width, int height) {
    return (long) width * height * 8 + (long) Math.max(1, width / 4) * Math.max(1, height / 4) * 8;
  }

  public static void require(long bytes) {
    if (bytes > LIMIT)
      throw new IllegalArgumentException(
          "Requested GPU allocation exceeds the 512 MiB engine budget: "
              + Math.round(bytes / 1048576.)
              + " MiB");
  }
}
