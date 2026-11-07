package dev.visualengine;

import com.google.gson.*;
import dev.visualengine.export.*;
import dev.visualengine.model.Settings;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;

public final class TextureMapChecks {
  private static int count;

  private interface Action {
    void run() throws Exception;
  }

  private static void check(boolean value, String label) {
    count++;
    if (!value) throw new AssertionError(label);
  }

  private static void rejects(Action action, String label) throws Exception {
    try {
      action.run();
    } catch (IOException | IllegalArgumentException e) {
      count++;
      return;
    }
    throw new AssertionError("Accepted " + label);
  }

  public static int run() throws Exception {
    var size = new CaptureSize(32, 32);
    Settings s = new Settings();
    s.seed = Long.MIN_VALUE;
    s.name = "Été maps";
    s.debug = 8;
    Path root = Files.createTempDirectory("texture-map-contract-");
    try {
      for (var kind : TextureMap.Kind.values()) {
        ByteBuffer floats =
            ByteBuffer.allocate(32 * 32 * kind.gpuChannels() * 4).order(ByteOrder.nativeOrder());
        for (int y = 0; y < 32; y++)
          for (int x = 0; x < 32; x++) {
            if (kind.channels == 1)
              floats.putFloat(
                  (float)
                      (kind.initialRange.minimum()
                          + y / 31. * (kind.initialRange.maximum() - kind.initialRange.minimum())));
            else if (kind == TextureMap.Kind.NORMAL)
              floats.putFloat(0).putFloat(-1).putFloat(1).putFloat(1);
            else floats.putFloat(0).putFloat(8).putFloat(16).putFloat(1);
          }
        var data = new TextureMap.Data(kind, size, kind.initialRange, floats.array());
        Path path = root.resolve(kind.id() + " data.png");
        long clipped = TextureMap.write(path, data, s, 77, "test GPU", "a".repeat(64));
        var image = ImageIO.read(path.toFile());
        check(
            clipped == 0
                && image.getRaster().getNumBands() == kind.channels
                && Arrays.stream(image.getSampleModel().getSampleSize())
                    .allMatch(bits -> bits == 16),
            "native 16-bit PNG channels: " + kind);
        if (kind.channels == 1)
          check(
              image.getRaster().getSample(0, 0, 0) == 65535
                  && image.getRaster().getSample(0, 31, 0) == 0
                  && Math.abs(image.getRaster().getSample(0, 15, 0) - Math.round(16. / 31 * 65535))
                      <= 1,
              "scalar linear codes and top-left orientation: " + kind);
        else
          check(
              image.getRaster().getSample(0, 0, 0) == (kind == TextureMap.Kind.NORMAL ? 32768 : 0)
                  && image.getRaster().getSample(0, 0, 1)
                      == (kind == TextureMap.Kind.NORMAL ? 0 : 32768)
                  && image.getRaster().getSample(0, 0, 2) == 65535,
              "RGB samples bypass color conversion: " + kind);
        var doc =
            JsonParser.parseString(Files.readString(Path.of(path + ".json"))).getAsJsonObject();
        check(
            doc.get("rootSeed").getAsString().equals(Long.toString(Long.MIN_VALUE))
                && doc.get("referenceTick").getAsLong() == 77
                && doc.get("mapKind").getAsString().equals(kind.id())
                && doc.get("mapChannels").getAsInt() == kind.channels
                && !doc.get("mapIncludesReference").getAsBoolean(),
            "map sidecar retains capture context: " + kind);
        try (var input = ImageIO.createImageInputStream(path.toFile())) {
          var readers = ImageIO.getImageReaders(input);
          var reader = readers.next();
          try {
            reader.setInput(input);
            var tree =
                (javax.imageio.metadata.IIOMetadataNode)
                    reader.getImageMetadata(0).getAsTree("javax_imageio_png_1.0");
            var texts = tree.getElementsByTagName("iTXtEntry");
            check(
                texts.getLength() == 1
                    && JsonParser.parseString(
                            ((javax.imageio.metadata.IIOMetadataNode) texts.item(0))
                                .getAttribute("text"))
                        .equals(doc),
                "PNG embeds the complete authoritative JSON: " + kind);
            check(
                List.of("sRGB", "gAMA", "iCCP", "cHRM").stream()
                    .allMatch(name -> tree.getElementsByTagName(name).getLength() == 0),
                "data PNG has no display color-transfer tags: " + kind);
          } finally {
            reader.dispose();
          }
        }
      }
      ByteBuffer values = ByteBuffer.allocate(32 * 32 * 4).order(ByteOrder.nativeOrder());
      for (int i = 0; i < 32 * 32; i++) values.putFloat(i % 2 == 0 ? -3 : 3);
      var clipped =
          new TextureMap.Data(
              TextureMap.Kind.HEIGHT, size, new TextureMap.Range(-2, 2), values.array());
      check(
          TextureMap.encode(clipped).clippedSamples() == 1024,
          "out-of-range map samples are counted");
      Path existing = root.resolve("height data.png");
      byte[] before = Files.readAllBytes(existing),
          metadata = Files.readAllBytes(Path.of(existing + ".json"));
      values.putFloat(0, Float.NaN);
      rejects(
          () -> TextureMap.write(existing, clipped, s, 77, "test GPU", "a".repeat(64)),
          "nonfinite map samples");
      check(
          Arrays.equals(before, Files.readAllBytes(existing))
              && Arrays.equals(metadata, Files.readAllBytes(Path.of(existing + ".json"))),
          "invalid map preserves the previous image and metadata");
      rejects(() -> new TextureMap.Range(2, 1), "reversed range");
      rejects(() -> new TextureMap.Range(0, Double.POSITIVE_INFINITY), "nonfinite range");
      rejects(
          () -> TextureMap.Kind.NORMAL.validate(new TextureMap.Range(0, 1)),
          "normal encoding override");
      rejects(
          () -> TextureMap.Kind.LINEAR_COLOR.validate(new TextureMap.Range(-1, 1)),
          "negative color encoding range");
      rejects(
          () ->
              new TextureMap.Data(
                  TextureMap.Kind.MASK, size, new TextureMap.Range(0, 1), new byte[4]),
          "wrong readback size");
      values.putFloat(0, 0);
      Path occupiedSidecar = Path.of(existing + ".json");
      Files.delete(occupiedSidecar);
      Files.createDirectory(occupiedSidecar);
      Files.writeString(occupiedSidecar.resolve("marker"), "keep");
      rejects(
          () -> TextureMap.write(existing, clipped, s, 78, "test", "a".repeat(64)),
          "occupied map sidecar");
      check(
          Arrays.equals(before, Files.readAllBytes(existing))
              && Files.readString(occupiedSidecar.resolve("marker")).equals("keep"),
          "blocked map sidecar preserves the existing PNG and directory");
      Files.delete(occupiedSidecar.resolve("marker"));
      Files.delete(occupiedSidecar);
      TextureMap.write(existing, clipped, s, 79, "test", "a".repeat(64));
      check(
          JsonParser.parseString(Files.readString(occupiedSidecar))
                  .getAsJsonObject()
                  .get("referenceTick")
                  .getAsLong()
              == 79,
          "successful map overwrite commits matching metadata");
      Path blocked = root.resolve("blocked.png");
      Files.createDirectory(blocked);
      rejects(
          () -> TextureMap.write(blocked, clipped, s, 77, "test", "a".repeat(64)),
          "occupied map destination");
      try (var files = Files.list(root)) {
        check(
            files.noneMatch(
                p ->
                    p.getFileName().toString().startsWith(".texture-map-")
                        || p.getFileName().toString().startsWith(".visual-output-")),
            "map writer cleans temporary files");
      }
    } finally {
      try (var files = Files.walk(root)) {
        for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
      }
    }
    return count;
  }
}
