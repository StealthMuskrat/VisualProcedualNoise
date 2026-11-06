package dev.visualengine.app;

public record Command(String type, Object value) {
  public record Still(java.nio.file.Path path, dev.visualengine.export.CaptureSize size) {}

  public record Edit(String id, double value) {}

  public record EffectEdit(String id, java.util.List<Double> value) {
    public EffectEdit {
      value = java.util.List.copyOf(value);
    }
  }
}
