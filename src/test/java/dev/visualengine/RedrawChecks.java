package dev.visualengine;

import dev.visualengine.app.Command;
import dev.visualengine.platform.*;
import dev.visualengine.time.*;
import java.util.*;

public final class RedrawChecks {
  private static int count;

  private static void check(boolean ok, String text) {
    count++;
    if (!ok) throw new AssertionError(text);
  }

  private static void rejects(Runnable action, String text) {
    try {
      action.run();
    } catch (IllegalArgumentException expected) {
      count++;
      return;
    }
    throw new AssertionError(text);
  }

  private static boolean frozen(RedrawDemand demand) {
    return demand.needsFrame(false, true, false, 96, 64);
  }

  private static void present(RedrawDemand demand, DesktopInput.Pointer pointer) {
    demand.presented(demand.beginFrame(), 96, 64, pointer);
  }

  public static int run() {
    RedrawDemand demand = new RedrawDemand();
    check(frozen(demand), "first held frame is drawn even without an event");
    present(demand, DesktopInput.NONE);
    check(
        !frozen(demand) && !demand.snapshot().invalidated(),
        "unchanged held scene has no redraw demand");
    for (int i = 0; i < 40; i++) {
      demand.pointerChanged(new DesktopInput.Pointer(i / 40., -i / 80., false));
      demand.idleWait();
    }
    check(
        !frozen(demand)
            && demand.snapshot().presentedFrames() == 1
            && demand.snapshot().idleWaits() == 40,
        "hover and idle wakeups do not request another frame");
    var press = new DesktopInput.Pointer(.2, -.1, true);
    demand.pointerChanged(press);
    check(frozen(demand), "press requests a held brush-preview frame");
    present(demand, press);
    demand.pointerChanged(press);
    check(!frozen(demand), "stationary held brush does not redraw or tick continuously");
    var drag = new DesktopInput.Pointer(.25, -.15, true);
    demand.pointerChanged(drag);
    check(frozen(demand), "brush drag changes visible pointer uniforms");
    present(demand, drag);
    demand.pointerChanged(DesktopInput.NONE);
    check(frozen(demand), "brush release requests removal of its visible preview");
    present(demand, DesktopInput.NONE);
    demand.pointerChanged(new DesktopInput.Pointer(100, 200, false));
    check(!frozen(demand), "inactive pointer coordinates are ignored after release");
    demand.invalidate();
    demand.invalidate();
    check(frozen(demand), "coalesced edits or refreshes retain demand until presented");
    long token = demand.beginFrame();
    demand.invalidate();
    demand.presented(token, 96, 64, DesktopInput.NONE);
    check(frozen(demand), "invalidation during a frame survives its completion");
    present(demand, DesktopInput.NONE);
    check(!frozen(demand), "completing the latest revision clears explicit demand");
    check(demand.needsFrame(false, true, false, 97, 65), "framebuffer changes redraw held scenes");
    check(
        demand.needsFrame(false, true, true, 96, 64),
        "capture reset step and finite-frame work force a frame");
    check(
        demand.needsFrame(false, false, false, 96, 64),
        "playing sessions continue normal frame scheduling");
    check(
        demand.needsFrame(true, true, false, 96, 64),
        "offline output bypasses held-scene idle policy");
    check(
        !demand.needsFrame(true, false, true, 0, 64)
            && !demand.needsFrame(false, true, true, 96, 0),
        "zero framebuffers cannot request dependent work in any mode");
    rejects(
        () -> demand.pointerChanged(new DesktopInput.Pointer(Double.NaN, 0, true)),
        "Accepted nonfinite pointer");
    rejects(
        () -> demand.presented(demand.beginFrame(), 0, 64, DesktopInput.NONE),
        "Accepted invalid presented dimensions");

    check(
        List.of("gpuReport", "save", "load", "morphCapture", "discoveryScan", "presentationSaved")
            .stream()
            .map(type -> new Command(type, null))
            .allMatch(c -> !c.needsFrame() && !c.editsHeldControls()),
        "metadata and asynchronous preparation do not disturb a held scene");
    check(
        List.of("capture", "saveCheckpoint", "inspect", "checkpointLoaded", "referenceLoaded")
            .stream()
            .map(type -> new Command(type, null))
            .allMatch(Command::needsFrame),
        "frame-bound output and completed asynchronous activation request presentation");
    check(
        List.of("parameter", "effectValue", "modulations", "morphBlend", "undo").stream()
            .map(type -> new Command(type, null))
            .allMatch(c -> c.needsFrame() && c.editsHeldControls()),
        "explicit control edits redraw and resolve held values");
    check(
        List.of("pause", "speed", "reload", "freeze", "palette", "presentation").stream()
            .map(type -> new Command(type, null))
            .allMatch(c -> c.needsFrame() && !c.editsHeldControls()),
        "transport visual and reference actions redraw without settling unfinished smoothing");
    check(
        new Command("future-action", null).needsFrame(),
        "unknown future commands conservatively redraw");

    FramePacer pacer = new FramePacer();
    pacer.configure(1);
    pacer.frameStarted(0);
    demand.invalidate();
    check(
        frozen(demand) && pacer.waitNanos(1) == 999_999_999,
        "new redraw demand does not bypass the presentation deadline");
    Playback clock = new Playback();
    clock.accumulate(.004, 1, false);
    clock.discardBacklog();
    check(
        clock.accumulate(0, 1, false) == 0 && clock.tick() == 0,
        "discarding an idle interval changes neither ticks nor fractional backlog");
    ControlSignals signals = new ControlSignals();
    var settings = EffectModulationChecks.settings();
    signals.snap(settings);
    signals.tick(settings, Playback.DT);
    var state = signals.snapshot(settings);
    for (String type :
        List.of("save", "gpuReport", "reload", "capture", "freeze", "pause", "speed"))
      if (new Command(type, null).editsHeldControls()) signals.snap(settings, Playback.DT);
    check(
        signals.snapshot(settings).equals(state),
        "metadata transport and capture work leave resolved smoothing and clocks untouched");
    return count;
  }
}
