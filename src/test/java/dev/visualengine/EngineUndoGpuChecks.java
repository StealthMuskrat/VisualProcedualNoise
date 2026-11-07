package dev.visualengine;

import static org.lwjgl.opengl.GL43C.*;

import dev.visualengine.app.*;
import dev.visualengine.authoring.*;
import dev.visualengine.export.Capture;
import dev.visualengine.gpu.*;
import dev.visualengine.model.*;
import dev.visualengine.presets.*;
import dev.visualengine.time.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;

/** Drive the existing private command boundary without adding a production test API. */
public final class EngineUndoGpuChecks {
  private static int count;
  private static final Method APPLY;

  static {
    try {
      APPLY = Engine.class.getDeclaredMethod("apply", Command.class);
      APPLY.setAccessible(true);
    } catch (ReflectiveOperationException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  private static Object get(Engine engine, String name) throws Exception {
    Field field = Engine.class.getDeclaredField(name);
    field.setAccessible(true);
    return field.get(engine);
  }

  private static void set(Engine engine, String name, Object value) throws Exception {
    Field field = Engine.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(engine, value);
  }

  private static void command(Engine engine, String name, Object value) throws Exception {
    try {
      APPLY.invoke(engine, new Command(name, value));
    } catch (InvocationTargetException e) {
      throw new AssertionError("Command boundary escaped", e.getCause());
    }
  }

  private static Settings settings(Engine engine) throws Exception {
    return (Settings) get(engine, "settings");
  }

  private static Renderer renderer(Engine engine) throws Exception {
    return (Renderer) get(engine, "renderer");
  }

  private static void check(boolean ok, String text) {
    count++;
    if (!ok) throw new AssertionError(text);
  }

  private static int[] image() {
    return Capture.read(96, 64).getRGB(0, 0, 96, 64, null, 0, 96);
  }

  private static int[] draw(Engine engine) throws Exception {
    Settings settings = settings(engine);
    var signals = (ControlSignals) get(engine, "signals");
    var clock = (Playback) get(engine, "clock");
    renderer(engine).render(signals.frame(settings), clock.seconds(), 96, 64, 0, 0, false);
    return image();
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
    Path root = Files.createTempDirectory(Path.of("build/reports"), "engine-undo-");
    try {
      Path shaders = root.resolve("shaders"), source = Path.of("src/main/resources/shaders");
      try (var files = Files.walk(source)) {
        for (Path path : files.toList()) {
          Path target = shaders.resolve(source.relativize(path));
          if (Files.isDirectory(path)) Files.createDirectories(target);
          else Files.copy(path, target);
        }
      }
      Settings initial = EffectModulationChecks.settings();
      initial.paused = true;
      Path preset = root.resolve("initial.json");
      PresetIO.save(preset, initial, 0);
      Options options =
          Options.parse(
              new String[] {
                "--headless",
                "--frames",
                "8",
                "--size",
                "96x64",
                "--preset",
                preset.toString(),
                "--shaders",
                shaders.toString()
              });
      try (Engine engine = new Engine(options, root.resolve("data"))) {
        engine.run();
        var history = (SceneHistory) get(engine, "undo");
        var signals = (ControlSignals) get(engine, "signals");
        var clock = (Playback) get(engine, "clock");
        Renderer renderer = renderer(engine);
        var before = renderer.snapshot(signals.frame(settings(engine)), clock.tick());
        var controlState = signals.snapshot(settings(engine));
        int[] original = draw(engine);
        int program = renderer.materialProgramId();
        var cache = renderer.shaderCacheStats();
        var checkpoint =
            new CheckpointIO.Snapshot(
                settings(engine),
                clock.tick(),
                96,
                64,
                controlState,
                new CheckpointIO.Pointer(0, 0, false),
                before,
                null);
        command(engine, "undo", null);
        check(
            history.size() == 0
                && controlState.equals(signals.snapshot(settings(engine)))
                && Arrays.equals(original, draw(engine)),
            "empty engine undo preserves unfinished controls and the developed frame");
        command(engine, "parameter", new Command.Edit("warp", 99));
        command(engine, "expression", "unknownFunction(p)");
        command(engine, "grid", 1025);
        check(
            history.size() == 0
                && !(boolean) get(engine, "reset")
                && program == renderer.materialProgramId()
                && cache.equals(renderer.shaderCacheStats())
                && controlState.equals(signals.snapshot(settings(engine)))
                && same(before, renderer.snapshot(signals.frame(settings(engine)), clock.tick()))
                && Arrays.equals(original, draw(engine)),
            "failed value, source and layout edits preserve history, reset intent, image and every"
                + " GPU byte");
        int palette = settings(engine).palette;
        command(engine, "palette", (palette + 1) % 4);
        check(
            history.size() == 1 && controlState.equals(signals.snapshot(settings(engine))),
            "accepted palette change records one configuration without settling smoothing");
        command(engine, "undo", null);
        check(
            history.size() == 0
                && settings(engine).palette == palette
                && controlState.equals(signals.snapshot(settings(engine)))
                && Arrays.equals(original, draw(engine))
                && same(before, renderer.snapshot(signals.frame(settings(engine)), clock.tick())),
            "palette undo restores the frame with exact unfinished smoothing and persistent state");
        double warp = settings(engine).get("warp");
        command(engine, "parameter", new Command.Edit("warp", warp + .1));
        command(engine, "undo", null);
        ControlSignals expected = new ControlSignals();
        expected.restore(controlState, settings(engine));
        expected.snap(settings(engine), clock.seconds());
        check(
            history.size() == 0
                && settings(engine).get("warp") == warp
                && signals.snapshot(settings(engine)).equals(expected.snapshot(settings(engine)))
                && clock.tick() == 16
                && same(before, renderer.snapshot(signals.frame(settings(engine)), clock.tick())),
            "numeric undo resolves held targets at the same tick without rewinding GPU state");
        String expression = settings(engine).expression;
        String alternate = "cos(p.x*amplitude)+sin(p.y)";
        command(engine, "expression", alternate);
        int changedProgram = renderer.materialProgramId();
        var changedControls = signals.snapshot(settings(engine));
        int[] changed = draw(engine);
        Path fragment = shaders.resolve("render/material.frag");
        String good = Files.readString(fragment);
        ReplayLog activeTake = ReplayLog.begin(settings(engine), 96, 64);
        set(engine, "playbackLog", activeTake);
        Files.writeString(fragment, "#version 430 core\ninvalid shader;\n");
        command(engine, "undo", null);
        check(
            history.size() == 1
                && get(engine, "playbackLog") == activeTake
                && settings(engine).expression.equals(alternate)
                && renderer.materialProgramId() == changedProgram
                && changedControls.equals(signals.snapshot(settings(engine)))
                && Arrays.equals(changed, draw(engine))
                && same(before, renderer.snapshot(signals.frame(settings(engine)), clock.tick())),
            "failed shader compilation during actual undo retains its entry and the working scene");
        Files.writeString(fragment, good);
        command(engine, "undo", null);
        check(
            history.size() == 0
                && get(engine, "playbackLog") == null
                && settings(engine).expression.equals(expression)
                && renderer.materialProgramId() == program
                && changedControls.equals(signals.snapshot(settings(engine))),
            "retrying corrected undo consumes exactly the retained entry");
        ReplayLog full = ReplayLog.begin(settings(engine), 96, 64);
        full.recordControl(17, settings(engine));
        while (full.events.size() < 200000) full.events.add(full.events.getFirst());
        set(engine, "recordingLog", full);
        set(engine, "recording", true);
        cache = renderer.shaderCacheStats();
        command(engine, "expression", alternate);
        check(
            history.size() == 0
                && settings(engine).expression.equals(expression)
                && renderer.materialProgramId() == program
                && cache.equals(renderer.shaderCacheStats())
                && full.events.size() == 200000
                && full.endTick == 17
                && (boolean) get(engine, "recording")
                && changedControls.equals(signals.snapshot(settings(engine))),
            "recording exhaustion rejects before GPU or history commit and preserves the take");
        full.events = new ArrayList<>(List.of(full.events.getFirst()));
        full.restoreCursor(1);
        set(engine, "playbackLog", full);
        set(engine, "replayX", .2);
        set(engine, "replayY", -.1);
        set(engine, "replayBrush", true);
        Settings other = settings(engine).copy();
        other.expression = alternate;
        ReplayLog candidate = ReplayLog.begin(other, 96, 64);
        Files.writeString(fragment, "#version 430 core\ninvalid shader;\n");
        command(engine, "replayLoaded", candidate);
        check(
            get(engine, "playbackLog") == full
                && full.cursor() == 1
                && get(engine, "recordingLog") == full
                && (boolean) get(engine, "recording")
                && (boolean) get(engine, "replayBrush")
                && (double) get(engine, "replayX") == .2
                && (double) get(engine, "replayY") == -.1
                && !(boolean) get(engine, "reset")
                && history.size() == 0
                && settings(engine).expression.equals(expression),
            "rejected replay activation restores transport, cursor, pointer, recording and"
                + " history");
        Files.writeString(fragment, good);
        set(engine, "playbackLog", null);
        set(engine, "recordingLog", null);
        set(engine, "recording", false);
        set(engine, "replayBrush", false);
        for (int i = 0; i < 64; i++) command(engine, "palette", (settings(engine).palette + 1) % 4);
        command(engine, "parameter", new Command.Edit("warp", 99));
        check(history.size() == 64, "rejected edits do not evict full engine history");
        for (int i = 0; i < 64; i++) command(engine, "undo", null);
        check(
            history.size() == 0
                && settings(engine).palette == palette
                && changedControls.equals(signals.snapshot(settings(engine))),
            "all 64 accepted configuration changes remain undoable with held smoothing intact");
        long seed = settings(engine).seed;
        command(engine, "seed", seed + 1);
        set(engine, "reset", false);
        command(engine, "undo", null);
        check(
            history.size() == 0 && settings(engine).seed == seed && (boolean) get(engine, "reset"),
            "seed undo requests a simulation restart");
        set(engine, "reset", false);
        var schema = new ArrayList<>(settings(engine).effectControls);
        schema.add(
            new EffectControl(
                "resetValue",
                "Reset value",
                Formula.Type.FLOAT,
                "",
                List.of(1.),
                0,
                2,
                .1,
                EffectControl.Scale.LINEAR,
                EffectControl.Update.RESET));
        command(engine, "effectControls", schema);
        check(
            (boolean) get(engine, "reset") && history.size() == 1,
            "adding a reset-required control requests a restart and history entry");
        set(engine, "reset", false);
        command(engine, "effectValue", new Command.EffectEdit("resetValue", List.of(2.)));
        set(engine, "reset", false);
        command(engine, "undo", null);
        check(
            (boolean) get(engine, "reset")
                && settings(engine).effectValues.get("resetValue").equals(List.of(1.)),
            "undoing a reset-required value requests a restart");
        set(engine, "reset", false);
        command(engine, "undo", null);
        check(
            (boolean) get(engine, "reset")
                && !settings(engine).effectValues.containsKey("resetValue")
                && history.size() == 0,
            "undoing a reset-control schema restores the old configuration and restart policy");
        set(engine, "reset", false);
        Settings loaded = settings(engine).copy();
        loaded.palette = (palette + 1) % 4;
        command(engine, "loaded", loaded);
        set(engine, "reset", false);
        command(engine, "undo", null);
        check(
            (boolean) get(engine, "reset")
                && settings(engine).palette == palette
                && history.size() == 0,
            "undo of preset activation restarts even when only its palette differs");
        set(engine, "reset", false);
        command(engine, "grid", 67);
        set(engine, "reset", false);
        command(engine, "undo", null);
        check(
            (boolean) get(engine, "reset")
                && settings(engine).grid == initial.grid
                && history.size() == 0,
            "grid edits and their undo share bounded history and restart semantics");
        command(engine, "palette", (palette + 1) % 4);
        var wrongAspect =
            new CheckpointIO.Snapshot(
                checkpoint.settings(),
                checkpoint.tick(),
                128,
                64,
                checkpoint.signals(),
                checkpoint.pointer(),
                checkpoint.gpu(),
                null);
        command(engine, "checkpointLoaded", wrongAspect);
        check(
            history.size() == 1 && settings(engine).palette != palette,
            "rejected checkpoint restore retains configuration history");
        command(engine, "checkpointLoaded", checkpoint);
        renderer = renderer(engine);
        signals = (ControlSignals) get(engine, "signals");
        check(
            history.size() == 0
                && controlState.equals(signals.snapshot(settings(engine)))
                && Arrays.equals(original, draw(engine))
                && same(before, renderer.snapshot(signals.frame(settings(engine)), clock.tick())),
            "successful checkpoint clears history only after exact developed-state restoration");
        check(
            glGetError() == GL_NO_ERROR,
            "actual engine command rejection and undo leave no GL errors");
      }
    } finally {
      try (var files = Files.walk(root)) {
        for (Path path : files.sorted(Comparator.reverseOrder()).toList())
          Files.deleteIfExists(path);
      }
    }
    return count;
  }
}
