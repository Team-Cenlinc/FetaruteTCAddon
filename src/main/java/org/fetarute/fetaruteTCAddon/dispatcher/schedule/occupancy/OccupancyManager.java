package org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 调度占用/闭塞管理器：负责资源互斥与 headway 约束。
 *
 * <p>运行时层通过 {@link OccupancyRequest} 申请占用，调度层返回可进入时间与信号许可。
 */
public interface OccupancyManager {

  OccupancyDecision canEnter(OccupancyRequest request);

  /** 尝试占用资源；若不可进入则返回阻塞决策。 */
  OccupancyDecision acquire(OccupancyRequest request);

  /** 查询某个资源的当前占用记录。 */
  Optional<OccupancyClaim> getClaim(OccupancyResource resource);

  /** 获取占用快照（只读）。 */
  List<OccupancyClaim> snapshotClaims();

  /** 按列车名称释放所有占用记录。 */
  int releaseByTrain(String trainName);

  /** 释放单个资源占用（可选校验列车名称）。 */
  boolean releaseResource(OccupancyResource resource, Optional<String> trainName);

  /**
   * 更新单个资源的 releaseAt（可选校验列车名称）。
   *
   * <p>用于事件驱动的精确释放或提前/延后释放。
   */
  boolean updateReleaseAt(
      OccupancyResource resource, Instant releaseAt, Optional<String> trainName);

  /** 批量更新某列车占用的 releaseAt。 */
  int updateReleaseAtByTrain(String trainName, Instant releaseAt);

  /** 清理超时占用记录（releaseAt + headway 到期）。 */
  int cleanupExpired(Instant now);
}
