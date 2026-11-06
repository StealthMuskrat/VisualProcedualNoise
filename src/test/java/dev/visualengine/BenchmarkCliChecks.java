package dev.visualengine;

import dev.visualengine.app.Options;
import dev.visualengine.export.*;
import java.nio.file.*;
import java.util.*;

public final class BenchmarkCliChecks {
  private static int count;

  private static void check(boolean ok, String label) {
    count++;
    if (!ok) throw new AssertionError(label);
  }

  private static void rejects(String... args) {
    try {
      Options.parse(args);
    } catch (IllegalArgumentException expected) {
      count++;
      return;
    }
    throw new AssertionError("Accepted benchmark options " + Arrays.toString(args));
  }

  static int run() throws Exception {
    var defaults = Options.parse(new String[] {"--benchmark-suite", "results"});
    check(
        defaults.headless && defaults.benchmarkProfile == BenchmarkWorkloads.Profile.QUICK,
        "suite has explicit headless mode and quick default profile");
    var smoke =
        Options.parse(
            new String[] {
              "--benchmark-suite",
              "results",
              "--benchmark-profile",
              "smoke",
              "--headless",
              "--shaders",
              "source"
            });
    check(
        smoke.benchmarkProfile == BenchmarkWorkloads.Profile.SMOKE
            && smoke.shaders.equals(Path.of("source")),
        "suite supports selected profile and recorded shader implementation");
    for (String[] override :
        new String[][] {
          {"--size", "320x200"},
          {"--frames", "64"},
          {"--preset", "scene.json"},
          {"--seed", "7"},
          {"--benchmark", "single.json"},
          {"--capture", "image.png"},
          {"--replay", "take.json"}
        }) rejects("--benchmark-suite", "results", override[0], override[1]);
    rejects("--benchmark-profile", "smoke");
    rejects("--benchmark", "single.json", "--frames", "30");
    rejects("--benchmark", "single.json", "--frames", "64", "--fps", "10");
    check(
        Options.parse(new String[] {"--benchmark", "single.json", "--frames", "64"}).frames == 64,
        "single-scene benchmark retains finite supported runs");
    Path root = Files.createTempDirectory(Path.of("build/reports"), "suite-destination-");
    try {
      Files.writeString(root.resolve("marker"), "existing result");
      boolean rejected = false;
      try {
        BenchmarkSuite.run(root, BenchmarkWorkloads.Profile.SMOKE, null);
      } catch (IllegalArgumentException expected) {
        rejected = true;
      }
      check(
          rejected && Files.readString(root.resolve("marker")).equals("existing result"),
          "existing suite destination rejected before graphics initialization");
    } finally {
      Files.delete(root.resolve("marker"));
      Files.delete(root);
    }
    return count;
  }
}
