package dev.visualengine.app;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL43C.*;

import dev.visualengine.export.Capture;
import dev.visualengine.export.Periodicity;
import dev.visualengine.export.TextureMap;
import dev.visualengine.gpu.*;
import dev.visualengine.io.FileJobs;
import dev.visualengine.model.PresetMorph;
import dev.visualengine.model.Settings;
import dev.visualengine.platform.DesktopInput;
import dev.visualengine.platform.DesktopLifecycle;
import dev.visualengine.platform.Presentation;
import dev.visualengine.platform.PresentationIO;
import dev.visualengine.platform.RedrawDemand;
import dev.visualengine.platform.Window;
import dev.visualengine.presets.CheckpointIO;
import dev.visualengine.presets.DiscoveryStore;
import dev.visualengine.presets.PresetIO;
import dev.visualengine.time.*;
import dev.visualengine.ui.ControlPanel;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

public final class Engine implements AutoCloseable {
  private final Options options;
  private final Path data;
  private final DiscoveryStore discoveries;
  private String bookmark, sourceDiscovery;
  private final ConcurrentLinkedQueue<Command> commands = new ConcurrentLinkedQueue<>();
  private final FileJobs files;
  private final ScheduledExecutorService watcher = Executors.newSingleThreadScheduledExecutor();
  private final Playback clock = new Playback();
  private final ControlSignals signals = new ControlSignals();
  private final FramePacer pacer = new FramePacer();
  private final DesktopLifecycle desktop = new DesktopLifecycle();
  private final RedrawDemand redraw = new RedrawDemand();
  private boolean pointerChangedSinceFrame;
  private Presentation presentation = Presentation.DEFAULT;
  private boolean presentationBusy;
  private ReplayLog playbackLog, recordingLog;
  private boolean recording, lastBrush, replayBrush;
  private double replayX, replayY;
  private final dev.visualengine.model.SceneHistory undo =
      new dev.visualengine.model.SceneHistory(64);
  private Settings settings = new Settings();
  private final PresetMorph.Session morph = new PresetMorph.Session();
  private Window window;
  private Renderer renderer;
  private ControlPanel controls;
  private boolean step, reset, freeze, discardElapsed;
  private final dev.visualengine.export.Benchmark benchmark;
  private Path capture;
  private Command.Still stillCapture;
  private Command.MapExport mapExport;
  private Command.Inspection inspectionRequest;
  private final java.util.concurrent.atomic.AtomicBoolean inspectionBusy =
      new java.util.concurrent.atomic.AtomicBoolean();
  private final java.util.concurrent.atomic.AtomicBoolean mapBusy =
      new java.util.concurrent.atomic.AtomicBoolean();
  private Path checkpointSave;
  private final java.util.concurrent.atomic.AtomicBoolean checkpointBusy =
      new java.util.concurrent.atomic.AtomicBoolean();
  private CheckpointIO.Pointer renderedPointer = new CheckpointIO.Pointer(0, 0, false);
  private boolean resumeFrame;
  private String device;
  private long sourceStamp;
  private volatile boolean accepting = true;

  public Engine(Options options, Path data) {
    this.options = options;
    this.files =
        new FileJobs(
            2,
            options.headless,
            failure -> {
              System.err.println(failure);
              status(failure.getMessage());
            });
    this.benchmark = options.benchmark == null ? null : new dev.visualengine.export.Benchmark();
    this.data = data;
    this.discoveries =
        new DiscoveryStore(
            options.discoveries == null ? data.resolve("discoveries") : options.discoveries);
  }

  public void run() throws Exception {
    if (options.discovery != null) {
      settings = discoveries.load(options.discovery).settings();
      sourceDiscovery = options.discovery;
    }
    CheckpointIO.Snapshot initialCheckpoint = null;
    if (options.checkpoint != null) {
      initialCheckpoint = CheckpointIO.load(options.checkpoint);
      settings = initialCheckpoint.settings().copy();
      if (!options.explicitSize) {
        options.width = initialCheckpoint.width();
        options.height = initialCheckpoint.height();
      }
      CheckpointIO.requireAspect(
          options.width, options.height, initialCheckpoint.width(), initialCheckpoint.height());
    }
    if (options.preset != null) settings = PresetIO.load(options.preset);
    if (options.effect != null)
      dev.visualengine.authoring.EffectPackage.load(options.effect).apply(settings);
    if (options.recipe != null) dev.visualengine.model.Recipes.apply(settings, options.recipe);
    if (options.quality != null) settings = options.quality.apply(settings);
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
    if (options.morphTarget != null)
      settings =
          PresetMorph.blend(
              settings, PresetIO.load(options.morphTarget), settings, options.morphAmount);
    String presentationNotice = null;
    if (!options.headless) {
      try {
        presentation = PresentationIO.load(data.resolve("presentation.json"));
      } catch (Exception e) {
        presentation = Presentation.DEFAULT;
        presentationNotice = "Using default presentation settings: " + e.getMessage();
      }
      presentation =
          new Presentation(
              options.vsync == null ? presentation.vsync() : options.vsync,
              options.frameLimit == null ? presentation.frameLimit() : options.frameLimit,
              presentation.suspendWhenMinimized());
    }
    window = new Window(options.width, options.height, options.headless, presentation);
    presentation = window.presentation();
    System.err.println(
        "Resolved quality: grid="
            + settings.grid
            + " particles="
            + settings.particles
            + " historyLayers="
            + settings.historyLayers
            + " displayScale="
            + settings.displayScale
            + " framebuffer="
            + window.width
            + "x"
            + window.height);
    pacer.configure(presentation.frameLimit());
    renderer = new Renderer(new ShaderSources(options.shaders), options.shaderCache);
    if (initialCheckpoint != null) restoreCheckpoint(initialCheckpoint);
    else renderer.configure(settings);
    if (options.referenceDiscovery != null)
      renderer.setReference(Capture.pixels(discoveries.artwork(options.referenceDiscovery)));
    device =
        glGetString(GL_VENDOR) + " / " + glGetString(GL_RENDERER) + " / " + glGetString(GL_VERSION);
    if (!options.headless) controls = new ControlPanel(this::submit, settings, data, presentation);
    if (presentationNotice != null) status(presentationNotice);
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
            case GLFW_KEY_F11 -> submit(new Command("fullscreen", null));
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
    while (!window.closing() && (options.frames == 0 || rendered < options.frames)) {
      files.checkFailure();
      long frameStart = System.nanoTime();
      glfwPollEvents();
      window.resize();
      drain();
      if (window.closing()) break;
      if (window.consumeTimingReset()) desktop.interrupt();
      if (window.consumeRedrawRequest()) redraw.invalidate();
      pointerChangedSinceFrame |= window.consumePointerChange();
      var activity =
          desktop.update(
              options.headless,
              window.iconified(),
              window.width,
              window.height,
              presentation.suspendWhenMinimized());
      if (activity.discardElapsed()) {
        discardElapsed = true;
        clock.discardBacklog();
        pacer.reset();
      }
      if (activity.suspended()) {
        last = glfwGetTime();
        glfwWaitEventsTimeout(.1);
        continue;
      }
      boolean frozen = settings.paused || settings.speed == 0;
      var pointer = window.pointer();
      if (pointerChangedSinceFrame && playbackLog == null && !resumeFrame)
        redraw.pointerChanged(pointer);
      if (!redraw.needsFrame(
          options.headless, frozen, frameWorkPending(), window.width, window.height)) {
        last = glfwGetTime();
        discardElapsed = true;
        clock.discardBacklog();
        if (!commands.isEmpty()) continue;
        redraw.idleWait();
        glfwWaitEventsTimeout(.1);
        continue;
      }
      if (!options.headless) {
        long wait = pacer.waitNanos(System.nanoTime());
        if (wait > 0) {
          glfwWaitEventsTimeout(wait / 1e9);
          continue;
        }
      }
      double now = glfwGetTime(), elapsed = discardElapsed ? 0 : now - last;
      discardElapsed = false;
      last = now;
      if (reset) {
        elapsed = 0;
        clock.reset();
        signals.reset();
        renderer.reset(settings);
        lastBrush = false;
        if (recording) recordingLog = ReplayLog.begin(settings, window.width, window.height);
        reset = false;
      }
      if (benchmark != null) renderer.measurementFrame(rendered);
      if (!options.headless) pacer.frameStarted(System.nanoTime());
      int steps =
          options.headless
              ? (options.export != null && rendered == 0 ? 0 : 120 / options.fps)
              : clock.accumulate(elapsed, settings.speed, settings.paused);
      if (step) {
        steps = 1;
        step = false;
      }
      double x = pointer.x(), y = pointer.y();
      boolean brush = pointer.brush();
      for (int j = 0; j < steps; j++) advance(x, y, brush);
      if (playbackLog != null) {
        x = replayX;
        y = replayY;
        brush = replayBrush;
      } else if (steps > 0) {
        brush = lastBrush;
      } else if (frozen && !pointerChangedSinceFrame) {
        x = renderedPointer.x();
        y = renderedPointer.y();
        brush = renderedPointer.brush();
      }
      if (resumeFrame && steps == 0) {
        x = renderedPointer.x();
        y = renderedPointer.y();
        brush = renderedPointer.brush();
      }
      resumeFrame = false;
      renderedPointer = new CheckpointIO.Pointer(x, y, brush);
      long drawRevision = redraw.beginFrame();
      renderer.render(
          signals.frame(settings), clock.seconds(), window.width, window.height, x, y, brush);
      if (freeze) {
        freeze = false;
        try {
          renderer.freezeFinished(window.width, window.height);
          status("Frozen A/B reference: left frozen, right live");
        } catch (RuntimeException e) {
          status("Reference capture failed: " + e.getMessage());
        }
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
      if (stillCapture != null) {
        captureStill(stillCapture, false);
        stillCapture = null;
      }
      if (options.capture != null && rendered + 1 == options.frames)
        captureStill(new Command.Still(options.capture, options.captureSize), options.headless);
      if (mapExport != null) {
        exportMap(mapExport, false);
        mapExport = null;
      }
      if (options.mapOutput != null && rendered + 1 == options.frames)
        exportMap(
            new Command.MapExport(
                options.mapOutput,
                options.mapKind,
                options.mapSize == null
                    ? new dev.visualengine.export.CaptureSize(window.width, window.height)
                    : options.mapSize,
                options.mapRange),
            options.headless);
      if (checkpointSave != null) {
        saveCheckpoint(checkpointSave, false);
        checkpointSave = null;
      }
      if (inspectionRequest != null) {
        inspect(inspectionRequest, false);
        inspectionRequest = null;
      }
      if (options.inspection != null && rendered + 1 == options.frames)
        inspect(
            new Command.Inspection(options.inspection, options.inspectionSize), options.headless);
      if (bookmark != null) {
        saveDiscovery(bookmark, false);
        bookmark = null;
      }
      if (options.bookmark != null && rendered + 1 == options.frames)
        saveDiscovery(options.bookmark, options.headless);
      glfwSwapBuffers(window.handle);
      redraw.presented(
          drawRevision, window.width, window.height, new DesktopInput.Pointer(x, y, brush));
      pointerChangedSinceFrame = false;
      if (benchmark != null) {
        var measurements = renderer.collectMeasurements();
        benchmark.recordFrame(rendered, (System.nanoTime() - frameStart) / 1e6);
        benchmark.accept(measurements.render(), measurements.tick());
      }
      rendered++;
    }
    if (options.saveCheckpoint != null) saveCheckpoint(options.saveCheckpoint, options.headless);
    if (options.export != null) {
      var manifest = PresetIO.document(settings, clock.tick());
      manifest.addProperty("fps", options.fps);
      manifest.addProperty("frames", rendered);
      manifest.addProperty("warmupTicks", options.warmup);
      manifest.addProperty("device", device);
      manifest.addProperty("interval", "half-open; first frame follows warmup");
      enqueueIOBlocking(
          () ->
              PresetIO.atomicWrite(
                  options.export.resolve("sequence.json"), PresetIO.JSON.toJson(manifest)));
    }
    if (benchmark != null) {
      var tail = renderer.finishMeasurements();
      benchmark.accept(tail.render(), tail.tick());
      Settings snapshot = settings.copy();
      var active = signals.frame(settings);
      snapshot.motionClock = active.motionClock;
      snapshot.colorClock = active.colorClock;
      var report =
          benchmark.report(
              snapshot, clock.tick(), window.width, window.height, device, renderer.bytes());
      report.add("presentationRequest", PresetIO.JSON.toJsonTree(presentation));
      report.add("deviceCapabilities", PresetIO.JSON.toJsonTree(window.capabilities()));
      report.add("shaderCache", PresetIO.JSON.toJsonTree(renderer.shaderCacheStats()));
      report.add("allocation", PresetIO.JSON.toJsonTree(renderer.allocationStats()));
      report.add("fileQueue", PresetIO.JSON.toJsonTree(files.snapshot()));
      report.add("redraw", PresetIO.JSON.toJsonTree(redraw.snapshot()));
      enqueueIOBlocking(
          () -> PresetIO.atomicWrite(options.benchmark, PresetIO.JSON.toJson(report)));
    }
    System.out.println("Rendered " + rendered + " frames at tick " + clock.tick());
  }

  private void advance(double x, double y, boolean brush) {
    long nextTick = clock.tick() + 1;
    if (playbackLog == null) brush = window.tickBrush(brush);
    if (playbackLog != null) {
      for (var event : playbackLog.at(nextTick)) {
        if (event.kind().equals("controls")) {
          settings = PresetIO.parse(event.preset().toString());
          renderer.configure(settings);
          refreshMorph();
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

  private boolean frameWorkPending() {
    return options.frames > 0
        || reset
        || step
        || freeze
        || capture != null
        || stillCapture != null
        || mapExport != null
        || checkpointSave != null
        || inspectionRequest != null
        || bookmark != null;
  }

  private static long stamp(Path root) throws java.io.IOException {
    try (var paths = Files.walk(root)) {
      return paths
          .filter(Files::isRegularFile)
          .mapToLong(
              p -> {
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

  private void drain() {
    Command.drain(commands, this::apply);
  }

  private void apply(Command c) {
    if (c.needsFrame()) redraw.invalidate();
    Settings before = settings.copy();
    String sourceBefore = sourceDiscovery;
    boolean resetBefore = reset,
        stepBefore = step,
        freezeBefore = freeze,
        elapsedBefore = discardElapsed,
        recordingBefore = recording,
        brushBefore = replayBrush;
    ReplayLog playbackBefore = playbackLog, recordingLogBefore = recordingLog;
    int cursorBefore = playbackBefore == null ? 0 : playbackBefore.cursor();
    double xBefore = replayX, yBefore = replayY;
    boolean resolveHeldControls = c.editsHeldControls();
    try (var change = undo.begin()) {
      switch (c.type()) {
        case "gpuReport" -> {
          var report = dev.visualengine.export.GpuReport.document(window.capabilities(), null);
          var scene = new com.google.gson.JsonObject();
          scene.addProperty("referenceTick", clock.tick());
          scene.addProperty("grid", settings.grid);
          scene.addProperty("particles", settings.particles);
          scene.addProperty("historyLayers", settings.historyLayers);
          scene.addProperty("displayScale", settings.displayScale);
          scene.addProperty("framebufferWidth", window.width);
          scene.addProperty("framebufferHeight", window.height);
          scene.addProperty("allocatedBytes", renderer.bytes());
          scene.add("presentationRequest", PresetIO.JSON.toJsonTree(presentation));
          report.add("scene", scene);
          report.add("shaderCache", PresetIO.JSON.toJsonTree(renderer.shaderCacheStats()));
          report.add("allocation", PresetIO.JSON.toJsonTree(renderer.allocationStats()));
          report.add("fileQueue", PresetIO.JSON.toJsonTree(files.snapshot()));
          report.add("redraw", PresetIO.JSON.toJsonTree(redraw.snapshot()));
          enqueueIO(
              () -> {
                PresetIO.atomicWrite(
                    (Path) c.value(), dev.visualengine.export.GpuReport.encode(report));
                status("GPU capability report saved: " + c.value());
              });
          return;
        }
        case "fullscreen" -> {
          if (!options.headless) {
            boolean enabled = window.toggleFullscreen();
            status(enabled ? "Fullscreen on current monitor" : "Restored windowed placement");
          }
          return;
        }
        case "presentation" -> {
          if (options.headless)
            throw new IllegalArgumentException("Presentation controls are desktop-only");
          changePresentation((Presentation) c.value());
          return;
        }
        case "presentationSaved" -> {
          var result = (Command.PresentationSaved) c.value();
          presentationBusy = false;
          String message =
              result.failure() == null
                  ? "Presentation settings saved"
                  : "Presentation applied for this session; save failed: " + result.failure();
          if (controls != null) controls.showPresentation(presentation, message, false);
          status(message);
          return;
        }
        case "inspect" -> {
          var request = (Command.Inspection) c.value();
          Periodicity.requireSize(request.size());
          if (inspectionRequest != null || inspectionBusy.get())
            status("Wait for the current inspection");
          else inspectionRequest = request;
          return;
        }
        case "morphCapture" -> {
          morph.capture((PresetMorph.Slot) c.value(), settings);
          refreshMorph();
          status("Captured morph endpoint " + c.value() + "; live scene preserved");
          return;
        }
        case "morphLoad" -> {
          var request = (Command.MorphLoad) c.value();
          long generation = morph.begin(request.slot());
          enqueueIO(
              () ->
                  submit(
                      new Command(
                          "morphLoaded",
                          new Command.MorphLoaded(
                              request.slot(), generation, PresetIO.load(request.path())))));
          return;
        }
        case "morphLoaded" -> {
          var loaded = (Command.MorphLoaded) c.value();
          if (morph.complete(loaded.slot(), loaded.generation(), loaded.settings())) {
            refreshMorph();
            status("Loaded morph endpoint " + loaded.slot() + "; live scene preserved");
          }
          return;
        }
        case "morphBlend" -> {
          Settings candidate = morph.blend(settings, ((Number) c.value()).doubleValue());
          change.remember(settings);
          settings = candidate;
        }
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
          change.remember(settings);
          var e = (Command.Edit) c.value();
          settings.set(e.id(), e.value());
        }
        case "palette" -> {
          change.remember(settings);
          settings.palette = (int) c.value();
        }
        case "debug" -> {
          change.remember(settings);
          settings.debug = (int) c.value();
        }
        case "seed" -> {
          change.remember(settings, true);
          settings.seed = (long) c.value();
          reset = true;
        }
        case "recipe" -> {
          change.remember(settings, true);
          dev.visualengine.model.Recipes.apply(settings, (Settings.Recipe) c.value());
          reset = true;
          if (controls != null) controls.sync(settings);
        }
        case "feedbackEnabled" -> {
          change.remember(settings);
          settings.feedbackEnabled = (boolean) c.value();
        }
        case "pigmentEnabled" -> {
          change.remember(settings);
          settings.pigmentEnabled = (boolean) c.value();
        }
        case "reactionEnabled" -> {
          change.remember(settings);
          settings.reactionEnabled = (boolean) c.value();
        }
        case "particlesEnabled" -> {
          change.remember(settings);
          settings.particlesEnabled = (boolean) c.value();
        }
        case "grid" -> {
          change.remember(settings);
          settings.grid = ((Number) c.value()).intValue();
          reset = true;
        }
        case "quality" -> {
          var profile = (dev.visualengine.model.QualityProfile) c.value();
          Settings candidate = profile.apply(settings);
          change.remember(settings);
          reset |= profile.resets(settings);
          settings = candidate;
          if (controls != null) controls.sync(settings);
        }
        case "particles" -> {
          change.remember(settings);
          settings.particles = ((Number) c.value()).intValue();
          reset = true;
        }
        case "historyLayers" -> {
          change.remember(settings);
          settings.historyLayers = ((Number) c.value()).intValue();
          reset = true;
        }
        case "speed" -> {
          change.remember(settings);
          settings.speed = ((Number) c.value()).doubleValue();
          discardElapsed = true;
        }
        case "displayScale" -> {
          change.remember(settings);
          settings.displayScale = ((Number) c.value()).doubleValue();
        }
        case "loopSeconds" -> {
          change.remember(settings);
          settings.loopSeconds = ((Number) c.value()).doubleValue();
        }
        case "tileMode" -> {
          change.remember(settings);
          settings.tileMode = (boolean) c.value();
        }
        case "tilePreview" -> {
          change.remember(settings);
          settings.tilePreview = (boolean) c.value();
        }
        case "modulations" -> {
          change.remember(settings);
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
          sourceDiscovery = null;
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
          change.remember(settings);
          var entry = (Map.Entry<?, ?>) c.value();
          if ((boolean) entry.getValue()) settings.locks.add((String) entry.getKey());
          else settings.locks.remove(entry.getKey());
        }
        case "undo" -> {
          var restored = change.undo(settings);
          if (restored.isEmpty()) {
            status("Nothing to undo");
            return;
          }
          reset |= restored.get().restart();
          resolveHeldControls = restored.get().resolveHeldControls();
          settings = restored.get().settings();
          if (controls != null) controls.sync(settings);
        }
        case "randomSeed", "randomPalette", "randomMotion", "randomComposition" -> {
          change.remember(settings);
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
          enqueueIO(
              () -> {
                PresetIO.save((Path) c.value(), snapshot, tick);
                status("Preset saved");
              });
        }
        case "load" ->
            enqueueIO(() -> submit(new Command("loaded", PresetIO.load((Path) c.value()))));
        case "loaded" -> {
          sourceDiscovery = null;
          change.remember(settings, true);
          settings = (Settings) c.value();
          reset = true;
          if (controls != null) controls.sync(settings);
          status("Preset loaded; state restarted");
        }
        case "saveCheckpoint" -> {
          checkpointSave = (Path) c.value();
          return;
        }
        case "loadCheckpoint" -> {
          if (!checkpointBusy.compareAndSet(false, true))
            throw new IllegalArgumentException("Wait for the current checkpoint operation");
          if (!enqueueIO(
              () -> {
                try {
                  submit(new Command("checkpointLoaded", CheckpointIO.load((Path) c.value())));
                } catch (Exception e) {
                  checkpointBusy.set(false);
                  throw e;
                }
              })) checkpointBusy.set(false);
          return;
        }
        case "checkpointLoaded" -> {
          try {
            restoreCheckpoint((CheckpointIO.Snapshot) c.value());
          } finally {
            checkpointBusy.set(false);
          }
          return;
        }
        case "capture" -> capture = (Path) c.value();
        case "captureStill" -> {
          stillCapture = (Command.Still) c.value();
          return;
        }
        case "exportMap" -> {
          mapExport = (Command.MapExport) c.value();
          return;
        }
        case "freeze" -> freeze = true;
        case "clearReference" -> renderer.clearReference();
        case "bookmark" -> {
          bookmark = settings.name.replaceAll("\\p{Cc}", " ").strip();
          if (bookmark.isEmpty()) bookmark = "Untitled study";
          return;
        }
        case "discoveryScan" -> {
          long generation = ((Command.History) c.value()).generation();
          if (!enqueueIO(
                  () -> {
                    try {
                      var scan = discoveries.scan();
                      if (controls != null) controls.showDiscoveries(generation, scan);
                    } catch (Exception e) {
                      if (controls != null) controls.discoveryFailure(generation, e.getMessage());
                    }
                  })
              && controls != null)
            controls.discoveryFailure(
                generation, "File queue full; try Refresh after captures finish");
          return;
        }
        case "discoveryThumbnails" -> {
          var request = (Command.Thumbnails) c.value();
          enqueueIO(
              () -> {
                Map<String, java.awt.image.BufferedImage> images = new LinkedHashMap<>();
                for (String id : request.ids()) {
                  try {
                    images.put(id, discoveries.thumbnail(id));
                  } catch (Exception e) {
                    status("Preview unavailable: " + id);
                  }
                }
                if (controls != null)
                  controls.showDiscoveryThumbnails(request.generation(), images);
              });
          return;
        }
        case "annotateDiscovery" -> {
          var edit = (Command.DiscoveryAnnotation) c.value();
          enqueueIO(
              () -> {
                discoveries.annotate(edit.id(), edit.label(), edit.favorite());
                if (controls != null) controls.discoveriesChanged();
              });
          return;
        }
        case "loadDiscovery" -> {
          var request = (Command.DiscoveryBranch) c.value();
          enqueueIO(
              () ->
                  submit(
                      new Command(
                          "discoveryLoaded",
                          new Command.DiscoveryActivation(
                              request.id(),
                              discoveries.branch(
                                  request.id(),
                                  request.variation(),
                                  request.lockComposition(),
                                  System.nanoTime())))));
          return;
        }
        case "discoveryLoaded" -> {
          var candidate = (Command.DiscoveryActivation) c.value();
          change.remember(settings, true);
          candidate.settings().validate();
          dev.visualengine.authoring.EffectPackage.resolveResources(candidate.settings());
          renderer.configure(candidate.settings());
          settings = candidate.settings();
          change.commit(settings);
          sourceDiscovery = candidate.id();
          playbackLog = null;
          recording = false;
          recordingLog = null;
          replayBrush = false;
          reset = true;
          step = freeze = false;
          discardElapsed = true;
          if (controls != null) controls.sync(settings);
          status("Discovery opened as a configuration; state restarted");
          refreshMorph();
          return;
        }
        case "discoveryReference" -> {
          enqueueIO(
              () ->
                  submit(
                      new Command(
                          "referenceLoaded",
                          Capture.pixels(discoveries.artwork((String) c.value())))));
          return;
        }
        case "referenceLoaded" -> {
          renderer.setReference((Capture.Pixels) c.value());
          status("Saved artwork loaded as A/B reference");
          return;
        }
        case "effectValue" -> {
          change.remember(settings);
          var edit = (Command.EffectEdit) c.value();
          settings.setEffectValue(edit.id(), edit.value());
          if (settings.effectControl(edit.id()).update()
              == dev.visualengine.authoring.EffectControl.Update.RESET) reset = true;
        }
        case "effectControls" -> {
          change.remember(settings);
          var controls =
              ((java.util.List<?>) c.value())
                  .stream().map(dev.visualengine.authoring.EffectControl.class::cast).toList();
          java.util.Map<String, java.util.List<Double>> retained = new java.util.LinkedHashMap<>();
          for (var control : controls)
            if (settings.effectValues.containsKey(control.id())
                && settings.effectValues.get(control.id()).size() == control.type().width)
              retained.put(control.id(), settings.effectValues.get(control.id()));
          settings.installEffectControls(controls, retained);
          reset |= dev.visualengine.model.SceneHistory.requiresRestart(before, settings);
          if (this.controls != null) this.controls.sync(settings);
        }
        case "expression" -> {
          change.remember(settings);
          dev.visualengine.authoring.EffectControl.compileField(
              (String) c.value(), settings.effectControls);
          settings.expression = (String) c.value();
          settings.graphEnabled = false;
          settings.recipe = Settings.Recipe.CUSTOM;
        }
        case "graph" -> {
          change.remember(settings);
          var g = (dev.visualengine.authoring.EffectGraph) c.value();
          var compiled =
              dev.visualengine.authoring.GraphCompiler.compile(g, settings.effectControls);
          settings.graph = g.copy();
          settings.graphEnabled = true;
          settings.recipe = Settings.Recipe.CUSTOM;
          if (compiled.resources().contains("previousVisualColor")) settings.feedbackEnabled = true;
          if (compiled.resources().contains("pigment")) settings.pigmentEnabled = true;
        }
        case "importEffect" ->
            enqueueIO(
                () ->
                    submit(
                        new Command(
                            "effectLoaded",
                            dev.visualengine.authoring.EffectPackage.load((Path) c.value()))));
        case "effectLoaded" -> {
          sourceDiscovery = null;
          change.remember(settings, true);
          ((dev.visualengine.authoring.EffectPackage) c.value()).apply(settings);
          reset = true;
          if (controls != null) controls.sync(settings);
        }
        case "exportEffect" -> {
          var effect = new dev.visualengine.authoring.EffectPackage();
          effect.parameters = new java.util.LinkedHashMap<>(settings.values);
          effect.controls = new java.util.ArrayList<>(settings.effectControls);
          effect.controlValues = new java.util.LinkedHashMap<>(settings.effectValues);
          effect.feedbackEnabled = settings.feedbackEnabled;
          effect.pigmentEnabled = settings.pigmentEnabled;
          effect.reactionEnabled = settings.reactionEnabled;
          effect.particlesEnabled = settings.particlesEnabled;
          effect.expression = settings.expression;
          effect.graph = settings.graph.copy();
          effect.authoring = settings.graphEnabled ? "graph" : "expression";
          enqueueIO(() -> effect.save((Path) c.value()));
        }
        default -> throw new IllegalArgumentException("Unknown command " + c.type());
      }
      settings.validate();
      dev.visualengine.authoring.EffectPackage.resolveResources(settings);
      ControlSignals.State held =
          ((settings.paused || settings.speed == 0) && resolveHeldControls
                  || c.type().equals("effectControls"))
              ? signals.prepareSnap(settings, clock.seconds())
              : null;
      ReplayLog.PreparedControl recorded =
          recording
                  && recordingLog != null
                  && !c.type().equals("record")
                  && !c.type().equals("saveReplay")
              ? recordingLog.prepareControl(clock.tick() + 1, settings)
              : null;
      renderer.configure(settings);
      if (held != null) signals.restore(held, settings);
      if (recorded != null) recorded.commit();
      change.commit(settings);
      if (c.type().equals("undo") && playbackLog != null) {
        playbackLog = null;
        replayBrush = false;
        status("Controls restored; replay stopped");
      }
      if (c.type().equals("morphBlend")) {
        boolean stoppedReplay = playbackLog != null;
        playbackLog = null;
        replayBrush = false;
        status(
            String.format(
                Locale.ROOT,
                "Morph applied: %.1f%% B; state and phase preserved%s",
                ((Number) c.value()).doubleValue() * 100,
                stoppedReplay ? "; replay stopped" : ""));
        if (controls != null) controls.sync(settings);
      }
      if (c.type().equals("quality")) {
        boolean stoppedReplay = playbackLog != null;
        playbackLog = null;
        replayBrush = false;
        status(
            c.value()
                + " quality applied"
                + (reset ? "; simulation restarted" : "; state preserved")
                + (stoppedReplay ? "; replay stopped" : ""));
      }
      refreshMorph();
    } catch (Exception e) {
      sourceDiscovery = sourceBefore;
      reset = resetBefore;
      step = stepBefore;
      freeze = freezeBefore;
      discardElapsed = elapsedBefore;
      playbackLog = playbackBefore;
      if (playbackBefore != null) playbackBefore.restoreCursor(cursorBefore);
      recordingLog = recordingLogBefore;
      recording = recordingBefore;
      replayBrush = brushBefore;
      replayX = xBefore;
      replayY = yBefore;
      settings = before;
      if (controls != null) controls.sync(settings);
      refreshMorph();
      System.err.println(e);
      status(e.getMessage());
    }
  }

  private void refreshMorph() {
    if (controls != null) {
      controls.showMorph(morph.view(settings));
      controls.showInspectionSettings(settings);
    }
  }

  private void changePresentation(Presentation requested) {
    if (presentationBusy) {
      status("Wait for the current presentation save");
      return;
    }
    if (!files.canAccept()) {
      status("File queue full; wait before applying presentation settings");
      return;
    }
    window.applyPresentation(requested);
    presentation = window.presentation();
    pacer.configure(presentation.frameLimit());
    presentationBusy = true;
    if (controls != null)
      controls.showPresentation(presentation, "Applied; saving preferences…", true);
    boolean queued =
        enqueueIO(
            () -> {
              String failure = null;
              try {
                PresentationIO.save(data.resolve("presentation.json"), requested);
              } catch (Exception e) {
                failure = e.toString();
              }
              submit(
                  new Command(
                      "presentationSaved", new Command.PresentationSaved(requested, failure)));
            });
    if (!queued) {
      presentationBusy = false;
      if (controls != null)
        controls.showPresentation(
            presentation, "Applied for this session; preferences were not saved", false);
    }
  }

  private void restoreCheckpoint(CheckpointIO.Snapshot snapshot) {
    snapshot.validate();
    CheckpointIO.requireAspect(window.width, window.height, snapshot.width(), snapshot.height());
    Settings target = snapshot.settings().copy();
    // Validate all CPU state before preparing GPU resources. The live renderer remains owned here.
    ControlSignals restoredSignals = new ControlSignals();
    restoredSignals.restore(snapshot.signals(), target);
    ReplayLog restoredReplay = snapshot.replay() == null ? null : snapshot.replay().log();
    if (restoredReplay != null) restoredReplay.restoreCursor(snapshot.replay().cursor());
    Renderer candidate =
        renderer.stageRestore(target, snapshot.gpu(), snapshot.tick(), window.width, window.height);
    Renderer previous = renderer;
    renderer = candidate;
    settings = target;
    sourceDiscovery = null;
    signals.restore(restoredSignals.snapshot(target), target);
    clock.restore(snapshot.tick());
    playbackLog = restoredReplay;
    replayX = snapshot.replay() == null ? 0 : snapshot.replay().pointer().x();
    replayY = snapshot.replay() == null ? 0 : snapshot.replay().pointer().y();
    replayBrush = snapshot.replay() != null && snapshot.replay().pointer().brush();
    renderedPointer = snapshot.pointer();
    resumeFrame = true;
    recording = false;
    recordingLog = null;
    lastBrush = false;
    reset = step = freeze = false;
    discardElapsed = true;
    if (!options.headless) window.interruptInput();
    undo.clear();
    previous.close();
    if (controls != null) controls.sync(settings);
    refreshMorph();
    status("Checkpoint resumed at tick " + clock.tick());
  }

  private void saveCheckpoint(Path path, boolean offline) {
    if (!checkpointBusy.compareAndSet(false, true)) {
      status("Wait for the current checkpoint operation");
      return;
    }
    try {
      var snapshot =
          new CheckpointIO.Snapshot(
              settings,
              clock.tick(),
              window.width,
              window.height,
              signals.snapshot(settings),
              renderedPointer,
              renderer.snapshot(settings, clock.tick()),
              CheckpointIO.ReplayState.capture(
                  playbackLog, new CheckpointIO.Pointer(replayX, replayY, replayBrush)));
      FileJobs.Action write =
          () -> {
            try {
              CheckpointIO.save(path, snapshot);
              status("Checkpoint saved: " + path);
            } finally {
              checkpointBusy.set(false);
            }
          };
      if (offline) enqueueIOBlocking(write);
      else if (!enqueueIO(write)) checkpointBusy.set(false);
    } catch (RuntimeException e) {
      checkpointBusy.set(false);
      if (options.headless) throw e;
      status("Checkpoint save failed: " + e.getMessage());
    }
  }

  private boolean enqueueIO(FileJobs.Action action) {
    if (files.trySubmit(action)) return true;
    status("File queue is full or closing; wait for the current capture");
    return false;
  }

  private void enqueueIOBlocking(FileJobs.Action action) {
    files.submitBlocking(action);
  }

  private void capture(Path path) {
    capture(path, false);
  }

  private void exportMap(Command.MapExport request, boolean offline) {
    if (!mapBusy.compareAndSet(false, true)) {
      status("Wait for the current texture-map export");
      return;
    }
    try {
      if (!offline && !files.canAccept()) {
        mapBusy.set(false);
        status("File queue full; wait before exporting a map");
        return;
      }
      var active = signals.frame(settings);
      var data =
          renderer.captureMap(
              active,
              clock.seconds(),
              request.kind(),
              request.size(),
              request.range(),
              renderedPointer.x(),
              renderedPointer.y(),
              renderedPointer.brush());
      var frame =
          new TextureMap.Frame(
              settings,
              active,
              clock.tick(),
              device,
              renderer.programSignature(),
              renderedPointer.x(),
              renderedPointer.y(),
              renderedPointer.brush());
      FileJobs.Action write =
          () -> {
            try {
              long clipped = TextureMap.write(request.path(), data, frame);
              status(
                  "Exported "
                      + request.kind().id()
                      + " map: "
                      + request.path()
                      + " | clipped samples: "
                      + clipped);
            } finally {
              mapBusy.set(false);
            }
          };
      if (offline) enqueueIOBlocking(write);
      else if (!enqueueIO(write)) mapBusy.set(false);
    } catch (RuntimeException e) {
      mapBusy.set(false);
      if (options.headless) throw e;
      status("Map export failed: " + e.getMessage());
    }
  }

  private void inspect(Command.Inspection request, boolean offline) {
    if (!inspectionBusy.compareAndSet(false, true)) {
      status("Wait for the current inspection");
      return;
    }
    try {
      Periodicity.requireSize(request.size());
      if (!offline && !files.canAccept()) {
        inspectionBusy.set(false);
        status("File queue full; wait before inspecting");
        return;
      }
      var active = signals.frame(settings);
      double seconds = clock.seconds();
      var frame =
          new Periodicity.Frame(
              settings,
              active,
              clock.tick(),
              seconds,
              device,
              renderer.programSignature(),
              renderedPointer.x(),
              renderedPointer.y(),
              renderedPointer.brush());
      var first =
          renderer.captureInspection(
              active,
              seconds,
              request.size(),
              renderedPointer.x(),
              renderedPointer.y(),
              renderedPointer.brush());
      var endpoint =
          Periodicity.sampleLoop(active)
              ? renderer.captureInspection(
                  active,
                  seconds + active.loopSeconds,
                  request.size(),
                  renderedPointer.x(),
                  renderedPointer.y(),
                  renderedPointer.brush())
              : null;
      FileJobs.Action write =
          () -> {
            try {
              var report = Periodicity.analyze(frame, first, endpoint);
              Periodicity.write(request.path(), frame, report);
              if (controls != null)
                controls.showInspection(report, Periodicity.preview(first), frame.tick());
              status("Inspection saved: " + request.path());
            } finally {
              inspectionBusy.set(false);
            }
          };
      if (offline) enqueueIOBlocking(write);
      else if (!enqueueIO(write)) inspectionBusy.set(false);
    } catch (RuntimeException e) {
      inspectionBusy.set(false);
      if (options.headless) throw e;
      status("Inspection failed: " + e.getMessage());
    }
  }

  private void saveDiscovery(String label, boolean offline) {
    if (!offline && !files.canAccept()) {
      status("File queue full; wait before bookmarking");
      return;
    }
    var image = Capture.read(window.width, window.height);
    Settings snapshot = settings.copy();
    var active = signals.frame(settings);
    snapshot.motionClock = active.motionClock;
    snapshot.colorClock = active.colorClock;
    long tick = clock.tick();
    var view =
        new Capture.View(
            Math.max(1, (int) (window.width * settings.displayScale)),
            Math.max(1, (int) (window.height * settings.displayScale)),
            renderer.hasReference());
    String signature = renderer.programSignature(), parent = sourceDiscovery;
    FileJobs.Action write =
        () -> {
          var saved =
              discoveries.save(label, image, snapshot, tick, device, view, signature, parent);
          status("Discovery saved: " + saved.id());
          if (controls != null) controls.discoveriesChanged();
        };
    if (offline) enqueueIOBlocking(write);
    else enqueueIO(write);
  }

  private void capture(Path path, boolean offline) {
    capture(path, offline, null);
  }

  private void captureStill(Command.Still request, boolean offline) {
    try {
      capture(request.path(), offline, request.size());
    } catch (RuntimeException e) {
      if (options.headless) throw e;
      status("Capture failed: " + e.getMessage());
    }
  }

  private void capture(Path path, boolean offline, dev.visualengine.export.CaptureSize size) {
    if (!offline && !files.canAccept()) {
      status("File queue is full; wait for the current capture");
      return;
    }
    var active = signals.frame(settings);
    var image =
        size == null
            ? Capture.read(window.width, window.height)
            : renderer.captureStill(
                active,
                clock.seconds(),
                size,
                renderedPointer.x(),
                renderedPointer.y(),
                renderedPointer.brush());
    var view =
        new Capture.View(
            size == null ? Math.max(1, (int) (window.width * active.displayScale)) : size.width(),
            size == null ? Math.max(1, (int) (window.height * active.displayScale)) : size.height(),
            renderer.hasReference());
    Settings snapshot = settings.copy();
    snapshot.motionClock = active.motionClock;
    snapshot.colorClock = active.colorClock;
    long tick = clock.tick();
    FileJobs.Action write =
        () -> {
          Capture.write(path, image, snapshot, tick, device, view);
          status("Captured " + path);
        };
    if (offline) enqueueIOBlocking(write);
    else enqueueIO(write);
  }

  @Override
  public void close() {
    accepting = false;
    watcher.shutdownNow();
    RuntimeException failure = null;
    for (AutoCloseable resource : new AutoCloseable[] {files, controls, renderer, window}) {
      if (resource == null) continue;
      try {
        resource.close();
      } catch (Exception e) {
        if (failure == null) failure = new IllegalStateException("Application shutdown failed", e);
        else failure.addSuppressed(e);
      }
    }
    if (failure != null) throw failure;
  }
}
