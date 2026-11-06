package dev.visualengine.export;

import static org.lwjgl.opengl.GL43C.*;

import dev.visualengine.model.Settings;
import dev.visualengine.presets.PresetIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import javax.imageio.ImageIO;
import org.lwjgl.system.MemoryUtil;

public final class Capture {
  public record View(int renderWidth, int renderHeight, boolean includesReference) {
    public View {
      if (renderWidth < 1 || renderHeight < 1 || renderWidth > 8192 || renderHeight > 8192)
        throw new IllegalArgumentException("Invalid capture render dimensions");
    }
  }

  private Capture() {}

  /**
   * Explicit still capture may synchronize; encoding and filesystem work happen on the IO worker.
   */
  public static BufferedImage read(int width, int height) {
    if (width < 1 || height < 1 || (long) width * height > 33_554_432L)
      throw new IllegalArgumentException("Capture dimensions exceed 32 megapixels");
    var pixels = MemoryUtil.memAlloc(Math.multiplyExact(Math.multiplyExact(width, height), 4));
    try {
      glPixelStorei(GL_PACK_ALIGNMENT, 1);
      glReadPixels(0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
      if (glGetError() != GL_NO_ERROR) throw new IllegalStateException("Artwork readback failed");
      BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
      for (int y = 0; y < height; y++)
        for (int x = 0; x < width; x++) {
          int p = ((height - 1 - y) * width + x) * 4;
          image.setRGB(
              x,
              y,
              ((pixels.get(p) & 255) << 16)
                  | ((pixels.get(p + 1) & 255) << 8)
                  | (pixels.get(p + 2) & 255));
        }
      return image;
    } finally {
      MemoryUtil.memFree(pixels);
    }
  }

  public static void write(
      Path path, BufferedImage image, Settings settings, long tick, String device)
      throws IOException {
    write(
        path,
        image,
        settings,
        tick,
        device,
        new View(
            Math.max(1, (int) (image.getWidth() * settings.displayScale)),
            Math.max(1, (int) (image.getHeight() * settings.displayScale)),
            false));
  }

  public static void write(
      Path path, BufferedImage image, Settings settings, long tick, String device, View view)
      throws IOException {
    Path target = path.toAbsolutePath();
    Files.createDirectories(target.getParent());
    Path temp = Files.createTempFile(target.getParent(), ".capture-", ".png");
    try {
      if (!ImageIO.write(image, "png", temp.toFile()))
        throw new IOException("PNG encoder unavailable");
      Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
    } finally {
      Files.deleteIfExists(temp);
    }
    var doc = PresetIO.document(settings, tick);
    doc.addProperty("device", device);
    doc.addProperty("captureEncoding", "sRGB SDR opaque RGB top-left origin");
    doc.addProperty("width", image.getWidth());
    doc.addProperty("height", image.getHeight());
    doc.addProperty("captureStage", "finished display color before UI");
    doc.addProperty("captureRenderWidth", view.renderWidth());
    doc.addProperty("captureRenderHeight", view.renderHeight());
    doc.addProperty("captureIncludesReference", view.includesReference());
    doc.addProperty(
        "qualityProfile",
        dev.visualengine.model.QualityProfile.matching(settings)
            .map(p -> p.name().toLowerCase(java.util.Locale.ROOT))
            .orElse("custom"));
    PresetIO.atomicWrite(Path.of(target + ".json"), PresetIO.JSON.toJson(doc));
  }
}
