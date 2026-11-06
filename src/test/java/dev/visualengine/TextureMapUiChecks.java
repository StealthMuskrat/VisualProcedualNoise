package dev.visualengine;

import dev.visualengine.app.Options;
import dev.visualengine.export.TextureMap;
import dev.visualengine.ui.TextureMapPanel;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import javax.imageio.ImageIO;
import javax.swing.*;

public final class TextureMapUiChecks {
  private static int count;

  private static void check(boolean value, String label) {
    count++;
    if (!value) throw new AssertionError(label);
  }

  private static Component find(Container root, String name) {
    for (Component c : root.getComponents()) {
      if (name.equals(c.getName())) return c;
      if (c instanceof Container parent) {
        Component result = find(parent, name);
        if (result != null) return result;
      }
    }
    return null;
  }

  private static void layout(Container parent) {
    parent.doLayout();
    for (Component c : parent.getComponents()) if (c instanceof Container child) layout(child);
  }

  private static void rejects(String[] args, String text) {
    try {
      Options.parse(args);
    } catch (IllegalArgumentException e) {
      count++;
      return;
    }
    throw new AssertionError("Accepted " + text);
  }

  public static int run() throws Exception {
    List<TextureMapPanel.Choice> choices = new ArrayList<>();
    List<String> errors = new ArrayList<>();
    SwingUtilities.invokeAndWait(() -> {
      var panel = new TextureMapPanel(choices::add, errors::add);
      var kind = (JComboBox<?>) find(panel, "map-kind");
      var minimum = (JSpinner) find(panel, "map-minimum");
      var maximum = (JSpinner) find(panel, "map-maximum");
      var export = (JButton) find(panel, "map-export");
      kind.setSelectedItem(TextureMap.Kind.NORMAL);
      check(
          !minimum.isEnabled()
              && !maximum.isEnabled()
              && ((Number) minimum.getValue()).doubleValue() == -1,
          "normal encoding range is fixed and visible");
      export.doClick();
      check(
          choices.getLast().kind() == TextureMap.Kind.NORMAL
              && choices.getLast().range().equals(new TextureMap.Range(-1, 1)),
          "map action submits the displayed contract");
      kind.setSelectedItem(TextureMap.Kind.HEIGHT);
      minimum.setValue(3.);
      maximum.setValue(2.);
      export.doClick();
      check(
          errors.size() == 1 && choices.size() == 1,
          "invalid UI range is reported before destination selection");
      minimum.setValue(-4.);
      maximum.setValue(4.);
      export.doClick();
      check(
          choices.getLast().range().equals(new TextureMap.Range(-4, 4)),
          "custom height range retained");
      panel.setSize(500, 260);
      layout(panel);
      var image = new BufferedImage(500, 260, BufferedImage.TYPE_INT_RGB);
      var g = image.createGraphics();
      panel.printAll(g);
      g.dispose();
      try {
        Path path = Path.of("build/reports/ui/texture-maps.png");
        Files.createDirectories(path.getParent());
        ImageIO.write(image, "png", path.toFile());
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    });
    var options =
        Options.parse(
            new String[] {
              "--headless",
              "--frames",
              "2",
              "--size",
              "320x200",
              "--map",
              "height",
              "--map-output",
              "height.png",
              "--map-size",
              "640x400",
              "--map-range",
              "-4:4"
            });
    check(
        options.width == 320 && options.mapSize.width() == 640 && options.mapRange.minimum() == -4,
        "CLI map resolution and range are independent of live output");
    rejects(new String[] {"--map", "height", "--frames", "1"}, "missing map output");
    rejects(new String[] {"--map-output", "data.png", "--frames", "1"}, "missing map kind");
    rejects(new String[] {"--map", "height", "--map-output", "data.png"}, "nonfinite map run");
    rejects(
        new String[] {
          "--map", "normal", "--map-output", "data.png", "--frames", "1", "--map-range", "0:1"
        },
        "normal range override");
    check(
        Options.parse(
                    new String[] {
                      "--map",
                      "linear-color",
                      "--map-output",
                      "data.png",
                      "--frames",
                      "1",
                      "--checkpoint",
                      "state"
                    })
                .mapKind
            == TextureMap.Kind.LINEAR_COLOR,
        "maps can read a resumed checkpoint");
    return count;
  }
}
