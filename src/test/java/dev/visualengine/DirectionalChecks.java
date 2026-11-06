package dev.visualengine;

import dev.visualengine.app.Command;
import dev.visualengine.authoring.*;
import dev.visualengine.model.*;
import dev.visualengine.presets.PresetIO;
import dev.visualengine.time.ReplayLog;
import dev.visualengine.ui.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import javax.imageio.ImageIO;
import javax.swing.*;

public final class DirectionalChecks {
  private static int count;

  private static void check(boolean ok, String label) {
    count++;
    if (!ok) throw new AssertionError(label);
  }

  private static Component find(Container root, String name) {
    for (Component c : root.getComponents()) {
      if (name.equals(c.getName())) return c;
      if (c instanceof Container parent) {
        Component found = find(parent, name);
        if (found != null) return found;
      }
    }
    return null;
  }

  private static void layout(Container root) {
    root.doLayout();
    for (Component c : root.getComponents()) if (c instanceof Container child) layout(child);
  }

  public static int run() throws Exception {
    Path reports = Path.of("build/reports/directional");
    Files.createDirectories(reports);
    for (var style : DirectionalEffects.Style.values()) {
      EffectPackage effect = DirectionalEffects.create(style);
      var compiled = EffectControl.compile(effect.expression, effect.controls);
      check(
          compiled.root().cost() < 8192 && !compiled.dependencies().contains("t"),
          "directional effects are bounded phase-driven formulas");
      Settings settings = new Settings();
      settings.seed = Long.MIN_VALUE;
      settings.pigmentEnabled = true;
      effect.apply(settings);
      settings.grid = 65;
      settings.particles = 257;
      settings.historyLayers = 3;
      check(
          settings.seed == Long.MIN_VALUE
              && settings.recipe == Settings.Recipe.CUSTOM
              && !settings.pigmentEnabled
              && settings.effectControls.size()
                  == (style == DirectionalEffects.Style.FIBERS ? 6 : 7),
          "directional activation preserves seed and installs declared resource/control contract");
      settings.loopSeconds = 8;
      settings.validate();
      EffectPackage.resolveResources(settings);
      check(
          settings.effectControl("carrier").scale() == EffectControl.Scale.LOGARITHMIC
              && settings.effectControl("bandwidth").update() == EffectControl.Update.SMOOTHED,
          "directional controls expose units, logarithmic mapping and smoothing");
      Path manifest = reports.resolve(style.name().toLowerCase(Locale.ROOT) + ".effect.json");
      effect.save(manifest);
      EffectPackage loaded = EffectPackage.load(manifest);
      check(
          loaded.definitionHash.equals(effect.definitionHash)
              && loaded.controls.equals(effect.controls)
              && loaded.expression.equals(effect.expression),
          "directional hashed manifest roundtrip");
      Settings roundtrip = PresetIO.parse(PresetIO.JSON.toJson(PresetIO.document(settings, 10)));
      check(
          roundtrip.effectValues.equals(settings.effectValues)
              && PresetMorph.differences(settings, roundtrip).isEmpty(),
          "directional settings roundtrip and remain morph-compatible");
      settings.loopSeconds = 0;
      settings.feedbackEnabled = settings.pigmentEnabled = true;
      Settings other = settings.copy();
      other.setEffectValue("carrier", List.of(5.));
      ReplayLog replay = ReplayLog.begin(settings, 96, 64);
      replay.recordControl(9, other);
      Settings smoothed = other.copy();
      smoothed.setEffectValue("bandwidth", List.of(2.5));
      replay.recordControl(17, smoothed);
      replay.endTick = 32;
      replay.save(reports.resolve(style.name().toLowerCase(Locale.ROOT) + ".replay.json"));
      effect.controls.clear();
      check(
          !DirectionalEffects.create(style).controls.isEmpty(),
          "directional factories own mutable definitions");
    }
    List<Command> commands = new ArrayList<>();
    SwingUtilities.invokeAndWait(() -> {
      DirectionalPanel panel = new DirectionalPanel(commands::add);
      var style = (JComboBox<?>) find(panel, "directional-style");
      var load = (JButton) find(panel, "directional-load");
      style.setSelectedItem(DirectionalEffects.Style.WEAVE);
      check(commands.isEmpty(), "directional selection alone does not reset scene");
      load.doClick();
      check(
          commands.size() == 1
              && commands.getFirst().type().equals("effectLoaded")
              && ((EffectPackage) commands.getFirst().value()).id.equals("builtin.gabor-weave"),
          "directional activation submits complete ordinary effect manifest");
      AuthoringPanel authoring = new AuthoringPanel(commands::add, new Settings());
      authoring.setSize(520, 760);
      layout(authoring);
      BufferedImage image = new BufferedImage(520, 760, BufferedImage.TYPE_INT_RGB);
      var g = image.createGraphics();
      authoring.printAll(g);
      g.dispose();
      try {
        Path path = Path.of("build/reports/ui/directional.png");
        Files.createDirectories(path.getParent());
        ImageIO.write(image, "png", path.toFile());
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    });
    return count;
  }
}
