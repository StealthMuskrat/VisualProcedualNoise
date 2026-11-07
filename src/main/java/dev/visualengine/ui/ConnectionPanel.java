package dev.visualengine.ui;

import dev.visualengine.authoring.*;
import java.awt.*;
import java.util.List;
import java.util.function.*;
import javax.swing.*;

/** Ordered root-node input editing; accepted changes still belong to the authoring draft. */
final class ConnectionPanel extends JPanel {
  private final JComboBox<GraphNavigation.Choice> target = new JComboBox<>(),
      source = new JComboBox<>();
  private final JList<String> inputs = new JList<>();
  private final JButton append = new JButton("Append input"),
      replace = new JButton("Replace input"),
      remove = new JButton("Remove input"),
      up = new JButton("Move up"),
      down = new JButton("Move down"),
      show = new JButton("Show target");
  private final BiConsumer<EffectGraph, Integer> changed;
  private final Consumer<Exception> problem;
  private EffectGraph graph;
  private String selected;
  private boolean syncing;

  ConnectionPanel(
      Consumer<String> select,
      BiConsumer<EffectGraph, Integer> changed,
      Consumer<Exception> problem,
      Runnable reveal) {
    this.changed = changed;
    this.problem = problem;
    setName("authoring-connection-panel");
    setLayout(new BorderLayout(6, 8));
    setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
    target.setName("authoring-connection-target");
    source.setName("authoring-connection-source");
    inputs.setName("authoring-connection-inputs");
    inputs.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    inputs.setVisibleRowCount(8);
    target.setPrototypeDisplayValue(
        new GraphNavigation.Choice("Node name", EffectGraph.Kind.FORMULA));
    source.setPrototypeDisplayValue(
        new GraphNavigation.Choice("Node name", EffectGraph.Kind.FORMULA));
    target.addActionListener(
        e -> {
          if (!syncing) {
            var choice = (GraphNavigation.Choice) target.getSelectedItem();
            select.accept(choice == null ? null : choice.id());
          }
        });
    source.addActionListener(e -> updateButtons());
    inputs.addListSelectionListener(e -> updateButtons());
    JPanel names = new JPanel(new GridLayout(0, 2, 6, 6));
    names.add(new JLabel("Target node"));
    names.add(target);
    names.add(new JLabel("Source node"));
    names.add(source);
    JPanel header = new JPanel(new BorderLayout(0, 8));
    header.add(names, BorderLayout.NORTH);
    header.add(new JLabel("Ordered inputs · first entry is input0"), BorderLayout.SOUTH);
    add(header, BorderLayout.NORTH);
    add(new JScrollPane(inputs), BorderLayout.CENTER);
    append.setName("authoring-connection-append");
    replace.setName("authoring-connection-replace");
    remove.setName("authoring-connection-remove");
    up.setName("authoring-connection-up");
    down.setName("authoring-connection-down");
    show.setName("authoring-connection-show");
    append.addActionListener(
        e ->
            change(
                () -> GraphEdits.appendInput(graph, selected, sourceId()), node().inputs().size()));
    replace.addActionListener(
        e ->
            change(
                () ->
                    GraphEdits.replaceInput(graph, selected, inputs.getSelectedIndex(), sourceId()),
                inputs.getSelectedIndex()));
    remove.addActionListener(
        e ->
            change(
                () -> GraphEdits.removeInput(graph, selected, inputs.getSelectedIndex()),
                Math.min(inputs.getSelectedIndex(), inputs.getModel().getSize() - 2)));
    up.addActionListener(
        e ->
            change(
                () ->
                    GraphEdits.moveInput(
                        graph, selected, inputs.getSelectedIndex(), inputs.getSelectedIndex() - 1),
                inputs.getSelectedIndex() - 1));
    down.addActionListener(
        e ->
            change(
                () ->
                    GraphEdits.moveInput(
                        graph, selected, inputs.getSelectedIndex(), inputs.getSelectedIndex() + 1),
                inputs.getSelectedIndex() + 1));
    show.addActionListener(e -> reveal.run());
    JPanel buttons = new JPanel(new GridLayout(2, 3, 4, 4));
    for (var button : List.of(append, replace, remove, up, down, show)) buttons.add(button);
    JPanel footer = new JPanel(new BorderLayout(0, 8));
    footer.add(buttons, BorderLayout.CENTER);
    footer.add(new JLabel("Apply graph in Node graph to activate this draft."), BorderLayout.SOUTH);
    add(footer, BorderLayout.SOUTH);
    updateButtons();
  }

  private String sourceId() {
    var choice = (GraphNavigation.Choice) source.getSelectedItem();
    return choice == null ? null : choice.id();
  }

  private EffectGraph.Node node() {
    if (graph == null) return null;
    return graph.nodes.stream().filter(n -> n.id().equals(selected)).findFirst().orElse(null);
  }

  private void change(Supplier<EffectGraph> edit, int selection) {
    try {
      changed.accept(edit.get(), selection);
    } catch (IllegalArgumentException error) {
      problem.accept(error);
    }
  }

  void resetSelection() {
    source.setSelectedItem(null);
    inputs.clearSelection();
  }

  void sync(EffectGraph graph, String selected) {
    int previousSlot =
        java.util.Objects.equals(this.selected, selected) ? inputs.getSelectedIndex() : -1;
    String previousSource = sourceId();
    this.graph = graph;
    this.selected = selected;
    var choices = GraphNavigation.choices(graph, "").toArray(GraphNavigation.Choice[]::new);
    syncing = true;
    try {
      target.setModel(new DefaultComboBoxModel<>(choices));
      source.setModel(new DefaultComboBoxModel<>(choices));
      target.setSelectedItem(findChoice(choices, selected));
      source.setSelectedItem(findChoice(choices, previousSource));
      DefaultListModel<String> slots = new DefaultListModel<>();
      var node = node();
      if (node != null)
        for (int i = 0; i < node.inputs().size(); i++) {
          String name = node.inputs().get(i);
          var from = findChoice(choices, name);
          slots.addElement("input" + i + " ← " + (from == null ? name + " · missing node" : from));
        }
      inputs.setModel(slots);
      selectInput(previousSlot);
    } finally {
      syncing = false;
      updateButtons();
    }
  }

  private static GraphNavigation.Choice findChoice(GraphNavigation.Choice[] choices, String id) {
    for (var choice : choices) if (choice.id().equals(id)) return choice;
    return null;
  }

  void selectInput(int index) {
    inputs.setSelectedIndex(index >= 0 && index < inputs.getModel().getSize() ? index : -1);
    if (inputs.getSelectedIndex() >= 0) inputs.ensureIndexIsVisible(inputs.getSelectedIndex());
    updateButtons();
  }

  private void updateButtons() {
    boolean targetExists = node() != null, sourceExists = source.getSelectedItem() != null;
    int index = inputs.getSelectedIndex();
    append.setEnabled(targetExists && sourceExists);
    replace.setEnabled(targetExists && sourceExists && index >= 0);
    remove.setEnabled(targetExists && index >= 0);
    up.setEnabled(targetExists && index > 0);
    down.setEnabled(targetExists && index >= 0 && index < inputs.getModel().getSize() - 1);
    show.setEnabled(targetExists);
  }
}
