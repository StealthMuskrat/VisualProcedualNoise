package dev.visualengine.ui;

import dev.visualengine.export.*;
import dev.visualengine.model.Settings;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.function.Consumer;
import javax.swing.*;

public final class InspectionPanel extends JPanel {
  private final JSpinner width = new JSpinner(new SpinnerNumberModel(512, 32, 4096, 32));
  private final JSpinner height = new JSpinner(new SpinnerNumberModel(512, 32, 4096, 32));
  private final JLabel current = new JLabel();
  private final JTextArea result = new JTextArea(13, 38);
  private final JLabel preview = new JLabel();

  public InspectionPanel(Consumer<CaptureSize> inspect, Consumer<String> error, Settings settings) {
    setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
    setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
    add(new JLabel("Rendered seam and loop measurements"));
    add(Box.createVerticalStrut(8));
    add(current);
    JPanel dimensions = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 8));
    width.setName("inspection-width");
    height.setName("inspection-height");
    dimensions.add(width);
    dimensions.add(new JLabel(" × "));
    dimensions.add(height);
    dimensions.setAlignmentX(Component.LEFT_ALIGNMENT);
    dimensions.setMaximumSize(
        new Dimension(Integer.MAX_VALUE, dimensions.getPreferredSize().height));
    add(dimensions);
    JButton run = new JButton("Inspect and save JSON report…");
    run.setName("inspection-run");
    run.addActionListener(e -> {
      try {
        width.commitEdit();
        height.commitEdit();
        var size =
            Periodicity.requireSize(
                new CaptureSize(
                    ((Number) width.getValue()).intValue(),
                    ((Number) height.getValue()).intValue()));
        inspect.accept(size);
      } catch (Exception ex) {
        error.accept(ex.getMessage());
      }
    });
    add(run);
    add(Box.createVerticalStrut(8));
    JTextArea help =
        new JTextArea(
            "Full-resolution creative output; diagnostics, A/B and wrap-preview overlays are"
                + " excluded. Loop endpoints hold resolved controls and pointer fixed. No state or"
                + " time is advanced.",
            3,
            38);
    help.setEditable(false);
    help.setLineWrap(true);
    help.setWrapStyleWord(true);
    help.setOpaque(false);
    help.setFont(UIManager.getFont("Label.font"));
    help.setMaximumSize(new Dimension(Integer.MAX_VALUE, help.getPreferredSize().height));
    help.setAlignmentX(Component.LEFT_ALIGNMENT);
    add(help);
    add(Box.createVerticalStrut(8));
    result.setName("inspection-result");
    result.setEditable(false);
    result.setLineWrap(true);
    result.setWrapStyleWord(true);
    result.setFont(UIManager.getFont("Label.font"));
    result.setText(
        "No measurement yet. Compare wrap differences with nearby interior steps. The report"
            + " records measurements without certifying seamlessness.");
    JScrollPane scroll = new JScrollPane(result);
    scroll.setAlignmentX(Component.LEFT_ALIGNMENT);
    scroll.setMaximumSize(new Dimension(Integer.MAX_VALUE, 230));
    add(scroll);
    add(Box.createVerticalStrut(8));
    add(new JLabel("Last measured image repeated 2×2 (nearest-neighbor preview)"));
    preview.setName("inspection-preview");
    preview.setAlignmentX(Component.LEFT_ALIGNMENT);
    add(preview);
    add(Box.createVerticalGlue());
    sync(settings);
  }

  public void sync(Settings settings) {
    current.setText(
        "Live: tile mapping "
            + (settings.tileMode ? "on" : "off")
            + " · analytic period "
            + settings.loopSeconds
            + " s");
  }

  public void showReport(Periodicity.Report report, BufferedImage image, long tick) {
    result.setText("Last measured tick: " + tick + "\n" + report.summary());
    result.setCaretPosition(0);
    preview.setIcon(new ImageIcon(image));
  }
}
