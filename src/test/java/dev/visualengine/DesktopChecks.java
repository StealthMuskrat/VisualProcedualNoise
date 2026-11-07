package dev.visualengine;

import dev.visualengine.platform.*;
import dev.visualengine.platform.WindowPlacement.*;
import dev.visualengine.time.Playback;
import java.util.List;

public final class DesktopChecks {
  private static int count;

  private static void check(boolean ok, String label) {
    count++;
    if (!ok) throw new AssertionError(label);
  }

  static int run() {
    var primary = new Monitor(1, new Bounds(0, 0, 1920, 1080), new Bounds(0, 0, 1920, 1040), 60);
    var left =
        new Monitor(
            2, new Bounds(-2560, -360, 2560, 1440), new Bounds(-2560, -330, 2560, 1410), 144);
    var monitors = List.of(primary, left);
    check(
        WindowPlacement.choose(new Bounds(-1000, 20, 900, 700), monitors).id() == 2,
        "fullscreen follows the window onto a negative-coordinate secondary monitor");
    check(
        WindowPlacement.choose(new Bounds(-200, 20, 900, 700), monitors).id() == 1,
        "straddling window uses greatest monitor overlap");
    check(
        WindowPlacement.choose(new Bounds(-3000, 1500, 200, 200), monitors).id() == 2,
        "disconnected/offscreen placement uses nearest remaining monitor");
    check(
        WindowPlacement.choose(new Bounds(-100, 20, 200, 200), monitors).id() == 1,
        "equal overlap deterministically prefers primary-first order");
    check(
        WindowPlacement.choose(primary.bounds(), List.of()) == null,
        "no-monitor geometry is explicit");
    var frame = new Insets(8, 31, 8, 8);
    var original = new Bounds(-2200, -200, 1280, 800);
    check(
        WindowPlacement.fit(original, left.workArea(), frame).equals(original),
        "visible window position and custom size survive fullscreen restore");
    check(
        WindowPlacement.fit(new Bounds(-3000, -500, 4000, 2000), left.workArea(), frame)
            .equals(new Bounds(-2552, -299, 2544, 1371)),
        "oversized restore fits content and title bar inside the monitor work area");
    check(
        WindowPlacement.fit(original, primary.workArea(), frame)
            .equals(new Bounds(8, 31, 1280, 800)),
        "removed secondary monitor relocates restore to accessible primary work area");
    var extreme = new Bounds(Integer.MAX_VALUE - 20, Integer.MIN_VALUE, 1000, 1000);
    check(
        WindowPlacement.choose(extreme, List.of(new Monitor(3, extreme, extreme, 60))).id() == 3,
        "monitor overlap avoids signed coordinate overflow");

    DesktopInput input = new DesktopInput();
    input.focus(true);
    check(
        !input.sample(200, 100, 800, 400, 1600, 800, true).brush(),
        "startup held button waits for a deliberate focused release");
    input.sample(200, 100, 800, 400, 1600, 800, false);
    var p = input.sample(200, 100, 800, 400, 1600, 800, true);
    check(
        p.x() == -1 && p.y() == .5 && p.brush(),
        "high-DPI cursor conversion uses window units and framebuffer aspect");
    check(
        p.equals(input.sample(200, 100, 800, 400, 800, 400, true)),
        "uniform DPI changes preserve domain pointer position");
    input.focus(false);
    input.release();
    input.focus(true);
    check(
        !input.sample(200, 100, 800, 400, 1600, 800, true).brush(),
        "focus loss and synthetic release cannot resume a held pigment injector");
    input.release();
    check(
        input.sample(200, 100, 800, 400, 1600, 800, true).brush(),
        "release callback between capped frames rearms a fresh press");
    check(
        !input.tickBrush(true) && input.tickBrush(true),
        "focus interruption emits a tick release before a fresh stroke, including after a paused"
            + " interval");
    check(
        !input.sample(-1, 100, 800, 400, 1600, 800, true).brush()
            && !input.sample(800, 100, 800, 400, 1600, 800, true).brush(),
        "captured mouse outside artwork cannot paint");
    input.cancel();
    check(
        !input.sample(200, 100, 800, 400, 1600, 800, true).brush(),
        "fullscreen and minimize transitions require a fresh pointer gesture");
    check(
        input.sample(200, 100, 0, 0, 0, 0, true).equals(DesktopInput.NONE)
            && input.sample(Double.NaN, 100, 800, 400, 1600, 800, true).equals(DesktopInput.NONE),
        "zero dimensions and nonfinite pointer values cannot enter render state");

    DesktopLifecycle lifecycle = new DesktopLifecycle();
    check(
        !lifecycle.update(false, false, 800, 400, true).suspended(),
        "ordinary desktop remains active");
    check(
        lifecycle.update(false, true, 800, 400, true).suspended(),
        "iconified window suspends even when native framebuffer dimensions stay positive");
    check(
        lifecycle.update(false, true, 0, 0, false).suspended(),
        "zero framebuffer always suspends dependent work");
    var resumed = lifecycle.update(false, false, 800, 400, true);
    Playback clock = new Playback();
    check(
        !resumed.suspended()
            && resumed.discardElapsed()
            && clock.accumulate(resumed.discardElapsed() ? 0 : 60, 1, false) == 0,
        "restoring after a hidden minute advances no catch-up ticks");
    clock.advance();
    clock.accumulate(Playback.DT * .75, 1, false);
    clock.discardBacklog();
    check(
        clock.tick() == 1 && clock.accumulate(Playback.DT * .5, 1, false) == 0,
        "suspension clears fractional backlog while retaining developed tick state");
    check(
        !lifecycle.update(false, false, 800, 400, true).discardElapsed(),
        "resume consumes one clock boundary then normal time continues");
    check(
        !lifecycle.update(false, true, 800, 400, false).suspended(),
        "explicit continue-while-minimized preference keeps positive-sized rendering active");
    check(
        !lifecycle.update(true, true, 800, 400, true).suspended(),
        "offline scheduling ignores desktop minimization preferences");
    lifecycle.interrupt();
    check(
        lifecycle.update(false, false, 800, 400, true).discardElapsed(),
        "focus and display transitions discard their blocking interval");
    return count;
  }
}
