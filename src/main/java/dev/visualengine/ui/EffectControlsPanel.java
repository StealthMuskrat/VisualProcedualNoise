package dev.visualengine.ui;

import dev.visualengine.app.Command;
import dev.visualengine.authoring.*;
import dev.visualengine.model.Settings;
import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;

/** Schema editing and generated controls share the same validated descriptors. */
public final class EffectControlsPanel extends JPanel {
  private final Consumer<Command> submit;
  private final DefaultTableModel schema =
      new DefaultTableModel(
          new String[] {
            "ID",
            "Label",
            "Type",
            "Units",
            "Default components",
            "Min",
            "Max",
            "Step",
            "Scale",
            "Update"
          },
          0);
  private final JPanel values = new JPanel();
  private final JLabel status = new JLabel("Define controls here, then use their IDs in formulas.");
  private boolean syncing;

  public EffectControlsPanel(Consumer<Command> submit, Settings settings) {
    this.submit = submit;
    setLayout(new BorderLayout(4, 4));
    JTable table = new JTable(schema);
    table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
    var plain = new javax.swing.table.DefaultTableCellRenderer();
    plain.putClientProperty("html.disable", Boolean.TRUE);
    table.setDefaultRenderer(Object.class, plain);
    JPanel editing = new JPanel(new BorderLayout());
    editing.add(new JScrollPane(table));
    JPanel buttons = new JPanel();
    JButton add = new JButton("Add control"),
        remove = new JButton("Remove selected"),
        apply = new JButton("Apply schema");
    add.addActionListener(e ->
        schema.addRow(
            new Object[] {
              "custom" + (schema.getRowCount() + 1),
              "Custom control",
              "FLOAT",
              "",
              "1",
              0,
              10,
              .01,
              "LINEAR",
              "UNIFORM"
            }));
    remove.addActionListener(e -> {
      if (table.getSelectedRow() >= 0) schema.removeRow(table.getSelectedRow());
    });
    apply.addActionListener(e -> {
      try {
        if (table.isEditing()) table.getCellEditor().stopCellEditing();
        List<EffectControl> controls = new ArrayList<>();
        for (int row = 0; row < schema.getRowCount(); row++)
          controls.add(
              new EffectControl(
                  text(row, 0),
                  text(row, 1),
                  Formula.Type.valueOf(text(row, 2)),
                  text(row, 3),
                  Arrays.stream(text(row, 4).split("[,\\s]+")).map(Double::parseDouble).toList(),
                  Double.parseDouble(text(row, 5)),
                  Double.parseDouble(text(row, 6)),
                  Double.parseDouble(text(row, 7)),
                  EffectControl.Scale.valueOf(text(row, 8)),
                  EffectControl.Update.valueOf(text(row, 9))));
        EffectControl.validateSchema(controls);
        submit.accept(new Command("effectControls", List.copyOf(controls)));
        status.setText("Schema submitted");
      } catch (Exception ex) {
        status.setText(ex.getMessage());
      }
    });
    buttons.add(add);
    buttons.add(remove);
    buttons.add(apply);
    editing.add(buttons, BorderLayout.SOUTH);
    values.setLayout(new BoxLayout(values, BoxLayout.Y_AXIS));
    JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, editing, new JScrollPane(values));
    split.setResizeWeight(.45);
    add(split);
    add(status, BorderLayout.SOUTH);
    sync(settings);
  }

  private String text(int row, int col) {
    Object value = schema.getValueAt(row, col);
    return value == null ? "" : value.toString().trim();
  }

  public void sync(Settings settings) {
    syncing = true;
    try {
      schema.setRowCount(0);
      values.removeAll();
      for (var c : settings.effectControls) {
        schema.addRow(
            new Object[] {
              c.id(),
              c.label(),
              c.type(),
              c.units(),
              String.join(",", c.initial().stream().map(Object::toString).toList()),
              c.minimum(),
              c.maximum(),
              c.step(),
              c.scale(),
              c.update()
            });
        List<Double> current = new ArrayList<>(settings.effectValues.get(c.id()));
        JLabel caption =
            new JLabel(c.label() + (c.units().isEmpty() ? "" : " (" + c.units() + ")"));
        caption.putClientProperty("html.disable", Boolean.TRUE);
        values.add(caption);
        for (int component = 0; component < c.type().width; component++) {
          final int index = component;
          JPanel row = new JPanel(new BorderLayout());
          row.add(
              new JLabel(c.type().width == 1 ? c.id() : c.id() + "." + "xyzw".charAt(index)),
              BorderLayout.WEST);
          JSlider slider = new JSlider(0, 1000);
          double initial = current.get(index);
          slider.setValue(
              (int)
                  Math.round(
                      1000
                          * (c.scale() == EffectControl.Scale.LINEAR
                              ? (initial - c.minimum()) / (c.maximum() - c.minimum())
                              : Math.log(initial / c.minimum())
                                  / Math.log(c.maximum() / c.minimum()))));
          JLabel number = new JLabel(String.format(Locale.ROOT, "%.4g", initial));
          slider.addChangeListener(e -> {
            if (syncing) return;
            double t = slider.getValue() / 1000.;
            double value =
                c.scale() == EffectControl.Scale.LINEAR
                    ? c.minimum() + t * (c.maximum() - c.minimum())
                    : c.minimum() * Math.pow(c.maximum() / c.minimum(), t);
            value =
                Math.clamp(
                    c.minimum() + Math.rint((value - c.minimum()) / c.step()) * c.step(),
                    c.minimum(),
                    c.maximum());
            current.set(index, value);
            number.setText(String.format(Locale.ROOT, "%.4g", value));
            submit.accept(new Command("effectValue", new Command.EffectEdit(c.id(), current)));
          });
          row.add(slider);
          row.add(number, BorderLayout.EAST);
          values.add(row);
        }
      }
      values.revalidate();
      values.repaint();
    } finally {
      syncing = false;
    }
  }
}
