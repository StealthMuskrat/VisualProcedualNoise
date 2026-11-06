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

  public Settings tick(Settings target, double time) {
    Settings result = target.copy();
    for (var mapping : target.modulations) {
      double value = mapping.evaluate(time, target.seed, target.get("agitation")),
          old = result.get(mapping.destination());
      double resolved =
          switch (mapping.mode()) {
            case ADD -> old + value;
            case MULTIPLY -> old * value;
            case REPLACE -> value;
          };
      var parameter =
          Settings.PARAMETERS.stream()
              .filter(p -> p.id().equals(mapping.destination()))
              .findFirst()
              .orElseThrow();
      result.set(
          mapping.destination(), Math.clamp(resolved, parameter.minimum(), parameter.maximum()));
    }
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
      var value = target.effectValues.get(c.id());
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

  public Settings frame(Settings target) {
    Settings result = target.copy();
    smooth.forEach((id, v) -> {
      if (!id.equals("exposure") && !id.equals("bloom")) result.values.put(id, v);
    });
    effectSmooth.forEach((id, value) -> {
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
