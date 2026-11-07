package dev.visualengine.gpu;

import static org.lwjgl.opengl.GL43C.*;

public final class Texture implements AutoCloseable {
  public final int id, framebuffer, width, height, format;

  public Texture(int width, int height, int format) {
    if (width < 1 || height < 1 || width > 8192 || height > 8192)
      throw new IllegalArgumentException("Invalid texture extent");
    GpuDevice.current().requireTexture(GpuDevice.textureName(format), width, height);
    this.width = width;
    this.height = height;
    this.format = format;
    id = glGenTextures();
    framebuffer = glGenFramebuffers();
    try {
      glBindTexture(GL_TEXTURE_2D, id);
      glTexStorage2D(GL_TEXTURE_2D, 1, format, width, height);
      glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
      glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
      glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT);
      glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_REPEAT);
      glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
      glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, id, 0);
      int status = glCheckFramebufferStatus(GL_FRAMEBUFFER), error = glGetError();
      if (status != GL_FRAMEBUFFER_COMPLETE || error != GL_NO_ERROR)
        throw new IllegalStateException(
            "GPU allocation failed for "
                + width
                + "x"
                + height
                + " format "
                + format
                + "; framebuffer status "
                + status
                + "; GL error "
                + error);
      clear(0, 0, 0, 0);
    } catch (RuntimeException e) {
      close();
      throw e;
    }
  }

  public void bind(int unit) {
    glActiveTexture(GL_TEXTURE0 + unit);
    glBindTexture(GL_TEXTURE_2D, id);
  }

  public void clamp() {
    glBindTexture(GL_TEXTURE_2D, id);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
  }

  public void image(int unit) {
    glBindImageTexture(unit, id, 0, false, 0, GL_WRITE_ONLY, format);
  }

  public void target() {
    glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
    glViewport(0, 0, width, height);
  }

  public void clear(float r, float g, float b, float a) {
    target();
    glClearBufferfv(GL_COLOR, 0, new float[] {r, g, b, a});
  }

  public long bytes() {
    return (long) width
        * height
        * (format == GL_RGBA32F ? 16 : format == GL_R32F || format == GL_RGBA8 ? 4 : 8);
  }

  @Override
  public void close() {
    glDeleteFramebuffers(framebuffer);
    glDeleteTextures(id);
  }
}
