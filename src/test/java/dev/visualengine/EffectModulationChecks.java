package dev.visualengine;

import dev.visualengine.authoring.*;
import dev.visualengine.model.Settings;
import dev.visualengine.presets.PresetIO;
import dev.visualengine.time.*;
import java.nio.file.*;
import java.util.*;

public final class EffectModulationChecks {
  private static int count;

  private static void check(boolean ok, String text) {
    count++;
    if (!ok) throw new AssertionError(text);
  }

  private static void near(double actual, double expected, String text) {
    check(Math.abs(actual - expected) < 1e-12, text);
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

  public static Modulation mapping(
      String id,
      String destination,
      Modulation.Source source,
      Modulation.Mode mode,
      double period,
      double low,
      double high,
      double phase) {
    return new Modulation(
        id,
        destination,
        source,
        mode,
        period,
        low,
        high,
        phase,
        new double[] {0, .2, .3, 1, .7, .4, 1, .7});
  }

  public static Settings settings() {
    Settings s = new Settings();
    s.recipe = Settings.Recipe.CUSTOM;
    s.name = "Modulated custom field";
    s.grid = 65;
    s.particles = 257;
    s.historyLayers = 3;
    s.feedbackEnabled = s.pigmentEnabled = true;
    s.installEffectControls(
        List.of(
            AuthoringChecks.scalar(),
            new EffectControl(
                "offset",
                "Domain offset",
                Formula.Type.VEC2,
                "domain",
                List.of(.2, -.1),
                -2,
                2,
                .01,
                EffectControl.Scale.LINEAR,
                EffectControl.Update.UNIFORM)),
        Map.of());
    s.expression = "amplitude * sin(p.x * frequency + offset.x) + cos(p.y * stripeRate + offset.y)";
    s.modulations.add(
        mapping(
            "amplitude-wave",
            "effect:amplitude",
            Modulation.Source.SINE,
            Modulation.Mode.REPLACE,
            4,
            .2,
            2.4,
            .4));
    s.modulations.add(
        mapping(
            "offset-x-wave",
            "effect:offset.x",
            Modulation.Source.TRIANGLE,
            Modulation.Mode.REPLACE,
            2,
            -.8,
            .8,
            .25));
    s.modulations.add(
        mapping(
            "offset-y-track",
            "effect:offset.y",
            Modulation.Source.KEYFRAMES,
            Modulation.Mode.ADD,
            .6,
            -.2,
            .4,
            0));
    s.modulations.add(
        mapping(
            "color-macro",
            "colorRate",
            Modulation.Source.MACRO,
            Modulation.Mode.REPLACE,
            8,
            .02,
            .08,
            0));
    return s;
  }

  public static Settings edited(Settings source, int stage) {
    Settings s = source.copy();
    if (stage == 1) {
      s.modulations.set(
          0,
          mapping(
              "amplitude-wave",
              "effect:amplitude",
              Modulation.Source.SINE,
              Modulation.Mode.REPLACE,
              1.5,
              .4,
              2.8,
              -.2));
      s.setEffectValue("offset", List.of(.5, -.4));
    } else {
      s.modulations.set(
          2,
          mapping(
              "offset-y-track",
              "effect:offset.y",
              Modulation.Source.SMOOTH_RANDOM,
              Modulation.Mode.ADD,
              .8,
              -.5,
              .5,
              .1));
      s.set("agitation", .8);
    }
    s.validate();
    return s;
  }

  public static ReplayLog replay() {
    Settings s = settings(), first = edited(s, 1), second = edited(first, 2);
    ReplayLog log = ReplayLog.begin(s, 96, 64);
    log.recordPointer(1, -.2, .1, .12, true);
    log.recordControl(9, first);
    log.recordPointer(12, .15, -.1, .12, true);
    log.recordControl(17, second);
    log.recordPointer(24, .15, -.1, .12, false);
    log.endTick = 32;
    return log;
  }

  public static int run() throws Exception {
    Settings s = settings();
    s.validate();
    var available = ModulationTarget.available(s);
    check(
        available.size() == Settings.PARAMETERS.size() + 3
            && available.stream()
                .map(ModulationTarget::key)
                .toList()
                .containsAll(
                    List.of("warp", "effect:amplitude", "effect:offset.x", "effect:offset.y")),
        "target list exposes scalar controls and each real vector component");
    for (String bad :
        List.of(
            "amplitude",
            "effect:amplitude.x",
            "effect:offset",
            "effect:offset.z",
            "effect:offset.w",
            "effect:offset.r",
            "effect:offset.xy",
            "effect:missing",
            "effect:offset.x.extra"))
      rejects(
          () -> ModulationTarget.resolve(s.effectControls, bad), "Accepted invalid target " + bad);
    var reset =
        new EffectControl(
            "rebuild",
            "Rebuild",
            Formula.Type.FLOAT,
            "",
            List.of(1.),
            0,
            2,
            .1,
            EffectControl.Scale.LINEAR,
            EffectControl.Update.RESET);
    List<EffectControl> controls = new ArrayList<>(s.effectControls);
    controls.add(reset);
    Settings withReset = s.copy();
    withReset.installEffectControls(controls, Map.of());
    check(
        ModulationTarget.available(withReset).stream()
            .noneMatch(t -> t.key().equals("effect:rebuild")),
        "reset-required controls are absent from modulation choices");
    rejects(
        () ->
            mapping(
                    "reset",
                    "effect:rebuild",
                    Modulation.Source.SINE,
                    Modulation.Mode.REPLACE,
                    4,
                    0,
                    1,
                    0)
                .validate(controls),
        "Accepted reset-required target");
    rejects(
        () -> s.modulations.getFirst().validate(),
        "Accepted custom target without its descriptor context");
    Settings changed = s.copy();
    var x = ModulationTarget.resolve(changed.effectControls, "effect:offset.x");
    x.write(changed, 10);
    check(
        changed.effectValues.get("offset").equals(List.of(2., -.1))
            && s.effectValues.get("offset").equals(List.of(.2, -.1)),
        "component write clamps and preserves other components and base snapshot");
    rejects(() -> x.write(changed, Double.NaN), "Accepted nonfinite modulated component");
    Settings order = s.copy();
    order.modulations.clear();
    order.modulations.add(
        mapping(
            "replace",
            "effect:offset.x",
            Modulation.Source.SINE,
            Modulation.Mode.REPLACE,
            1,
            .5,
            .5,
            0));
    order.modulations.add(
        mapping(
            "add", "effect:offset.x", Modulation.Source.SINE, Modulation.Mode.ADD, 1, .25, .25, 0));
    order.modulations.add(
        mapping(
            "multiply",
            "effect:offset.x",
            Modulation.Source.SINE,
            Modulation.Mode.MULTIPLY,
            1,
            .5,
            .5,
            0));
    near(
        new ControlSignals().tick(order, Playback.DT).effectValues.get("offset").getFirst(),
        .375,
        "custom mappings compose in stored order");
    Collections.swap(order.modulations, 1, 2);
    near(
        new ControlSignals().tick(order, Playback.DT).effectValues.get("offset").getFirst(),
        .5,
        "reordering mappings changes combination order");

    ControlSignals signals = new ControlSignals();
    signals.snap(s);
    double time = .1, alpha = -Math.expm1(-8 * Playback.DT);
    Settings resolved = signals.tick(s, time);
    double goal = .2 + 2.2 * (.5 + .5 * Math.sin(2 * Math.PI * (time + .4) / 4));
    near(
        resolved.effectValues.get("amplitude").getFirst(),
        1 + (goal - 1) * alpha,
        "custom modulation is evaluated before descriptor smoothing");
    double fraction = (time + .25) / 2;
    near(
        resolved.effectValues.get("offset").getFirst(),
        -.8 + 1.6 * (1 - Math.abs(2 * fraction - 1)),
        "uniform vector component follows an independent triangle reference");
    near(
        resolved.effectValues.get("offset").get(1),
        -.1 - .2 + .6 * (.2 + .8 * (time / .6 / .3)),
        "keyframe component combines with its base value before clamping");
    near(
        resolved.get("colorRate"),
        .05,
        "macro uses the base agitation control for built-in and custom mappings");
    check(
        s.effectValues.get("amplitude").equals(List.of(1.))
            && s.effectValues.get("offset").equals(List.of(.2, -.1)),
        "tick evaluation leaves the restarting effect values unchanged");
    var state = signals.snapshot(s);
    check(
        signals.frame(s).effectValues.equals(resolved.effectValues)
            && signals.snapshot(s).equals(state),
        "held frames reuse resolved values without reevaluating sources or clocks");
    signals.snap(s, .25);
    near(
        signals.frame(s).effectValues.get("amplitude").getFirst(),
        .2 + 2.2 * (.5 + .5 * Math.sin(2 * Math.PI * (.25 + .4) / 4)),
        "paused edit resolves at held simulation time");
    near(
        signals.snapshot(s).motionClock(),
        state.motionClock(),
        "paused edit does not advance motion clock");
    near(
        signals.snapshot(s).colorClock(),
        state.colorClock(),
        "paused edit does not advance color clock");
    Settings finish = new Settings();
    finish.modulations.add(
        mapping(
            "exposure", "exposure", Modulation.Source.SINE, Modulation.Mode.REPLACE, 1, 2, 2, 0));
    finish.modulations.add(
        mapping("bloom", "bloom", Modulation.Source.SINE, Modulation.Mode.REPLACE, 1, .75, .75, 0));
    ControlSignals finishing = new ControlSignals();
    finishing.tick(finish, Playback.DT);
    check(
        finishing.frame(finish).get("exposure") == 2 && finishing.frame(finish).get("bloom") == .75,
        "finishing mappings reach held display frames");
    finish.modulations.clear();
    finish.set("exposure", 1.7);
    near(finishing.frame(finish).get("exposure"), 1.7, "unmapped finishing edits remain immediate");

    Settings large = new Settings();
    large.installEffectControls(
        List.of(
            new EffectControl(
                "large",
                "Large",
                Formula.Type.FLOAT,
                "",
                List.of(1000.),
                100,
                10000,
                100,
                EffectControl.Scale.LINEAR,
                EffectControl.Update.UNIFORM)),
        Map.of());
    large.modulations.add(
        mapping(
            "large",
            "effect:large",
            Modulation.Source.ENVELOPE,
            Modulation.Mode.REPLACE,
            1,
            400,
            8000,
            0));
    large.validate();
    near(
        new ControlSignals().tick(large, 1).effectValues.get("large").getFirst(),
        8000,
        "custom output bounds cover declared large control ranges");
    rejects(
        () ->
            mapping("big", "warp", Modulation.Source.SINE, Modulation.Mode.ADD, 1, 0, 1000, 0)
                .validate(),
        "Accepted wider custom limits for a built-in destination");
    var before = PresetIO.JSON.toJsonTree(s);
    rejects(
        () -> s.installEffectControls(List.of(s.effectControls.getFirst()), Map.of()),
        "Removed a referenced control");
    check(
        PresetIO.JSON.toJsonTree(s).equals(before),
        "rejected schema edit preserves descriptors, values and mappings");
    var amp = s.effectControls.getFirst();
    var resetAmp =
        new EffectControl(
            amp.id(),
            amp.label(),
            amp.type(),
            amp.units(),
            amp.initial(),
            amp.minimum(),
            amp.maximum(),
            amp.step(),
            amp.scale(),
            EffectControl.Update.RESET);
    rejects(
        () -> s.installEffectControls(List.of(resetAmp, s.effectControls.get(1)), Map.of()),
        "Changed referenced control to reset-required");
    check(
        PresetIO.JSON.toJsonTree(s).equals(before),
        "rejected policy edit preserves the working schema");
    Settings loaded = PresetIO.parse(PresetIO.JSON.toJson(PresetIO.document(s, 16)));
    check(
        PresetIO.JSON.toJsonTree(loaded).equals(before)
            && PresetIO.document(s, 0).get("schemaVersion").getAsInt() == 5,
        "schema 5 roundtrips custom destinations, phases, keys and base effect values");
    var downgraded = PresetIO.document(s, 0);
    downgraded.addProperty("schemaVersion", 4);
    rejects(
        () -> PresetIO.parse(downgraded.toString()),
        "Accepted custom mappings labelled as legacy schema");
    Settings legacy = new Settings();
    legacy.modulations.add(
        mapping("legacy", "warp", Modulation.Source.SINE, Modulation.Mode.ADD, 4, 0, 1, .3));
    for (int version = 1; version <= 4; version++) {
      var doc = PresetIO.document(legacy, 0);
      doc.addProperty("schemaVersion", version);
      check(
          PresetIO.JSON
              .toJsonTree(PresetIO.parse(doc.toString()))
              .equals(PresetIO.JSON.toJsonTree(legacy)),
          "legacy built-in modulation remains readable: schema " + version);
    }
    Settings replaced = s.copy();
    new EffectPackage().apply(replaced);
    check(
        replaced.modulations.isEmpty() && replaced.effectControls.isEmpty(),
        "loading another effect clears prior effect mappings before replacing its schema");
    double[] keys = {0, 0, 1, 1};
    var owned =
        new Modulation(
            "owned",
            "warp",
            Modulation.Source.KEYFRAMES,
            Modulation.Mode.REPLACE,
            1,
            0,
            1,
            0,
            keys);
    keys[0] = .9;
    owned.keys()[0] = .5;
    check(
        owned.keys()[0] == 0 && owned.copy().evaluate(.5, 1, .5) == .5,
        "mapping owns keyframe arrays across construction, access and copying");
    Settings bad = s.copy();
    bad.modulations.add(null);
    rejects(bad::validate, "Accepted null mapping");
    resume();
    Path root = Path.of("build/reports/effect-modulation");
    Files.createDirectories(root);
    PresetIO.save(root.resolve("preset.json"), s, 0);
    replay().save(root.resolve("replay.json"));
    return count;
  }

  private static void resume() {
    Settings s = settings();
    ControlSignals whole = new ControlSignals();
    for (int tick = 1; tick <= 16; tick++) {
      if (tick == 9) s = edited(s, 1);
      whole.tick(s, tick * Playback.DT);
    }
    ControlSignals resumed = new ControlSignals();
    resumed.restore(whole.snapshot(s), s);
    Settings a = null, b = null;
    for (int tick = 17; tick <= 120; tick++) {
      if (tick == 17) s = edited(s, 2);
      a = whole.tick(s, tick * Playback.DT);
      b = resumed.tick(s, tick * Playback.DT);
    }
    check(
        a.effectValues.equals(b.effectValues)
            && a.values.equals(b.values)
            && whole.snapshot(s).equals(resumed.snapshot(s)),
        "saved custom smoothing resumes exactly through future mapping edits and random sources");
  }
}
