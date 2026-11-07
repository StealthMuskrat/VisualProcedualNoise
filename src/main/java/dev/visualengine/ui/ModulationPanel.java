package dev.visualengine.ui;

import dev.visualengine.app.Command;
import dev.visualengine.model.Settings;
import dev.visualengine.time.*;
import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.*;

/** Draft mappings are selected, edited and reordered before one explicit apply command. */
public final class ModulationPanel extends JPanel {
  private final List<Modulation> mappings = new ArrayList<>();
  private final DefaultListModel<String> model = new DefaultListModel<>();
  private final JList<String> list = new JList<>(model);
  private final JComboBox<ModulationTarget> target = new JComboBox<>();
  private final JComboBox<Modulation.Source> source = new JComboBox<>(Modulation.Source.values());
  private final JComboBox<Modulation.Mode> mode = new JComboBox<>(Modulation.Mode.values());
  private final JSpinner period = new JSpinner(new SpinnerNumberModel(8., .1, 3600., .5)),
      low = new JSpinner(new SpinnerNumberModel(0., -100., 100., .1)),
      high = new JSpinner(new SpinnerNumberModel(1., -100., 100., .1)),
      phase = new JSpinner(new SpinnerNumberModel(0., -3600., 3600., .1));
  private final JTextField keys = new JTextField("0:0, 0.3:1, 0.7:0.4, 1:1");
  private final JLabel range = new JLabel(),
      status = new JLabel("Edit drafts, then apply. Mappings run in list order.");
  private Settings context;
  private boolean loading;

  public ModulationPanel(Consumer<Command> submit, Settings settings) {
    setLayout(new BorderLayout(4, 4));
    target.setName("modulation-target");
    source.setName("modulation-source");
    mode.setName("modulation-mode");
    period.setName("modulation-period");
    low.setName("modulation-low");
    high.setName("modulation-high");
    phase.setName("modulation-phase");
    keys.setName("modulation-keys");
    list.setName("modulation-list");
    status.setName("modulation-status");
    range.setName("modulation-range");
    var plain =
        new DefaultListCellRenderer() {
          @Override
          public Component getListCellRendererComponent(
              JList<?> list, Object value, int index, boolean selected, boolean focus) {
            var component =
                (JLabel) super.getListCellRendererComponent(list, value, index, selected, focus);
            component.putClientProperty("html.disable", Boolean.TRUE);
            return component;
          }
        };
    target.setRenderer(plain);
    list.setCellRenderer(plain);
    range.putClientProperty("html.disable", Boolean.TRUE);
    status.putClientProperty("html.disable", Boolean.TRUE);
    list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    add(new JScrollPane(list), BorderLayout.CENTER);
    JPanel fields = new JPanel(new GridLayout(0, 2, 4, 4));
    for (var pair :
        List.of(
            new Object[] {"Destination", target},
            new Object[] {"Source", source},
            new Object[] {"Combination", mode},
            new Object[] {"Period / duration (s)", period},
            new Object[] {"Low", low},
            new Object[] {"High", high},
            new Object[] {"Time offset (s)", phase},
            new Object[] {"Keyframes time:value (0..1)", keys})) {
      fields.add(new JLabel((String) pair[0]));
      fields.add((Component) pair[1]);
    }
    JButton add = button("Add mapping", "modulation-add"),
        update = button("Update selected", "modulation-update"),
        remove = button("Remove selected", "modulation-remove"),
        up = button("Move up", "modulation-up"),
        down = button("Move down", "modulation-down"),
        apply = button("Apply mappings", "modulation-apply");
    add.addActionListener(
        e -> {
          try {
            if (mappings.size() >= 32)
              throw new IllegalArgumentException("At most 32 mappings are allowed");
            Modulation value = read("mapping" + UUID.randomUUID());
            mappings.add(value);
            refresh(mappings.size() - 1);
            status.setText("Added to draft; apply to change the scene");
          } catch (Exception ex) {
            status.setText(ex.getMessage());
          }
        });
    update.addActionListener(
        e -> {
          try {
            int selected = list.getSelectedIndex();
            if (selected < 0) throw new IllegalArgumentException("Select a mapping to update");
            Modulation value = read(mappings.get(selected).id());
            mappings.set(selected, value);
            refresh(selected);
            status.setText("Updated draft; apply to change the scene");
          } catch (Exception ex) {
            status.setText(ex.getMessage());
          }
        });
    remove.addActionListener(
        e -> {
          int selected = list.getSelectedIndex();
          if (selected >= 0) {
            mappings.remove(selected);
            refresh(Math.min(selected, mappings.size() - 1));
          }
        });
    up.addActionListener(e -> move(-1));
    down.addActionListener(e -> move(1));
    apply.addActionListener(
        e -> {
          try {
            Settings candidate = context.copy();
            candidate.modulations = mappings.stream().map(Modulation::copy).toList();
            candidate.validate();
            submit.accept(new Command("modulations", candidate.modulations));
            status.setText("Mappings submitted");
          } catch (Exception ex) {
            status.setText(ex.getMessage());
          }
        });
    for (JButton button : List.of(add, update, remove, up, down, apply)) fields.add(button);
    JPanel bottom = new JPanel(new BorderLayout(4, 4));
    bottom.add(range, BorderLayout.NORTH);
    bottom.add(fields, BorderLayout.CENTER);
    bottom.add(status, BorderLayout.SOUTH);
    add(bottom, BorderLayout.SOUTH);
    target.addActionListener(
        e -> {
          if (!loading) targetRange(true);
        });
    list.addListSelectionListener(
        e -> {
          if (!e.getValueIsAdjusting() && !loading) loadSelected();
        });
    sync(settings);
  }

  private static JButton button(String label, String name) {
    JButton b = new JButton(label);
    b.setName(name);
    return b;
  }

  private Modulation read(String id) {
    var destination = (ModulationTarget) target.getSelectedItem();
    if (destination == null) throw new IllegalArgumentException("Choose a modulation destination");
    String text = keys.getText().trim();
    if (text.length() > 4096) throw new IllegalArgumentException("Keyframe text is too long");
    String[] pairs = text.split("[,\\s]+");
    if (pairs.length < 2 || pairs.length > 32)
      throw new IllegalArgumentException("Use 2..32 time:value pairs");
    double[] frames = new double[pairs.length * 2];
    for (int i = 0; i < pairs.length; i++) {
      String[] pair = pairs[i].split(":", -1);
      if (pair.length != 2) throw new IllegalArgumentException("Keyframes need time:value pairs");
      frames[i * 2] = Double.parseDouble(pair[0]);
      frames[i * 2 + 1] = Double.parseDouble(pair[1]);
    }
    Modulation mapping =
        new Modulation(
            id,
            destination.key(),
            (Modulation.Source) source.getSelectedItem(),
            (Modulation.Mode) mode.getSelectedItem(),
            ((Number) period.getValue()).doubleValue(),
            ((Number) low.getValue()).doubleValue(),
            ((Number) high.getValue()).doubleValue(),
            ((Number) phase.getValue()).doubleValue(),
            frames);
    mapping.validate(context.effectControls);
    return mapping;
  }

  private void targetRange(boolean defaults) {
    var destination = (ModulationTarget) target.getSelectedItem();
    if (destination == null) return;
    double limit = destination.effect() ? 1e6 : 100;
    double lo = defaults ? destination.minimum() : ((Number) low.getValue()).doubleValue(),
        hi = defaults ? destination.maximum() : ((Number) high.getValue()).doubleValue();
    low.setModel(new SpinnerNumberModel(Math.clamp(lo, -limit, limit), -limit, limit, .1));
    high.setModel(new SpinnerNumberModel(Math.clamp(hi, -limit, limit), -limit, limit, .1));
    range.setText(
        String.format(
            Locale.ROOT,
            "Range %.4g to %.4g %s · %s",
            destination.minimum(),
            destination.maximum(),
            destination.units(),
            destination.smoothed() ? "smoothed after mapping" : "uniform update"));
  }

  private void loadSelected() {
    int selected = list.getSelectedIndex();
    if (selected < 0) return;
    Modulation mapping = mappings.get(selected);
    loading = true;
    try {
      selectTarget(mapping.destination());
      targetRange(false);
      source.setSelectedItem(mapping.source());
      mode.setSelectedItem(mapping.mode());
      period.setValue(mapping.period());
      low.setValue(mapping.low());
      high.setValue(mapping.high());
      phase.setValue(mapping.phase());
      double[] frames = mapping.keys();
      List<String> pairs = new ArrayList<>();
      for (int i = 0; i < frames.length; i += 2) pairs.add(frames[i] + ":" + frames[i + 1]);
      keys.setText(String.join(", ", pairs));
      keys.setCaretPosition(0);
    } finally {
      loading = false;
    }
  }

  private void selectTarget(String key) {
    for (int i = 0; i < target.getItemCount(); i++)
      if (target.getItemAt(i).key().equals(key)) {
        target.setSelectedIndex(i);
        return;
      }
  }

  private void move(int delta) {
    int selected = list.getSelectedIndex(), next = selected + delta;
    if (selected >= 0 && next >= 0 && next < mappings.size()) {
      Collections.swap(mappings, selected, next);
      refresh(next);
    }
  }

  private void refresh(int selected) {
    loading = true;
    try {
      model.clear();
      for (var mapping : mappings) {
        var destination = ModulationTarget.resolve(context.effectControls, mapping.destination());
        model.addElement(
            mapping.source()
                + " → "
                + destination.label()
                + " · "
                + mapping.mode()
                + " ["
                + mapping.low()
                + ", "
                + mapping.high()
                + "] · offset "
                + mapping.phase()
                + " s");
      }
      list.setSelectedIndex(selected);
    } finally {
      loading = false;
    }
    loadSelected();
  }

  public void sync(Settings settings) {
    String selected = target.getSelectedItem() instanceof ModulationTarget t ? t.key() : "scale";
    context = settings.copy();
    mappings.clear();
    settings.modulations.forEach(m -> mappings.add(m.copy()));
    loading = true;
    try {
      target.removeAllItems();
      ModulationTarget.available(settings).forEach(target::addItem);
      selectTarget(selected);
      targetRange(false);
    } finally {
      loading = false;
    }
    refresh(mappings.isEmpty() ? -1 : 0);
    status.setText("Mappings loaded; edit drafts, then apply.");
  }
}
