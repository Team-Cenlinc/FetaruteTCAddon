package org.fetarute.fetaruteTCAddon.dispatcher.runtime.control;

import java.time.Duration;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
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

  private static final int MAX_CACHE_SIZE = 4096;
  private static final long MIN_REFRESH_MILLIS = 1_000L;

  private final RailGraphPathFinder pathFinder;
  private volatile long refreshAfterMillis;
  private final Consumer<String> debugLogger;
  private final ConcurrentMap<DistanceKey, CacheEntry> cache = new ConcurrentHashMap<>();
  private final ConcurrentMap<DistanceKey, Boolean> refreshing = new ConcurrentHashMap<>();

  /**
   * @param pathFinder 最短路求解器
   * @param refreshAfter 刷新间隔（小于 1 秒会自动提升到 1 秒）
   * @param debugLogger 调试日志（可为空）
   */
  public ShortestPathDistanceCache(
      RailGraphPathFinder pathFinder, Duration refreshAfter, Consumer<String> debugLogger) {
    this.pathFinder = Objects.requireNonNull(pathFinder, "pathFinder");
    this.refreshAfterMillis = normalizeRefreshMillis(refreshAfter);
    this.debugLogger = debugLogger != null ? debugLogger : unused -> {};
  }

  /** 动态调整异步刷新间隔。 */
  public void setRefreshAfter(Duration refreshAfter) {
    this.refreshAfterMillis = normalizeRefreshMillis(refreshAfter);
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
    CacheEntry cached = cache.get(key);
    if (cached != null) {
      if (nowMs - cached.sampledAtMs() >= refreshAfterMillis) {
        refreshAsync(key, graph, from, to);
      }
      return cached.distance();
    }
    OptionalLong computed = computeDistance(graph, from, to);
    cache.put(key, new CacheEntry(computed, nowMs));
    pruneIfNeeded(nowMs);
    return computed;
  }

  private void refreshAsync(DistanceKey key, RailGraph graph, NodeId from, NodeId to) {
    if (refreshing.putIfAbsent(key, Boolean.TRUE) != null) {
      return;
    }
    CompletableFuture.runAsync(
        () -> {
          try {
            OptionalLong refreshed = computeDistance(graph, from, to);
            cache.put(key, new CacheEntry(refreshed, System.currentTimeMillis()));
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
    if (cache.size() <= MAX_CACHE_SIZE) {
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

  private record CacheEntry(OptionalLong distance, long sampledAtMs) {
    private CacheEntry {
      Objects.requireNonNull(distance, "distance");
    }
  }

  private static long normalizeRefreshMillis(Duration refreshAfter) {
    long configured =
        refreshAfter != null && !refreshAfter.isNegative() ? refreshAfter.toMillis() : 0L;
    return Math.max(MIN_REFRESH_MILLIS, configured);
  }
}
