package dev.visualengine.ui;

import dev.visualengine.app.Command;
import dev.visualengine.presets.DiscoveryStore;
import dev.visualengine.presets.DiscoveryStore.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.*;
import javax.swing.event.*;

/** Bounded, paged gallery. Disk reads arrive on the file worker, never inside a cell renderer. */
public final class DiscoveryPanel extends JPanel {
  private static final int PAGE = 24;
  private final Consumer<Command> submit;
  private final JTextField query = new JTextField(18), label = new JTextField();
  private final JCheckBox onlyFavorites = new JCheckBox("Favorites only"),
      favorite = new JCheckBox("Favorite"),
      lock = new JCheckBox("Lock composition controls", true);
  private final DefaultListModel<Summary> model = new DefaultListModel<>();
  private final JList<Summary> list = new JList<>(model);
  private final JTextArea details = new JTextArea(7, 34);
  private final JLabel status = new JLabel("Open this tab to load discoveries"),
      pageLabel = new JLabel();
  private final JButton previous = new JButton("Previous"),
      next = new JButton("Next"),
      save = new JButton("Save label / favorite");
  private final List<JButton> actions = new ArrayList<>();
  private List<Summary> entries = List.of(), filtered = List.of();
  private Map<String, BufferedImage> thumbnails = Map.of();
  private long scanGeneration, thumbnailGeneration;
  private int page, skipped;
  private boolean requested, truncated;

  public DiscoveryPanel(Consumer<Command> submit) {
    super(new BorderLayout(8, 8));
    this.submit = submit;
    setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
    JPanel top = new JPanel(new BorderLayout(4, 4));
    JPanel search = new JPanel(new BorderLayout(4, 0));
    search.add(new JLabel("Search"), BorderLayout.WEST);
    search.add(query);
    JButton refresh = new JButton("Refresh");
    refresh.addActionListener(e -> refresh());
    search.add(refresh, BorderLayout.EAST);
    top.add(search, BorderLayout.NORTH);
    top.add(onlyFavorites, BorderLayout.CENTER);
    top.add(status, BorderLayout.SOUTH);
    add(top, BorderLayout.NORTH);
    list.setName("discovery-list");
    onlyFavorites.setName("discovery-favorites");
    previous.setName("discovery-previous");
    next.setName("discovery-next");
    query.setName("discovery-search");
    list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    list.setFixedCellHeight(116);
    list.setCellRenderer(
        new DefaultListCellRenderer() {
          public Component getListCellRendererComponent(
              JList<?> source, Object value, int index, boolean selected, boolean focused) {
            JLabel cell =
                (JLabel)
                    super.getListCellRendererComponent(source, value, index, selected, focused);
            Summary item = (Summary) value;
            BufferedImage preview = thumbnails.get(item.id());
            cell.setIcon(preview == null ? null : new ImageIcon(preview));
            cell.setIconTextGap(12);
            cell.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
            cell.setText(
                "<html><b>"
                    + escape(item.label())
                    + "</b><br>"
                    + escape(item.recipe().toLowerCase(Locale.ROOT).replace('_', ' '))
                    + "<br>"
                    + (item.favorite() ? "★ · " : "")
                    + (item.legacy() ? "Legacy · " : "")
                    + "Configuration · restart</html>");
            return cell;
          }
        });
    add(new JScrollPane(list), BorderLayout.CENTER);
    JPanel bottom = new JPanel();
    bottom.setLayout(new BoxLayout(bottom, BoxLayout.Y_AXIS));
    JPanel pages = new JPanel(new FlowLayout(FlowLayout.CENTER));
    pages.add(previous);
    pages.add(pageLabel);
    pages.add(next);
    bottom.add(pages);
    previous.addActionListener(e -> {
      page--;
      rebuild();
    });
    next.addActionListener(e -> {
      page++;
      rebuild();
    });
    details.setEditable(false);
    details.setLineWrap(true);
    details.setWrapStyleWord(true);
    details.setOpaque(false);
    bottom.add(new JScrollPane(details));
    bottom.add(lock);
    JPanel row = new JPanel(new GridLayout(1, 2, 5, 4));
    branch(row, "Open configuration (restart)", "open-discovery", Variation.CONFIGURATION);
    JButton reference =
        action(
            "Use image as A/B reference",
            "reference-discovery",
            () -> submit.accept(new Command("discoveryReference", selected().id())));
    row.add(reference);
    bottom.add(row);
    JPanel variants = new JPanel(new GridLayout(1, 2, 5, 4));
    branch(variants, "Branch palette", "branch-palette", Variation.PALETTE);
    branch(variants, "Branch motion", "branch-motion", Variation.MOTION);
    bottom.add(variants);
    bottom.add(new JLabel("Label (managed discoveries only)"));
    label.setName("discovery-label");
    bottom.add(label);
    JPanel annotations = new JPanel(new BorderLayout());
    annotations.add(favorite, BorderLayout.WEST);
    annotations.add(save, BorderLayout.EAST);
    bottom.add(annotations);
    save.addActionListener(e ->
        submit.accept(
            new Command(
                "annotateDiscovery",
                new Command.DiscoveryAnnotation(
                    selected().id(), label.getText().trim(), favorite.isSelected()))));
    add(bottom, BorderLayout.SOUTH);
    list.addListSelectionListener(e -> {
      if (!e.getValueIsAdjusting()) selection();
    });
    query
        .getDocument()
        .addDocumentListener(
            new DocumentListener() {
              public void insertUpdate(DocumentEvent e) {
                filter();
              }

              public void removeUpdate(DocumentEvent e) {
                filter();
              }

              public void changedUpdate(DocumentEvent e) {
                filter();
              }
            });
    onlyFavorites.addActionListener(e -> filter());
    selection();
    rebuild();
  }

  public void open() {
    if (!requested) refresh();
  }

  public void refresh() {
    requested = true;
    status.setText("Loading discovery history…");
    submit.accept(new Command("discoveryScan", new Command.History(++scanGeneration)));
  }

  public void changed() {
    if (requested) refresh();
  }

  public void showScan(long generation, Scan scan) {
    if (generation != scanGeneration) return;
    entries = scan.items();
    skipped = scan.skipped();
    truncated = scan.truncated();
    filter();
  }

  public void failure(long generation, String message) {
    if (generation == scanGeneration) status.setText("History unavailable: " + message);
  }

  public void showThumbnails(long generation, Map<String, BufferedImage> images) {
    if (generation != thumbnailGeneration) return;
    thumbnails = Map.copyOf(images);
    list.repaint();
  }

  private void filter() {
    String text = query.getText().trim().toLowerCase(Locale.ROOT);
    filtered =
        entries.stream()
            .filter(
                e -> (!onlyFavorites.isSelected() || e.favorite()) && e.searchable().contains(text))
            .toList();
    page = 0;
    rebuild();
  }

  private void rebuild() {
    page = Math.clamp(page, 0, Math.max(0, (filtered.size() - 1) / PAGE));
    thumbnails = Map.of();
    model.clear();
    int start = page * PAGE, end = Math.min(start + PAGE, filtered.size());
    for (int i = start; i < end; i++) model.addElement(filtered.get(i));
    previous.setEnabled(page > 0);
    next.setEnabled(end < filtered.size());
    pageLabel.setText(
        filtered.isEmpty() ? "No matches" : (start + 1) + "–" + end + " of " + filtered.size());
    status.setText(
        entries.size()
            + " discoveries · "
            + skipped
            + " invalid skipped"
            + (truncated ? " · newest " + DiscoveryStore.LIMIT + " scanned" : ""));
    long generation = ++thumbnailGeneration;
    if (!model.isEmpty()) {
      list.setSelectedIndex(0);
      submit.accept(
          new Command(
              "discoveryThumbnails",
              new Command.Thumbnails(
                  generation, filtered.subList(start, end).stream().map(Summary::id).toList())));
    }
    selection();
  }

  private Summary selected() {
    return list.getSelectedValue();
  }

  private void selection() {
    Summary item = selected();
    actions.forEach(b -> b.setEnabled(item != null));
    boolean editable = item != null && !item.legacy();
    save.setEnabled(editable);
    label.setEnabled(editable);
    favorite.setEnabled(editable);
    label.setText(item == null ? "" : item.label());
    favorite.setSelected(item != null && item.favorite());
    details.setText(
        item == null
            ? "Bookmark a scene from the Files tab.\n"
                + "Discoveries store artwork and a restarting configuration.\n"
                + "Use a checkpoint to resume developed simulation state."
            : "Configuration only — restarts at tick 0. Recorded tick "
                + item.tick()
                + ".\nSeed "
                + item.seed()
                + " · "
                + item.width()
                + "×"
                + item.height()
                + " · "
                + DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                    .withZone(ZoneId.systemDefault())
                    .format(Instant.ofEpochMilli(item.savedAt()))
                + "\nGrid "
                + item.grid()
                + " · "
                + item.particles()
                + " particles · "
                + item.historyLayers()
                + " history layers"
                + "\nEngine "
                + item.engineVersion()
                + " · "
                + item.algorithmRevision()
                + "\nGroups: "
                + (item.dependencies().isEmpty()
                    ? "embedded built-in configuration"
                    : String.join(", ", item.dependencies()))
                + "\nID: "
                + item.id()
                + "\nShader: "
                + item.programSignature()
                + (item.includesReference() ? "\nSaved artwork includes its A/B comparison." : ""));
    details.setCaretPosition(0);
  }

  private void branch(JPanel row, String title, String name, Variation variation) {
    row.add(
        action(
            title,
            name,
            () ->
                submit.accept(
                    new Command(
                        "loadDiscovery",
                        new Command.DiscoveryBranch(
                            selected().id(), variation, lock.isSelected())))));
  }

  private JButton action(String title, String name, Runnable run) {
    JButton button = new JButton(title);
    button.setName(name);
    actions.add(button);
    button.addActionListener(e -> run.run());
    return button;
  }

  private static String escape(String s) {
    return s.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;");
  }
}
