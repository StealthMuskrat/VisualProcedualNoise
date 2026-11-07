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

public final class GroupCaptureUiChecks {
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
    throw new AssertionError("Missing source: " + id);
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
          Settings settings = new Settings();
          settings.graph = AuthoringChecks.noiseGraph();
          List<Command> commands = new ArrayList<>();
          var panel = new AuthoringPanel(commands::add, settings);
          var tabs = (JTabbedPane) find(panel, "tabs");
          var source = (JComboBox<?>) find(panel, "group-source");
          var name = (JTextField) find(panel, "group-input-name");
          var id = (JTextField) find(panel, "group-id");
          var version = (JTextField) find(panel, "group-version");
          var ports = (JList<?>) find(panel, "group-inputs");
          var revisions = (JComboBox<?>) find(panel, "group-revisions");
          var status = (JTextArea) find(panel, "status");
          var output = (JTextField) find(panel, "output");
          click(panel, "open-groups");
          panel.setSize(530, 800);
          layout(panel);
          check(
              tabs.getSelectedIndex() == 3
                  && source.getItemCount() == 4
                  && revisions.getItemCount() == 0,
              "group capture opens a complete root-source form");
          check(
              id.getText().equals("local.group1")
                  && version.getText().equals("1.0.0")
                  && !((JButton) find(panel, "group-add-input")).isEnabled(),
              "capture form supplies a new identity and requires a source/name");
          choose(source, "coord");
          name.setText("domain");
          click(panel, "group-add-input");
          choose(source, "time");
          name.setText("clock");
          click(panel, "group-add-input");
          check(
              ports.getModel().getSize() == 2
                  && ports.getSelectedIndex() == 1
                  && ports.getModel().getElementAt(0).toString().equals("domain ← coord"),
              "named inputs enter the declared port order");
          choose(source, "time");
          name.setText("another");
          click(panel, "group-add-input");
          check(
              ports.getModel().getSize() == 2 && status.getText().contains("only once"),
              "duplicate source inputs are rejected without changing the form");
          choose(source, "point");
          name.setText("domain");
          click(panel, "group-add-input");
          check(
              ports.getModel().getSize() == 2 && status.getText().contains("already exists"),
              "duplicate port names are rejected without changing the form");
          name.setText("bad name");
          click(panel, "group-add-input");
          check(
              ports.getModel().getSize() == 2
                  && name.getText().equals("bad name")
                  && status.getText().contains("Input names"),
              "invalid input names remain editable");
          click(panel, "group-up");
          check(
              ports.getSelectedIndex() == 0
                  && ports.getModel().getElementAt(0).toString().equals("clock ← time"),
              "port reordering follows the selected entry");
          click(panel, "group-down");
          ports.setSelectedIndex(0);
          click(panel, "group-remove-input");
          check(
              ports.getModel().getSize() == 1
                  && ports.getModel().getElementAt(0).toString().equals("clock ← time"),
              "form removal affects only its selected port");
          choose(source, "coord");
          name.setText("domain");
          click(panel, "group-add-input");
          click(panel, "group-up");
          check(commands.isEmpty(), "editing capture metadata submits no scene commands");
          id.setText("local.editor");
          click(panel, "group-capture");
          check(
              tabs.getSelectedIndex() == 1
                  && output.getText().equals("instance")
                  && revisions.getItemCount() == 1
                  && ports.getModel().getSize() == 0,
              "capture creates a revision, selects the instance and clears the temporary input"
                  + " form");
          check(commands.isEmpty(), "capture changes only the graph draft until Apply");
          click(panel, "apply-graph");
          var captured = submitted(commands);
          check(
              captured.nodes.size() == 3
                  && captured
                      .groups
                      .getFirst()
                      .inputs()
                      .equals(
                          List.of(
                              new GraphTemplates.Input("domain", GraphCompiler.Port.VECTOR2),
                              new GraphTemplates.Input("clock", GraphCompiler.Port.SCALAR))),
              "Apply submits typed named boundaries and retained external sources");
          var slots = (JList<?>) find(panel, "connection-inputs");
          check(
              slots.getModel().getElementAt(0).toString().contains("domain: VECTOR2")
                  && slots.getModel().getElementAt(1).toString().contains("clock: SCALAR"),
              "connection editor shows captured port names and types");
          click(panel, "undo");
          click(panel, "apply-graph");
          check(
              GraphEdits.same(submitted(commands), settings.graph) && revisions.getItemCount() == 0,
              "one undo restores the complete graph before capture");
          check(
              captured.groups.size() == 1
                  && captured.nodes.getLast().template().equals("local.editor@1.0.0"),
              "undo preserves already submitted captured snapshots");
          tabs.setSelectedIndex(3);
          choose(source, "coord");
          name.setText("domain");
          click(panel, "group-add-input");
          id.setText("bad id");
          int before = commands.size();
          click(panel, "group-capture");
          check(
              commands.size() == before
                  && ports.getModel().getSize() == 1
                  && output.getText().equals("field")
                  && status.getText().contains("group revision"),
              "failed capture retains input form and prior graph");
          id.setText("local.editor");
          click(panel, "group-capture");
          click(panel, "apply-graph");
          var first = submitted(commands);
          tabs.setSelectedIndex(3);
          click(panel, "group-capture");
          check(
              status.getText().contains("already exists") && revisions.getItemCount() == 1,
              "existing pinned revisions cannot be overwritten");
          version.setText("1.1.0");
          click(panel, "group-capture");
          click(panel, "apply-graph");
          var second = submitted(commands);
          check(
              second.groups.size() == 2 && second.groups.getFirst().equals(first.groups.getFirst()),
              "capturing a new revision preserves the old definition");
          click(panel, "undo");
          click(panel, "apply-graph");
          check(
              GraphEdits.same(submitted(commands), first),
              "new revision capture is one reversible graph edit");
          panel.sync(settings);
          tabs.setSelectedIndex(3);
          choose(source, "coord");
          name.setText("domain");
          click(panel, "group-add-input");
          var target = (JComboBox<?>) find(panel, "connection-target");
          choose(target, "coord");
          ((JTextField) find(panel, "node-id")).setText("renamedCoord");
          click(panel, "update");
          check(
              ports.getModel().getSize() == 1,
              "source renaming retains the unsubmitted capture form for correction");
          click(panel, "group-capture");
          check(
              status.getText().contains("contribute") && output.getText().equals("field"),
              "stale capture source IDs are rejected without changing the graph");
          panel.sync(settings);
          check(
              ports.getModel().getSize() == 0
                  && source.getSelectedItem() == null
                  && id.getText().equals("local.group1")
                  && version.getText().equals("1.0.0"),
              "scene sync resets capture metadata and sources");
          tabs.setSelectedIndex(3);
          choose(source, "coord");
          name.setText("domain");
          click(panel, "group-add-input");
          choose(source, "time");
          name.setText("clock");
          click(panel, "group-add-input");
          ((JTextField) find(panel, "node-comment")).setText("one graph edit");
          click(panel, "update");
          for (int i = 0; i < 40; i++) {
            ports.setSelectedIndex(0);
            click(panel, "group-down");
            click(panel, "group-up");
          }
          click(panel, "undo");
          click(panel, "apply-graph");
          check(
              GraphEdits.same(submitted(commands), settings.graph),
              "form-only edits consume no graph undo entries");
          layout(panel);
          var image = new BufferedImage(530, 800, BufferedImage.TYPE_INT_RGB);
          var g = image.createGraphics();
          panel.printAll(g);
          g.dispose();
          try {
            var path = Path.of("build/reports/ui/group-capture.png");
            Files.createDirectories(path.getParent());
            ImageIO.write(image, "png", path.toFile());
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        });
    return count;
  }
}
