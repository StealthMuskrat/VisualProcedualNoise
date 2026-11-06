package dev.visualengine;

import dev.visualengine.authoring.*;
import dev.visualengine.model.Settings;
import dev.visualengine.presets.PresetIO;
import dev.visualengine.time.ControlSignals;
import java.util.*;

public final class AuthoringChecks {
  private static int count;

  private static void check(boolean ok, String message) {
    count++;
    if (!ok) throw new AssertionError(message);
  }

  private static void rejects(Runnable action, String message) {
    try {
      action.run();
    } catch (IllegalArgumentException e) {
      count++;
      return;
    }
    throw new AssertionError(message);
  }

  public static EffectControl scalar() {
    return new EffectControl(
        "amplitude",
        "Amplitude",
        Formula.Type.FLOAT,
        "domain",
        List.of(1.),
        .1,
        3,
        .01,
        EffectControl.Scale.LOGARITHMIC,
        EffectControl.Update.SMOOTHED);
  }

  public static EffectControl vector() {
    return new EffectControl(
        "tint",
        "Tint",
        Formula.Type.VEC3,
        "linear",
        List.of(.2, .4, .6),
        0,
        1,
        .01,
        EffectControl.Scale.LINEAR,
        EffectControl.Update.UNIFORM);
  }

  public static int run() throws Exception {
    count = 0;
    var controls = List.of(scalar(), vector());
    Settings s = new Settings();
    s.installEffectControls(controls, Map.of());
    s.recipe = Settings.Recipe.CUSTOM;
    s.expression = "amplitude*sin(p.x)+dot(tint,vec3(.2,.3,.5))";
    var f = EffectControl.compile(s.expression, controls);
    check(
        f.expression().contains("user_amplitude") && f.expression().contains("user_tint"),
        "custom symbols are generated uniforms");
    check(f.root().type() == Formula.Type.FLOAT, "vector controls type check");
    rejects(
        () -> EffectControl.compile("tint + vec2(1)", controls),
        "vector mismatch must fail before linking");
    rejects(
        () -> EffectControl.validateSchema(List.of(scalar(), scalar())), "duplicate descriptor");
    rejects(
        () ->
            new EffectControl(
                    "p",
                    "P",
                    Formula.Type.FLOAT,
                    "",
                    List.of(1.),
                    0,
                    2,
                    .1,
                    EffectControl.Scale.LINEAR,
                    EffectControl.Update.UNIFORM)
                .validate(),
        "reserved symbol");
    rejects(() -> s.setEffectValue("tint", List.of(1., 2., 3.)), "vector bounds");
    rejects(() -> s.setEffectValue("amplitude", List.of(Double.NaN)), "nonfinite value");
    rejects(
        () -> s.installEffectControls(controls, Map.of("missing", List.of(1.))),
        "unknown resolved control");
    s.setEffectValue("amplitude", List.of(2.));
    Settings clone = s.copy();
    s.setEffectValue("amplitude", List.of(1.));
    check(
        clone.effectValues.get("amplitude").equals(List.of(2.)),
        "snapshots own their resolved values");
    Settings loaded = PresetIO.parse(PresetIO.JSON.toJson(PresetIO.document(clone, 42)));
    check(
        loaded.effectControls.equals(controls) && loaded.effectValues.equals(clone.effectValues),
        "custom schema and values survive preset roundtrip");
    ControlSignals signals = new ControlSignals();
    signals.tick(s, 0);
    s.setEffectValue("amplitude", List.of(3.));
    var smooth = signals.tick(s, 1. / 120);
    check(
        smooth.effectValues.get("amplitude").getFirst() > 1
            && smooth.effectValues.get("amplitude").getFirst() < 3,
        "custom smoothing is tick based");
    EffectPackage pack = new EffectPackage();
    pack.controls = controls;
    pack.controlValues = new LinkedHashMap<>(clone.effectValues);
    pack.expression = clone.expression;
    pack.pigmentEnabled = true;
    pack.definitionHash = pack.contentHash();
    pack.validate();
    EffectPackage decoded = PresetIO.JSON.fromJson(PresetIO.JSON.toJson(pack), EffectPackage.class);
    decoded.validate();
    Settings resolved = new Settings();
    decoded.apply(resolved);
    check(
        resolved.pigmentEnabled && resolved.effectValues.equals(clone.effectValues),
        "effect package restores custom values and state requirements");
    decoded.expression = "0";
    rejects(decoded::validate, "definition hash pins effect content");
    EffectPackage legacy = new EffectPackage();
    legacy.effectApiVersion = 1;
    legacy.validate();
    check(legacy.controls.isEmpty(), "API 1 compatibility");
    return count;
  }
}
