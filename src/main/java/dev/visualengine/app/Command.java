package dev.visualengine.app;

public record Command(String type, Object value) {
  public record Edit(String id, double value) {}
}
