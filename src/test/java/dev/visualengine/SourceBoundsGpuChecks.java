package dev.visualengine;

import static org.lwjgl.opengl.GL43C.*;

import dev.visualengine.authoring.*;
import dev.visualengine.export.Capture;
import dev.visualengine.gpu.*;
import dev.visualengine.model.Settings;
import dev.visualengine.presets.PresetIO;
import dev.visualengine.time.*;
import java.nio.file.*;
import java.util.*;

public final class SourceBoundsGpuChecks {
  private static int count;

  private static void check(boolean ok, String text) {
    count++;
    if (!ok) throw new AssertionError(text);
  }

  private static boolean rejects(Runnable action, String text) {
    try {
      action.run();
    } catch (IllegalArgumentException e) {
      return e.getMessage().contains(text);
    }
    return false;
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

  public static int run() throws Exception {
    count = 0;
    Settings settings = EffectModulationChecks.settings();
    var descriptors = new ArrayList<>(settings.effectControls);
    descriptors.addAll(SourceBoundsChecks.controls());
    settings.installEffectControls(descriptors, settings.effectValues);
    ControlSignals signals = new ControlSignals();
    Path source = Path.of("src/main/resources/shaders");
    Path root = Files.createTempDirectory(Path.of("build/reports"), "source-bound-");
    try {
      try (var files = Files.walk(source)) {
        for (Path path : files.toList()) {
          Path target = root.resolve(source.relativize(path));
          if (Files.isDirectory(path)) Files.createDirectories(target);
          else Files.copy(path, target);
        }
      }
      ShaderSources sources = new ShaderSources(root);
      try (Renderer renderer = new Renderer(sources)) {
        renderer.reset(settings);
        Settings active = null;
        for (int tick = 1; tick <= 16; tick++) {
          active = signals.tick(settings, tick * Playback.DT);
          renderer.tick(active, tick * Playback.DT, 1.5, .2, -.1, true);
        }
        renderer.render(active, 16 * Playback.DT, 96, 64, .2, -.1, true);
        var before = renderer.snapshot(active, 16);
        int[] original = image();
        int program = renderer.materialProgramId();
        String signature = renderer.programSignature();
        long storage = renderer.bytes();
        var controls = signals.snapshot(settings);
        var cache = renderer.shaderCacheStats();
        Settings over = active.copy();
        over.expression = SourceBoundsChecks.expression(26);
        check(
            rejects(() -> renderer.configure(over), "expression and gradient exceed 64 KiB"),
            "oversized generated gradient is rejected before a material-cache build");
        check(
            program == renderer.materialProgramId()
                && cache.equals(renderer.shaderCacheStats())
                && signature.equals(renderer.programSignature())
                && Arrays.equals(original, image())
                && same(before, renderer.snapshot(active, 16))
                && controls.equals(signals.snapshot(settings))
                && storage == renderer.bytes(),
            "gradient rejection preserves source identity, pixels, every GPU byte, resolved"
                + " controls and storage");
        int boundProgram = glGetInteger(GL_CURRENT_PROGRAM);
        check(
            rejects(
                    () -> new Program("vertex", "x".repeat(ShaderSources.EXPANDED_BYTES + 1)),
                    "exceeds 1 MiB")
                && rejects(() -> Program.compute("compute\0source"), "NUL")
                && glGetInteger(GL_CURRENT_PROGRAM) == boundProgram,
            "direct program limits reject before disturbing the active GL program");
        Path fragment = root.resolve("render/material.frag");
        String good = Files.readString(fragment), padded = good;
        for (int i = 0; i < 5; i++) {
          String name = "padding-" + i + ".glsl";
          Files.writeString(
              root.resolve("render").resolve(name),
              i < 4 ? "/*" + "x".repeat(230000) + "*/" : "/**/");
          padded += "\n#include \"" + name + "\"\n";
        }
        Files.writeString(fragment, padded);
        int extra =
            ShaderSources.EXPANDED_BYTES
                - ShaderSources.sourceBytes(sources.read("render/material.frag"))
                - 64;
        check(
            extra > 0 && extra < ShaderSources.FILE_BYTES - 4,
            "native assembly fixture keeps each include below its file limit");
        Files.writeString(root.resolve("render/padding-4.glsl"), "/*" + "x".repeat(extra) + "*/");
        check(
            ShaderSources.sourceBytes(sources.read("render/material.frag"))
                == ShaderSources.EXPANDED_BYTES - 64,
            "expanded engine source fits while leaving too little room for the generated custom"
                + " field");
        Settings edited = active.copy();
        edited.expression = "cos(p.x * amplitude)";
        check(
            rejects(() -> renderer.configure(edited), "after insertion")
                && rejects(renderer::reload, "after insertion"),
            "candidate edits and F5 preflight the complete inserted stage");
        renderer.render(active, 16 * Playback.DT, 96, 64, .2, -.1, true);
        check(
            program == renderer.materialProgramId()
                && cache.equals(renderer.shaderCacheStats())
                && signature.equals(renderer.programSignature())
                && Arrays.equals(original, image())
                && same(before, renderer.snapshot(active, 16))
                && storage == renderer.bytes(),
            "final-stage rejection preserves the full cache generation, source identity and"
                + " developed scene");
        Files.writeString(fragment, good);
        renderer.reload();
        renderer.render(active, 16 * Playback.DT, 96, 64, .2, -.1, true);
        check(
            signature.equals(renderer.programSignature())
                && Arrays.equals(original, image())
                && same(before, renderer.snapshot(active, 16)),
            "corrected engine source reloads with the original full program signature and exact"
                + " state");
        Settings graph = active.copy();
        graph.graphEnabled = true;
        graph.graph = SourceBoundsChecks.graph(SourceBoundsChecks.expression(26), false);
        renderer.render(graph, 16 * Playback.DT, 96, 64, .2, -.1, true);
        int[] graphPixels = image();
        check(
            same(before, renderer.snapshot(active, 16)) && renderer.bytes() == storage,
            "a bounded graph value renders without generating its unused oversized analytic"
                + " gradient");
        graph.graph = SourceBoundsChecks.graph(SourceBoundsChecks.expression(26), true);
        renderer.render(graph, 16 * Playback.DT, 96, 64, .2, -.1, true);
        check(
            Arrays.equals(graphPixels, image()) && same(before, renderer.snapshot(active, 16)),
            "formula and expression graph values match every finished pixel without advancing"
                + " persistent state");
      }
    } finally {
      try (var paths = Files.walk(root)) {
        for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
          Files.deleteIfExists(path);
      }
    }
    check(glGetError() == GL_NO_ERROR, "source bounds and corrected candidates leave no GL errors");
    return count;
  }
}
