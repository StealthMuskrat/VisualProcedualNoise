package dev.visualengine.export;

import com.google.gson.JsonObject;
import dev.visualengine.model.Settings;
import dev.visualengine.presets.PresetIO;
import java.awt.Transparency;
import java.awt.color.ColorSpace;
import java.awt.image.*;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import javax.imageio.*;
import javax.imageio.metadata.*;

/** Linear data export. Stored unsigned samples are deliberately independent of display encoding. */
public final class TextureMap {
  public static final String KEYWORD = "VisualEngineTextureMap";
  private static final String PNG_METADATA = "javax_imageio_png_1.0";

  public enum Kind {
    HEIGHT(
        "Height (surface field)",
        1,
        1,
        new Range(-2, 2),
        true,
        "procedural scalar field multiplied by surface height; field-domain units"),
    NORMAL(
        "Normal (+Y up)",
        2,
        3,
        new Range(-1, 1),
        false,
        "unit XYZ normal from derivatives of complete surface height in the scaled base domain; +X"
            + " right, +Y up, +Z out"),
    MASK(
        "Mask (zero level)",
        3,
        1,
        new Range(0, 1),
        false,
        "antialiased zero-level coverage of the procedural scalar field before height scaling"),
    LINEAR_COLOR(
        "Linear material color",
        4,
        3,
        new Range(0, 16),
        true,
        "linear RGB material color including active simulation overlays, before bloom, exposure and"
            + " sRGB"),
    EMISSION(
        "Emission (bloom source)",
        5,
        3,
        new Range(0, 16),
        true,
        "max(linear material RGB - 0.65, 0), matching the bloom source threshold; no blur");
    public final int shaderId, channels;
    public final Range initialRange;
    public final boolean adjustable;
    public final String meaning;
    private final String label;

    Kind(
        String label,
        int shaderId,
        int channels,
        Range initialRange,
        boolean adjustable,
        String meaning) {
      this.label = label;
      this.shaderId = shaderId;
      this.channels = channels;
      this.initialRange = initialRange;
      this.adjustable = adjustable;
      this.meaning = meaning;
    }

    public int gpuChannels() {
      return channels == 1 ? 1 : 4;
    }

    public String id() {
      return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    public static Kind parse(String value) {
      return valueOf(value.toUpperCase(Locale.ROOT).replace('-', '_'));
    }

    public void validate(Range range) {
      if (range == null
          || !adjustable && !range.equals(initialRange)
          || (this == LINEAR_COLOR || this == EMISSION) && range.minimum() < 0)
        throw new IllegalArgumentException("Invalid range for " + label);
    }

    @Override
    public String toString() {
      return label;
    }
  }

  public record Range(double minimum, double maximum) {
    public Range {
      if (!Double.isFinite(minimum)
          || !Double.isFinite(maximum)
          || Math.abs(minimum) > 1e6
          || Math.abs(maximum) > 1e6
          || maximum - minimum < 1e-6)
        throw new IllegalArgumentException(
            "Map range must increase, span at least 0.000001, and stay within ±1000000");
    }

    public static Range parse(String text) {
      String[] parts = text.split(":", -1);
      if (parts.length != 2) throw new IllegalArgumentException("Use MIN:MAX for map range");
      return new Range(Double.parseDouble(parts[0]), Double.parseDouble(parts[1]));
    }
  }

  /**
   * Owned native-order float data from the context thread; quantization/encoding run on a worker.
   */
  public record Data(Kind kind, CaptureSize size, Range range, byte[] floats) {
    public Data {
      if (kind == null || size == null)
        throw new IllegalArgumentException("Missing texture map type or size");
      kind.validate(range);
      if (floats == null
          || floats.length != (long) size.width() * size.height() * kind.gpuChannels() * 4)
        throw new IllegalArgumentException("Texture map readback size mismatch");
    }
  }

  public record Encoded(BufferedImage image, long clippedSamples) {}

  public record Frame(
      Settings target,
      Settings resolved,
      long tick,
      String device,
      String signature,
      double pointerX,
      double pointerY,
      boolean brush) {
    public Frame {
      if (target == null
          || resolved == null
          || !Double.isFinite(pointerX)
          || !Double.isFinite(pointerY)
          || Math.abs(pointerX) > 1e6
          || Math.abs(pointerY) > 1e6)
        throw new IllegalArgumentException("Invalid texture-map frame context");
      target = target.copy();
      resolved = resolved.copy();
      target.validate();
      resolved.validate();
      target.motionClock = resolved.motionClock;
      target.colorClock = resolved.colorClock;
    }
  }

  private TextureMap() {}

  public static Encoded encode(Data data) {
    int width = data.size().width(), height = data.size().height(), channels = data.kind().channels;
    BufferedImage image;
    if (channels == 1) image = new BufferedImage(width, height, BufferedImage.TYPE_USHORT_GRAY);
    else {
      var raster =
          Raster.createInterleavedRaster(DataBuffer.TYPE_USHORT, width, height, channels, null);
      var model =
          new ComponentColorModel(
              ColorSpace.getInstance(ColorSpace.CS_LINEAR_RGB),
              new int[] {16, 16, 16},
              false,
              false,
              Transparency.OPAQUE,
              DataBuffer.TYPE_USHORT);
      image = new BufferedImage(model, raster, false, null);
    }
    short[] samples = ((DataBufferUShort) image.getRaster().getDataBuffer()).getData();
    ByteBuffer input = ByteBuffer.wrap(data.floats()).order(ByteOrder.nativeOrder());
    long clipped = 0;
    for (int y = 0; y < height; y++)
      for (int x = 0; x < width; x++)
        for (int channel = 0; channel < channels; channel++) {
          float value =
              input.getFloat(
                  (((height - 1 - y) * width + x) * data.kind().gpuChannels() + channel) * 4);
          if (!Float.isFinite(value))
            throw new IllegalArgumentException(
                "Nonfinite map sample at " + x + "," + y + " channel " + channel);
          if (value < data.range().minimum() || value > data.range().maximum()) clipped++;
          double unit =
              Math.clamp(
                  (value - data.range().minimum())
                      / (data.range().maximum() - data.range().minimum()),
                  0,
                  1);
          samples[(y * width + x) * channels + channel] = (short) Math.round(unit * 65535);
        }
    return new Encoded(image, clipped);
  }

  public static JsonObject document(
      Data data, Settings settings, long tick, String device, String signature, long clipped) {
    if (signature == null || !signature.matches("[a-f0-9]{64}") || tick < 0 || tick > (1L << 52))
      throw new IllegalArgumentException("Invalid map capture context");
    JsonObject doc = PresetIO.document(settings, tick);
    doc.addProperty("textureMapVersion", 1);
    doc.addProperty("mapKind", data.kind().id());
    doc.addProperty("mapMeaning", data.kind().meaning);
    doc.addProperty(
        "mapEncoding", "unsigned 16-bit linear data; no display transfer or color profile");
    doc.addProperty("mapChannels", data.kind().channels);
    doc.addProperty("mapMinimum", data.range().minimum());
    doc.addProperty("mapMaximum", data.range().maximum());
    doc.addProperty("mapDecode", "value = minimum + code / 65535 * (maximum - minimum)");
    doc.addProperty("mapClippedSamples", clipped);
    doc.addProperty("width", data.size().width());
    doc.addProperty("height", data.size().height());
    doc.addProperty("origin", "top-left; normal Y points upward in the artistic domain");
    doc.addProperty("mapRenderScale", 1);
    doc.addProperty("mapIncludesReference", false);
    doc.addProperty("mapAppliesDebugView", false);
    doc.addProperty("mapAppliesTilePreview", false);
    doc.addProperty("device", device);
    doc.addProperty("programSignature", signature);
    return doc;
  }

  /**
   * PNG is the authoritative atomic artifact; the independently atomic sidecar mirrors its JSON.
   */
  public static long write(
      Path path, Data data, Settings settings, long tick, String device, String signature)
      throws IOException {
    return write(path, data, new Frame(settings, settings, tick, device, signature, 0, 0, false));
  }

  public static long write(Path path, Data data, Frame frame) throws IOException {
    Encoded encoded = encode(data);
    JsonObject doc =
        document(
            data,
            frame.target(),
            frame.tick(),
            frame.device(),
            frame.signature(),
            encoded.clippedSamples());
    doc.add("mapResolvedParameters", PresetIO.JSON.toJsonTree(frame.resolved().values));
    doc.add("mapResolvedEffectValues", PresetIO.JSON.toJsonTree(frame.resolved().effectValues));
    doc.add(
        "mapPointer",
        PresetIO.JSON.toJsonTree(
            new double[] {frame.pointerX(), frame.pointerY(), frame.brush() ? 1 : 0}));
    doc.addProperty(
        "mapPointerSpace", "centered pre-scale artistic domain; x aspect-correct, y upward");
    doc.addProperty(
        "mapDomain", "scaled base artistic domain: 2*scale field units across image height");
    String text = PresetIO.JSON.toJson(doc);
    if (text.getBytes(StandardCharsets.UTF_8).length > 1_048_576)
      throw new IOException("Map metadata exceeds 1 MiB");
    PresetIO.parse(text);
    Path target = path.toAbsolutePath(), sidecar = Path.of(target + ".json");
    Files.createDirectories(target.getParent());
    for (Path destination : List.of(target, sidecar))
      if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)
          && !Files.isRegularFile(destination, LinkOption.NOFOLLOW_LINKS))
        throw new IOException(
            "Map destination is not a regular file: " + destination.getFileName());
    Path png = Files.createTempFile(target.getParent(), ".texture-map-", ".png");
    Path json = null;
    try {
      json = Files.createTempFile(target.getParent(), ".texture-map-", ".json");
      var writers = ImageIO.getImageWritersByFormatName("png");
      if (!writers.hasNext()) throw new IOException("PNG writer unavailable");
      ImageWriter writer = writers.next();
      try (var output = ImageIO.createImageOutputStream(png.toFile())) {
        writer.setOutput(output);
        var parameter = writer.getDefaultWriteParam();
        var metadata =
            writer.getDefaultImageMetadata(
                ImageTypeSpecifier.createFromRenderedImage(encoded.image()), parameter);
        var tree = (IIOMetadataNode) metadata.getAsTree(PNG_METADATA);
        for (String name : List.of("sRGB", "gAMA", "iCCP", "cHRM")) {
          var nodes = tree.getElementsByTagName(name);
          for (int i = nodes.getLength() - 1; i >= 0; i--) tree.removeChild(nodes.item(i));
        }
        IIOMetadataNode texts = new IIOMetadataNode("iTXt"),
            entry = new IIOMetadataNode("iTXtEntry");
        entry.setAttribute("keyword", KEYWORD);
        entry.setAttribute("compressionFlag", "FALSE");
        entry.setAttribute("compressionMethod", "0");
        entry.setAttribute("languageTag", "");
        entry.setAttribute("translatedKeyword", "");
        entry.setAttribute("text", text);
        texts.appendChild(entry);
        tree.appendChild(texts);
        metadata.setFromTree(PNG_METADATA, tree);
        writer.write(null, new IIOImage(encoded.image(), null, metadata), parameter);
      } finally {
        writer.dispose();
      }
      Files.writeString(json, text);
      publish(png, target);
      publish(json, sidecar);
      return encoded.clippedSamples();
    } finally {
      Files.deleteIfExists(png);
      if (json != null) Files.deleteIfExists(json);
    }
  }

  private static void publish(Path temporary, Path target) throws IOException {
    try {
      Files.move(
          temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } catch (AtomicMoveNotSupportedException e) {
      Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
    }
  }
}
