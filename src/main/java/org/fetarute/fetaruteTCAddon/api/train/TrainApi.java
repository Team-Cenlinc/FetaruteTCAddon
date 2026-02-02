package org.fetarute.fetaruteTCAddon.api.train;

import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

/**
 * 列车 API：提供运行中列车的只读状态访问。
 *
 * <p>包含列车的实时位置、速度、信号状态、ETA 等信息，适用于：
 *
 * <ul>
 *   <li>地图上的列车图标渲染
 *   <li>列车位置动画插值
 *   <li>到站时间显示
 * </ul>
 *
 * <h2>使用示例</h2>
 *
 * <pre>{@code
 * TrainApi trains = api.trains();
 *
 * // 获取某世界的所有活跃列车
 * for (TrainSnapshot train : trains.listActiveTrains(worldId)) {
 *     System.out.println(train.trainName() + " 位于 " + train.currentNode());
 *     System.out.println("速度: " + train.speedBps() + " blocks/s");
 *     System.out.println("信号: " + train.signal());
 * }
 *
 * // 获取单个列车的详细信息
 * trains.getTrainSnapshot("train-1").ifPresent(train -> {
 *     train.eta().ifPresent(eta -> {
 *         System.out.println("预计到达: " + eta.etaMinutes() + " 分钟");
 *     });
 * });
 * }</pre>
 *
 * <h2>位置插值</h2>
 *
 * <p>使用 {@link TrainSnapshot#edgeProgress()} 可实现平滑的列车位置动画：
 *
 * <pre>{@code
 * // 在当前边上的进度 (0.0 ~ 1.0)
 * double progress = train.edgeProgress();
 * // 结合 fromNode 和 toNode 的坐标进行线性插值
 * }</pre>
 */
public interface TrainApi {

  /**
   * 获取指定世界的所有活跃列车。
   *
   * @param worldId 世界 UUID
   * @return 列车快照集合（不可变）
   */
  Collection<TrainSnapshot> listActiveTrains(UUID worldId);

  /**
   * 获取所有世界的所有活跃列车。
   *
   * @return 列车快照集合（不可变）
   */
  Collection<TrainSnapshot> listAllActiveTrains();

  /**
   * 获取指定列车的快照。
   *
   * @param trainName 列车名称
   * @return 列车快照，若列车不存在或未被调度则返回 empty
   */
  Optional<TrainSnapshot> getTrainSnapshot(String trainName);

  /**
   * 获取活跃列车数量。
   *
   * @return 当前被 FTA 调度的列车总数
   */
  int activeTrainCount();

  /**
   * 获取指定世界的活跃列车数量。
   *
   * @param worldId 世界 UUID
   * @return 该世界的活跃列车数
   */
  int activeTrainCount(UUID worldId);

  // ─────────────────────────────────────────────────────────────────────────────
  // 数据模型
  // ─────────────────────────────────────────────────────────────────────────────

  /**
   * 列车状态快照。
   *
   * @param trainName 列车名称（TrainCarts 中的 train name）
   * @param worldId 所在世界
   * @param routeId 当前路线 ID（如 "SURN:L1:R1"）
   * @param routeCode 路线代码（人类可读，如 "L1-R1"）
   * @param currentNode 当前所在/最近经过的节点 ID
   * @param nextNode 下一目标节点 ID
   * @param speedBps 当前速度（blocks/s）
   * @param signal 当前信号状态
   * @param edgeProgress 当前边上的进度（0.0 ~ 1.0），用于位置插值
   * @param updatedAt 快照更新时间
   * @param eta ETA 信息（可选）
   */
  record TrainSnapshot(
      String trainName,
      UUID worldId,
      String routeId,
      Optional<String> routeCode,
      Optional<String> currentNode,
      Optional<String> nextNode,
      double speedBps,
      Signal signal,
      double edgeProgress,
      Instant updatedAt,
      Optional<EtaInfo> eta) {}

  /** 信号状态枚举。 */
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
   * ETA 信息。
   *
   * @param targetNode 目标节点 ID
   * @param targetName 目标名称（站点名等）
   * @param etaEpochMillis 预计到达时间戳（毫秒）
   * @param etaMinutes 预计到达分钟数（四舍五入）
   * @param arriving 是否即将到达（距离目标 ≤2 个 edge）
   * @param delayed 是否延误
   */
  record EtaInfo(
      String targetNode,
      Optional<String> targetName,
      long etaEpochMillis,
      int etaMinutes,
      boolean arriving,
      boolean delayed) {}
}
