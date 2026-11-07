package dev.visualengine;

import dev.visualengine.authoring.*;
import dev.visualengine.model.*;
import dev.visualengine.time.*;
import java.util.*;
import java.util.concurrent.*;

public final class SceneHistoryChecks {
  private static int count;

  private static void check(boolean ok, String text) {
    count++;
    if (!ok) throw new AssertionError(text);
  }

  private static void rejects(Runnable action, String text) {
    try {
      action.run();
    } catch (IllegalArgumentException | IllegalStateException expected) {
      count++;
      return;
    }
    throw new AssertionError(text);
  }

  private static Settings palette(int value) {
    Settings s = new Settings();
    s.palette = value;
    return s;
  }

  private static void remember(SceneHistory history, Settings before, Settings after) {
    try (var change = history.begin()) {
      change.remember(before);
      change.commit(after);
    }
  }

  private static SceneHistory.Restoration peek(SceneHistory history, Settings live) {
    try (var change = history.begin()) {
      return change.undo(live).orElseThrow();
    }
  }

  public static int run() throws Exception {
    count = 0;
    SceneHistory history = new SceneHistory(2);
    try (var pending = history.begin()) {
      pending.remember(palette(0));
      check(history.size() == 0, "pending edit does not publish history");
    }
    check(history.size() == 0, "rejected edit leaves no history entry");
    remember(history, palette(0), palette(1));
    remember(history, palette(1), palette(2));
    try (var pending = history.begin()) {
      pending.remember(palette(2));
    }
    check(
        history.size() == 2 && peek(history, palette(2)).settings().palette == 1,
        "aborted edit at capacity never evicts useful undo history");
    var restoration = peek(history, palette(2));
    restoration.settings().palette = 3;
    check(peek(history, palette(2)).settings().palette == 1, "undo candidates own their settings");
    check(history.size() == 2, "failed undo does not consume the selected entry");
    try (var change = history.begin()) {
      var restored = change.undo(palette(2)).orElseThrow();
      change.commit(restored.settings());
    }
    check(
        history.size() == 1 && peek(history, palette(1)).settings().palette == 0,
        "successful undo consumes exactly one entry");
    Settings source = palette(2);
    try (var change = history.begin()) {
      change.remember(source);
      source.palette = 0;
      change.commit(palette(3));
    }
    check(
        peek(history, palette(3)).settings().palette == 2,
        "remember owns the before-edit snapshot");
    remember(history, palette(3), palette(0));
    check(history.size() == 2, "successful edits retain the configured history bound");
    try (var change = history.begin()) {
      rejects(history::begin, "overlapping history mutation");
      change.remember(palette(0));
      rejects(() -> change.undo(palette(1)), "mixed edit and undo");
      change.commit(palette(1));
      rejects(() -> change.commit(palette(1)), "repeated commit");
    }
    SceneHistory.Change stale = history.begin();
    stale.remember(palette(1));
    history.clear();
    rejects(() -> stale.commit(palette(2)), "checkpoint-invalidated edit");
    stale.close();
    check(history.size() == 0, "checkpoint replacement clears history");
    remember(history, palette(0), palette(0));
    check(history.size() == 0, "unchanged configurations do not add redundant undo entries");
    Settings phaseOnly = palette(0);
    phaseOnly.motionClock = 3;
    remember(history, palette(0), phaseOnly);
    check(history.size() == 0, "phase clocks are not configuration history");
    Settings locks = palette(0);
    Settings.PARAMETERS.forEach(parameter -> locks.locks.add(parameter.id()));
    locks.locks.retainAll(Set.of("scale", "motion", "agitation"));
    remember(history, locks, locks.copy());
    check(history.size() == 0, "lock sets retain semantic equality after growth and copying");
    try (var change = history.begin()) {
      check(change.undo(palette(0)).isEmpty(), "empty undo is explicitly available as a no-op");
      change.commit(palette(0));
    }
    check(
        !SceneHistory.controlsDiffer(palette(0), palette(1)),
        "palette undo does not settle unrelated held controls");
    Settings numeric = palette(0);
    numeric.set("warp", 1.5);
    check(SceneHistory.controlsDiffer(palette(0), numeric), "numeric undo resolves held controls");
    Settings mappings = EffectModulationChecks.settings();
    check(
        !SceneHistory.controlsDiffer(mappings, mappings.copy()),
        "copied key arrays retain logical mapping equality");
    check(
        !SceneHistory.requiresRestart(palette(0), palette(1)),
        "palette undo preserves persistent state");
    Settings changed = palette(0);
    changed.seed++;
    check(SceneHistory.requiresRestart(palette(0), changed), "seed restore requires restart");
    changed = palette(0);
    changed.grid++;
    check(SceneHistory.requiresRestart(palette(0), changed), "grid restore requires restart");
    changed = palette(0);
    changed.particles++;
    check(
        SceneHistory.requiresRestart(palette(0), changed),
        "particle layout restore requires restart");
    changed = palette(0);
    changed.historyLayers++;
    check(
        SceneHistory.requiresRestart(palette(0), changed),
        "history layout restore requires restart");
    changed = palette(0);
    changed.recipe = Settings.Recipe.CUSTOM;
    check(
        SceneHistory.requiresRestart(palette(0), changed),
        "recipe restore follows recipe restart policy");
    Settings a = palette(0), b = palette(1);
    try (var change = history.begin()) {
      change.remember(a, true);
      change.commit(b);
    }
    check(
        peek(history, b).restart(),
        "preset/effect activation retains its explicit restart-on-undo policy");
    var reset =
        new EffectControl(
            "resetValue",
            "Reset value",
            Formula.Type.FLOAT,
            "",
            List.of(1.),
            0,
            2,
            .1,
            EffectControl.Scale.LINEAR,
            EffectControl.Update.RESET);
    a.installEffectControls(List.of(reset), Map.of());
    b = a.copy();
    b.setEffectValue(reset.id(), List.of(2.));
    check(
        SceneHistory.requiresRestart(a, b),
        "reset-required custom value restores restart simulation");
    check(
        SceneHistory.requiresRestart(a, palette(0)),
        "removing a reset-required custom value restarts simulation");
    b = a.copy();
    b.effectControls =
        List.of(
            new EffectControl(
                reset.id(),
                "Renamed",
                reset.type(),
                reset.units(),
                reset.initial(),
                reset.minimum(),
                reset.maximum(),
                reset.step(),
                reset.scale(),
                reset.update()));
    check(
        !SceneHistory.requiresRestart(a, b),
        "cosmetic reset-control labels do not restart simulation");
    check(
        CompletableFuture.supplyAsync(
                () -> {
                  try {
                    history.size();
                    return false;
                  } catch (IllegalStateException expected) {
                    return true;
                  }
                })
            .get(5, TimeUnit.SECONDS),
        "history rejects worker-thread mutation and inspection");
    rejects(() -> new SceneHistory(0), "zero history capacity");
    rejects(() -> new SceneHistory(65), "unbounded history capacity");
    Settings modulated = EffectModulationChecks.settings();
    ControlSignals signals = new ControlSignals();
    signals.tick(modulated, Playback.DT);
    var before = signals.snapshot(modulated);
    modulated.setEffectValue("amplitude", List.of(1.5));
    var prepared = signals.prepareSnap(modulated, Playback.DT);
    check(
        signals.snapshot(modulated).equals(before),
        "preparing held controls leaves live smoothing and clocks intact");
    signals.restore(prepared, modulated);
    check(
        signals.snapshot(modulated).equals(prepared)
            && before.motionClock() == prepared.motionClock()
            && before.colorClock() == prepared.colorClock(),
        "accepted prepared values preserve phase clocks");
    ReplayLog log = ReplayLog.begin(modulated, 96, 64);
    var event = log.prepareControl(2, modulated);
    check(
        log.events.isEmpty() && log.endTick == 0,
        "preparing a control event does not mutate recording");
    modulated.set("warp", .25);
    event.commit();
    check(
        log.events.size() == 1
            && log.endTick == 2
            && log.events
                    .getFirst()
                    .preset()
                    .getAsJsonObject("settings")
                    .getAsJsonObject("values")
                    .get("warp")
                    .getAsDouble()
                != .25,
        "accepted recording owns its prepared configuration");
    rejects(event::commit, "duplicate prepared event commit");
    var recorded = log.events.getFirst();
    while (log.events.size() < 200000) log.events.add(recorded);
    rejects(
        () -> log.prepareControl(3, palette(0)),
        "full recording must reject before renderer commit");
    check(
        log.events.size() == 200000 && log.endTick == 2,
        "recording-capacity failure preserves the take");
    return count;
  }
}
