package dev.visualengine.platform;

/**
 * Live pointer gate. A focus/fullscreen interruption requires a focused release before painting.
 */
public final class DesktopInput {
  public record Pointer(double x, double y, boolean brush) {}

  public static final Pointer NONE = new Pointer(0, 0, false);
  private boolean focused, armed;

  public void focus(boolean value) {
    focused = value;
    if (!value) cancel();
  }

  public void cancel() {
    armed = false;
  }

  /** Unfocused synthetic releases must not rearm a held button after an alt-tab. */
  public void release() {
    if (focused) armed = true;
  }

  public Pointer sample(
      double cursorX,
      double cursorY,
      int windowWidth,
      int windowHeight,
      int framebufferWidth,
      int framebufferHeight,
      boolean down) {
    if (windowWidth <= 0
        || windowHeight <= 0
        || framebufferWidth <= 0
        || framebufferHeight <= 0
        || !Double.isFinite(cursorX)
        || !Double.isFinite(cursorY)) {
      cancel();
      return NONE;
    }
    if (!down) release();
    boolean inside =
        cursorX >= 0 && cursorX < windowWidth && cursorY >= 0 && cursorY < windowHeight;
    double x =
        (Math.clamp(cursorX / windowWidth, 0, 1) * 2 - 1) * framebufferWidth / framebufferHeight;
    double y = 1 - Math.clamp(cursorY / windowHeight, 0, 1) * 2;
    return new Pointer(x, y, focused && armed && inside && down);
  }
}
