package dev.visualengine.ui;

import dev.visualengine.app.Command;
import dev.visualengine.authoring.*;
import dev.visualengine.model.Settings;
import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.*;

public final class AuthoringPanel extends JPanel {
  private final Consumer<Command> submit;
  private final JTextArea expression = new JTextArea(6, 28);
  private EffectGraph graph;
  private final Canvas canvas = new Canvas();
  private final JTextField id = new JTextField(),
      inputs = new JTextField(),
      formula = new JTextField(),
      group = new JTextField(),
      comment = new JTextField(),
      output = new JTextField();
  private final JComboBox<EffectGraph.Kind> kind = new JComboBox<>(EffectGraph.Kind.values());
  private final JCheckBox bypass = new JCheckBox("Bypass selected node");
  private final JLabel diagnostic = new JLabel("Ready");
  private final Deque<EffectGraph> undo = new ArrayDeque<>();
  private String selected;
  private java.util.List<EffectControl> controls = java.util.List.of();

  public AuthoringPanel(Consumer<Command> submit, Settings settings) {
    this.submit = submit;
    controls = java.util.List.copyOf(settings.effectControls);
    graph = settings.graph.copy();
    expression.setText(settings.expression);
    setLayout(new BorderLayout());
    JTabbedPane tabs = new JTabbedPane();
    JPanel formulas = new JPanel(new BorderLayout(8, 8));
    expression.setLineWrap(true);
    expression.setWrapStyleWord(true);
    formulas.add(new JScrollPane(expression), BorderLayout.CENTER);
    JPanel actions = new JPanel(new GridLayout(0, 1, 3, 3));
    JComboBox<String> templates =
        new JComboBox<>(new String[] {"Radial waves", "Nested noise", "Interference"});
    templates.addActionListener(e ->
        expression.setText(
            switch (templates.getSelectedIndex()) {
              case 1 -> "fbm(vec3(p * frequency + vec2(noise3d(vec3(p, phase))), sin(phase)))";
              case 2 -> "sin(p.x * stripeRate + phase) * cos(p.y * frequency - phase)";
              default -> "sin(length(p) * frequency - phase)";
            }));
    actions.add(templates);
    JButton apply = new JButton("Apply formula");
    apply.addActionListener(e -> {
      try {
        EffectControl.compile(expression.getText(), controls);
        submit.accept(new Command("expression", expression.getText()));
        diagnostic.setText("Formula submitted");
      } catch (Exception ex) {
        diagnostic.setText(ex.getMessage());
      }
    });
    actions.add(apply);
    actions.add(new JLabel("p: coordinates · t: seconds · phase: radians"));
    actions.add(new JLabel("Knobs: frequency, drift, stripeRate"));
    formulas.add(actions, BorderLayout.SOUTH);
    tabs.addTab("Formulas", formulas);
    JPanel nodes = new JPanel(new BorderLayout());
    nodes.add(new JScrollPane(canvas), BorderLayout.CENTER);
    JPanel edit = new JPanel(new GridLayout(0, 2, 4, 4));
    edit.add(new JLabel("Node ID"));
    edit.add(id);
    edit.add(new JLabel("Operation"));
    edit.add(kind);
    edit.add(new JLabel("Input node IDs in order"));
    edit.add(inputs);
    edit.add(new JLabel("Formula"));
    edit.add(formula);
    edit.add(new JLabel("Group"));
    edit.add(group);
    edit.add(new JLabel("Comment"));
    edit.add(comment);
    edit.add(bypass);
    JButton update = new JButton("Update selected");
    update.addActionListener(e -> updateSelected());
    edit.add(update);
    JButton add = new JButton("Add node");
    add.addActionListener(e -> {
      remember();
      String name = "node" + (graph.nodes.size() + 1);
      while (find(name) != null) name += "x";
      graph.nodes.add(
          new EffectGraph.Node(
              name,
              (EffectGraph.Kind) kind.getSelectedItem(),
              List.of(),
              "0",
              60 + (graph.nodes.size() % 3) * 180,
              60 + (graph.nodes.size() / 3) * 100,
              false,
              "",
              ""));
      select(name);
      refresh();
    });
    edit.add(add);
    JButton delete = new JButton("Delete selected");
    delete.addActionListener(e -> {
      remember();
      graph.nodes.removeIf(n -> n.id().equals(selected));
      selected = null;
      refresh();
    });
    edit.add(delete);
    JButton previous = new JButton("Undo graph edit");
    previous.addActionListener(e -> {
      if (!undo.isEmpty()) {
        graph = undo.pop();
        output.setText(graph.output);
        refresh();
      }
    });
    edit.add(previous);
    JButton preview = new JButton("Preview selected output");
    preview.addActionListener(e -> {
      if (selected != null) {
        graph.output = selected;
        output.setText(selected);
        applyGraph();
      }
    });
    edit.add(preview);
    edit.add(new JLabel("Active output node"));
    output.setText(graph.output);
    edit.add(output);
    JButton applyGraph = new JButton("Apply graph");
    applyGraph.addActionListener(e -> applyGraph());
    edit.add(applyGraph);
    edit.add(new JLabel("Shift-click a target to connect from selection"));
    nodes.add(edit, BorderLayout.SOUTH);
    tabs.addTab("Node graph", nodes);
    add(tabs, BorderLayout.CENTER);
    add(diagnostic, BorderLayout.SOUTH);
    select(graph.output);
    refresh();
  }

  private EffectGraph.Node find(String name) {
    return graph.nodes.stream().filter(n -> n.id().equals(name)).findFirst().orElse(null);
  }

  private void remember() {
    undo.push(graph.copy());
    while (undo.size() > 32) undo.removeLast();
  }

  private void select(String name) {
    selected = name;
    EffectGraph.Node n = find(name);
    if (n == null) return;
    id.setText(n.id());
    kind.setSelectedItem(n.kind());
    inputs.setText(String.join(", ", n.inputs()));
    formula.setText(n.expression());
    group.setText(n.group());
    comment.setText(n.comment());
    bypass.setSelected(n.bypass());
    canvas.repaint();
  }

  private void updateSelected() {
    EffectGraph.Node old = find(selected);
    if (old == null) return;
    remember();
    List<String> sources =
        inputs.getText().trim().isEmpty()
            ? List.of()
            : Arrays.asList(inputs.getText().trim().split("[,\\s]+"));
    var n =
        new EffectGraph.Node(
            id.getText().trim(),
            (EffectGraph.Kind) kind.getSelectedItem(),
            sources,
            formula.getText(),
            old.x(),
            old.y(),
            bypass.isSelected(),
            group.getText(),
            comment.getText());
    graph.nodes.set(graph.nodes.indexOf(old), n);
    if (graph.output.equals(selected)) graph.output = n.id();
    selected = n.id();
    output.setText(graph.output);
    refresh();
  }

  private void applyGraph() {
    try {
      graph.output = output.getText().trim();
      var compiled = GraphCompiler.compile(graph, controls);
      submit.accept(new Command("graph", graph.copy()));
      diagnostic.setText("Applied " + compiled.order().size() + " reachable nodes");
    } catch (Exception ex) {
      diagnostic.setText(ex.getMessage());
    }
  }

  private void refresh() {
    try {
      var c = GraphCompiler.compile(graph, controls);
      diagnostic.setText("Valid scalar graph · " + c.order().size() + " active nodes");
    } catch (Exception e) {
      diagnostic.setText(e.getMessage());
    }
    canvas.repaint();
  }

  public void sync(Settings s) {
    controls = java.util.List.copyOf(s.effectControls);
    expression.setText(s.expression);
    graph = s.graph == null ? new EffectGraph() : s.graph.copy();
    output.setText(graph.output);
    select(graph.output);
    refresh();
  }

  private final class Canvas extends JPanel {
    private Point anchor;
    private EffectGraph.Node dragged;

    Canvas() {
      setPreferredSize(new Dimension(1200, 900));
      setBackground(new Color(25, 29, 38));
      MouseAdapter mouse =
          new MouseAdapter() {
            public void mousePressed(MouseEvent e) {
              EffectGraph.Node hit =
                  graph.nodes.stream()
                      .filter(n ->
                          new Rectangle((int) n.x(), (int) n.y(), 150, 76).contains(e.getPoint()))
                      .findFirst()
                      .orElse(null);
              if (hit == null) return;
              if (e.isShiftDown() && selected != null && !selected.equals(hit.id())) {
                remember();
                var links = new ArrayList<>(hit.inputs());
                links.add(selected);
                var next =
                    new EffectGraph.Node(
                        hit.id(),
                        hit.kind(),
                        links,
                        hit.expression(),
                        hit.x(),
                        hit.y(),
                        hit.bypass(),
                        hit.group(),
                        hit.comment());
                graph.nodes.set(graph.nodes.indexOf(hit), next);
                select(next.id());
                refresh();
                return;
              }
              select(hit.id());
              remember();
              dragged = hit;
              anchor = e.getPoint();
            }

            public void mouseDragged(MouseEvent e) {
              if (dragged == null) return;
              EffectGraph.Node now = find(dragged.id());
              var moved =
                  dragged.moved(
                      Math.max(0, dragged.x() + e.getX() - anchor.x),
                      Math.max(0, dragged.y() + e.getY() - anchor.y));
              graph.nodes.set(graph.nodes.indexOf(now), moved);
              repaint();
            }

            public void mouseReleased(MouseEvent e) {
              dragged = null;
            }
          };
      addMouseListener(mouse);
      addMouseMotionListener(mouse);
    }

    protected void paintComponent(Graphics graphics) {
      super.paintComponent(graphics);
      Graphics2D g = (Graphics2D) graphics.create();
      g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      for (var n : graph.nodes)
        for (String source : n.inputs()) {
          var from = find(source);
          if (from != null) {
            g.setColor(new Color(95, 140, 165));
            g.drawLine((int) from.x() + 150, (int) from.y() + 38, (int) n.x(), (int) n.y() + 38);
          }
        }
      for (var n : graph.nodes) {
        int x = (int) n.x(), y = (int) n.y();
        g.setColor(n.id().equals(selected) ? new Color(56, 104, 130) : new Color(48, 55, 70));
        g.fillRoundRect(x, y, 150, 76, 12, 12);
        g.setColor(Color.WHITE);
        g.drawString(n.id(), x + 10, y + 22);
        g.setColor(new Color(190, 210, 220));
        g.drawString(n.kind().name(), x + 10, y + 43);
        g.drawString(n.bypass() ? "Bypassed" : n.group(), x + 10, y + 63);
      }
      g.dispose();
    }
  }
}
