package org.fetarute.fetaruteTCAddon.api.eta;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * ETA API：提供列车到达时间与站牌列表的只读访问。
 *
 * <p>该 API 封装内部 ETA 服务，面向外部插件输出稳定、不可变的结果对象。
 *
 * <h2>使用示例</h2>
 *
 * <pre>{@code
 * EtaApi eta = api.eta();
 *
 * // 查询列车下一站 ETA
 * eta.getForTrain("train-1", EtaApi.Target.nextStop())
 *     .ifPresent(result -> System.out.println(result.etaMinutes()));
 *
 * // 查询站牌列表
 * eta.getBoard("OP", "AAA", null, Duration.ofMinutes(10))
 *     .rows().forEach(row -> System.out.println(row.destination()));
 * }</pre>
 */
public interface EtaApi {

  /**
   * 查询列车 ETA。
   *
   * @param trainName 列车名（trainId）
   * @param target 目标（下一站/指定站点/指定节点）
   * @return ETA 结果（不可用时会返回 statusText 为 N/A 的结果）
   */
  EtaResult getForTrain(String trainName, Target target);

  /**
   * 查询未发车票据 ETA。
   *
   * @param ticketId 票据 ID
   * @return ETA 结果（不可用时会返回 statusText 为 N/A 的结果）
   */
  EtaResult getForTicket(String ticketId);

  /**
   * 查询站牌列表（推荐形式：operator + stationCode）。
   *
   * @param operator 运营商代码
   * @param stationCode 站点代码
   * @param lineId 线路代码（可选）
   * @param horizon 时间窗口（可选，默认 10 分钟）
   * @return 站牌列表
   */
  BoardResult getBoard(String operator, String stationCode, String lineId, Duration horizon);

  /**
   * 查询站牌列表（兼容形式：stationId 可为 StationCode 或 Operator:StationCode）。
   *
   * @param stationId 站点标识
   * @param lineId 线路代码（可选）
   * @param horizon 时间窗口（可选，默认 10 分钟）
   * @return 站牌列表
   */
  BoardResult getBoard(String stationId, String lineId, Duration horizon);

  // ─────────────────────────────────────────────────────────────────────────────
  // 数据模型
  // ─────────────────────────────────────────────────────────────────────────────

  /** ETA 可信度。 */
  enum Confidence {
    HIGH,
    MED,
    LOW
  }

  /** ETA 诊断标签。 */
  enum Reason {
    NO_VEHICLE,
    NO_ROUTE,
    NO_TARGET,
    NO_PATH,
    THROAT,
    SINGLELINE,
    PLATFORM,
    DEPOT_GATE,
    /** 可预知的等待（按表等点、票据尚未到点）。1.4.0 起占用/信号造成的等待改报 {@link #HOLD}。 */
    WAIT,
    /** 列车正被运行时扣停，ETA 已按扣停时长顺延（1.4.0）。 */
    HOLD,
    /** 班次已过计划发车时刻仍未发出，ETA 已顺延（1.4.0）。 */
    OVERDUE
  }

  /** ETA 目标。 */
  sealed interface Target permits Target.NextStop, Target.Station, Target.PlatformNode {

    /** 下一站。 */
    record NextStop() implements Target {}

    /** 指定站点。 */
    record Station(String stationId) implements Target {
      public Station {
        if (stationId == null || stationId.isBlank()) {
          throw new IllegalArgumentException("stationId 不能为空");
        }
      }
    }

    /** 指定节点。 */
    record PlatformNode(String nodeId) implements Target {
      public PlatformNode {
        if (nodeId == null || nodeId.isBlank()) {
          throw new IllegalArgumentException("nodeId 不能为空");
        }
      }
    }

    static Target nextStop() {
      return new NextStop();
    }
  }

  /**
   * ETA 结果。
   *
   * @param arriving 是否即将到达
   * @param statusText 状态文本
   * @param etaEpochMillis 预计到达时间戳
   * @param etaMinutes 预计到达分钟数（四舍五入）
   * @param travelSec 行驶时间（秒）
   * @param dwellSec 停站时间（秒）
   * @param waitSec 等待时间（秒）
   * @param reasons 原因列表
   * @param confidence 可信度
   */
  record EtaResult(
      boolean arriving,
      String statusText,
      long etaEpochMillis,
      int etaMinutes,
      int travelSec,
      int dwellSec,
      int waitSec,
      List<Reason> reasons,
      Confidence confidence) {

    /** 预计到达时间。 */
    public Optional<Instant> eta() {
      return etaEpochMillis <= 0L
          ? Optional.empty()
          : Optional.of(Instant.ofEpochMilli(etaEpochMillis));
    }
  }

  /** 站牌列表结果。 */
  record BoardResult(List<BoardRow> rows) {
    public BoardResult {
      rows = rows == null ? List.of() : List.copyOf(rows);
    }
  }

  /** 站牌行所处的阶段（1.9.0），按离本站由远到近排列。 */
  enum BoardPhase {
    /** 未出票的预测班次（按发车计划或时刻表推算）。 */
    FORECAST,
    /** 已出票、尚未发车。 */
    PENDING,
    /** 运行中，尚未临近本站。 */
    EN_ROUTE,
    /** 即将到达或通过本站。 */
    ARRIVING,
    /** 已停在本站，尚未获准发车。 */
    AT_STATION
  }

  /**
   * 站牌行。
   *
   * <p>1.9.0 起增补结构化字段（时刻、阶段、停靠属性、晚点），显示方不必再解析 {@code statusText}。
   *
   * @param lineName 列车到本站时所属线路的代码（直通运转换线后为新线路）
   * @param routeId 交路 ID（{@code 运营商:线路:交路}）
   * @param destination 主目的地显示名（运营终点；回库车越过运营终点后为“回库”）
   * @param destinationId 主目的地 ID
   * @param endRoute 线路终点（EOR）显示名
   * @param endRouteId 线路终点 ID
   * @param endOperation 运营终点（EOP）显示名
   * @param endOperationId 运营终点 ID
   * @param platform 站台号；无法解析时为 {@code -}。1.9.0 起站台待定（见 {@code platformPending}）时也为 {@code -}， 此前给的是
   *     DYNAMIC 范围里的第一条股道，列车未必去那里
   * @param statusText 状态文本（英文短语）
   * @param reasons 诊断标签
   * @param etaEpochMillis 预计到达或通过本站的时间戳；已在站时为查询时刻；1.8.0 构造器创建的行为 0
   * @param phase 所处阶段（1.9.0）
   * @param stopSequence 本站停靠序号，交路节点的 0 起下标，与 RouteApi、TimetableApi 同一口径；未知时为 -1（1.9.0）
   * @param passing 本站通过不停（1.9.0）
   * @param terminating 本站是运营终点，乘客在此下车（1.9.0）
   * @param outOfService 本站已越过运营终点，列车在回库途中（1.9.0）
   * @param trainName 运行中列车的列车名；票据与预测为空（1.9.0）
   * @param delaySeconds 按表运行时相对计划的偏差，正数为晚点：运行中为到达本站，已在站为发车，未发车为起点发车；不按表运行时为空（1.9.0）
   * @param platformPending 站台待定：本站是动态站台（DYNAMIC）停靠，列车还没有选台。运行中列车通常在到达本站前一个节点
   *     （多为站咽喉）时选台；未发车的票据与预测一律待定（1.9.0）
   * @param platformCandidates 站台待定时可能停靠的站台号，按站台号升序；站台已定或候选未知时为空（1.9.0）
   */
  record BoardRow(
      String lineName,
      String routeId,
      String destination,
      Optional<String> destinationId,
      String endRoute,
      Optional<String> endRouteId,
      String endOperation,
      Optional<String> endOperationId,
      String platform,
      String statusText,
      List<Reason> reasons,
      long etaEpochMillis,
      BoardPhase phase,
      int stopSequence,
      boolean passing,
      boolean terminating,
      boolean outOfService,
      Optional<String> trainName,
      OptionalLong delaySeconds,
      boolean platformPending,
      List<String> platformCandidates) {

    public BoardRow {
      destinationId = destinationId == null ? Optional.empty() : destinationId;
      endRouteId = endRouteId == null ? Optional.empty() : endRouteId;
      endOperationId = endOperationId == null ? Optional.empty() : endOperationId;
      reasons = reasons == null ? List.of() : List.copyOf(reasons);
      phase = phase == null ? BoardPhase.EN_ROUTE : phase;
      trainName = trainName == null ? Optional.empty() : trainName;
      delaySeconds = delaySeconds == null ? OptionalLong.empty() : delaySeconds;
      platformCandidates = platformCandidates == null ? List.of() : List.copyOf(platformCandidates);
    }

    /**
     * 1.8.0 及以前的构造器（源码与二进制兼容）：没有结构化字段，阶段按运行中、序号为 -1，{@code outOfService} 按主目的地 ID 是否为 {@code
     * OUT_OF_SERVICE} 推断。
     */
    public BoardRow(
        String lineName,
        String routeId,
        String destination,
        Optional<String> destinationId,
        String endRoute,
        Optional<String> endRouteId,
        String endOperation,
        Optional<String> endOperationId,
        String platform,
        String statusText,
        List<Reason> reasons) {
      this(
          lineName,
          routeId,
          destination,
          destinationId,
          endRoute,
          endRouteId,
          endOperation,
          endOperationId,
          platform,
          statusText,
          reasons,
          0L,
          BoardPhase.EN_ROUTE,
          -1,
          false,
          false,
          destinationId != null && destinationId.filter("OUT_OF_SERVICE"::equals).isPresent(),
          Optional.empty(),
          OptionalLong.empty(),
          false,
          List.of());
    }

    /** 预计到达或通过本站的时间（1.9.0）；1.8.0 构造器创建的行为空。 */
    public Optional<Instant> eta() {
      return etaEpochMillis <= 0L
          ? Optional.empty()
          : Optional.of(Instant.ofEpochMilli(etaEpochMillis));
    }
  }

  /**
   * ETA 诊断信息（面向调试/展示）。
   *
   * @param trainName 列车名
   * @param worldId 世界 UUID
   * @param routeId 路线 ID
   * @param routeIndex 列车最近到达的交路节点下标（0 起，与停靠序号同一口径）
   * @param currentNode 当前节点
   * @param lastPassedNode 上一节点
   */
  record RuntimeSnapshot(
      String trainName,
      UUID worldId,
      String routeId,
      int routeIndex,
      Optional<String> currentNode,
      Optional<String> lastPassedNode) {}

  /**
   * 查询运行时快照（用于调试/诊断）。
   *
   * @param trainName 列车名
   * @return 运行时快照
   */
  Optional<RuntimeSnapshot> getRuntimeSnapshot(String trainName);

  /**
   * 获取当前采样到的列车名集合（用于补全）。
   *
   * @return 列车名集合（不可变）
   */
  Collection<String> listSnapshotTrainNames();
}
