package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteId;

/**
 * 路线列车位置追踪器：维护每条路线上列车的 routeIndex 位置。
 *
 * <p>用于快速查询"前方最近的列车"，支持跟车信号计算。
 *
 * <p>数据结构：
 *
 * <ul>
 *   <li>{@code routePositions}: RouteId -> TreeMap(index -> trainName)，按 index 升序
 *   <li>{@code trainRoutes}: trainName -> RouteId，用于快速定位列车所在路线
 * </ul>
 */
public final class RouteTrainTracker {

  /** 路线上的列车位置：routeId -> TreeMap(index -> trainName)。 */
  private final ConcurrentMap<RouteId, NavigableMap<Integer, String>> routePositions =
      new ConcurrentHashMap<>();

  /** 列车所在路线：trainName(lowercase) -> RouteId。 */
  private final ConcurrentMap<String, RouteId> trainRoutes = new ConcurrentHashMap<>();

  /** 列车当前 index：trainName(lowercase) -> index。 */
  private final ConcurrentMap<String, Integer> trainIndices = new ConcurrentHashMap<>();

  /**
   * 注册或更新列车位置。
   *
   * @param routeId 路线 ID
   * @param trainName 列车名
   * @param index 当前 routeIndex
   */
  public void update(RouteId routeId, String trainName, int index) {
    if (routeId == null || trainName == null || trainName.isBlank()) {
      return;
    }
    String key = trainName.toLowerCase(java.util.Locale.ROOT);

    // 检查是否换线
    RouteId oldRouteId = trainRoutes.get(key);
    Integer oldIndex = trainIndices.get(key);
    if (oldRouteId != null && !oldRouteId.equals(routeId)) {
      // 从旧路线移除
      removeFromRoute(oldRouteId, key, oldIndex);
    } else if (oldRouteId != null && oldIndex != null && oldIndex != index) {
      // 同一路线但 index 变化，需要更新
      removeFromRoute(oldRouteId, key, oldIndex);
    }

    // 更新到新位置
    trainRoutes.put(key, routeId);
    trainIndices.put(key, index);
    NavigableMap<Integer, String> positions =
        routePositions.computeIfAbsent(
            routeId, unused -> Collections.synchronizedNavigableMap(new TreeMap<>()));
    positions.put(index, trainName);
  }

  /**
   * 移除列车。
   *
   * @param trainName 列车名
   */
  public void remove(String trainName) {
    if (trainName == null || trainName.isBlank()) {
      return;
    }
    String key = trainName.toLowerCase(java.util.Locale.ROOT);
    RouteId routeId = trainRoutes.remove(key);
    Integer index = trainIndices.remove(key);
    if (routeId != null) {
      removeFromRoute(routeId, key, index);
    }
  }

  private void removeFromRoute(RouteId routeId, String trainKey, Integer index) {
    NavigableMap<Integer, String> positions = routePositions.get(routeId);
    if (positions == null) {
      return;
    }
    if (index != null) {
      // 精确移除
      positions.remove(index, trainKey);
    } else {
      // 遍历移除
      positions.values().removeIf(name -> trainKey.equalsIgnoreCase(name));
    }
  }

  /**
   * 查找前方最近的列车。
   *
   * @param routeId 路线 ID
   * @param currentIndex 当前 routeIndex
   * @param trainName 当前列车名（用于排除自身）
   * @return 前方最近列车的 (trainName, index)，如果没有则返回 empty
   */
  public Optional<TrainPosition> findNearestAhead(
      RouteId routeId, int currentIndex, String trainName) {
    if (routeId == null || trainName == null) {
      return Optional.empty();
    }
    NavigableMap<Integer, String> positions = routePositions.get(routeId);
    if (positions == null || positions.isEmpty()) {
      return Optional.empty();
    }
    String selfKey = trainName.toLowerCase(java.util.Locale.ROOT);

    // 查找 index > currentIndex 的最小值
    Map.Entry<Integer, String> entry = positions.higherEntry(currentIndex);
    while (entry != null) {
      if (!selfKey.equalsIgnoreCase(entry.getValue())) {
        return Optional.of(new TrainPosition(entry.getValue(), entry.getKey()));
      }
      entry = positions.higherEntry(entry.getKey());
    }
    return Optional.empty();
  }

  /**
   * 查找前方指定范围内的所有列车。
   *
   * @param routeId 路线 ID
   * @param currentIndex 当前 routeIndex
   * @param maxEdges 最大扫描边数（即 index 差值）
   * @param trainName 当前列车名（用于排除自身）
   * @return 前方列车列表，按 index 升序
   */
  public List<TrainPosition> findTrainsAhead(
      RouteId routeId, int currentIndex, int maxEdges, String trainName) {
    if (routeId == null || trainName == null || maxEdges <= 0) {
      return List.of();
    }
    NavigableMap<Integer, String> positions = routePositions.get(routeId);
    if (positions == null || positions.isEmpty()) {
      return List.of();
    }
    String selfKey = trainName.toLowerCase(java.util.Locale.ROOT);
    int maxIndex = currentIndex + maxEdges;

    List<TrainPosition> result = new ArrayList<>();
    NavigableMap<Integer, String> subMap = positions.subMap(currentIndex + 1, true, maxIndex, true);
    for (Map.Entry<Integer, String> entry : subMap.entrySet()) {
      if (!selfKey.equalsIgnoreCase(entry.getValue())) {
        result.add(new TrainPosition(entry.getValue(), entry.getKey()));
      }
    }
    return result;
  }

  /**
   * 获取列车当前位置。
   *
   * @param trainName 列车名
   * @return 列车位置，如果未追踪则返回 empty
   */
  public Optional<TrainPosition> getPosition(String trainName) {
    if (trainName == null || trainName.isBlank()) {
      return Optional.empty();
    }
    String key = trainName.toLowerCase(java.util.Locale.ROOT);
    Integer index = trainIndices.get(key);
    if (index == null) {
      return Optional.empty();
    }
    return Optional.of(new TrainPosition(trainName, index));
  }

  /**
   * 获取列车所在路线。
   *
   * @param trainName 列车名
   * @return 路线 ID，如果未追踪则返回 empty
   */
  public Optional<RouteId> getRoute(String trainName) {
    if (trainName == null || trainName.isBlank()) {
      return Optional.empty();
    }
    return Optional.ofNullable(trainRoutes.get(trainName.toLowerCase(java.util.Locale.ROOT)));
  }

  /** 列车位置记录。 */
  public record TrainPosition(String trainName, int index) {
    public TrainPosition {
      Objects.requireNonNull(trainName, "trainName");
    }
  }
}
