package dev.visualengine.platform;

/** Desktop transport preferences, deliberately separate from creative scene persistence. */
public record Presentation(boolean vsync, int frameLimit) {
  public static final Presentation DEFAULT = new Presentation(true, 0);
  public static final Presentation OFFLINE = new Presentation(false, 0);

  public Presentation {
    if (frameLimit < 0 || frameLimit > 240)
      throw new IllegalArgumentException("Frame limit must be 0 (uncapped) or 1..240 FPS");
  }
}
