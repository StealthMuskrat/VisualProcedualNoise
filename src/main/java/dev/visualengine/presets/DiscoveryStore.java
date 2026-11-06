package dev.visualengine.presets;

import com.google.gson.*;
import dev.visualengine.export.Capture;
import dev.visualengine.model.Settings;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * Completed bookmark directories are immutable captures with independently editable labels/stars.
 */
public final class DiscoveryStore {
  public static final int LIMIT = 512, THUMB_WIDTH = 160, THUMB_HEIGHT = 100;
  private static final String NATIVE =
      "study-[0-9]{13}-[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}";
  private static final String LEGACY = "study-[0-9]{13}\\.png";
  private final Path root;
  private final Clock clock;

  public DiscoveryStore(Path root) {
    this(root, Clock.systemUTC());
  }

  public DiscoveryStore(Path root, Clock clock) {
    this.root = root.toAbsolutePath().normalize();
    this.clock = clock;
  }

  public enum Variation {
    CONFIGURATION,
    PALETTE,
    MOTION
  }

  public record Summary(
      String id,
      String label,
      long savedAt,
      boolean favorite,
      boolean legacy,
      String recipe,
      long seed,
      long tick,
      int width,
      int height,
      int grid,
      int particles,
      int historyLayers,
      String engineVersion,
      String algorithmRevision,
      String programSignature,
      List<String> dependencies,
      boolean includesReference) {
    public Summary {
      dependencies = List.copyOf(dependencies);
    }

    public String searchable() {
      return (label + " " + recipe + " " + seed + " " + String.join(" ", dependencies))
          .toLowerCase(Locale.ROOT);
    }
  }

  public record Loaded(Summary summary, Settings settings) {}

  public record Scan(List<Summary> items, int skipped, boolean truncated) {
    public Scan {
      items = List.copyOf(items);
    }
  }

  public Summary save(
      String label,
      BufferedImage image,
      Settings settings,
      long tick,
      String device,
      Capture.View view,
      String programSignature,
      String parent)
      throws IOException {
    validateLabel(label);
    extent(image.getWidth(), image.getHeight());
    if (tick < 0
        || tick > (1L << 52)
        || programSignature == null
        || !programSignature.matches("[a-f0-9]{64}"))
      throw new IllegalArgumentException("Invalid discovery time or shader signature");
    if (parent != null) path(parent);
    PresetIO.parse(PresetIO.JSON.toJson(PresetIO.document(settings, tick)));
    long now = clock.millis();
    if (now < 1_000_000_000_000L || now > 9_999_999_999_999L)
      throw new IllegalArgumentException("Discovery timestamp outside supported range");
    String id = "study-" + now + "-" + UUID.randomUUID();
    Files.createDirectories(root);
    Path pending = Files.createTempDirectory(root, ".pending-");
    try {
      Capture.write(pending.resolve("artwork.png"), image, settings, tick, device, view);
      ImageIO.write(thumbnail(image), "png", pending.resolve("thumbnail.png").toFile());
      var document = document(pending.resolve("artwork.png.json"));
      document.addProperty("programSignature", programSignature);
      JsonObject metadata = new JsonObject();
      metadata.addProperty("version", 1);
      metadata.addProperty("id", id);
      metadata.addProperty("savedAt", now);
      metadata.addProperty("label", label);
      metadata.addProperty("favorite", false);
      metadata.addProperty("reproducibility", "configuration-restart");
      if (parent != null) metadata.addProperty("parent", parent);
      metadata.addProperty(
          "configurationHash",
          hash(PresetIO.JSON.toJson(document).getBytes(StandardCharsets.UTF_8)));
      metadata.addProperty("artworkHash", hash(pending.resolve("artwork.png"), 192L << 20));
      metadata.addProperty("thumbnailHash", hash(pending.resolve("thumbnail.png"), 1 << 20));
      document.add("discovery", metadata);
      PresetIO.atomicWrite(pending.resolve("artwork.png.json"), PresetIO.JSON.toJson(document));
      bounded(pending.resolve("artwork.png.json"), 1 << 20);
      // Validate the completed bundle before making it visible to a gallery scan.
      read(id, document);
      try {
        Files.move(pending, root.resolve(id), StandardCopyOption.ATOMIC_MOVE);
      } catch (AtomicMoveNotSupportedException e) {
        Files.move(pending, root.resolve(id));
      }
      return load(id).summary();
    } finally {
      if (Files.exists(pending))
        try (var paths = Files.walk(pending)) {
          for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
    }
  }

  public Scan scan() throws IOException {
    if (!Files.exists(root)) return new Scan(List.of(), 0, false);
    List<String> candidates;
    try (var paths = Files.list(root)) {
      candidates =
          paths
              .map(p -> p.getFileName().toString())
              .filter(n -> n.matches(NATIVE) || n.matches(LEGACY))
              .sorted(Comparator.reverseOrder())
              .limit(LIMIT + 1)
              .toList();
    }
    List<Summary> items = new ArrayList<>();
    int skipped = 0;
    for (String name : candidates.stream().limit(LIMIT).toList()) {
      try {
        items.add(load(name.endsWith(".png") ? "legacy:" + name : name).summary());
      } catch (IOException | RuntimeException e) {
        skipped++;
      }
    }
    items.sort(Comparator.comparingLong(Summary::savedAt).reversed().thenComparing(Summary::id));
    return new Scan(items, skipped, candidates.size() > LIMIT);
  }

  public Loaded load(String id) throws IOException {
    try {
      return read(id, document(manifest(id)));
    } catch (RuntimeException e) {
      throw new IOException("Invalid discovery: " + e.getMessage(), e);
    }
  }

  private Loaded read(String id, JsonObject document) throws IOException {
    boolean legacy = id.startsWith("legacy:");
    JsonObject metadata = legacy ? null : document.getAsJsonObject("discovery");
    String label;
    long savedAt;
    boolean favorite = false;
    if (legacy) {
      savedAt = Long.parseLong(id.substring("legacy:study-".length(), id.length() - 4));
      label = "Legacy discovery " + savedAt;
    } else {
      if (metadata == null
          || metadata.get("version").getAsInt() != 1
          || !id.equals(metadata.get("id").getAsString())
          || !"configuration-restart".equals(metadata.get("reproducibility").getAsString()))
        throw new IOException("Unsupported discovery metadata");
      var original = document.deepCopy();
      original.remove("discovery");
      if (!hash(PresetIO.JSON.toJson(original).getBytes(StandardCharsets.UTF_8))
          .equals(metadata.get("configurationHash").getAsString()))
        throw new IOException("Discovery configuration checksum mismatch");
      savedAt = metadata.get("savedAt").getAsLong();
      if (savedAt != Long.parseLong(id.substring(6, 19)))
        throw new IOException("Discovery timestamp mismatch");
      label = metadata.get("label").getAsString();
      validateLabel(label);
      favorite = metadata.get("favorite").getAsBoolean();
      for (String field : List.of("artworkHash", "thumbnailHash"))
        if (!metadata.get(field).getAsString().matches("[a-f0-9]{64}"))
          throw new IOException("Invalid discovery checksum");
    }
    Settings settings = PresetIO.parse(document.toString());
    long tick = document.get("referenceTick").getAsLong();
    int width = document.get("width").getAsInt(), height = document.get("height").getAsInt();
    extent(width, height);
    if (tick < 0 || tick > (1L << 52)) throw new IOException("Invalid discovery tick");
    String signature =
        document.has("programSignature")
            ? document.get("programSignature").getAsString()
            : "unrecorded";
    if (!(legacy && signature.equals("unrecorded")) && !signature.matches("[a-f0-9]{64}"))
      throw new IOException("Invalid discovery shader signature");
    String engineVersion = document.get("engineVersion").getAsString();
    List<String> dependencies =
        settings.graph.groups.stream().map(g -> g.id() + "@" + g.version()).sorted().toList();
    if (!engineVersion.matches("[A-Za-z0-9_.+\\-]{1,64}")
        || dependencies.stream().anyMatch(k -> k.length() > 129))
      throw new IOException("Discovery summary text exceeds its bounds");
    Summary summary =
        new Summary(
            id,
            label,
            savedAt,
            favorite,
            legacy,
            settings.recipe.name(),
            settings.seed,
            tick,
            width,
            height,
            settings.grid,
            settings.particles,
            settings.historyLayers,
            engineVersion,
            document.get("algorithmRevision").getAsString(),
            signature,
            dependencies,
            document.has("captureIncludesReference")
                && document.get("captureIncludesReference").getAsBoolean());
    return new Loaded(summary, settings);
  }

  public Summary annotate(String id, String label, boolean favorite) throws IOException {
    validateLabel(label);
    Loaded loaded = load(id);
    if (loaded.summary().legacy())
      throw new IOException("Legacy bookmarks are read-only; save a new discovery after opening");
    Path manifest = manifest(id);
    var doc = document(manifest);
    doc.getAsJsonObject("discovery").addProperty("label", label);
    doc.getAsJsonObject("discovery").addProperty("favorite", favorite);
    read(id, doc);
    PresetIO.atomicWrite(manifest, PresetIO.JSON.toJson(doc));
    return load(id).summary();
  }

  public Settings branch(String id, Variation variation, boolean lockComposition, long entropy)
      throws IOException {
    Settings result = load(id).settings().copy();
    if (lockComposition)
      Settings.PARAMETERS.stream()
          .filter(p -> p.group().equals("Composition"))
          .forEach(p -> result.locks.add(p.id()));
    SplittableRandom random = new SplittableRandom(entropy);
    if (variation == Variation.PALETTE)
      result.palette = (result.palette + 1 + random.nextInt(3)) % 4;
    if (variation == Variation.MOTION)
      for (var p : Settings.PARAMETERS)
        if (List.of("motion", "colorRate").contains(p.id()) && !result.locks.contains(p.id()))
          result.set(p.id(), p.minimum() + random.nextDouble() * (p.maximum() - p.minimum()));
    result.validate();
    return result;
  }

  public BufferedImage artwork(String id) throws IOException {
    Summary entry = load(id).summary();
    Path file = path(id).resolve("artwork.png");
    if (entry.legacy()) file = root.resolve(id.substring(7));
    verifyImage(id, file, false);
    BufferedImage image = image(file, entry.width(), entry.height());
    return image;
  }

  public BufferedImage thumbnail(String id) throws IOException {
    if (id.startsWith("legacy:")) return thumbnail(artwork(id));
    load(id);
    Path file = path(id).resolve("thumbnail.png");
    verifyImage(id, file, true);
    return image(file, THUMB_WIDTH, THUMB_HEIGHT);
  }

  private void verifyImage(String id, Path file, boolean thumbnail) throws IOException {
    String actual = hash(file, thumbnail ? 1 << 20 : 192L << 20);
    if (!id.startsWith("legacy:")) {
      var meta = document(manifest(id)).getAsJsonObject("discovery");
      if (!actual.equals(meta.get(thumbnail ? "thumbnailHash" : "artworkHash").getAsString()))
        throw new IOException("Discovery image checksum mismatch");
    }
  }

  private Path path(String id) {
    if (id == null
        || !(id.matches(NATIVE) || id.startsWith("legacy:") && id.substring(7).matches(LEGACY)))
      throw new IllegalArgumentException("Invalid discovery ID");
    Path path = root.resolve(id.startsWith("legacy:") ? id.substring(7) : id);
    if (!id.startsWith("legacy:")
        && Files.exists(path, LinkOption.NOFOLLOW_LINKS)
        && !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
      throw new IllegalArgumentException("Discovery directory must not be a link or regular file");
    return path;
  }

  private Path manifest(String id) {
    Path p = path(id);
    return id.startsWith("legacy:") ? Path.of(p + ".json") : p.resolve("artwork.png.json");
  }

  private static JsonObject document(Path path) throws IOException {
    bounded(path, 1 << 20);
    return JsonParser.parseString(Files.readString(path)).getAsJsonObject();
  }

  public static void validateLabel(String label) {
    if (label == null
        || label.isBlank()
        || label.length() > 256
        || label.chars().anyMatch(Character::isISOControl))
      throw new IllegalArgumentException(
          "Discovery label must contain 1..256 printable characters");
  }

  private static void extent(int w, int h) {
    if (w < 1 || h < 1 || w > 8192 || h > 8192 || (long) w * h > 33_554_432)
      throw new IllegalArgumentException("Discovery image exceeds supported dimensions");
  }

  private static void bounded(Path path, long limit) throws IOException {
    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > limit)
      throw new IOException("Missing or oversized discovery file: " + path.getFileName());
  }

  private static String hash(byte[] bytes) {
    return HexFormat.of().formatHex(digest().digest(bytes));
  }

  private static MessageDigest digest() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new AssertionError(e);
    }
  }

  private static String hash(Path path, long limit) throws IOException {
    bounded(path, limit);
    MessageDigest digest = digest();
    try (InputStream input = Files.newInputStream(path)) {
      byte[] block = new byte[65536];
      long count = 0;
      int read;
      while ((read = input.read(block)) != -1) {
        if ((count += read) > limit) throw new IOException("Discovery file grew beyond limit");
        digest.update(block, 0, read);
      }
    }
    return HexFormat.of().formatHex(digest.digest());
  }

  private static BufferedImage image(Path path, int width, int height) throws IOException {
    try (var input = ImageIO.createImageInputStream(path.toFile())) {
      var readers = ImageIO.getImageReaders(input);
      if (!readers.hasNext()) throw new IOException("Invalid discovery PNG");
      var reader = readers.next();
      try {
        reader.setInput(input, true, true);
        if (!reader.getFormatName().equalsIgnoreCase("PNG")
            || reader.getWidth(0) != width
            || reader.getHeight(0) != height)
          throw new IOException("Discovery PNG dimensions do not match metadata");
        return reader.read(0);
      } finally {
        reader.dispose();
      }
    }
  }

  private static BufferedImage thumbnail(BufferedImage image) {
    BufferedImage result = new BufferedImage(THUMB_WIDTH, THUMB_HEIGHT, BufferedImage.TYPE_INT_RGB);
    Graphics2D g = result.createGraphics();
    try {
      g.setColor(new Color(17, 24, 32));
      g.fillRect(0, 0, THUMB_WIDTH, THUMB_HEIGHT);
      g.setRenderingHint(
          RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
      double scale =
          Math.min(
              (double) THUMB_WIDTH / image.getWidth(), (double) THUMB_HEIGHT / image.getHeight());
      int w = Math.max(1, (int) Math.round(image.getWidth() * scale)),
          h = Math.max(1, (int) Math.round(image.getHeight() * scale));
      g.drawImage(image, (THUMB_WIDTH - w) / 2, (THUMB_HEIGHT - h) / 2, w, h, null);
    } finally {
      g.dispose();
    }
    return result;
  }
}
