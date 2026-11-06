package dev.visualengine.app;

public record Command(String type, Object value) {
  public record History(long generation) {}

  public record Thumbnails(long generation, java.util.List<String> ids) {
    public Thumbnails {
      ids = java.util.List.copyOf(ids);
    }
  }

  public record DiscoveryBranch(
      String id,
      dev.visualengine.presets.DiscoveryStore.Variation variation,
      boolean lockComposition) {}

  public record DiscoveryAnnotation(String id, String label, boolean favorite) {}

  public record DiscoveryActivation(String id, dev.visualengine.model.Settings settings) {}

  public record Still(java.nio.file.Path path, dev.visualengine.export.CaptureSize size) {}

  public record MapExport(
      java.nio.file.Path path,
      dev.visualengine.export.TextureMap.Kind kind,
      dev.visualengine.export.CaptureSize size,
      dev.visualengine.export.TextureMap.Range range) {}

  public record Edit(String id, double value) {}

  public record EffectEdit(String id, java.util.List<Double> value) {
    public EffectEdit {
      value = java.util.List.copyOf(value);
    }
  }
}
