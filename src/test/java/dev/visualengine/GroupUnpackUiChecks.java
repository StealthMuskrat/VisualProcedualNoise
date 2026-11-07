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

public final class GroupUnpackUiChecks {
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
          settings.graph = GroupUnpackChecks.packed();
          List<Command> commands = new ArrayList<>();
          var panel = new AuthoringPanel(commands::add, settings);
          var tabs = (JTabbedPane) find(panel, "tabs");
          tabs.setSelectedIndex(3);
          panel.setSize(530, 800);
          layout(panel);
          var instance = (JComboBox<?>) find(panel, "group-instance");
          var unpack = (JButton) find(panel, "group-unpack");
          var revisions = (JComboBox<?>) find(panel, "group-revisions");
          var source = (JComboBox<?>) find(panel, "group-source");
          var ports = (JList<?>) find(panel, "group-inputs");
          var nodeId = (JTextField) find(panel, "node-id");
          var output = (JTextField) find(panel, "output");
          var status = (JTextArea) find(panel, "status");
          var comment = (JTextField) find(panel, "node-comment");
          var navigator = (JComboBox<?>) find(panel, "node-list");
          var target = (JComboBox<?>) find(panel, "connection-target");
          check(
              instance.getItemCount() == 1
                  && instance.getSelectedItem() != null
                  && unpack.isEnabled(),
              "group selector shares the initial selected instance");
          choose(target, "coord");
          check(
              instance.getSelectedItem() == null && !unpack.isEnabled(),
              "selecting a non-group disables unpack");
          choose(instance, "instance");
          check(
              nodeId.getText().equals("instance")
                  && revisions.getSelectedItem().equals("local.unpack@1.0.0"),
              "choosing an instance synchronizes node fields and pinned revision display");
          choose(source, "coord");
          ((JTextField) find(panel, "group-input-name")).setText("domain");
          click(panel, "group-add-input");
          click(panel, "group-unpack");
          check(
              tabs.getSelectedIndex() == 1
                  && nodeId.getText().equals("instance")
                  && output.getText().equals("instance"),
              "unpack reveals the preserved output identity");
          check(
              instance.getItemCount() == 0
                  && !unpack.isEnabled()
                  && source.getItemCount() == 9
                  && revisions.getItemCount() == 1
                  && ports.getModel().getSize() == 1,
              "unpack refreshes root choices while preserving revisions and capture form");
          check(
              commands.isEmpty() && status.getText().startsWith("Valid"),
              "unpacking remains a validated draft without engine submission");
          click(panel, "apply-graph");
          var accepted = submitted(commands);
          check(
              accepted.nodes.size() == 9 && accepted.groups.equals(settings.graph.groups),
              "Apply submits unpacked members without changing pinned definitions");
          String overlapMember =
              accepted.nodes.stream()
                  .filter(n -> n.kind() == EffectGraph.Kind.VECTOR3)
                  .findFirst()
                  .orElseThrow()
                  .id();
          choose(navigator, overlapMember);
          var canvas = find(panel, "canvas");
          for (int[] event :
              new int[][] {
                {MouseEvent.MOUSE_PRESSED, 10, 10},
                {MouseEvent.MOUSE_DRAGGED, 30, 40},
                {MouseEvent.MOUSE_RELEASED, 30, 40}
              })
            canvas.dispatchEvent(
                new MouseEvent(
                    canvas, event[0], 1, 0, event[1], event[2], 1, false, MouseEvent.BUTTON1));
          click(panel, "apply-graph");
          var moved =
              submitted(commands).nodes.stream()
                  .filter(n -> n.id().equals(overlapMember))
                  .findFirst()
                  .orElseThrow();
          check(
              moved.x() == 20
                  && moved.y() == 30
                  && submitted(commands).nodes.getFirst().equals(settings.graph.nodes.getFirst()),
              "overlapping unpacked nodes pick and drag the selected visible member");
          click(panel, "undo");
          click(panel, "apply-graph");
          check(
              nodeId.getText().equals(overlapMember)
                  && GraphEdits.same(submitted(commands), accepted),
              "overlap drag undo restores its complete graph and selected member");
          click(panel, "undo");
          click(panel, "apply-graph");
          check(
              GraphEdits.same(submitted(commands), settings.graph)
                  && nodeId.getText().equals("instance")
                  && instance.getSelectedItem() != null,
              "one undo restores the packed graph and selected instance");
          check(
              accepted.nodes.stream().noneMatch(n -> n.kind() == EffectGraph.Kind.GROUP),
              "undo preserves an already submitted unpacked snapshot");
          comment.setText("one edit");
          click(panel, "update");
          for (int i = 0; i < 40; i++) {
            choose(target, "coord");
            choose(instance, "instance");
          }
          click(panel, "undo");
          check(comment.getText().isEmpty(), "instance selection consumes no graph undo history");
          click(panel, "group-unpack");
          String field =
              accepted.nodes.stream()
                  .filter(n -> n.kind() == EffectGraph.Kind.NOISE)
                  .findFirst()
                  .orElseThrow()
                  .id();
          choose(navigator, field);
          ((JComboBox<?>) find(panel, "kind")).setSelectedItem(EffectGraph.Kind.FORMULA);
          ((JTextField) find(panel, "node-inputs")).setText("");
          ((JTextField) find(panel, "node-formula")).setText("sin(p.x * frequency)");
          click(panel, "update");
          check(
              status.getText().startsWith("Valid") && !unpack.isEnabled(),
              "unpacked members can be edited through ordinary node fields");
          ports.setSelectedIndex(0);
          click(panel, "group-remove-input");
          ((JTextField) find(panel, "group-id")).setText("local.unpack");
          ((JTextField) find(panel, "group-version")).setText("1.1.0");
          click(panel, "group-capture");
          click(panel, "apply-graph");
          var revised = submitted(commands);
          check(
              revised.groups.size() == 2
                  && revised.groups.getFirst().equals(settings.graph.groups.getFirst())
                  && revised.groups.getLast().version().equals("1.1.0"),
              "edited roots can be captured as a new revision while the old definition remains"
                  + " exact");
          check(
              revised.groups.getLast().nodes().stream()
                  .anyMatch(n -> n.id().equals(field) && n.kind() == EffectGraph.Kind.FORMULA),
              "new revision contains the deliberate member edit");
          click(panel, "undo");
          check(
              nodeId.getText().equals(field) && revisions.getItemCount() == 1,
              "undo recapture returns to the edited unpacked member");
          click(panel, "undo");
          click(panel, "undo");
          click(panel, "apply-graph");
          check(
              GraphEdits.same(submitted(commands), settings.graph),
              "three logical undos reverse recapture, member edit and unpack");
          check(
              revised.groups.size() == 2 && accepted.groups.size() == 1,
              "revision workflow retains immutable command snapshots");
          var full = settings.copy();
          for (int i = 0; i < 56; i++)
            full.graph.nodes.add(
                new EffectGraph.Node(
                    "extra" + i, EffectGraph.Kind.TIME, List.of(), "0", 0, 0, false, "", ""));
          panel.sync(full);
          tabs.setSelectedIndex(3);
          int before = commands.size();
          click(panel, "group-unpack");
          check(
              status.getText().contains("64 root nodes")
                  && commands.size() == before
                  && instance.getItemCount() == 1,
              "over-limit unpack preserves the instance and scene");
          comment.setText("accepted draft edit");
          click(panel, "update");
          click(panel, "group-unpack");
          click(panel, "undo");
          click(panel, "apply-graph");
          check(
              GraphEdits.same(submitted(commands), full.graph),
              "failed unpack consumes no undo entry");
          var bypass = settings.copy();
          var old = bypass.graph.nodes.getLast();
          bypass.graph.nodes.set(
              2,
              new EffectGraph.Node(
                  old.id(),
                  old.kind(),
                  old.inputs(),
                  old.expression(),
                  old.x(),
                  old.y(),
                  true,
                  old.group(),
                  old.comment(),
                  old.template(),
                  old.seedKey()));
          panel.sync(bypass);
          tabs.setSelectedIndex(3);
          check(
              !unpack.isEnabled() && unpack.getToolTipText().contains("Disable bypass"),
              "bypassed groups require explicit bypass removal");
          panel.sync(new Settings());
          check(
              instance.getItemCount() == 0 && !unpack.isEnabled(),
              "scene sync clears stale instance choices");
          panel.sync(settings);
          tabs.setSelectedIndex(3);
          ((JTextField) find(panel, "group-version")).setText("1.1.0");
          layout(panel);
          var image = new BufferedImage(530, 800, BufferedImage.TYPE_INT_RGB);
          var g = image.createGraphics();
          panel.printAll(g);
          g.dispose();
          try {
            var path = Path.of("build/reports/ui/group-unpack.png");
            Files.createDirectories(path.getParent());
            ImageIO.write(image, "png", path.toFile());
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        });
    return count;
  }
}
