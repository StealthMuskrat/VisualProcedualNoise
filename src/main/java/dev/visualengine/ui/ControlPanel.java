package dev.visualengine.ui;

import dev.visualengine.app.Command;
import dev.visualengine.model.QualityProfile;
import dev.visualengine.model.Settings;
import java.awt.*;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;
import javax.swing.*;

/** A separate native inspector leaves the entire GLFW viewport available to artwork and input. */
public final class ControlPanel implements AutoCloseable {
  private JFrame frame;
  private final Consumer<Command> submit;
  private final Path data;
  private final JLabel status = new JLabel("Ready");
  private final Map<String, JSlider> sliders = new HashMap<>();
  private final Map<String, JCheckBox> flags = new HashMap<>();
  private final Map<String, JCheckBox> locks = new HashMap<>();
  private final Map<String, JSpinner> numeric = new HashMap<>();
  private JTextField seedField;
  private final JLabel budget = new JLabel();
  private final JLabel qualityCurrent = new JLabel();
  private JComboBox<QualityProfile> qualityProfile;
  private JComboBox<Settings.Recipe> recipes;
  private JComboBox<String> palette, debug;
  private AuthoringPanel authoring;
  private EffectControlsPanel effectControls;
  private ModulationPanel modulation;
  private DiscoveryPanel discoveries;
  private MorphPanel morph;
  private boolean syncing;

  public ControlPanel(Consumer<Command> submit, Settings initial, Path data) {
    this.submit = submit;
    this.data = data;
    try {
      SwingUtilities.invokeAndWait(() -> build(initial));
    } catch (Exception e) {
      throw new IllegalStateException("Could not open controls", e);
    }
  }

  private JPanel column() {
    JPanel p = new JPanel();
    p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
    p.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
    return p;
  }

  private void button(JPanel p, String label, String type) {
    JButton b = new JButton(label);
    b.addActionListener(e -> submit.accept(new Command(type, null)));
    p.add(b);
  }

  private void build(Settings s) {
    frame = new JFrame("Visual Engine Controls");
    frame.setDefaultCloseOperation(WindowConstants.HIDE_ON_CLOSE);
    JPanel scene = column();
    recipes = new JComboBox<>(Settings.Recipe.values());
    recipes.addActionListener(e -> {
      if (!syncing) submit.accept(new Command("recipe", recipes.getSelectedItem()));
    });
    scene.add(recipes);
    palette = new JComboBox<>(new String[] {"Opal", "Botanical", "Ember", "Glacier"});
    palette.addActionListener(e -> {
      if (!syncing) submit.accept(new Command("palette", palette.getSelectedIndex()));
    });
    scene.add(palette);
    JTextField seed = new JTextField(Long.toString(s.seed), 20);
    seedField = seed;
    JButton applySeed = new JButton("Apply seed and reset");
    applySeed.addActionListener(e -> {
      try {
        submit.accept(new Command("seed", Long.parseLong(seed.getText().trim())));
      } catch (NumberFormatException ex) {
        showStatus("Seed must be a signed 64-bit integer");
      }
    });
    scene.add(seed);
    scene.add(applySeed);
    for (String[] b :
        new String[][] {
          {"Play / pause", "pause"},
          {"Single tick", "step"},
          {"Reset simulation", "reset"},
          {"Undo controls", "undo"},
          {"Randomize seed", "randomSeed"},
          {"Randomize palette", "randomPalette"},
          {"Randomize motion", "randomMotion"},
          {"Randomize composition", "randomComposition"},
          {"Reload engine resources", "reload"}
        }) button(scene, b[0], b[1]);
    for (String[] f :
        new String[][] {
          {"Feedback", "feedbackEnabled"},
          {"Pigment transport", "pigmentEnabled"},
          {"Reaction diffusion", "reactionEnabled"},
          {"Particles and trails", "particlesEnabled"}
        }) {
      JCheckBox box = new JCheckBox(f[0]);
      box.addActionListener(e -> {
        if (!syncing) submit.accept(new Command(f[1], box.isSelected()));
      });
      flags.put(f[1], box);
      scene.add(box);
    }
    JPanel params = column();
    String group = "";
    for (var p : Settings.PARAMETERS) {
      if (!p.group().equals(group)) {
        group = p.group();
        params.add(new JLabel(group));
      }
      JLabel label = new JLabel(p.label());
      params.add(label);
      JSlider slider = new JSlider(0, 1000);
      slider.addChangeListener(e -> {
        double value = p.minimum() + (p.maximum() - p.minimum()) * slider.getValue() / 1000.;
        label.setText(p.label() + "  " + String.format(Locale.ROOT, "%.3f", value));
        if (!syncing) submit.accept(new Command("parameter", new Command.Edit(p.id(), value)));
      });
      sliders.put(p.id(), slider);
      params.add(slider);
      JCheckBox lock = new JCheckBox("Lock for randomization");
      lock.addActionListener(
          e -> submit.accept(new Command("lock", Map.entry(p.id(), lock.isSelected()))));
      locks.put(p.id(), lock);
      params.add(lock);
    }
    JPanel quality = column();
    quality.add(qualityCurrent);
    qualityProfile = new JComboBox<>(QualityProfile.values());
    qualityProfile.setSelectedItem(QualityProfile.matching(s).orElse(QualityProfile.BALANCED));
    JLabel qualityPreview = new JLabel();
    JLabel profileBudget = new JLabel();
    Runnable previewQuality =
        () -> {
          var profile = (QualityProfile) qualityProfile.getSelectedItem();
          qualityPreview.setText(profile.describe());
          profileBudget.setText(
              String.format(
                  Locale.ROOT,
                  "Preset storage: %.1f MiB plus display buffers",
                  dev.visualengine.gpu.Budget.persistent(profile.apply(new Settings()))
                      / 1048576.));
        };
    qualityProfile.addActionListener(e -> previewQuality.run());
    previewQuality.run();
    quality.add(qualityProfile);
    quality.add(qualityPreview);
    quality.add(profileBudget);
    quality.add(new JLabel("Changing grid, particle count or history restarts state."));
    quality.add(new JLabel("Applying a quality preset stops an active replay."));
    JButton applyQuality = new JButton("Apply quality preset");
    applyQuality.addActionListener(
        e -> submit.accept(new Command("quality", qualityProfile.getSelectedItem())));
    quality.add(applyQuality);
    quality.add(budget);
    debug =
        new JComboBox<>(
            new String[] {
              "Artwork",
              "Raw noise",
              "Cell ID",
              "Flow",
              "Chemical A",
              "Chemical B",
              "Pigment",
              "Age",
              "Normals",
              "History validity",
              "Linear HDR"
            });
    debug.addActionListener(e -> {
      if (!syncing) submit.accept(new Command("debug", debug.getSelectedIndex()));
    });
    quality.add(debug);
    spinner(quality, "Simulation grid (resets state)", "grid", s.grid, 32, 1024, 32);
    spinner(quality, "Particle count (resets state)", "particles", s.particles, 0, 262144, 1024);
    spinner(quality, "History layers (resets state)", "historyLayers", s.historyLayers, 0, 32, 1);
    spinner(quality, "Playback speed", "speed", s.speed, 0., 4., .1);
    spinner(quality, "Display scale", "displayScale", s.displayScale, .25, 1., .25);
    spinner(
        quality, "Analytic loop seconds (0 disables)", "loopSeconds", s.loopSeconds, 0., 3600., 1.);
    for (String[] f :
        new String[][] {
          {"Analytic spatial tile", "tileMode"}, {"Show 2×2 wrap preview", "tilePreview"}
        }) {
      JCheckBox box = new JCheckBox(f[0]);
      box.addActionListener(e -> {
        if (!syncing) submit.accept(new Command(f[1], box.isSelected()));
      });
      flags.put(f[1], box);
      quality.add(box);
    }
    JPanel files = column();
    fileButton(files, "Save configuration preset", "save", "study.json", true);
    fileButton(files, "Load configuration preset (restart)", "load", "study.json", false);
    fileButton(files, "Save full simulation checkpoint", "saveCheckpoint", "study.vstate", true);
    fileButton(files, "Resume full simulation checkpoint", "loadCheckpoint", "study.vstate", false);
    fileButton(files, "Capture artwork PNG", "capture", "artwork.png", true);
    files.add(new JLabel("Still output width and height"));
    JPanel captureDimensions = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
    JSpinner captureWidth = new JSpinner(new SpinnerNumberModel(1920, 32, 8192, 32));
    JSpinner captureHeight = new JSpinner(new SpinnerNumberModel(1080, 32, 8192, 32));
    captureDimensions.add(captureWidth);
    captureDimensions.add(new JLabel("×"));
    captureDimensions.add(captureHeight);
    files.add(captureDimensions);
    JButton captureSized = new JButton("Capture at chosen resolution");
    captureSized.addActionListener(e -> {
      try {
        var size =
            new dev.visualengine.export.CaptureSize(
                (int) captureWidth.getValue(), (int) captureHeight.getValue());
        JFileChooser chooser = new JFileChooser(data.toFile());
        chooser.setSelectedFile(data.resolve("artwork.png").toFile());
        if (chooser.showSaveDialog(frame) == JFileChooser.APPROVE_OPTION)
          submit.accept(
              new Command(
                  "captureStill", new Command.Still(chooser.getSelectedFile().toPath(), size)));
      } catch (IllegalArgumentException ex) {
        showStatus(ex.getMessage());
      }
    });
    files.add(captureSized);
    files.add(new JLabel("Chosen-size stills render at full quality without advancing time."));
    files.add(
        new TextureMapPanel(
            choice -> {
              var size =
                  new dev.visualengine.export.CaptureSize(
                      (int) captureWidth.getValue(), (int) captureHeight.getValue());
              JFileChooser chooser = new JFileChooser(data.toFile());
              chooser.setSelectedFile(data.resolve(choice.kind().id() + ".png").toFile());
              if (chooser.showSaveDialog(frame) == JFileChooser.APPROVE_OPTION)
                submit.accept(
                    new Command(
                        "exportMap",
                        new Command.MapExport(
                            chooser.getSelectedFile().toPath(),
                            choice.kind(),
                            size,
                            choice.range())));
            },
            this::showStatus));
    button(files, "Bookmark discovery (configuration and artwork)", "bookmark");
    button(files, "Freeze A/B reference (left frozen, right live)", "freeze");
    button(files, "Clear A/B reference", "clearReference");
    fileButton(files, "Import reusable effect", "importEffect", "effect.json", false);
    fileButton(files, "Export reusable effect", "exportEffect", "effect.json", true);
    button(files, "Start replay recording from reset", "record");
    button(files, "Stop recording", "stopRecord");
    fileButton(files, "Save recorded replay", "saveReplay", "take.replay.json", true);
    fileButton(files, "Play recorded replay", "loadReplay", "take.replay.json", false);
    files.add(new JLabel("Presets restart state. Captures record the reference tick."));
    JTabbedPane tabs = new JTabbedPane();
    tabs.addTab("Scene", new JScrollPane(scene));
    tabs.addTab("Controls", new JScrollPane(params));
    tabs.addTab("Quality", new JScrollPane(quality));
    tabs.addTab("Files", new JScrollPane(files));
    discoveries = new DiscoveryPanel(submit);
    tabs.addTab("Discoveries", discoveries);
    morph =
        new MorphPanel(
            submit,
            slot -> {
              JFileChooser chooser = new JFileChooser(data.toFile());
              if (chooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION)
                submit.accept(
                    new Command(
                        "morphLoad",
                        new Command.MorphLoad(slot, chooser.getSelectedFile().toPath())));
            });
    tabs.addTab("Morph", new JScrollPane(morph));
    tabs.addChangeListener(e -> {
      if (tabs.getSelectedComponent() == discoveries) discoveries.open();
    });
    authoring = new AuthoringPanel(submit, s);
    tabs.addTab("Authoring", authoring);
    effectControls = new EffectControlsPanel(submit, s);
    tabs.addTab("Effect controls", effectControls);
    modulation = new ModulationPanel(submit, s);
    tabs.addTab("Modulation", modulation);
    frame.add(tabs, BorderLayout.CENTER);
    frame.add(status, BorderLayout.SOUTH);
    frame.setSize(540, 860);
    frame.setLocationByPlatform(true);
    syncNow(s);
    frame.setVisible(true);
  }

  private void spinner(
      JPanel p,
      String label,
      String id,
      Number initial,
      Comparable<?> min,
      Comparable<?> max,
      Number step) {
    p.add(new JLabel(label));
    JSpinner control =
        new JSpinner(new SpinnerNumberModel(initial, (Comparable) min, (Comparable) max, step));
    numeric.put(id, control);
    control.addChangeListener(e -> {
      updateBudget();
      if (!syncing) submit.accept(new Command(id, control.getValue()));
    });
    p.add(control);
  }

  private void updateBudget() {
    if (numeric.containsKey("grid")
        && numeric.containsKey("particles")
        && numeric.containsKey("historyLayers")) {
      Settings estimate = new Settings();
      estimate.grid = ((Number) numeric.get("grid").getValue()).intValue();
      estimate.particles = ((Number) numeric.get("particles").getValue()).intValue();
      estimate.historyLayers = ((Number) numeric.get("historyLayers").getValue()).intValue();
      if (numeric.containsKey("displayScale"))
        estimate.displayScale = ((Number) numeric.get("displayScale").getValue()).doubleValue();
      qualityCurrent.setText(
          "Current quality: "
              + QualityProfile.matching(estimate).map(Object::toString).orElse("Custom"));
      budget.setText(
          String.format(
              Locale.ROOT,
              "Persistent GPU storage: %.1f MiB; display buffers additional",
              dev.visualengine.gpu.Budget.persistent(estimate) / 1048576.));
    }
  }

  private void fileButton(JPanel p, String label, String type, String filename, boolean save) {
    JButton b = new JButton(label);
    b.addActionListener(e -> {
      JFileChooser chooser = new JFileChooser(data.toFile());
      chooser.setSelectedFile(data.resolve(filename).toFile());
      if ((save ? chooser.showSaveDialog(frame) : chooser.showOpenDialog(frame))
          == JFileChooser.APPROVE_OPTION)
        submit.accept(new Command(type, chooser.getSelectedFile().toPath()));
    });
    p.add(b);
  }

  private void syncNow(Settings s) {
    syncing = true;
    try {
      seedField.setText(Long.toString(s.seed));
      qualityCurrent.setText(
          "Current quality: " + QualityProfile.matching(s).map(Object::toString).orElse("Custom"));
      locks.forEach((id, box) -> box.setSelected(s.locks.contains(id)));
      numeric.get("grid").setValue(s.grid);
      numeric.get("particles").setValue(s.particles);
      numeric.get("historyLayers").setValue(s.historyLayers);
      numeric.get("speed").setValue(s.speed);
      numeric.get("displayScale").setValue(s.displayScale);
      numeric.get("loopSeconds").setValue(s.loopSeconds);
      updateBudget();
      recipes.setSelectedItem(s.recipe);
      palette.setSelectedIndex(s.palette);
      debug.setSelectedIndex(s.debug);
      for (var p : Settings.PARAMETERS)
        sliders
            .get(p.id())
            .setValue(
                (int)
                    Math.round(1000 * (s.get(p.id()) - p.minimum()) / (p.maximum() - p.minimum())));
      flags.get("feedbackEnabled").setSelected(s.feedbackEnabled);
      flags.get("pigmentEnabled").setSelected(s.pigmentEnabled);
      flags.get("reactionEnabled").setSelected(s.reactionEnabled);
      flags.get("particlesEnabled").setSelected(s.particlesEnabled);
      if (authoring != null) authoring.sync(s);
      if (effectControls != null) effectControls.sync(s);
      if (modulation != null) modulation.sync(s);
      flags.get("tileMode").setSelected(s.tileMode);
      flags.get("tilePreview").setSelected(s.tilePreview);
    } finally {
      syncing = false;
    }
  }

  public void sync(Settings s) {
    Settings copy = s.copy();
    SwingUtilities.invokeLater(() -> syncNow(copy));
  }

  public void showStatus(String text) {
    SwingUtilities.invokeLater(() -> status.setText(text.replace('\n', ' ')));
  }

  public void showMorph(dev.visualengine.model.PresetMorph.View view) {
    SwingUtilities.invokeLater(() -> morph.showView(view));
  }

  public void showDiscoveries(long generation, dev.visualengine.presets.DiscoveryStore.Scan scan) {
    SwingUtilities.invokeLater(() -> discoveries.showScan(generation, scan));
  }

  public void discoveryFailure(long generation, String message) {
    SwingUtilities.invokeLater(() -> discoveries.failure(generation, message));
  }

  public void showDiscoveryThumbnails(
      long generation, Map<String, java.awt.image.BufferedImage> images) {
    SwingUtilities.invokeLater(() -> discoveries.showThumbnails(generation, images));
  }

  public void discoveriesChanged() {
    SwingUtilities.invokeLater(() -> discoveries.changed());
  }

  public void show() {
    SwingUtilities.invokeLater(() -> frame.setVisible(true));
  }

  @Override
  public void close() {
    SwingUtilities.invokeLater(() -> frame.dispose());
  }
}
