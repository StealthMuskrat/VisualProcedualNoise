package dev.visualengine;

import static org.lwjgl.opengl.GL43C.*;

import dev.visualengine.authoring.*;
import dev.visualengine.export.Capture;
import dev.visualengine.gpu.*;
import dev.visualengine.model.*;
import dev.visualengine.platform.Window;
import dev.visualengine.simulation.*;
import dev.visualengine.time.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import org.lwjgl.system.MemoryUtil;

/** Real OpenGL contracts. Linux uses Mesa EGL; Windows requires a capable native driver. */
public final class GpuChecks {
  private static int count;

  private static void check(boolean condition, String label) {
    count++;
    if (!condition) throw new AssertionError(label);
  }

  private static int[] image() {
    return Capture.read(96, 64).getRGB(0, 0, 96, 64, null, 0, 96);
  }

  private static float[] read(Texture texture, int channels) {
    FloatBuffer buffer = MemoryUtil.memAllocFloat(texture.width * texture.height * channels);
    try {
      glMemoryBarrier(GL_TEXTURE_UPDATE_BARRIER_BIT);
      glBindTexture(GL_TEXTURE_2D, texture.id);
      glGetTexImage(
          GL_TEXTURE_2D,
          0,
          channels == 1 ? GL_RED : channels == 2 ? GL_RG : GL_RGBA,
          GL_FLOAT,
          buffer);
      float[] values = new float[buffer.capacity()];
      buffer.get(values);
      return values;
    } finally {
      MemoryUtil.memFree(buffer);
    }
  }

  private static Settings settings() {
    Settings s = new Settings();
    s.grid = 65;
    s.particles = 257;
    s.historyLayers = 3;
    return s;
  }

  private static Settings evolve(Renderer renderer, Settings s, int ticks) {
    ControlSignals signals = new ControlSignals();
    Settings active = s;
    for (int tick = 1; tick <= ticks; tick++) {
      active = signals.tick(s, tick * Playback.DT);
      renderer.tick(active, tick * Playback.DT, 1.5, 0, 0, false);
    }
    return active;
  }

  public static void main(String[] args) throws Exception {
    try (Window window = new Window(96, 64, true);
        Renderer renderer = new Renderer(new ShaderSources(null))) {
      Settings s = settings();
      renderer.reset(s);
      Settings active = evolve(renderer, s, 16);
      renderer.render(active, 16 * Playback.DT, 96, 64, 0, 0, false);
      int[] original = image();
      renderer.render(active, 16 * Playback.DT, 96, 64, 0, 0, false);
      check(Arrays.equals(original, image()), "paused image unchanged");
      renderer.reset(s);
      active = evolve(renderer, s, 16);
      renderer.render(active, 16 * Playback.DT, 96, 64, 0, 0, false);
      check(Arrays.equals(original, image()), "same seed/tick reset reproduces image");
      long memory = renderer.bytes();
      Settings bad = s.copy();
      bad.grid = 100000;
      boolean rejected = false;
      try {
        renderer.configure(bad);
      } catch (IllegalArgumentException e) {
        rejected = true;
      }
      check(
          rejected && renderer.bytes() == memory, "invalid allocation preserves active resources");
      renderer.freezeFinished(96, 64);
      s.palette = 2;
      renderer.render(s, 0, 96, 64, 0, 0, false);
      int[] comparison = image();
      check(
          original[32 * 96 + 12] == comparison[32 * 96 + 12],
          "A/B frozen left preserves encoded pixel");
      renderer.clearReference();
      s = settings();
      s.recipe = Settings.Recipe.CUSTOM;
      s.graphEnabled = true;
      s.graph = AuthoringChecks.noiseGraph();
      renderer.configure(s);
      renderer.render(s, 0, 96, 64, 0, 0, false);
      int[] ungrouped = image();
      s.graph = GraphTemplates.capture(s.graph, "local.noise", "1.0.0", List.of());
      renderer.configure(s);
      renderer.render(s, 0, 96, 64, 0, 0, false);
      check(
          Arrays.equals(ungrouped, image()),
          "group encapsulation preserves seeded rendered output");
      s.installEffectControls(List.of(AuthoringChecks.scalar()), Map.of());
      s.graph = AuthoringChecks.groupGraph();
      renderer.configure(s);
      renderer.render(s, 0, 96, 64, 0, 0, false);
      check(glGetError() == GL_NO_ERROR, "typed group expression shader links and renders");
      Set<Integer> signatures = new HashSet<>();
      s = settings();
      s.recipe = Settings.Recipe.CUSTOM;
      s.installEffectControls(
          List.of(AuthoringChecks.scalar(), AuthoringChecks.vector()), Map.of());
      s.expression = "amplitude*sin(p.x*frequency)+dot(tint,vec3(.2,.3,.5))";
      renderer.configure(s);
      int program = renderer.materialProgramId();
      renderer.render(s, 0, 96, 64, 0, 0, false);
      int[] uniformBefore = image();
      s.setEffectValue("amplitude", List.of(2.));
      s.setEffectValue("tint", List.of(.9, .1, .2));
      renderer.render(s, 0, 96, 64, 0, 0, false);
      check(
          program == renderer.materialProgramId() && !Arrays.equals(uniformBefore, image()),
          "custom uniform values change image without relinking");
      for (var recipe : Settings.Recipe.values()) {
        s = settings();
        Recipes.apply(s, recipe);
        renderer.reset(s);
        active = evolve(renderer, s, 16);
        renderer.render(active, 16 * Playback.DT, 96, 64, 0, 0, false);
        int[] pixels = image();
        signatures.add(Arrays.hashCode(pixels));
        check(Arrays.stream(pixels).anyMatch(c -> (c & 0xffffff) != 0), "visible " + recipe);
        Path gallery = Path.of("build/reports/gpu-gallery");
        Capture.write(
            gallery.resolve(recipe.name().toLowerCase(java.util.Locale.ROOT) + ".png"),
            Capture.read(96, 64),
            active,
            16,
            glGetString(GL_RENDERER));
        check(glGetError() == GL_NO_ERROR, "GL clean " + recipe);
      }
      check(signatures.size() >= 10, "recipes have distinct rendered outputs");
      s = settings();
      s.recipe = Settings.Recipe.CUSTOM;
      s.graphEnabled = true;
      s.graph = new EffectGraph();
      s.graph.nodes =
          new ArrayList<>(
              List.of(
                  new EffectGraph.Node(
                      "coord", EffectGraph.Kind.COORDINATES, List.of(), "0", 0, 0, false, "", ""),
                  new EffectGraph.Node(
                      "distance",
                      EffectGraph.Kind.DISTANCE,
                      List.of("coord"),
                      "0",
                      0,
                      0,
                      false,
                      "",
                      ""),
                  new EffectGraph.Node(
                      "mask",
                      EffectGraph.Kind.MASK,
                      List.of("distance"),
                      "0",
                      0,
                      0,
                      false,
                      "",
                      "")));
      s.graph.output = "mask";
      renderer.configure(s);
      renderer.render(s, 0, 96, 64, 0, 0, false);
      check(image().length == 6144, "typed graph shader links and renders");
      Simulation sim = renderer.diagnostics();
      check(
          Budget.persistent(s) == sim.bytes() + 65L * 65 * 8,
          "memory estimate matches resource accounting");
      s = settings();
      s.pigmentEnabled = true;
      s.set("flow", 0);
      s.set("decay", 0);
      s.set("injection", 0);
      renderer.reset(s);
      sim = renderer.diagnostics();
      sim.pigment.clear(.25f, .5f, .75f, 0);
      sim.update(s, Playback.DT, 1, 0, 0, false);
      float[] pigment = read(sim.pigment.read(), 4);
      double error = 0;
      for (int i = 0; i < pigment.length; i += 4)
        error =
            Math.max(
                error,
                Math.max(
                    Math.abs(pigment[i] - .25),
                    Math.max(Math.abs(pigment[i + 1] - .5), Math.abs(pigment[i + 2] - .75))));
      check(error < .001, "constant pigment preserved without sources/decay/flow");
      transportDirection(sim, s);
      s = settings();
      s.reactionEnabled = true;
      renderer.reset(s);
      sim = renderer.diagnostics();
      sim.update(s, Playback.DT, 1, 0, 0, false);
      float[] seedA = read(sim.chemicals.read(), 2);
      s.seed++;
      sim.reset();
      sim.update(s, Playback.DT, 1, 0, 0, false);
      check(
          !Arrays.equals(seedA, read(sim.chemicals.read(), 2)),
          "root seed changes chemical initialization");
      for (double[] pair : new double[][] {{.01, .03}, {.08, .075}, {.0367, .0649}}) {
        s.set("feed", pair[0]);
        s.set("kill", pair[1]);
        sim.reset();
        for (int tick = 1; tick <= 256; tick++) sim.update(s, tick * Playback.DT, 1, 0, 0, false);
        float[] chemicals = read(sim.chemicals.read(), 2);
        check(allFiniteRange(chemicals, 0, 1), "finite bounded reaction " + Arrays.toString(pair));
      }
      s = settings();
      s.particlesEnabled = true;
      renderer.reset(s);
      sim = renderer.diagnostics();
      for (int tick = 1; tick <= 300; tick++) sim.update(s, tick * Playback.DT, 1.5, 0, 0, false);
      float[] trails = read(sim.particles.trails.read(), 4);
      glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT);
      glBindBuffer(GL_SHADER_STORAGE_BUFFER, sim.particles.buffer);
      IntBuffer counters = MemoryUtil.memAllocInt(s.particles * 8);
      boolean respawn = false;
      try {
        glGetBufferSubData(GL_SHADER_STORAGE_BUFFER, 0, counters);
        for (int i = 0; i < s.particles; i++) respawn |= counters.get(i * 8 + 4) > 1;
      } finally {
        MemoryUtil.memFree(counters);
      }
      check(respawn, "particle respawn counters advance");
      sim.reset();
      for (int tick = 1; tick <= 300; tick++) sim.update(s, tick * Playback.DT, 1.5, 0, 0, false);
      check(
          Arrays.equals(trails, read(sim.particles.trails.read(), 4)),
          "particle trails and respawns reproduce after reset");
      s = settings();
      Recipes.apply(s, Settings.Recipe.TIME_EXCAVATION);
      renderer.reset(s);
      check(renderer.diagnostics().history.count() == 0, "history invalid after reset");
      evolve(renderer, s, 64);
      History history = renderer.diagnostics().history;
      check(
          history.count() == 3 && Arrays.stream(history.ticks()).max().orElse(-1) == 64,
          "history ring bounds and timestamps");
      reloadFailure(window);
      check(glGetError() == GL_NO_ERROR, "no final GL error");
      System.out.println("GPU checks passed: " + count + " | " + glGetString(GL_RENDERER));
    }
  }

  private static boolean allFiniteRange(float[] values, float minimum, float maximum) {
    for (float x : values) if (!Float.isFinite(x) || x < minimum || x > maximum) return false;
    return true;
  }

  private static void transportDirection(Simulation sim, Settings s) {
    FloatBuffer input = MemoryUtil.memAllocFloat(65 * 65 * 4);
    try {
      for (int y = 0; y < 65; y++)
        for (int x = 0; x < 65; x++) {
          float v = (float) Math.exp(-((x - 20.) * (x - 20.) + (y - 32.) * (y - 32.)) / 8);
          input.put(v).put(0).put(0).put(0);
        }
      input.flip();
      glBindTexture(GL_TEXTURE_2D, sim.pigment.read().id);
      glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, 65, 65, GL_RGBA, GL_FLOAT, input);
      sim.flow.clear(.2f, 0, 0, 0);
      try (Program p = Program.compute(new ShaderSources(null).read("simulation/pigment.comp"))) {
        p.use();
        p.f("dt", .25);
        p.f("aspect", 1);
        p.f("decay", 0);
        p.f("injection", 0);
        p.v4("pointer", 0, 0, .1, 0);
        p.v2("previousPointer", 0, 0);
        p.i("recipe", 0);
        sim.pigment.read().bind(0);
        sim.flow.bind(1);
        sim.pigment.write().image(0);
        glDispatchCompute(9, 9, 1);
        glMemoryBarrier(GL_TEXTURE_FETCH_BARRIER_BIT | GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);
        float[] result = read(sim.pigment.write(), 4);
        double mass = 0, moment = 0;
        for (int y = 0; y < 65; y++)
          for (int x = 0; x < 65; x++) {
            double v = result[(y * 65 + x) * 4];
            mass += v;
            moment += v * x;
          }
        check(
            moment / mass > 21.3 && moment / mass < 21.9,
            "positive flow advects impulse right in domain units");
      }
    } finally {
      MemoryUtil.memFree(input);
    }
  }

  private static void reloadFailure(Window window) throws Exception {
    Path root = Files.createTempDirectory("shader-fallback-");
    Path source = Path.of("src/main/resources/shaders");
    try (var paths = Files.walk(source)) {
      for (Path p : paths.toList()) {
        Path target = root.resolve(source.relativize(p));
        if (Files.isDirectory(p)) Files.createDirectories(target);
        else Files.copy(p, target);
      }
    }
    try (Renderer r = new Renderer(new ShaderSources(root))) {
      Settings s = settings();
      r.reset(s);
      r.render(s, 0, 96, 64, 0, 0, false);
      int[] before = image();
      long memory = r.bytes();
      Files.writeString(root.resolve("render/material.frag"), "#version 430 core\ninvalid shader;");
      boolean rejected = false;
      try {
        r.reload();
      } catch (IllegalArgumentException e) {
        rejected = true;
      }
      r.render(s, 0, 96, 64, 0, 0, false);
      check(
          rejected && Arrays.equals(before, image()) && memory == r.bytes(),
          "failed reload preserves image and resource budget");
    } finally {
      try (var paths = Files.walk(root)) {
        for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
      }
    }
  }
}
