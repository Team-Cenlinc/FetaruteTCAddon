package org.fetarute.fetaruteTCAddon.dispatcher.runtime.control;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 控车诊断缓存：per-tick 缓存诊断数据，避免重复计算。
 *
 * <p>特性：
 *
 * <ul>
 *   <li>基于 TTL 的自动过期（默认 1 tick ≈ 50ms）
 *   <li>线程安全（ConcurrentHashMap）
 *   <li>按列车名索引
 * </ul>
 *
 * <p>使用场景：
 *
 * <ul>
 *   <li>同一 tick 内多次查询同一列车状态时复用缓存
 *   <li>{@code /fta train debug} 命令快速获取诊断数据
 *   <li>避免 SignalLookahead/MotionPlanner 重复计算
 * </ul>
 */
public final class ControlDiagnosticsCache {

  /**
   * 默认 TTL：约 2 个信号检查周期（1 秒）。
   *
   * <p>信号检查默认每 10 tick（500ms）执行一次，TTL 需要足够长以确保命令查询时数据仍有效。
   */
  private static final Duration DEFAULT_TTL = Duration.ofMillis(1000);

  private final Duration ttl;
  private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();

  public ControlDiagnosticsCache() {
    this(DEFAULT_TTL);
  }

  public ControlDiagnosticsCache(Duration ttl) {
    this.ttl = ttl != null ? ttl : DEFAULT_TTL;
  }

  /**
   * 获取缓存的诊断数据（如果未过期）。
   *
   * @param trainName 列车名
   * @param now 当前时间
   * @return 缓存的诊断数据，如果已过期或不存在则返回空
   */
  public Optional<ControlDiagnostics> get(String trainName, Instant now) {
    if (trainName == null || trainName.isBlank()) {
      return Optional.empty();
    }
    CacheEntry entry = cache.get(trainName);
    if (entry == null) {
      return Optional.empty();
    }
    Instant effectiveNow = now != null ? now : Instant.now();
    if (Duration.between(entry.cachedAt, effectiveNow).compareTo(ttl) > 0) {
      cache.remove(trainName);
      return Optional.empty();
    }
    return Optional.of(entry.diagnostics);
  }

  /**
   * 缓存诊断数据。
   *
   * @param diagnostics 诊断数据
   * @param now 缓存时间
   */
  public void put(ControlDiagnostics diagnostics, Instant now) {
    if (diagnostics == null || diagnostics.trainName() == null) {
      return;
    }
    Instant effectiveNow = now != null ? now : Instant.now();
    cache.put(diagnostics.trainName(), new CacheEntry(diagnostics, effectiveNow));
  }

  /**
   * 移除指定列车的缓存。
   *
   * @param trainName 列车名
   */
  public void remove(String trainName) {
    if (trainName != null) {
      cache.remove(trainName);
    }
  }

  /** 清空所有缓存。 */
  public void clear() {
    cache.clear();
  }

  /**
   * 获取所有缓存的诊断数据快照（用于调试）。
   *
   * @param now 当前时间（过期数据不包含）
   * @return 未过期的诊断数据映射
   */
  public Map<String, ControlDiagnostics> snapshot(Instant now) {
    Instant effectiveNow = now != null ? now : Instant.now();
    java.util.Map<String, ControlDiagnostics> result = new java.util.HashMap<>();
    cache.forEach(
        (name, entry) -> {
          if (Duration.between(entry.cachedAt, effectiveNow).compareTo(ttl) <= 0) {
            result.put(name, entry.diagnostics);
          }
        });
    return Map.copyOf(result);
  }

  /** 当前缓存条目数（包含可能过期的）。 */
  public int size() {
    return cache.size();
  }

  /** 清理过期条目（可定期调用以释放内存）。 */
  public void evictExpired(Instant now) {
    Instant effectiveNow = now != null ? now : Instant.now();
    cache
        .entrySet()
        .removeIf(e -> Duration.between(e.getValue().cachedAt, effectiveNow).compareTo(ttl) > 0);
  }

  private record CacheEntry(ControlDiagnostics diagnostics, Instant cachedAt) {}
}
