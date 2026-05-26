package org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy;

import java.util.List;
import java.util.Optional;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;

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
   * 只释放指定角色的短生命周期 claim。
   *
   * <p>默认实现不支持角色级释放。Smart Dispatcher rollback 必须使用支持该接口的实现，避免误清物理占用或保护性 retain。
   */
  default int releaseResourcesByTrainAndRole(
      String trainName, List<OccupancyResource> resources, ClaimRole role) {
    return 0;
  }

  /**
   * 清理当前请求中同一列车持有的 single conflict 反向残留。
   *
   * <p>默认实现不做任何操作。该入口只供健康恢复在确认列车已因 STOP 进度停滞后使用，用来丢弃“旧方向 claim/queue 阻塞当前方向”的陈旧状态；普通进路授权必须继续通过
   * {@link #canEnter(OccupancyRequest)} 或 {@link #acquire(OccupancyRequest)} 判定，不应把此方法当作放行机制。
   *
   * @param request 当前准备重新判定的运行请求
   * @return 实际清理的 claim/queue 条目数量
   */
  default int clearSelfOwnedSingleDirectionMismatches(OccupancyRequest request) {
    return 0;
  }

  /**
   * 检查节点是否被占用。
   *
   * @param nodeId 节点 ID
   * @return true 如果节点被占用
   */
  default boolean isNodeOccupied(NodeId nodeId) {
    if (nodeId == null) {
      return false;
    }
    OccupancyResource resource = OccupancyResource.forNode(nodeId);
    return getClaim(resource).isPresent();
  }

  /**
   * 是否需要让行（优先级让行）。
   *
   * <p>默认实现不启用让行，由具体占用实现决定是否支持。
   */
  default boolean shouldYield(OccupancyRequest request) {
    return false;
  }
}
