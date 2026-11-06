package dev.visualengine.gpu;

import static org.lwjgl.opengl.GL43C.*;

import java.util.*;

/** Nonblocking collection during frames. Tagged results are drained once by benchmark callers. */
public final class GpuTimer implements AutoCloseable {
  public record Sample(long sequence, long frame, double milliseconds) {
    public Sample {
      if (sequence < 1 || frame < 0 || !Double.isFinite(milliseconds) || milliseconds < 0)
        throw new IllegalArgumentException("Invalid GPU timing sample");
    }
  }

  public record Batch(List<Sample> samples, List<Long> skippedFrames, int pending) {
    public Batch {
      samples = List.copyOf(samples);
      skippedFrames = List.copyOf(skippedFrames);
      if (pending < 0 || pending > 8)
        throw new IllegalArgumentException("Invalid pending query count");
    }
  }

  private final int[] queries = new int[8];
  private final boolean[] pending = new boolean[8];
  private final long[] frames = new long[8], sequences = new long[8];
  private final List<Sample> completed = new ArrayList<>();
  private final List<Long> skipped = new ArrayList<>();
  private int cursor, slot;
  private boolean active;
  private double milliseconds;
  private long issued, latest;

  public GpuTimer() {
    for (int i = 0; i < queries.length; i++) queries[i] = glGenQueries();
  }

  public void begin() {
    begin(-1);
  }

  public void begin(long frame) {
    if (active) throw new IllegalStateException("GPU timer query is already active");
    poll();
    for (int i = 0; i < queries.length; i++) {
      slot = (cursor + i) % queries.length;
      if (!pending[slot]) {
        active = true;
        frames[slot] = frame;
        sequences[slot] = ++issued;
        glBeginQuery(GL_TIME_ELAPSED, queries[slot]);
        return;
      }
    }
    if (frame >= 0) skipped.add(frame);
  }

  public void end() {
    if (active) {
      glEndQuery(GL_TIME_ELAPSED);
      pending[slot] = true;
      cursor = (slot + 1) % queries.length;
      active = false;
    }
  }

  public void poll() {
    for (int i = 0; i < queries.length; i++)
      if (pending[i] && glGetQueryObjecti(queries[i], GL_QUERY_RESULT_AVAILABLE) != 0) {
        collect(i);
      }
  }

  private void collect(int i) {
    double ms = glGetQueryObjectui64(queries[i], GL_QUERY_RESULT) / 1e6;
    if (sequences[i] > latest) {
      latest = sequences[i];
      milliseconds = ms;
    }
    if (frames[i] >= 0) completed.add(new Sample(sequences[i], frames[i], ms));
    pending[i] = false;
  }

  public Batch drain() {
    poll();
    return take();
  }

  /** Blocking tail collection is reserved for outside measured frame intervals. */
  public Batch finish() {
    if (active) throw new IllegalStateException("Cannot finish an active GPU timer");
    for (int i = 0; i < queries.length; i++) if (pending[i]) collect(i);
    return take();
  }

  private Batch take() {
    completed.sort(Comparator.comparingLong(Sample::sequence));
    int count = 0;
    for (boolean p : pending) if (p) count++;
    Batch batch = new Batch(completed, skipped, count);
    completed.clear();
    skipped.clear();
    return batch;
  }

  public double milliseconds() {
    return milliseconds;
  }

  @Override
  public void close() {
    for (int q : queries) glDeleteQueries(q);
  }
}
