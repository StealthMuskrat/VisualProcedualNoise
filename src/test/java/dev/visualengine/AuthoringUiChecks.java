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

public final class AuthoringUiChecks {
  private static int count;

  private static void check(boolean ok, String message) {
    count++;
    if (!ok) throw new AssertionError(message);
  }

  private static Component find(Container root, String name) {
    for (Component child : root.getComponents()) {
      if (("authoring-" + name).equals(child.getName())) return child;
      if (child instanceof Container nested) {
        Component match = find(nested, name);
        if (match != null) return match;
      }
    }
    return null;
  }

  private static void click(Container root, String name) {
    ((JButton) find(root, name)).doClick();
  }

  private static void layout(Container root) {
    root.doLayout();
    for (Component child : root.getComponents())
      if (child instanceof Container nested) layout(nested);
  }

  public static int run() throws Exception {
    count = 0;
    SwingUtilities.invokeAndWait(
        () -> {
          List<Command> commands = new ArrayList<>();
          Settings settings = new Settings();
          AuthoringPanel panel = new AuthoringPanel(commands::add, settings);
          var expression = (JTextArea) find(panel, "expression");
          var formula = (JTextField) find(panel, "node-formula");
          var id = (JTextField) find(panel, "node-id");
          var status = (JTextArea) find(panel, "status");
          var tabs = (JTabbedPane) find(panel, "tabs");
          expression.setText("sin(p.x)\n + missing");
          click(panel, "apply-formula");
          check(
              commands.isEmpty() && "missing".equals(expression.getSelectedText()),
              "rejected formula selects its exact source and sends no scene command");
          check(
              status.getText().contains("line 2, column 4") && tabs.getSelectedIndex() == 0,
              "formula diagnostics reveal the source tab and line/column");
          String longName = "unknown".repeat(800);
          expression.setText(longName);
          click(panel, "apply-formula");
          panel.setSize(620, 900);
          layout(panel);
          check(
              commands.isEmpty()
                  && status.getText().contains(longName)
                  && status.getParent().getHeight() < 150,
              "large diagnostics retain their full text in a bounded scrolling area");
          expression.setText("vec2(1)");
          click(panel, "apply-formula");
          check(
              commands.isEmpty() && "vec2(1)".equals(expression.getSelectedText()),
              "non-scalar formulas are caught in the editor before submitting");
          expression.setText("sin(p.x");
          click(panel, "apply-formula");
          check(
              expression.getSelectionStart() == 7 && expression.getSelectionEnd() == 7,
              "EOF diagnostics place the caret at the missing delimiter");
          expression.setText("sin(p.x)");
          click(panel, "apply-formula");
          check(
              commands.size() == 1
                  && commands.getFirst().type().equals("expression")
                  && status.getText().equals("Formula submitted"),
              "corrected formula submits and clears the previous error");
          formula.setText("sin(p.y) + absent");
          click(panel, "update");
          check(
              commands.size() == 1 && tabs.getSelectedIndex() == 0,
              "invalid graph draft stays local and does not steal the active tab");
          click(panel, "apply-graph");
          check(
              commands.size() == 1
                  && id.getText().equals("field")
                  && "absent".equals(formula.getSelectedText())
                  && tabs.getSelectedIndex() == 1,
              "apply reveals the original graph node and exact formula span");
          check(
              status.getText().contains("Node field") && status.getText().contains("column 12"),
              "graph error displays node identity and source column");
          click(panel, "undo");
          check(
              formula.getText().equals(settings.graph.nodes.getFirst().expression())
                  && status.getText().startsWith("Valid"),
              "graph undo restores draft fields and clears the diagnostic");
          formula.setText("cos(p.x)");
          click(panel, "update");
          click(panel, "apply-graph");
          var submitted = (EffectGraph) commands.getLast().value();
          check(
              commands.size() == 2 && submitted.nodes.getFirst().expression().equals("cos(p.x)"),
              "corrected graph submits a valid snapshot");
          formula.setText("missing");
          click(panel, "update");
          check(
              submitted.nodes.getFirst().expression().equals("cos(p.x)"),
              "later draft edits preserve the submitted graph");
          Settings loaded = new Settings();
          loaded.graph.nodes =
              new ArrayList<>(
                  List.of(
                      new EffectGraph.Node(
                          "loaded",
                          EffectGraph.Kind.FORMULA,
                          List.of(),
                          "0.25",
                          70,
                          70,
                          false,
                          "",
                          "")));
          loaded.graph.output = "loaded";
          panel.sync(loaded);
          click(panel, "undo");
          check(
              id.getText().equals("loaded") && formula.getText().equals("0.25"),
              "scene synchronization discards undo history from the previous scene");
          check(
              !status.isEditable() && status.getLineWrap() && status.getWrapStyleWord(),
              "long diagnostics are readable in a wrapping noneditable area");
          Settings grouped = new Settings();
          grouped.installEffectControls(
              List.of(AuthoringChecks.scalar(), AuthoringChecks.vector()), Map.of());
          grouped.graph = AuthoringChecks.groupGraph();
          var group = grouped.graph.groups.getFirst();
          var body = new ArrayList<>(group.nodes());
          var field = body.getLast();
          body.set(
              body.size() - 1,
              new EffectGraph.Node(
                  field.id(),
                  field.kind(),
                  field.inputs(),
                  "input0.z",
                  field.x(),
                  field.y(),
                  false,
                  "",
                  ""));
          grouped.graph.groups.set(
              0,
              new GraphTemplates.Group(
                  group.id(),
                  group.version(),
                  group.inputs(),
                  body,
                  group.output(),
                  group.outputType()));
          grouped.graph.nodes.set(0, grouped.graph.nodes.get(0).moved(70, 80));
          grouped.graph.nodes.set(1, grouped.graph.nodes.get(1).moved(70, 240));
          grouped.graph.nodes.set(2, grouped.graph.nodes.get(2).moved(300, 150));
          panel.setSize(620, 900);
          layout(panel);
          panel.sync(grouped);
          click(panel, "apply-graph");
          check(
              commands.size() == 2
                  && id.getText().equals("instance")
                  && status.getText().contains("instance / field"),
              "nested errors select the root instance and show the original inner node path");
          check(
              formula.getSelectedText() == null,
              "inner expression offsets are never applied to the outer instance's formula");
          List<Command> boundedCommands = new ArrayList<>();
          Settings boundedSettings = new Settings();
          boundedSettings.installEffectControls(SourceBoundsChecks.controls(), Map.of());
          AuthoringPanel bounded = new AuthoringPanel(boundedCommands::add, boundedSettings);
          var boundedExpression = (JTextArea) find(bounded, "expression");
          boundedExpression.setText(SourceBoundsChecks.expression(26));
          click(bounded, "apply-formula");
          check(
              boundedCommands.isEmpty()
                  && boundedExpression.getSelectionStart() == 0
                  && boundedExpression.getSelectionEnd() == boundedExpression.getText().length()
                  && ((JTextArea) find(bounded, "status"))
                      .getText()
                      .contains("expression and gradient exceed 64 KiB"),
              "generated-source limits select the complete offending formula without submitting"
                  + " it");
          panel.setSize(620, 900);
          layout(panel);
          check(
              status.getWidth() < 620 && status.getHeight() > 25,
              "long diagnostics fit the inspector width");
          BufferedImage image = new BufferedImage(620, 900, BufferedImage.TYPE_INT_RGB);
          var graphics = image.createGraphics();
          panel.printAll(graphics);
          graphics.dispose();
          try {
            Path path = Path.of("build/reports/ui/authoring-diagnostics.png");
            Files.createDirectories(path.getParent());
            ImageIO.write(image, "png", path.toFile());
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        });
    return count;
  }
}
