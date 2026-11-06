package dev.visualengine;

import dev.visualengine.app.Options;
import dev.visualengine.gpu.Budget;
import dev.visualengine.model.*;
import dev.visualengine.presets.PresetIO;
import java.util.*;

public final class QualityChecks {
  private static int count;

  private static void check(boolean condition, String message) {
    count++;
    if (!condition) throw new AssertionError(message);
  }

  public static int run() {
    Settings original = new Settings();
    original.seed = Long.MIN_VALUE;
    original.set("warp", 1.1);
    original.locks.add("warp");
    original.installEffectControls(List.of(AuthoringChecks.scalar()), Map.of());
    original.graph = AuthoringChecks.groupGraph();
    String before = PresetIO.JSON.toJson(PresetIO.document(original, 0));
    long last = 0;
    for (var quality : QualityProfile.values()) {
      Settings s = quality.apply(original);
      check(
          s.seed == original.seed
              && s.get("warp") == 1.1
              && s.locks.equals(original.locks)
              && s.effectControls.equals(original.effectControls)
              && s.graph.groups.equals(original.graph.groups),
          "quality preserves creative controls, seed and dependencies: " + quality);
      long bytes = Budget.persistent(s);
      check(
          bytes > last && bytes + Budget.display(1920, 1080) < Budget.LIMIT,
          "ordered profiles fit the declared budget at 1080p: " + quality);
      last = bytes;
      Settings loaded = PresetIO.parse(PresetIO.JSON.toJson(PresetIO.document(s, 0)));
      check(
          QualityProfile.matching(loaded).orElseThrow() == quality,
          "resolved quality survives preset: " + quality);
    }
    check(
        before.equals(PresetIO.JSON.toJson(PresetIO.document(original, 0))),
        "quality candidate leaves original untouched");
    Settings scaled = QualityProfile.DRAFT.apply(original);
    scaled.displayScale = 1;
    check(
        !QualityProfile.DRAFT.resets(scaled) && QualityProfile.matching(scaled).isEmpty(),
        "display scale alone preserves simulation and reports custom quality");
    check(QualityProfile.DETAILED.resets(scaled), "layout changes explicitly require reset");
    check(
        Options.parse(new String[] {"--quality", "draft", "--grid", "65"}).grid == 65,
        "CLI retains explicit grid override");
    boolean rejected = false;
    try {
      Options.parse(new String[] {"--checkpoint", "study.vstate", "--quality", "draft"});
    } catch (IllegalArgumentException e) {
      rejected = true;
    }
    check(rejected, "checkpoint rejects quality override");
    rejected = false;
    try {
      Options.parse(new String[] {"--replay", "take.json", "--quality", "draft"});
    } catch (IllegalArgumentException e) {
      rejected = true;
    }
    check(rejected, "recorded quality cannot be silently overridden or ignored");
    return count;
  }
}
