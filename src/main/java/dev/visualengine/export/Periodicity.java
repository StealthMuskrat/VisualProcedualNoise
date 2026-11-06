package dev.visualengine.export;

import com.google.gson.JsonObject;
import dev.visualengine.model.Settings;
import dev.visualengine.presets.PresetIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/** Measured rendered-pixel comparisons. No automatic seamlessness certification. */
public final class Periodicity {
  public static final long MAX_PIXELS = 4_194_304;
  public static final double CONTROL_TOLERANCE = 1e-6;

  public record Metrics(
      long pixelPairs, long changedPairs, int maximum, double meanAbsolute, double rms) {}

  public record Edges(
      Metrics horizontalWrap,
      Metrics horizontalInterior,
      Metrics verticalWrap,
      Metrics verticalInterior) {}

  public record Loop(boolean sampled, double seconds, Metrics difference, String interpretation) {}

  public record Report(
      CaptureSize size, Edges edges, Loop loop, double maximumNormalizedControlDelta) {
    public String summary() {
      return String.format(
          Locale.ROOT,
          "Measured %d×%d · encoded RGB levels 0..255\n"
              + "Left/right wrap: RMS %.3f · max %d\n"
              + "Nearby horizontal steps: RMS %.3f\n"
              + "Top/bottom wrap: RMS %.3f · max %d\n"
              + "Nearby vertical steps: RMS %.3f\n"
              + "%s\n"
              + "%s\n"
              + "Edge pixels are adjacent sample centers across a wrap, not duplicate boundary"
              + " samples.",
          size.width(),
          size.height(),
          edges.horizontalWrap().rms(),
          edges.horizontalWrap().maximum(),
          edges.horizontalInterior().rms(),
          edges.verticalWrap().rms(),
          edges.verticalWrap().maximum(),
          edges.verticalInterior().rms(),
          loop.sampled()
              ? String.format(
                  Locale.ROOT,
                  "Loop endpoint: RMS %.3f · max %d · %d changed pixels",
                  loop.difference().rms(),
                  loop.difference().maximum(),
                  loop.difference().changedPairs())
              : "Loop endpoint not sampled: " + loop.interpretation(),
          maximumNormalizedControlDelta <= CONTROL_TOLERANCE
              ? "Resolved controls are within target tolerance."
              : "Resolved controls differ from targets; endpoint test holds resolved values"
                  + " fixed.");
    }
  }

  public record Frame(
      Settings target,
      Settings resolved,
      long tick,
      double seconds,
      String device,
      String signature,
      double pointerX,
      double pointerY,
      boolean brush) {
    public Frame {
      if (target == null
          || resolved == null
          || tick < 0
          || !Double.isFinite(seconds)
          || seconds < 0
          || device == null
          || signature == null
          || !Double.isFinite(pointerX)
          || !Double.isFinite(pointerY)
          || Math.abs(pointerX) > 1e6
          || Math.abs(pointerY) > 1e6)
        throw new IllegalArgumentException("Invalid inspection frame");
      target = target.copy();
      resolved = resolved.copy();
      target.validate();
      resolved.validate();
      target.motionClock = resolved.motionClock;
      target.colorClock = resolved.colorClock;
    }
  }

  private Periodicity() {}

  public static CaptureSize requireSize(CaptureSize size) {
    if (size == null
        || size.width() > 4096
        || size.height() > 4096
        || (long) size.width() * size.height() > MAX_PIXELS)
      throw new IllegalArgumentException(
          "Inspection dimensions must be 32..4096, at most 4 megapixels");
    return size;
  }

  public static boolean sampleLoop(Settings settings) {
    settings.validate();
    // Settings validation excludes state, graph resources, modulation and unbounded formula time
    // for configured analytic loops (the latter is checked by resolveResources).
    dev.visualengine.authoring.EffectPackage.resolveResources(settings.copy());
    return settings.loopSeconds > 0;
  }

  public static Report analyze(Frame frame, BufferedImage first, BufferedImage endpoint) {
    requireImage(first);
    CaptureSize size = requireSize(new CaptureSize(first.getWidth(), first.getHeight()));
    boolean loop = sampleLoop(frame.resolved());
    if (loop != (endpoint != null))
      throw new IllegalArgumentException(
          "Inspection endpoint does not match configured analytic loop");
    Metrics temporal = endpoint == null ? null : difference(first, endpoint);
    return new Report(
        size,
        edges(first),
        new Loop(
            loop,
            frame.resolved().loopSeconds,
            temporal,
            loop
                ? "Rendered endpoints of the configured analytic period at fixed resolved controls"
                    + " and pointer; no simulation or smoothing advancement."
                : "No analytic loop period is configured; future persistent state is not"
                    + " inferred."),
        controlDelta(frame.target(), frame.resolved()));
  }

  public static Edges edges(BufferedImage image) {
    requireImage(image);
    int width = image.getWidth(), height = image.getHeight();
    if (width < 2 || height < 2)
      throw new IllegalArgumentException("Edge inspection needs at least two rows and columns");
    Accumulator x = new Accumulator(),
        xi = new Accumulator(),
        y = new Accumulator(),
        yi = new Accumulator();
    for (int row = 0; row < height; row++) {
      x.add(image.getRGB(0, row), image.getRGB(width - 1, row));
      xi.add(image.getRGB(0, row), image.getRGB(1, row));
      xi.add(image.getRGB(width - 2, row), image.getRGB(width - 1, row));
    }
    for (int column = 0; column < width; column++) {
      y.add(image.getRGB(column, 0), image.getRGB(column, height - 1));
      yi.add(image.getRGB(column, 0), image.getRGB(column, 1));
      yi.add(image.getRGB(column, height - 2), image.getRGB(column, height - 1));
    }
    return new Edges(x.result(), xi.result(), y.result(), yi.result());
  }

  public static Metrics difference(BufferedImage first, BufferedImage second) {
    requireImage(first);
    requireImage(second);
    if (first.getWidth() != second.getWidth() || first.getHeight() != second.getHeight())
      throw new IllegalArgumentException("Endpoint images must have equal dimensions");
    Accumulator result = new Accumulator();
    for (int y = 0; y < first.getHeight(); y++)
      for (int x = 0; x < first.getWidth(); x++)
        result.add(first.getRGB(x, y), second.getRGB(x, y));
    return result.result();
  }

  private static void requireImage(BufferedImage image) {
    if (image == null
        || image.getWidth() < 1
        || image.getHeight() < 1
        || (long) image.getWidth() * image.getHeight() > MAX_PIXELS
        || image.getTransparency() != Transparency.OPAQUE)
      throw new IllegalArgumentException(
          "Inspection requires opaque images of at most 4 megapixels");
  }

  public static double controlDelta(Settings target, Settings resolved) {
    double maximum = 0;
    for (var p : Settings.PARAMETERS)
      maximum =
          Math.max(
              maximum,
              Math.abs(target.get(p.id()) - resolved.get(p.id())) / (p.maximum() - p.minimum()));
    if (!target.effectControls.equals(resolved.effectControls))
      throw new IllegalArgumentException("Inspection control schemas differ");
    for (var c : target.effectControls)
      for (int i = 0; i < c.type().width; i++)
        maximum =
            Math.max(
                maximum,
                Math.abs(
                        target.effectValues.get(c.id()).get(i)
                            - resolved.effectValues.get(c.id()).get(i))
                    / (c.maximum() - c.minimum()));
    return maximum;
  }

  public static JsonObject document(Frame frame, Report report) {
    JsonObject json = PresetIO.document(frame.target(), frame.tick());
    json.addProperty("inspectionVersion", 1);
    json.addProperty("device", frame.device());
    json.addProperty("programSignature", frame.signature());
    json.addProperty("referenceSeconds", frame.seconds());
    json.addProperty(
        "measurement",
        "absolute differences of opaque 8-bit encoded sRGB channel levels; alpha excluded");
    json.addProperty(
        "spatialInterpretation",
        "First/last pixel centers are neighbors across a repeated image, not duplicate mathematical"
            + " boundary samples. Interior values are the adjacent steps just inside both borders."
            + " No automatic seamlessness verdict.");
    json.addProperty(
        "tileContract",
        frame.target().tileMode
            ? "analytic spatial domain mapping enabled"
            : "unrestricted spatial domain");
    if (report.loop().sampled())
      json.addProperty("endpointSeconds", frame.seconds() + report.loop().seconds());
    Settings render = frame.resolved().copy();
    render.debug = 0;
    render.tilePreview = false;
    render.displayScale = 1;
    json.add("renderSettings", PresetIO.document(render, frame.tick()).getAsJsonObject("settings"));
    json.addProperty("includesReference", false);
    json.addProperty("includesDiagnostics", false);
    json.addProperty("includesWrapPreview", false);
    json.addProperty("controlTolerance", CONTROL_TOLERANCE);
    json.addProperty(
        "controlsWithinTolerance", report.maximumNormalizedControlDelta() <= CONTROL_TOLERANCE);
    json.add(
        "pointer",
        PresetIO.JSON.toJsonTree(
            Map.of("x", frame.pointerX(), "y", frame.pointerY(), "brush", frame.brush())));
    json.add("inspection", PresetIO.JSON.toJsonTree(report));
    return json;
  }

  public static void write(Path path, Frame frame, Report report) throws IOException {
    PresetIO.atomicWrite(path, PresetIO.JSON.toJson(document(frame, report)));
  }

  /** Small nearest-neighbor 2x2 image repeat, with no border drawn over the joins. */
  public static BufferedImage preview(BufferedImage image) {
    requireImage(image);
    double factor = Math.min(240. / image.getWidth(), 160. / image.getHeight());
    int w = Math.max(1, (int) (image.getWidth() * factor)),
        h = Math.max(1, (int) (image.getHeight() * factor));
    BufferedImage result = new BufferedImage(w * 2, h * 2, BufferedImage.TYPE_INT_RGB);
    Graphics2D g = result.createGraphics();
    try {
      g.setRenderingHint(
          RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
      for (int y = 0; y < 2; y++)
        for (int x = 0; x < 2; x++) g.drawImage(image, x * w, y * h, w, h, null);
    } finally {
      g.dispose();
    }
    return result;
  }

  private static final class Accumulator {
    long pairs, changed, sum, squares;
    int maximum;

    void add(int a, int b) {
      pairs++;
      if ((a & 0xffffff) != (b & 0xffffff)) changed++;
      for (int shift = 0; shift <= 16; shift += 8) {
        int d = Math.abs(((a >>> shift) & 255) - ((b >>> shift) & 255));
        sum += d;
        squares += (long) d * d;
        maximum = Math.max(maximum, d);
      }
    }

    Metrics result() {
      return new Metrics(
          pairs,
          changed,
          maximum,
          (double) sum / (pairs * 3),
          Math.sqrt((double) squares / (pairs * 3)));
    }
  }
}
