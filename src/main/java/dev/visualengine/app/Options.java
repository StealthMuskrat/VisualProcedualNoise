package dev.visualengine.app;

import java.nio.file.Path;

public final class Options {
  public boolean headless;
  public int frames, width = 1280, height = 800;
  public Path shaders, preset, capture;
  public Path effect;
  public String expression;
  public Path export, replay;
  public Path benchmark;
  public int fps = 60, warmup;
  public dev.visualengine.model.Settings.Recipe recipe;
  public Integer grid;
  public Long seed;

  public static Options parse(String[] args) {
    Options o = new Options();
    for (int i = 0; i < args.length; i++)
      switch (args[i]) {
        case "--headless" -> o.headless = true;
        case "--frames" -> o.frames = Integer.parseInt(args[++i]);
        case "--size" -> {
          String[] size = args[++i].split("x");
          o.width = Integer.parseInt(size[0]);
          o.height = Integer.parseInt(size[1]);
        }
        case "--shaders" -> o.shaders = Path.of(args[++i]);
        case "--preset" -> o.preset = Path.of(args[++i]);
        case "--effect" -> o.effect = Path.of(args[++i]);
        case "--capture" -> o.capture = Path.of(args[++i]);
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
        case "--fps" -> o.fps = Integer.parseInt(args[++i]);
        case "--warmup" -> o.warmup = Integer.parseInt(args[++i]);
        case "--recipe" ->
            o.recipe =
                dev.visualengine.model.Settings.Recipe.valueOf(
                    args[++i].toUpperCase(java.util.Locale.ROOT).replace('-', '_'));
        case "--grid" -> o.grid = Integer.parseInt(args[++i]);
        case "--seed" -> o.seed = Long.parseLong(args[++i]);
        default -> throw new IllegalArgumentException("Unknown option: " + args[i]);
      }
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
    return o;
  }
}
