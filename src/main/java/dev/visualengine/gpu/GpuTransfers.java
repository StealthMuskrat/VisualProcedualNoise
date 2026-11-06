package dev.visualengine.gpu;

import static org.lwjgl.opengl.GL43C.*;

import java.nio.ByteBuffer;
import org.lwjgl.system.MemoryUtil;

/** Explicit blocking transfers for checkpoints, outside the ordinary frame path. */
public final class GpuTransfers {
  private GpuTransfers() {}

  private static int channels(int format) {
    return format == GL_R32F ? 1 : format == GL_RG32F ? 2 : 4;
  }

  private static int type(int format) {
    return format == GL_RGBA16F ? GL_HALF_FLOAT : format == GL_RGBA8 ? GL_UNSIGNED_BYTE : GL_FLOAT;
  }

  private static int external(int format) {
    return format == GL_R32F ? GL_RED : format == GL_RG32F ? GL_RG : GL_RGBA;
  }

  private static int length(int width, int height, int format) {
    return Math.multiplyExact(
        Math.multiplyExact(width, height),
        channels(format) * (type(format) == GL_HALF_FLOAT ? 2 : type(format) == GL_FLOAT ? 4 : 1));
  }

  private static void pixels(boolean pack) {
    glBindBuffer(pack ? GL_PIXEL_PACK_BUFFER : GL_PIXEL_UNPACK_BUFFER, 0);
    glPixelStorei(pack ? GL_PACK_ALIGNMENT : GL_UNPACK_ALIGNMENT, 1);
    glPixelStorei(pack ? GL_PACK_ROW_LENGTH : GL_UNPACK_ROW_LENGTH, 0);
    glPixelStorei(pack ? GL_PACK_IMAGE_HEIGHT : GL_UNPACK_IMAGE_HEIGHT, 0);
    glPixelStorei(pack ? GL_PACK_SKIP_PIXELS : GL_UNPACK_SKIP_PIXELS, 0);
    glPixelStorei(pack ? GL_PACK_SKIP_ROWS : GL_UNPACK_SKIP_ROWS, 0);
    glPixelStorei(pack ? GL_PACK_SKIP_IMAGES : GL_UNPACK_SKIP_IMAGES, 0);
  }

  public static byte[] read(Texture texture) {
    try (Guard guard = new Guard()) {
      pixels(true);
      glMemoryBarrier(GL_TEXTURE_UPDATE_BARRIER_BIT);
      glBindTexture(GL_TEXTURE_2D, texture.id);
      ByteBuffer buffer =
          MemoryUtil.memAlloc(length(texture.width, texture.height, texture.format));
      try {
        glGetTexImage(GL_TEXTURE_2D, 0, external(texture.format), type(texture.format), buffer);
        check();
        byte[] bytes = new byte[buffer.capacity()];
        buffer.get(bytes);
        return bytes;
      } finally {
        MemoryUtil.memFree(buffer);
      }
    }
  }

  public static void write(Texture texture, byte[] data) {
    if (data.length != length(texture.width, texture.height, texture.format))
      throw new IllegalArgumentException("Texture checkpoint size mismatch");
    try (Guard guard = new Guard()) {
      pixels(false);
      glBindTexture(GL_TEXTURE_2D, texture.id);
      ByteBuffer buffer = MemoryUtil.memAlloc(data.length);
      try {
        buffer.put(data).flip();
        glTexSubImage2D(
            GL_TEXTURE_2D,
            0,
            0,
            0,
            texture.width,
            texture.height,
            external(texture.format),
            type(texture.format),
            buffer);
        check();
      } finally {
        MemoryUtil.memFree(buffer);
      }
    }
  }

  public static byte[] readLayer(int texture, int layer, int resolution) {
    try (Guard guard = new Guard()) {
      int fbo = glGenFramebuffers();
      ByteBuffer buffer = MemoryUtil.memAlloc(length(resolution, resolution, GL_RGBA16F));
      try {
        pixels(true);
        glMemoryBarrier(GL_FRAMEBUFFER_BARRIER_BIT);
        glBindFramebuffer(GL_READ_FRAMEBUFFER, fbo);
        glFramebufferTextureLayer(GL_READ_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, texture, 0, layer);
        glReadBuffer(GL_COLOR_ATTACHMENT0);
        if (glCheckFramebufferStatus(GL_READ_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE)
          throw new IllegalStateException("History readback framebuffer is incomplete");
        glReadPixels(0, 0, resolution, resolution, GL_RGBA, GL_HALF_FLOAT, buffer);
        check();
        byte[] bytes = new byte[buffer.capacity()];
        buffer.get(bytes);
        return bytes;
      } finally {
        MemoryUtil.memFree(buffer);
        glDeleteFramebuffers(fbo);
      }
    }
  }

  public static void writeLayer(int texture, int layer, int resolution, byte[] data) {
    if (data.length != length(resolution, resolution, GL_RGBA16F))
      throw new IllegalArgumentException("History checkpoint size mismatch");
    try (Guard guard = new Guard()) {
      pixels(false);
      glBindTexture(GL_TEXTURE_2D_ARRAY, texture);
      ByteBuffer buffer = MemoryUtil.memAlloc(data.length);
      try {
        buffer.put(data).flip();
        glTexSubImage3D(
            GL_TEXTURE_2D_ARRAY,
            0,
            0,
            0,
            layer,
            resolution,
            resolution,
            1,
            GL_RGBA,
            GL_HALF_FLOAT,
            buffer);
        check();
      } finally {
        MemoryUtil.memFree(buffer);
      }
    }
  }

  public static byte[] readBuffer(int id, int length) {
    if (length == 0) return new byte[0];
    try (Guard guard = new Guard()) {
      glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT);
      glBindBuffer(GL_COPY_READ_BUFFER, id);
      ByteBuffer buffer = MemoryUtil.memAlloc(length);
      try {
        glGetBufferSubData(GL_COPY_READ_BUFFER, 0, buffer);
        check();
        byte[] bytes = new byte[length];
        buffer.get(bytes);
        return bytes;
      } finally {
        MemoryUtil.memFree(buffer);
      }
    }
  }

  public static void writeBuffer(int id, byte[] data) {
    if (data.length == 0) return;
    try (Guard guard = new Guard()) {
      glBindBuffer(GL_COPY_WRITE_BUFFER, id);
      ByteBuffer buffer = MemoryUtil.memAlloc(data.length);
      try {
        buffer.put(data).flip();
        glBufferSubData(GL_COPY_WRITE_BUFFER, 0, buffer);
        check();
      } finally {
        MemoryUtil.memFree(buffer);
      }
    }
  }

  private static void check() {
    int error = glGetError();
    if (error != GL_NO_ERROR)
      throw new IllegalStateException("Checkpoint GPU transfer failed: GL error " + error);
  }

  private static final class Guard implements AutoCloseable {
    private final int read = glGetInteger(GL_READ_FRAMEBUFFER_BINDING),
        draw = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING),
        active = glGetInteger(GL_ACTIVE_TEXTURE),
        tex = glGetInteger(GL_TEXTURE_BINDING_2D),
        array = glGetInteger(GL_TEXTURE_BINDING_2D_ARRAY),
        copyRead = glGetInteger(GL_COPY_READ_BUFFER_BINDING),
        copyWrite = glGetInteger(GL_COPY_WRITE_BUFFER_BINDING),
        pack = glGetInteger(GL_PIXEL_PACK_BUFFER_BINDING),
        unpack = glGetInteger(GL_PIXEL_UNPACK_BUFFER_BINDING);
    private final int[] names = {
      GL_PACK_ALIGNMENT,
      GL_PACK_ROW_LENGTH,
      GL_PACK_IMAGE_HEIGHT,
      GL_PACK_SKIP_PIXELS,
      GL_PACK_SKIP_ROWS,
      GL_PACK_SKIP_IMAGES,
      GL_UNPACK_ALIGNMENT,
      GL_UNPACK_ROW_LENGTH,
      GL_UNPACK_IMAGE_HEIGHT,
      GL_UNPACK_SKIP_PIXELS,
      GL_UNPACK_SKIP_ROWS,
      GL_UNPACK_SKIP_IMAGES
    };
    private final int[] values = java.util.Arrays.stream(names).map(n -> glGetInteger(n)).toArray();

    public void close() {
      glBindFramebuffer(GL_READ_FRAMEBUFFER, read);
      glBindFramebuffer(GL_DRAW_FRAMEBUFFER, draw);
      glActiveTexture(active);
      glBindTexture(GL_TEXTURE_2D, tex);
      glBindTexture(GL_TEXTURE_2D_ARRAY, array);
      glBindBuffer(GL_COPY_READ_BUFFER, copyRead);
      glBindBuffer(GL_COPY_WRITE_BUFFER, copyWrite);
      glBindBuffer(GL_PIXEL_PACK_BUFFER, pack);
      glBindBuffer(GL_PIXEL_UNPACK_BUFFER, unpack);
      for (int i = 0; i < names.length; i++) glPixelStorei(names[i], values[i]);
    }
  }
}
