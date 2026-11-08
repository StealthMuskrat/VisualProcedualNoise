package dev.visualengine.app;

import java.io.*;
import java.nio.file.*;

public final class Main {
  public static void main(String[] args) {
    if (java.util.Arrays.asList(args).contains("--verify-installation")
        && !java.util.Arrays.asList(args).contains("--acceptance-run")) {
      System.out.println(Installation.verify());
      return;
    }
    if (java.util.Arrays.asList(args).contains("--help")) {
      System.out.println(
          "Procedural Visual Engine "
              + dev.visualengine.presets.PresetIO.ENGINE_VERSION
              + "\n"
              + "Java 25 / OpenGL 4.3\n"
              + "--preset file.json --effect effect.json --recipe NAME --seed INTEGER --grid"
              + " 32..1024\n"
              + "--expression FORMULA --shaders DIRECTORY\n"
              + "--shader-cache on|off (session-only inactive material retention)\n"
              + "--diagnose-gpu FILE.json (standalone hidden-context capability report)\n"
              + "--vsync on|off --frame-limit 0..240 (desktop session overrides; 0 = uncapped)\n"
              + "--quality draft|balanced|detailed (applied before --grid override)\n"
              + "--morph-target preset.json --morph-amount 0..1 (compatible numeric controls)\n"
              + "--headless --frames N --size WIDTHxHEIGHT --capture artwork.png\n"
              + "--capture-size WIDTHxHEIGHT (full-resolution still; window stays unchanged)\n"
              + "--inspect report.json --inspect-size WIDTHxHEIGHT (seam/loop measurements, max 4"
              + " MP)\n"
              + "--map height|normal|mask|linear-color|emission --map-output FILE.png\n"
              + "--map-size WIDTHxHEIGHT --map-range MIN:MAX (height/color/emission only)\n"
              + "--export DIRECTORY --frames N --fps 24|30|60|120 --warmup TICKS\n"
              + "--replay take.replay.json --list-recipes\n"
              + "--benchmark FILE.json --frames N>30 (individual ticks; --fps >=15)\n"
              + "--benchmark-suite NEW_DIRECTORY --benchmark-profile smoke|quick|standard|fullhd"
              + " [--shaders DIRECTORY]\n"
              + "--acceptance-run NEW_DIRECTORY [--acceptance-profile"
              + " smoke|quick|standard|fullhd]\n"
              + "  [--acceptance-environment unspecified|physical|hosted] (manual review remains"
              + " required)\n"
              + "--checkpoint study.vstate --save-checkpoint study.vstate\n"
              + "--bookmark LABEL --discoveries DIRECTORY --discovery ID --reference-discovery ID\n"
              + "Interactive: Space pause, period step, R reset, F5 reload, F11 fullscreen, Tab"
              + " controls, Escape exit");
      return;
    }
    if (java.util.Arrays.asList(args).contains("--list-recipes")) {
      for (var recipe : dev.visualengine.model.Settings.Recipe.values())
        System.out.println(recipe.name().toLowerCase().replace('_', '-'));
      return;
    }
    Path data =
        Path.of(
            System.getenv().getOrDefault("LOCALAPPDATA", System.getProperty("user.home")),
            "ProceduralVisualEngine");
    PrintStream console = System.err;
    int exitStatus = 0;
    try {
      try (var log = dev.visualengine.platform.BoundedLog.open(data)) {
        System.setErr(log);
        try {
          log.println(
              java.time.Instant.now()
                  + " | Procedural Visual Engine "
                  + dev.visualengine.presets.PresetIO.ENGINE_VERSION
                  + " | Java "
                  + System.getProperty("java.version")
                  + " | "
                  + System.getProperty("os.name")
                  + " / "
                  + System.getProperty("os.arch"));
          Options options = Options.parse(args);
          if (options.acceptanceRun != null)
            exitStatus =
                dev.visualengine.export.AcceptanceRun.run(
                        options.acceptanceRun,
                        options.acceptanceProfile,
                        options.acceptanceEnvironment)
                    .exitCode();
          else if (options.gpuDiagnostic != null)
            dev.visualengine.export.GpuReport.probe(options.gpuDiagnostic);
          else if (options.benchmarkSuite != null)
            dev.visualengine.export.BenchmarkSuite.run(
                options.benchmarkSuite, options.benchmarkProfile, options.shaders);
          else
            try (Engine engine = new Engine(options, data)) {
              engine.run();
            }
        } catch (Exception e) {
          e.printStackTrace(log);
          throw e;
        } finally {
          System.setErr(console);
        }
      }
    } catch (Exception e) {
      e.printStackTrace();
      if (!java.awt.GraphicsEnvironment.isHeadless()
          && java.util.Arrays.stream(args)
              .noneMatch(
                  java.util.Set.of(
                          "--headless",
                          "--export",
                          "--benchmark-suite",
                          "--diagnose-gpu",
                          "--acceptance-run")
                      ::contains))
        javax.swing.JOptionPane.showMessageDialog(
            null,
            e.getMessage() + "\nLog: " + data,
            "Visual Engine error",
            javax.swing.JOptionPane.ERROR_MESSAGE);
      System.exit(1);
    }
    if (exitStatus != 0) System.exit(exitStatus);
  }
}
