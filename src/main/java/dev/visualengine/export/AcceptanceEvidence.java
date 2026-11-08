package dev.visualengine.export;

import com.google.gson.*;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/**
 * Local archive identity and immutable evidence digests; absolute installation paths are omitted.
 */
public final class AcceptanceEvidence {
  private AcceptanceEvidence() {}

  public static String sha256(Path path) throws IOException {
    try {
      var digest = MessageDigest.getInstance("SHA-256");
      try (var input = Files.newInputStream(path)) {
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException error) {
      throw new AssertionError(error);
    }
  }

  private static JsonObject file(Path path, String name) throws IOException {
    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
      throw new IOException("Evidence must be a regular file: " + name);
    var item = new JsonObject();
    item.addProperty("path", name);
    item.addProperty("bytes", Files.size(path));
    item.addProperty("sha256", sha256(path));
    return item;
  }

  static JsonObject application(Path main) throws IOException {
    var result = new JsonObject();
    var files = new JsonArray();
    if (Files.isDirectory(main)) {
      result.addProperty("kind", "classes");
      result.add("archives", files);
      return result;
    }
    if (!Files.isRegularFile(main, LinkOption.NOFOLLOW_LINKS))
      throw new IOException("Application archive is unavailable");
    result.addProperty("kind", "jar");
    result.addProperty("mainArchive", main.getFileName().toString());
    try (var siblings = Files.list(main.toAbsolutePath().getParent())) {
      var archives =
          siblings
              .filter(p -> p.getFileName().toString().endsWith(".jar"))
              .sorted(Comparator.comparing(p -> p.getFileName().toString()))
              .toList();
      if (archives.isEmpty() || archives.size() > 64)
        throw new IOException("Expected 1..64 application/dependency archives");
      for (var path : archives) files.add(file(path, path.getFileName().toString()));
    }
    result.add("archives", files);
    result.addProperty("archiveSetSha256", BenchmarkWorkloads.hash(files));
    return result;
  }

  static JsonObject application() throws IOException {
    try {
      return application(
          Path.of(
              AcceptanceEvidence.class
                  .getProtectionDomain()
                  .getCodeSource()
                  .getLocation()
                  .toURI()));
    } catch (java.net.URISyntaxException | SecurityException | NullPointerException error) {
      throw new IOException("Application code location is unavailable", error);
    }
  }

  static JsonArray artifacts(Path root) throws IOException {
    var result = new JsonArray();
    try (var paths = Files.walk(root)) {
      for (var path : paths.filter(p -> !p.equals(root)).sorted().toList()) {
        if (Files.isSymbolicLink(path))
          throw new IOException("Evidence may not contain symbolic links");
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) continue;
        String relative = root.relativize(path).toString().replace(File.separatorChar, '/');
        if (relative.equals("manual-review.md") || relative.equals("results.json")) continue;
        result.add(file(path, relative));
      }
    }
    return result;
  }
}
