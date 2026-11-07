package dev.visualengine.io;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/** One file worker, bounded pending work, explicit draining and wakeable offline backpressure. */
public final class FileJobs implements AutoCloseable {
  @FunctionalInterface
  public interface Action {
    void run() throws Exception;
  }

  public enum State {
    OPEN,
    DRAINING,
    CANCELLING,
    TERMINATED
  }

  public record Snapshot(
      State state,
      int capacity,
      int queued,
      boolean running,
      long accepted,
      long completed,
      long failed,
      long cancelled,
      boolean stopOnFailure) {}

  private final Object gate = new Object();
  private final ArrayDeque<Action> pending = new ArrayDeque<>();
  private final int capacity;
  private final boolean stopOnFailure;
  private final Consumer<Throwable> onFailure;
  private final Thread worker;
  private State state = State.OPEN;
  private boolean running;
  private long accepted, completed, failed, cancelled;
  private Throwable terminalFailure;

  public FileJobs(int capacity, boolean stopOnFailure, Consumer<Throwable> onFailure) {
    if (capacity < 1) throw new IllegalArgumentException("File queue capacity must be positive");
    this.capacity = capacity;
    this.stopOnFailure = stopOnFailure;
    this.onFailure = Objects.requireNonNull(onFailure);
    worker = new Thread(this::run, "visual-file-worker");
    // Normal shutdown drains explicitly. A file operation ignoring interruption must not trap exit.
    worker.setDaemon(true);
    worker.start();
  }

  public boolean canAccept() {
    synchronized (gate) {
      return state == State.OPEN && terminalFailure == null && pending.size() < capacity;
    }
  }

  public Snapshot snapshot() {
    synchronized (gate) {
      return new Snapshot(
          state,
          capacity,
          pending.size(),
          running,
          accepted,
          completed,
          failed,
          cancelled,
          stopOnFailure);
    }
  }

  public void checkFailure() {
    synchronized (gate) {
      requireHealthy();
    }
  }

  private void requireHealthy() {
    if (terminalFailure != null)
      throw new IllegalStateException("A file operation failed", terminalFailure);
  }

  public boolean trySubmit(Action action) {
    Objects.requireNonNull(action);
    synchronized (gate) {
      requireHealthy();
      if (state != State.OPEN || pending.size() == capacity) return false;
      enqueue(action);
      return true;
    }
  }

  public void submitBlocking(Action action) {
    Objects.requireNonNull(action);
    if (Thread.currentThread() == worker)
      throw new IllegalStateException("File worker cannot wait for its own queue");
    synchronized (gate) {
      while (true) {
        requireHealthy();
        if (state != State.OPEN) throw new IllegalStateException("File queue is closing");
        if (pending.size() < capacity) {
          enqueue(action);
          return;
        }
        try {
          gate.wait();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException("Export submission interrupted", e);
        }
      }
    }
  }

  private void enqueue(Action action) {
    pending.addLast(action);
    accepted++;
    gate.notifyAll();
  }

  private void run() {
    try {
      while (true) {
        Action action;
        synchronized (gate) {
          while (pending.isEmpty() && state == State.OPEN) {
            try {
              gate.wait();
            } catch (InterruptedException e) {
              cancel(e);
            }
          }
          if (pending.isEmpty()) return;
          action = pending.removeFirst();
          running = true;
          gate.notifyAll();
        }
        Throwable problem = null;
        try {
          action.run();
        } catch (Throwable e) {
          problem = e;
          synchronized (gate) {
            if (stopOnFailure || e instanceof Error) cancel(e);
          }
          try {
            onFailure.accept(e);
          } catch (Throwable callback) {
            if (callback != e) e.addSuppressed(callback);
            if (callback instanceof Error)
              synchronized (gate) {
                cancel(callback);
              }
          }
        } finally {
          synchronized (gate) {
            running = false;
            if (problem == null) completed++;
            else failed++;
            gate.notifyAll();
          }
        }
      }
    } finally {
      synchronized (gate) {
        cancelled += pending.size();
        pending.clear();
        running = false;
        state = State.TERMINATED;
        gate.notifyAll();
      }
    }
  }

  /** Called under the gate; the active operation is accounted for when it actually finishes. */
  private void cancel(Throwable cause) {
    if (terminalFailure == null) terminalFailure = cause;
    cancelled += pending.size();
    pending.clear();
    state = State.CANCELLING;
    gate.notifyAll();
  }

  public void finish(Duration timeout) {
    Objects.requireNonNull(timeout);
    if (timeout.isNegative()) throw new IllegalArgumentException("Negative file shutdown timeout");
    long budget;
    try {
      budget = timeout.toNanos();
    } catch (ArithmeticException e) {
      throw new IllegalArgumentException("File shutdown timeout is too large", e);
    }
    if (Thread.currentThread() == worker)
      throw new IllegalStateException("File worker cannot wait for itself");
    long start = System.nanoTime();
    synchronized (gate) {
      if (state == State.OPEN) state = State.DRAINING;
      if (!running && pending.isEmpty()) state = State.TERMINATED;
      gate.notifyAll();
      while (state != State.TERMINATED) {
        long remaining = budget - (System.nanoTime() - start);
        if (remaining <= 0) {
          cancel(new TimeoutException("File shutdown deadline elapsed"));
          worker.interrupt();
          throw new IllegalStateException(
              "Timed out finishing file operations; "
                  + cancelled
                  + " queued jobs cancelled; the active operation may still be finishing",
              terminalFailure);
        }
        try {
          gate.wait(remaining / 1_000_000, (int) (remaining % 1_000_000));
        } catch (InterruptedException e) {
          cancel(e);
          worker.interrupt();
          Thread.currentThread().interrupt();
          throw new IllegalStateException(
              "File shutdown interrupted; queued work cancelled", terminalFailure);
        }
      }
      requireHealthy();
    }
  }

  @Override
  public void close() {
    finish(Duration.ofSeconds(30));
  }
}
