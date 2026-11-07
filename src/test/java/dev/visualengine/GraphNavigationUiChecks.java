package dev.visualengine;

import dev.visualengine.app.Command;
import dev.visualengine.authoring.*;
import dev.visualengine.model.Settings;
import dev.visualengine.ui.AuthoringPanel;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import javax.imageio.ImageIO;
import javax.swing.*;

public final class GraphNavigationUiChecks {
  private static int count;

  private static void check(boolean ok, String text) {
    count++;
    if (!ok) throw new AssertionError(text);
  }

  private static Component find(Container root, String name) {
    for (Component child : root.getComponents()) {
      if (("authoring-" + name).equals(child.getName())) return child;
      if (child instanceof Container container) {
        var result = find(container, name);
        if (result != null) return result;
      }
    }
    throwIfMissing(root, name);
    return null;
  }

  private static void throwIfMissing(Container root, String name) {
    if (root instanceof AuthoringPanel) throw new AssertionError("Missing UI component: " + name);
  }

  private static void click(Container panel, String name) {
    ((JButton) find(panel, name)).doClick();
  }

  private static void choose(JComboBox<?> list, String id) {
    for (int i = 0; i < list.getItemCount(); i++) {
      if (((GraphNavigation.Choice) list.getItemAt(i)).id().equals(id)) {
        list.setSelectedIndex(i);
        return;
      }
    }
    throw new AssertionError("Missing node choice: " + id);
  }

  private static void layout(Container root) {
    root.doLayout();
    for (Component child : root.getComponents()) if (child instanceof Container c) layout(c);
  }

  private static void mouse(Component canvas, int type, int x, int y) {
    canvas.dispatchEvent(new MouseEvent(canvas, type, 1, 0, x, y, 1, false, MouseEvent.BUTTON1));
  }

  private static EffectGraph submitted(List<Command> commands) {
    return (EffectGraph) commands.getLast().value();
  }

  public static int run() throws Exception {
    count = 0;
    SwingUtilities.invokeAndWait(
        () -> {
          Settings settings = new Settings();
          settings.graph = AuthoringChecks.noiseGraph();
          var field = settings.graph.nodes.getLast();
          settings.graph.nodes.set(
              3,
              new EffectGraph.Node(
                  field.id(),
                  field.kind(),
                  field.inputs(),
                  field.expression(),
                  -45000,
                  87000,
                  false,
                  "Cloud layers",
                  "Fine detail",
                  "local.cloud@1.2.0",
                  field.seedKey()));
          settings.graph.nodes.set(
              1, settings.graph.nodes.get(1).moved(Double.MAX_VALUE, -Double.MAX_VALUE));
          List<Command> commands = new ArrayList<>();
          AuthoringPanel panel = new AuthoringPanel(commands::add, settings);
          ((JTabbedPane) find(panel, "tabs")).setSelectedIndex(1);
          panel.setSize(780, 960);
          layout(panel);
          var search = (JTextField) find(panel, "node-search");
          var list = (JComboBox<?>) find(panel, "node-list");
          var countLabel = (JLabel) find(panel, "node-count");
          var id = (JTextField) find(panel, "node-id");
          var comment = (JTextField) find(panel, "node-comment");
          var status = (JTextArea) find(panel, "status");
          var show = (JButton) find(panel, "show-node");
          var canvas = (JComponent) find(panel, "canvas");
          check(
              list.getItemCount() == 4 && countLabel.getText().equals("4 / 4 nodes"),
              "navigator lists all root nodes and count");
          search.setText("NOISE cloud detail");
          check(
              list.getItemCount() == 1 && id.getText().equals("field") && show.isEnabled(),
              "metadata search retains the matching selection");
          search.setText("local.cloud@1.2.0");
          check(list.getItemCount() == 1, "template revisions are discoverable from the editor");
          search.setText("absent");
          check(
              list.getItemCount() == 0
                  && !show.isEnabled()
                  && id.getText().equals("field")
                  && countLabel.getText().equals("0 / 4 nodes"),
              "empty results retain edited selection and disable reveal");
          search.setText("point");
          check(
              list.getSelectedItem() == null && id.getText().equals("field"),
              "filtering does not select a different node");
          choose(list, "point");
          check(
              id.getText().equals("point") && show.isEnabled(),
              "choosing a result updates node fields");
          search.setText("");
          choose(list, "field");
          Rectangle visible = canvas.getVisibleRect();
          int centerX = visible.width / 2, centerY = visible.height / 2;
          check(
              visible.x == 0 && visible.y == 0 && centerX > 75 && centerY > 38,
              "reveal uses the visible viewport at its origin");
          id.setText("unapplied text");
          mouse(canvas, MouseEvent.MOUSE_PRESSED, centerX, centerY);
          mouse(canvas, MouseEvent.MOUSE_RELEASED, centerX, centerY);
          check(id.getText().equals("field"), "revealed distant negative node is hit-testable");
          click(panel, "show-node");
          click(panel, "reset-view");
          choose(list, "time");
          id.setText("unapplied text");
          mouse(canvas, MouseEvent.MOUSE_PRESSED, centerX, centerY);
          mouse(canvas, MouseEvent.MOUSE_RELEASED, centerX, centerY);
          check(
              id.getText().equals("time"),
              "extreme finite coordinates can be revealed and selected");
          check(commands.isEmpty(), "search and navigation submit no engine commands");
          click(panel, "apply-graph");
          check(
              GraphEdits.same(submitted(commands), settings.graph),
              "navigation preserves all authored positions, seed keys and graph metadata");
          choose(list, "field");
          mouse(canvas, MouseEvent.MOUSE_PRESSED, centerX, centerY);
          mouse(canvas, MouseEvent.MOUSE_DRAGGED, centerX + 20, centerY + 30);
          mouse(canvas, MouseEvent.MOUSE_DRAGGED, centerX + 40, centerY + 50);
          mouse(canvas, MouseEvent.MOUSE_RELEASED, centerX + 40, centerY + 50);
          click(panel, "apply-graph");
          check(
              submitted(commands).nodes.getLast().x() == -44960
                  && submitted(commands).nodes.getLast().y() == 87050,
              "dragging in a focused view preserves negative world coordinates");
          for (int i = 0; i < 40; i++) {
            choose(list, "time");
            choose(list, "field");
            click(panel, "reset-view");
          }
          click(panel, "undo");
          click(panel, "apply-graph");
          check(
              GraphEdits.same(submitted(commands), settings.graph) && id.getText().equals("field"),
              "one undo restores the drag after repeated view changes");
          id.setText("heightField");
          click(panel, "update");
          check(
              ((GraphNavigation.Choice) list.getSelectedItem()).id().equals("heightField")
                  && list.getItemCount() == 4,
              "rename refreshes navigator identity and selection");
          search.setText("field");
          check(list.getItemCount() == 1, "search refreshes after a rename");
          click(panel, "delete");
          check(
              list.getItemCount() == 0 && id.getText().isEmpty() && !show.isEnabled(),
              "deletion removes stale navigation entries");
          click(panel, "undo");
          check(
              list.getItemCount() == 1 && id.getText().equals("heightField"),
              "undo repopulates the filtered navigator");
          click(panel, "undo");
          search.setText("");
          check(
              ((GraphNavigation.Choice) list.getSelectedItem()).id().equals("field"),
              "rename undo restores the original choice");
          click(panel, "add");
          check(
              list.getItemCount() == 5
                  && ((GraphNavigation.Choice) list.getSelectedItem()).id().equals(id.getText()),
              "added nodes enter the navigator");
          panel.sync(settings);
          check(
              search.getText().isEmpty()
                  && list.getItemCount() == 4
                  && id.getText().equals("field"),
              "scene sync resets filters and repopulates navigation");
          click(panel, "undo");
          click(panel, "apply-graph");
          check(
              GraphEdits.same(submitted(commands), settings.graph),
              "scene sync clears the preceding draft history");
          choose(list, "field");
          ((JTextField) find(panel, "node-formula")).setText("sin(");
          ((JComboBox<?>) find(panel, "kind")).setSelectedItem(EffectGraph.Kind.FORMULA);
          ((JTextField) find(panel, "node-inputs")).setText("");
          click(panel, "update");
          click(panel, "reset-view");
          int before = commands.size();
          click(panel, "apply-graph");
          check(
              commands.size() == before
                  && id.getText().equals("field")
                  && !status.getText().startsWith("Submitted"),
              "invalid distant drafts retain located diagnostics without engine submission");
          id.setText("unapplied text");
          mouse(canvas, MouseEvent.MOUSE_PRESSED, centerX, centerY);
          mouse(canvas, MouseEvent.MOUSE_RELEASED, centerX, centerY);
          check(id.getText().equals("field"), "located diagnostics reveal the same distant node");
          search.setText("fine detail");
          check(list.getItemCount() == 1, "invalid formula drafts remain searchable");
          layout(panel);
          BufferedImage image = new BufferedImage(780, 960, BufferedImage.TYPE_INT_RGB);
          var g = image.createGraphics();
          panel.printAll(g);
          g.dispose();
          try {
            Path path = Path.of("build/reports/ui/graph-navigation.png");
            Files.createDirectories(path.getParent());
            ImageIO.write(image, "png", path.toFile());
          } catch (Exception error) {
            throw new RuntimeException(error);
          }
          panel.sync(settings);
          choose(list, "field");
          comment.setText("one edit");
          click(panel, "update");
          for (int i = 0; i < 40; i++) {
            search.setText("noise");
            search.setText("");
            choose(list, "time");
            choose(list, "field");
          }
          click(panel, "undo");
          check(
              comment.getText().equals("Fine detail"),
              "search and list selection consume no draft undo entries");
        });
    return count;
  }
}
