package dev.visualengine;

import static org.lwjgl.opengl.GL43C.*;

import dev.visualengine.authoring.*;
import dev.visualengine.export.Capture;
import dev.visualengine.export.CaptureSize;
import dev.visualengine.export.TextureMap;
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
      window.applyPresentation(new dev.visualengine.platform.Presentation(true, 60));
      check(
          window.presentation().equals(dev.visualengine.platform.Presentation.OFFLINE),
          "headless context forces unsynchronized uncapped presentation");
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
      checkpoints();
      stillCaptures();
      savedReferences();
      textureMaps();
      morphs();
      count += GaborGpuChecks.run();
      count += PeriodicityGpuChecks.run();
      count += BenchmarkGpuChecks.run();
      reloadFailure(window);
      check(glGetError() == GL_NO_ERROR, "no final GL error");
      System.out.println("GPU checks passed: " + count + " | " + glGetString(GL_RENDERER));
    }
  }

  private static void morphs() {
    Settings a = settings();
    Recipes.apply(a, Settings.Recipe.TIME_EXCAVATION);
    a.pigmentEnabled = a.reactionEnabled = a.feedbackEnabled = a.particlesEnabled = true;
    Settings b = a.copy();
    b.set("warp", 1.8);
    b.set("exposure", 3);
    b.set("motion", 1.4);
    ControlSignals signals = new ControlSignals();
    try (Renderer r = new Renderer(new ShaderSources(null))) {
      r.reset(a);
      for (int tick = 1; tick <= 32; tick++) checkpointTick(r, signals, a, tick);
      double time = 32 * Playback.DT;
      r.render(signals.frame(a), time, 96, 64, .1, .2, false);
      int[] original = image();
      GpuSnapshot before = r.snapshot(a, 32);
      String signature = r.programSignature();
      long bytes = r.bytes();
      Settings middle = PresetMorph.blend(a, b, a, .5);
      r.configure(middle);
      signals.snap(middle);
      r.render(signals.frame(middle), time, 96, 64, .1, .2, false);
      check(!Arrays.equals(original, image()), "numeric morph changes rendered composition");
      GpuSnapshot after = r.snapshot(middle, 32);
      check(
          sameBlobs(before, after)
              && dev.visualengine.presets.PresetIO.JSON
                  .toJsonTree(before.simulation())
                  .equals(dev.visualengine.presets.PresetIO.JSON.toJsonTree(after.simulation()))
              && r.bytes() == bytes
              && signature.equals(r.programSignature()),
          "morph preserves every persistent GPU byte, simulation metadata and program source");
      Settings restored = PresetMorph.blend(a, b, middle, 0);
      r.configure(restored);
      signals.snap(restored);
      r.render(signals.frame(restored), time, 96, 64, .1, .2, false);
      check(Arrays.equals(original, image()), "returning to endpoint reproduces paused frame");
      Settings incompatible = b.copy();
      incompatible.palette++;
      boolean rejected = false;
      try {
        r.configure(PresetMorph.blend(a, incompatible, restored, .5));
      } catch (IllegalArgumentException expected) {
        rejected = true;
      }
      r.render(signals.frame(restored), time, 96, 64, .1, .2, false);
      check(
          rejected
              && Arrays.equals(original, image())
              && sameBlobs(before, r.snapshot(restored, 32)),
          "incompatible morph preserves live image and state");
      checkpointTick(r, signals, restored, 33);
      check(
          !sameBlobs(before, r.snapshot(restored, 33)),
          "simulation continues after morph without reset");
      a = settings();
      a.recipe = Settings.Recipe.CUSTOM;
      a.installEffectControls(List.of(AuthoringChecks.scalar()), Map.of());
      a.expression = "sin(p.x * amplitude) + sin(p.y)";
      b = a.copy();
      b.setEffectValue("amplitude", List.of(2.));
      r.configure(a);
      r.render(a, 0, 96, 64, 0, 0, false);
      original = image();
      signature = r.programSignature();
      middle = PresetMorph.blend(a, b, a, .5);
      r.configure(middle);
      r.render(middle, 0, 96, 64, 0, 0, false);
      check(
          !Arrays.equals(original, image()) && signature.equals(r.programSignature()),
          "custom-control morph changes image with identical program source");
    }
  }

  private static void checkpoints() {
    Settings s = settings();
    Recipes.apply(s, Settings.Recipe.TIME_EXCAVATION);
    s.pigmentEnabled = s.feedbackEnabled = s.reactionEnabled = s.particlesEnabled = true;
    s.debug = 7;
    s.installEffectControls(List.of(AuthoringChecks.scalar()), Map.of());
    ControlSignals signals = new ControlSignals();
    try (Renderer r = new Renderer(new ShaderSources(null))) {
      r.reset(s);
      GpuSnapshot empty = r.snapshot(s, 0);
      check(
          empty.blobs().get("particles").length == 0
              && empty.blobs().keySet().stream().noneMatch(n -> n.startsWith("history.")),
          "reset snapshot omits uninitialized particles and history layers");
      try (Renderer restored = r.stageRestore(s, empty, 0, 96, 64)) {
        check(sameBlobs(empty, restored.snapshot(s, 0)), "reset state restores exactly");
      }
      for (int tick = 1; tick <= 303; tick++) {
        if (tick == 301) {
          s.set("warp", 1.7);
          s.setEffectValue("amplitude", List.of(2.));
        }
        checkpointTick(r, signals, s, tick);
      }
      r.render(signals.frame(s), 303 * Playback.DT, 96, 64, .2, .1, true);
      int[] before = image();
      r.freezeFinished(96, 64);
      glActiveTexture(GL_TEXTURE10);
      glBindTexture(GL_TEXTURE_2D, r.diagnostics().flow.id);
      glPixelStorei(GL_PACK_ALIGNMENT, 8);
      GpuSnapshot saved = r.snapshot(s, 303);
      check(
          glGetInteger(GL_ACTIVE_TEXTURE) == GL_TEXTURE10
              && glGetInteger(GL_TEXTURE_BINDING_2D) == r.diagnostics().flow.id
              && glGetInteger(GL_PACK_ALIGNMENT) == 8,
          "snapshot preserves texture bindings and pixel transfer state");
      glPixelStorei(GL_PACK_ALIGNMENT, 4);
      ControlSignals.State controls = signals.snapshot(s);
      check(
          controls.effectSmooth().get("amplitude").getFirst() < 2
              && controls.smooth().get("warp") < 1.7,
          "checkpoint includes unfinished smoothing");
      check(
          saved.simulation().history().count() == 3
              && saved.simulation().history().head() == 0
              && saved.simulation().pigmentIndex() == 1,
          "checkpoint includes wrapped history and odd ping-pong indices");
      r.render(signals.frame(s), 303 * Playback.DT, 96, 64, .2, .1, true);
      check(Arrays.equals(before, image()), "readback leaves paused image unchanged");
      for (int tick = 304; tick <= 312; tick++) checkpointTick(r, signals, s, tick);
      r.render(signals.frame(s), 312 * Playback.DT, 96, 64, .2, .1, true);
      int[] expected = image();
      GpuSnapshot end = r.snapshot(s, 312);
      try (Renderer restored = r.stageRestore(s, saved, 303, 96, 64)) {
        ControlSignals resumedSignals = new ControlSignals();
        resumedSignals.restore(controls, s);
        check(sameBlobs(saved, restored.snapshot(s, 303)), "all saved GPU bytes restored exactly");
        for (int tick = 304; tick <= 312; tick++) checkpointTick(restored, resumedSignals, s, tick);
        restored.render(resumedSignals.frame(s), 312 * Playback.DT, 96, 64, .2, .1, true);
        GpuSnapshot resumed = restored.snapshot(s, 312);
        check(
            Arrays.equals(expected, image()) && sameBlobs(end, resumed),
            "checkpoint continuation reproduces image and every persistent GPU resource");
        check(
            signals.snapshot(s).equals(resumedSignals.snapshot(s)),
            "phase clocks and smoothing resume exactly");
      }
      boolean rejected = false;
      try (Renderer unused =
          r.stageRestore(
              s,
              new GpuSnapshot(
                  saved.device(),
                  "0".repeat(64),
                  saved.simulation(),
                  saved.reference(),
                  saved.blobs()),
              303,
              96,
              64)) {
        throw new AssertionError("incompatible shader snapshot accepted");
      } catch (IllegalArgumentException expectedError) {
        rejected = true;
      }
      r.render(signals.frame(s), 312 * Playback.DT, 96, 64, .2, .1, true);
      check(
          rejected && Arrays.equals(expected, image()) && sameBlobs(end, r.snapshot(s, 312)),
          "failed staged restore preserves active image and resources");
      Map<String, byte[]> corrupt = new LinkedHashMap<>(saved.blobs());
      byte[] badFlow = corrupt.get("flow").clone();
      ByteBuffer.wrap(badFlow).order(ByteOrder.LITTLE_ENDIAN).putFloat(Float.NaN);
      corrupt.put("flow", badFlow);
      rejected = false;
      try {
        new GpuSnapshot(
                saved.device(), saved.signature(), saved.simulation(), saved.reference(), corrupt)
            .validate(s, 303);
      } catch (IllegalArgumentException expectedError) {
        rejected = true;
      }
      check(rejected, "nonfinite checkpoint data rejected before upload");
    }
  }

  private static void checkpointTick(Renderer r, ControlSignals signals, Settings s, int tick) {
    double x = .3 * Math.sin(tick * .013), y = .2 * Math.cos(tick * .017);
    r.tick(signals.tick(s, tick * Playback.DT), tick * Playback.DT, 1.5, x, y, tick % 23 < 17);
  }

  private static boolean sameBlobs(GpuSnapshot a, GpuSnapshot b) {
    return a.blobs().keySet().equals(b.blobs().keySet())
        && a.blobs().entrySet().stream()
            .allMatch(e -> Arrays.equals(e.getValue(), b.blobs().get(e.getKey())));
  }

  private static boolean allFiniteRange(float[] values, float minimum, float maximum) {
    for (float x : values) if (!Float.isFinite(x) || x < minimum || x > maximum) return false;
    return true;
  }

  private static void textureMaps() {
    Settings s = settings();
    s.recipe = Settings.Recipe.CUSTOM;
    s.expression = "p.x";
    s.set("scale", 1);
    s.set("height", .5);
    CaptureSize size = new CaptureSize(96, 64);
    try (Renderer r = new Renderer(new ShaderSources(null))) {
      r.reset(s);
      r.render(s, 0, 96, 64, 0, 0, false);
      int[] before = image();
      long memory = r.bytes();
      int program = r.materialProgramId();
      var height =
          r.captureMap(
              s, 0, TextureMap.Kind.HEIGHT, size, new TextureMap.Range(-1, 1), 0, 0, false);
      var encoded = TextureMap.encode(height).image().getRaster();
      double h = (((8.5 / 96) * 2 - 1) * 1.5) * .5;
      check(
          Math.abs(encoded.getSample(8, 32, 0) - Math.round((h + 1) * .5 * 65535)) <= 1,
          "height map exports the known signed plane with declared linear range");
      var normal =
          r.captureMap(
              s, 0, TextureMap.Kind.NORMAL, size, TextureMap.Kind.NORMAL.initialRange, 0, 0, false);
      var normalImage = TextureMap.encode(normal).image().getRaster();
      check(
          Math.abs(
                      normalImage.getSample(32, 32, 0)
                          - Math.round((1 - .5 / Math.sqrt(1.25)) * .5 * 65535))
                  <= 1
              && normalImage.getSample(32, 32, 1) == 32768
              && Math.abs(
                      normalImage.getSample(32, 32, 2)
                          - Math.round((1 + 1 / Math.sqrt(1.25)) * .5 * 65535))
                  <= 1,
          "normal map matches the analytical plane in field-domain units");
      var mask =
          TextureMap.encode(
                  r.captureMap(
                      s,
                      0,
                      TextureMap.Kind.MASK,
                      size,
                      TextureMap.Kind.MASK.initialRange,
                      0,
                      0,
                      false))
              .image()
              .getRaster();
      check(
          mask.getSample(8, 32, 0) == 0 && mask.getSample(88, 32, 0) == 65535,
          "zero-level mask separates negative and positive field");
      check(
          Arrays.equals(before, image()) && r.bytes() == memory && r.materialProgramId() == program,
          "map output leaves live pixels, allocations and linked program intact");
      s.expression = "p.y";
      r.configure(s);
      var vertical =
          TextureMap.encode(
                  r.captureMap(
                      s, 0, TextureMap.Kind.HEIGHT, size, new TextureMap.Range(-1, 1), 0, 0, false))
              .image()
              .getRaster();
      check(
          vertical.getSample(32, 0, 0) > vertical.getSample(32, 63, 0),
          "map image origin is top-left with artistic Y upward");
      s.expression = "1e10 * 1e10 * p.x";
      r.configure(s);
      var steep =
          r.captureMap(
              s, 0, TextureMap.Kind.NORMAL, size, TextureMap.Kind.NORMAL.initialRange, 0, 0, false);
      var steepValues = ByteBuffer.wrap(steep.floats()).order(ByteOrder.nativeOrder());
      int center = (32 * 96 + 32) * 16;
      check(
          Math.abs(steepValues.getFloat(center) + 1) < 1e-6
              && Math.abs(steepValues.getFloat(center + 4)) < 1e-6
              && Float.isFinite(steepValues.getFloat(center + 8)),
          "extremely steep finite fields retain valid unit normals");
      s.expression = "p.y";
      r.configure(s);
      s.set("role", 1);
      var surface =
          r.captureMap(
              s, 0, TextureMap.Kind.HEIGHT, size, TextureMap.Kind.HEIGHT.initialRange, 0, 0, false);
      var composed =
          r.captureMap(
              s, 0, TextureMap.Kind.NORMAL, size, TextureMap.Kind.NORMAL.initialRange, 0, 0, false);
      ByteBuffer heights = ByteBuffer.wrap(surface.floats()).order(ByteOrder.nativeOrder()),
          normals = ByteBuffer.wrap(composed.floats()).order(ByteOrder.nativeOrder());
      int at = 18 * 96 + 16;
      double base = heights.getFloat(at * 4),
          dx = (heights.getFloat((at + 1) * 4) - base) * 32,
          dy = (heights.getFloat((at + 96) * 4) - base) * 32,
          length = Math.sqrt(1 + dx * dx + dy * dy);
      check(
          Math.abs(normals.getFloat(at * 16) + dx / length) < 2e-5
              && Math.abs(normals.getFloat(at * 16 + 4) + dy / length) < 2e-5,
          "map normals differentiate the complete role-mixed field");
      s = settings();
      Recipes.apply(s, Settings.Recipe.TIME_EXCAVATION);
      s.feedbackEnabled =
          s.pigmentEnabled = s.reactionEnabled = s.particlesEnabled = s.graphEnabled = true;
      r.reset(s);
      Settings active = evolve(r, s, 48);
      r.render(active, 48 * Playback.DT, 96, 64, .2, .1, false);
      GpuSnapshot state = r.snapshot(s, 48);
      memory = r.bytes();
      before = image();
      var color =
          r.captureMap(
              active,
              48 * Playback.DT,
              TextureMap.Kind.LINEAR_COLOR,
              size,
              TextureMap.Kind.LINEAR_COLOR.initialRange,
              .2,
              .1,
              false);
      var emission =
          r.captureMap(
              active,
              48 * Playback.DT,
              TextureMap.Kind.EMISSION,
              size,
              TextureMap.Kind.EMISSION.initialRange,
              .2,
              .1,
              false);
      ByteBuffer colors = ByteBuffer.wrap(color.floats()).order(ByteOrder.nativeOrder()),
          emissions = ByteBuffer.wrap(emission.floats()).order(ByteOrder.nativeOrder());
      double error = 0;
      for (int offset = 0; offset < color.floats().length; offset += 16)
        for (int channel = 0; channel < 3; channel++)
          error =
              Math.max(
                  error,
                  Math.abs(
                      emissions.getFloat(offset + channel * 4)
                          - Math.max(colors.getFloat(offset + channel * 4) - .65f, 0)));
      check(error < 1e-6, "emission follows the defined unblurred bloom threshold in linear RGB");
      for (var kind : TextureMap.Kind.values())
        TextureMap.encode(
            r.captureMap(
                active,
                48 * Playback.DT,
                kind,
                new CaptureSize(191, 127),
                kind.initialRange,
                .2,
                .1,
                false));
      check(
          sameBlobs(state, r.snapshot(s, 48))
              && r.bytes() == memory
              && Arrays.equals(before, image()),
          "all odd-sized maps preserve persistent GPU bytes and the live framebuffer");
      active.debug = 8;
      active.set("exposure", 4);
      active.set("bloom", 1);
      r.render(active, 48 * Playback.DT, 96, 64, .2, .1, false);
      r.freezeFinished(96, 64);
      var dataOnly =
          r.captureMap(
              active,
              48 * Playback.DT,
              TextureMap.Kind.LINEAR_COLOR,
              size,
              TextureMap.Kind.LINEAR_COLOR.initialRange,
              .2,
              .1,
              false);
      check(
          Arrays.equals(color.floats(), dataOnly.floats()),
          "linear maps exclude debug display, exposure, bloom and A/B reference");
      memory = r.bytes();
      before = image();
      boolean rejected = false;
      try {
        r.captureMap(
            active,
            48 * Playback.DT,
            TextureMap.Kind.NORMAL,
            new CaptureSize(8192, 4096),
            TextureMap.Kind.NORMAL.initialRange,
            0,
            0,
            false);
      } catch (IllegalArgumentException e) {
        rejected = true;
      }
      check(
          rejected && r.bytes() == memory && Arrays.equals(before, image()),
          "over-budget map rejects before allocation and leaves view intact");
    }
  }

  private static void savedReferences() {
    Settings s = settings();
    try (Renderer r = new Renderer(new ShaderSources(null))) {
      r.reset(s);
      r.render(s, 0, 96, 64, 0, 0, false);
      int[] before = image();
      GpuSnapshot state = r.snapshot(s, 0);
      long memory = r.bytes();
      var source =
          new java.awt.image.BufferedImage(96, 64, java.awt.image.BufferedImage.TYPE_INT_RGB);
      for (int y = 0; y < 64; y++)
        for (int x = 0; x < 96; x++) source.setRGB(x, y, y < 32 ? 0xff24a86c : 0xff4c38b8);
      var pixels = Capture.pixels(source);
      r.setReference(pixels);
      r.render(s, 0, 96, 64, 0, 0, false);
      int[] comparison = image();
      check(
          comparison[7 * 96 + 12] == source.getRGB(12, 7)
              && comparison[55 * 96 + 12] == source.getRGB(12, 55),
          "loaded reference preserves top-left PNG orientation and encoded colors");
      check(
          comparison[7 * 96 + 80] == before[7 * 96 + 80],
          "saved reference leaves live right half intact");
      GpuSnapshot after = r.snapshot(s, 0);
      check(
          state.blobs().entrySet().stream()
              .allMatch(e -> Arrays.equals(e.getValue(), after.blobs().get(e.getKey()))),
          "loading a saved reference does not advance or replace persistent state");
      check(
          Arrays.equals(pixels.rgba(), after.blobs().get("reference")),
          "uploaded reference survives checkpoint readback exactly");
      r.setReference(pixels);
      check(
          r.bytes() == memory + 96 * 64 * 4,
          "reference replacement has bounded resource accounting");
      boolean rejected = false;
      try {
        r.setReference(new Capture.Pixels(96, 64, new byte[3]));
      } catch (IllegalArgumentException e) {
        rejected = true;
      }
      check(
          rejected
              && Arrays.equals(
                  after.blobs().get("reference"), r.snapshot(s, 0).blobs().get("reference")),
          "invalid reference preserves the active reference");
      r.clearReference();
      r.render(s, 0, 96, 64, 0, 0, false);
      check(
          memory == r.bytes() && Arrays.equals(before, image()),
          "clearing imported reference restores the original view and budget");
    }
  }

  private static void stillCaptures() {
    Settings s = settings();
    Recipes.apply(s, Settings.Recipe.TIME_EXCAVATION);
    s.feedbackEnabled =
        s.pigmentEnabled = s.reactionEnabled = s.particlesEnabled = s.graphEnabled = true;
    try (Renderer r = new Renderer(new ShaderSources(null))) {
      r.reset(s);
      Settings active = evolve(r, s, 32);
      double time = 32 * Playback.DT;
      r.render(active, time, 96, 64, .2, -.1, true);
      int[] full = image();
      GpuSnapshot before = r.snapshot(s, 32);
      long memory = r.bytes();
      var size = new dev.visualengine.export.CaptureSize(96, 64);
      var still = r.captureStill(active, time, size, .2, -.1, true);
      check(
          Arrays.equals(full, still.getRGB(0, 0, 96, 64, null, 0, 96)),
          "offscreen still matches finished viewport color and orientation");
      check(Arrays.equals(full, image()), "offscreen still preserves the live framebuffer");
      int[] viewport = new int[4];
      glGetIntegerv(GL_VIEWPORT, viewport);
      check(
          Arrays.equals(viewport, new int[] {0, 0, 96, 64})
              && glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING) == 0,
          "still restores live viewport and framebuffer binding");
      active.displayScale = .5;
      s.displayScale = .5;
      r.render(active, time, 96, 64, .2, -.1, true);
      int[] low = image();
      memory = r.bytes();
      still = r.captureStill(active, time, size, .2, -.1, true);
      check(
          Arrays.equals(full, still.getRGB(0, 0, 96, 64, null, 0, 96)),
          "chosen-size still ignores reduced live display scale");
      check(
          Arrays.equals(low, image()) && memory == r.bytes(),
          "full-quality capture preserves reduced live output and allocation");
      var odd =
          r.captureStill(
              active, time, new dev.visualengine.export.CaptureSize(191, 127), .2, -.1, true);
      check(
          odd.getWidth() == 191
              && odd.getHeight() == 127
              && Arrays.stream(odd.getRGB(0, 0, 191, 127, null, 0, 191)).distinct().count() > 100,
          "independent odd-sized and different-aspect still is fully rendered");
      check(
          sameBlobs(before, r.snapshot(s, 32)) && memory == r.bytes(),
          "repeated stills preserve every persistent resource and free temporary storage");
      r.freezeFinished(96, 64);
      still = r.captureStill(active, time, size, .2, -.1, true);
      check(
          r.hasReference() && still.getRGB(12, 32) == low[12 + 32 * 96],
          "still includes frozen A/B reference consistently");
      GpuSnapshot ref = r.snapshot(s, 32);
      var again = r.captureStill(active, time, size, .2, -.1, true);
      check(
          Arrays.equals(
                  still.getRGB(0, 0, 96, 64, null, 0, 96), again.getRGB(0, 0, 96, 64, null, 0, 96))
              && sameBlobs(ref, r.snapshot(s, 32)),
          "paused still with reference is repeatable without ticks");
    }
    // A valid output extent can still exceed the budget alongside an expensive live simulation.
    s.grid = 1024;
    s.particles = 262144;
    s.historyLayers = 32;
    try (Renderer r = new Renderer(new ShaderSources(null))) {
      r.reset(s);
      r.render(s, 0, 96, 64, 0, 0, false);
      long memory = r.bytes();
      int[] before = image();
      boolean rejected = false;
      try {
        r.captureStill(s, 0, new dev.visualengine.export.CaptureSize(8192, 4096), 0, 0, false);
      } catch (IllegalArgumentException e) {
        rejected = true;
      }
      check(
          rejected && memory == r.bytes() && Arrays.equals(before, image()),
          "over-budget still rejects before changing live resources or image");
    }
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
      Files.writeString(
          root.resolve("render/material.frag"),
          """
          #version 430 core
          in vec2 uv; out vec4 color;
          uniform vec2 viewport; uniform uint seed; uniform float scale;
          void main(){color=vec4(uv.x+float(seed)*1e-10+viewport.y*.00001+scale*.001,.1,.2,1);}
          """);
      r.reload();
      r.render(s, 0, 96, 64, 0, 0, false);
      before = image();
      memory = r.bytes();
      rejected = false;
      try {
        r.captureMap(
            s,
            0,
            TextureMap.Kind.HEIGHT,
            new CaptureSize(96, 64),
            TextureMap.Kind.HEIGHT.initialRange,
            0,
            0,
            false);
      } catch (IllegalArgumentException e) {
        rejected = true;
      }
      check(
          rejected && Arrays.equals(before, image()) && memory == r.bytes(),
          "editable shader without the map interface rejects export and preserves live image");
    } finally {
      try (var paths = Files.walk(root)) {
        for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
      }
    }
  }
}
