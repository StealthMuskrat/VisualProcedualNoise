package dev.visualengine.platform;

/** Decides suspension and resume clock boundaries independently of the OS event source. */
public final class DesktopLifecycle {
  public record Activity(boolean suspended, boolean discardElapsed) {}

  private boolean suspended, interrupted;

  public void interrupt() {
    interrupted = true;
  }

  public Activity update(
      boolean offline,
      boolean iconified,
      int framebufferWidth,
      int framebufferHeight,
      boolean suspendWhenMinimized) {
    boolean next =
        framebufferWidth <= 0
            || framebufferHeight <= 0
            || !offline && iconified && suspendWhenMinimized;
    boolean discard = interrupted || suspended || next;
    interrupted = false;
    suspended = next;
    return new Activity(next, discard);
  }
}
