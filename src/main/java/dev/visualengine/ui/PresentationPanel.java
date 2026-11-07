package dev.visualengine.ui;

import dev.visualengine.app.Command;
import dev.visualengine.platform.Presentation;
import java.awt.*;
import java.util.function.Consumer;
import javax.swing.*;

public final class PresentationPanel extends JPanel {
  private final JCheckBox vsync = new JCheckBox("Request VSync");
  private final JSpinner limit = new JSpinner(new SpinnerNumberModel(0, 0, 240, 1));
  private final JCheckBox suspend = new JCheckBox("Suspend while minimized");
  private final JButton apply = new JButton("Apply and save presentation");
  private final JTextArea state = text(4);

  public PresentationPanel(Consumer<Command> submit, Consumer<String> error, Presentation initial) {
    setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
    setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
    add(new JLabel("Desktop presentation"));
    add(Box.createVerticalStrut(12));
    vsync.setName("presentation-vsync");
    limit.setName("presentation-limit");
    suspend.setName("presentation-suspend");
    apply.setName("presentation-apply");
    state.setName("presentation-state");
    add(vsync);
    add(new JLabel("Maximum FPS (0 = uncapped, up to 240)"));
    limit.setAlignmentX(Component.LEFT_ALIGNMENT);
    limit.setMaximumSize(new Dimension(140, limit.getPreferredSize().height));
    add(limit);
    add(suspend);
    add(Box.createVerticalStrut(10));
    add(apply);
    add(Box.createVerticalStrut(12));
    add(state);
    apply.addActionListener(e -> {
      try {
        limit.commitEdit();
        submit.accept(
            new Command(
                "presentation",
                new Presentation(
                    vsync.isSelected(),
                    ((Number) limit.getValue()).intValue(),
                    suspend.isSelected())));
      } catch (Exception ex) {
        error.accept(ex.getMessage());
      }
    });
    JTextArea help = text(24);
    help.setText(
        "These preferences belong to this computer. Presets, checkpoints and replay retain their"
            + " creative settings. Applying presentation preserves simulation state and animation"
            + " phase.\n\n"
            + "Paused playback remains event-driven. Offline export and benchmark suites stay"
            + " unsynchronized and uncapped.\n\n"
            + "Minimized suspension holds simulation ticks and waits for restore. With suspension"
            + " off, positive-sized framebuffers keep running. Zero-sized framebuffers always wait."
            + " Captures and steps wait while suspended.\n\n"
            + "F11 uses the current monitor and restores the prior window bounds. After focus loss"
            + " or a display transition, release the mouse before painting again.\n\n"
            + "VSync is an application request; driver overrides and timer granularity can affect"
            + " the observed rate.\n\n"
            + "Low frame caps or high playback speed can reach the eight-tick catch-up budget and"
            + " slow interactive simulation relative to wall time.");
    add(help);
    add(Box.createVerticalGlue());
    showApplied(initial, "Ready", false);
  }

  private static JTextArea text(int rows) {
    JTextArea result = new JTextArea(rows, 38);
    result.setEditable(false);
    result.setLineWrap(true);
    result.setWrapStyleWord(true);
    result.setOpaque(false);
    result.setFont(UIManager.getFont("Label.font"));
    result.setAlignmentX(Component.LEFT_ALIGNMENT);
    result.setMaximumSize(new Dimension(Integer.MAX_VALUE, result.getPreferredSize().height));
    return result;
  }

  public void showApplied(Presentation presentation, String message, boolean busy) {
    vsync.setSelected(presentation.vsync());
    limit.setValue(presentation.frameLimit());
    suspend.setSelected(presentation.suspendWhenMinimized());
    vsync.setEnabled(!busy);
    limit.setEnabled(!busy);
    suspend.setEnabled(!busy);
    apply.setEnabled(!busy);
    state.setText(
        "Requested: VSync "
            + (presentation.vsync() ? "on" : "off")
            + " · maximum "
            + (presentation.frameLimit() == 0 ? "uncapped" : presentation.frameLimit() + " FPS")
            + "\nMinimized: "
            + (presentation.suspendWhenMinimized() ? "suspend" : "continue when drawable")
            + "\n"
            + message);
  }
}
