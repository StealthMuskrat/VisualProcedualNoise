package dev.visualengine;

import dev.visualengine.app.Options;
import dev.visualengine.authoring.DirectionalEffects;
import dev.visualengine.export.*;
import dev.visualengine.model.Settings;
import dev.visualengine.presets.PresetIO;
import dev.visualengine.ui.InspectionPanel;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import javax.imageio.ImageIO;
import javax.swing.*;

public final class InspectionUiChecks {
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
    throw new AssertionError("Accepted inspection options " + Arrays.toString(args));
  }

  static int run() throws Exception {
    Settings settings = new Settings();
    settings.loopSeconds = 8;
    settings.tileMode = true;
    List<CaptureSize> choices = new ArrayList<>();
    List<String> errors = new ArrayList<>();
    SwingUtilities.invokeAndWait(() -> {
      InspectionPanel panel = new InspectionPanel(choices::add, errors::add, settings);
      JSpinner width = (JSpinner) find(panel, "inspection-width"),
          height = (JSpinner) find(panel, "inspection-height");
      JButton run = (JButton) find(panel, "inspection-run");
      width.setValue(127);
      height.setValue(95);
      run.doClick();
      check(
          choices.equals(List.of(new CaptureSize(127, 95))),
          "inspection UI submits exact independent odd dimensions");
      width.setValue(4096);
      height.setValue(4096);
      run.doClick();
      check(
          errors.size() == 1 && choices.size() == 1,
          "inspection UI rejects excess allocation before choosing a destination");
      BufferedImage image = new BufferedImage(127, 95, BufferedImage.TYPE_INT_RGB);
      var report =
          Periodicity.analyze(
              new Periodicity.Frame(
                  settings, settings, 12, .1, "test", "0".repeat(64), 0, 0, false),
              image,
              image);
      panel.showReport(report, Periodicity.preview(image), 12);
      check(
          ((JTextArea) find(panel, "inspection-result"))
                  .getText()
                  .contains("Last measured tick: 12")
              && ((JLabel) find(panel, "inspection-preview")).getIcon() != null,
          "inspection UI labels measured context and displays repeated-image preview");
      width.setValue(512);
      height.setValue(512);
      panel.setSize(520, 760);
      layout(panel);
      BufferedImage screenshot = new BufferedImage(520, 760, BufferedImage.TYPE_INT_RGB);
      var g = screenshot.createGraphics();
      panel.printAll(g);
      g.dispose();
      try {
        Path path = Path.of("build/reports/ui/inspection.png");
        Files.createDirectories(path.getParent());
        ImageIO.write(screenshot, "png", path.toFile());
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    });
    var options =
        Options.parse(
            new String[] {
              "--frames",
              "1",
              "--size",
              "320x200",
              "--inspect",
              "report.json",
              "--inspect-size",
              "127x95"
            });
    check(
        options.width == 320 && options.inspectionSize.equals(new CaptureSize(127, 95)),
        "inspection CLI dimensions are independent of the live viewport");
    check(
        Options.parse(new String[] {"--frames", "1", "--inspect", "report.json"})
            .inspectionSize
            .equals(new CaptureSize(512, 512)),
        "inspection CLI has bounded explicit default size");
    rejects("--inspect-size", "128x128", "--frames", "1");
    rejects("--inspect", "report.json");
    rejects("--inspect", "report.json", "--frames", "1", "--inspect-size", "4096x4096");
    rejects("--inspect", "report.json", "--frames", "1", "--inspect-size", "8192x32");
    Settings loop = new Settings();
    DirectionalEffects.create(DirectionalEffects.Style.FIBERS).apply(loop);
    loop.grid = 65;
    loop.particles = 257;
    loop.historyLayers = 3;
    loop.loopSeconds = 8;
    loop.tileMode = true;
    loop.debug = 8;
    loop.tilePreview = true;
    loop.displayScale = .5;
    PresetIO.save(Path.of("build/reports/inspection-loop.json"), loop, 0);
    return count;
  }
}
