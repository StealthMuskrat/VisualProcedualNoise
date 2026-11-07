package dev.visualengine.platform;

/** Context-thread redraw demand. Only real input events compare the visible brush preview. */
public final class RedrawDemand {
  private long revision = 1, paintedRevision, frames, idleWaits;
  private int width, height;
  private boolean hasFrame;
  private DesktopInput.Pointer pointer = DesktopInput.NONE;

  public record Snapshot(
      boolean invalidated, int lastWidth, int lastHeight, long presentedFrames, long idleWaits) {}

  public void invalidate() {
    revision++;
  }

  public void pointerChanged(DesktopInput.Pointer next) {
    validate(next);
    if (pointer.brush() != next.brush()
        || next.brush() && (pointer.x() != next.x() || pointer.y() != next.y())) invalidate();
  }

  public boolean needsFrame(
      boolean offline, boolean frozen, boolean pendingWork, int width, int height) {
    if (width <= 0 || height <= 0) return false;
    return offline
        || !frozen
        || pendingWork
        || !hasFrame
        || revision != paintedRevision
        || this.width != width
        || this.height != height;
  }

  /** Capture after queued edits/ticks, immediately before drawing. */
  public long beginFrame() {
    return revision;
  }

  /** New invalidations after beginFrame remain pending instead of being lost at presentation. */
  public void presented(long atStart, int width, int height, DesktopInput.Pointer pointer) {
    if (width <= 0 || height <= 0)
      throw new IllegalArgumentException("Invalid presented dimensions");
    validate(pointer);
    this.width = width;
    this.height = height;
    this.pointer = pointer.brush() ? pointer : DesktopInput.NONE;
    paintedRevision = atStart;
    hasFrame = true;
    frames++;
  }

  public void idleWait() {
    idleWaits++;
  }

  public Snapshot snapshot() {
    return new Snapshot(!hasFrame || revision != paintedRevision, width, height, frames, idleWaits);
  }

  private static void validate(DesktopInput.Pointer pointer) {
    if (pointer == null || !Double.isFinite(pointer.x()) || !Double.isFinite(pointer.y()))
      throw new IllegalArgumentException("Invalid redraw pointer");
  }
}
