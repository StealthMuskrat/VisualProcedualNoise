package dev.visualengine.model;

import dev.visualengine.authoring.EffectControl;
import dev.visualengine.presets.PresetIO;
import java.util.*;

/** Context-thread configuration history. Candidates never mutate committed entries. */
public final class SceneHistory {
  private record Entry(Settings settings, boolean restart) {}

  public record Restoration(Settings settings, boolean restart, boolean resolveHeldControls) {}

  private final Thread owner = Thread.currentThread();
  private final int capacity;
  private final Deque<Entry> entries = new ArrayDeque<>();
  private Change active;

  public SceneHistory(int capacity) {
    if (capacity < 1 || capacity > 64)
      throw new IllegalArgumentException("History capacity must be 1..64");
    this.capacity = capacity;
  }

  private void checkOwner() {
    if (Thread.currentThread() != owner)
      throw new IllegalStateException("Scene history requires its owning thread");
  }

  public int size() {
    checkOwner();
    return entries.size();
  }

  public Change begin() {
    checkOwner();
    if (active != null) throw new IllegalStateException("A scene edit is already pending");
    return active = new Change();
  }

  /** A completed checkpoint replaces history and invalidates any earlier pending command. */
  public void clear() {
    checkOwner();
    entries.clear();
    active = null;
  }

  public static boolean requiresRestart(Settings from, Settings to) {
    if (from.seed != to.seed
        || from.recipe != to.recipe
        || from.grid != to.grid
        || from.particles != to.particles
        || from.historyLayers != to.historyLayers) return true;
    Map<String, EffectControl> resetControls = new LinkedHashMap<>();
    for (var controls : List.of(from.effectControls, to.effectControls))
      for (var control : controls)
        if (control.update() == EffectControl.Update.RESET)
          resetControls.put(control.id(), control);
    for (String id : resetControls.keySet()) {
      var before = from.effectControls.stream().filter(c -> c.id().equals(id)).findFirst();
      var after = to.effectControls.stream().filter(c -> c.id().equals(id)).findFirst();
      if (!before.map(EffectControl::type).equals(after.map(EffectControl::type))
          || !Objects.equals(from.effectValues.get(id), to.effectValues.get(id))) return true;
    }
    return false;
  }

  private static boolean sameConfiguration(Settings a, Settings b) {
    if (!a.locks.equals(b.locks)) return false;
    var left = PresetIO.JSON.toJsonTree(a).getAsJsonObject();
    var right = PresetIO.JSON.toJsonTree(b).getAsJsonObject();
    left.remove("locks");
    right.remove("locks");
    return left.equals(right);
  }

  public static boolean controlsDiffer(Settings from, Settings to) {
    return !from.values.equals(to.values)
        || !from.effectValues.equals(to.effectValues)
        || !from.effectControls.equals(to.effectControls)
        || !PresetIO.JSON
            .toJsonTree(from.modulations)
            .equals(PresetIO.JSON.toJsonTree(to.modulations));
  }

  public final class Change implements AutoCloseable {
    private Entry pending, restoring;
    private boolean selected, finished;

    private Change() {}

    private void requireActive() {
      checkOwner();
      if (finished || active != this)
        throw new IllegalStateException("Scene edit is no longer pending");
    }

    public void remember(Settings before) {
      remember(before, false);
    }

    public void remember(Settings before, boolean restartOnUndo) {
      requireActive();
      if (selected) throw new IllegalStateException("Scene history action already selected");
      pending = new Entry(before.copy(), restartOnUndo);
      selected = true;
    }

    public Optional<Restoration> undo(Settings live) {
      requireActive();
      if (selected) throw new IllegalStateException("Scene history action already selected");
      selected = true;
      restoring = entries.peekFirst();
      return restoring == null
          ? Optional.empty()
          : Optional.of(
              new Restoration(
                  restoring.settings().copy(),
                  restoring.restart() || requiresRestart(live, restoring.settings()),
                  controlsDiffer(live, restoring.settings())));
    }

    public void commit(Settings accepted) {
      requireActive();
      if (pending != null && !sameConfiguration(pending.settings(), accepted)) {
        entries.addFirst(pending);
        while (entries.size() > capacity) entries.removeLast();
      } else if (restoring != null) entries.removeFirst();
      finished = true;
      active = null;
    }

    @Override
    public void close() {
      checkOwner();
      if (!finished && active == this) active = null;
      finished = true;
    }
  }
}
