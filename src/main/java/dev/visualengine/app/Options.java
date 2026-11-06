package dev.visualengine.app;

import java.nio.file.Path;

public final class Options {
  public boolean headless;
  public Boolean vsync;
  public Integer frameLimit;
  public int frames, width = 1280, height = 800;
  public Path shaders, preset, capture;
  public Path effect;
  public Path inspection;
  public dev.visualengine.export.CaptureSize inspectionSize;
  public Path morphTarget;
  public Double morphAmount;
  public Path discoveries;
  public String bookmark, discovery, referenceDiscovery;
  public dev.visualengine.export.CaptureSize captureSize;
  public Path mapOutput;
  public dev.visualengine.export.TextureMap.Kind mapKind;
  public dev.visualengine.export.TextureMap.Range mapRange;
  public dev.visualengine.export.CaptureSize mapSize;
  public Path checkpoint, saveCheckpoint;
  public boolean explicitSize;
  public String expression;
  public Path export, replay;
  public Path benchmark;
  public Path benchmarkSuite;
  public dev.visualengine.export.BenchmarkWorkloads.Profile benchmarkProfile =
      dev.visualengine.export.BenchmarkWorkloads.Profile.QUICK;
  private boolean explicitBenchmarkProfile;
  public int fps = 60, warmup;
  public dev.visualengine.model.Settings.Recipe recipe;
  public dev.visualengine.model.QualityProfile quality;
  public Integer grid;
  public Long seed;

  public static Options parse(String[] args) {
    Options o = new Options();
    for (int i = 0; i < args.length; i++)
      switch (args[i]) {
        case "--headless" -> o.headless = true;
        case "--vsync" ->
            o.vsync =
                switch (args[++i]) {
                  case "on" -> true;
                  case "off" -> false;
                  default -> throw new IllegalArgumentException("Use --vsync on|off");
                };
        case "--frame-limit" -> o.frameLimit = Integer.valueOf(args[++i]);
        case "--frames" -> o.frames = Integer.parseInt(args[++i]);
        case "--size" -> {
          o.explicitSize = true;
          var size = dev.visualengine.export.CaptureSize.parse(args[++i]);
          o.width = size.width();
          o.height = size.height();
        }
        case "--shaders" -> o.shaders = Path.of(args[++i]);
        case "--preset" -> o.preset = Path.of(args[++i]);
        case "--effect" -> o.effect = Path.of(args[++i]);
        case "--inspect" -> o.inspection = Path.of(args[++i]);
        case "--inspect-size" ->
            o.inspectionSize =
                dev.visualengine.export.Periodicity.requireSize(
                    dev.visualengine.export.CaptureSize.parse(args[++i]));
        case "--morph-target" -> o.morphTarget = Path.of(args[++i]);
        case "--morph-amount" -> o.morphAmount = Double.valueOf(args[++i]);
        case "--discoveries" -> o.discoveries = Path.of(args[++i]);
        case "--bookmark" -> {
          o.bookmark = args[++i];
          dev.visualengine.presets.DiscoveryStore.validateLabel(o.bookmark);
        }
        case "--discovery" -> o.discovery = args[++i];
        case "--reference-discovery" -> o.referenceDiscovery = args[++i];
        case "--checkpoint" -> o.checkpoint = Path.of(args[++i]);
        case "--save-checkpoint" -> o.saveCheckpoint = Path.of(args[++i]);
        case "--capture" -> o.capture = Path.of(args[++i]);
        case "--capture-size" ->
            o.captureSize = dev.visualengine.export.CaptureSize.parse(args[++i]);
        case "--map" -> o.mapKind = dev.visualengine.export.TextureMap.Kind.parse(args[++i]);
        case "--map-output" -> o.mapOutput = Path.of(args[++i]);
        case "--map-size" -> o.mapSize = dev.visualengine.export.CaptureSize.parse(args[++i]);
        case "--map-range" ->
            o.mapRange = dev.visualengine.export.TextureMap.Range.parse(args[++i]);
        case "--expression" -> {
          o.expression = args[++i];
          o.recipe = dev.visualengine.model.Settings.Recipe.CUSTOM;
        }
        case "--export" -> {
          o.export = Path.of(args[++i]);
          o.headless = true;
        }
        case "--replay" -> o.replay = Path.of(args[++i]);
        case "--benchmark" -> o.benchmark = Path.of(args[++i]);
        case "--benchmark-suite" -> o.benchmarkSuite = Path.of(args[++i]);
        case "--benchmark-profile" -> {
          o.benchmarkProfile =
              dev.visualengine.export.BenchmarkWorkloads.Profile.valueOf(
                  args[++i].toUpperCase(java.util.Locale.ROOT));
          o.explicitBenchmarkProfile = true;
        }
        case "--fps" -> o.fps = Integer.parseInt(args[++i]);
        case "--warmup" -> o.warmup = Integer.parseInt(args[++i]);
        case "--recipe" ->
            o.recipe =
                dev.visualengine.model.Settings.Recipe.valueOf(
                    args[++i].toUpperCase(java.util.Locale.ROOT).replace('-', '_'));
        case "--grid" -> o.grid = Integer.parseInt(args[++i]);
        case "--quality" ->
            o.quality =
                dev.visualengine.model.QualityProfile.valueOf(
                    args[++i].toUpperCase(java.util.Locale.ROOT));
        case "--seed" -> o.seed = Long.parseLong(args[++i]);
        default -> throw new IllegalArgumentException("Unknown option: " + args[i]);
      }
    if (o.benchmarkSuite != null) {
      var allowed =
          java.util.Set.of("--benchmark-suite", "--benchmark-profile", "--headless", "--shaders");
      for (String arg : args)
        if (arg.startsWith("--") && !allowed.contains(arg))
          throw new IllegalArgumentException(
              "Benchmark suite pins its workload; unsupported override: " + arg);
      o.headless = true;
      return o;
    }
    if (o.explicitBenchmarkProfile)
      throw new IllegalArgumentException("--benchmark-profile requires --benchmark-suite");
    if (o.frameLimit != null) new dev.visualengine.platform.Presentation(true, o.frameLimit);
    if (o.headless && (o.vsync != null || o.frameLimit != null))
      throw new IllegalArgumentException(
          "--vsync and --frame-limit are desktop-only; offline timing is uncapped");
    if (o.width < 32
        || o.height < 32
        || o.width > 8192
        || o.height > 8192
        || (long) o.width * o.height > 33_554_432
        || o.frames < 0
        || o.frames > 100000
        || o.headless && o.frames == 0
        || o.fps < 1
        || o.fps > 120
        || 120 % o.fps != 0
        || o.warmup < 0
        || o.warmup > 120000)
      throw new IllegalArgumentException(
          "Use dimensions 32..8192 and --frames > 0 for headless mode");
    if (o.checkpoint != null
        && (o.preset != null
            || o.effect != null
            || o.replay != null
            || o.recipe != null
            || o.quality != null
            || o.grid != null
            || o.seed != null
            || o.expression != null))
      throw new IllegalArgumentException(
          "Use --checkpoint without preset, effect, replay, or scene overrides");
    if (o.discovery != null
        && (o.preset != null || o.effect != null || o.replay != null || o.checkpoint != null))
      throw new IllegalArgumentException(
          "Use --discovery without preset, effect, replay or checkpoint sources");
    if (o.bookmark != null && o.frames == 0)
      throw new IllegalArgumentException("--bookmark requires --frames > 0");
    if (o.benchmark != null && o.frames <= 30)
      throw new IllegalArgumentException(
          "--benchmark requires --frames > 30 to leave samples after warmup");
    if (o.benchmark != null && o.fps < 15)
      throw new IllegalArgumentException(
          "--benchmark requires --fps >= 15 to bound queries to eight ticks per frame");
    if (o.captureSize != null && o.capture == null)
      throw new IllegalArgumentException("--capture-size requires --capture");
    if (o.inspection != null || o.inspectionSize != null) {
      if (o.inspection == null || o.frames == 0)
        throw new IllegalArgumentException(
            "Inspection requires --inspect FILE.json and --frames > 0");
      if (o.inspectionSize == null)
        o.inspectionSize = new dev.visualengine.export.CaptureSize(512, 512);
    }
    if (o.replay != null && o.quality != null)
      throw new IllegalArgumentException("Replay uses its recorded quality; omit --quality");
    if (o.morphTarget != null || o.morphAmount != null) {
      if (o.morphTarget == null
          || o.morphAmount == null
          || !Double.isFinite(o.morphAmount)
          || o.morphAmount < 0
          || o.morphAmount > 1)
        throw new IllegalArgumentException("Use --morph-target FILE with --morph-amount 0..1");
      if (o.checkpoint != null || o.replay != null)
        throw new IllegalArgumentException(
            "Startup morphing requires a restarting configuration; omit --checkpoint and --replay");
    }
    if (o.mapKind != null || o.mapOutput != null || o.mapSize != null || o.mapRange != null) {
      if (o.mapKind == null || o.mapOutput == null || o.frames == 0)
        throw new IllegalArgumentException(
            "Texture maps require --map TYPE --map-output FILE and --frames > 0");
      if (o.mapRange == null) o.mapRange = o.mapKind.initialRange;
      o.mapKind.validate(o.mapRange);
    }
    return o;
  }
}
