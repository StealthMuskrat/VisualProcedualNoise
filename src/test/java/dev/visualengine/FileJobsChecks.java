package dev.visualengine;

import dev.visualengine.io.FileJobs;
import java.io.IOException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public final class FileJobsChecks {
  private static int count;
  private static final Duration WAIT = Duration.ofSeconds(5);

  private static void check(boolean value, String text) {
    count++;
    if (!value) throw new AssertionError(text);
  }

  private static void await(CountDownLatch latch) throws InterruptedException {
    if (!latch.await(5, TimeUnit.SECONDS))
      throw new AssertionError("File worker did not reach barrier");
  }

  private static void uninterruptible(CountDownLatch release, CountDownLatch interrupted) {
    while (true) {
      try {
        release.await();
        return;
      } catch (InterruptedException e) {
        interrupted.countDown();
      }
    }
  }

  private static RuntimeException fails(Runnable action, String text) {
    try {
      action.run();
    } catch (RuntimeException expected) {
      count++;
      return expected;
    }
    throw new AssertionError(text);
  }

  private static CompletableFuture<Void> async(Runnable action) {
    var future = new CompletableFuture<Void>();
    Thread thread =
        new Thread(
            () -> {
              try {
                action.run();
                future.complete(null);
              } catch (Throwable e) {
                future.completeExceptionally(e);
              }
            });
    thread.setDaemon(true);
    thread.start();
    return future;
  }

  private static Throwable failedFuture(CompletableFuture<Void> future) throws Exception {
    try {
      future.get(5, TimeUnit.SECONDS);
    } catch (ExecutionException e) {
      return e.getCause();
    }
    throw new AssertionError("Blocked submission unexpectedly succeeded");
  }

  private static boolean settled(FileJobs q) {
    var s = q.snapshot();
    return s.state() == FileJobs.State.TERMINATED
        && !s.running()
        && s.queued() == 0
        && s.accepted() == s.completed() + s.failed() + s.cancelled();
  }

  public static int run() throws Exception {
    fails(() -> new FileJobs(0, true, e -> {}), "Accepted empty file queue");
    normalAndBackpressure();
    failFast();
    continueAfterError();
    drainingWakesProducer();
    interruptedProducer();
    cancelledShutdown(false);
    cancelledShutdown(true);
    fatalError();
    try (FileJobs q = new FileJobs(2, false, e -> {})) {
      fails(() -> q.finish(Duration.ofSeconds(-1)), "Accepted negative shutdown timeout");
      var selfChecks = new AtomicInteger();
      q.submitBlocking(
          () -> {
            try {
              q.submitBlocking(() -> {});
            } catch (IllegalStateException expected) {
              selfChecks.incrementAndGet();
            }
            try {
              q.finish(WAIT);
            } catch (IllegalStateException expected) {
              selfChecks.incrementAndGet();
            }
          });
      q.finish(WAIT);
      check(selfChecks.get() == 2 && settled(q), "worker cannot deadlock by blocking on itself");
      check(!q.trySubmit(() -> {}) && !q.canAccept(), "drained queue rejects new interactive work");
      fails(() -> q.submitBlocking(() -> {}), "Accepted offline work after shutdown");
    }
    return count;
  }

  private static void normalAndBackpressure() throws Exception {
    var started = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var order = new ArrayList<Integer>();
    var daemon = new AtomicBoolean();
    try (FileJobs q =
        new FileJobs(
            2,
            true,
            e -> {
              throw new AssertionError(e);
            })) {
      q.submitBlocking(
          () -> {
            daemon.set(Thread.currentThread().isDaemon());
            started.countDown();
            release.await();
            order.add(0);
          });
      await(started);
      check(
          q.trySubmit(() -> order.add(1))
              && q.trySubmit(() -> order.add(2))
              && !q.trySubmit(() -> order.add(99)),
          "interactive file work never exceeds two queued jobs");
      var producer = async(() -> q.submitBlocking(() -> order.add(3)));
      check(
          q.snapshot().queued() == 2 && q.snapshot().running() && q.snapshot().accepted() == 3,
          "offline producer waits without inserting work beyond capacity");
      release.countDown();
      producer.get(5, TimeUnit.SECONDS);
      q.finish(WAIT);
      check(
          order.equals(List.of(0, 1, 2, 3))
              && daemon.get()
              && q.snapshot().completed() == 4
              && settled(q),
          "accepted work drains in FIFO order and worker cannot hold the JVM open after failed"
              + " shutdown");
    } finally {
      release.countDown();
    }
  }

  private static void failFast() throws Exception {
    var started = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    IOException original = new IOException("first export failed");
    var observed = new AtomicReference<Throwable>();
    var ran = new AtomicInteger();
    FileJobs q = new FileJobs(2, true, observed::set);
    try {
      q.submitBlocking(
          () -> {
            started.countDown();
            release.await();
            throw original;
          });
      await(started);
      q.submitBlocking(ran::incrementAndGet);
      q.submitBlocking(ran::incrementAndGet);
      var producer = async(() -> q.submitBlocking(ran::incrementAndGet));
      release.countDown();
      check(
          failedFuture(producer).getCause() == original,
          "first export failure wakes blocked producer with original cause");
      check(
          fails(() -> q.finish(WAIT), "Ignored failed export").getCause() == original
              && observed.get() == original
              && ran.get() == 0
              && q.snapshot().cancelled() == 2
              && settled(q),
          "offline failure cancels queued writes and shutdown reports exact terminal failure");
      check(
          fails(q::checkFailure, "Lost export failure").getCause() == original,
          "render loop can observe the sticky file failure before another capture");
    } finally {
      release.countDown();
      try {
        q.finish(WAIT);
      } catch (IllegalStateException expected) {
      }
    }
  }

  private static void continueAfterError() throws Exception {
    var notified = new CountDownLatch(1);
    var ran = new AtomicBoolean();
    IOException original = new IOException("interactive save failed");
    try (FileJobs q =
        new FileJobs(
            2,
            false,
            e -> {
              notified.countDown();
              throw new IllegalArgumentException("status callback");
            })) {
      q.submitBlocking(
          () -> {
            throw original;
          });
      await(notified);
      q.submitBlocking(() -> ran.set(true));
      q.finish(WAIT);
      q.checkFailure();
      check(
          ran.get()
              && original.getSuppressed().length == 1
              && q.snapshot().failed() == 1
              && q.snapshot().completed() == 1
              && settled(q),
          "desktop file error and reporting exception leave worker available for the next save");
    }
  }

  private static void drainingWakesProducer() throws Exception {
    var started = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    FileJobs q = new FileJobs(2, true, e -> {});
    try {
      q.submitBlocking(
          () -> {
            started.countDown();
            release.await();
          });
      await(started);
      q.submitBlocking(() -> {});
      q.submitBlocking(() -> {});
      var producer = async(() -> q.submitBlocking(() -> {}));
      var closer = async(() -> q.finish(WAIT));
      check(
          failedFuture(producer).getMessage().contains("closing"),
          "draining wakes a producer without appending unreachable work");
      release.countDown();
      closer.get(5, TimeUnit.SECONDS);
      check(
          q.snapshot().accepted() == 3 && q.snapshot().completed() == 3 && settled(q),
          "normal close finishes every previously accepted job");
    } finally {
      release.countDown();
      q.finish(WAIT);
    }
  }

  private static void interruptedProducer() throws Exception {
    var started = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var interrupted = new AtomicBoolean();
    var outcome = new CompletableFuture<Throwable>();
    try (FileJobs q = new FileJobs(2, false, e -> {})) {
      q.submitBlocking(
          () -> {
            started.countDown();
            release.await();
          });
      await(started);
      q.submitBlocking(() -> {});
      q.submitBlocking(() -> {});
      Thread producer =
          new Thread(
              () -> {
                try {
                  q.submitBlocking(() -> {});
                  outcome.complete(null);
                } catch (RuntimeException e) {
                  interrupted.set(Thread.currentThread().isInterrupted());
                  outcome.complete(e);
                }
              });
      producer.setDaemon(true);
      producer.start();
      producer.interrupt();
      Throwable failure = outcome.get(5, TimeUnit.SECONDS);
      check(
          interrupted.get()
              && failure.getCause() instanceof InterruptedException
              && q.snapshot().accepted() == 3,
          "interrupted backpressure preserves interrupt status and does not accept the job");
      release.countDown();
      q.finish(WAIT);
      check(
          q.snapshot().completed() == 3 && settled(q),
          "interrupting a producer does not cancel unrelated accepted work");
    } finally {
      release.countDown();
    }
  }

  private static void cancelledShutdown(boolean interruptCaller) throws Exception {
    var started = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var workerInterrupted = new CountDownLatch(1);
    FileJobs q = new FileJobs(2, true, e -> {});
    try {
      q.submitBlocking(
          () -> {
            started.countDown();
            uninterruptible(release, workerInterrupted);
          });
      await(started);
      q.submitBlocking(() -> {});
      q.submitBlocking(() -> {});
      if (interruptCaller) {
        var flag = new AtomicBoolean();
        var closer =
            async(
                () -> {
                  Thread.currentThread().interrupt();
                  try {
                    q.finish(WAIT);
                  } finally {
                    flag.set(Thread.currentThread().isInterrupted());
                  }
                });
        check(
            failedFuture(closer).getMessage().contains("interrupted") && flag.get(),
            "interrupted shutdown reports cancellation and preserves the caller interrupt");
      } else {
        check(
            fails(() -> q.finish(Duration.ZERO), "Ignored unfinished shutdown")
                .getMessage()
                .contains("Timed out"),
            "shutdown deadline reports unfinished work instead of success");
      }
      await(workerInterrupted);
      check(
          q.snapshot().state() == FileJobs.State.CANCELLING
              && q.snapshot().cancelled() == 2
              && q.snapshot().running(),
          "cancelled shutdown distinguishes discarded queued writes from still-running work");
      release.countDown();
      fails(() -> q.finish(WAIT), "Lost failed shutdown");
      check(
          q.snapshot().completed() == 1 && settled(q),
          "active operation can finish cleanup after shutdown cancellation");
    } finally {
      release.countDown();
      try {
        q.finish(WAIT);
      } catch (IllegalStateException expected) {
      }
    }
  }

  private static void fatalError() throws Exception {
    var started = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    AssertionError original = new AssertionError("worker fatal error");
    FileJobs q = new FileJobs(2, false, e -> {});
    try {
      q.submitBlocking(
          () -> {
            started.countDown();
            release.await();
            throw original;
          });
      await(started);
      q.submitBlocking(() -> {});
      release.countDown();
      check(
          fails(() -> q.finish(WAIT), "Lost fatal worker error").getCause() == original
              && q.snapshot().cancelled() == 1
              && q.snapshot().failed() == 1
              && settled(q),
          "fatal worker error terminates even the desktop queue and cannot strand pending work");
    } finally {
      release.countDown();
      try {
        q.finish(WAIT);
      } catch (IllegalStateException expected) {
      }
    }
  }
}
