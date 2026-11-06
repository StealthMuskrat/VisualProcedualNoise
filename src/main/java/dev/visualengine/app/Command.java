package dev.visualengine.app;

import java.util.*;
import java.util.function.Consumer;

public record Command(String type, Object value) {
  /** Coalesce adjacent edits, preserving action boundaries such as endpoint captures and undo. */
  public static void drain(Queue<Command> pending, Consumer<Command> apply) {
    Map<String, Command> edits = new LinkedHashMap<>();
    Command command;
    while ((command = pending.poll()) != null) {
      String key = command.type();
      if (key.equals("parameter")) key += ":" + ((Edit) command.value()).id();
      if (command.type().equals("effectValue")) key += ":" + ((EffectEdit) command.value()).id();
      if (Set.of("parameter", "effectValue", "reload", "discoveryScan", "discoveryThumbnails")
          .contains(command.type())) edits.put(key, command);
      else {
        edits.values().forEach(apply);
        edits.clear();
        apply.accept(command);
      }
    }
    edits.values().forEach(apply);
  }

  public record MorphLoad(dev.visualengine.model.PresetMorph.Slot slot, java.nio.file.Path path) {}

  public record MorphLoaded(
      dev.visualengine.model.PresetMorph.Slot slot,
      long generation,
      dev.visualengine.model.Settings settings) {}

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
