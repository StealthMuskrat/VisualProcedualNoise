package dev.visualengine;

import dev.visualengine.app.*;
import dev.visualengine.presets.DiscoveryStore.*;
import dev.visualengine.ui.DiscoveryPanel;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import javax.imageio.ImageIO;
import javax.swing.*;

/** Offscreen Swing checks exercise actual gallery actions and stale-result handling. */
public final class DiscoveryUiChecks {
  private static int count;

  private static void check(boolean value, String text) {
    count++;
    if (!value) throw new AssertionError(text);
  }

  private static Component find(Container root, String name) {
    for (Component c : root.getComponents()) {
      if (name.equals(c.getName())) return c;
      if (c instanceof Container nested) {
        Component found = find(nested, name);
        if (found != null) return found;
      }
    }
    return null;
  }

  private static void layout(Container parent) {
    parent.doLayout();
    for (Component c : parent.getComponents()) if (c instanceof Container child) layout(child);
  }

  public static int run() throws Exception {
    List<Command> commands = new ArrayList<>();
    SwingUtilities.invokeAndWait(() -> {
      DiscoveryPanel panel = new DiscoveryPanel(commands::add);
      panel.open();
      panel.open();
      long generation = ((Command.History) commands.getFirst().value()).generation();
      check(commands.size() == 1, "gallery loads once until explicitly refreshed");
      var first =
          new Summary(
              "study-1793990000000-aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
              "Opal currents",
              1793990000000L,
              true,
              false,
              "LIQUID_OPAL",
              Long.MIN_VALUE,
              1440,
              1280,
              800,
              256,
              16384,
              8,
              "0.5.0",
              "gradient-v1-simulation-v2",
              "a".repeat(64),
              List.of("local.warp@1.0.0"),
              false);
      var second =
          new Summary(
              "study-1793980000000-bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
              "Ember study",
              1793980000000L,
              false,
              false,
              "FLUID_PALETTE",
              77,
              720,
              1280,
              800,
              128,
              4096,
              4,
              "0.5.0",
              "gradient-v1-simulation-v2",
              "b".repeat(64),
              List.of(),
              false);
      panel.showScan(generation + 100, new Scan(List.of(first), 0, false));
      JList<?> list = (JList<?>) find(panel, "discovery-list");
      check(list.getModel().getSize() == 0, "stale gallery scan ignored");
      panel.showScan(generation, new Scan(List.of(first, second), 1, false));
      check(list.getModel().getSize() == 2, "current gallery scan displayed");
      JTextField search = (JTextField) find(panel, "discovery-search");
      search.setText("local.warp");
      check(list.getModel().getSize() == 1, "search finds pinned dependencies");
      ((JButton) find(panel, "branch-palette")).doClick();
      var request = (Command.DiscoveryBranch) commands.getLast().value();
      check(
          request.id().equals(first.id())
              && request.lockComposition()
              && request.variation() == Variation.PALETTE,
          "gallery branch action identifies selection and composition policy");
      ((JButton) find(panel, "reference-discovery")).doClick();
      check(
          commands.getLast().type().equals("discoveryReference")
              && commands.getLast().value().equals(first.id()),
          "reference action targets saved artwork");
      search.setText("no matches");
      check(
          !((JButton) find(panel, "branch-motion")).isEnabled(),
          "empty selection disables actions");
      search.setText("");
      var favorites = (JCheckBox) find(panel, "discovery-favorites");
      favorites.doClick();
      check(list.getModel().getSize() == 1, "favorites filter keeps only starred discoveries");
      favorites.doClick();
      List<Summary> many = new ArrayList<>();
      for (int i = 0; i < 25; i++)
        many.add(
            new Summary(
                "study-1793990000000-aaaaaaaa-aaaa-aaaa-aaaa-"
                    + String.format(Locale.ROOT, "%012d", i),
                "Study " + i,
                first.savedAt(),
                false,
                false,
                first.recipe(),
                i,
                10,
                96,
                64,
                65,
                257,
                3,
                first.engineVersion(),
                first.algorithmRevision(),
                first.programSignature(),
                List.of(),
                false));
      panel.showScan(generation, new Scan(many, 0, false));
      check(
          list.getModel().getSize() == 24 && ((JButton) find(panel, "discovery-next")).isEnabled(),
          "gallery bounds its visible page");
      ((JButton) find(panel, "discovery-next")).doClick();
      check(
          list.getModel().getSize() == 1
              && ((JButton) find(panel, "discovery-previous")).isEnabled(),
          "next page exposes older discovery without retaining a full thumbnail cache");
      panel.showScan(generation, new Scan(List.of(first, second), 1, false));
      var thumbnailRequest =
          commands.stream()
              .filter(c -> c.type().equals("discoveryThumbnails"))
              .map(c -> (Command.Thumbnails) c.value())
              .reduce((a, b) -> b)
              .orElseThrow();
      BufferedImage thumbnail = new BufferedImage(160, 100, BufferedImage.TYPE_INT_RGB);
      Graphics2D brush = thumbnail.createGraphics();
      brush.setPaint(
          new GradientPaint(0, 0, new Color(35, 103, 119), 160, 100, new Color(224, 165, 98)));
      brush.fillRect(0, 0, 160, 100);
      brush.dispose();
      panel.showThumbnails(
          thumbnailRequest.generation(), Map.of(first.id(), thumbnail, second.id(), thumbnail));
      panel.setSize(520, 780);
      layout(panel);
      BufferedImage rendered = new BufferedImage(520, 780, BufferedImage.TYPE_INT_RGB);
      Graphics2D graphics = rendered.createGraphics();
      panel.printAll(graphics);
      graphics.dispose();
      try {
        Path path = Path.of("build/reports/ui/discoveries.png");
        Files.createDirectories(path.getParent());
        ImageIO.write(rendered, "png", path.toFile());
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    });
    boolean rejected = false;
    try {
      Options.parse(new String[] {"--bookmark", "study"});
    } catch (IllegalArgumentException e) {
      rejected = true;
    }
    check(rejected, "CLI bookmark requires a final frame");
    rejected = false;
    try {
      Options.parse(new String[] {"--discovery", "id", "--checkpoint", "state"});
    } catch (IllegalArgumentException e) {
      rejected = true;
    }
    check(rejected, "CLI rejects conflicting discovery sources");
    check(
        Options.parse(
                new String[] {
                  "--headless", "--frames", "1", "--bookmark", "Study", "--discoveries", "history"
                })
            .bookmark
            .equals("Study"),
        "finite CLI bookmark accepted");
    return count;
  }
}
