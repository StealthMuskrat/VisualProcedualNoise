package dev.visualengine;

import dev.visualengine.export.*;
import dev.visualengine.model.*;
import dev.visualengine.presets.PresetIO;
import java.awt.image.BufferedImage;
import java.nio.file.*;

public final class PeriodicityChecks {
  private static int count;

  private static void check(boolean ok, String label) {
    count++;
    if (!ok) throw new AssertionError(label);
  }

  private static void rejects(Runnable action, String label) {
    try {
      action.run();
    } catch (IllegalArgumentException expected) {
      count++;
      return;
    }
    throw new AssertionError("Accepted " + label);
  }

  private static BufferedImage image() {
    return new BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB);
  }

  private static Periodicity.Frame frame(Settings target, Settings resolved) {
    return new Periodicity.Frame(
        target, resolved, 24, .2, "test device", "0".repeat(64), .2, -.1, true);
  }

  static int run() throws Exception {
    BufferedImage first = image(), second = image();
    var solid = Periodicity.edges(first);
    check(
        solid.horizontalWrap().maximum() == 0
            && solid.verticalWrap().rms() == 0
            && solid.horizontalWrap().pixelPairs() == 32
            && solid.horizontalInterior().pixelPairs() == 64,
        "edge comparisons count wrap and adjacent interior pairs");
    for (int y = 0; y < 32; y++)
      for (int x = 0; x < 32; x++) first.setRGB(x, y, (x * 4 << 16) | (y * 2 << 8));
    var gradient = Periodicity.edges(first);
    check(
        gradient.horizontalWrap().maximum() == 124
            && gradient.verticalWrap().maximum() == 62
            && gradient.horizontalInterior().maximum() == 4
            && gradient.verticalInterior().maximum() == 2,
        "horizontal and vertical seam axes retain their correct baseline steps");
    check(
        Math.abs(gradient.horizontalWrap().rms() - 124 / Math.sqrt(3)) < 1e-12,
        "edge RMS uses all encoded RGB channels");
    first = image();
    second.setRGB(3, 7, 0xff7f00);
    var difference = Periodicity.difference(first, second);
    check(
        difference.pixelPairs() == 1024
            && difference.changedPairs() == 1
            && difference.maximum() == 255
            && Math.abs(difference.meanAbsolute() - 382. / 3072) < 1e-12
            && Math.abs(difference.rms() - Math.sqrt((255. * 255 + 127. * 127) / 3072)) < 1e-12,
        "temporal metrics preserve changed pixel count, max, mean and RMS");
    rejects(
        () ->
            Periodicity.difference(image(), new BufferedImage(33, 32, BufferedImage.TYPE_INT_RGB)),
        "unequal endpoints");
    rejects(
        () -> Periodicity.edges(new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB)),
        "implicit alpha removal");
    rejects(
        () -> Periodicity.requireSize(new CaptureSize(4096, 4096)), "inspection over 4 megapixels");
    rejects(() -> Periodicity.requireSize(new CaptureSize(8192, 32)), "inspection over 4096 edge");
    check(
        Periodicity.requireSize(new CaptureSize(2048, 2048)).width() == 2048,
        "inspection maximum pixel budget accepted");
    Settings settings = new Settings();
    settings.seed = Long.MIN_VALUE;
    settings.debug = 8;
    settings.tilePreview = true;
    settings.displayScale = .5;
    var plain = Periodicity.analyze(frame(settings, settings), first, null);
    check(
        !plain.loop().sampled() && plain.loop().difference() == null,
        "unconfigured loop does not infer future state");
    Settings stateful = settings.copy();
    stateful.feedbackEnabled = true;
    check(
        !Periodicity.sampleLoop(stateful),
        "stateful scene without analytic period remains unclassified temporally");
    settings.loopSeconds = 8;
    settings.tileMode = true;
    var loop = Periodicity.analyze(frame(settings, settings), first, second);
    check(
        loop.loop().sampled()
            && loop.loop().difference().changedPairs() == 1
            && loop.loop().seconds() == 8,
        "configured loop reports measured differences instead of assuming equality");
    final BufferedImage base = first;
    rejects(
        () -> Periodicity.analyze(frame(settings, settings), base, null),
        "missing configured endpoint");
    rejects(
        () -> Periodicity.analyze(frame(stateful, stateful), base, image()),
        "unexpected inferred endpoint");
    Settings resolved = settings.copy();
    resolved.set("warp", 1.2);
    resolved.motionClock = 2;
    resolved.colorClock = 3;
    var context = frame(settings, resolved);
    var report = Periodicity.analyze(context, first, first);
    var json = Periodicity.document(context, report);
    check(
        json.get("rootSeed").getAsString().equals(Long.toString(Long.MIN_VALUE))
            && !json.getAsJsonObject("renderSettings").has("seed")
            && !json.get("controlsWithinTolerance").getAsBoolean()
            && json.getAsJsonObject("renderSettings").get("debug").getAsInt() == 0
            && !json.getAsJsonObject("renderSettings").get("tilePreview").getAsBoolean()
            && json.getAsJsonObject("renderSettings").get("displayScale").getAsDouble() == 1
            && !json.get("includesReference").getAsBoolean(),
        "inspection metadata records normalized render conditions and unfinished control changes");
    check(
        json.get("referenceMotionClock").getAsDouble() == 2
            && json.get("endpointSeconds").getAsDouble() == 8.2
            && json.getAsJsonObject("pointer").get("brush").getAsBoolean(),
        "inspection metadata retains phase, endpoint and pointer context");
    settings.set("warp", .1);
    resolved.set("warp", .2);
    check(
        context.target().get("warp") == .65 && context.resolved().get("warp") == 1.2,
        "inspection context owns target and resolved configurations");
    check(
        Periodicity.controlDelta(context.target(), context.target()) == 0,
        "settled control delta is zero");
    BufferedImage preview = Periodicity.preview(second);
    check(
        preview.getWidth() <= 480
            && preview.getHeight() <= 320
            && preview.getRGB(0, 0) == preview.getRGB(preview.getWidth() / 2, 0),
        "wrap preview is bounded and repeats actual sampled image");
    Path path = Path.of("build/reports/periodicity-contract.json");
    Periodicity.write(path, context, report);
    check(
        PresetIO.JSON
                .fromJson(Files.readString(path), com.google.gson.JsonObject.class)
                .get("inspectionVersion")
                .getAsInt()
            == 1,
        "inspection report persists its versioned contract");
    return count;
  }
}
