package dev.visualengine;

import dev.visualengine.app.Options;
import dev.visualengine.export.*;
import java.nio.file.Path;

public final class AcceptanceCliChecks {
  private static int count;

  private static void check(boolean ok, String text) {
    count++;
    if (!ok) throw new AssertionError(text);
  }

  private static void rejects(String... args) {
    try {
      Options.parse(args);
    } catch (IllegalArgumentException expected) {
      count++;
      return;
    }
    throw new AssertionError("Accepted invalid acceptance options");
  }

  public static int run() {
    count = 0;
    var defaults = Options.parse(new String[] {"--acceptance-run", "results"});
    check(
        defaults.headless
            && defaults.acceptanceRun.equals(Path.of("results"))
            && defaults.acceptanceProfile == BenchmarkWorkloads.Profile.SMOKE
            && defaults.acceptanceEnvironment == AcceptanceRun.Environment.UNSPECIFIED,
        "acceptance defaults are pinned smoke and an unverified environment");
    var physical =
        Options.parse(
            new String[] {
              "--acceptance-run",
              "results",
              "--acceptance-profile",
              "fullhd",
              "--acceptance-environment",
              "physical"
            });
    check(
        physical.acceptanceProfile == BenchmarkWorkloads.Profile.FULLHD
            && physical.acceptanceEnvironment == AcceptanceRun.Environment.PHYSICAL,
        "physical declaration and fullhd profile are explicit");
    for (String[] unsupported :
        new String[][] {
          {"--preset", "scene.json"},
          {"--shaders", "modified"},
          {"--size", "1920x1080"},
          {"--frames", "30"},
          {"--seed", "7"},
          {"--diagnose-gpu", "gpu.json"},
          {"--benchmark-suite", "suite"},
          {"--benchmark-profile", "smoke"},
          {"--vsync", "off"},
          {"--shader-cache", "off"}
        }) rejects("--acceptance-run", "results", unsupported[0], unsupported[1]);
    rejects("--acceptance-profile", "smoke");
    rejects("--acceptance-environment", "hosted");
    rejects("--acceptance-run", "results", "--acceptance-profile", "ultra");
    rejects("--acceptance-run", "results", "--acceptance-environment", "verified");
    rejects("--diagnose-gpu", "gpu.json", "--acceptance-profile", "smoke");
    check(
        Options.parse(new String[] {"--benchmark-suite", "suite", "--benchmark-profile", "fullhd"})
                .benchmarkProfile
            == BenchmarkWorkloads.Profile.FULLHD,
        "fullhd also runs through the ordinary benchmark CLI");
    return count;
  }
}
