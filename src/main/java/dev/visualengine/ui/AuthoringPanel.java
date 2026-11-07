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
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

public final class AuthoringPanel extends JPanel {
  private final Consumer<Command> submit;
  private final JTextArea expression = new JTextArea(6, 28);
  private EffectGraph graph;
  private final Canvas canvas = new Canvas();
  private final JScrollPane canvasScroll = new JScrollPane(canvas);
  private final JTextField nodeSearch = new JTextField();
  private final JComboBox<GraphNavigation.Choice> nodeList = new JComboBox<>();
  private final JLabel nodeCount = new JLabel();
  private final JButton showNode = new JButton("Show node");
  private boolean syncingNavigator;
  private final JTextField id = new JTextField(),
      inputs = new JTextField(),
      formula = new JTextField(),
      group = new JTextField(),
      comment = new JTextField(),
      output = new JTextField();
  private final JComboBox<EffectGraph.Kind> kind = new JComboBox<>(EffectGraph.Kind.values());
  private final JCheckBox bypass = new JCheckBox("Bypass selected node");
  private final JTextArea diagnostic = new JTextArea("Ready", 3, 28);
  private final JTabbedPane tabs = new JTabbedPane();
  private final ConnectionPanel connections;
  private final GroupCapturePanel groups;
  private String diagnosticNode;

  private record Draft(EffectGraph graph, String selected) {}

  private final Deque<Draft> undo = new ArrayDeque<>();
  private String selected;
  private final JTextField template = new JTextField();
  private java.util.List<EffectControl> controls = java.util.List.of();

  public AuthoringPanel(Consumer<Command> submit, Settings settings) {
    this.submit = submit;
    controls = java.util.List.copyOf(settings.effectControls);
    graph = settings.graph.copy();
    expression.setText(settings.expression);
    setLayout(new BorderLayout());
    setName("authoring-panel");
    expression.setName("authoring-expression");
    formula.setName("authoring-node-formula");
    id.setName("authoring-node-id");
    inputs.setName("authoring-node-inputs");
    group.setName("authoring-node-group");
    comment.setName("authoring-node-comment");
    output.setName("authoring-output");
    kind.setName("authoring-kind");
    canvas.setName("authoring-canvas");
    tabs.setName("authoring-tabs");
    diagnostic.setName("authoring-status");
    diagnostic.setEditable(false);
    diagnostic.setLineWrap(true);
    diagnostic.setWrapStyleWord(true);
    diagnostic.setMargin(new Insets(5, 5, 5, 5));
    JPanel formulas = new JPanel(new BorderLayout(8, 8));
    expression.setLineWrap(true);
    expression.setWrapStyleWord(true);
    formulas.add(new JScrollPane(expression), BorderLayout.CENTER);
    JPanel actions = new JPanel(new GridLayout(0, 1, 3, 3));
    JComboBox<String> templates =
        new JComboBox<>(
            new String[] {"Radial waves", "Nested noise", "Interference", "Gabor field"});
    templates.addActionListener(
        e ->
            expression.setText(
                switch (templates.getSelectedIndex()) {
                  case 1 -> "fbm(vec3(p * frequency + vec2(noise3d(vec3(p, phase))), sin(phase)))";
                  case 2 -> "sin(p.x * stripeRate + phase) * cos(p.y * frequency - phase)";
                  case 3 -> "gabor(p + vec2(cos(phase),sin(phase))*drift, 0.65, frequency, 1.0)";
                  default -> "sin(length(p) * frequency - phase)";
                }));
    actions.add(templates);
    JButton apply = new JButton("Apply formula");
    apply.setName("authoring-apply-formula");
    apply.addActionListener(
        e -> {
          try {
            EffectControl.compileField(expression.getText(), controls);
            submit.accept(new Command("expression", expression.getText()));
            diagnosticNode = null;
            diagnostic.setText("Formula submitted");
            canvas.repaint();
          } catch (Exception ex) {
            showProblem(ex, true, false);
          }
        });
    actions.add(apply);
    actions.add(new JLabel("p: coordinates · t: seconds · phase: radians"));
    actions.add(new JLabel("Knobs: frequency, drift, stripeRate"));
    actions.add(new DirectionalPanel(submit));
    formulas.add(actions, BorderLayout.SOUTH);
    tabs.addTab("Formulas", formulas);
    JPanel nodes = new JPanel(new BorderLayout());
    nodes.add(navigator(), BorderLayout.NORTH);
    nodes.add(canvasScroll, BorderLayout.CENTER);
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
    edit.add(new JLabel("Group revision ID@version"));
    edit.add(template);
    edit.add(bypass);
    JButton update = new JButton("Update selected");
    update.setName("authoring-update");
    update.addActionListener(e -> updateSelected());
    edit.add(update);
    JButton add = new JButton("Add node");
    add.setName("authoring-add");
    add.addActionListener(
        e -> {
          if (graph.nodes.size() >= 64) {
            diagnostic.setText("A graph can contain at most 64 root nodes");
            return;
          }
          remember();
          int index = graph.nodes.size() + 1;
          String name = "node" + index;
          while (find(name) != null) name = "node" + (++index);
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
    delete.setName("authoring-delete");
    delete.addActionListener(
        e -> {
          if (find(selected) == null) return;
          remember();
          graph.nodes.removeIf(n -> n.id().equals(selected));
          select(null);
          refresh();
        });
    edit.add(delete);
    JButton previous = new JButton("Undo graph edit");
    previous.setName("authoring-undo");
    previous.addActionListener(
        e -> {
          if (!undo.isEmpty()) {
            Draft previousDraft = undo.pop();
            graph = previousDraft.graph();
            output.setText(graph.output);
            select(
                find(previousDraft.selected()) == null ? graph.output : previousDraft.selected());
            refresh();
          }
        });
    edit.add(previous);
    JButton preview = new JButton("Preview selected output");
    preview.setName("authoring-preview");
    preview.addActionListener(
        e -> {
          if (selected != null) applyGraph(selected);
        });
    edit.add(preview);
    edit.add(new JLabel("Active output node"));
    output.setText(graph.output);
    edit.add(output);
    JButton applyGraph = new JButton("Apply graph");
    applyGraph.setName("authoring-apply-graph");
    applyGraph.addActionListener(e -> applyGraph());
    edit.add(applyGraph);
    JButton captureGroup = new JButton("Save graph as reusable group");
    captureGroup.setName("authoring-open-groups");
    captureGroup.addActionListener(e -> tabs.setSelectedIndex(3));
    edit.add(captureGroup);
    edit.add(new JLabel("Shift-click a target to connect from selection"));
    nodes.add(edit, BorderLayout.SOUTH);
    tabs.addTab("Node graph", nodes);
    connections =
        new ConnectionPanel(
            this::select,
            this::editConnections,
            error -> showProblem(error, false, true),
            () -> {
              tabs.setSelectedIndex(1);
              var node = find(selected);
              if (node != null) canvas.reveal(node);
            });
    tabs.addTab("Connections", connections);
    groups =
        new GroupCapturePanel(
            this::captureGroup,
            error -> showProblem(error, false, true),
            this::select,
            this::unpackGroup);
    groups.reset(graph, graph.output);
    tabs.addTab("Groups", groups);
    add(tabs, BorderLayout.CENTER);
    JScrollPane messages = new JScrollPane(diagnostic);
    messages.setPreferredSize(
        new Dimension(280, diagnostic.getFontMetrics(diagnostic.getFont()).getHeight() * 3 + 12));
    add(messages, BorderLayout.SOUTH);
    select(graph.output);
    refresh();
  }

  private JPanel navigator() {
    nodeSearch.setName("authoring-node-search");
    nodeSearch.setToolTipText(
        "Find nodes: all words in name, operation, group, comment or template");
    nodeList.setName("authoring-node-list");
    nodeList.setPrototypeDisplayValue(
        new GraphNavigation.Choice("Node name", EffectGraph.Kind.FORMULA));
    nodeCount.setName("authoring-node-count");
    showNode.setName("authoring-show-node");
    JButton reset = new JButton("Reset view");
    reset.setName("authoring-reset-view");
    reset.addActionListener(e -> canvas.resetView());
    showNode.addActionListener(e -> revealChoice());
    nodeList.addActionListener(
        e -> {
          if (!syncingNavigator) revealChoice();
        });
    nodeSearch
        .getDocument()
        .addDocumentListener(
            new DocumentListener() {
              public void insertUpdate(DocumentEvent e) {
                refreshNavigator();
              }

              public void removeUpdate(DocumentEvent e) {
                refreshNavigator();
              }

              public void changedUpdate(DocumentEvent e) {
                refreshNavigator();
              }
            });
    JPanel search = new JPanel(new BorderLayout(6, 0));
    search.add(new JLabel("Find nodes"), BorderLayout.WEST);
    search.add(nodeSearch, BorderLayout.CENTER);
    search.add(nodeCount, BorderLayout.EAST);
    JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
    buttons.add(showNode);
    buttons.add(reset);
    JPanel choices = new JPanel(new BorderLayout(6, 0));
    choices.add(nodeList, BorderLayout.CENTER);
    choices.add(buttons, BorderLayout.EAST);
    JPanel result = new JPanel(new GridLayout(2, 1, 4, 4));
    result.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
    result.add(search);
    result.add(choices);
    return result;
  }

  private void revealChoice() {
    var choice = (GraphNavigation.Choice) nodeList.getSelectedItem();
    if (choice == null) return;
    var node = find(choice.id());
    if (node == null) return;
    select(node.id());
    canvas.reveal(node);
  }

  private void refreshNavigator() {
    var choices = GraphNavigation.choices(graph, nodeSearch.getText());
    syncingNavigator = true;
    try {
      nodeList.setModel(new DefaultComboBoxModel<>(choices.toArray(GraphNavigation.Choice[]::new)));
      syncNavigatorSelection();
      nodeCount.setText(choices.size() + " / " + graph.nodes.size() + " nodes");
    } finally {
      syncingNavigator = false;
    }
  }

  private void syncNavigatorSelection() {
    boolean previous = syncingNavigator;
    syncingNavigator = true;
    try {
      GraphNavigation.Choice choice = null;
      for (int i = 0; i < nodeList.getItemCount(); i++) {
        if (nodeList.getItemAt(i).id().equals(selected)) {
          choice = nodeList.getItemAt(i);
          break;
        }
      }
      nodeList.setSelectedItem(choice);
      showNode.setEnabled(choice != null);
    } finally {
      syncingNavigator = previous;
    }
  }

  private EffectGraph.Node find(String name) {
    return graph.nodes.stream().filter(n -> n.id().equals(name)).findFirst().orElse(null);
  }

  private void remember() {
    undo.push(new Draft(graph.copy(), selected));
    while (undo.size() > 32) undo.removeLast();
  }

  private void select(String name) {
    canvas.cancelDrag();
    EffectGraph.Node n = find(name);
    selected = n == null ? null : name;
    syncNavigatorSelection();
    connections.sync(graph, selected);
    groups.sync(graph, selected);
    if (n == null) {
      for (var field : List.of(id, inputs, formula, group, comment, template)) field.setText("");
      bypass.setSelected(false);
      canvas.repaint();
      return;
    }
    id.setText(n.id());
    kind.setSelectedItem(n.kind());
    inputs.setText(String.join(", ", n.inputs()));
    formula.setText(n.expression());
    group.setText(n.group());
    comment.setText(n.comment());
    template.setText(n.template());
    bypass.setSelected(n.bypass());
    canvas.repaint();
  }

  private void captureGroup(GroupCapturePanel.Request request) {
    try {
      var candidate =
          GraphTemplates.capture(
              graph, request.id(), request.version(), controls, request.inputs());
      remember();
      graph = candidate;
      output.setText(graph.output);
      groups.clearInputs();
      select(graph.output);
      refresh();
      tabs.setSelectedIndex(1);
      canvas.reveal(find(graph.output));
    } catch (IllegalArgumentException error) {
      showProblem(error, false, true);
    }
  }

  private void unpackGroup(String id) {
    try {
      var candidate = GraphTemplates.unpack(graph, id, controls);
      remember();
      graph = candidate;
      output.setText(graph.output);
      select(id);
      refresh();
      tabs.setSelectedIndex(1);
      canvas.reveal(find(id));
    } catch (IllegalArgumentException error) {
      showProblem(error, false, true);
    }
  }

  private void editConnections(EffectGraph candidate, int slot) {
    if (!GraphEdits.same(graph, candidate)) remember();
    graph = candidate;
    canvas.cancelDrag();
    var node = find(selected);
    inputs.setText(node == null ? "" : String.join(", ", node.inputs()));
    refresh();
    connections.selectInput(slot);
  }

  private void updateSelected() {
    EffectGraph.Node old = find(selected);
    if (old == null) return;
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
            comment.getText(),
            template.getText().trim(),
            old.seedKey());
    try {
      EffectGraph candidate = GraphEdits.update(graph, selected, n);
      if (!GraphEdits.same(graph, candidate)) remember();
      graph = candidate;
      output.setText(graph.output);
      select(n.id());
      refresh();
    } catch (IllegalArgumentException error) {
      showProblem(error, false, true);
      id.requestFocusInWindow();
      id.selectAll();
    }
  }

  private void applyGraph() {
    applyGraph(output.getText().trim());
  }

  private void applyGraph(String requestedOutput) {
    try {
      EffectGraph candidate = graph.copy();
      candidate.output = requestedOutput;
      var compiled = GraphCompiler.compile(candidate, controls);
      submit.accept(new Command("graph", candidate.copy()));
      if (!GraphEdits.same(graph, candidate)) remember();
      graph = candidate;
      canvas.cancelDrag();
      output.setText(graph.output);
      connections.sync(graph, selected);
      groups.sync(graph, selected);
      diagnosticNode = null;
      diagnostic.setText("Submitted " + compiled.order().size() + " reachable nodes");
      canvas.repaint();
    } catch (Exception ex) {
      showProblem(ex, true, true);
    }
  }

  private void showProblem(Exception error, boolean reveal, boolean graphProblem) {
    diagnostic.setText(error.getMessage());
    diagnostic.setCaretPosition(0);
    diagnosticNode = null;
    if (error instanceof AuthoringDiagnostic located) {
      if (graphProblem && !located.nodePath().isEmpty()) {
        String root = located.nodePath().getFirst();
        if (find(root) != null) {
          diagnosticNode = root;
          if (reveal) {
            tabs.setSelectedIndex(1);
            select(root);
            var node = find(root);
            if (located.nodePath().size() == 1
                && located.span() != null
                && (node.kind() == EffectGraph.Kind.FORMULA
                    || node.kind() == EffectGraph.Kind.EXPRESSION)
                && node.expression().equals(formula.getText())) {
              formula.requestFocusInWindow();
              formula.select(located.span().start(), located.span().end());
            }
            canvas.reveal(node);
          }
        }
      } else if (!graphProblem && reveal && located.span() != null) {
        tabs.setSelectedIndex(0);
        expression.requestFocusInWindow();
        expression.select(located.span().start(), located.span().end());
      }
    }
    canvas.repaint();
  }

  private void refresh() {
    refreshNavigator();
    groups.sync(graph, selected);
    connections.sync(graph, selected);
    try {
      var c = GraphCompiler.compile(graph, controls);
      diagnosticNode = null;
      diagnostic.setText("Valid scalar graph · " + c.order().size() + " active nodes");
    } catch (Exception e) {
      showProblem(e, false, true);
    }
    canvas.repaint();
  }

  public void sync(Settings s) {
    undo.clear();
    diagnosticNode = null;
    controls = java.util.List.copyOf(s.effectControls);
    expression.setText(s.expression);
    graph = s.graph == null ? new EffectGraph() : s.graph.copy();
    output.setText(graph.output);
    canvas.resetView();
    connections.resetSelection();
    groups.reset(graph, graph.output);
    nodeSearch.setText("");
    select(graph.output);
    refresh();
  }

  private final class Canvas extends JPanel {
    private final GraphNavigation.View view = new GraphNavigation.View();
    private Point anchor;
    private EffectGraph.Node dragged;
    private boolean dragRemembered;

    void cancelDrag() {
      dragged = null;
      anchor = null;
      dragRemembered = false;
    }

    void resetView() {
      cancelDrag();
      view.reset();
      canvasScroll.getViewport().setViewPosition(new Point());
      repaint();
    }

    void reveal(EffectGraph.Node node) {
      cancelDrag();
      Rectangle visible = getVisibleRect();
      if (visible.width <= 0 || visible.height <= 0) return;
      var projected = view.position(node);
      if (projected.isPresent()) {
        var p = projected.get();
        if (visible.contains(
            new Rectangle(p.x(), p.y(), GraphNavigation.NODE_WIDTH, GraphNavigation.NODE_HEIGHT)))
          return;
      }
      if (view.focus(node, visible.width, visible.height)) {
        canvasScroll.getViewport().setViewPosition(new Point());
        repaint();
      }
    }

    private boolean contains(EffectGraph.Node node, Point point) {
      return view.position(node)
          .map(
              p ->
                  new Rectangle(
                          p.x(), p.y(), GraphNavigation.NODE_WIDTH, GraphNavigation.NODE_HEIGHT)
                      .contains(point))
          .orElse(false);
    }

    private List<EffectGraph.Node> paintOrder() {
      var nodes = new ArrayList<>(graph.nodes);
      var node = find(selected);
      if (node != null) {
        nodes.remove(node);
        nodes.add(node);
      }
      return nodes;
    }

    Canvas() {
      setPreferredSize(new Dimension(1200, 900));
      setBackground(new Color(25, 29, 38));
      MouseAdapter mouse =
          new MouseAdapter() {
            public void mousePressed(MouseEvent e) {
              EffectGraph.Node hit =
                  paintOrder().reversed().stream()
                      .filter(n -> contains(n, e.getPoint()))
                      .findFirst()
                      .orElse(null);
              if (hit == null) return;
              if (e.isShiftDown() && selected != null && !selected.equals(hit.id())) {
                EffectGraph candidate = GraphEdits.appendInput(graph, hit.id(), selected);
                if (!GraphEdits.same(graph, candidate)) remember();
                graph = candidate;
                select(hit.id());
                refresh();
                connections.selectInput(hit.inputs().size());
                return;
              }
              select(hit.id());
              dragged = hit;
              anchor = e.getPoint();
            }

            public void mouseDragged(MouseEvent e) {
              if (dragged == null) return;
              EffectGraph.Node now = find(dragged.id());
              if (now == null) {
                cancelDrag();
                return;
              }
              var moved =
                  dragged.moved(
                      dragged.x() + (e.getX() - anchor.x), dragged.y() + (e.getY() - anchor.y));
              if (now.equals(moved)) return;
              if (!dragRemembered) {
                remember();
                dragRemembered = true;
              }
              graph.nodes.set(graph.nodes.indexOf(now), moved);
              repaint();
            }

            public void mouseReleased(MouseEvent e) {
              cancelDrag();
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
            var start = view.position(from);
            var end = view.position(n);
            if (start.isPresent() && end.isPresent()) {
              g.setColor(new Color(95, 140, 165));
              g.drawLine(
                  start.get().x() + GraphNavigation.NODE_WIDTH,
                  start.get().y() + 38,
                  end.get().x(),
                  end.get().y() + 38);
            }
          }
        }
      for (var n : paintOrder()) {
        var position = view.position(n);
        if (position.isEmpty()) continue;
        int x = position.get().x(), y = position.get().y();
        g.setColor(n.id().equals(selected) ? new Color(56, 104, 130) : new Color(48, 55, 70));
        g.fillRoundRect(x, y, 150, 76, 12, 12);
        if (n.id().equals(diagnosticNode)) {
          g.setColor(new Color(255, 140, 120));
          g.setStroke(new BasicStroke(2));
          g.drawRoundRect(x + 1, y + 1, 148, 74, 12, 12);
        }
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
