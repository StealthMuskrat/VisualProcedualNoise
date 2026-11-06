package dev.visualengine.ui;

import dev.visualengine.app.Command;
import dev.visualengine.model.PresetMorph;
import java.awt.*;
import java.util.Hashtable;
import java.util.Locale;
import java.util.function.Consumer;
import javax.swing.*;

/** Endpoint selection is read-only until the user explicitly applies a blend. */
public final class MorphPanel extends JPanel {
  private final JTextArea a = text(3), b = text(3), compatibility = text(7);
  private final JSlider amount = new JSlider(0, 1000, 500);
  private final JButton apply = new JButton("Apply blend");

  public MorphPanel(Consumer<Command> submit, Consumer<PresetMorph.Slot> load) {
    setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
    setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
    add(new JLabel("Blend compatible preset controls"));
    add(Box.createVerticalStrut(12));
    endpoint(PresetMorph.Slot.A, a, submit, load);
    endpoint(PresetMorph.Slot.B, b, submit, load);
    JLabel percent = new JLabel();
    amount.setName("morph-amount");
    amount.setAlignmentX(Component.LEFT_ALIGNMENT);
    amount.setMajorTickSpacing(250);
    amount.setPaintTicks(true);
    Hashtable<Integer, JLabel> labels = new Hashtable<>();
    labels.put(0, new JLabel("A"));
    labels.put(500, new JLabel("50%"));
    labels.put(1000, new JLabel("B"));
    amount.setLabelTable(labels);
    amount.setPaintLabels(true);
    Runnable update =
        () ->
            percent.setText(
                String.format(
                    Locale.ROOT,
                    "%.1f%% A / %.1f%% B",
                    100 - amount.getValue() / 10.,
                    amount.getValue() / 10.));
    amount.addChangeListener(e -> update.run());
    update.run();
    add(percent);
    add(amount);
    apply.setName("morph-apply");
    apply.addActionListener(
        e -> submit.accept(new Command("morphBlend", amount.getValue() / 1000.)));
    add(apply);
    add(Box.createVerticalStrut(12));
    add(scroll(compatibility));
    add(
        text(
            "Choose an amount, then apply. State and animation phase continue. Play mode uses"
                + " normal control smoothing; pause applies values immediately."));
    add(Box.createVerticalStrut(8));
    add(
        text(
            "Applying stops replay playback. Recording stores the resolved controls. Save the"
                + " result as an ordinary preset or checkpoint."));
    add(Box.createVerticalGlue());
    showView(
        new PresetMorph.View(
            "Empty", "Empty", java.util.List.of("Capture or load endpoints A and B.")));
  }

  private void endpoint(
      PresetMorph.Slot slot,
      JTextArea label,
      Consumer<Command> submit,
      Consumer<PresetMorph.Slot> load) {
    add(new JLabel("Endpoint " + slot));
    label.setName("morph-" + slot + "-label");
    add(scroll(label));
    JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 4));
    JButton capture = new JButton("Capture current");
    capture.setName("morph-" + slot + "-capture");
    capture.addActionListener(e -> submit.accept(new Command("morphCapture", slot)));
    JButton open = new JButton("Load preset…");
    open.setName("morph-" + slot + "-load");
    open.addActionListener(e -> load.accept(slot));
    actions.add(capture);
    actions.add(Box.createHorizontalStrut(8));
    actions.add(open);
    actions.setAlignmentX(Component.LEFT_ALIGNMENT);
    actions.setMaximumSize(new Dimension(Integer.MAX_VALUE, actions.getPreferredSize().height));
    add(actions);
    add(Box.createVerticalStrut(10));
  }

  private static JTextArea text(int rows) {
    JTextArea result = new JTextArea(rows, 30);
    result.setEditable(false);
    result.setLineWrap(true);
    result.setWrapStyleWord(true);
    result.setOpaque(false);
    result.setFont(UIManager.getFont("Label.font"));
    result.setAlignmentX(Component.LEFT_ALIGNMENT);
    result.setMaximumSize(new Dimension(Integer.MAX_VALUE, result.getPreferredSize().height));
    return result;
  }

  private static JScrollPane scroll(JTextArea text) {
    JScrollPane result = new JScrollPane(text);
    result.setBorder(BorderFactory.createEmptyBorder());
    result.setAlignmentX(Component.LEFT_ALIGNMENT);
    result.setMaximumSize(new Dimension(Integer.MAX_VALUE, text.getPreferredSize().height + 2));
    return result;
  }

  private static JTextArea text(String value) {
    JTextArea result = text(3);
    result.setText(value);
    return result;
  }

  public void showView(PresetMorph.View view) {
    a.setText(view.a());
    b.setText(view.b());
    compatibility.setText(
        view.ready()
            ? "Compatible with the live scene. Continuous controls can be blended."
            : String.join("\n", view.reasons()));
    compatibility.setCaretPosition(0);
    amount.setEnabled(view.ready());
    apply.setEnabled(view.ready());
  }
}
