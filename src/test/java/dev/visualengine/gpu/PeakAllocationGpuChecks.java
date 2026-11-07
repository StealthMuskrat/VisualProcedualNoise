package dev.visualengine.gpu;

import static org.lwjgl.opengl.GL43C.*;

import dev.visualengine.export.*;
import dev.visualengine.model.Settings;
import dev.visualengine.platform.Window;
import dev.visualengine.presets.PresetIO;
import dev.visualengine.time.Playback;
import java.nio.file.*;
import java.util.*;

/** Small synthetic budgets exercise overlapping allocations without exhausting real GPU memory. */
public final class PeakAllocationGpuChecks {
  private static int count;
  private static final long LIMIT = 240_000;

  private static void check(boolean ok, String label) {
    count++;
    if (!ok) throw new AssertionError(label);
  }

  private static Settings settings() {
    Settings s = new Settings();
    s.grid = 33;
    s.particles = 17;
    s.historyLayers = 0;
    s.feedbackEnabled = true;
    s.pigmentEnabled = true;
    return s;
  }

  private static void develop(Renderer r, Settings s) {
    r.reset(s);
    for (int tick = 1; tick <= 16; tick++) r.tick(s, tick * Playback.DT, 1.5, .2, -.1, true);
    r.render(s, 16 * Playback.DT, 96, 64, .2, -.1, true);
  }

  private static int[] image() {
    return Capture.read(96, 64).getRGB(0, 0, 96, 64, null, 0, 96);
  }

  private static boolean same(GpuSnapshot a, GpuSnapshot b) {
    return a.blobs().keySet().equals(b.blobs().keySet())
        && a.blobs().entrySet().stream()
            .allMatch(e -> Arrays.equals(e.getValue(), b.blobs().get(e.getKey())))
        && PresetIO.JSON
            .toJsonTree(a.simulation())
            .equals(PresetIO.JSON.toJsonTree(b.simulation()));
  }

  private static Budget.Plan rejected(Renderer r, String operation, Runnable action) {
    long largest = r.allocationStats().largestAllowedPeakBytes();
    try {
      action.run();
    } catch (IllegalArgumentException expected) {
      Budget.Plan plan = r.allocationStats().lastPreflight();
      check(
          expected.getMessage().contains(operation) && expected.getMessage().contains("peak"),
          operation + " identifies its peak budget failure");
      check(
          plan.operation().equals(operation)
              && plan.peakBytes() > r.allocationStats().limitBytes()
              && plan.settledBytes() <= r.allocationStats().limitBytes()
              && r.bytes() == plan.residentBytes()
              && r.allocationStats().largestAllowedPeakBytes() == largest,
          operation + " rejects old/new overlap even though settled resources fit");
      return plan;
    }
    throw new AssertionError("Accepted over-budget peak: " + operation);
  }

  public static int run() throws Exception {
    Settings s = settings();
    var plans = new ArrayList<Budget.Plan>();
    try (Renderer r = new Renderer(new ShaderSources(null), GpuDevice.current(), true, LIMIT)) {
      develop(r, s);
      long resident = r.bytes();
      check(
          resident == Budget.sum(Budget.persistent(s), Budget.display(96, 64)),
          "settled tracked bytes agree with the checked plan");
      GpuSnapshot before = r.snapshot(s, 16);
      int[] pixels = image();
      int material = r.materialProgramId();
      var cache = r.shaderCacheStats();
      Settings larger = s.copy();
      larger.grid = 35;
      larger.recipe = Settings.Recipe.CUSTOM;
      larger.expression = "sin(p.x)";
      plans.add(rejected(r, "simulation replacement", () -> r.configure(larger)));
      check(cache.equals(r.shaderCacheStats()), "peak rejection occurs before material lookup");
      plans.add(
          rejected(
              r,
              "display replacement",
              () -> r.render(s, 16 * Playback.DT, 140, 90, .2, -.1, true)));
      plans.add(
          rejected(
              r,
              "still capture",
              () -> r.captureStill(s, 16 * Playback.DT, new CaptureSize(96, 64), .2, -.1, true)));
      plans.add(
          rejected(
              r,
              "texture-map capture",
              () ->
                  r.captureMap(
                      s,
                      16 * Playback.DT,
                      TextureMap.Kind.LINEAR_COLOR,
                      new CaptureSize(96, 64),
                      TextureMap.Kind.LINEAR_COLOR.initialRange,
                      .2,
                      -.1,
                      true)));
      plans.add(rejected(r, "checkpoint replacement", () -> r.stageRestore(s, before, 16, 96, 64)));
      check(
          r.bytes() == resident
              && r.materialProgramId() == material
              && cache.equals(r.shaderCacheStats())
              && same(before, r.snapshot(s, 16))
              && Arrays.equals(pixels, image()),
          "all rejected peaks preserve active program, image, cache and every GPU state byte");

      r.captureMap(
          s,
          16 * Playback.DT,
          TextureMap.Kind.HEIGHT,
          new CaptureSize(96, 64),
          TextureMap.Kind.HEIGHT.initialRange,
          .2,
          -.1,
          true);
      Budget.Plan temporary = r.allocationStats().lastPreflight();
      check(
          temporary.peakBytes() == resident + Budget.pixels(96, 64, 4)
              && temporary.settledBytes() == r.bytes()
              && same(before, r.snapshot(s, 16)),
          "accepted scalar map releases its entire temporary allocation");
      r.render(s, 16 * Playback.DT, 80, 64, .2, -.1, true);
      Budget.Plan display = r.allocationStats().lastPreflight();
      check(
          display.residentBytes() == resident
              && display.replacedBytes() == Budget.display(96, 64)
              && display.peakBytes() == resident + Budget.display(80, 64)
              && display.settledBytes() == r.bytes(),
          "accepted display replacement reports peak overlap and exact committed storage");
      r.render(s, 16 * Playback.DT, 96, 64, .2, -.1, true);
      check(
          Arrays.equals(pixels, image()) && same(before, r.snapshot(s, 16)),
          "display replacement and temporary export preserve simulation and restored output");

      r.freezeFinished(96, 64);
      GpuSnapshot frozen = r.snapshot(s, 16);
      r.freezeFinished(96, 64);
      check(
          same(frozen, r.snapshot(s, 16)) && r.bytes() == resident + Budget.pixels(96, 64, 4),
          "repeated frozen reference commits a candidate and releases the old reference");
      plans.add(
          rejected(
              r,
              "saved-reference replacement",
              () -> r.setReference(new Capture.Pixels(130, 96, new byte[130 * 96 * 4]))));
      check(
          r.hasReference() && same(frozen, r.snapshot(s, 16)) && Arrays.equals(pixels, image()),
          "over-budget saved reference preserves the previous reference and finished framebuffer");
      r.clearReference();
      check(
          r.bytes() == resident && r.allocationStats().lastPreflight().settledBytes() == resident,
          "reference release records exact settled storage without a staged allocation");
    }
    try (Renderer r = new Renderer(new ShaderSources(null), GpuDevice.current(), true, 206_000)) {
      develop(r, s);
      r.freezeFinished(96, 64);
      GpuSnapshot frozen = r.snapshot(s, 16);
      int[] pixels = image();
      plans.add(rejected(r, "frozen-reference replacement", () -> r.freezeFinished(96, 64)));
      check(
          r.hasReference() && same(frozen, r.snapshot(s, 16)) && Arrays.equals(pixels, image()),
          "rejected repeated freeze preserves its old reference, simulation and finished image");
    }
    candidateTransactions();
    check(
        glGetError() == GL_NO_ERROR,
        "peak preflight and candidate transactions leave no GL errors");
    Path report = Path.of("build/reports/allocation-gpu-verification.json");
    Files.createDirectories(report.getParent());
    Files.writeString(report, PresetIO.JSON.toJson(plans));
    return count;
  }

  private static void candidateTransactions() throws Exception {
    Path root = Files.createTempDirectory(Path.of("build/reports"), "allocation-shaders-");
    try {
      Path source = Path.of("src/main/resources/shaders");
      try (var paths = Files.walk(source)) {
        for (Path p : paths.toList()) {
          Path target = root.resolve(source.relativize(p));
          if (Files.isDirectory(p)) Files.createDirectories(target);
          else Files.copy(p, target);
        }
      }
      Settings s = settings();
      try (Renderer r =
          new Renderer(new ShaderSources(root), GpuDevice.current(), true, 1_000_000)) {
        develop(r, s);
        GpuSnapshot before = r.snapshot(s, 16);
        long resident = r.bytes();
        int material = r.materialProgramId();
        var cache = r.shaderCacheStats();
        int[] pixels = image();
        Path broken = root.resolve("simulation/particles.comp");
        String valid = Files.readString(broken);
        Files.writeString(broken, "#version 430 core\nintentionally broken shader\n");
        Settings changed = s.copy();
        changed.grid = 35;
        try (Markers markers = new Markers()) {
          boolean failed = false;
          try {
            r.configure(changed);
          } catch (IllegalArgumentException expected) {
            failed = true;
          }
          check(
              failed && markers.intact(),
              "failed candidate construction restores allocation bindings");
        }
        check(
            r.bytes() == resident
                && r.materialProgramId() == material
                && cache.equals(r.shaderCacheStats())
                && same(before, r.snapshot(s, 16))
                && Arrays.equals(pixels, image()),
            "failure after allocating candidate textures preserves all owned live resources");
        Files.writeString(broken, valid);
        try (Markers markers = new Markers()) {
          r.configure(changed);
          check(
              markers.intact(),
              "successful candidate construction restores unrelated allocation bindings");
        }
        Budget.Plan plan = r.allocationStats().lastPreflight();
        check(
            plan.peakBytes() == resident + Budget.persistent(changed)
                && plan.settledBytes() == r.bytes()
                && plan.replacedBytes() == Budget.persistent(s),
            "successful simulation replacement reports the actual old/candidate overlap");
        r.reset(changed);
        r.render(changed, 0, 96, 64, 0, 0, false);
        GpuSnapshot snapshot = r.snapshot(changed, 0);
        try (Renderer candidate = r.stageRestore(changed, snapshot, 0, 96, 64)) {
          Budget.Plan restored = candidate.allocationStats().lastPreflight();
          check(
              restored.operation().equals("checkpoint replacement")
                  && restored.peakBytes() == r.bytes() + candidate.bytes()
                  && restored.settledBytes() == candidate.bytes()
                  && same(snapshot, candidate.snapshot(changed, 0)),
              "staged checkpoint reports both live renderers until caller commits replacement");
        }
      }
    } finally {
      try (var paths = Files.walk(root)) {
        for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
      }
    }
  }

  /** Independent objects remain valid even when a successful replacement deletes owned handles. */
  private static final class Markers implements AutoCloseable {
    final int texture = glGenTextures(),
        array = glGenTextures(),
        storage = glGenBuffers(),
        vao = glGenVertexArrays(),
        read = glGenFramebuffers(),
        draw = glGenFramebuffers();

    Markers() {
      glActiveTexture(GL_TEXTURE0 + 7);
      glBindTexture(GL_TEXTURE_2D, texture);
      glBindTexture(GL_TEXTURE_2D_ARRAY, array);
      glBindBuffer(GL_SHADER_STORAGE_BUFFER, storage);
      glBindVertexArray(vao);
      glBindFramebuffer(GL_READ_FRAMEBUFFER, read);
      glBindFramebuffer(GL_DRAW_FRAMEBUFFER, draw);
      glViewport(3, 5, 41, 29);
    }

    boolean intact() {
      int[] viewport = new int[4];
      glGetIntegerv(GL_VIEWPORT, viewport);
      return glGetInteger(GL_ACTIVE_TEXTURE) == GL_TEXTURE0 + 7
          && glGetInteger(GL_TEXTURE_BINDING_2D) == texture
          && glGetInteger(GL_TEXTURE_BINDING_2D_ARRAY) == array
          && glGetInteger(GL_SHADER_STORAGE_BUFFER_BINDING) == storage
          && glGetInteger(GL_VERTEX_ARRAY_BINDING) == vao
          && glGetInteger(GL_READ_FRAMEBUFFER_BINDING) == read
          && glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING) == draw
          && Arrays.equals(viewport, new int[] {3, 5, 41, 29});
    }

    @Override
    public void close() {
      glBindFramebuffer(GL_FRAMEBUFFER, 0);
      glDeleteFramebuffers(read);
      glDeleteFramebuffers(draw);
      glDeleteTextures(texture);
      glDeleteTextures(array);
      glDeleteBuffers(storage);
      glDeleteVertexArrays(vao);
      glActiveTexture(GL_TEXTURE0);
      glViewport(0, 0, 96, 64);
    }
  }

  public static void main(String[] args) throws Exception {
    try (Window window = new Window(96, 64, true)) {
      System.out.println("Peak allocation GPU checks passed: " + run());
    }
  }
}
