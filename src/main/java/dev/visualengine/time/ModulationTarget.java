package dev.visualengine.time;

import dev.visualengine.authoring.EffectControl;
import dev.visualengine.model.Settings;
import java.util.*;
import java.util.regex.Pattern;

/** A built-in parameter or one explicitly addressed component of an effect control. */
public final class ModulationTarget {
  private static final Pattern EFFECT =
      Pattern.compile("effect:([A-Za-z][A-Za-z0-9_]{0,47})(?:\\.([xyzw]))?");
  private static final Set<String> SMOOTHED = Set.of("scale", "warp", "detail", "height", "flow");
  private final String key, control, label, units;
  private final int component;
  private final double minimum, maximum;
  private final boolean smoothed;

  private ModulationTarget(
      String key,
      String control,
      int component,
      String label,
      String units,
      double minimum,
      double maximum,
      boolean smoothed) {
    this.key = key;
    this.control = control;
    this.component = component;
    this.label = label;
    this.units = units;
    this.minimum = minimum;
    this.maximum = maximum;
    this.smoothed = smoothed;
  }

  public String key() {
    return key;
  }

  public String label() {
    return label;
  }

  public String units() {
    return units;
  }

  public double minimum() {
    return minimum;
  }

  public double maximum() {
    return maximum;
  }

  public boolean smoothed() {
    return smoothed;
  }

  public boolean effect() {
    return component >= 0;
  }

  @Override
  public String toString() {
    return label;
  }

  public static ModulationTarget resolve(List<EffectControl> controls, String key) {
    if (key == null || key.length() > 64)
      throw new IllegalArgumentException("Invalid modulation destination");
    for (var p : Settings.PARAMETERS)
      if (p.id().equals(key))
        return new ModulationTarget(
            key,
            key,
            -1,
            p.label() + " · built-in",
            "",
            p.minimum(),
            p.maximum(),
            SMOOTHED.contains(key));
    var match = EFFECT.matcher(key);
    if (!match.matches())
      throw new IllegalArgumentException("Unknown modulation destination: " + key);
    var c =
        controls.stream()
            .filter(x -> x.id().equals(match.group(1)))
            .findFirst()
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "Unknown effect modulation control: " + match.group(1)));
    c.validate();
    if (c.update() == EffectControl.Update.RESET)
      throw new IllegalArgumentException("Reset-required controls cannot be modulated: " + c.id());
    String axis = match.group(2);
    if (c.type().width == 1 && axis != null || c.type().width > 1 && axis == null)
      throw new IllegalArgumentException(
          "Use a scalar control or an explicit vector component: " + key);
    int component = axis == null ? 0 : "xyzw".indexOf(axis);
    if (component >= c.type().width)
      throw new IllegalArgumentException("Missing vector component: " + key);
    return new ModulationTarget(
        key,
        c.id(),
        component,
        "Effect · " + c.label() + (axis == null ? "" : " " + axis.toUpperCase(Locale.ROOT)),
        c.units(),
        c.minimum(),
        c.maximum(),
        c.update() == EffectControl.Update.SMOOTHED);
  }

  public static List<ModulationTarget> available(Settings settings) {
    List<ModulationTarget> targets = new ArrayList<>();
    for (var p : Settings.PARAMETERS) targets.add(resolve(settings.effectControls, p.id()));
    for (var c : settings.effectControls) {
      if (c.update() == EffectControl.Update.RESET) continue;
      for (int i = 0; i < c.type().width; i++)
        targets.add(
            resolve(
                settings.effectControls,
                "effect:" + c.id() + (c.type().width == 1 ? "" : "." + "xyzw".charAt(i))));
    }
    return List.copyOf(targets);
  }

  public double read(Settings settings) {
    return effect() ? settings.effectValues.get(control).get(component) : settings.get(control);
  }

  public void write(Settings settings, double value) {
    if (!Double.isFinite(value))
      throw new IllegalArgumentException("Nonfinite modulated value: " + key);
    double bounded = Math.clamp(value, minimum, maximum);
    if (!effect()) settings.set(control, bounded);
    else {
      List<Double> values = new ArrayList<>(settings.effectValues.get(control));
      values.set(component, bounded);
      settings.setEffectValue(control, values);
    }
  }
}
