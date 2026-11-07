package dev.visualengine;

import dev.visualengine.platform.*;
import dev.visualengine.time.FramePacer;
import java.nio.file.*;

public final class PresentationChecks {
  private static int count;

  private static void check(boolean ok, String label) {
    count++;
    if (!ok) throw new AssertionError(label);
  }

  private static void rejects(Runnable action, String label) {
    try {
      action.run();
    } catch (IllegalArgumentException expected) {
      count++;
      return;
    }
    throw new AssertionError("Accepted " + label);
  }

  static int run() throws Exception {
    rejects(() -> new Presentation(true, -1), "negative presentation frame cap");
    rejects(() -> new Presentation(false, 241), "excess presentation frame cap");
    FramePacer pacer = new FramePacer();
    pacer.configure(60);
    check(pacer.waitNanos(0) == 0, "first presentation frame is immediately due");
    pacer.frameStarted(100);
    check(
        pacer.waitNanos(100) == 16_666_667 && pacer.waitNanos(101) == 16_666_666,
        "presentation cap rounds period up without overshooting requested rate");
    check(
        pacer.waitNanos(1000) > 0 && pacer.waitNanos(2000) > 0,
        "early event wakes do not consume or bypass the frame deadline");
    check(
        pacer.waitNanos(16_666_767) == 0 && pacer.waitNanos(100_000_000) == 0,
        "late frames render once without catch-up presentation bursts");
    pacer.frameStarted(100_000_000);
    check(pacer.waitNanos(100_000_001) == 16_666_666, "late presentation starts a fresh deadline");
    pacer.reset();
    check(
        pacer.waitNanos(100_000_002) == 0,
        "restore or explicit reset invalidates old presentation deadline");
    pacer.configure(0);
    pacer.frameStarted(0);
    check(pacer.waitNanos(1) == 0, "uncapped presentation imposes no wait");
    pacer.configure(1);
    pacer.frameStarted(Long.MAX_VALUE - 10);
    check(
        pacer.waitNanos(Long.MAX_VALUE) == 999_999_990
            && pacer.waitNanos(Long.MIN_VALUE + 999_999_989) == 0,
        "presentation deadline survives monotonic nanosecond counter wrap");
    rejects(() -> pacer.configure(241), "invalid pacing rate");
    Path root = Files.createTempDirectory(Path.of("build/reports"), "presentation-contract-");
    Path file = root.resolve("presentation.json");
    try {
      check(
          PresentationIO.load(file).equals(Presentation.DEFAULT),
          "missing preferences retain prior VSync-on uncapped desktop default");
      String legacy = "{\"schemaVersion\":1,\"vsync\":false,\"frameLimit\":60}";
      Files.writeString(file, legacy);
      check(
          PresentationIO.load(file).equals(new Presentation(false, 60, true))
              && Files.readString(file).equals(legacy),
          "v1 desktop preferences migrate with minimized suspension without rewriting on load");
      Presentation desired = new Presentation(false, 144, false);
      PresentationIO.save(file, desired);
      check(
          PresentationIO.load(file).equals(desired),
          "presentation preferences roundtrip independently of presets");
      Path blocked = Files.createDirectory(root.resolve("blocked.json"));
      Files.writeString(blocked.resolve("marker"), "keep");
      boolean failed = false;
      try {
        PresentationIO.save(blocked, desired);
      } catch (java.io.IOException expected) {
        failed = true;
      }
      check(
          failed && Files.readString(blocked.resolve("marker")).equals("keep"),
          "failed presentation save preserves existing destination");
      Files.delete(blocked.resolve("marker"));
      Files.delete(blocked);
      for (String invalid :
          new String[] {
            "{}",
            "{\"schemaVersion\":2,\"vsync\":true,\"frameLimit\":60}",
            "{\"schemaVersion\":3,\"vsync\":true,\"frameLimit\":60,\"suspendWhenMinimized\":true}",
            "{\"schemaVersion\":2,\"vsync\":true,\"frameLimit\":60,\"suspendWhenMinimized\":\"true\"}",
            "{\"schemaVersion\":2,\"vsync\":true,\"frameLimit\":60,\"suspendWhenMinimized\":1}",
            "{\"schemaVersion\":1,\"vsync\":true,\"frameLimit\":60,\"suspendWhenMinimized\":false}",
            "{\"schemaVersion\":1,\"vsync\":\"true\",\"frameLimit\":60}",
            "{\"schemaVersion\":1,\"vsync\":true,\"frameLimit\":60.5}",
            "{\"schemaVersion\":1,\"vsync\":true,\"frameLimit\":-1}",
            "x".repeat(16385)
          }) {
        Files.writeString(file, invalid);
        boolean rejected = false;
        try {
          PresentationIO.load(file);
        } catch (java.io.IOException expected) {
          rejected = true;
        }
        check(rejected, "malformed or oversized presentation preferences rejected");
      }
    } finally {
      Files.deleteIfExists(file);
      Files.delete(root);
    }
    return count;
  }
}
