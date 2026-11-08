package dev.visualengine;

import dev.visualengine.app.Command;
import dev.visualengine.authoring.*;
import dev.visualengine.model.Settings;
import dev.visualengine.ui.AuthoringPanel;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import javax.imageio.ImageIO;
import javax.swing.*;

public final class GraphPreviewUiChecks {
  private static int count;

  private static void check(boolean ok, String text) {
    count++;
    if (!ok) throw new AssertionError(text);
  }

  private static Component find(Container root, String name) {
    for (var child : root.getComponents()) {
      if (("authoring-" + name).equals(child.getName())) return child;
      if (child instanceof Container c) {
        var result = find(c, name);
        if (result != null) return result;
      }
    }
    return null;
  }

  private static void click(Container panel, String name) {
    ((JButton) find(panel, name)).doClick();
  }

  private static void choose(JComboBox<?> list, String id) {
    for (int i = 0; i < list.getItemCount(); i++)
      if (((GraphNavigation.Choice) list.getItemAt(i)).id().equals(id)) {
        list.setSelectedIndex(i);
        return;
      }
    throw new AssertionError("Missing choice: " + id);
  }

  private static void layout(Container root) {
    root.doLayout();
    for (var child : root.getComponents()) if (child instanceof Container c) layout(c);
  }

  private static EffectGraph submitted(List<Command> commands) {
    return (EffectGraph) commands.getLast().value();
  }

  public static int run() throws Exception {
    count = 0;
    SwingUtilities.invokeAndWait(
        () -> {
          var settings = new Settings();
          settings.graph = GraphPreviewChecks.graph();
          var original = settings.graph.copy();
          List<Command> commands = new ArrayList<>();
          var panel = new AuthoringPanel(commands::add, settings);
          var tabs = (JTabbedPane) find(panel, "tabs");
          tabs.setSelectedIndex(1);
          panel.setSize(780, 960);
          layout(panel);
          var navigator = (JComboBox<?>) find(panel, "node-list");
          var mode = (JComboBox<?>) find(panel, "preview-mode");
          var id = (JTextField) find(panel, "node-id");
          var output = (JTextField) find(panel, "output");
          var comment = (JTextField) find(panel, "node-comment");
          var status = (JTextArea) find(panel, "status");
          check(
              mode.getSelectedItem() == GraphPreviews.Mode.SCALAR,
              "preview keeps the existing scalar default");
          choose(navigator, "coord");
          click(panel, "preview");
          check(
              commands.isEmpty()
                  && output.getText().equals("field")
                  && status.getText().contains("must be scalar"),
              "scalar interpretation rejects a vector without changing the draft or scene");
          mode.setSelectedItem(GraphPreviews.Mode.X);
          check(commands.isEmpty(), "choosing an interpretation submits no command");
          comment.setText("unfinished property");
          click(panel, "preview");
          var x = submitted(commands);
          check(
              x.nodes.size() == 8
                  && output.getText().equals("preview")
                  && id.getText().equals("coord"),
              "component preview adds an output adapter and retains source selection");
          check(
              comment.getText().equals("unfinished property")
                  && x.nodes.getFirst().comment().isEmpty(),
              "preview leaves unsubmitted property fields separate from the graph");
          check(
              navigator.getItemCount() == 8
                  && ((JComboBox<?>) find(panel, "connection-target")).getItemCount() == 8
                  && ((JComboBox<?>) find(panel, "group-source")).getItemCount() == 8,
              "accepted preview refreshes every root-node selector");
          for (int i = 0; i < 4; i++) click(panel, "preview");
          check(
              GraphEdits.same(submitted(commands), x),
              "repeated preview reuses the same converter and output");
          click(panel, "undo");
          click(panel, "apply-graph");
          check(
              GraphEdits.same(submitted(commands), original) && id.getText().equals("coord"),
              "one undo removes the converter after repeated previews");
          check(
              x.nodes.size() == 8 && x.output.equals("preview"),
              "undo preserves the submitted preview snapshot");
          mode.setSelectedItem(GraphPreviews.Mode.Y);
          click(panel, "preview");
          var y = submitted(commands);
          check(
              y.nodes.getLast().expression().equals("input0.y"),
              "Y preview submits the explicit requested component");
          mode.setSelectedItem(GraphPreviews.Mode.W);
          int before = commands.size();
          click(panel, "preview");
          check(
              commands.size() == before
                  && output.getText().equals(y.output)
                  && id.getText().equals("coord"),
              "incompatible component preserves the prior accepted preview");
          click(panel, "undo");
          click(panel, "apply-graph");
          check(
              GraphEdits.same(submitted(commands), original),
              "failed preview consumes no undo entry");
          choose(navigator, "color");
          mode.setSelectedItem(GraphPreviews.Mode.W);
          click(panel, "preview");
          check(
              submitted(commands).nodes.getLast().expression().equals("input0.w")
                  && id.getText().equals("color"),
              "disconnected VECTOR4 output can be previewed explicitly");
          click(panel, "undo");
          choose(navigator, "coord");
          mode.setSelectedItem(GraphPreviews.Mode.LENGTH);
          click(panel, "preview");
          check(
              submitted(commands).nodes.getLast().expression().equals("length(input0)"),
              "vector length preview is an ordinary graph expression");
          click(panel, "undo");
          choose(navigator, "distance");
          mode.setSelectedItem(GraphPreviews.Mode.DISTANCE_MASK);
          click(panel, "preview");
          check(
              submitted(commands).nodes.getLast().kind() == EffectGraph.Kind.MASK
                  && id.getText().equals("distance"),
              "distance preview uses the declared mask conversion");
          click(panel, "undo");
          choose(navigator, "region");
          mode.setSelectedItem(GraphPreviews.Mode.REGION_HASH);
          click(panel, "preview");
          check(
              submitted(commands).nodes.getLast().kind() == EffectGraph.Kind.HASH_ID,
              "region preview uses the declared identity hash conversion");
          panel.sync(settings);
          check(
              mode.getSelectedItem() == GraphPreviews.Mode.SCALAR
                  && navigator.getItemCount() == 7
                  && output.getText().equals("field"),
              "scene sync resets interpretation and preview draft history");
          choose(navigator, "time");
          click(panel, "preview");
          check(
              submitted(commands).nodes.size() == 7 && output.getText().equals("time"),
              "ordinary scalar preview still adds no node");
          click(panel, "undo");
          click(panel, "apply-graph");
          check(
              GraphEdits.same(submitted(commands), original),
              "scalar preview retains selected draft undo");
          var full = settings.copy();
          for (int i = 0; full.graph.nodes.size() < 64; i++)
            full.graph.nodes.add(
                new EffectGraph.Node(
                    "extra" + i, EffectGraph.Kind.TIME, List.of(), "0", 0, 0, false, "", ""));
          panel.sync(full);
          choose(navigator, "coord");
          mode.setSelectedItem(GraphPreviews.Mode.X);
          before = commands.size();
          click(panel, "preview");
          check(
              commands.size() == before
                  && navigator.getItemCount() == 64
                  && status.getText().contains("64 root nodes"),
              "conversion at the root limit is rejected before mutation");
          panel.sync(settings);
          choose(navigator, "coord");
          comment.setText("one edit");
          click(panel, "update");
          for (int i = 0; i < 40; i++) {
            mode.setSelectedItem(GraphPreviews.Mode.X);
            mode.setSelectedItem(GraphPreviews.Mode.Y);
          }
          click(panel, "undo");
          click(panel, "apply-graph");
          check(
              GraphEdits.same(submitted(commands), original),
              "interpretation selection consumes no graph undo history");
          choose(navigator, "color");
          mode.setSelectedItem(GraphPreviews.Mode.W);
          click(panel, "preview");
          layout(panel);
          var image = new BufferedImage(780, 960, BufferedImage.TYPE_INT_RGB);
          var g = image.createGraphics();
          panel.printAll(g);
          g.dispose();
          try {
            var path = Path.of("build/reports/ui/graph-previews.png");
            Files.createDirectories(path.getParent());
            ImageIO.write(image, "png", path.toFile());
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        });
    return count;
  }
}
