package dev.visualengine.authoring;

import java.util.*;

/** Fresh portable effect definitions; the same manifests can be exported and edited by users. */
public final class DirectionalEffects {
  public enum Style {
    FIBERS("Gabor fibers"),
    WEAVE("Gabor weave");
    private final String label;

    Style(String label) {
      this.label = label;
    }

    @Override
    public String toString() {
      return label;
    }
  }

  private DirectionalEffects() {}

  public static EffectPackage create(Style style) {
    Objects.requireNonNull(style);
    EffectPackage effect = new EffectPackage();
    effect.id = "builtin.gabor-" + style.name().toLowerCase(Locale.ROOT);
    effect.author = "Procedural Visual Engine";
    effect.controls =
        new ArrayList<>(
            List.of(
                control(
                    "orientation",
                    "Carrier orientation",
                    "radians",
                    .65,
                    -Math.PI,
                    Math.PI,
                    .01,
                    false,
                    EffectControl.Update.UNIFORM),
                control(
                    "carrier",
                    "Principal frequency",
                    "cycles/domain",
                    3.2,
                    .25,
                    8,
                    .01,
                    true,
                    EffectControl.Update.UNIFORM),
                control(
                    "bandwidth",
                    "Bandwidth / packet size",
                    "1/domain",
                    1,
                    .25,
                    4,
                    .01,
                    true,
                    EffectControl.Update.SMOOTHED),
                control(
                    "bend",
                    "Fiber bending",
                    "domain",
                    .18,
                    0,
                    1,
                    .01,
                    false,
                    EffectControl.Update.SMOOTHED),
                control(
                    "travel",
                    "Orbit displacement",
                    "domain",
                    .25,
                    0,
                    1,
                    .01,
                    false,
                    EffectControl.Update.SMOOTHED),
                control(
                    "amplitude",
                    "Field amplitude",
                    "",
                    .9,
                    .1,
                    2,
                    .01,
                    true,
                    EffectControl.Update.UNIFORM)));
    String domain =
        "p + vec2(cos(phase),sin(phase))*travel +"
            + " vec2(noise3d(vec3(p*.55,sin(phase))),noise3d(vec3(p*.55+vec2(17.1,-8.2),cos(phase))))*bend";
    String first = "gabor(" + domain + ",orientation,carrier,bandwidth)";
    effect.expression = "amplitude * " + first;
    if (style == Style.WEAVE) {
      effect.controls.add(
          control(
              "weaveMix",
              "Crossing fibers",
              "",
              .45,
              0,
              1,
              .01,
              false,
              EffectControl.Update.UNIFORM));
      effect.expression =
          "amplitude * mix("
              + first
              + ",gabor("
              + domain
              + ",orientation+pi*.5,carrier*.83,bandwidth),weaveMix)";
    }
    effect.parameters.put("height", .18);
    effect.parameters.put("motion", .15);
    effect.parameters.put("colorRate", .025);
    effect.parameters.put("bloom", .08);
    effect.parameters.put("role", 0.);
    effect.validate();
    return effect;
  }

  private static EffectControl control(
      String id,
      String label,
      String units,
      double initial,
      double min,
      double max,
      double step,
      boolean logarithmic,
      EffectControl.Update update) {
    return new EffectControl(
        id,
        label,
        Formula.Type.FLOAT,
        units,
        List.of(initial),
        min,
        max,
        step,
        logarithmic ? EffectControl.Scale.LOGARITHMIC : EffectControl.Scale.LINEAR,
        update);
  }
}
