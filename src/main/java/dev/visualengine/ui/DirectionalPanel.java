package dev.visualengine.ui;

import dev.visualengine.app.Command;
import dev.visualengine.authoring.DirectionalEffects;
import java.awt.*;
import java.util.function.Consumer;
import javax.swing.*;

/** Explicit activation uses the ordinary reusable-effect import path. */
public final class DirectionalPanel extends JPanel {
  public DirectionalPanel(Consumer<Command> submit) {
    super(new BorderLayout(6, 4));
    JComboBox<DirectionalEffects.Style> style = new JComboBox<>(DirectionalEffects.Style.values());
    style.setName("directional-style");
    JButton load = new JButton("Load effect and reset");
    load.setName("directional-load");
    load.addActionListener(e ->
        submit.accept(
            new Command(
                "effectLoaded",
                DirectionalEffects.create((DirectionalEffects.Style) style.getSelectedItem()))));
    add(style, BorderLayout.CENTER);
    add(load, BorderLayout.EAST);
    add(
        new JLabel("Orientation, frequency and bandwidth: Effect controls tab"),
        BorderLayout.SOUTH);
  }
}
