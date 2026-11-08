package dev.visualengine.export;

import com.google.gson.*;
import dev.visualengine.model.*;
import dev.visualengine.presets.PresetIO;
import dev.visualengine.time.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Versioned pinned workloads, independent of the current desktop scene and user data. */
public final class BenchmarkWorkloads {
  public static final int VERSION = 1, TICKS_PER_FRAME = 2;

  public enum Profile {
    SMOKE(96, 64, 65, 257, 3, 8, 16),
    QUICK(320, 200, 129, 4096, 8, 30, 60),
    STANDARD(1280, 720, 256, 16384, 16, 60, 180),
    FULLHD(1920, 1080, 257, 16384, 16, 60, 180);
    public final int width, height, grid, particles, history, warmup, samples;

    Profile(int width, int height, int grid, int particles, int history, int warmup, int samples) {
      this.width = width;
      this.height = height;
      this.grid = grid;
      this.particles = particles;
      this.history = history;
      this.warmup = warmup;
      this.samples = samples;
    }

    public int frames() {
      return warmup + samples;
    }

    public int ticks() {
      return frames() * TICKS_PER_FRAME;
    }
  }

  public record Workload(
      String id, Settings initial, ReplayLog input, JsonObject contract, String hash) {}

  private BenchmarkWorkloads() {}

  public static List<Workload> create(Profile profile) {
    List<Workload> result = new ArrayList<>();
    String[] ids = {
      "opal", "contours", "nested-warps", "pigment", "reaction", "particles", "history"
    };
    Settings.Recipe[] recipes = {
      Settings.Recipe.LIQUID_OPAL,
      Settings.Recipe.LIVING_CONTOURS,
      Settings.Recipe.PATTERNS_WITHIN_PATTERNS,
      Settings.Recipe.FLUID_PALETTE,
      Settings.Recipe.ALIEN_ECOSYSTEM,
      Settings.Recipe.COSMIC_SILK,
      Settings.Recipe.TIME_EXCAVATION
    };
    for (int index = 0; index < ids.length; index++) {
      String id = ids[index];
      Settings s = new Settings();
      Recipes.apply(s, recipes[index]);
      s.name = "Benchmark " + id;
      s.seed = Seeds.derive(173496285120L, id, "benchmark-v1");
      s.grid = profile.grid;
      s.particles = profile.particles;
      s.historyLayers = profile.history;
      s.displayScale = 1;
      if (id.equals("contours")) s.set("scale", 3.6);
      if (id.equals("nested-warps")) {
        s.set("warp", 1.6);
        s.set("detail", .7);
      }
      if (id.equals("history")) s.set("historyMix", .7);
      s.validate();
      ReplayLog input = ReplayLog.begin(s, profile.width, profile.height);
      for (int tick = 1; tick <= profile.ticks(); tick++) {
        boolean brush = index >= 3 && ((tick - 1) / 24) % 3 != 2;
        double x = index >= 3 ? .55 * Math.sin(tick * Playback.DT * 1.7) : 0;
        double y = index >= 3 ? .35 * Math.cos(tick * Playback.DT * 1.1) : 0;
        input.recordPointer(tick, x, y, s.get("radius"), brush);
      }
      input.endTick = profile.ticks();
      input.validate();
      JsonObject contract = PresetIO.document(s, 0);
      contract.remove("engineVersion");
      contract.addProperty("workloadVersion", VERSION);
      contract.addProperty("id", id);
      contract.addProperty("profile", profile.name().toLowerCase(Locale.ROOT));
      contract.addProperty("width", profile.width);
      contract.addProperty("height", profile.height);
      contract.addProperty("warmupFrames", profile.warmup);
      contract.addProperty("measuredFrames", profile.samples);
      contract.addProperty("ticksPerFrame", TICKS_PER_FRAME);
      contract.addProperty(
          "captureStage",
          "finished opaque SDR sRGB before UI; full display scale; no reference or diagnostic"
              + " overlays");
      contract.add("inputEvents", PresetIO.JSON.toJsonTree(input.events));
      result.add(new Workload(id, s, input, contract, hash(contract)));
    }
    return List.copyOf(result);
  }

  public static String hash(JsonElement value) {
    return sha256(canonical(value).getBytes(StandardCharsets.UTF_8));
  }

  public static String sha256(byte[] bytes) {
    try {
      return HexFormat.of()
          .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new AssertionError(e);
    }
  }

  private static String canonical(JsonElement value) {
    if (value.isJsonObject()) {
      var object = value.getAsJsonObject();
      return "{"
          + String.join(
              ",",
              new TreeSet<>(object.keySet())
                  .stream()
                      .map(k -> PresetIO.JSON.toJson(k) + ":" + canonical(object.get(k)))
                      .toList())
          + "}";
    }
    if (value.isJsonArray())
      return "["
          + String.join(
              ",",
              java.util.stream.StreamSupport.stream(value.getAsJsonArray().spliterator(), false)
                  .map(BenchmarkWorkloads::canonical)
                  .toList())
          + "]";
    return value.toString();
  }
}
