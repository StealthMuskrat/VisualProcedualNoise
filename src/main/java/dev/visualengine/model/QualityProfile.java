package dev.visualengine.model;

import java.util.*;

/**
 * Explicit starting points. The resolved settings, rather than a mutable profile name, are saved.
 */
public enum QualityProfile {
  DRAFT("Draft", 128, 4096, 4, .5),
  BALANCED("Balanced", 256, 16384, 8, 1),
  DETAILED("Detailed", 512, 65536, 16, 1);
  public final int grid, particles, historyLayers;
  public final double displayScale;
  private final String label;

  QualityProfile(String label, int grid, int particles, int historyLayers, double displayScale) {
    this.label = label;
    this.grid = grid;
    this.particles = particles;
    this.historyLayers = historyLayers;
    this.displayScale = displayScale;
  }

  public Settings apply(Settings source) {
    Settings result = source.copy();
    result.grid = grid;
    result.particles = particles;
    result.historyLayers = historyLayers;
    result.displayScale = displayScale;
    result.validate();
    return result;
  }

  public boolean resets(Settings source) {
    return source.grid != grid
        || source.particles != particles
        || source.historyLayers != historyLayers;
  }

  public static Optional<QualityProfile> matching(Settings settings) {
    return Arrays.stream(values())
        .filter(p -> !p.resets(settings) && p.displayScale == settings.displayScale)
        .findFirst();
  }

  public String describe() {
    return String.format(
        Locale.ROOT,
        "%d×%d grid · %,d particles · %d history layers · %.0f%% display",
        grid,
        grid,
        particles,
        historyLayers,
        displayScale * 100);
  }

  @Override
  public String toString() {
    return label;
  }
}
