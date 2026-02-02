package org.fetarute.fetaruteTCAddon.api.occupancy;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 占用 API：提供轨道占用和信号状态的只读访问。
 *
 * <p>占用系统是 FTA 的安全核心，确保列车不会相撞：
 *
 * <ul>
 *   <li><b>节点占用</b>：某节点当前被哪辆列车占用
 *   <li><b>边占用</b>：某段轨道当前被哪辆列车使用
 *   <li><b>信号状态</b>：基于占用计算的信号灯颜色
 *   <li><b>等待队列</b>：在冲突点排队等待的列车
 * </ul>
 *
 * <h2>使用示例</h2>
 *
 * <pre>{@code
 * OccupancyApi occupancy = api.occupancy();
 *
 * // 检查某节点是否被占用
 * if (occupancy.isNodeOccupied(worldId, "SURN:S:AAA:1")) {
 *     occupancy.getNodeOccupant(worldId, "SURN:S:AAA:1").ifPresent(claim -> {
 *         System.out.println("被 " + claim.trainName() + " 占用");
 *     });
 * }
 *
 * // 获取所有占用信息（用于地图渲染）
 * for (OccupancyClaim claim : occupancy.listClaims(worldId)) {
 *     // 渲染占用区域
 * }
 *
 * // 查看等待队列
 * for (QueueSnapshot queue : occupancy.listQueues(worldId)) {
 *     System.out.println("资源 " + queue.resourceId() + " 队列:");
 *     for (QueueEntry entry : queue.entries()) {
 *         System.out.println("  " + entry.position() + ". " + entry.trainName());
 *     }
 * }
 * }</pre>
 */
public interface OccupancyApi {

  /**
   * 检查指定节点是否被占用。
   *
   * @param worldId 世界 UUID
   * @param nodeId 节点 ID
   * @return 若被占用则返回 true
   */
  boolean isNodeOccupied(UUID worldId, String nodeId);

  /**
   * 检查指定边是否被占用。
   *
   * @param worldId 世界 UUID
   * @param edgeId 边 ID
   * @return 若被占用则返回 true
   */
  boolean isEdgeOccupied(UUID worldId, String edgeId);

  /**
   * 获取占用指定节点的列车信息。
   *
   * @param worldId 世界 UUID
   * @param nodeId 节点 ID
   * @return 占用信息，若未被占用则返回 empty
   */
  Optional<OccupancyClaim> getNodeOccupant(UUID worldId, String nodeId);

  /**
   * 列出指定世界的所有占用信息。
   *
   * @param worldId 世界 UUID
   * @return 占用信息集合（不可变）
   */
  Collection<OccupancyClaim> listClaims(UUID worldId);

  /**
   * 列出所有世界的所有占用信息。
   *
   * @return 占用信息集合（不可变）
   */
  Collection<OccupancyClaim> listAllClaims();

  /**
   * 列出指定世界的等待队列。
   *
   * @param worldId 世界 UUID
   * @return 队列快照集合（不可变）
   */
  Collection<QueueSnapshot> listQueues(UUID worldId);

  /**
   * 获取当前占用资源总数。
   *
   * @return 被占用的资源（节点+边）数量
   */
  int totalOccupiedCount();

  // ─────────────────────────────────────────────────────────────────────────────
  // 数据模型
  // ─────────────────────────────────────────────────────────────────────────────

  /**
   * 占用声明。
   *
   * @param trainName 占用列车名称
   * @param resourceType 资源类型
   * @param resourceId 资源 ID（节点 ID 或边 ID）
   * @param worldId 世界 UUID
   * @param claimedAt 占用时间
   * @param signal 该资源相关的信号状态
   */
  record OccupancyClaim(
      String trainName,
      ResourceType resourceType,
      String resourceId,
      UUID worldId,
      Instant claimedAt,
      Signal signal) {}

  /** 资源类型。 */
  enum ResourceType {
    /** 节点 */
    NODE,
    /** 边 */
    EDGE,
    /** 冲突组 */
    CONFLICT
  }

  /** 信号状态（与 TrainApi.Signal 相同，为避免依赖重复定义）。 */
  enum Signal {
    /** 畅通 */
    PROCEED,
    /** 减速通过 */
    CAUTION,
    /** 减速准备停车 */
    PROCEED_WITH_CAUTION,
    /** 停车 */
    STOP,
    /** 未知 */
    UNKNOWN
  }

  /**
   * 等待队列快照。
   *
   * @param resourceId 资源 ID
   * @param resourceType 资源类型
   * @param entries 队列中的列车（按顺序）
   */
  record QueueSnapshot(String resourceId, ResourceType resourceType, List<QueueEntry> entries) {}

  /**
   * 队列条目。
   *
   * @param trainName 列车名称
   * @param position 队列位置（1 = 队首）
   * @param waitingSinceSec 等待时间（秒）
   */
  record QueueEntry(String trainName, int position, int waitingSinceSec) {}
}
