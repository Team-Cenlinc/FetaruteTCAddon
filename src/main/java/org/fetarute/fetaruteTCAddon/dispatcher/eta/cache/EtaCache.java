package org.fetarute.fetaruteTCAddon.dispatcher.eta.cache;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ETA 结果缓存（TTL）。
 *
 * <p>目的：避免 HUD/内部占位符高频刷新造成重复计算。
 *
 * <p>过期条目在读到时删除，并且每写入 {@link #PURGE_EVERY} 次整体清一遍：列车销毁、换交路后旧的键再也不会被读到，不清就一直留着。
 */
public final class EtaCache<K, V> {

  /** 每写入多少次清一遍过期条目。 */
  static final int PURGE_EVERY = 256;

  private final Duration ttl;
  private final ConcurrentMap<K, Entry<V>> map = new ConcurrentHashMap<>();
  private final AtomicInteger puts = new AtomicInteger();

  public EtaCache(Duration ttl) {
    this.ttl = Objects.requireNonNull(ttl, "ttl");
  }

  public Optional<V> getIfFresh(K key, Instant now) {
    if (key == null) {
      return Optional.empty();
    }
    Entry<V> e = map.get(key);
    if (e == null) {
      return Optional.empty();
    }
    Instant t = now != null ? now : Instant.now();
    if (Duration.between(e.createdAt, t).compareTo(ttl) > 0) {
      map.remove(key);
      return Optional.empty();
    }
    return Optional.ofNullable(e.value);
  }

  public void put(K key, V value, Instant now) {
    if (key == null) {
      return;
    }
    Instant at = now != null ? now : Instant.now();
    map.put(key, new Entry<>(value, at));
    if (puts.incrementAndGet() % PURGE_EVERY == 0) {
      map.values().removeIf(entry -> Duration.between(entry.createdAt, at).compareTo(ttl) > 0);
    }
  }

  /** 当前条目数（含尚未清掉的过期条目）。 */
  public int size() {
    return map.size();
  }

  /** 删除特定 key 的缓存。 */
  public void invalidate(K key) {
    if (key != null) {
      map.remove(key);
    }
  }

  /** 删除所有 key.toString() 以指定前缀开头的缓存。 */
  public void invalidateByPrefix(String prefix) {
    if (prefix == null || prefix.isBlank()) {
      return;
    }
    map.keySet().removeIf(key -> key != null && key.toString().startsWith(prefix));
  }

  public Map<K, V> snapshotValues() {
    java.util.Map<K, V> out = new java.util.HashMap<>();
    for (var e : map.entrySet()) {
      out.put(e.getKey(), e.getValue().value);
    }
    return java.util.Map.copyOf(out);
  }

  private static final class Entry<V> {
    private final V value;
    private final Instant createdAt;

    private Entry(V value, Instant createdAt) {
      this.value = value;
      this.createdAt = createdAt;
    }
  }
}
