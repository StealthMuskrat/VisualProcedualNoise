package dev.visualengine.app;

import java.io.*;
import java.nio.file.*;

public final class Main {
  public static void main(String[] args) {
    if (java.util.Arrays.asList(args).contains("--verify-installation")) {
      var settings = new dev.visualengine.model.Settings();
      dev.visualengine.presets.PresetIO.parse(
          dev.visualengine.presets.PresetIO.JSON.toJson(
              dev.visualengine.presets.PresetIO.document(settings, 0)));
      dev.visualengine.authoring.Formula.compile(settings.expression);
      new dev.visualengine.gpu.ShaderSources(null).read("render/material.frag");
      if (Main.class.getResource("/META-INF/licenses/LWJGL-BSD.txt") == null)
        throw new IllegalStateException("Packaged license resources are missing");
      System.out.println(
          "Installation verified | Java "
              + System.getProperty("java.version")
              + " | GLFW "
              + org.lwjgl.glfw.GLFW.glfwGetVersionString());
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
              + "--quality draft|balanced|detailed (applied before --grid override)\n"
              + "--headless --frames N --size WIDTHxHEIGHT --capture artwork.png\n"
              + "--capture-size WIDTHxHEIGHT (full-resolution still; window stays unchanged)\n"
              + "--export DIRECTORY --frames N --fps 24|30|60|120 --warmup TICKS\n"
              + "--replay take.replay.json --list-recipes\n"
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
    try {
      Files.createDirectories(data);
      try (var log =
          new PrintStream(
              Files.newOutputStream(data.resolve("engine.log")),
              true,
              java.nio.charset.StandardCharsets.UTF_8)) {
        System.setErr(log);
        try (Engine engine = new Engine(Options.parse(args), data)) {
          engine.run();
        } catch (Exception e) {
          e.printStackTrace(log);
          throw e;
        } finally {
          System.setErr(console);
        }
      }
    } catch (Exception e) {
      e.printStackTrace();
      if (!java.awt.GraphicsEnvironment.isHeadless())
        javax.swing.JOptionPane.showMessageDialog(
            null,
            e.getMessage() + "\nLog: " + data,
            "Visual Engine error",
            javax.swing.JOptionPane.ERROR_MESSAGE);
      System.exit(1);
    }
  }
}
