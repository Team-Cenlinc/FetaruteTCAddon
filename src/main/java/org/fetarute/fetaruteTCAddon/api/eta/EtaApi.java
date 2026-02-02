package org.fetarute.fetaruteTCAddon.api.eta;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
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
    WAIT
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

  /** 站牌行。 */
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
      List<Reason> reasons) {}

  /**
   * ETA 诊断信息（面向调试/展示）。
   *
   * @param trainName 列车名
   * @param worldId 世界 UUID
   * @param routeId 路线 ID
   * @param routeIndex 当前 index
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
