package dev.visualengine;

import com.google.gson.JsonParser;
import dev.visualengine.export.Capture;
import dev.visualengine.model.*;
import dev.visualengine.presets.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;

public final class DiscoveryChecks {
  private static int count;

  private interface Action {
    void run() throws Exception;
  }

  private static void check(boolean value, String message) {
    count++;
    if (!value) throw new AssertionError(message);
  }

  private static void rejects(Action action, String message) throws Exception {
    try {
      action.run();
    } catch (IOException | IllegalArgumentException e) {
      count++;
      return;
    }
    throw new AssertionError("Accepted " + message);
  }

  public static int run() throws Exception {
    Path temporary = Files.createTempDirectory("discovery-contract-");
    Path root = temporary.resolve("études and studies");
    try {
      var store =
          new DiscoveryStore(
              root, Clock.fixed(Instant.ofEpochMilli(1793990000000L), ZoneOffset.UTC));
      check(
          store.scan().items().isEmpty() && !Files.exists(root),
          "empty history scan performs no writes");
      Settings s = new Settings();
      s.seed = Long.MIN_VALUE;
      s.graph = AuthoringChecks.groupGraph();
      s.installEffectControls(List.of(AuthoringChecks.scalar()), Map.of());
      s.locks.add("motion");
      BufferedImage image = new BufferedImage(96, 64, BufferedImage.TYPE_INT_RGB);
      for (int y = 0; y < 64; y++)
        for (int x = 0; x < 96; x++) image.setRGB(x, y, 0xff000000 | (x * 2 << 16) | y * 3);
      var entry =
          store.save(
              "Été <waves>",
              image,
              s,
              121,
              "test GPU",
              new Capture.View(96, 64, true),
              "a".repeat(64),
              null);
      var loaded = store.load(entry.id());
      check(
          loaded.settings().seed == Long.MIN_VALUE
              && loaded.summary().tick() == 121
              && loaded.summary().includesReference()
              && !loaded.summary().legacy(),
          "lossless discovery seed, tick and capture policy round trip");
      check(
          loaded.settings().effectControls.equals(s.effectControls)
              && loaded.settings().graph.groups.equals(s.graph.groups)
              && !loaded.summary().dependencies().isEmpty(),
          "discovery retains custom controls and pinned graph definitions");
      var art = store.artwork(entry.id());
      check(
          art.getRGB(0, 0) == image.getRGB(0, 0) && art.getRGB(95, 63) == image.getRGB(95, 63),
          "discovery artwork is complete and correctly oriented");
      var thumb = store.thumbnail(entry.id());
      check(thumb.getWidth() == 160 && thumb.getHeight() == 100, "bounded discovery thumbnail");
      String original = PresetIO.JSON.toJson(PresetIO.document(loaded.settings(), 121));
      Settings palette = store.branch(entry.id(), DiscoveryStore.Variation.PALETTE, true, 123);
      check(
          palette.palette != s.palette
              && palette.seed == s.seed
              && palette.get("warp") == s.get("warp")
              && palette.effectControls.equals(s.effectControls)
              && palette.graph.groups.equals(s.graph.groups),
          "palette branch preserves composition, seed and authoring");
      check(
          Settings.PARAMETERS.stream()
              .filter(p -> p.group().equals("Composition"))
              .allMatch(p -> palette.locks.contains(p.id())),
          "branch locks composition controls");
      Settings motion = store.branch(entry.id(), DiscoveryStore.Variation.MOTION, true, 123);
      check(
          motion.get("motion") == s.get("motion")
              && motion.get("colorRate") != s.get("colorRate")
              && motion.palette == s.palette,
          "motion branch respects existing locks and leaves palette intact");
      check(
          original.equals(
              PresetIO.JSON.toJson(PresetIO.document(store.load(entry.id()).settings(), 121))),
          "branching never edits source discovery");
      Path manifest = root.resolve(entry.id()).resolve("artwork.png.json");
      byte[] artwork = Files.readAllBytes(root.resolve(entry.id()).resolve("artwork.png"));
      var annotated = store.annotate(entry.id(), "A favorite", true);
      check(
          annotated.favorite()
              && annotated.label().equals("A favorite")
              && Arrays.equals(
                  artwork, Files.readAllBytes(root.resolve(entry.id()).resolve("artwork.png"))),
          "annotation preserves artwork and configuration");
      byte[] before = Files.readAllBytes(manifest);
      rejects(() -> store.annotate(entry.id(), "bad\nname", false), "control characters in labels");
      check(
          Arrays.equals(before, Files.readAllBytes(manifest)),
          "failed annotation preserves metadata");
      rejects(() -> store.load("../outside"), "path traversal");
      rejects(() -> store.load("legacy:../outside.png"), "legacy path traversal");
      Files.createDirectories(root.resolve(".pending-hidden"));
      check(store.scan().items().size() == 1, "incomplete saves stay hidden");
      Path legacy = root.resolve("study-1793980000000.png");
      Capture.write(legacy, image, s, 9, "legacy GPU");
      var scan = store.scan();
      check(
          scan.items().size() == 2
              && scan.items().getFirst().id().equals(entry.id())
              && scan.items().get(1).legacy(),
          "newest-first history includes legacy bookmarks");
      String legacyId = scan.items().get(1).id();
      check(
          store.artwork(legacyId).getRGB(95, 63) == image.getRGB(95, 63),
          "legacy artwork remains readable without migration");
      rejects(() -> store.annotate(legacyId, "changed", true), "legacy annotations");
      var doc = JsonParser.parseString(Files.readString(manifest)).getAsJsonObject();
      doc.getAsJsonObject("settings").addProperty("palette", (s.palette + 1) % 4);
      Files.writeString(manifest, PresetIO.JSON.toJson(doc));
      rejects(() -> store.load(entry.id()), "corrupted immutable configuration");
      check(
          store.scan().skipped() == 1 && store.scan().items().size() == 1,
          "one damaged discovery does not hide valid neighbors");
      Files.write(manifest, before);
      Path thumbnail = root.resolve(entry.id()).resolve("thumbnail.png");
      Files.write(thumbnail, new byte[] {1, 2, 3});
      rejects(() -> store.thumbnail(entry.id()), "damaged thumbnail");
      long oldCount;
      try (var paths = Files.list(root)) {
        oldCount = paths.count();
      }
      final long countBefore = oldCount;
      Settings invalid = s.copy();
      invalid.grid = 0;
      rejects(
          () ->
              store.save(
                  "invalid",
                  image,
                  invalid,
                  1,
                  "device",
                  new Capture.View(96, 64, false),
                  "a".repeat(64),
                  null),
          "invalid discovery save");
      try (var paths = Files.list(root)) {
        check(paths.count() == countBefore, "failed save leaves no published bundle");
      }
      Path blocked = temporary.resolve("blocked");
      Files.writeString(blocked, "keep");
      rejects(
          () ->
              new DiscoveryStore(blocked)
                  .save(
                      "blocked",
                      image,
                      s,
                      1,
                      "device",
                      new Capture.View(96, 64, false),
                      "a".repeat(64),
                      null),
          "unwritable discovery destination");
      check(
          Files.readString(blocked).equals("keep"),
          "failed save leaves occupied destination intact");
    } finally {
      try (var paths = Files.walk(temporary)) {
        for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
      }
    }
    return count;
  }
}
