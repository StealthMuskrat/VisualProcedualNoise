package dev.visualengine.gpu;

import static org.lwjgl.opengl.GL43C.*;

import dev.visualengine.export.*;
import dev.visualengine.model.Settings;
import dev.visualengine.presets.PresetIO;
import dev.visualengine.time.Playback;
import java.nio.file.*;
import java.util.*;

public final class ShaderCacheGpuChecks {
  public static void main(String[] args) throws Exception {
    try (var window = new dev.visualengine.platform.Window(96, 64, true)) {
      System.out.println("Shader cache GPU checks passed: " + run());
    }
  }

  private static int count;

  private static void check(boolean ok, String label) {
    count++;
    if (!ok) throw new AssertionError(label);
  }

  private static int[] image() {
    return Capture.read(96, 64).getRGB(0, 0, 96, 64, null, 0, 96);
  }

  private static boolean released(int program) {
    return !glIsProgram(program) || glGetProgrami(program, GL_DELETE_STATUS) == GL_TRUE;
  }

  private static Settings settings(String expression) {
    Settings s = new Settings();
    s.recipe = Settings.Recipe.CUSTOM;
    s.expression = expression;
    s.grid = 65;
    s.particles = 257;
    s.historyLayers = 3;
    s.feedbackEnabled = s.pigmentEnabled = true;
    return s;
  }

  private static boolean same(GpuSnapshot a, GpuSnapshot b) {
    return a.blobs().keySet().equals(b.blobs().keySet())
        && a.blobs().entrySet().stream()
            .allMatch(e -> Arrays.equals(e.getValue(), b.blobs().get(e.getKey())))
        && PresetIO.JSON
            .toJsonTree(a.simulation())
            .equals(PresetIO.JSON.toJsonTree(b.simulation()));
  }

  public static int run() throws Exception {
    Settings a = settings("sin(p.x * frequency)"), b = settings("cos(p.y * frequency)");
    try (Renderer r = new Renderer(new ShaderSources(null))) {
      r.reset(a);
      for (int tick = 1; tick <= 16; tick++) r.tick(a, tick * Playback.DT, 1.5, .2, .1, true);
      r.render(a, 16 * Playback.DT, 96, 64, .2, .1, true);
      int first = r.materialProgramId();
      int[] original = image();
      GpuSnapshot state = r.snapshot(a, 16);
      check(
          glGetProgrami(first, GL_ATTACHED_SHADERS) == 0,
          "cached linked programs release their shader compilation objects");
      r.configure(b);
      int second = r.materialProgramId();
      r.configure(a);
      r.render(a, 16 * Playback.DT, 96, 64, .2, .1, true);
      check(
          first != second
              && first == r.materialProgramId()
              && r.shaderCacheStats().variants().hits() == 1
              && Arrays.equals(original, image())
              && same(state, r.snapshot(a, 16)),
          "returning to a retained effect reuses its actual GL program and preserves paused image"
              + " and GPU state");
      var counters = r.shaderCacheStats();
      a.set("frequency", 3);
      r.render(a, 16 * Playback.DT, 96, 64, .2, .1, true);
      int[] edited = image();
      check(
          first == r.materialProgramId()
              && counters.equals(r.shaderCacheStats())
              && !Arrays.equals(original, edited),
          "numeric edits update uniforms without any cache lookup or compilation");
      try (Renderer cold = r.stageRestore(a, state, 16, 96, 64)) {
        cold.render(a, 16 * Playback.DT, 96, 64, .2, .1, true);
        check(
            Arrays.equals(edited, image()),
            "reused program uniforms match a freshly linked scene restored from the same state");
      }
      r.render(a, 16 * Playback.DT, 96, 64, .2, .1, true);
      r.captureMap(
          a,
          16 * Playback.DT,
          TextureMap.Kind.HEIGHT,
          new CaptureSize(96, 64),
          TextureMap.Kind.HEIGHT.initialRange,
          .2,
          .1,
          true);
      r.configure(b);
      r.configure(a);
      r.render(a, 16 * Playback.DT, 96, 64, .2, .1, true);
      check(
          Arrays.equals(edited, image()) && same(state, r.snapshot(a, 16)),
          "map-output uniforms cannot leak through a cached effect switch into finished artwork or"
              + " simulation state");
      for (int i = 1; i <= 8; i++)
        r.configure(settings("sin(p.x * frequency) + cos(p.y) * " + i + ".0"));
      glUseProgram(0);
      var stats = r.shaderCacheStats().variants();
      check(
          stats.entries() == 8
              && stats.evictions() == 3
              && stats.sourceBytes() <= stats.maximumSourceBytes()
              && released(first),
          "native material residency stays within both budgets and releases the LRU program: "
              + stats
              + "; previous program delete requested="
              + released(first));
    }
    try (Renderer r = new Renderer(new ShaderSources(null), false)) {
      r.configure(a);
      int first = r.materialProgramId();
      r.configure(b);
      glUseProgram(0);
      boolean oldReleased = released(first);
      long misses = r.shaderCacheStats().variants().misses();
      r.configure(a);
      check(
          oldReleased
              && !r.shaderCacheStats().retainInactive()
              && r.shaderCacheStats().variants().entries() == 1
              && r.shaderCacheStats().variants().misses() == misses + 1,
          "cache-off policy retains only the current program and recompiles a previous effect");
    }
    reloads(a, b);
    check(glGetError() == GL_NO_ERROR, "material reuse, eviction and reload leave no GL error");
    return count;
  }

  private static void reloads(Settings a, Settings b) throws Exception {
    Path source = Path.of("src/main/resources/shaders");
    Path root = Files.createTempDirectory(Path.of("build/reports"), "cache-reload-");
    try {
      try (var files = Files.walk(source)) {
        for (Path p : files.toList()) {
          Path target = root.resolve(source.relativize(p));
          if (Files.isDirectory(p)) Files.createDirectories(target);
          else Files.copy(p, target);
        }
      }
      Path fragment = root.resolve("render/material.frag"),
          particle = root.resolve("simulation/particles.comp");
      String goodFragment = Files.readString(fragment), goodParticle = Files.readString(particle);
      try (Renderer r = new Renderer(new ShaderSources(root))) {
        r.reset(a);
        r.configure(b);
        int bId = r.materialProgramId();
        r.configure(a);
        int aId = r.materialProgramId();
        for (int tick = 1; tick <= 16; tick++) r.tick(a, tick * Playback.DT, 1.5, 0, 0, true);
        r.render(a, 16 * Playback.DT, 96, 64, 0, 0, true);
        GpuSnapshot state = r.snapshot(a, 16);
        int[] original = image();
        Files.writeString(fragment, "#version 430 core\ninvalid material;");
        boolean rejected = false;
        try {
          r.configure(b);
        } catch (IllegalArgumentException expected) {
          rejected = true;
        }
        check(
            rejected
                && r.materialProgramId() == aId
                && r.shaderCacheStats().variants().failures() == 1,
            "a changed broken source cannot be hidden by an old cached effect or replace the"
                + " working program");
        var before = r.shaderCacheStats();
        rejected = false;
        try {
          r.reload();
        } catch (IllegalArgumentException expected) {
          rejected = true;
        }
        r.render(a, 16 * Playback.DT, 96, 64, 0, 0, true);
        check(
            rejected
                && before.equals(r.shaderCacheStats())
                && Arrays.equals(original, image())
                && same(state, r.snapshot(a, 16)),
            "failed F5 preserves the complete previous cache, finished image and simulation bytes");
        Files.writeString(fragment, goodFragment);
        r.configure(b);
        check(
            r.materialProgramId() == bId,
            "the previous inactive program remains reusable after a failed reload");
        r.configure(a);
        Files.writeString(particle, "#version 430 core\ninvalid particle;");
        Settings resized = b.copy();
        resized.particles = 258;
        long hits = r.shaderCacheStats().variants().hits();
        rejected = false;
        try {
          r.configure(resized);
        } catch (IllegalArgumentException expected) {
          rejected = true;
        }
        r.render(a, 16 * Playback.DT, 96, 64, 0, 0, true);
        check(
            rejected
                && r.materialProgramId() == aId
                && r.shaderCacheStats().variants().hits() == hits + 1
                && glGetProgrami(bId, GL_DELETE_STATUS) == GL_FALSE
                && Arrays.equals(original, image())
                && same(state, r.snapshot(a, 16)),
            "failed resource candidate preserves both the active material and its retained cache"
                + " hit");
        before = r.shaderCacheStats();
        rejected = false;
        try {
          r.reload();
        } catch (IllegalArgumentException expected) {
          rejected = true;
        }
        check(
            rejected
                && before.equals(r.shaderCacheStats())
                && r.programSignature().equals(state.signature())
                && same(state, r.snapshot(a, 16)),
            "late particle compile failure rolls back staged material, finishing and simulation"
                + " programs together");
        Files.writeString(particle, goodParticle);
        r.reload();
        glUseProgram(0);
        check(
            r.shaderCacheStats().generation() == before.generation() + 1
                && r.shaderCacheStats().variants().entries() == 1
                && r.shaderCacheStats().variants().misses() == 1
                && released(aId)
                && released(bId),
            "successful F5 publishes a fresh generation and releases every older material variant");
        r.render(a, 16 * Playback.DT, 96, 64, 0, 0, true);
        check(
            Arrays.equals(original, image()) && same(state, r.snapshot(a, 16)),
            "successful unchanged-source reload preserves image and developed GPU state");
      }
    } finally {
      try (var files = Files.walk(root)) {
        for (Path p : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
      }
    }
  }
}
