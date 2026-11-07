package dev.visualengine;

import dev.visualengine.gpu.Budget;
import dev.visualengine.model.*;

public final class PeakBudgetChecks {
  private static int count;

  private static void check(boolean ok, String label) {
    count++;
    if (!ok) throw new AssertionError(label);
  }

  private static void rejects(Runnable action, String text) {
    try {
      action.run();
    } catch (IllegalArgumentException expected) {
      check(expected.getMessage().contains(text), "allocation rejection: " + text);
      return;
    }
    throw new AssertionError("Accepted " + text);
  }

  static int run() {
    var replacement = Budget.replacement("simulation", 100, 80, 80);
    check(
        replacement.peakBytes() == 180 && replacement.settledBytes() == 100,
        "replacing resources counts simultaneous old and new allocations separately from settled"
            + " storage");
    rejects(() -> Budget.require(replacement, 128), "peak");
    Budget.require(replacement, 180);
    check(true, "transaction peak fits exactly at its declared limit");
    var temporary = Budget.temporary("still", 100, 30);
    check(
        temporary.peakBytes() == 130
            && temporary.settledBytes() == 100
            && temporary.retainedBytes() == 0,
        "temporary captures release their staged storage without replacing live state");
    var initial = Budget.replacement("startup", 0, 0, 64);
    check(
        initial.peakBytes() == 64 && initial.settledBytes() == 64,
        "initial allocation has no old resource overlap");
    var reference = Budget.replacement("reference", 200, 50, 60);
    check(
        reference.peakBytes() == 260 && reference.settledBytes() == 210,
        "reference replacement keeps the old reference until the new one is ready");
    check(
        Budget.display(97, 65) == 97L * 65 * 8 + 24L * 16 * 8,
        "odd output dimensions use the actual integer bloom extent");
    check(
        Budget.pixels(8192, 4096, 16) == Budget.LIMIT,
        "32-megapixel RGBA32F map storage reaches the budget before live resources");
    for (QualityProfile quality : QualityProfile.values()) {
      Settings s = quality.apply(new Settings());
      long state = Budget.persistent(s), display = Budget.display(1920, 1080);
      var plan = Budget.replacement("quality", Budget.sum(state, display), state, state);
      Budget.require(plan, Budget.LIMIT);
      check(
          plan.peakBytes() == 2 * state + display && plan.settledBytes() == state + display,
          "quality transition includes both state sets: " + quality);
    }
    rejects(() -> Budget.sum(Long.MAX_VALUE, 1), "overflow");
    rejects(() -> Budget.pixels(Integer.MAX_VALUE, Integer.MAX_VALUE, 16), "overflow");
    rejects(() -> Budget.replacement("bad", 10, 20, 10), "nonnegative");
    rejects(() -> Budget.temporary("bad", 10, -1), "nonnegative");
    rejects(() -> Budget.require(-1), "Invalid");
    rejects(() -> Budget.require(initial, 0), "positive");
    rejects(() -> Budget.display(0, 20), "dimensions");
    rejects(() -> new Budget.Plan("forged", 100, 0, 50, 50, 100, 150), "Invalid");
    return count;
  }
}
