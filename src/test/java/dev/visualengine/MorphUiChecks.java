package dev.visualengine;

import dev.visualengine.app.*;
import dev.visualengine.model.*;
import dev.visualengine.ui.MorphPanel;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import javax.imageio.ImageIO;
import javax.swing.*;

public final class MorphUiChecks {
  private static int count;

  private static void check(boolean value, String label) {
    count++;
    if (!value) throw new AssertionError(label);
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

  private static void rejects(String... args) {
    try {
      Options.parse(args);
    } catch (IllegalArgumentException e) {
      count++;
      return;
    }
    throw new AssertionError("Accepted invalid morph options " + Arrays.toString(args));
  }

  public static int run() throws Exception {
    List<Command> commands = new ArrayList<>();
    List<PresetMorph.Slot> loads = new ArrayList<>();
    SwingUtilities.invokeAndWait(() -> {
      MorphPanel panel = new MorphPanel(commands::add, loads::add);
      JButton apply = (JButton) find(panel, "morph-apply");
      JSlider amount = (JSlider) find(panel, "morph-amount");
      check(!apply.isEnabled() && !amount.isEnabled(), "empty endpoints disable blending");
      ((JButton) find(panel, "morph-A-capture")).doClick();
      ((JButton) find(panel, "morph-B-load")).doClick();
      check(
          commands.size() == 1
              && commands.getFirst().type().equals("morphCapture")
              && commands.getFirst().value() == PresetMorph.Slot.A
              && loads.equals(List.of(PresetMorph.Slot.B)),
          "endpoint controls only request capture or load");
      PresetMorph.Session session = new PresetMorph.Session();
      Settings a = new Settings(), b = a.copy();
      b.set("warp", 1.7);
      session.capture(PresetMorph.Slot.A, a);
      session.capture(PresetMorph.Slot.B, b);
      panel.showView(session.view(a));
      check(apply.isEnabled() && amount.isEnabled(), "compatible endpoints enable blending");
      amount.setValue(750);
      check(commands.size() == 1, "choosing amount does not alter live scene");
      apply.doClick();
      check(
          commands.getLast().type().equals("morphBlend") && commands.getLast().value().equals(.75),
          "explicit apply submits exact blend amount");
      b.palette = 2;
      session.capture(PresetMorph.Slot.B, b);
      panel.showView(session.view(a));
      check(!apply.isEnabled() && !amount.isEnabled(), "incompatibility disables apply");
      b.palette = 0;
      session.capture(PresetMorph.Slot.B, b);
      panel.showView(session.view(a));
      panel.setSize(500, 760);
      layout(panel);
      BufferedImage image = new BufferedImage(500, 760, BufferedImage.TYPE_INT_RGB);
      var graphics = image.createGraphics();
      panel.printAll(graphics);
      graphics.dispose();
      try {
        Path path = Path.of("build/reports/ui/morph.png");
        Files.createDirectories(path.getParent());
        ImageIO.write(image, "png", path.toFile());
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    });
    Options parsed =
        Options.parse(new String[] {"--morph-target", "target.json", "--morph-amount", "0.25"});
    check(
        parsed.morphTarget.equals(Path.of("target.json")) && parsed.morphAmount == .25,
        "CLI parses target and finite blend amount");
    rejects("--morph-target", "target.json");
    rejects("--morph-amount", "0.5");
    for (String value : List.of("NaN", "Infinity", "-0.1", "1.1"))
      rejects("--morph-target", "target.json", "--morph-amount", value);
    rejects(
        "--morph-target", "target.json", "--morph-amount", ".5", "--checkpoint", "state.vstate");
    rejects("--morph-target", "target.json", "--morph-amount", ".5", "--replay", "take.json");
    Settings current = new Settings();
    PresetMorph.Session captures = new PresetMorph.Session();
    Queue<Command> queue =
        new ArrayDeque<>(
            List.of(
                new Command("parameter", new Command.Edit("warp", .4)),
                new Command("parameter", new Command.Edit("warp", .8)),
                new Command("morphCapture", PresetMorph.Slot.A),
                new Command("parameter", new Command.Edit("warp", 1.6)),
                new Command("morphCapture", PresetMorph.Slot.B)));
    int[] edits = {0};
    Command.drain(
        queue,
        command -> {
          if (command.type().equals("parameter")) {
            var edit = (Command.Edit) command.value();
            current.set(edit.id(), edit.value());
            edits[0]++;
          } else captures.capture((PresetMorph.Slot) command.value(), current);
        });
    check(
        edits[0] == 2
            && captures.blend(current, 0).get("warp") == .8
            && captures.blend(current, 1).get("warp") == 1.6,
        "endpoint actions observe preceding coalesced edits in submission order");
    return count;
  }
}
