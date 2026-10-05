package org.fetarute.fetaruteTCAddon.dispatcher.runtime.control;

import java.time.Duration;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.query.RailGraphPathFinder;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;

/**
 * 最短路距离缓存（含异步刷新）。
 *
 * <p>主线程命中缓存时直接返回；缓存过期后在后台刷新，避免在道岔密集区连续发车时反复同步跑最短路。
 */
public final class ShortestPathDistanceCache {

  private static final int DEFAULT_MAX_CACHE_SIZE = 4096;
  private static final long MIN_REFRESH_MILLIS = 1_000L;

  private final RailGraphPathFinder pathFinder;
  private volatile long refreshAfterMillis;
  private volatile int maxCacheSize;
  private final Consumer<String> debugLogger;
  private final ConcurrentMap<DistanceKey, CacheEntry> cache = new ConcurrentHashMap<>();
  private final ConcurrentMap<DistanceKey, Boolean> refreshing = new ConcurrentHashMap<>();
  private volatile java.util.function.LongSupplier graphVersion = () -> 0L;
  private final LongAdder cacheHits = new LongAdder();
  private final LongAdder cacheMisses = new LongAdder();

  /**
   * @param pathFinder 最短路求解器
   * @param refreshAfter 刷新间隔（小于 1 秒会自动提升到 1 秒）
   * @param debugLogger 调试日志（可为空）
   */
  public ShortestPathDistanceCache(
      RailGraphPathFinder pathFinder, Duration refreshAfter, Consumer<String> debugLogger) {
    this(pathFinder, refreshAfter, DEFAULT_MAX_CACHE_SIZE, debugLogger);
  }

  /**
   * @param pathFinder 最短路求解器
   * @param refreshAfter 刷新间隔（小于 1 秒会自动提升到 1 秒）
   * @param maxCacheSize 最大缓存项数量（小于 1 时回退默认值）
   * @param debugLogger 调试日志（可为空）
   */
  public ShortestPathDistanceCache(
      RailGraphPathFinder pathFinder,
      Duration refreshAfter,
      int maxCacheSize,
      Consumer<String> debugLogger) {
    this.pathFinder = Objects.requireNonNull(pathFinder, "pathFinder");
    this.refreshAfterMillis = normalizeRefreshMillis(refreshAfter);
    this.maxCacheSize = normalizeMaxCacheSize(maxCacheSize);
    this.debugLogger = debugLogger != null ? debugLogger : unused -> {};
  }

  /**
   * 图快照版本：缓存键只有起止节点，换图后旧图上算的距离（含"不可达"）必须作废，不能等过期再异步刷新。
   *
   * @param graphVersion 每次图快照切换都会变的版本号
   */
  public void setGraphVersion(java.util.function.LongSupplier graphVersion) {
    this.graphVersion = Objects.requireNonNull(graphVersion, "graphVersion");
  }

  /** 动态调整异步刷新间隔。 */
  public void setRefreshAfter(Duration refreshAfter) {
    this.refreshAfterMillis = normalizeRefreshMillis(refreshAfter);
  }

  /** 动态调整最大缓存项数量。 */
  public void setMaxCacheSize(int maxCacheSize) {
    this.maxCacheSize = normalizeMaxCacheSize(maxCacheSize);
    pruneIfNeeded(System.currentTimeMillis());
  }

  /** 返回缓存命中统计。 */
  public Stats stats() {
    return new Stats(cacheHits.sum(), cacheMisses.sum(), cache.size(), refreshing.size());
  }

  /**
   * 解析两节点最短距离。
   *
   * <p>行为：
   *
   * <ul>
   *   <li>缓存命中：立即返回；
   *   <li>命中但过期：先返回旧值，再异步刷新；
   *   <li>未命中：同步计算并写入缓存（保证首帧可用）。
   * </ul>
   */
  public OptionalLong resolve(RailGraph graph, NodeId from, NodeId to) {
    if (graph == null || from == null || to == null) {
      return OptionalLong.empty();
    }
    DistanceKey key = new DistanceKey(from.value(), to.value());
    long nowMs = System.currentTimeMillis();
    long version = graphVersion.getAsLong();
    CacheEntry cached = cache.get(key);
    if (cached != null && cached.graphVersion() == version) {
      cacheHits.increment();
      if (nowMs - cached.sampledAtMs() >= refreshAfterMillis) {
        refreshAsync(key, graph, from, to, version);
      }
      return cached.distance();
    }
    cacheMisses.increment();
    OptionalLong computed = computeDistance(graph, from, to);
    cache.put(key, new CacheEntry(computed, nowMs, version));
    pruneIfNeeded(nowMs);
    return computed;
  }

  private void refreshAsync(
      DistanceKey key, RailGraph graph, NodeId from, NodeId to, long version) {
    if (refreshing.putIfAbsent(key, Boolean.TRUE) != null) {
      return;
    }
    CompletableFuture.runAsync(
        () -> {
          try {
            OptionalLong refreshed = computeDistance(graph, from, to);
            // 带上算它时的图版本：刷新期间换了图，这条结果下次读到时按未命中处理。
            cache.put(key, new CacheEntry(refreshed, System.currentTimeMillis(), version));
          } catch (RuntimeException ex) {
            debugLogger.accept(
                "最短路异步刷新失败: from="
                    + from.value()
                    + " to="
                    + to.value()
                    + " error="
                    + ex.getClass().getSimpleName());
          } finally {
            refreshing.remove(key);
          }
        });
  }

  private OptionalLong computeDistance(RailGraph graph, NodeId from, NodeId to) {
    return pathFinder
        .shortestPath(graph, from, to, RailGraphPathFinder.Options.shortestDistance())
        .map(path -> OptionalLong.of(path.totalLengthBlocks()))
        .orElse(OptionalLong.empty());
  }

  private void pruneIfNeeded(long nowMs) {
    if (cache.size() <= maxCacheSize) {
      return;
    }
    long expireBefore = nowMs - refreshAfterMillis * 4L;
    cache.entrySet().removeIf(entry -> entry.getValue().sampledAtMs() < expireBefore);
  }

  private record DistanceKey(String from, String to) {
    private DistanceKey {
      Objects.requireNonNull(from, "from");
      Objects.requireNonNull(to, "to");
    }
  }

  private record CacheEntry(OptionalLong distance, long sampledAtMs, long graphVersion) {
    private CacheEntry {
      Objects.requireNonNull(distance, "distance");
    }
  }

  private static long normalizeRefreshMillis(Duration refreshAfter) {
    long configured =
        refreshAfter != null && !refreshAfter.isNegative() ? refreshAfter.toMillis() : 0L;
    return Math.max(MIN_REFRESH_MILLIS, configured);
  }

  private static int normalizeMaxCacheSize(int maxCacheSize) {
    return maxCacheSize > 0 ? maxCacheSize : DEFAULT_MAX_CACHE_SIZE;
  }

  /** 缓存运行统计。 */
  public record Stats(long hits, long misses, int size, int refreshing) {}
}
