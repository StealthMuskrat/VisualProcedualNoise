package dev.visualengine.app;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL43C.*;

import dev.visualengine.export.Capture;
import dev.visualengine.gpu.*;
import dev.visualengine.model.Settings;
import dev.visualengine.platform.Window;
import dev.visualengine.presets.PresetIO;
import dev.visualengine.time.*;
import dev.visualengine.ui.ControlPanel;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

public final class Engine implements AutoCloseable {
  private final Options options;
  private final Path data;
  private final ConcurrentLinkedQueue<Command> commands = new ConcurrentLinkedQueue<>();
  private final ThreadPoolExecutor io =
      new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(2));
  private final ScheduledExecutorService watcher = Executors.newSingleThreadScheduledExecutor();
  private final Playback clock = new Playback();
  private final ControlSignals signals = new ControlSignals();
  private ReplayLog playbackLog, recordingLog;
  private boolean recording, lastBrush, replayBrush;
  private double replayX, replayY;
  private final java.util.concurrent.atomic.AtomicReference<Throwable> ioFailure =
      new java.util.concurrent.atomic.AtomicReference<>();
  private final Deque<Settings> undo = new ArrayDeque<>();
  private Settings settings = new Settings();
  private Window window;
  private Renderer renderer;
  private ControlPanel controls;
  private boolean step, reset, fullscreen, freeze, discardElapsed;
  private final dev.visualengine.export.Benchmark benchmark =
      new dev.visualengine.export.Benchmark();
  private Path capture;
  private String device;
  private long sourceStamp;
  private volatile boolean accepting = true;

  public Engine(Options options, Path data) {
    this.options = options;
    this.data = data;
  }

  public void run() throws Exception {
    if (options.preset != null) settings = PresetIO.load(options.preset);
    if (options.recipe != null) dev.visualengine.model.Recipes.apply(settings, options.recipe);
    if (options.grid != null) settings.grid = options.grid;
    if (options.seed != null) settings.seed = options.seed;
    if (options.expression != null) settings.expression = options.expression;
    if (options.replay != null) {
      playbackLog = ReplayLog.load(options.replay);
      settings = playbackLog.initial();
      if (Math.abs(
              (double) options.width / options.height
                  - (double) playbackLog.width / playbackLog.height)
          > 1e-6)
        throw new IllegalArgumentException(
            "Replay requires the recorded aspect ratio: "
                + playbackLog.width
                + "x"
                + playbackLog.height);
    }
    settings.validate();
    dev.visualengine.authoring.EffectPackage.resolveResources(settings);
    window = new Window(options.width, options.height, options.headless);
    renderer = new Renderer(new ShaderSources(options.shaders));
    renderer.configure(settings);
    device =
        glGetString(GL_VENDOR) + " / " + glGetString(GL_RENDERER) + " / " + glGetString(GL_VERSION);
    if (!options.headless) controls = new ControlPanel(this::submit, settings, data);
    glfwSetKeyCallback(
        window.handle,
        (win, key, scan, action, mods) -> {
          if (action != GLFW_PRESS) return;
          switch (key) {
            case GLFW_KEY_ESCAPE -> glfwSetWindowShouldClose(win, true);
            case GLFW_KEY_SPACE -> submit(new Command("pause", null));
            case GLFW_KEY_PERIOD -> submit(new Command("step", null));
            case GLFW_KEY_R -> submit(new Command("reset", null));
            case GLFW_KEY_P -> submit(new Command("randomPalette", null));
            case GLFW_KEY_F5 -> submit(new Command("reload", null));
            case GLFW_KEY_TAB -> {
              if (controls != null) controls.show();
            }
            case GLFW_KEY_F11 -> {
              fullscreen = !fullscreen;
              window.fullscreen(fullscreen);
            }
            default -> {}
          }
        });
    if (options.shaders != null) {
      sourceStamp = stamp(options.shaders);
      watcher.scheduleWithFixedDelay(
          () -> {
            try {
              long next = stamp(options.shaders);
              if (next != sourceStamp) {
                sourceStamp = next;
                submit(new Command("reload", null));
              }
            } catch (Exception e) {
              status(e.getMessage());
            }
          },
          500,
          500,
          TimeUnit.MILLISECONDS);
    }
    for (int warm = 0; warm < options.warmup; warm++) advance(0, 0, false);
    double last = glfwGetTime();
    int rendered = 0;
    double[] px = {0}, py = {0};
    int[] ww = {options.width}, wh = {options.height};
    while (!window.closing() && (options.frames == 0 || rendered < options.frames)) {
      long frameStart = System.nanoTime();
      glfwPollEvents();
      window.resize();
      drain();
      double now = glfwGetTime(), elapsed = discardElapsed ? 0 : now - last;
      discardElapsed = false;
      last = now;
      if (window.width == 0 || window.height == 0) {
        glfwWaitEventsTimeout(.1);
        continue;
      }
      if (reset) {
        elapsed = 0;
        clock.reset();
        signals.reset();
        renderer.reset(settings);
        lastBrush = false;
        if (recording) recordingLog = ReplayLog.begin(settings, window.width, window.height);
        reset = false;
      }
      int steps =
          options.headless
              ? (options.export != null && rendered == 0 ? 0 : 120 / options.fps)
              : clock.accumulate(elapsed, settings.speed, settings.paused);
      if (step) {
        steps = 1;
        step = false;
      }
      glfwGetCursorPos(window.handle, px, py);
      glfwGetWindowSize(window.handle, ww, wh);
      double x = (px[0] / Math.max(1, ww[0]) * 2 - 1) * window.width / window.height,
          y = 1 - py[0] / Math.max(1, wh[0]) * 2;
      boolean brush =
          glfwGetWindowAttrib(window.handle, GLFW_FOCUSED) == GLFW_TRUE
              && glfwGetMouseButton(window.handle, GLFW_MOUSE_BUTTON_LEFT) == GLFW_PRESS;
      for (int j = 0; j < steps; j++) advance(x, y, brush);
      renderer.render(
          signals.frame(settings), clock.seconds(), window.width, window.height, x, y, brush);
      if (freeze) {
        renderer.freezeFinished(window.width, window.height);
        freeze = false;
        status("Frozen A/B reference: left frozen, right live");
      }
      if (options.export != null)
        capture(
            options.export.resolve(
                String.format(java.util.Locale.ROOT, "frame-%06d.png", rendered)),
            true);
      if (rendered % 120 == 0 && controls != null)
        controls.showStatus(
            String.format(
                java.util.Locale.ROOT,
                "Tick %d | GPU %.2f ms | %.1f MiB",
                clock.tick(),
                renderer.gpuMilliseconds(),
                renderer.bytes() / 1048576.));
      if (capture != null) {
        capture(capture);
        capture = null;
      }
      if (options.capture != null && rendered + 1 == options.frames) capture(options.capture);
      glfwSwapBuffers(window.handle);
      rendered++;
      benchmark.record(
          (System.nanoTime() - frameStart) / 1e6,
          renderer.gpuMilliseconds(),
          renderer.tickMilliseconds());
      if ((settings.paused || settings.speed == 0) && !options.headless) glfwWaitEvents();
    }
    if (options.export != null) {
      var manifest = PresetIO.document(settings, clock.tick());
      manifest.addProperty("fps", options.fps);
      manifest.addProperty("frames", rendered);
      manifest.addProperty("warmupTicks", options.warmup);
      manifest.addProperty("device", device);
      manifest.addProperty("interval", "half-open; first frame follows warmup");
      enqueueIOBlocking(() -> {
        if (ioFailure.get() == null)
          PresetIO.atomicWrite(
              options.export.resolve("sequence.json"), PresetIO.JSON.toJson(manifest));
      });
    }
    if (options.benchmark != null) {
      var report =
          benchmark.report(settings, window.width, window.height, device, renderer.bytes());
      enqueueIOBlocking(
          () -> PresetIO.atomicWrite(options.benchmark, PresetIO.JSON.toJson(report)));
    }
    System.out.println("Rendered " + rendered + " frames at tick " + clock.tick());
  }

  private void advance(double x, double y, boolean brush) {
    long nextTick = clock.tick() + 1;
    if (playbackLog != null) {
      for (var event : playbackLog.at(nextTick)) {
        if (event.kind().equals("controls")) {
          settings = PresetIO.parse(event.preset().toString());
          renderer.configure(settings);
        } else {
          replayX = event.x();
          replayY = event.y();
          replayBrush = event.strength() > 0;
          settings.set("radius", event.radius());
        }
      }
      if (nextTick > playbackLog.endTick) replayBrush = false;
      x = replayX;
      y = replayY;
      brush = replayBrush;
    }
    if (recording) {
      if (Math.abs(
              (double) window.width / window.height
                  - (double) recordingLog.width / recordingLog.height)
          > 1e-6) {
        recording = false;
        status("Recording stopped because viewport aspect changed");
      } else {
        if (brush || lastBrush)
          recordingLog.recordPointer(nextTick, x, y, settings.get("radius"), brush);
        recordingLog.endTick = nextTick;
      }
    }
    Settings active = signals.tick(settings, nextTick * Playback.DT);
    renderer.tick(
        active, nextTick * Playback.DT, (double) window.width / window.height, x, y, brush);
    clock.advance();
    lastBrush = brush;
  }

  private static long stamp(Path root) throws java.io.IOException {
    try (var paths = Files.walk(root)) {
      return paths
          .filter(Files::isRegularFile)
          .mapToLong(p -> {
            try {
              return Files.getLastModifiedTime(p).toMillis() ^ Files.size(p);
            } catch (Exception e) {
              return 0;
            }
          })
          .sum();
    }
  }

  private void submit(Command c) {
    if (accepting) {
      commands.add(c);
      glfwPostEmptyEvent();
    }
  }

  private void status(String text) {
    System.out.println(text);
    if (controls != null) controls.showStatus(text);
  }

  private void remember() {
    undo.push(settings.copy());
    while (undo.size() > 64) undo.removeLast();
  }

  private void drain() {
    // Coalesce slider drags and reload notifications before touching GPU resources.
    Map<String, Command> edits = new LinkedHashMap<>();
    Command c;
    while ((c = commands.poll()) != null) {
      String key =
          c.type().equals("parameter") ? "parameter:" + ((Command.Edit) c.value()).id() : c.type();
      if (c.type().equals("parameter") || c.type().equals("reload")) edits.put(key, c);
      else apply(c);
    }
    edits.values().forEach(this::apply);
  }

  private void apply(Command c) {
    Settings before = settings.copy();
    boolean resetBefore = reset;
    try {
      switch (c.type()) {
        case "pause" -> {
          settings.paused = !settings.paused;
          discardElapsed = true;
        }
        case "step" -> {
          settings.paused = true;
          step = true;
        }
        case "reset" -> reset = true;
        case "reload" -> {
          renderer.reload();
          status("Engine shaders reloaded");
        }
        case "parameter" -> {
          remember();
          var e = (Command.Edit) c.value();
          settings.set(e.id(), e.value());
        }
        case "palette" -> {
          remember();
          settings.palette = (int) c.value();
        }
        case "debug" -> settings.debug = (int) c.value();
        case "seed" -> {
          remember();
          settings.seed = (long) c.value();
          reset = true;
        }
        case "recipe" -> {
          remember();
          dev.visualengine.model.Recipes.apply(settings, (Settings.Recipe) c.value());
          reset = true;
          if (controls != null) controls.sync(settings);
        }
        case "feedbackEnabled" -> settings.feedbackEnabled = (boolean) c.value();
        case "pigmentEnabled" -> settings.pigmentEnabled = (boolean) c.value();
        case "reactionEnabled" -> settings.reactionEnabled = (boolean) c.value();
        case "particlesEnabled" -> settings.particlesEnabled = (boolean) c.value();
        case "grid" -> {
          settings.grid = ((Number) c.value()).intValue();
          reset = true;
        }
        case "particles" -> {
          settings.particles = ((Number) c.value()).intValue();
          reset = true;
        }
        case "historyLayers" -> {
          settings.historyLayers = ((Number) c.value()).intValue();
          reset = true;
        }
        case "speed" -> {
          settings.speed = ((Number) c.value()).doubleValue();
          discardElapsed = true;
        }
        case "displayScale" -> settings.displayScale = ((Number) c.value()).doubleValue();
        case "loopSeconds" -> settings.loopSeconds = ((Number) c.value()).doubleValue();
        case "tileMode" -> settings.tileMode = (boolean) c.value();
        case "tilePreview" -> settings.tilePreview = (boolean) c.value();
        case "modulations" -> {
          remember();
          settings.modulations = new ArrayList<>((List<Modulation>) c.value());
        }
        case "record" -> {
          recording = true;
          playbackLog = null;
          reset = true;
          status("Recording a new take from reset");
        }
        case "stopRecord" -> {
          recording = false;
          status("Recording stopped");
        }
        case "saveReplay" -> {
          recording = false;
          if (recordingLog == null) throw new IllegalArgumentException("Record a take first");
          ReplayLog snapshot = recordingLog;
          enqueueIO(() -> snapshot.save((Path) c.value()));
        }
        case "loadReplay" ->
            enqueueIO(() -> submit(new Command("replayLoaded", ReplayLog.load((Path) c.value()))));
        case "replayLoaded" -> {
          ReplayLog candidate = (ReplayLog) c.value();
          if (Math.abs(
                  (double) window.width / window.height
                      - (double) candidate.width / candidate.height)
              > 1e-6)
            throw new IllegalArgumentException(
                "Replay requires the recorded viewport aspect ratio");
          playbackLog = candidate;
          playbackLog.rewind();
          settings = playbackLog.initial();
          recording = false;
          reset = true;
          replayBrush = false;
          replayX = replayY = 0;
          if (controls != null) controls.sync(settings);
        }
        case "lock" -> {
          var entry = (Map.Entry<?, ?>) c.value();
          if ((boolean) entry.getValue()) settings.locks.add((String) entry.getKey());
          else settings.locks.remove(entry.getKey());
        }
        case "undo" -> {
          if (!undo.isEmpty()) {
            settings = undo.pop();
            if (controls != null) controls.sync(settings);
          }
        }
        case "randomSeed", "randomPalette", "randomMotion", "randomComposition" -> {
          remember();
          Random random = new Random(System.nanoTime());
          if (c.type().equals("randomSeed")) settings.seed = random.nextLong();
          else if (c.type().equals("randomPalette")) settings.palette = random.nextInt(4);
          else
            for (String id :
                c.type().equals("randomMotion")
                    ? List.of("motion", "colorRate")
                    : List.of("scale", "warp", "detail", "role"))
              if (!settings.locks.contains(id)) {
                var p =
                    Settings.PARAMETERS.stream()
                        .filter(d -> d.id().equals(id))
                        .findFirst()
                        .orElseThrow();
                settings.set(id, p.minimum() + (p.maximum() - p.minimum()) * random.nextDouble());
              }
          reset = c.type().equals("randomSeed");
          if (controls != null) controls.sync(settings);
        }
        case "save" -> {
          Settings snapshot = settings.copy();
          long tick = clock.tick();
          enqueueIO(() -> {
            PresetIO.save((Path) c.value(), snapshot, tick);
            status("Preset saved");
          });
        }
        case "load" ->
            enqueueIO(() -> submit(new Command("loaded", PresetIO.load((Path) c.value()))));
        case "loaded" -> {
          remember();
          settings = (Settings) c.value();
          reset = true;
          if (controls != null) controls.sync(settings);
          status("Preset loaded; state restarted");
        }
        case "capture" -> capture = (Path) c.value();
        case "freeze" -> freeze = true;
        case "clearReference" -> renderer.clearReference();
        case "bookmark" ->
            capture =
                data.resolve("discoveries").resolve("study-" + System.currentTimeMillis() + ".png");
        case "expression" -> {
          remember();
          var f = dev.visualengine.authoring.Formula.compile((String) c.value());
          if (f.root().type() != dev.visualengine.authoring.Formula.Type.FLOAT)
            throw new IllegalArgumentException("Formula output must be scalar");
          settings.expression = (String) c.value();
          settings.graphEnabled = false;
          settings.recipe = Settings.Recipe.CUSTOM;
        }
        case "graph" -> {
          remember();
          var g = (dev.visualengine.authoring.EffectGraph) c.value();
          var compiled = dev.visualengine.authoring.GraphCompiler.compile(g);
          settings.graph = g.copy();
          settings.graphEnabled = true;
          settings.recipe = Settings.Recipe.CUSTOM;
          if (compiled.resources().contains("previousVisualColor")) settings.feedbackEnabled = true;
          if (compiled.resources().contains("pigment")) settings.pigmentEnabled = true;
        }
        case "importEffect" ->
            enqueueIO(() ->
                submit(
                    new Command(
                        "effectLoaded",
                        dev.visualengine.authoring.EffectPackage.load((Path) c.value()))));
        case "effectLoaded" -> {
          remember();
          ((dev.visualengine.authoring.EffectPackage) c.value()).apply(settings);
          reset = true;
          if (controls != null) controls.sync(settings);
        }
        case "exportEffect" -> {
          var effect = new dev.visualengine.authoring.EffectPackage();
          effect.parameters = new java.util.LinkedHashMap<>(settings.values);
          effect.expression = settings.expression;
          effect.graph = settings.graph.copy();
          effect.authoring = settings.graphEnabled ? "graph" : "expression";
          enqueueIO(() -> effect.save((Path) c.value()));
        }
        default -> throw new IllegalArgumentException("Unknown command " + c.type());
      }
      settings.validate();
      dev.visualengine.authoring.EffectPackage.resolveResources(settings);
      renderer.configure(settings);
      if (settings.paused) signals.snap(settings);
      if (recording
          && recordingLog != null
          && !c.type().equals("record")
          && !c.type().equals("saveReplay")) recordingLog.recordControl(clock.tick() + 1, settings);
    } catch (Exception e) {
      reset = resetBefore;
      settings = before;
      if (controls != null) controls.sync(settings);
      System.err.println(e);
      status(e.getMessage());
    }
  }

  @FunctionalInterface
  private interface IOAction {
    void run() throws Exception;
  }

  private void enqueueIO(IOAction action) {
    try {
      io.execute(() -> {
        try {
          action.run();
        } catch (Exception e) {
          if (options.headless) ioFailure.compareAndSet(null, e);
          System.err.println(e);
          status(e.getMessage());
        }
      });
    } catch (RejectedExecutionException e) {
      status("File queue is full; wait for the current capture");
    }
  }

  private void enqueueIOBlocking(IOAction action) {
    Runnable work =
        () -> {
          try {
            action.run();
          } catch (Exception e) {
            ioFailure.compareAndSet(null, e);
            status(e.getMessage());
          }
        };
    try {
      io.execute(work);
    } catch (RejectedExecutionException full) {
      try {
        io.getQueue().put(work);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("Export cancelled", e);
      }
    }
  }

  private void capture(Path path) {
    capture(path, false);
  }

  private void capture(Path path, boolean offline) {
    var image = Capture.read(window.width, window.height);
    Settings snapshot = settings.copy();
    var active = signals.frame(settings);
    snapshot.motionClock = active.motionClock;
    snapshot.colorClock = active.colorClock;
    long tick = clock.tick();
    IOAction write =
        () -> {
          Capture.write(path, image, snapshot, tick, device);
          status("Captured " + path);
        };
    if (offline) enqueueIOBlocking(write);
    else enqueueIO(write);
  }

  @Override
  public void close() {
    accepting = false;
    watcher.shutdownNow();
    io.shutdown();
    try {
      if (!io.awaitTermination(30, TimeUnit.SECONDS)) io.shutdownNow();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      io.shutdownNow();
    }
    if (controls != null) controls.close();
    if (renderer != null) renderer.close();
    if (window != null) window.close();
    if (ioFailure.get() != null)
      throw new IllegalStateException("A file operation failed", ioFailure.get());
  }
}
