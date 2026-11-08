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
    var reader = Options.parse(new String[] {"--verify-acceptance", "copied evidence"});
    check(
        reader.headless
            && reader.verifyAcceptance.equals(Path.of("copied evidence"))
            && reader.acceptanceManifestSha256 == null
            && reader.acceptanceRun == null,
        "read-only verification needs no GPU/run options");
    var pinned =
        Options.parse(
            new String[] {
              "--verify-acceptance", "copied", "--acceptance-manifest-sha256", "A".repeat(64)
            });
    check(pinned.acceptanceManifestSha256.equals("a".repeat(64)), "trusted digest is normalized");
    rejects("--acceptance-manifest-sha256", "a".repeat(64));
    rejects("--acceptance-run", "results", "--acceptance-manifest-sha256", "a".repeat(64));
    rejects("--verify-acceptance", "copied", "--acceptance-manifest-sha256", "invalid");
    for (String[] override :
        new String[][] {
          {"--acceptance-run", "new"}, {"--acceptance-profile", "smoke"},
          {"--acceptance-environment", "hosted"}, {"--benchmark-suite", "new"},
          {"--diagnose-gpu", "gpu.json"}, {"--preset", "scene.json"},
          {"--seed", "7"}, {"--shaders", "modified"}
        }) rejects("--verify-acceptance", "copied", override[0], override[1]);
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
