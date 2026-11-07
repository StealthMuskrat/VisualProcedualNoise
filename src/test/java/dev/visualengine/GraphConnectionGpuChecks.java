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

public final class GraphConnectionGpuChecks {
  private static int count;

  private static void check(boolean ok, String text) {
    count++;
    if (!ok) throw new AssertionError(text);
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
    var fixture =
        com.google.gson.JsonParser.parseString(
                Files.readString(Path.of("build/reports/graph-connection-fixture.json")))
            .getAsJsonObject();
    Settings original = PresetIO.parse(fixture.get("original").toString());
    Settings rewired = PresetIO.parse(fixture.get("rewired").toString());
    Settings restored = PresetIO.parse(fixture.get("restored").toString());
    var signals = new ControlSignals();
    try (Renderer renderer = new Renderer(new ShaderSources(null))) {
      renderer.reset(original);
      Settings active = null;
      for (int tick = 1; tick <= 16; tick++) {
        active = signals.tick(original, tick * Playback.DT);
        renderer.tick(active, tick * Playback.DT, 1.5, .2, -.1, true);
      }
      renderer.render(active, 16 * Playback.DT, 96, 64, .2, -.1, true);
      int[] pixels = image();
      var state = renderer.snapshot(active, 16);
      long memory = renderer.bytes();
      int program = renderer.materialProgramId();
      String signature = renderer.programSignature();
      var controls = signals.snapshot(original);
      Settings changed = signals.frame(rewired);
      renderer.render(changed, 16 * Playback.DT, 96, 64, .2, -.1, true);
      int[] changedPixels = image();
      int changedProgram = renderer.materialProgramId();
      check(
          !signature.equals(renderer.programSignature()) && !Arrays.equals(pixels, changedPixels),
          "rerouting a domain changes generated source and rendered output");
      check(
          same(state, renderer.snapshot(changed, 16))
              && memory == renderer.bytes()
              && controls.equals(signals.snapshot(rewired)),
          "held connection changes preserve every GPU byte, storage and phase controls");
      for (EffectGraph invalid :
          List.of(
              GraphEdits.moveInput(rewired.graph, "point", 0, 1),
              GraphEdits.removeInput(rewired.graph, "point", 0))) {
        Settings bad = changed.copy();
        bad.graph = invalid;
        boolean rejected = false;
        try {
          renderer.configure(bad);
        } catch (IllegalArgumentException expected) {
          rejected = true;
        }
        renderer.render(changed, 16 * Playback.DT, 96, 64, .2, -.1, true);
        check(
            rejected
                && renderer.materialProgramId() == changedProgram
                && Arrays.equals(changedPixels, image())
                && same(state, renderer.snapshot(changed, 16)),
            "invalid connection draft preserves accepted material, pixels and simulation");
      }
      Settings undone = signals.frame(restored);
      renderer.render(undone, 16 * Playback.DT, 96, 64, .2, -.1, true);
      check(
          renderer.materialProgramId() == program
              && signature.equals(renderer.programSignature())
              && Arrays.equals(pixels, image()),
          "connection undo reuses the original material and restores its exact output");
      try (Renderer baseline = renderer.stageRestore(active, state, 16, 96, 64)) {
        active = signals.tick(original, 17 * Playback.DT);
        renderer.tick(active, 17 * Playback.DT, 1.5, .2, -.1, true);
        renderer.render(active, 17 * Playback.DT, 96, 64, .2, -.1, true);
        int[] continued = image();
        baseline.tick(active, 17 * Playback.DT, 1.5, .2, -.1, true);
        baseline.render(active, 17 * Playback.DT, 96, 64, .2, -.1, true);
        check(
            Arrays.equals(continued, image())
                && same(renderer.snapshot(active, 17), baseline.snapshot(active, 17)),
            "undoing held connection edits preserves exact next-tick evolution");
      }
    }
    check(glGetError() == GL_NO_ERROR, "connection edits leave no GL errors");
    return count;
  }
}
