package dev.visualengine;

import dev.visualengine.app.*;
import dev.visualengine.platform.Presentation;
import dev.visualengine.ui.PresentationPanel;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import javax.imageio.ImageIO;
import javax.swing.*;

public final class PresentationUiChecks {
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

  private static void rejects(String... args) {
    try {
      Options.parse(args);
    } catch (IllegalArgumentException expected) {
      count++;
      return;
    }
    throw new AssertionError("Accepted presentation options " + Arrays.toString(args));
  }

  static int run() throws Exception {
    List<Command> commands = new ArrayList<>();
    List<String> errors = new ArrayList<>();
    SwingUtilities.invokeAndWait(() -> {
      var panel = new PresentationPanel(commands::add, errors::add, Presentation.DEFAULT);
      var vsync = (JCheckBox) find(panel, "presentation-vsync");
      var limit = (JSpinner) find(panel, "presentation-limit");
      var apply = (JButton) find(panel, "presentation-apply");
      vsync.setSelected(false);
      limit.setValue(144);
      check(commands.isEmpty(), "presentation draft edits wait for explicit application");
      apply.doClick();
      check(
          commands.size() == 1
              && commands.getFirst().type().equals("presentation")
              && commands.getFirst().value().equals(new Presentation(false, 144)),
          "presentation UI submits complete validated desktop preference");
      panel.showApplied(new Presentation(false, 144), "Saving", true);
      check(
          !apply.isEnabled() && !vsync.isEnabled() && !limit.isEnabled(),
          "presentation save serializes further preference edits");
      panel.showApplied(
          new Presentation(false, 144), "Applied for this session; save failed", false);
      check(
          apply.isEnabled()
              && ((JTextArea) find(panel, "presentation-state")).getText().contains("save failed"),
          "save failure leaves active preferences visible and controls usable");
      panel.showApplied(new Presentation(true, 60), "Saved", false);
      panel.setSize(520, 700);
      layout(panel);
      BufferedImage image = new BufferedImage(520, 700, BufferedImage.TYPE_INT_RGB);
      var g = image.createGraphics();
      panel.printAll(g);
      g.dispose();
      try {
        Path path = Path.of("build/reports/ui/presentation.png");
        Files.createDirectories(path.getParent());
        ImageIO.write(image, "png", path.toFile());
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    });
    var options = Options.parse(new String[] {"--vsync", "off", "--frame-limit", "144"});
    check(
        options.vsync.equals(false) && options.frameLimit == 144 && !options.headless,
        "desktop CLI overrides are distinct from export frame rate");
    rejects("--vsync", "adaptive");
    rejects("--frame-limit", "241");
    rejects("--frame-limit", "-1");
    rejects("--headless", "--frames", "1", "--vsync", "on");
    rejects("--export", "frames", "--frames", "1", "--frame-limit", "60");
    rejects("--benchmark-suite", "results", "--vsync", "off");
    check(
        Options.parse(new String[] {"--headless", "--frames", "1", "--fps", "24"}).fps == 24,
        "offline output timing retains its existing independent option");
    return count;
  }
}
