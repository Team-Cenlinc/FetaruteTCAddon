package org.fetarute.fetaruteTCAddon.dispatcher.eta.cache;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * ETA 结果缓存（TTL）。
 *
 * <p>目的：避免 HUD/内部占位符高频刷新造成重复计算。
 */
public final class EtaCache<K, V> {

  private final Duration ttl;
  private final ConcurrentMap<K, Entry<V>> map = new ConcurrentHashMap<>();

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
    map.put(key, new Entry<>(value, now != null ? now : Instant.now()));
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
