package dev.visualengine.time;

import dev.visualengine.model.Settings;
import java.util.*;

/**
 * Tick-evaluated modulation and smoothing; phase integrates rate rather than multiplying changed
 * rates by elapsed time.
 */
public final class ControlSignals {
  private final Map<String, Double> smooth = new LinkedHashMap<>();
  private double motionClock, colorClock;
  private final Map<String, List<Double>> effectSmooth = new LinkedHashMap<>();
  private static final Set<String> SMOOTHED = Set.of("scale", "warp", "detail", "height", "flow");

  public record State(
      double motionClock,
      double colorClock,
      Map<String, Double> smooth,
      Map<String, List<Double>> effectSmooth) {
    public State {
      smooth = smooth == null ? null : Map.copyOf(smooth);
      if (effectSmooth != null) {
        Map<String, List<Double>> copy = new LinkedHashMap<>();
        effectSmooth.forEach((id, v) -> copy.put(id, List.copyOf(v)));
        effectSmooth = Map.copyOf(copy);
      }
    }

    public void validate(Settings settings) {
      if (!Double.isFinite(motionClock)
          || !Double.isFinite(colorClock)
          || Math.abs(motionClock) > 1e12
          || Math.abs(colorClock) > 1e12
          || smooth == null
          || effectSmooth == null) throw new IllegalArgumentException("Invalid checkpoint clocks");
      for (var entry : smooth.entrySet()) {
        var p =
            Settings.PARAMETERS.stream()
                .filter(x -> x.id().equals(entry.getKey()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown checkpoint signal"));
        p.validate(entry.getValue());
      }
      effectSmooth.forEach((id, value) -> settings.effectControl(id).validateValue(value));
    }
  }

  public State snapshot(Settings settings) {
    Map<String, List<Double>> values = new LinkedHashMap<>();
    effectSmooth.forEach(
        (id, v) -> {
          if (settings.effectValues.containsKey(id)) {
            var c = settings.effectControl(id);
            if (v.size() == c.type().width)
              values.put(id, v.stream().map(x -> Math.clamp(x, c.minimum(), c.maximum())).toList());
          }
        });
    return new State(motionClock, colorClock, smooth, values);
  }

  public void restore(State state, Settings settings) {
    state.validate(settings);
    smooth.clear();
    smooth.putAll(state.smooth());
    effectSmooth.clear();
    effectSmooth.putAll(state.effectSmooth());
    motionClock = state.motionClock();
    colorClock = state.colorClock();
  }

  public void reset() {
    effectSmooth.clear();
    smooth.clear();
    motionClock = colorClock = 0;
  }

  public void snap(Settings target) {
    effectSmooth.clear();
    smooth.clear();
    smooth.putAll(target.values);
    effectSmooth.putAll(target.effectValues);
  }

  /** Resolve an explicit paused edit at held simulation time without advancing any clock. */
  public void snap(Settings target, double time) {
    snap(resolve(target, time));
  }

  public Settings tick(Settings target, double time) {
    Settings result = resolve(target, time);
    for (var parameter : Settings.PARAMETERS) {
      double value = result.get(parameter.id()), old = smooth.getOrDefault(parameter.id(), value);
      double next =
          SMOOTHED.contains(parameter.id())
              ? old + (value - old) * (-Math.expm1(-8 * Playback.DT))
              : value;
      smooth.put(parameter.id(), next);
      result.values.put(parameter.id(), next);
    }
    effectSmooth.keySet().retainAll(target.effectValues.keySet());
    for (var c : target.effectControls) {
      var value = result.effectValues.get(c.id());
      var old = effectSmooth.getOrDefault(c.id(), value);
      List<Double> next = new ArrayList<>();
      for (int i = 0; i < value.size(); i++)
        next.add(
            c.update() == dev.visualengine.authoring.EffectControl.Update.SMOOTHED
                    && old.size() == value.size()
                ? Math.clamp(old.get(i), c.minimum(), c.maximum())
                    + (value.get(i) - Math.clamp(old.get(i), c.minimum(), c.maximum()))
                        * (-Math.expm1(-8 * Playback.DT))
                : value.get(i));
      effectSmooth.put(c.id(), List.copyOf(next));
      result.setEffectValue(c.id(), next);
    }
    motionClock += result.get("motion") * Playback.DT;
    colorClock += result.get("colorRate") * Playback.DT;
    result.motionClock = motionClock;
    result.colorClock = colorClock;
    return result;
  }

  private static Settings resolve(Settings target, double time) {
    Settings result = target.copy();
    for (var mapping : target.modulations) {
      var destination = ModulationTarget.resolve(target.effectControls, mapping.destination());
      double value = mapping.evaluate(time, target.seed, target.get("agitation")),
          old = destination.read(result);
      double resolved =
          switch (mapping.mode()) {
            case ADD -> old + value;
            case MULTIPLY -> old * value;
            case REPLACE -> value;
          };
      destination.write(result, resolved);
    }
    return result;
  }

  public Settings frame(Settings target) {
    Settings result = target.copy();
    smooth.forEach(
        (id, v) -> {
          if (!id.equals("exposure") && !id.equals("bloom")
              || target.modulations.stream().anyMatch(m -> m.destination().equals(id)))
            result.values.put(id, v);
        });
    effectSmooth.forEach(
        (id, value) -> {
          if (result.effectValues.containsKey(id)
              && value.size() == result.effectControl(id).type().width) {
            var c = result.effectControl(id);
            result.setEffectValue(
                id, value.stream().map(v -> Math.clamp(v, c.minimum(), c.maximum())).toList());
          }
        });
    result.motionClock = motionClock;
    result.colorClock = colorClock;
    return result;
  }
}
