package dev.visualengine.export;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Real filesystem publication, injected rename failures and retained recovery copies. */
public final class OutputPairChecks {
  private static int count;

  private static void check(boolean value, String message) {
    count++;
    if (!value) throw new AssertionError(message);
  }

  private static void rejects(Action action, String message) throws Exception {
    try {
      action.run();
    } catch (IOException expected) {
      count++;
      return;
    }
    throw new AssertionError(message);
  }

  @FunctionalInterface
  private interface Action {
    void run() throws Exception;
  }

  private static void fresh(Path image, Path metadata, boolean oldImage, boolean oldMetadata)
      throws IOException {
    Files.deleteIfExists(image);
    Files.deleteIfExists(metadata);
    if (oldImage) Files.writeString(image, "old image");
    if (oldMetadata) Files.writeString(metadata, "old metadata");
  }

  private static boolean unchanged(Path image, Path metadata, boolean oldImage, boolean oldMetadata)
      throws IOException {
    return (oldImage ? Files.readString(image).equals("old image") : !Files.exists(image))
        && (oldMetadata
            ? Files.readString(metadata).equals("old metadata")
            : !Files.exists(metadata));
  }

  private static boolean clean(Path parent) throws IOException {
    try (var paths = Files.list(parent)) {
      return paths.noneMatch(p -> p.getFileName().toString().startsWith(".visual-output-"));
    }
  }

  public static int run() throws Exception {
    Path root = Files.createTempDirectory("paired-output-");
    try {
      Path image = root.resolve("Été final.png"), metadata = Path.of(image + ".json");
      for (boolean oldImage : List.of(false, true))
        for (boolean oldMetadata : List.of(false, true)) {
          fresh(image, metadata, oldImage, oldMetadata);
          OutputPair.write(image, "new metadata", p -> Files.writeString(p, "new image"));
          check(
              Files.readString(image).equals("new image")
                  && Files.readString(metadata).equals("new metadata")
                  && clean(root),
              "successful paired publication replaces present/absent outputs and removes staging");
          for (int failedMove : List.of(1, 2))
            for (boolean afterMove : List.of(false, true)) {
              fresh(image, metadata, oldImage, oldMetadata);
              int[] calls = {0};
              rejects(
                  () ->
                      OutputPair.write(
                          image,
                          "new metadata",
                          p -> Files.writeString(p, "new image"),
                          (from, to) -> {
                            int call = ++calls[0];
                            if (call == failedMove && !afterMove)
                              throw new IOException("injected before move");
                            OutputPair.move(from, to);
                            if (call == failedMove && afterMove)
                              throw new IOException("injected after move");
                          }),
                  "Accepted failed publication");
              check(
                  unchanged(image, metadata, oldImage, oldMetadata) && clean(root),
                  "rename failure restores the complete prior pair, including absent destinations");
            }
        }
      fresh(image, metadata, true, true);
      rejects(
          () ->
              OutputPair.write(
                  image,
                  "new metadata",
                  p -> {
                    Files.writeString(p, "partial encoding");
                    throw new IOException("encoder failure");
                  }),
          "Accepted failed image encoding");
      check(
          unchanged(image, metadata, true, true) && clean(root),
          "encoding failure publishes neither file");
      Files.delete(metadata);
      Files.createDirectory(metadata);
      Files.writeString(metadata.resolve("marker"), "keep");
      rejects(
          () -> OutputPair.write(image, "new metadata", p -> Files.writeString(p, "new image")),
          "Replaced sidecar directory");
      check(
          Files.readString(image).equals("old image")
              && Files.readString(metadata.resolve("marker")).equals("keep")
              && clean(root),
          "non-file sidecar rejects before changing the image or directory");
      Files.delete(metadata.resolve("marker"));
      Files.delete(metadata);
      Path linked = root.resolve("linked.txt");
      Files.writeString(linked, "linked bytes");
      try {
        Files.createSymbolicLink(metadata, linked.getFileName());
        rejects(
            () -> OutputPair.write(image, "new metadata", p -> Files.writeString(p, "new image")),
            "Replaced sidecar link");
        check(
            Files.isSymbolicLink(metadata)
                && Files.readString(linked).equals("linked bytes")
                && Files.readString(image).equals("old image")
                && clean(root),
            "output validation preserves symbolic link and referent");
        Files.delete(metadata);
      } catch (UnsupportedOperationException | FileSystemException unavailable) {
        // Windows may lack the symlink privilege; all regular-file rollback cases remain required.
        if (Files.isSymbolicLink(metadata)) Files.delete(metadata);
      }
      fresh(image, metadata, true, true);
      int[] calls = {0};
      IOException recovery = null;
      try {
        OutputPair.write(
            image,
            "new metadata",
            p -> Files.writeString(p, "new image"),
            (from, to) -> {
              int call = ++calls[0];
              if (call == 3) throw new IOException("injected publication/restore failure");
              OutputPair.move(from, to);
              if (call == 2) throw new IOException("injected publication/restore failure");
            });
      } catch (IOException expected) {
        recovery = expected;
      }
      Path retained;
      try (var paths = Files.list(root)) {
        retained =
            paths
                .filter(p -> p.getFileName().toString().startsWith(".visual-output-"))
                .findFirst()
                .orElseThrow();
      }
      check(
          recovery != null
              && recovery.getMessage().contains(retained.toString())
              && recovery.getCause().getMessage().contains("injected")
              && recovery.getSuppressed().length == 1,
          "failed rollback reports retained recovery directory and original/restoration failures");
      check(
          Files.readString(retained.resolve("previous-image")).equals("old image")
              && Files.readString(retained.resolve("previous-metadata")).equals("old metadata")
              && Files.readString(retained.resolve("RECOVERY.txt")).contains(metadata.toString()),
          "rollback failure retains both original byte copies and their destinations");
      check(
          Files.readString(image).equals("old image")
              && Files.readString(metadata).equals("new metadata"),
          "rollback continues restoring the image after metadata restoration fails");
    } finally {
      try (var paths = Files.walk(root)) {
        for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
          Files.deleteIfExists(path);
      }
    }
    return count;
  }
}
