package dev.visualengine.gpu;

import static org.lwjgl.opengl.GL43C.*;

/**
 * Bindings touched by resource construction; restored before a candidate is committed or closed.
 */
final class AllocationBindings implements AutoCloseable {
  private final int read = glGetInteger(GL_READ_FRAMEBUFFER_BINDING),
      draw = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING),
      active = glGetInteger(GL_ACTIVE_TEXTURE),
      texture = glGetInteger(GL_TEXTURE_BINDING_2D),
      array = glGetInteger(GL_TEXTURE_BINDING_2D_ARRAY),
      storage = glGetInteger(GL_SHADER_STORAGE_BUFFER_BINDING),
      vao = glGetInteger(GL_VERTEX_ARRAY_BINDING);
  private final int[] viewport = new int[4];

  AllocationBindings() {
    glGetIntegerv(GL_VIEWPORT, viewport);
  }

  @Override
  public void close() {
    glBindFramebuffer(GL_READ_FRAMEBUFFER, read);
    glBindFramebuffer(GL_DRAW_FRAMEBUFFER, draw);
    glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
    glActiveTexture(active);
    glBindTexture(GL_TEXTURE_2D, texture);
    glBindTexture(GL_TEXTURE_2D_ARRAY, array);
    glBindBuffer(GL_SHADER_STORAGE_BUFFER, storage);
    glBindVertexArray(vao);
  }
}
