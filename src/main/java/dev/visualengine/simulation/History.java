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
    dev.visualengine.gpu.GpuDevice.current().requireHistory(layers, resolution);
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

  public record State(int count, int head, long[] ticks) {
    public State {
      ticks = ticks == null ? null : ticks.clone();
    }

    public long[] ticks() {
      return ticks == null ? null : ticks.clone();
    }

    public java.util.Set<Integer> validLayers(int layers, long tick) {
      if (ticks == null
          || ticks.length != layers
          || count < 0
          || count > layers
          || count == 0 && head != -1
          || count > 0 && (head < 0 || head >= layers))
        throw new IllegalArgumentException("Invalid history ring metadata");
      java.util.Set<Integer> valid = new java.util.TreeSet<>();
      long previous = Long.MAX_VALUE;
      for (int i = 0; i < count; i++) {
        int layer = (head - i + layers) % layers;
        long t = ticks[layer];
        if (t < 0 || t > tick || t % 8 != 0 || t >= previous)
          throw new IllegalArgumentException("Invalid history timestamps");
        previous = t;
        valid.add(layer);
      }
      for (int i = 0; i < layers; i++)
        if (!valid.contains(i) && ticks[i] != -1)
          throw new IllegalArgumentException("Invalid history validity mask");
      return java.util.Set.copyOf(valid);
    }
  }

  public State state() {
    return new State(count, head, ticks);
  }

  public void restore(State state, java.util.Map<String, byte[]> blobs, long tick) {
    for (int layer : state.validLayers(layers, tick))
      dev.visualengine.gpu.GpuTransfers.writeLayer(
          id, layer, resolution, blobs.get("history." + layer));
    count = state.count();
    head = state.head();
    System.arraycopy(state.ticks(), 0, ticks, 0, layers);
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
