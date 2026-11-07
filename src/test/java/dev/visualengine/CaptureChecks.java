package dev.visualengine;

import com.google.gson.JsonParser;
import dev.visualengine.app.Options;
import dev.visualengine.export.*;
import dev.visualengine.model.*;
import dev.visualengine.presets.PresetIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;

public final class CaptureChecks {
  private static int count;

  private static void check(boolean value, String message) {
    count++;
    if (!value) throw new AssertionError(message);
  }

  private static void rejects(Runnable action) {
    try {
      action.run();
    } catch (IllegalArgumentException e) {
      count++;
      return;
    }
    throw new AssertionError("Accepted invalid capture input");
  }

  private interface Write {
    void run() throws IOException;
  }

  private static void fails(Write action) throws IOException {
    try {
      action.run();
    } catch (IOException | IllegalArgumentException expected) {
      count++;
      return;
    }
    throw new AssertionError("Accepted invalid artwork save");
  }

  public static int run() throws Exception {
    check(
        CaptureSize.parse("191x127").equals(new CaptureSize(191, 127)),
        "odd extent accepted without rounding");
    rejects(() -> CaptureSize.parse("1920x1080x1"));
    rejects(() -> CaptureSize.parse("-1x64"));
    rejects(() -> new CaptureSize(8192, 8192));
    rejects(() -> new CaptureSize(31, 64));
    rejects(() -> Options.parse(new String[] {"--capture-size", "1920x1080"}));
    var options =
        Options.parse(
            new String[] {
              "--size", "320x200", "--capture", "out.png", "--capture-size", "1920x1080"
            });
    check(
        options.width == 320 && options.height == 200 && options.captureSize.width() == 1920,
        "capture extent leaves window extent untouched");
    Path root = Files.createTempDirectory("capture-contract-");
    try {
      Path file = root.resolve("été final.png");
      BufferedImage image = new BufferedImage(96, 64, BufferedImage.TYPE_INT_RGB);
      image.setRGB(0, 0, 0xff123456);
      image.setRGB(95, 63, 0xffabcdef);
      Settings s = QualityProfile.DRAFT.apply(new Settings());
      Capture.write(file, image, s, 77, "test GPU", new Capture.View(96, 64, true));
      var document =
          JsonParser.parseString(Files.readString(Path.of(file + ".json"))).getAsJsonObject();
      check(
          document.get("width").getAsInt() == 96
              && document.get("height").getAsInt() == 64
              && document.get("captureRenderWidth").getAsInt() == 96
              && document.get("captureRenderHeight").getAsInt() == 64
              && document.get("captureIncludesReference").getAsBoolean()
              && document.get("referenceTick").getAsLong() == 77,
          "still sidecar identifies actual render dimensions, stage, reference and tick");
      check(
          PresetIO.load(Path.of(file + ".json")).displayScale == .5
              && document.get("qualityProfile").getAsString().equals("draft"),
          "sidecar retains live quality separately from full-resolution output");
      var decoded = ImageIO.read(file.toFile());
      check(
          decoded.getRGB(0, 0) == 0xff123456 && decoded.getRGB(95, 63) == 0xffabcdef,
          "PNG color and top-left orientation survive spaced Unicode file paths");
      Path sidecar = Path.of(file + ".json");
      byte[] previous = Files.readAllBytes(file), previousMetadata = Files.readAllBytes(sidecar);
      image.setRGB(0, 0, 0xff556677);
      Settings invalid = s.copy();
      invalid.grid = 1;
      fails(() -> Capture.write(file, image, invalid, 78, "test GPU"));
      check(
          Arrays.equals(previous, Files.readAllBytes(file))
              && Arrays.equals(previousMetadata, Files.readAllBytes(sidecar)),
          "invalid capture metadata is rejected before either destination changes");
      fails(() -> Capture.write(file, image, s, 78, "x".repeat(1_048_576)));
      check(
          Arrays.equals(previous, Files.readAllBytes(file))
              && Arrays.equals(previousMetadata, Files.readAllBytes(sidecar)),
          "oversized UTF-8 capture metadata preserves the previous pair");
      Files.delete(sidecar);
      Files.createDirectory(sidecar);
      Files.writeString(sidecar.resolve("marker"), "keep");
      fails(() -> Capture.write(file, image, s, 78, "test GPU"));
      check(
          Arrays.equals(previous, Files.readAllBytes(file))
              && Files.readString(sidecar.resolve("marker")).equals("keep"),
          "blocked artwork sidecar preserves the prior image and occupied directory");
      Files.delete(sidecar.resolve("marker"));
      Files.delete(sidecar);
      Capture.write(file, image, s, 79, "test GPU");
      check(
          ImageIO.read(file.toFile()).getRGB(0, 0) == 0xff556677
              && JsonParser.parseString(Files.readString(sidecar))
                      .getAsJsonObject()
                      .get("referenceTick")
                      .getAsLong()
                  == 79,
          "successful artwork overwrite commits the new image and matching metadata");
      try (var files = Files.list(root)) {
        check(
            files.noneMatch(p -> p.getFileName().toString().startsWith(".visual-output-")),
            "artwork writer cleans paired staging after successful and rejected saves");
      }
    } finally {
      try (var paths = Files.walk(root)) {
        for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
      }
    }
    return count;
  }
}
