package dev.visualengine.gpu;

public final class PingPong implements AutoCloseable {
  private final Texture[] textures = new Texture[2];
  private int index;

  public PingPong(int width, int height, int format) {
    try {
      textures[0] = new Texture(width, height, format);
      textures[1] = new Texture(width, height, format);
    } catch (RuntimeException e) {
      close();
      throw e;
    }
  }

  public Texture read() {
    return textures[index];
  }

  public Texture write() {
    return textures[index ^ 1];
  }

  public void swap() {
    index ^= 1;
  }

  public void clear(float r, float g, float b, float a) {
    for (var t : textures) t.clear(r, g, b, a);
    index = 0;
  }

  public long bytes() {
    return textures[0].bytes() * 2;
  }

  @Override
  public void close() {
    for (var t : textures) if (t != null) t.close();
  }
}
