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
   * 获取所有世界的所有活跃列车，可选不算 ETA（1.9.0）。
   *
   * <p>{@code includeEta} 为 false 时每辆车的 {@link TrainSnapshot#eta()} 恒为空，省去每车一次到下一站的 ETA 计算；
   * 只要线路、位置、退出服务等字段的汇总（如按线路数在途列车）用它。
   *
   * @param includeEta 是否计算下一站 ETA
   * @return 列车快照集合（不可变）
   */
  default Collection<TrainSnapshot> listAllActiveTrains(boolean includeEta) {
    return listAllActiveTrains();
  }

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
   * <p><b>线路</b>：{@code routeId}/{@code routeCode} 里的运营商、线路是交路本身的归属（管理归属），出车后不变； 直通运转（停靠点上的 {@code
   * CHANGE:<运营商>:<线路>}）只是通知列车改按另一条线对乘客运营，换线后对乘客显示的线路看 {@code operatorCode}/{@code
   * lineCode}（1.7.0）。每站属于哪条线见 {@code RouteApi.StopInfo#lineChange}。
   *
   * @param trainName 列车名称（TrainCarts 中的 train name）
   * @param worldId 所在世界
   * @param routeId 当前交路的代码 {@code 运营商:线路:交路}（如 {@code SURN:L1:R1}）
   * @param routeCode 同 {@code routeId}（交路在缓存中找得到时有值），可按 {@code :} 拆成三段交给 {@code
   *     RouteApi#findByCode}
   * @param currentNode 当前所在/最近经过的节点 ID
   * @param nextNode 下一目标节点 ID（下一个途经节点，可能是区间点或咽喉，不一定是下一个停车站）
   * @param speedBps 当前速度（blocks/s）
   * @param signal 当前信号状态
   * @param edgeProgress 当前边上的进度（0.0 ~ 1.0），用于位置插值
   * @param updatedAt 快照更新时间
   * @param eta 下一个停车站的 ETA（可选）
   * @param operatorCode 列车当前对乘客显示的运营商代码（1.7.0）：取自列车的线路标签（出车与 CHANGE 写入），没有标签时为交路本身的运营商；
   *     线路存在时按主数据的写法给出。标签与交路都不明时为空。管理归属看 {@code routeId}
   * @param lineCode 列车当前对乘客显示的线路代码（1.7.0），与 {@code operatorCode} 同时有值、同一口径
   * @param outOfService 是否已退出服务（1.7.0）：回库交路越过运营终点（EOP）之后，或整趟没有载客车站的回库交路。与 HUD、站牌的「回库 / Not in
   *     Service」同一判定；出库、运营交路恒为 false
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
      Optional<EtaInfo> eta,
      Optional<String> operatorCode,
      Optional<String> lineCode,
      boolean outOfService) {

    public TrainSnapshot {
      routeCode = routeCode == null ? Optional.empty() : routeCode;
      currentNode = currentNode == null ? Optional.empty() : currentNode;
      nextNode = nextNode == null ? Optional.empty() : nextNode;
      eta = eta == null ? Optional.empty() : eta;
      operatorCode = operatorCode == null ? Optional.empty() : operatorCode;
      lineCode = lineCode == null ? Optional.empty() : lineCode;
    }

    /** 1.6.0 及以前的构造器（源码与二进制兼容）：当前线路为空、{@code outOfService} 为 false。 */
    public TrainSnapshot(
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
        Optional<EtaInfo> eta) {
      this(
          trainName,
          worldId,
          routeId,
          routeCode,
          currentNode,
          nextNode,
          speedBps,
          signal,
          edgeProgress,
          updatedAt,
          eta,
          Optional.empty(),
          Optional.empty(),
          false);
    }
  }

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
