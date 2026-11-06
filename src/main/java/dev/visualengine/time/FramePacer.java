package dev.visualengine.time;

/** Deadline calculation only: the context loop waits through the wakeable GLFW event API. */
public final class FramePacer {
  private long interval, deadline;
  private boolean scheduled;

  public void configure(int frameLimit) {
    if (frameLimit < 0 || frameLimit > 240)
      throw new IllegalArgumentException("Frame limit must be 0..240");
    interval = frameLimit == 0 ? 0 : (1_000_000_000L + frameLimit - 1) / frameLimit;
    reset();
  }

  public void reset() {
    scheduled = false;
  }

  public long waitNanos(long now) {
    if (!scheduled || interval == 0) return 0;
    // Intervals are at most one second, so subtraction also handles nanoTime wraparound.
    long remaining = deadline - now;
    return remaining <= 0 ? 0 : Math.min(remaining, interval);
  }

  public void frameStarted(long now) {
    deadline = now + interval;
    scheduled = interval != 0;
  }
}
