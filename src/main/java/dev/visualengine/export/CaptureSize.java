package dev.visualengine.export;

/** Explicit output extent, independent of the window and persistent simulation grid. */
public record CaptureSize(int width, int height) {
  public CaptureSize {
    if (width < 32
        || height < 32
        || width > 8192
        || height > 8192
        || (long) width * height > 33_554_432)
      throw new IllegalArgumentException("Use capture dimensions 32..8192, at most 32 megapixels");
  }

  public static CaptureSize parse(String text) {
    if (text == null || !text.matches("[0-9]{1,5}x[0-9]{1,5}"))
      throw new IllegalArgumentException("Use WIDTHxHEIGHT, for example 1920x1080");
    String[] parts = text.split("x");
    return new CaptureSize(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
  }
}
