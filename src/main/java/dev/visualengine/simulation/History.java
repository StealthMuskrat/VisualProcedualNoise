package dev.visualengine.simulation;

import static org.lwjgl.opengl.GL43C.*;

import dev.visualengine.gpu.Texture;
import java.util.Arrays;

/** A fixed-cadence ring. Invalid layers are never sampled. Timestamps use simulation ticks. */
public final class History implements AutoCloseable {
  public final int layers, resolution, id;
  private final Texture reduced;
  private final long[] ticks;
  private int count, head = -1;

  public History(int layers, int resolution) {
    this.layers = layers;
    this.resolution = resolution;
    ticks = new long[layers];
    reduced = new Texture(resolution, resolution, GL_RGBA16F);
    id = glGenTextures();
    try {
      glBindTexture(GL_TEXTURE_2D_ARRAY, id);
      glTexStorage3D(
          GL_TEXTURE_2D_ARRAY, 1, GL_RGBA16F, resolution, resolution, Math.max(1, layers));
      glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
      glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
      glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
      glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
      if (glGetError() != GL_NO_ERROR) throw new IllegalStateException("History allocation failed");
    } catch (RuntimeException e) {
      close();
      throw e;
    }
    reset();
  }

  public void reset() {
    count = 0;
    head = -1;
    Arrays.fill(ticks, -1);
  }

  public void capture(Texture source, long tick) {
    if (layers == 0 || tick % 8 != 0) return;
    // The source may be the feedback image written by a compute shader.
    glMemoryBarrier(GL_FRAMEBUFFER_BARRIER_BIT);
    glBindFramebuffer(GL_READ_FRAMEBUFFER, source.framebuffer);
    glBindFramebuffer(GL_DRAW_FRAMEBUFFER, reduced.framebuffer);
    glBlitFramebuffer(
        0,
        0,
        source.width,
        source.height,
        0,
        0,
        resolution,
        resolution,
        GL_COLOR_BUFFER_BIT,
        GL_LINEAR);
    glBindFramebuffer(GL_READ_FRAMEBUFFER, reduced.framebuffer);
    glBindTexture(GL_TEXTURE_2D_ARRAY, id);
    head = (head + 1) % layers;
    glCopyTexSubImage3D(GL_TEXTURE_2D_ARRAY, 0, 0, 0, head, 0, 0, resolution, resolution);
    ticks[head] = tick;
    count = Math.min(count + 1, layers);
  }

  public int count() {
    return count;
  }

  public int head() {
    return Math.max(0, head);
  }

  public long[] ticks() {
    return ticks.clone();
  }

  public void bind(int unit) {
    glActiveTexture(GL_TEXTURE0 + unit);
    glBindTexture(GL_TEXTURE_2D_ARRAY, id);
  }

  public long bytes() {
    return reduced.bytes() + (long) resolution * resolution * Math.max(1, layers) * 8;
  }

  @Override
  public void close() {
    reduced.close();
    glDeleteTextures(id);
  }
}
