package org.fetarute.fetaruteTCAddon.api.route;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 路线 API：提供路线定义和站点信息的只读访问。
 *
 * <p>路线是列车运行的逻辑路径，包含：
 *
 * <ul>
 *   <li><b>路线定义</b>：途经节点序列、运营商、线路代码
 *   <li><b>停靠站点</b>：每个站点的停车时间、通过类型
 *   <li><b>元数据</b>：终点站、运行方向等
 * </ul>
 *
 * <h2>使用示例</h2>
 *
 * <pre>{@code
 * RouteApi routes = api.routes();
 *
 * // 列出所有路线
 * for (RouteInfo route : routes.listRoutes()) {
 *     System.out.println(route.code() + ": " + route.displayName());
 * }
 *
 * // 获取路线详情
 * routes.getRoute(routeId).ifPresent(route -> {
 *     System.out.println("途经站点:");
 *     for (StopInfo stop : route.stops()) {
 *         System.out.println("  " + stop.sequence() + ". " + stop.stationName());
 *     }
 * });
 *
 * // 按代码查找路线
 * routes.findByCode("SURN", "L1", "R1").ifPresent(route -> { ... });
 * }</pre>
 */
public interface RouteApi {

  /**
   * 列出所有已注册的路线。
   *
   * @return 路线信息集合（不可变）
   */
  Collection<RouteInfo> listRoutes();

  /**
   * 获取指定路线的详情。
   *
   * @param routeId 路线 UUID
   * @return 路线详情，若不存在则返回 empty
   */
  Optional<RouteDetail> getRoute(UUID routeId);

  /**
   * 按代码查找路线。
   *
   * @param operatorCode 运营商代码（如 "SURN"）
   * @param lineCode 线路代码（如 "L1"）
   * @param routeCode 路线代码（如 "R1"）
   * @return 路线详情，若不存在则返回 empty
   */
  Optional<RouteDetail> findByCode(String operatorCode, String lineCode, String routeCode);

  /**
   * 获取已注册的路线数量。
   *
   * @return 路线总数
   */
  int routeCount();

  // ─────────────────────────────────────────────────────────────────────────────
  // 数据模型
  // ─────────────────────────────────────────────────────────────────────────────

  /**
   * 路线基本信息（用于列表展示）。
   *
   * @param id 路线 UUID
   * @param code 完整代码（如 "SURN:L1:R1"）
   * @param operatorCode 运营商代码
   * @param lineCode 线路代码
   * @param routeCode 路线代码
   * @param displayName 显示名称
   * @param operationType 运营类型（普通/快速/特急等）
   */
  record RouteInfo(
      UUID id,
      String code,
      String operatorCode,
      String lineCode,
      String routeCode,
      Optional<String> displayName,
      OperationType operationType) {}

  /**
   * 路线详情（包含完整信息）。
   *
   * @param info 基本信息
   * @param waypoints 途经节点 ID 列表（有序）
   * @param stops 停靠站点列表（有序）
   * @param terminal 终点信息（EOR/EOP）
   * @param totalDistanceBlocks 全程距离（blocks）
   */
  record RouteDetail(
      RouteInfo info,
      List<String> waypoints,
      List<StopInfo> stops,
      TerminalInfo terminal,
      int totalDistanceBlocks) {}

  /**
   * 终点信息（End of Route / End of Operation）。
   *
   * <ul>
   *   <li><b>EOR (End of Route)</b>: 路线物理终点，即 waypoints 列表的最后一个节点
   *   <li><b>EOP (End of Operation)</b>: 运营终点，即最后一个 Station 类型的停靠点（跳过 PASS 类型）
   * </ul>
   *
   * <p>对于大多数路线，EOR 和 EOP 通常相同。但在以下场景可能不同：
   *
   * <ul>
   *   <li>路线末尾有回库/折返点（Depot/Waypoint）
   *   <li>终点站后有咽喉节点
   * </ul>
   *
   * @param endOfRouteNodeId EOR 节点 ID（路线物理终点）
   * @param endOfRouteName EOR 站点名称
   * @param endOfOperationNodeId EOP 节点 ID（运营终点）
   * @param endOfOperationName EOP 站点名称（用于方向牌显示）
   */
  record TerminalInfo(
      String endOfRouteNodeId,
      Optional<String> endOfRouteName,
      String endOfOperationNodeId,
      Optional<String> endOfOperationName) {

    /** 空的终点信息。 */
    public static TerminalInfo empty() {
      return new TerminalInfo("", Optional.empty(), "", Optional.empty());
    }

    /** 判断是否为空（无有效终点）。 */
    public boolean isEmpty() {
      return (endOfRouteNodeId == null || endOfRouteNodeId.isEmpty())
          && (endOfOperationNodeId == null || endOfOperationNodeId.isEmpty());
    }
  }

  /**
   * 停靠站点信息。
   *
   * @param sequence 序号（从 1 开始）
   * @param nodeId 节点 ID（DYNAMIC stop 使用 placeholder nodeId，格式 {@code OP:S/D:NAME:fromTrack}）
   * @param stationName 站点名称
   * @param dwellSeconds 停车时间（秒），0 表示通过不停
   * @param passType 通过类型（行为：停车/通过/终点）
   * @param dynamic 是否为动态站台选择（运行时根据占用情况选择轨道）
   */
  record StopInfo(
      int sequence,
      String nodeId,
      Optional<String> stationName,
      int dwellSeconds,
      PassType passType,
      boolean dynamic) {}

  /** 通过类型（描述停靠行为）。 */
  enum PassType {
    /** 停车 */
    STOP,
    /** 通过不停 */
    PASS,
    /** 终点 */
    TERMINATE
  }

  /** 运营类型。 */
  enum OperationType {
    /** 普通 */
    NORMAL,
    /** 快速 */
    RAPID,
    /** 特急 */
    EXPRESS,
    /** 各停 */
    LOCAL,
    /** 其他 */
    OTHER
  }
}
