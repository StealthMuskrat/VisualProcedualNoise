package dev.visualengine.gpu;

import static org.lwjgl.opengl.GL43C.*;

import dev.visualengine.model.Settings;
import dev.visualengine.simulation.Simulation;
import dev.visualengine.time.Playback;

public final class Renderer implements AutoCloseable {
  private Program material, finish, bloom;
  private final ShaderSources sources;
  private Settings compiledSettings = new Settings();
  private final int vao;
  private Texture scene, glow, marks;
  private Texture reference;
  private Simulation simulation;
  private final GpuTimer timer = new GpuTimer();
  private final GpuTimer tickTimer = new GpuTimer();

  public Renderer(ShaderSources sources) {
    this.sources = sources;
    vao = glGenVertexArrays();
    glBindVertexArray(vao);
    try {
      reload();
    } catch (RuntimeException e) {
      close();
      throw e;
    }
  }

  public void reload() {
    Program m = null, f = null, b = null;
    try {
      String vertex = sources.read("render/fullscreen.vert");
      m = new Program(vertex, materialSource(compiledSettings));
      m.require("viewport", "seed", "scale");
      f = new Program(vertex, sources.read("post/finish.frag"));
      f.require("scene", "exposure");
      b = new Program(vertex, sources.read("post/bloom.frag"));
      b.require("scene");
      if (simulation != null) simulation.reload();
    } catch (RuntimeException e) {
      if (m != null) m.close();
      if (f != null) f.close();
      if (b != null) b.close();
      throw e;
    }
    if (material != null) material.close();
    if (finish != null) finish.close();
    if (bloom != null) bloom.close();
    material = m;
    finish = f;
    bloom = b;
  }

  private String materialSource(Settings settings) {
    String function = "float customField(vec2 p){return 0.;}\n";
    String gradient = null;
    if (settings.recipe == Settings.Recipe.CUSTOM) {
      if (settings.graphEnabled)
        function =
            dev.visualengine.authoring.GraphCompiler.compile(
                    settings.graph, settings.effectControls)
                .function();
      else {
        var compiled =
            dev.visualengine.authoring.EffectControl.compile(
                settings.expression, settings.effectControls);
        if (compiled.root().type() != dev.visualengine.authoring.Formula.Type.FLOAT)
          throw new IllegalArgumentException("Material formulas must return a scalar");
        function = "float customField(vec2 p){return " + compiled.expression() + ";}\n";
        gradient = compiled.gradient();
      }
    }
    function +=
        "vec2 customGradient(vec2 p){"
            + (gradient != null
                ? "return " + gradient + ";"
                : "float e=max(1e-4,scale/viewport.y);return"
                      + " vec2(customField(p+vec2(e,0))-customField(p-vec2(e,0)),customField(p+vec2(0,e))-customField(p-vec2(0,e)))/(2.*e);")
            + "}\n";
    return sources
        .read("render/material.frag")
        .replace("#define recipe 0", "#define recipe " + settings.recipe.ordinal())
        .replace(
            "// @CUSTOM_FIELD@",
            dev.visualengine.authoring.EffectControl.declarations(settings.effectControls)
                + function);
  }

  private boolean structureChanged(Settings s) {
    if (compiledSettings.recipe != s.recipe
        || !compiledSettings.effectControls.equals(s.effectControls)) return true;
    if (s.recipe != Settings.Recipe.CUSTOM) return false;
    return compiledSettings.graphEnabled != s.graphEnabled
        || !java.util.Objects.equals(compiledSettings.expression, s.expression)
        || s.graphEnabled
            && (!java.util.Objects.equals(compiledSettings.graph.groups, s.graph.groups)
                || !java.util.Objects.equals(compiledSettings.graph.nodes, s.graph.nodes)
                || !java.util.Objects.equals(compiledSettings.graph.output, s.graph.output));
  }

  public void configure(Settings settings) {
    settings.validate();
    Budget.require(
        Budget.persistent(settings)
            + (scene == null ? 0 : scene.bytes() + glow.bytes())
            + (reference == null ? 0 : reference.bytes()));
    Program candidateMaterial = null;
    Simulation candidate = null;
    Texture nextMarks = null;
    try {
      if (structureChanged(settings)) {
        candidateMaterial =
            new Program(sources.read("render/fullscreen.vert"), materialSource(settings));
        candidateMaterial.require("viewport", "scale");
      }
      if (simulation == null
          || simulation.grid != settings.grid
          || simulation.particles.count != settings.particles
          || simulation.history.layers != settings.historyLayers) {
        candidate = new Simulation(sources, settings);
        nextMarks = new Texture(settings.grid, settings.grid, GL_RGBA16F);
      }
    } catch (RuntimeException e) {
      if (candidateMaterial != null) candidateMaterial.close();
      if (candidate != null) candidate.close();
      if (nextMarks != null) nextMarks.close();
      throw e;
    }
    if (candidate != null) {
      if (simulation != null) simulation.close();
      if (marks != null) marks.close();
      simulation = candidate;
      marks = nextMarks;
    }
    if (candidateMaterial != null) {
      material.close();
      material = candidateMaterial;
      compiledSettings = settings.copy();
    }
  }

  public void reset(Settings settings) {
    configure(settings);
    simulation.reset();
    marks.clear(0, 0, 0, 0);
  }

  public void tick(Settings s, double time, double aspect, double x, double y, boolean brush) {
    configure(s);
    tickTimer.begin();
    simulation.update(s, time, aspect, x, y, brush);
    if (s.feedbackEnabled || needsHistory(s) && Math.round(time / Playback.DT) % 8 == 0) {
      marks.target();
      material.use();
      uniforms(material, s, time, (int) (marks.height * aspect), marks.height, x, y, brush);
      simulation.materialUniforms(material, s);
      material.i("feedbackEnabled", 0);
      material.i("pigmentEnabled", s.pigmentEnabled ? 1 : 0);
      simulation.bind();
      triangle();
      simulation.visual(s, marks, time, aspect);
      if (needsHistory(s))
        simulation.history.capture(
            s.feedbackEnabled ? simulation.feedback.read() : marks, Math.round(time / Playback.DT));
    }
    tickTimer.end();
  }

  private static boolean needsHistory(Settings s) {
    return s.recipe == Settings.Recipe.TIME_EXCAVATION
        || s.recipe == Settings.Recipe.TEMPORAL_PAINTING
        || s.debug == 9;
  }

  private void ensureOutput(int w, int h) {
    if (scene != null && scene.width == w && scene.height == h) return;
    Budget.require(
        simulation.bytes()
            + marks.bytes()
            + Budget.display(w, h)
            + (reference == null ? 0 : reference.bytes()));
    Texture a = null, b = null;
    try {
      a = new Texture(w, h, GL_RGBA16F);
      b = new Texture(Math.max(1, w / 4), Math.max(1, h / 4), GL_RGBA16F);
      a.clamp();
      b.clamp();
    } catch (RuntimeException e) {
      if (a != null) a.close();
      if (b != null) b.close();
      throw e;
    }
    if (scene != null) scene.close();
    if (glow != null) glow.close();
    scene = a;
    glow = b;
  }

  private void triangle() {
    glBindVertexArray(vao);
    glDrawArrays(GL_TRIANGLES, 0, 3);
  }

  public void render(Settings s, double time, int w, int h, double x, double y, boolean brush) {
    configure(s);
    ensureOutput(Math.max(1, (int) (w * s.displayScale)), Math.max(1, (int) (h * s.displayScale)));
    timer.begin();
    renderInto(s, time, w, h, x, y, brush, scene, glow, 0);
    timer.end();
  }

  private void renderInto(
      Settings s,
      double time,
      int w,
      int h,
      double x,
      double y,
      boolean brush,
      Texture scene,
      Texture glow,
      int framebuffer) {
    scene.target();
    glDisable(GL_FRAMEBUFFER_SRGB);
    glDisable(GL_BLEND);
    material.use();
    uniforms(material, s, time, w, h, x, y, brush);
    simulation.materialUniforms(material, s);
    material.i("feedbackEnabled", s.feedbackEnabled && !s.graphEnabled ? 1 : 0);
    material.i("pigmentEnabled", s.pigmentEnabled ? 1 : 0);
    simulation.bind();
    triangle();
    glow.target();
    bloom.use();
    scene.bind(0);
    triangle();
    glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
    glViewport(0, 0, w, h);
    finish.use();
    scene.bind(0);
    glow.bind(1);
    finish.f("exposure", s.get("exposure"));
    finish.f("bloom", s.get("bloom"));
    finish.i("debugView", s.debug);
    finish.i("compareReference", reference == null ? 0 : 1);
    if (reference != null) reference.bind(2);
    else scene.bind(2);
    triangle();
  }

  /**
   * A full-resolution finished still. Never ticks or reallocates persistent or live output state.
   */
  public java.awt.image.BufferedImage captureStill(
      Settings s,
      double time,
      dev.visualengine.export.CaptureSize size,
      double x,
      double y,
      boolean brush) {
    s.validate();
    if (simulation == null
        || simulation.grid != s.grid
        || simulation.particles.count != s.particles
        || simulation.history.layers != s.historyLayers
        || structureChanged(s))
      throw new IllegalArgumentException("Configure the current scene before capturing");
    int w = size.width(), h = size.height();
    Budget.require(bytes() + Budget.display(w, h) + (long) w * h * 4);
    int read = glGetInteger(GL_READ_FRAMEBUFFER_BINDING),
        draw = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
    int[] viewport = new int[4];
    glGetIntegerv(GL_VIEWPORT, viewport);
    Texture a = null, b = null, output = null;
    try {
      a = new Texture(w, h, GL_RGBA16F);
      b = new Texture(Math.max(1, w / 4), Math.max(1, h / 4), GL_RGBA16F);
      a.clamp();
      b.clamp();
      output = new Texture(w, h, GL_RGBA8);
      renderInto(s, time, w, h, x, y, brush, a, b, output.framebuffer);
      return dev.visualengine.export.Capture.read(w, h);
    } finally {
      if (output != null) output.close();
      if (b != null) b.close();
      if (a != null) a.close();
      glBindFramebuffer(GL_READ_FRAMEBUFFER, read);
      glBindFramebuffer(GL_DRAW_FRAMEBUFFER, draw);
      glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
    }
  }

  public boolean hasReference() {
    return reference != null;
  }

  public dev.visualengine.export.TextureMap.Data captureMap(
      Settings s,
      double time,
      dev.visualengine.export.TextureMap.Kind kind,
      dev.visualengine.export.CaptureSize size,
      dev.visualengine.export.TextureMap.Range range,
      double x,
      double y,
      boolean brush) {
    s.validate();
    kind.validate(range);
    if (simulation == null
        || simulation.grid != s.grid
        || simulation.particles.count != s.particles
        || simulation.history.layers != s.historyLayers
        || structureChanged(s))
      throw new IllegalArgumentException("Configure the current scene before exporting maps");
    material.require("mapOutput");
    int w = size.width(), h = size.height();
    Budget.require(bytes() + (long) w * h * kind.gpuChannels() * 4);
    int read = glGetInteger(GL_READ_FRAMEBUFFER_BINDING),
        draw = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
    int[] viewport = new int[4];
    glGetIntegerv(GL_VIEWPORT, viewport);
    Texture output = null;
    try {
      output = new Texture(w, h, kind.channels == 1 ? GL_R32F : GL_RGBA32F);
      output.target();
      glDisable(GL_FRAMEBUFFER_SRGB);
      glDisable(GL_BLEND);
      material.use();
      uniforms(material, s, time, w, h, x, y, brush);
      simulation.materialUniforms(material, s);
      material.i("feedbackEnabled", s.feedbackEnabled && !s.graphEnabled ? 1 : 0);
      material.i("pigmentEnabled", s.pigmentEnabled ? 1 : 0);
      material.i("mapOutput", kind.shaderId);
      material.i("tilePreview", 0);
      simulation.bind();
      triangle();
      return new dev.visualengine.export.TextureMap.Data(
          kind, size, range, GpuTransfers.read(output));
    } finally {
      if (output != null) output.close();
      material.use();
      material.i("mapOutput", 0);
      glBindFramebuffer(GL_READ_FRAMEBUFFER, read);
      glBindFramebuffer(GL_DRAW_FRAMEBUFFER, draw);
      glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
    }
  }

  public double gpuMilliseconds() {
    return timer.milliseconds();
  }

  public double tickMilliseconds() {
    return tickTimer.milliseconds();
  }

  public String programSignature() {
    if (simulation == null) throw new IllegalStateException("Renderer is not configured");
    try {
      return java.util.HexFormat.of()
          .formatHex(
              java.security.MessageDigest.getInstance("SHA-256")
                  .digest(
                      (material.sourceHash
                              + finish.sourceHash
                              + bloom.sourceHash
                              + simulation.signature())
                          .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new AssertionError(e);
    }
  }

  public static String deviceSignature() {
    return glGetString(GL_VENDOR)
        + " / "
        + glGetString(GL_RENDERER)
        + " / "
        + glGetString(GL_VERSION);
  }

  public GpuSnapshot snapshot(Settings settings, long tick) {
    configure(settings);
    var blobs = simulation.capture();
    GpuSnapshot.Reference ref = null;
    if (reference != null) {
      ref = new GpuSnapshot.Reference(reference.width, reference.height);
      blobs.put("reference", GpuTransfers.read(reference));
    }
    var snapshot =
        new GpuSnapshot(deviceSignature(), programSignature(), simulation.state(), ref, blobs);
    snapshot.validate(settings, tick);
    return snapshot;
  }

  public Renderer stageRestore(
      Settings settings, GpuSnapshot snapshot, long tick, int width, int height) {
    snapshot.validate(settings, tick);
    if (width < 1
        || height < 1
        || width > 8192
        || height > 8192
        || (long) width * height > 33_554_432)
      throw new IllegalArgumentException("Invalid checkpoint output dimensions");
    int outputWidth = Math.max(1, (int) (width * settings.displayScale));
    int outputHeight = Math.max(1, (int) (height * settings.displayScale));
    if (!deviceSignature().equals(snapshot.device()))
      throw new IllegalArgumentException("Checkpoint graphics device or driver does not match");
    Budget.require(
        bytes()
            + Budget.persistent(settings)
            + Budget.display(outputWidth, outputHeight)
            + (snapshot.reference() == null ? 0 : snapshot.reference().bytes()));
    Renderer candidate = new Renderer(sources);
    try {
      candidate.configure(settings);
      if (!candidate.programSignature().equals(snapshot.signature()))
        throw new IllegalArgumentException(
            "Checkpoint shader programs do not match this build or shader source");
      candidate.simulation.restore(snapshot.simulation(), snapshot.blobs(), tick);
      if (snapshot.reference() != null) {
        var ref = snapshot.reference();
        candidate.reference = new Texture(ref.width(), ref.height(), GL_RGBA8);
        candidate.reference.clamp();
        GpuTransfers.write(candidate.reference, snapshot.blobs().get("reference"));
      }
      candidate.ensureOutput(outputWidth, outputHeight);
      return candidate;
    } catch (RuntimeException e) {
      candidate.close();
      throw e;
    }
  }

  public int materialProgramId() {
    return material.id;
  }

  public Simulation diagnostics() {
    return simulation;
  }

  public long bytes() {
    return (scene == null ? 0 : scene.bytes() + glow.bytes())
        + (simulation == null ? 0 : simulation.bytes() + marks.bytes())
        + (reference == null ? 0 : reference.bytes());
  }

  public void freezeFinished(int width, int height) {
    Budget.require(
        bytes() - (reference == null ? 0 : reference.bytes()) + (long) width * height * 4);
    Texture next = new Texture(width, height, GL_RGBA8);
    next.clamp();
    glBindFramebuffer(GL_READ_FRAMEBUFFER, 0);
    glBindTexture(GL_TEXTURE_2D, next.id);
    glCopyTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, 0, 0, width, height);
    if (reference != null) reference.close();
    reference = next;
  }

  public void clearReference() {
    if (reference != null) {
      reference.close();
      reference = null;
    }
  }

  public void setReference(dev.visualengine.export.Capture.Pixels image) {
    // Keep the old reference until allocation and upload of the candidate both succeed.
    Budget.require(bytes() + (long) image.width() * image.height() * 4);
    Texture candidate = new Texture(image.width(), image.height(), GL_RGBA8);
    try {
      candidate.clamp();
      GpuTransfers.write(candidate, image.rgba());
    } catch (RuntimeException e) {
      candidate.close();
      throw e;
    }
    Texture previous = reference;
    reference = candidate;
    if (previous != null) previous.close();
  }

  public static void uniforms(
      Program p, Settings s, double time, int w, int h, double x, double y, boolean brush) {
    p.i("mapOutput", 0);
    p.v2("viewport", w, h);
    p.ui("seed", (int) dev.visualengine.time.Seeds.derive(s.seed, "field", "root"));
    double a = s.loopSeconds > 0 ? time * Math.PI * 2 / s.loopSeconds : s.motionClock;
    double b = s.loopSeconds > 0 ? a * 2 : s.motionClock * .37;
    p.v2("motionPhase", Math.IEEEremainder(a, Math.PI * 2), Math.IEEEremainder(b, Math.PI * 2));
    p.f("colorPhase", Math.IEEEremainder(s.loopSeconds > 0 ? a : s.colorClock, Math.PI * 2));
    p.f("time", time);
    p.i("tileMode", s.tileMode ? 1 : 0);
    p.i("tilePreview", s.tilePreview ? 1 : 0);
    p.i("recipe", s.recipe.ordinal());
    p.i("paletteIndex", s.palette);
    p.i("debugView", s.debug);
    p.v4("pointer", x, y, s.get("radius"), brush ? 1 : 0);
    s.values.forEach(p::f);
    for (var c : s.effectControls) {
      var v = s.effectValues.get(c.id());
      String n = "user_" + c.id();
      switch (c.type()) {
        case FLOAT -> p.f(n, v.get(0));
        case VEC2 -> p.v2(n, v.get(0), v.get(1));
        case VEC3 -> p.v3(n, v.get(0), v.get(1), v.get(2));
        case VEC4 -> p.v4(n, v.get(0), v.get(1), v.get(2), v.get(3));
      }
    }
  }

  @Override
  public void close() {
    if (material != null) material.close();
    if (finish != null) finish.close();
    if (bloom != null) bloom.close();
    if (simulation != null) simulation.close();
    if (marks != null) marks.close();
    if (scene != null) scene.close();
    if (glow != null) glow.close();
    if (reference != null) reference.close();
    timer.close();
    tickTimer.close();
    glDeleteVertexArrays(vao);
  }
}
