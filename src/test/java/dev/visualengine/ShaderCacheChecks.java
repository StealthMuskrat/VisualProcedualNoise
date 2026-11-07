package dev.visualengine;

import dev.visualengine.app.Options;
import dev.visualengine.gpu.*;
import dev.visualengine.model.*;
import dev.visualengine.time.ReplayLog;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

public final class ShaderCacheChecks {
  private static int count;

  private static void check(boolean ok, String label) {
    count++;
    if (!ok) throw new AssertionError(label);
  }

  private static void rejects(Runnable action, String text) {
    try {
      action.run();
    } catch (RuntimeException expected) {
      check(expected.getMessage().contains(text), "bounded shader rejection: " + text);
      return;
    }
    throw new AssertionError("Accepted " + text);
  }

  private static void select(VariantCache<Integer> cache, String key, int value, long weight) {
    try (var selection = cache.prepare(key, weight, () -> value)) {
      selection.commit();
    }
  }

  static int run() throws Exception {
    check(
        Program.graphicsKey("vertex", "fragment").equals(Program.graphicsKey("vertex", "fragment"))
            && !Program.graphicsKey("a\0b", "c").equals(Program.graphicsKey("a", "b\0c")),
        "shader keys retain complete stage boundaries and stable UTF-8 content");
    check(
        !Options.parse(new String[] {"--headless", "--frames", "1", "--shader-cache", "off"})
                .shaderCache
            && Options.parse(new String[] {}).shaderCache,
        "inactive cache retention is a separate session-only option");
    rejects(() -> Options.parse(new String[] {"--shader-cache", "adaptive"}), "on|off");
    rejects(
        () -> Options.parse(new String[] {"--benchmark-suite", "suite", "--shader-cache", "off"}),
        "pins");
    rejects(
        () -> Options.parse(new String[] {"--diagnose-gpu", "gpu.json", "--shader-cache", "off"}),
        "accepts only");
    List<Integer> deleted = new ArrayList<>();
    try (var guarded = new VariantCache<Integer>(1, 100, deleted::add)) {
      rejects(
          () ->
              guarded.prepare(
                  "outer",
                  10,
                  () -> {
                    guarded.prepare(
                        "inner",
                        10,
                        () -> {
                          throw new AssertionError("Reentrant material build");
                        });
                    return 1;
                  }),
          "already staged");
    }
    try (var cache = new VariantCache<Integer>(2, 100, deleted::add)) {
      select(cache, "a", 1, 40);
      select(cache, "b", 2, 40);
      try (var hit =
          cache.prepare(
              "a",
              40,
              () -> {
                throw new AssertionError("Hit recompiled");
              })) {
        check(hit.value() == 1, "retained material selection skips compilation");
        hit.commit();
      }
      select(cache, "c", 3, 40);
      check(
          deleted.equals(List.of(2))
              && cache.stats().entries() == 2
              && cache.stats().activeKey().equals("c"),
          "committed LRU selection evicts the least recently selected inactive program");
      var before = cache.stats();
      try (var aborted = cache.prepare("d", 40, () -> 4)) {
        check(
            cache.stats().entries() == 2 && cache.stats().activeKey().equals("c"),
            "staged candidate does not evict the working cache");
        rejects(() -> cache.prepare("extra", 1, () -> 5), "already staged");
      }
      check(
          deleted.equals(List.of(2, 4)) && cache.stats().sourceBytes() == before.sourceBytes(),
          "downstream failure closes only its uncommitted candidate");
      try (var abortedHit = cache.prepare("a", 40, () -> 100)) {}
      select(cache, "e", 5, 40);
      check(
          deleted.equals(List.of(2, 4, 1)),
          "aborted cache hits do not change LRU order or release resident programs");
      rejects(
          () ->
              cache.prepare(
                  "bad",
                  40,
                  () -> {
                    throw new IllegalArgumentException("compile failed");
                  }),
          "compile failed");
      check(
          cache.stats().activeKey().equals("e") && cache.stats().failures() == 1,
          "failed compilation preserves the working selection");
      rejects(
          () ->
              cache.prepare(
                  "large",
                  101,
                  () -> {
                    throw new AssertionError("oversized build");
                  }),
          "budget");
      check(
          CompletableFuture.supplyAsync(
                  () -> {
                    try {
                      cache.stats();
                      return false;
                    } catch (IllegalStateException expected) {
                      return true;
                    }
                  })
              .get(5, TimeUnit.SECONDS),
          "worker threads cannot mutate or dispose context-owned variants");
    }
    check(
        new HashSet<>(deleted).equals(Set.of(1, 2, 3, 4, 5)) && deleted.size() == 5,
        "shutdown closes each owned candidate or retained program once");
    deleted.clear();
    try (var cache = new VariantCache<Integer>(8, 100, deleted::add)) {
      select(cache, "a", 1, 60);
      select(cache, "b", 2, 60);
      check(
          cache.stats().entries() == 1
              && cache.stats().sourceBytes() == 60
              && deleted.equals(List.of(1)),
          "source-byte budget bounds residency independently of entry count");
      var pending = cache.prepare("c", 40, () -> 3);
      cache.close();
      pending.close();
      cache.close();
      check(
          deleted.equals(List.of(1, 3, 2)),
          "shutdown aborts a pending candidate before releasing retained entries");
    }
    Path root = Files.createTempDirectory(Path.of("build/reports"), "source-bounds-");
    try {
      ShaderSources sources = new ShaderSources(root);
      Files.createDirectories(root.resolve("nested"));
      Files.writeString(
          root.resolve("main.frag"),
          "#version 430 core\n#include \"nested/part.glsl\"\nvoid main(){}\n");
      Files.writeString(root.resolve("nested/part.glsl"), "float f(){return 1.;}");
      String first = sources.read("main.frag");
      check(
          first.contains("float f(){return 1.;}") && first.contains("#line 3 1"),
          "bounded expansion preserves include diagnostics and source content");
      Files.writeString(root.resolve("nested/part.glsl"), "float f(){return 2.;}");
      check(
          !sources.read("main.frag").equals(first),
          "each root read observes current engine source instead of a stale cross-edit snapshot");
      Files.writeString(root.resolve("cycle.glsl"), "#include \"cycle.glsl\"");
      rejects(() -> sources.read("cycle.glsl"), "cyclic");
      rejects(() -> sources.read("../escape.glsl"), "Invalid");
      rejects(() -> sources.read("..\\escape.glsl"), "Invalid");
      Files.write(root.resolve("nul.glsl"), new byte[] {0});
      rejects(() -> sources.read("nul.glsl"), "NUL");
      Files.write(root.resolve("large.glsl"), new byte[ShaderSources.FILE_BYTES + 1]);
      rejects(() -> sources.read("large.glsl"), "256 KiB");
      Files.write(root.resolve("utf8.glsl"), new byte[] {(byte) 0xc3, 0x28});
      rejects(() -> sources.read("utf8.glsl"), "MalformedInput");
      Files.writeString(root.resolve("leaf.glsl"), "x".repeat(220000));
      Files.writeString(root.resolve("expanded.glsl"), "#include \"leaf.glsl\"\n".repeat(5));
      rejects(() -> sources.read("expanded.glsl"), "1 MiB");
      Files.writeString(root.resolve("tiny.glsl"), "");
      Files.writeString(root.resolve("wide.glsl"), "#include \"tiny.glsl\"\n".repeat(128));
      rejects(() -> sources.read("wide.glsl"), "128 file");
      for (int i = 0; i < 17; i++)
        Files.writeString(
            root.resolve("depth" + i), i == 16 ? "" : "#include \"depth" + (i + 1) + "\"");
      rejects(() -> sources.read("depth0"), "Invalid");
    } finally {
      try (var files = Files.walk(root)) {
        for (Path p : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
      }
    }
    Settings initial = new Settings();
    initial.recipe = Settings.Recipe.CUSTOM;
    initial.expression = "sin(p.x * frequency)";
    initial.grid = 65;
    initial.particles = 257;
    initial.historyLayers = 3;
    initial.pigmentEnabled = initial.feedbackEnabled = true;
    ReplayLog take = ReplayLog.begin(initial, 96, 64);
    String[] expressions = {
      initial.expression,
      "cos(p.y * frequency)",
      initial.expression,
      "noise3d(vec3(p * frequency, t * .1))",
      "cos(p.y * frequency)"
    };
    for (int i = 0; i < expressions.length; i++) {
      Settings event = initial.copy();
      event.expression = expressions[i];
      event.set("frequency", 1 + i * .25);
      take.recordControl(1 + i * 16, event);
      take.recordPointer(1 + i * 16, -.4 + i * .2, .1, event.get("radius"), i != 3);
    }
    take.endTick = 96;
    take.save(Path.of("build/reports/shader-cache-replay-fixture.json"));
    return count;
  }
}
