package dev.visualengine;

import static org.lwjgl.opengl.GL43C.*;

import dev.visualengine.authoring.*;
import dev.visualengine.export.*;
import dev.visualengine.gpu.*;
import dev.visualengine.model.Settings;
import java.nio.*;
import java.util.*;
import org.lwjgl.system.MemoryUtil;

public final class GaborGpuChecks {
  private static int count;

  private static void check(boolean value, String label) {
    count++;
    if (!value) throw new AssertionError(label);
  }

  static int run() throws Exception {
    ShaderSources sources = new ShaderSources(null);
    String source =
        "#version 430 core\nuniform uint seed;\n"
            + sources.read("common/noise.glsl")
            + sources.read("common/gabor.glsl")
            + """
            layout(local_size_x=64) in;
            layout(std430,binding=0) buffer Results {float values[];};
            uniform vec2 origin,spacing;
            uniform float orientation,carrier,bandwidth;
            void main(){uint i=gl_GlobalInvocationID.x;vec2 p=origin+vec2(i%64u,i/64u)*spacing;
              values[i]=gabor(p,orientation,carrier,bandwidth);}
            """;
    try (Program program = Program.compute(source)) {
      float[] baseline = probe(program, -.9, -.7, .03, .03, .4, 3, 1, 123456);
      double error = 0;
      for (int i = 0; i < baseline.length; i++)
        error =
            Math.max(
                error,
                Math.abs(
                    baseline[i]
                        - GaborChecks.reference(
                            -.9 + (i % 64) * .03, -.7 + (i / 64) * .03, .4, 3, 1, 123456)));
      check(
          error < 2e-4,
          "Gabor GPU values match independent double reference (max error " + error + ")");
      check(
          Arrays.equals(baseline, probe(program, -.9, -.7, .03, .03, .4, 3, 1, 123456)),
          "Gabor seed and coordinates repeat exactly");
      check(
          !Arrays.equals(baseline, probe(program, -.9, -.7, .03, .03, .4, 3, 1, 123457)),
          "Gabor responds to root seed");
      float[] horizontal = probe(program, -1, -1, .03, .03, 0, 4, 1, 123456);
      float[] vertical = probe(program, -1, -1, .03, .03, Math.PI / 2, 4, 1, 123456);
      double[] h = energy(horizontal), v = energy(vertical);
      check(
          h[0] > 4 * h[1] && v[1] > 4 * v[0],
          "Gabor orientation rotates principal spatial variation");
      float[] halfTurn = probe(program, -1, -1, .03, .03, Math.PI, 4, 1, 123456);
      check(
          maxDifference(horizontal, halfTurn) < 2e-4,
          "Gabor carrier orientation repeats after half turn");
      check(
          !Arrays.equals(baseline, probe(program, -.9, -.7, .03, .03, .4, 6, 1, 123456))
              && !Arrays.equals(baseline, probe(program, -.9, -.7, .03, .03, .4, 3, 2, 123456)),
          "Gabor frequency and bandwidth independently change field");
      check(
          maxDifference(
                  probe(program, 1 - 1e-5, -1, 0, .03, .4, 3, 1, 123456),
                  probe(program, 1 + 1e-5, -1, 0, .03, .4, 3, 1, 123456))
              < .005,
          "Gabor remains continuous across impulse grid boundaries");
      float[] minimum = probe(program, -.9, -.7, .03, .03, .4, -1, -1, 123456);
      check(
          Arrays.equals(minimum, probe(program, -.9, -.7, .03, .03, .4, 0, .25, 123456)),
          "Gabor clamps low frequency and bandwidth explicitly");
      check(
          Arrays.equals(
              probe(program, -.9, -.7, .03, .03, .4, 20, 8, 123456),
              probe(program, -.9, -.7, .03, .03, .4, 16, 4, 123456)),
          "Gabor clamps high frequency and bandwidth explicitly");
      float[] large = probe(program, 1e30, -1e30, 0, 0, .4, 16, 4, 123456);
      check(
          finite(large)
              && Arrays.equals(large, probe(program, 262144, -262144, 0, 0, .4, 16, 4, 123456)),
          "Gabor finite coordinate caps prevent integer overflow");
      check(
          !Float.isFinite(probe(program, 0, 0, 0, 0, Double.NaN, 3, 1, 123456)[0])
              && !Float.isFinite(
                  probe(program, Double.POSITIVE_INFINITY, 0, 0, 0, 0, 3, 1, 123456)[0]),
          "Gabor propagates nonfinite inputs to diagnostics");
    }
    Settings settings = new Settings();
    settings.grid = 65;
    settings.particles = 0;
    settings.historyLayers = 0;
    settings.recipe = Settings.Recipe.CUSTOM;
    settings.expression = "gabor(p,0.4,3,1)";
    settings.set("height", 1);
    settings.set("scale", 1);
    try (Renderer renderer = new Renderer(sources)) {
      renderer.configure(settings);
      var data =
          renderer.captureMap(
              settings,
              0,
              TextureMap.Kind.HEIGHT,
              new CaptureSize(96, 64),
              new TextureMap.Range(-2, 2),
              0,
              0,
              false);
      float[] height = new float[96 * 64];
      ByteBuffer.wrap(data.floats()).order(ByteOrder.nativeOrder()).asFloatBuffer().get(height);
      int seed = (int) dev.visualengine.time.Seeds.derive(settings.seed, "field", "root");
      double error = 0;
      for (int y = 0; y < 64; y++)
        for (int x = 0; x < 96; x++)
          error =
              Math.max(
                  error,
                  Math.abs(
                      height[y * 96 + x]
                          - GaborChecks.reference(
                              ((x + .5) / 96 * 2 - 1) * 1.5,
                              (y + .5) / 64 * 2 - 1,
                              .4,
                              3,
                              1,
                              seed)));
      check(error < 2e-4, "Gabor material height maps preserve raw field values");
      var normal =
          renderer.captureMap(
              settings,
              0,
              TextureMap.Kind.NORMAL,
              new CaptureSize(96, 64),
              new TextureMap.Range(-1, 1),
              0,
              0,
              false);
      TextureMap.encode(normal);
      check(
          renderer.materialProgramId() != 0, "Gabor complete-field normal path renders finite map");
    }
    effects();
    return count;
  }

  private static void effects() throws Exception {
    int[] first = null;
    for (var style : DirectionalEffects.Style.values()) {
      Settings settings = new Settings();
      DirectionalEffects.create(style).apply(settings);
      settings.grid = 65;
      settings.particles = 0;
      settings.historyLayers = 0;
      try (Renderer renderer = new Renderer(new ShaderSources(null))) {
        renderer.reset(settings);
        renderer.render(settings, 0, 96, 64, 0, 0, false);
        int[] original = Capture.read(96, 64).getRGB(0, 0, 96, 64, null, 0, 96);
        check(
            Arrays.stream(original).distinct().count() > 128,
            "directional effect produces varied visible image: " + style);
        if (first == null) first = original;
        else
          check(
              !Arrays.equals(first, original),
              "fibers and weave have distinct rendered compositions");
        Capture.write(
            java.nio.file.Path.of(
                "build/reports/gpu-gallery/gabor-"
                    + style.name().toLowerCase(Locale.ROOT)
                    + ".png"),
            Capture.read(96, 64),
            settings,
            0,
            glGetString(GL_RENDERER));
        int program = renderer.materialProgramId();
        settings.setEffectValue("orientation", List.of(-.8));
        renderer.configure(settings);
        renderer.render(settings, 0, 96, 64, 0, 0, false);
        int[] edited = Capture.read(96, 64).getRGB(0, 0, 96, 64, null, 0, 96);
        check(
            program == renderer.materialProgramId() && !Arrays.equals(original, edited),
            "directional controls change image without relinking: " + style);
        var height =
            renderer.captureMap(
                settings,
                0,
                TextureMap.Kind.HEIGHT,
                new CaptureSize(96, 64),
                new TextureMap.Range(-2, 2),
                0,
                0,
                false);
        settings.graphEnabled = true;
        settings.graph.nodes =
            new ArrayList<>(
                List.of(
                    new EffectGraph.Node(
                        "field",
                        EffectGraph.Kind.FORMULA,
                        List.of(),
                        settings.expression,
                        0,
                        0,
                        false,
                        "",
                        "")));
        settings.graph.output = "field";
        renderer.configure(settings);
        var graph =
            renderer.captureMap(
                settings,
                0,
                TextureMap.Kind.HEIGHT,
                new CaptureSize(96, 64),
                new TextureMap.Range(-2, 2),
                0,
                0,
                false);
        float[] a = new float[96 * 64], b = new float[a.length];
        ByteBuffer.wrap(height.floats()).order(ByteOrder.nativeOrder()).asFloatBuffer().get(a);
        ByteBuffer.wrap(graph.floats()).order(ByteOrder.nativeOrder()).asFloatBuffer().get(b);
        check(
            maxDifference(a, b) < 1e-5,
            "directional formula and typed graph preserve field values: " + style);
        settings.graphEnabled = false;
        settings.loopSeconds = 8;
        renderer.configure(settings);
        renderer.render(settings, 0, 96, 64, 0, 0, false);
        int[] start = Capture.read(96, 64).getRGB(0, 0, 96, 64, null, 0, 96);
        renderer.render(settings, 8, 96, 64, 0, 0, false);
        check(
            Arrays.equals(start, Capture.read(96, 64).getRGB(0, 0, 96, 64, null, 0, 96)),
            "phase-only directional effect repeats at configured analytic period: " + style);
      }
    }
  }

  private static boolean finite(float[] data) {
    for (float x : data) if (!Float.isFinite(x) || Math.abs(x) > 25) return false;
    return true;
  }

  private static double maxDifference(float[] a, float[] b) {
    double max = 0;
    for (int i = 0; i < a.length; i++) max = Math.max(max, Math.abs(a[i] - b[i]));
    return max;
  }

  private static double[] energy(float[] data) {
    double x = 0, y = 0;
    for (int j = 0; j < 63; j++)
      for (int i = 0; i < 63; i++) {
        double dx = data[j * 64 + i + 1] - data[j * 64 + i],
            dy = data[(j + 1) * 64 + i] - data[j * 64 + i];
        x += dx * dx;
        y += dy * dy;
      }
    return new double[] {x, y};
  }

  private static float[] probe(
      Program p,
      double x,
      double y,
      double dx,
      double dy,
      double angle,
      double frequency,
      double bandwidth,
      int seed) {
    int old = glGetInteger(GL_SHADER_STORAGE_BUFFER_BINDING),
        indexed = glGetIntegeri(GL_SHADER_STORAGE_BUFFER_BINDING, 0);
    int buffer = glGenBuffers();
    FloatBuffer values = MemoryUtil.memAllocFloat(4096);
    try {
      glBindBuffer(GL_SHADER_STORAGE_BUFFER, buffer);
      glBufferData(GL_SHADER_STORAGE_BUFFER, 4096L * 4, GL_DYNAMIC_READ);
      glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, buffer);
      p.use();
      p.ui("seed", seed);
      p.v2("origin", x, y);
      p.v2("spacing", dx, dy);
      p.f("orientation", angle);
      p.f("carrier", frequency);
      p.f("bandwidth", bandwidth);
      glDispatchCompute(64, 1, 1);
      glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT);
      glGetBufferSubData(GL_SHADER_STORAGE_BUFFER, 0, values);
      float[] result = new float[4096];
      values.get(result);
      return result;
    } finally {
      glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, indexed);
      glBindBuffer(GL_SHADER_STORAGE_BUFFER, old);
      glDeleteBuffers(buffer);
      MemoryUtil.memFree(values);
    }
  }
}
