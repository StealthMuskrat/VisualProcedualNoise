package dev.visualengine.simulation;

import static org.lwjgl.opengl.GL43C.*;

import dev.visualengine.gpu.*;
import dev.visualengine.model.Settings;
import dev.visualengine.time.Playback;

public final class Particles implements AutoCloseable {
  public final int count;
  public final int buffer;
  private final int vao;
  public final PingPong trails;
  private Program update, draw, fade;
  private boolean initialize = true;

  public Particles(ShaderSources sources, int count, int resolution) {
    this.count = count;
    buffer = glGenBuffers();
    vao = glGenVertexArrays();
    glBindBuffer(GL_SHADER_STORAGE_BUFFER, buffer);
    glBufferData(GL_SHADER_STORAGE_BUFFER, Math.max(32L, count * 32L), GL_DYNAMIC_DRAW);
    PingPong t = null;
    try {
      t = new PingPong(resolution, resolution, GL_RGBA16F);
      trails = t;
      reload(sources);
    } catch (RuntimeException e) {
      if (t != null) t.close();
      glDeleteBuffers(buffer);
      glDeleteVertexArrays(vao);
      throw e;
    }
  }

  public void reload(ShaderSources sources) {
    Program u = null, d = null, f = null;
    try {
      u = Program.compute(sources.read("simulation/particles.comp"));
      d = new Program(sources.read("render/particles.vert"), sources.read("render/particles.frag"));
      f = new Program(sources.read("render/fullscreen.vert"), sources.read("post/trail.frag"));
    } catch (RuntimeException e) {
      if (u != null) u.close();
      if (d != null) d.close();
      if (f != null) f.close();
      throw e;
    }
    if (update != null) update.close();
    if (draw != null) draw.close();
    if (fade != null) fade.close();
    update = u;
    draw = d;
    fade = f;
  }

  public record State(boolean initialize, int trailsIndex) {
    public void validate() {
      if (trailsIndex < 0 || trailsIndex > 1)
        throw new IllegalArgumentException("Invalid trail index");
    }
  }

  public State state() {
    return new State(initialize, trails.index());
  }

  public void restore(State state, java.util.Map<String, byte[]> blobs) {
    state.validate();
    GpuTransfers.writeBuffer(buffer, blobs.get("particles"));
    for (int side = 0; side < 2; side++)
      GpuTransfers.write(trails.side(side), blobs.get("trails." + side));
    initialize = state.initialize();
    trails.restoreIndex(state.trailsIndex());
  }

  public String signature() {
    return update.sourceHash + draw.sourceHash + fade.sourceHash;
  }

  public void reset() {
    initialize = true;
    trails.clear(0, 0, 0, 0);
  }

  public void tick(Settings s, Texture flow, double time, double aspect) {
    if (count == 0) return;
    update.use();
    update.i("count", count);
    update.i("initialize", initialize ? 1 : 0);
    update.f("dt", Playback.DT);
    update.f("aspect", aspect);
    update.ui("seed", (int) dev.visualengine.time.Seeds.derive(s.seed, "particles", "respawn"));
    flow.bind(0);
    glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, buffer);
    glDispatchCompute((count + 255) / 256, 1, 1);
    glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
    initialize = false;
    trails.write().target();
    fade.use();
    fade.f("dt", Playback.DT);
    trails.read().bind(0);
    glBindVertexArray(vao);
    glDrawArrays(GL_TRIANGLES, 0, 3);
    draw.use();
    draw.f("aspect", aspect);
    draw.f("colorPhase", Math.IEEEremainder(s.colorClock, Math.PI * 2));
    draw.i("paletteIndex", s.palette);
    glEnable(GL_PROGRAM_POINT_SIZE);
    glEnable(GL_BLEND);
    glBlendFunc(GL_ONE, GL_ONE);
    glDrawArrays(GL_POINTS, 0, count);
    glDisable(GL_BLEND);
    trails.swap();
  }

  public long bytes() {
    return Math.max(32, count * 32L) + trails.bytes();
  }

  @Override
  public void close() {
    trails.close();
    glDeleteBuffers(buffer);
    glDeleteVertexArrays(vao);
    if (update != null) update.close();
    if (draw != null) draw.close();
    if (fade != null) fade.close();
  }
}
