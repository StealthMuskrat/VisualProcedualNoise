package dev.visualengine.model;

import dev.visualengine.authoring.EffectControl;
import dev.visualengine.presets.PresetIO;
import java.util.*;

/** Numeric editing between compatible configurations; never interpolates GPU buffers or clocks. */
public final class PresetMorph {
  public enum Slot {
    A,
    B
  }

  public record View(String a, String b, List<String> reasons) {
    public View {
      reasons = List.copyOf(reasons);
    }

    public boolean ready() {
      return reasons.isEmpty();
    }
  }

  /** Owned by the engine thread. Generations prevent an old file read replacing a newer capture. */
  public static final class Session {
    private final Settings[] endpoints = new Settings[2];
    private final long[] generations = new long[2];

    public long begin(Slot slot) {
      return ++generations[slot.ordinal()];
    }

    public boolean complete(Slot slot, long generation, Settings settings) {
      if (generation != generations[slot.ordinal()]) return false;
      settings.validate();
      endpoints[slot.ordinal()] = settings.copy();
      return true;
    }

    public void capture(Slot slot, Settings settings) {
      complete(slot, begin(slot), settings);
    }

    public View view(Settings live) {
      List<String> reasons = new ArrayList<>();
      Settings a = endpoints[0], b = endpoints[1];
      if (a == null) reasons.add("Capture or load endpoint A.");
      if (b == null) reasons.add("Capture or load endpoint B.");
      if (a != null && b != null) {
        differences(a, b).forEach(x -> reasons.add("A / B: " + x));
        differences(a, live).forEach(x -> reasons.add("A / live: " + x));
      }
      return new View(describe(a), describe(b), reasons);
    }

    public Settings blend(Settings live, double amount) {
      View view = view(live);
      if (!view.ready()) throw new IllegalArgumentException(String.join(" ", view.reasons()));
      return PresetMorph.blend(endpoints[0], endpoints[1], live, amount);
    }

    private static String describe(Settings s) {
      return s == null ? "Empty" : s.name + " | " + s.recipe + " | seed " + s.seed;
    }
  }

  private PresetMorph() {}

  /** All non-blended creative choices must agree. Display and transport controls belong to live. */
  public static List<String> differences(Settings a, Settings b) {
    a.validate();
    b.validate();
    List<String> result = new ArrayList<>();
    if (a.recipe != b.recipe) result.add("recipe differs");
    if (a.seed != b.seed) result.add("root seed differs");
    if (a.palette != b.palette) result.add("palette differs");
    if (a.grid != b.grid || a.particles != b.particles || a.historyLayers != b.historyLayers)
      result.add("simulation layout differs (grid, particles or history)");
    if (a.feedbackEnabled != b.feedbackEnabled
        || a.pigmentEnabled != b.pigmentEnabled
        || a.reactionEnabled != b.reactionEnabled
        || a.particlesEnabled != b.particlesEnabled)
      result.add("enabled simulation resources differ");
    if (a.tileMode != b.tileMode || a.loopSeconds != b.loopSeconds)
      result.add("tile or loop topology differs");
    if (a.graphEnabled != b.graphEnabled
        || !a.expression.equals(b.expression)
        || !PresetIO.JSON.toJsonTree(a.graph).equals(PresetIO.JSON.toJsonTree(b.graph)))
      result.add("effect expression or graph differs");
    if (!a.effectControls.equals(b.effectControls)) result.add("effect control schema differs");
    else
      for (var c : a.effectControls)
        if (c.update() == EffectControl.Update.RESET
            && !a.effectValues.get(c.id()).equals(b.effectValues.get(c.id())))
          result.add("reset-required control differs: " + c.id());
    if (!PresetIO.JSON.toJsonTree(a.modulations).equals(PresetIO.JSON.toJsonTree(b.modulations)))
      result.add("modulation mappings differ");
    return List.copyOf(result);
  }

  public static Settings blend(Settings a, Settings b, Settings live, double amount) {
    if (!Double.isFinite(amount) || amount < 0 || amount > 1)
      throw new IllegalArgumentException("Morph amount must be between 0 and 1");
    List<String> reasons = new ArrayList<>(differences(a, b));
    differences(a, live).forEach(x -> reasons.add("live " + x));
    if (!reasons.isEmpty())
      throw new IllegalArgumentException("Incompatible morph: " + String.join("; ", reasons));
    Settings result = live.copy();
    for (var p : Settings.PARAMETERS) result.set(p.id(), mix(a.get(p.id()), b.get(p.id()), amount));
    for (var c : a.effectControls) {
      List<Double> left = a.effectValues.get(c.id()), right = b.effectValues.get(c.id());
      List<Double> values = new ArrayList<>();
      for (int i = 0; i < left.size(); i++) {
        double value =
            amount == 0 || left.get(i).equals(right.get(i))
                ? left.get(i)
                : amount == 1
                    ? right.get(i)
                    : c.scale() == EffectControl.Scale.LOGARITHMIC
                        ? Math.exp(mix(Math.log(left.get(i)), Math.log(right.get(i)), amount))
                        : mix(left.get(i), right.get(i), amount);
        values.add(Math.clamp(value, c.minimum(), c.maximum()));
      }
      result.setEffectValue(c.id(), values);
    }
    result.validate();
    return result;
  }

  private static double mix(double a, double b, double t) {
    return t == 0
        ? a
        : t == 1 ? b : Math.clamp(a * (1 - t) + b * t, Math.min(a, b), Math.max(a, b));
  }
}
