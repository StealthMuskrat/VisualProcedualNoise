package dev.visualengine.gpu;

import dev.visualengine.model.Settings;

public final class Budget {
  public static final long LIMIT = 512L * 1024 * 1024;

  public record Plan(
      String operation,
      long residentBytes,
      long replacedBytes,
      long stagedBytes,
      long retainedBytes,
      long peakBytes,
      long settledBytes) {
    public Plan {
      if (operation == null
          || operation.isBlank()
          || residentBytes < 0
          || replacedBytes < 0
          || replacedBytes > residentBytes
          || stagedBytes < 0
          || retainedBytes < 0
          || retainedBytes > stagedBytes
          || peakBytes != sum(residentBytes, stagedBytes)
          || settledBytes != sum(residentBytes - replacedBytes, retainedBytes))
        throw new IllegalArgumentException("Invalid GPU allocation plan");
    }
  }

  private Budget() {}

  public static long persistent(Settings s) {
    if (s.grid < 1 || s.particles < 0 || s.historyLayers < 0)
      throw new IllegalArgumentException("Invalid GPU resource dimensions");
    int history = Math.min(s.grid, 256);
    return sum(
        pixels(s.grid, s.grid, 88),
        Math.max(32, s.particles * 32L),
        multiply(pixels(history, history, 8), Math.max(1L, s.historyLayers) + 1));
  }

  public static long display(int width, int height) {
    return sum(
        pixels(width, height, 8), pixels(Math.max(1, width / 4), Math.max(1, height / 4), 8));
  }

  public static long pixels(int width, int height, int bytesPerPixel) {
    if (width < 1 || height < 1 || bytesPerPixel < 1)
      throw new IllegalArgumentException("Invalid GPU resource dimensions");
    return multiply(multiply(width, height), bytesPerPixel);
  }

  public static long sum(long... sizes) {
    long total = 0;
    try {
      for (long size : sizes) {
        if (size < 0) throw new IllegalArgumentException("GPU allocation size must be nonnegative");
        total = Math.addExact(total, size);
      }
    } catch (ArithmeticException e) {
      throw new IllegalArgumentException("GPU allocation size overflow", e);
    }
    return total;
  }

  private static long multiply(long a, long b) {
    try {
      return Math.multiplyExact(a, b);
    } catch (ArithmeticException e) {
      throw new IllegalArgumentException("GPU allocation size overflow", e);
    }
  }

  public static Plan replacement(String operation, long resident, long replaced, long candidate) {
    return new Plan(
        operation,
        resident,
        replaced,
        candidate,
        candidate,
        sum(resident, candidate),
        sum(resident - replaced, candidate));
  }

  public static Plan temporary(String operation, long resident, long temporary) {
    return new Plan(operation, resident, 0, temporary, 0, sum(resident, temporary), resident);
  }

  public static void require(Plan plan, long limit) {
    if (limit < 1) throw new IllegalArgumentException("GPU allocation budget must be positive");
    if (plan.peakBytes() > limit)
      throw new IllegalArgumentException(
          "GPU allocation peak for "
              + plan.operation()
              + " exceeds "
              + limit / 1048576.
              + " MiB: live "
              + plan.residentBytes()
              + " + staged "
              + plan.stagedBytes()
              + " = "
              + plan.peakBytes()
              + " bytes; settled "
              + plan.settledBytes()
              + " bytes");
  }

  public static void require(long bytes) {
    require(bytes, LIMIT);
  }

  public static void require(long bytes, long limit) {
    if (bytes < 0 || limit < 1)
      throw new IllegalArgumentException("Invalid GPU allocation budget or size");
    if (bytes > limit)
      throw new IllegalArgumentException(
          "Requested GPU allocation exceeds the "
              + limit / 1048576.
              + " MiB engine budget: "
              + Math.round(bytes / 1048576.)
              + " MiB");
  }
}
