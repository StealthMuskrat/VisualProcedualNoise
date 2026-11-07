package dev.visualengine;

import dev.visualengine.app.Command;
import dev.visualengine.authoring.*;
import dev.visualengine.model.Settings;
import dev.visualengine.time.*;
import dev.visualengine.ui.ModulationPanel;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import javax.imageio.ImageIO;
import javax.swing.*;

public final class ModulationUiChecks {
  private static int count;

  private static void check(boolean ok, String text) {
    count++;
    if (!ok) throw new AssertionError(text);
  }

  private static Component find(Container root, String name) {
    for (Component c : root.getComponents()) {
      if (name.equals(c.getName())) return c;
      if (c instanceof Container nested) {
        var result = find(nested, name);
        if (result != null) return result;
      }
    }
    return null;
  }

  private static void click(Container root, String name) {
    ((JButton) find(root, "modulation-" + name)).doClick();
  }

  private static void select(JComboBox<?> combo, String key) {
    for (int i = 0; i < combo.getItemCount(); i++) {
      if (((ModulationTarget) combo.getItemAt(i)).key().equals(key)) {
        combo.setSelectedIndex(i);
        return;
      }
    }
    throw new AssertionError("Missing target " + key);
  }

  private static List<Modulation> mappings(Command command) {
    return ((List<?>) command.value()).stream().map(Modulation.class::cast).toList();
  }

  private static void layout(Container root) {
    root.doLayout();
    for (var c : root.getComponents()) if (c instanceof Container child) layout(child);
  }

  public static int run() throws Exception {
    SwingUtilities.invokeAndWait(
        () -> {
          List<Command> commands = new ArrayList<>();
          Settings s = EffectModulationChecks.settings();
          List<EffectControl> controls = new ArrayList<>(s.effectControls);
          controls.add(
              new EffectControl(
                  "rebuild",
                  "Rebuild",
                  Formula.Type.FLOAT,
                  "",
                  List.of(1.),
                  0,
                  2,
                  .1,
                  EffectControl.Scale.LINEAR,
                  EffectControl.Update.RESET));
          s.installEffectControls(controls, Map.of());
          ModulationPanel panel = new ModulationPanel(commands::add, s);
          var target = (JComboBox<?>) find(panel, "modulation-target");
          var source = (JComboBox<?>) find(panel, "modulation-source");
          var mode = (JComboBox<?>) find(panel, "modulation-mode");
          var list = (JList<?>) find(panel, "modulation-list");
          var phase = (JSpinner) find(panel, "modulation-phase");
          var keys = (JTextField) find(panel, "modulation-keys");
          select(target, "effect:amplitude");
          check(
              ((JLabel) find(panel, "modulation-range")).getText().contains("smoothed"),
              "editor exposes destination smoothing policy");
          check(
              java.util.stream.IntStream.range(0, target.getItemCount())
                  .noneMatch(
                      i -> ((ModulationTarget) target.getItemAt(i)).key().equals("effect:rebuild")),
              "editor excludes reset-required destinations");
          select(target, "effect:offset.y");
          source.setSelectedItem(Modulation.Source.KEYFRAMES);
          mode.setSelectedItem(Modulation.Mode.REPLACE);
          ((JSpinner) find(panel, "modulation-period")).setValue(6.);
          ((JSpinner) find(panel, "modulation-low")).setValue(-.4);
          ((JSpinner) find(panel, "modulation-high")).setValue(.8);
          phase.setValue(-.25);
          keys.setText("0:0, 1:1");
          click(panel, "add");
          check(
              commands.isEmpty() && list.getModel().getSize() == 5 && s.modulations.size() == 4,
              "adding a custom component changes only the draft");
          click(panel, "apply");
          List<Modulation> first = mappings(commands.getLast());
          Modulation added = first.getLast();
          check(
              added.destination().equals("effect:offset.y")
                  && added.phase() == -.25
                  && added.period() == 6
                  && added.mode() == Modulation.Mode.REPLACE
                  && added.low() == -.4
                  && added.high() == .8,
              "explicit apply submits component, timing, range and combination");
          phase.setValue(.5);
          keys.setText("0:0, 1:0.5");
          click(panel, "update");
          click(panel, "up");
          check(
              commands.size() == 1 && list.getSelectedIndex() == 3,
              "editing and reordering remain draft actions");
          click(panel, "apply");
          List<Modulation> second = mappings(commands.getLast());
          check(
              second.get(3).id().equals(added.id())
                  && second.get(3).phase() == .5
                  && second.get(3).keys()[3] == .5
                  && first.getLast().phase() == -.25
                  && first.getLast().keys()[3] == 1,
              "updating preserves stable source identity and older submitted snapshots");
          keys.setText("0:0:1, 1:1");
          click(panel, "update");
          check(
              ((JLabel) find(panel, "modulation-status")).getText().contains("time:value"),
              "invalid keyframe syntax reports its error");
          click(panel, "apply");
          check(
              mappings(commands.getLast()).get(3).keys()[3] == .5,
              "rejected editor update leaves the valid draft intact");
          click(panel, "remove");
          click(panel, "apply");
          check(
              mappings(commands.getLast()).size() == 4,
              "removal is submitted only by explicit apply");
          int before = commands.size();
          panel.sync(new Settings());
          check(
              commands.size() == before
                  && target.getItemCount() == Settings.PARAMETERS.size()
                  && list.getModel().getSize() == 0,
              "schema sync rebuilds destinations without submitting stale custom mappings");
          Settings full = new Settings();
          for (int i = 0; i < 32; i++)
            full.modulations.add(
                EffectModulationChecks.mapping(
                    "m" + i, "warp", Modulation.Source.SINE, Modulation.Mode.REPLACE, 4, 0, 1, 0));
          panel.sync(full);
          click(panel, "add");
          check(
              list.getModel().getSize() == 32
                  && ((JLabel) find(panel, "modulation-status")).getText().contains("32"),
              "editor enforces the global mapping bound before adding a draft");
          panel.sync(s);
          check(
              list.getSelectedIndex() == 0
                  && keys.getCaretPosition() == 0
                  && ((JLabel) find(panel, "modulation-status"))
                      .getText()
                      .startsWith("Mappings loaded"),
              "loading a scene selects its first mapping and clears stale editor errors");
          panel.setSize(520, 760);
          layout(panel);
          BufferedImage image = new BufferedImage(520, 760, BufferedImage.TYPE_INT_RGB);
          var graphics = image.createGraphics();
          panel.printAll(graphics);
          graphics.dispose();
          try {
            Path path = Path.of("build/reports/ui/effect-modulation.png");
            Files.createDirectories(path.getParent());
            ImageIO.write(image, "png", path.toFile());
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        });
    return count;
  }
}
