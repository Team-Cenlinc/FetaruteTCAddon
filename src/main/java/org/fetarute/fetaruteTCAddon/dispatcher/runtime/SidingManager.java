package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.fetarute.fetaruteTCAddon.company.model.Station;
import org.fetarute.fetaruteTCAddon.company.model.StationSidingPool;

/**
 * 管理车站存车线（Siding）的运行时占用状态。
 *
 * <p>负责分配存车线槽位，并跟踪哪些列车停在哪条存车线。
 */
public class SidingManager {

  private final Map<String, SidingReservation> reservations =
      new ConcurrentHashMap<>(); // trainId -> 预留信息

  public SidingManager() {}

  /**
   * 为列车预留一个存车线槽位。
   *
   * @param station 所在车站
   * @param trainId 列车 ID
   * @return 分配到的图节点 ID；若无可用槽位则返回空
   */
  public Optional<String> reserveSlot(Station station, String trainId) {
    if (station == null || trainId == null) {
      return Optional.empty();
    }

    // 若已预留过，则直接返回原结果
    SidingReservation existing = reservations.get(trainId);
    if (existing != null) {
      if (existing.stationId().equals(station.id().toString())) {
        return Optional.of(existing.nodeId());
      } else {
        // 已切换到其他车站：先释放旧预留再尝试新车站
        releaseSlot(trainId);
      }
    }

    for (StationSidingPool pool : station.sidingPools()) {
      long used = countUsedSlots(station.id().toString(), pool.poolId());
      if (used < pool.capacity()) {
        String nodeId = pickNode(pool, station.id().toString());
        if (nodeId != null) {
          reservations.put(
              trainId,
              new SidingReservation(station.id().toString(), pool.poolId(), nodeId, Instant.now()));
          return Optional.of(nodeId);
        }
      }
    }

    return Optional.empty();
  }

  private long countUsedSlots(String stationId, String poolId) {
    return reservations.values().stream()
        .filter(r -> r.stationId().equals(stationId) && r.poolId().equals(poolId))
        .count();
  }

  private String pickNode(StationSidingPool pool, String stationId) {
    List<String> nodes = pool.candidateNodes();
    if (nodes.isEmpty()) {
      return null;
    }

    // 优先选择未被占用的节点
    Set<String> usedNodes =
        reservations.values().stream()
            .filter(r -> r.stationId().equals(stationId) && r.poolId().equals(pool.poolId()))
            .map(SidingReservation::nodeId)
            .collect(Collectors.toSet());

    for (String node : nodes) {
      if (!usedNodes.contains(node)) {
        return node;
      }
    }

    // 若候选节点都已被使用但仍进入此分支，说明 capacity > nodes.size()（允许叠放/复用）
    // 当前实现：为保持简单，直接复用第一个节点；如有需要可再实现轮询等策略。
    return nodes.get(0);
  }

  public Optional<String> resolveFallback(Station station, String poolId) {
    if (station == null) {
      return Optional.empty();
    }
    for (StationSidingPool pool : station.sidingPools()) {
      if (pool.poolId().equals(poolId)) {
        // 暂未实现“满载回退”逻辑；reserveSlot 在满载时会返回空。
        // 调用方应自行处理回退策略（例如停站台/等待/改线等）。
        return Optional.empty();
      }
    }
    return Optional.empty();
  }

  public void releaseSlot(String trainId) {
    reservations.remove(trainId);
  }

  public record SidingReservation(
      String stationId, String poolId, String nodeId, Instant reservedAt) {}
}
