package dev.visualengine.export;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/** Exception-safe publication of an image and its JSON sidecar on the existing file worker. */
final class OutputPair {
  @FunctionalInterface
  interface Encoder {
    void write(Path stagedImage) throws IOException;
  }

  @FunctionalInterface
  interface Move {
    void move(Path source, Path destination) throws IOException;
  }

  private OutputPair() {}

  static void write(Path image, String metadata, Encoder encoder) throws IOException {
    write(image, metadata, encoder, OutputPair::move);
  }

  // The move seam exercises real publication/rollback failures without filesystem permission
  // tricks.
  static void write(Path image, String metadata, Encoder encoder, Move mover) throws IOException {
    Path target = image.toAbsolutePath().normalize(), sidecar = Path.of(target + ".json");
    Files.createDirectories(target.getParent());
    regularOrAbsent(target);
    regularOrAbsent(sidecar);
    Path stage = Files.createTempDirectory(target.getParent(), ".visual-output-");
    boolean retain = false;
    Throwable failure = null;
    try {
      Path png = stage.resolve("image.png"), json = stage.resolve("metadata.json");
      encoder.write(png);
      if (!regularOrAbsent(png)) throw new IOException("Image encoder produced no file");
      Files.writeString(json, metadata);
      List<Path> destinations = List.of(target, sidecar), staged = List.of(png, json);
      List<Path> backups =
          List.of(stage.resolve("previous-image"), stage.resolve("previous-metadata"));
      boolean[] existed = new boolean[2];
      for (int i = 0; i < 2; i++) {
        existed[i] = regularOrAbsent(destinations.get(i));
        if (existed[i]) Files.copy(destinations.get(i), backups.get(i));
      }
      Files.writeString(
          stage.resolve("RECOVERY.txt"),
          "Image destination: "
              + target
              + "\nMetadata destination: "
              + sidecar
              + "\n"
              + "previous-image and previous-metadata are copies of files that existed before this"
              + " save.\n"
              + "An absent previous file means that destination did not exist.\n"
              + "Recovery is manual; this directory is never replayed automatically.\n");
      int attempted = -1;
      try {
        for (int i = 0; i < 2; i++) {
          attempted = i;
          mover.move(staged.get(i), destinations.get(i));
        }
      } catch (IOException | RuntimeException original) {
        List<Exception> restorationFailures = new ArrayList<>();
        for (int i = attempted; i >= 0; i--) {
          try {
            Path destination = destinations.get(i);
            regularOrAbsent(destination);
            if (existed[i]) {
              // Move a copy so that the recovery backup survives even a failed restore move.
              Path restore = stage.resolve("restore-" + i);
              Files.copy(backups.get(i), restore);
              mover.move(restore, destination);
            } else Files.deleteIfExists(destination);
          } catch (IOException | RuntimeException rollback) {
            restorationFailures.add(rollback);
          }
        }
        if (!restorationFailures.isEmpty()) {
          retain = true;
          IOException recovery =
              new IOException(
                  "Output publication failed; recovery files retained at " + stage, original);
          restorationFailures.forEach(recovery::addSuppressed);
          throw recovery;
        }
        throw original;
      }
    } catch (IOException | RuntimeException | Error e) {
      failure = e;
      throw e;
    } finally {
      if (!retain) {
        try {
          removeStage(stage);
        } catch (IOException cleanup) {
          if (failure != null) failure.addSuppressed(cleanup);
          else
            System.err.println(
                "Output files saved; temporary cleanup failed at "
                    + stage
                    + ": "
                    + cleanup.getMessage());
        }
      }
    }
  }

  private static boolean regularOrAbsent(Path path) throws IOException {
    try {
      var attributes =
          Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      if (!attributes.isRegularFile())
        throw new IOException("Output destination is not a regular file: " + path);
      return true;
    } catch (NoSuchFileException missing) {
      return false;
    }
  }

  static void move(Path source, Path destination) throws IOException {
    regularOrAbsent(destination);
    try {
      Files.move(
          source, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } catch (AtomicMoveNotSupportedException unsupported) {
      Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
    }
  }

  private static void removeStage(Path directory) throws IOException {
    try (var paths = Files.walk(directory)) {
      for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
    }
  }
}
