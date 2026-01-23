package org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy;

import java.util.List;
import java.util.Optional;

/**
 * 调度占用/闭塞管理器：负责资源互斥（headway 行为由实现决定）。
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
   * 是否需要让行（优先级让行）。
   *
   * <p>默认实现不启用让行，由具体占用实现决定是否支持。
   */
  default boolean shouldYield(OccupancyRequest request) {
    return false;
  }
}
