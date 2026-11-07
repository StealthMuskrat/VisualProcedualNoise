package dev.visualengine.ui;

import dev.visualengine.authoring.*;
import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

final class GroupCapturePanel extends JPanel {
  record Request(String id, String version, List<GraphTemplates.Exposure> inputs) {
    Request {
      inputs = List.copyOf(inputs);
    }
  }

  private final JTextField id = new JTextField(),
      version = new JTextField(),
      name = new JTextField();
  private final JComboBox<GraphNavigation.Choice> source = new JComboBox<>();
  private final JComboBox<String> revisions = new JComboBox<>();
  private final DefaultListModel<GraphTemplates.Exposure> entries = new DefaultListModel<>();
  private final JList<GraphTemplates.Exposure> inputs = new JList<>(entries);
  private final JLabel count = new JLabel();
  private final JButton add = new JButton("Expose input"),
      remove = new JButton("Remove input"),
      up = new JButton("Move up"),
      down = new JButton("Move down"),
      capture = new JButton("Capture graph as group");
  private EffectGraph graph;

  GroupCapturePanel(Consumer<Request> submit, Consumer<Exception> problem) {
    setLayout(new BorderLayout(6, 8));
    setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
    id.setName("authoring-group-id");
    version.setName("authoring-group-version");
    name.setName("authoring-group-input-name");
    source.setName("authoring-group-source");
    inputs.setName("authoring-group-inputs");
    revisions.setName("authoring-group-revisions");
    source.setPrototypeDisplayValue(
        new GraphNavigation.Choice("Node name", EffectGraph.Kind.FORMULA));
    revisions.setPrototypeDisplayValue("local.group@1.0.0");
    inputs.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    inputs.setCellRenderer(
        new DefaultListCellRenderer() {
          public Component getListCellRendererComponent(
              JList<?> list, Object value, int index, boolean selected, boolean focus) {
            var label =
                (JLabel) super.getListCellRendererComponent(list, value, index, selected, focus);
            var input = (GraphTemplates.Exposure) value;
            var from =
                graph == null
                    ? null
                    : graph.nodes.stream()
                        .filter(n -> n.id().equals(input.nodeId()))
                        .findFirst()
                        .orElse(null);
            label.setText(input + " · " + (from == null ? "missing source" : from.kind()));
            return label;
          }
        });
    JPanel fields = new JPanel(new GridLayout(0, 2, 6, 6));
    fields.add(new JLabel("Group ID"));
    fields.add(id);
    fields.add(new JLabel("New revision"));
    fields.add(version);
    fields.add(new JLabel("Embedded revisions"));
    fields.add(revisions);
    fields.add(new JLabel("Source node"));
    fields.add(source);
    fields.add(new JLabel("Input name"));
    fields.add(name);
    JPanel heading = new JPanel(new BorderLayout(0, 8));
    heading.add(fields, BorderLayout.CENTER);
    heading.add(
        new JLabel("Expose sources in port order; types are inferred at capture."),
        BorderLayout.SOUTH);
    add(heading, BorderLayout.NORTH);
    add(new JScrollPane(inputs), BorderLayout.CENTER);
    add.setName("authoring-group-add-input");
    remove.setName("authoring-group-remove-input");
    up.setName("authoring-group-up");
    down.setName("authoring-group-down");
    capture.setName("authoring-group-capture");
    count.setName("authoring-group-input-count");
    add.addActionListener(
        e -> {
          var choice = (GraphNavigation.Choice) source.getSelectedItem();
          if (choice == null) return;
          var next = Collections.list(entries.elements());
          next.add(new GraphTemplates.Exposure(choice.id(), name.getText().trim()));
          try {
            GraphTemplates.validateExposures(next);
            entries.addElement(next.getLast());
            inputs.setSelectedIndex(entries.size() - 1);
            name.setText("");
            updateButtons();
          } catch (IllegalArgumentException error) {
            problem.accept(error);
          }
        });
    remove.addActionListener(
        e -> {
          int index = inputs.getSelectedIndex();
          if (index >= 0) {
            entries.remove(index);
            inputs.setSelectedIndex(Math.min(index, entries.size() - 1));
            updateButtons();
          }
        });
    up.addActionListener(e -> move(-1));
    down.addActionListener(e -> move(1));
    capture.addActionListener(
        e ->
            submit.accept(
                new Request(
                    id.getText().trim(),
                    version.getText().trim(),
                    Collections.list(entries.elements()))));
    source.addActionListener(e -> updateButtons());
    inputs.addListSelectionListener(e -> updateButtons());
    var changes =
        new DocumentListener() {
          public void insertUpdate(DocumentEvent e) {
            updateButtons();
          }

          public void removeUpdate(DocumentEvent e) {
            updateButtons();
          }

          public void changedUpdate(DocumentEvent e) {
            updateButtons();
          }
        };
    for (var field : List.of(id, version, name)) field.getDocument().addDocumentListener(changes);
    JPanel actions = new JPanel(new GridLayout(2, 2, 4, 4));
    for (var button : List.of(add, remove, up, down)) actions.add(button);
    JPanel bottom = new JPanel(new BorderLayout(0, 8));
    bottom.add(actions, BorderLayout.NORTH);
    JPanel save = new JPanel(new BorderLayout(0, 8));
    save.add(count, BorderLayout.NORTH);
    save.add(capture, BorderLayout.CENTER);
    save.add(
        new JLabel("Creates a pinned draft revision. Apply graph to activate."),
        BorderLayout.SOUTH);
    bottom.add(save, BorderLayout.SOUTH);
    add(bottom, BorderLayout.SOUTH);
    updateButtons();
  }

  private void move(int delta) {
    int from = inputs.getSelectedIndex(), to = from + delta;
    if (from < 0 || to < 0 || to >= entries.size()) return;
    var value = entries.remove(from);
    entries.add(to, value);
    inputs.setSelectedIndex(to);
    updateButtons();
  }

  void clearInputs() {
    entries.clear();
    name.setText("");
    source.setSelectedItem(null);
    updateButtons();
  }

  void reset(EffectGraph graph) {
    clearInputs();
    id.setText("local.group" + (graph.groups.size() + 1));
    version.setText("1.0.0");
    sync(graph);
  }

  void sync(EffectGraph graph) {
    var previous = (GraphNavigation.Choice) source.getSelectedItem();
    this.graph = graph;
    var choices = GraphNavigation.choices(graph, "").toArray(GraphNavigation.Choice[]::new);
    source.setModel(new DefaultComboBoxModel<>(choices));
    source.setSelectedItem(
        Arrays.stream(choices)
            .filter(c -> previous != null && c.id().equals(previous.id()))
            .findFirst()
            .orElse(null));
    revisions.setModel(
        new DefaultComboBoxModel<>(
            graph.groups.stream().map(GraphTemplates.Group::key).toArray(String[]::new)));
    inputs.repaint();
    updateButtons();
  }

  private void updateButtons() {
    int index = inputs.getSelectedIndex();
    add.setEnabled(
        source.getSelectedItem() != null && !name.getText().isBlank() && entries.size() < 8);
    remove.setEnabled(index >= 0);
    up.setEnabled(index > 0);
    down.setEnabled(index >= 0 && index < entries.size() - 1);
    capture.setEnabled(graph != null && !id.getText().isBlank() && !version.getText().isBlank());
    count.setText(entries.size() + " / 8 exposed inputs");
  }
}
