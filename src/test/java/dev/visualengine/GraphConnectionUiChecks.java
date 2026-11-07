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

public final class GraphConnectionUiChecks {
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

  private static JButton button(Container root, String name) {
    return (JButton) find(root, name);
  }

  private static void click(Container root, String name) {
    button(root, name).doClick();
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

  private static String chosen(JComboBox<?> list) {
    return list.getSelectedItem() == null
        ? null
        : ((GraphNavigation.Choice) list.getSelectedItem()).id();
  }

  private static void mouse(Component canvas, int type, int modifiers, int x, int y) {
    canvas.dispatchEvent(
        new MouseEvent(canvas, type, 1, modifiers, x, y, 1, false, MouseEvent.BUTTON1));
  }

  public static int run() throws Exception {
    count = 0;
    SwingUtilities.invokeAndWait(
        () -> {
          Settings settings = new Settings();
          settings.graph = GraphConnectionChecks.graph();
          int[][] positions = {{40, 60}, {40, 180}, {250, 60}, {460, 60}, {40, 300}};
          for (int i = 0; i < positions.length; i++)
            settings.graph.nodes.set(
                i, settings.graph.nodes.get(i).moved(positions[i][0], positions[i][1]));
          List<Command> commands = new ArrayList<>();
          AuthoringPanel panel = new AuthoringPanel(commands::add, settings);
          var tabs = (JTabbedPane) find(panel, "tabs");
          tabs.setSelectedIndex(2);
          panel.setSize(530, 800);
          layout(panel);
          var target = (JComboBox<?>) find(panel, "connection-target");
          var source = (JComboBox<?>) find(panel, "connection-source");
          var slots = (JList<?>) find(panel, "connection-inputs");
          var id = (JTextField) find(panel, "node-id");
          var raw = (JTextField) find(panel, "node-inputs");
          var comment = (JTextField) find(panel, "node-comment");
          var status = (JTextArea) find(panel, "status");
          check(
              chosen(target).equals("field")
                  && target.getItemCount() == 5
                  && slots.getModel().getSize() == 1,
              "connection editor shares initial selected root node");
          check(
              source.getSelectedItem() == null
                  && !button(panel, "connection-append").isEnabled()
                  && !button(panel, "connection-remove").isEnabled(),
              "connection actions require an explicit source or input selection");
          choose(target, "point");
          check(
              id.getText().equals("point")
                  && slots
                      .getModel()
                      .getElementAt(0)
                      .toString()
                      .contains("input0 ← coord · COORDINATES"),
              "target choice updates node fields and ordered source descriptions");
          slots.setSelectedIndex(0);
          choose(source, "coord");
          check(
              button(panel, "connection-replace").isEnabled()
                  && !button(panel, "connection-up").isEnabled()
                  && button(panel, "connection-down").isEnabled(),
              "connection actions reflect ordinal boundaries");
          comment.setText("one edit");
          click(panel, "update");
          for (int i = 0; i < 40; i++) {
            choose(source, "shifted");
            choose(source, "coord");
            slots.setSelectedIndex(i % 2);
          }
          slots.setSelectedIndex(0);
          click(panel, "connection-replace");
          click(panel, "undo");
          check(
              comment.getText().equals("") && commands.isEmpty(),
              "source and slot selection and unchanged replacement consume no history or commands");
          choose(source, "shifted");
          slots.setSelectedIndex(0);
          comment.setText("unfinished property");
          raw.setText("unfinished inputs");
          click(panel, "connection-replace");
          check(
              raw.getText().equals("shifted, time")
                  && comment.getText().equals("unfinished property")
                  && slots.getSelectedIndex() == 0
                  && chosen(source).equals("shifted"),
              "reroute updates model links and input field while retaining other unfinished"
                  + " property fields");
          check(
              commands.isEmpty() && status.getText().startsWith("Valid"),
              "valid connection edits remain local until Apply");
          click(panel, "apply-graph");
          var accepted = submitted(commands);
          check(
              accepted.nodes.get(2).inputs().equals(List.of("shifted", "time"))
                  && accepted.nodes.get(2).comment().isEmpty(),
              "Apply submits the accepted connection draft without unfinished property edits");
          click(panel, "undo");
          check(
              raw.getText().equals("coord, time")
                  && id.getText().equals("point")
                  && accepted.nodes.get(2).inputs().getFirst().equals("shifted"),
              "undo restores links and selection without changing a submitted snapshot");
          slots.setSelectedIndex(0);
          int before = commands.size();
          click(panel, "connection-down");
          check(
              raw.getText().equals("time, coord")
                  && slots.getSelectedIndex() == 1
                  && status.getText().contains("Expected VECTOR2"),
              "reordering immediately reports invalid port types and keeps the moved slot"
                  + " selected");
          click(panel, "apply-graph");
          check(commands.size() == before, "invalid connection order never reaches the renderer");
          tabs.setSelectedIndex(2);
          click(panel, "connection-up");
          check(
              raw.getText().equals("coord, time") && status.getText().startsWith("Valid"),
              "reordering repairs the typed draft");
          click(panel, "undo");
          click(panel, "undo");
          slots.setSelectedIndex(0);
          click(panel, "connection-remove");
          check(
              raw.getText().equals("time")
                  && slots.getSelectedIndex() == 0
                  && status.getText().contains("requires 2 inputs"),
              "removing one input leaves a repairable arity diagnostic");
          choose(source, "coord");
          click(panel, "connection-append");
          click(panel, "connection-up");
          click(panel, "apply-graph");
          check(
              GraphEdits.same(submitted(commands), settings.graph),
              "remove, append and reorder can restore the complete original graph");
          panel.sync(settings);
          tabs.setSelectedIndex(2);
          choose(target, "point");
          choose(source, "coord");
          click(panel, "connection-append");
          check(
              slots.getModel().getSize() == 3 && slots.getSelectedIndex() == 2,
              "append selects the newly added repeated input");
          slots.setSelectedIndex(0);
          click(panel, "connection-remove");
          check(
              raw.getText().equals("time, coord"),
              "removal affects only the chosen repeated occurrence");
          panel.sync(settings);
          tabs.setSelectedIndex(2);
          choose(target, "point");
          raw.setText("ghost, time");
          click(panel, "update");
          check(
              slots.getModel().getElementAt(0).toString().contains("ghost · missing node"),
              "dangling references remain visible for repair");
          slots.setSelectedIndex(0);
          choose(source, "coord");
          click(panel, "connection-replace");
          check(
              status.getText().startsWith("Valid"),
              "a dangling input can be rerouted without compiling first");
          choose(source, "field");
          click(panel, "connection-replace");
          check(
              status.getText().contains("Same-step cycle"),
              "cycle edits receive immediate graph diagnostics");
          click(panel, "undo");
          choose(target, "coord");
          id.setText("domain");
          click(panel, "update");
          choose(target, "point");
          check(
              slots.getModel().getElementAt(0).toString().contains("domain")
                  && target.getItemCount() == 5,
              "renamed sources refresh targets and ordered links");
          click(panel, "undo");
          choose(target, "point");
          choose(source, "shifted");
          slots.setSelectedIndex(0);
          click(panel, "connection-replace");
          choose(target, "shifted");
          click(panel, "delete");
          check(
              target.getSelectedItem() == null
                  && source.getSelectedItem() == null
                  && !button(panel, "connection-append").isEnabled(),
              "deleted target and source choices are cleared");
          choose(target, "point");
          check(
              slots.getModel().getElementAt(0).toString().contains("missing node"),
              "deleting a connected source leaves its dangling slot visible");
          click(panel, "undo");
          check(
              target.getItemCount() == 5 && chosen(target).equals("shifted"),
              "undo deletion restores connection choices and selected target");
          click(panel, "add");
          check(
              target.getItemCount() == 6 && chosen(target).equals(id.getText()),
              "new nodes enter the connection target list");
          panel.sync(settings);
          check(
              chosen(target).equals("field")
                  && source.getSelectedItem() == null
                  && slots.getSelectedIndex() == -1,
              "scene sync clears stale connection choices and input selection");
          tabs.setSelectedIndex(1);
          layout(panel);
          var canvas = find(panel, "canvas");
          mouse(canvas, MouseEvent.MOUSE_PRESSED, 0, 50, 70);
          mouse(canvas, MouseEvent.MOUSE_RELEASED, 0, 50, 70);
          mouse(canvas, MouseEvent.MOUSE_PRESSED, MouseEvent.SHIFT_DOWN_MASK, 260, 70);
          mouse(canvas, MouseEvent.MOUSE_RELEASED, 0, 260, 70);
          check(
              chosen(target).equals("point")
                  && slots.getModel().getSize() == 3
                  && slots.getSelectedIndex() == 2,
              "shift-click append uses the same connection model and refreshes the editor");
          click(panel, "undo");
          click(panel, "apply-graph");
          check(
              GraphEdits.same(submitted(commands), settings.graph),
              "one undo reverses the shift-click link");
          tabs.setSelectedIndex(2);
          choose(target, "point");
          click(panel, "connection-show");
          check(
              tabs.getSelectedIndex() == 1 && id.getText().equals("point"),
              "Show target reveals the selected node on the canvas");
          panel.sync(settings);
          choose(target, "time");
          click(panel, "preview");
          choose(source, "coord");
          click(panel, "connection-append");
          comment.setText("pending repair");
          click(panel, "update");
          check(
              ((JTextField) find(panel, "output")).getText().equals("time"),
              "connection edits after an accepted preview preserve its active draft output");
          panel.sync(settings);
          choose(target, "point");
          tabs.setSelectedIndex(2);
          slots.setSelectedIndex(0);
          choose(source, "shifted");
          click(panel, "connection-replace");
          layout(panel);
          BufferedImage image = new BufferedImage(530, 800, BufferedImage.TYPE_INT_RGB);
          var g = image.createGraphics();
          panel.printAll(g);
          g.dispose();
          try {
            var path = Path.of("build/reports/ui/graph-connections.png");
            Files.createDirectories(path.getParent());
            ImageIO.write(image, "png", path.toFile());
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        });
    return count;
  }
}
