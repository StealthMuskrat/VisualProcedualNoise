package dev.visualengine.platform;

import java.util.List;

/**
 * Window content bounds and monitor geometry in GLFW screen coordinates, never framebuffer pixels.
 */
public final class WindowPlacement {
  private WindowPlacement() {}

  public record Bounds(int x, int y, int width, int height) {
    public Bounds {
      if (width <= 0 || height <= 0) throw new IllegalArgumentException("Empty window bounds");
    }
  }

  public record Insets(int left, int top, int right, int bottom) {
    public static final Insets NONE = new Insets(0, 0, 0, 0);

    public Insets {
      if (left < 0 || top < 0 || right < 0 || bottom < 0)
        throw new IllegalArgumentException("Negative window frame inset");
    }
  }

  public record Monitor(long id, Bounds bounds, Bounds workArea, int refreshRate) {}

  /** Greatest overlap, then nearest rectangle; the caller supplies primary-first monitor order. */
  public static Monitor choose(Bounds window, List<Monitor> monitors) {
    Monitor best = null;
    long bestArea = -1;
    double bestDistance = Double.POSITIVE_INFINITY;
    for (Monitor monitor : monitors) {
      Bounds b = monitor.bounds();
      long overlapX = Math.max(0L, Math.min(right(window), right(b)) - Math.max(window.x(), b.x()));
      long overlapY =
          Math.max(0L, Math.min(bottom(window), bottom(b)) - Math.max(window.y(), b.y()));
      long area = overlapX * overlapY;
      double dx =
          Math.max(0L, Math.max((long) b.x() - right(window), (long) window.x() - right(b)));
      double dy =
          Math.max(0L, Math.max((long) b.y() - bottom(window), (long) window.y() - bottom(b)));
      double distance = dx * dx + dy * dy;
      if (area > bestArea || area == bestArea && distance < bestDistance) {
        best = monitor;
        bestArea = area;
        bestDistance = distance;
      }
    }
    return best;
  }

  /** Keep the decorated window inside the work area, shrinking oversized content when necessary. */
  public static Bounds fit(Bounds desired, Bounds work, Insets frame) {
    int width =
        (int)
            Math.max(
                1L, Math.min(desired.width(), (long) work.width() - frame.left() - frame.right()));
    int height =
        (int)
            Math.max(
                1L,
                Math.min(desired.height(), (long) work.height() - frame.top() - frame.bottom()));
    long minX = (long) work.x() + frame.left(), minY = (long) work.y() + frame.top();
    long maxX = Math.max(minX, right(work) - frame.right() - width);
    long maxY = Math.max(minY, bottom(work) - frame.bottom() - height);
    return new Bounds(
        saturated(Math.clamp((long) desired.x(), minX, maxX)),
        saturated(Math.clamp((long) desired.y(), minY, maxY)),
        width,
        height);
  }

  private static long right(Bounds b) {
    return (long) b.x() + b.width();
  }

  private static long bottom(Bounds b) {
    return (long) b.y() + b.height();
  }

  private static int saturated(long value) {
    return (int) Math.clamp(value, Integer.MIN_VALUE, Integer.MAX_VALUE);
  }
}
