package dev.visualengine.ui;

import dev.visualengine.export.TextureMap;
import java.awt.*;
import java.util.function.Consumer;
import javax.swing.*;

/** Output contracts are visible before the user selects a destination. */
public final class TextureMapPanel extends JPanel {
  public record Choice(TextureMap.Kind kind, TextureMap.Range range) {
    public Choice {
      kind.validate(range);
    }
  }

  public TextureMapPanel(Consumer<Choice> export, Consumer<String> status) {
    setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
    setBorder(BorderFactory.createTitledBorder("Export texture map — 16-bit linear data"));
    JComboBox<TextureMap.Kind> kind = new JComboBox<>(TextureMap.Kind.values());
    kind.setName("map-kind");
    add(kind);
    JTextArea meaning = new JTextArea(3, 34);
    meaning.setEditable(false);
    meaning.setOpaque(false);
    meaning.setLineWrap(true);
    meaning.setWrapStyleWord(true);
    add(meaning);
    JPanel range = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
    JSpinner minimum = new JSpinner(new SpinnerNumberModel(-2., -1e6, 1e6, .1)),
        maximum = new JSpinner(new SpinnerNumberModel(2., -1e6, 1e6, .1));
    minimum.setName("map-minimum");
    maximum.setName("map-maximum");
    minimum.setEditor(new JSpinner.NumberEditor(minimum, "0.######"));
    maximum.setEditor(new JSpinner.NumberEditor(maximum, "0.######"));
    range.add(new JLabel("Black / min"));
    range.add(minimum);
    range.add(new JLabel("White / max"));
    range.add(maximum);
    add(range);
    Runnable update =
        () -> {
          var selected = (TextureMap.Kind) kind.getSelectedItem();
          minimum.setValue(selected.initialRange.minimum());
          maximum.setValue(selected.initialRange.maximum());
          minimum.setEnabled(selected.adjustable);
          maximum.setEnabled(selected.adjustable);
          meaning.setText(selected.meaning);
          meaning.setCaretPosition(0);
        };
    kind.addActionListener(e -> update.run());
    update.run();
    add(new JLabel("Uses the output width and height above at full resolution."));
    add(new JLabel("No debug view, A/B reference or display color encoding."));
    JButton save = new JButton("Export chosen texture map…");
    save.setName("map-export");
    save.addActionListener(e -> {
      try {
        export.accept(
            new Choice(
                (TextureMap.Kind) kind.getSelectedItem(),
                new TextureMap.Range(
                    ((Number) minimum.getValue()).doubleValue(),
                    ((Number) maximum.getValue()).doubleValue())));
      } catch (IllegalArgumentException error) {
        status.accept(error.getMessage());
      }
    });
    add(save);
    for (Component child : getComponents())
      if (child instanceof JComponent component) component.setAlignmentX(Component.LEFT_ALIGNMENT);
    meaning.setMaximumSize(new Dimension(Integer.MAX_VALUE, meaning.getPreferredSize().height));
    kind.setMaximumSize(new Dimension(Integer.MAX_VALUE, kind.getPreferredSize().height));
    range.setMaximumSize(new Dimension(Integer.MAX_VALUE, range.getPreferredSize().height));
    setMaximumSize(new Dimension(Integer.MAX_VALUE, getPreferredSize().height));
  }
}
