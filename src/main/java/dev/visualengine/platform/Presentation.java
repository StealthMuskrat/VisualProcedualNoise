package dev.visualengine.platform;

/** Desktop transport preferences, deliberately separate from creative scene persistence. */
public record Presentation(boolean vsync, int frameLimit, boolean suspendWhenMinimized) {
  public static final Presentation DEFAULT = new Presentation(true, 0, true);
  public static final Presentation OFFLINE = new Presentation(false, 0, false);

  public Presentation(boolean vsync, int frameLimit) {
    this(vsync, frameLimit, true);
  }

  public Presentation {
    if (frameLimit < 0 || frameLimit > 240)
      throw new IllegalArgumentException("Frame limit must be 0 (uncapped) or 1..240 FPS");
  }
}
