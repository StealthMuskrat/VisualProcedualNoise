package dev.visualengine.gpu;

import java.util.*;
import java.util.function.*;

/** Owner-thread LRU with one staged candidate. Only a committed selection changes residency. */
public final class VariantCache<T> implements AutoCloseable {
  public record Stats(
      int entries,
      long sourceBytes,
      int maximumEntries,
      long maximumSourceBytes,
      long hits,
      long misses,
      long failures,
      long evictions,
      String activeKey) {}

  private record Entry<T>(String key, long weight, T value) {}

  private final int maximumEntries;
  private final long maximumBytes;
  private final Consumer<T> dispose;
  private final Thread owner = Thread.currentThread();
  private final LinkedHashMap<String, Entry<T>> entries = new LinkedHashMap<>();
  private long bytes, hits, misses, failures, evictions;
  private String active;
  private boolean closed;
  private Selection pending;

  public VariantCache(int maximumEntries, long maximumBytes, Consumer<T> dispose) {
    if (maximumEntries < 1 || maximumBytes < 1)
      throw new IllegalArgumentException("Invalid variant-cache bounds");
    this.maximumEntries = maximumEntries;
    this.maximumBytes = maximumBytes;
    this.dispose = Objects.requireNonNull(dispose);
  }

  public Selection prepare(String key, long weight, Supplier<T> create) {
    check();
    if (pending != null) throw new IllegalStateException("A material candidate is already staged");
    if (key == null || key.isEmpty() || weight < 1 || weight > maximumBytes)
      throw new IllegalArgumentException("Material source exceeds the variant-cache budget");
    Entry<T> entry = entries.get(key);
    boolean retained = entry != null;
    if (retained) {
      if (entry.weight() != weight)
        throw new IllegalArgumentException("Variant key has inconsistent source size");
      hits++;
    } else {
      misses++;
      try {
        entry = new Entry<>(key, weight, Objects.requireNonNull(create.get()));
      } catch (RuntimeException | Error e) {
        failures++;
        throw e;
      }
    }
    pending = new Selection(entry, retained);
    return pending;
  }

  public final class Selection implements AutoCloseable {
    private final Entry<T> entry;
    private final boolean retained;
    private boolean done;

    private Selection(Entry<T> entry, boolean retained) {
      this.entry = entry;
      this.retained = retained;
    }

    public T value() {
      checkSelection();
      return entry.value();
    }

    public T commit() {
      checkSelection();
      if (!retained) bytes += entry.weight();
      entries.remove(entry.key());
      entries.put(entry.key(), entry);
      active = entry.key();
      done = true;
      pending = null;
      var iterator = entries.entrySet().iterator();
      while ((entries.size() > maximumEntries || bytes > maximumBytes) && iterator.hasNext()) {
        var old = iterator.next().getValue();
        if (old.key().equals(active)) continue;
        iterator.remove();
        bytes -= old.weight();
        evictions++;
        dispose.accept(old.value());
      }
      return entry.value();
    }

    private void checkSelection() {
      check();
      if (done || pending != this)
        throw new IllegalStateException("Material selection is no longer staged");
    }

    @Override
    public void close() {
      checkOwner();
      if (done) return;
      done = true;
      if (pending == this) pending = null;
      if (!retained) dispose.accept(entry.value());
    }
  }

  public Stats stats() {
    checkOwner();
    return new Stats(
        entries.size(),
        bytes,
        maximumEntries,
        maximumBytes,
        hits,
        misses,
        failures,
        evictions,
        active);
  }

  private void checkOwner() {
    if (Thread.currentThread() != owner)
      throw new IllegalStateException("Material variants require the owner thread");
  }

  private void check() {
    checkOwner();
    if (closed) throw new IllegalStateException("Material cache is closed");
  }

  @Override
  public void close() {
    checkOwner();
    if (closed) return;
    if (pending != null) pending.close();
    closed = true;
    entries.values().forEach(e -> dispose.accept(e.value()));
    entries.clear();
    bytes = 0;
    active = null;
  }
}
