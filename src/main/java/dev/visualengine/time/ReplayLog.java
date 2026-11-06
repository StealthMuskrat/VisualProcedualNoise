package dev.visualengine.time;

import com.google.gson.JsonObject;
import dev.visualengine.model.Settings;
import dev.visualengine.presets.PresetIO;
import java.io.*;
import java.nio.file.*;
import java.util.*;

public final class ReplayLog {
  public record Event(
      long tick,
      String kind,
      double x,
      double y,
      double radius,
      double strength,
      JsonObject preset) {}

  public int replayVersion = 1, width, height;
  public JsonObject initialPreset;
  public List<Event> events = new ArrayList<>();
  public long endTick;
  private transient int cursor;

  public static ReplayLog begin(Settings initial, int width, int height) {
    ReplayLog log = new ReplayLog();
    log.initialPreset = PresetIO.document(initial, 0);
    log.width = width;
    log.height = height;
    return log;
  }

  public Settings initial() {
    return PresetIO.parse(initialPreset.toString());
  }

  public void recordControl(long tick, Settings settings) {
    add(new Event(tick, "controls", 0, 0, 0, 0, PresetIO.document(settings, tick)));
  }

  public void recordPointer(long tick, double x, double y, double radius, boolean brush) {
    add(new Event(tick, "pointer", x, y, radius, brush ? 1 : 0, null));
  }

  private void add(Event event) {
    if (events.size() >= 200000)
      throw new IllegalArgumentException("Replay reached 200000 events; start a new take");
    events.add(event);
    endTick = Math.max(endTick, event.tick());
  }

  public List<Event> at(long tick) {
    List<Event> result = new ArrayList<>();
    while (cursor < events.size() && events.get(cursor).tick() <= tick)
      result.add(events.get(cursor++));
    return result;
  }

  public int cursor() {
    return cursor;
  }

  public void restoreCursor(int position) {
    if (position < 0 || position > events.size())
      throw new IllegalArgumentException("Invalid checkpoint replay cursor");
    cursor = position;
  }

  public void rewind() {
    cursor = 0;
  }

  public void validate() {
    if (replayVersion != 1
        || initialPreset == null
        || events == null
        || events.size() > 200000
        || width < 32
        || height < 32
        || width > 8192
        || height > 8192
        || endTick < 0) throw new IllegalArgumentException("Invalid replay header");
    initial();
    long previous = 0;
    for (var e : events) {
      if (e.tick() < previous
          || e.tick() > endTick
          || !Double.isFinite(e.x())
          || !Double.isFinite(e.y())
          || !Double.isFinite(e.radius())
          || !Double.isFinite(e.strength())
          || Math.abs(e.x()) > 256
          || Math.abs(e.y()) > 256)
        throw new IllegalArgumentException("Invalid replay event order or pointer");
      previous = e.tick();
      if ("controls".equals(e.kind())) {
        if (e.preset() == null) throw new IllegalArgumentException("Missing control state");
        PresetIO.parse(e.preset().toString());
      } else if (!"pointer".equals(e.kind())
          || e.radius() < .02
          || e.radius() > .5
          || e.strength() < 0
          || e.strength() > 1) throw new IllegalArgumentException("Invalid replay event");
    }
  }

  public void save(Path path) throws IOException {
    validate();
    PresetIO.atomicWrite(path, PresetIO.JSON.toJson(this));
  }

  public static ReplayLog load(Path path) throws IOException {
    if (Files.size(path) > 64 * 1024 * 1024) throw new IOException("Replay exceeds 64 MiB");
    ReplayLog log = PresetIO.JSON.fromJson(Files.readString(path), ReplayLog.class);
    log.validate();
    return log;
  }
}
