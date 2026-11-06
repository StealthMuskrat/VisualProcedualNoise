package dev.visualengine.time;

import dev.visualengine.model.Settings;
import java.util.*;

public record Modulation(
    String id,
    String destination,
    Source source,
    Mode mode,
    double period,
    double low,
    double high,
    double phase,
    double[] keys) {
  public enum Source {
    SINE,
    TRIANGLE,
    SMOOTH_RANDOM,
    ENVELOPE,
    KEYFRAMES,
    MACRO
  }

  public enum Mode {
    ADD,
    MULTIPLY,
    REPLACE
  }

  public void validate() {
    if (id == null
        || id.length() > 128
        || source == null
        || mode == null
        || Settings.PARAMETERS.stream().noneMatch(p -> p.id().equals(destination))
        || !Double.isFinite(period)
        || period < .1
        || period > 3600
        || !Double.isFinite(low)
        || !Double.isFinite(high)
        || Math.abs(low) > 100
        || Math.abs(high) > 100
        || !Double.isFinite(phase)
        || Math.abs(phase) > 3600) throw new IllegalArgumentException("Invalid modulation mapping");
    if (keys == null || keys.length < 4 || keys.length > 64 || keys.length % 2 != 0)
      throw new IllegalArgumentException("Keyframes require 2..32 time/value pairs");
    double previous = -1;
    for (int i = 0; i < keys.length; i += 2) {
      if (!Double.isFinite(keys[i])
          || !Double.isFinite(keys[i + 1])
          || keys[i] < 0
          || keys[i] > 1
          || keys[i] <= previous
          || keys[i + 1] < 0
          || keys[i + 1] > 1)
        throw new IllegalArgumentException(
            "Keyframe times must increase in [0,1], with values in [0,1]");
      previous = keys[i];
    }
  }

  public double evaluate(double time, long seed, double macro) {
    double x = (time + phase) / period,
        t = x - Math.floor(x),
        unit =
            switch (source) {
              case SINE -> .5 + .5 * Math.sin(2 * Math.PI * x);
              case TRIANGLE -> 1 - Math.abs(2 * t - 1);
              case SMOOTH_RANDOM -> {
                long i = (long) Math.floor(x);
                double a = random(seed, i), b = random(seed, i + 1), u = t * t * (3 - 2 * t);
                yield a + (b - a) * u;
              }
              case ENVELOPE -> {
                double p = Math.clamp(x, 0, 1);
                yield p * p * (3 - 2 * p);
              }
              case KEYFRAMES -> {
                double p = Math.clamp(x, 0, 1), value = keys[1];
                for (int i = 2; i < keys.length; i += 2) {
                  if (p <= keys[i]) {
                    double alpha = Math.clamp((p - keys[i - 2]) / (keys[i] - keys[i - 2]), 0, 1);
                    value = keys[i - 1] + (keys[i + 1] - keys[i - 1]) * alpha;
                    break;
                  }
                  value = keys[i + 1];
                }
                yield value;
              }
              case MACRO -> macro;
            };
    return low + (high - low) * unit;
  }

  private double random(long root, long index) {
    return (Seeds.derive(root, id, Long.toString(index)) >>> 11) * 0x1.0p-53;
  }

  public Modulation copy() {
    return new Modulation(id, destination, source, mode, period, low, high, phase, keys.clone());
  }
}
