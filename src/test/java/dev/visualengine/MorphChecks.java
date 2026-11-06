package dev.visualengine;

import dev.visualengine.authoring.*;
import dev.visualengine.model.*;
import dev.visualengine.presets.PresetIO;
import dev.visualengine.time.*;
import java.nio.file.*;
import java.util.*;

public final class MorphChecks {
  private static int count;

  private static void check(boolean condition, String label) {
    count++;
    if (!condition) throw new AssertionError(label);
  }

  private static void rejects(Runnable action, String label) {
    try {
      action.run();
    } catch (IllegalArgumentException e) {
      count++;
      return;
    }
    throw new AssertionError("Accepted " + label);
  }

  static int run() throws Exception {
    Settings a = new Settings(), b = a.copy();
    for (var p : Settings.PARAMETERS) {
      a.set(p.id(), p.minimum());
      b.set(p.id(), p.maximum());
    }
    Settings live = a.copy();
    live.paused = true;
    live.debug = 7;
    live.tilePreview = true;
    live.speed = 2;
    live.displayScale = .5;
    live.locks.add("warp");
    live.motionClock = 7;
    live.colorClock = 11;
    Settings half = PresetMorph.blend(a, b, live, .5);
    Settings midpoint = half, environment = live;
    check(
        Settings.PARAMETERS.stream()
            .allMatch(p -> midpoint.get(p.id()) == (p.minimum() + p.maximum()) / 2),
        "all built-in controls interpolate at midpoint");
    check(
        half.paused
            && half.debug == 7
            && half.tilePreview
            && half.speed == 2
            && half.displayScale == .5
            && half.locks.contains("warp")
            && half.motionClock == 7
            && half.colorClock == 11,
        "morph retains live transport, display, locks and phases");
    check(
        PresetMorph.blend(a, b, live, 0).values.equals(a.values)
            && PresetMorph.blend(a, b, live, 1).values.equals(b.values),
        "exact numeric endpoints");
    for (double t : new double[] {-1, 1.01, Double.NaN, Double.POSITIVE_INFINITY})
      rejects(() -> PresetMorph.blend(a, b, environment, t), "invalid morph amount");
    List<java.util.function.Consumer<Settings>> incompatible =
        List.of(
            s -> s.seed++,
            s -> s.palette = 1,
            s -> s.recipe = Settings.Recipe.CUSTOM,
            s -> s.grid = 128,
            s -> s.particles = 0,
            s -> s.historyLayers = 0,
            s -> s.feedbackEnabled = true,
            s -> s.pigmentEnabled = true,
            s -> s.reactionEnabled = true,
            s -> s.particlesEnabled = true,
            s -> s.tileMode = true,
            s -> s.loopSeconds = 8,
            s -> s.expression = "sin(p.x)",
            s -> s.graphEnabled = true,
            s -> s.graph.output = "another",
            s ->
                s.modulations.add(
                    new Modulation(
                        "pulse",
                        "warp",
                        Modulation.Source.SINE,
                        Modulation.Mode.ADD,
                        3,
                        0,
                        1,
                        0,
                        new double[] {0, 0, 1, 1})));
    for (var change : incompatible) {
      Settings other = b.copy();
      change.accept(other);
      rejects(() -> PresetMorph.blend(a, other, environment, .5), "discrete morph change");
      rejects(() -> PresetMorph.blend(a, b, other, .5), "incompatible live scene");
    }
    var scalar =
        new EffectControl(
            "gain",
            "Gain",
            Formula.Type.FLOAT,
            "",
            List.of(1.),
            .1,
            100,
            .1,
            EffectControl.Scale.LOGARITHMIC,
            EffectControl.Update.SMOOTHED);
    var vector =
        new EffectControl(
            "offset",
            "Offset",
            Formula.Type.VEC2,
            "",
            List.of(0., 0.),
            -10,
            10,
            .1,
            EffectControl.Scale.LINEAR,
            EffectControl.Update.UNIFORM);
    var reset =
        new EffectControl(
            "resetValue",
            "Reset",
            Formula.Type.FLOAT,
            "",
            List.of(0.),
            0,
            10,
            .1,
            EffectControl.Scale.LINEAR,
            EffectControl.Update.RESET);
    a.installEffectControls(
        List.of(scalar, vector, reset), Map.of("gain", List.of(.1), "offset", List.of(-4., 2.)));
    b.installEffectControls(
        List.of(scalar, vector, reset), Map.of("gain", List.of(10.), "offset", List.of(4., 6.)));
    live = a.copy();
    half = PresetMorph.blend(a, b, live, .5);
    check(
        Math.abs(half.effectValues.get("gain").getFirst() - 1) < 1e-12
            && half.effectValues.get("offset").equals(List.of(0., 4.)),
        "log scalar and linear vector morph");
    check(
        PresetMorph.blend(a, b, live, 0).effectValues.equals(a.effectValues)
            && PresetMorph.blend(a, b, live, 1).effectValues.equals(b.effectValues),
        "exact custom endpoints");
    Settings changed = b.copy();
    changed.setEffectValue("resetValue", List.of(1.));
    Settings base = live;
    rejects(() -> PresetMorph.blend(a, changed, base, .5), "reset control morph");
    Settings schema = b.copy();
    schema.installEffectControls(List.of(scalar), Map.of());
    rejects(() -> PresetMorph.blend(a, schema, base, .5), "control schema morph");
    Settings roundtrip = PresetIO.parse(PresetIO.JSON.toJson(PresetIO.document(half, 123)));
    check(
        PresetMorph.differences(half, roundtrip).isEmpty()
            && roundtrip.values.equals(half.values)
            && roundtrip.effectValues.equals(half.effectValues),
        "morph results use ordinary preset schema");
    PresetMorph.Session session = new PresetMorph.Session();
    check(!session.view(live).ready(), "empty morph session disabled");
    long old = session.begin(PresetMorph.Slot.A);
    session.capture(PresetMorph.Slot.A, a);
    check(!session.complete(PresetMorph.Slot.A, old, b), "stale endpoint load ignored");
    session.capture(PresetMorph.Slot.B, b);
    a.set("scale", 3);
    b.set("scale", 4);
    check(
        session.view(live).ready()
            && session.blend(live, 0).get("scale") == .2
            && session.blend(live, 1).get("scale") == 12,
        "endpoint captures own immutable copies");
    Settings edited = session.blend(live, .3);
    edited.seed++;
    check(!session.view(edited).ready(), "live structural edits invalidate captured pair");
    ControlSignals signals = new ControlSignals();
    Settings source = new Settings(), target = source.copy();
    target.set("motion", 1.5);
    target.set("colorRate", .8);
    for (int tick = 1; tick <= 100; tick++) signals.tick(source, tick * Playback.DT);
    var before = signals.snapshot(source);
    Settings blend = PresetMorph.blend(source, target, source, .5);
    signals.snap(blend);
    var after = signals.snapshot(blend);
    check(
        before.motionClock() == after.motionClock() && before.colorClock() == after.colorClock(),
        "paused morph snaps values without resetting circular phases");
    Settings next = signals.tick(blend, 101 * Playback.DT);
    check(
        Math.abs(next.motionClock - before.motionClock() - blend.get("motion") * Playback.DT)
                < 1e-12
            && Math.abs(
                    next.colorClock - before.colorClock() - blend.get("colorRate") * Playback.DT)
                < 1e-12,
        "morph integrates new rates from existing phase");
    Path reports = Path.of("build/reports");
    Files.createDirectories(reports);
    source.grid = 65;
    source.particles = 257;
    source.historyLayers = 3;
    target = source.copy();
    target.set("scale", 4.2);
    target.set("warp", 1.4);
    target.set("motion", .8);
    target.set("colorRate", .3);
    target.set("exposure", 2.2);
    PresetIO.save(reports.resolve("morph-a.json"), source, 0);
    PresetIO.save(reports.resolve("morph-b.json"), target, 0);
    PresetIO.save(
        reports.resolve("morph-half.json"), PresetMorph.blend(source, target, source, .5), 0);
    return count;
  }
}
