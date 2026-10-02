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
 *   <li><b>直通运转</b>（1.7.0）：停靠点上的 {@code CHANGE:<运营商>:<线路>} 通知列车从该站起改按另一条线对乘客运营， 见 {@link
 *       StopInfo#lineChange()}。交路的管理归属（{@link RouteInfo} 的运营商、线路，以及交路组、时刻表、调度）不随换线变化
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
 *         // sequence 是 0 起下标，展示给人看时自己 +1
 *         System.out.println("  " + (stop.sequence() + 1) + ". " + stop.stationName());
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
   * @param id 路线 UUID；{@link #listRoutes()}、{@link #getRoute}、{@link #findByCode}
   *     对同一条路线给出同一个值（1.6.0 起非空，此前恒为 null）
   * @param code 完整代码（如 "SURN:L1:R1"）
   * @param operatorCode 运营商代码
   * @param lineCode 线路代码
   * @param routeCode 路线代码
   * @param displayName 显示名称
   * @param operationType 运营类型（各停/快速/特急），来自路线的停站模式 {@code pattern_type}（1.6.0 起；此前恒为 {@code NORMAL}）
   * @param stage 交路阶段（出库/运营/回库），来自路线的 {@code operation_type}（1.6.0）
   */
  record RouteInfo(
      UUID id,
      String code,
      String operatorCode,
      String lineCode,
      String routeCode,
      Optional<String> displayName,
      OperationType operationType,
      RouteStage stage) {

    /** 1.5.0 及以前的构造器（源码兼容）；{@code stage} 取 {@link RouteStage#UNKNOWN}。 */
    public RouteInfo(
        UUID id,
        String code,
        String operatorCode,
        String lineCode,
        String routeCode,
        Optional<String> displayName,
        OperationType operationType) {
      this(
          id,
          code,
          operatorCode,
          lineCode,
          routeCode,
          displayName,
          operationType,
          RouteStage.UNKNOWN);
    }
  }

  /**
   * 路线详情（包含完整信息）。
   *
   * @param info 基本信息
   * @param waypoints 途经节点 ID 列表（有序）
   * @param stops 停靠站点列表（有序，与 {@code waypoints} 等长、下标一一对应）
   * @param terminal 终点信息（EOR/EOP）
   * @param totalDistanceBlocks 全程距离（blocks）
   * @param via 显式配置的经由站（1.9.0）：站码，按配置顺序（{@code /fta route set ... --via}）。未配置为空列表，
   *     显示方可自行推断（内置站台屏按换乘线路数、股道数与直通站推断）。只是配置值，不保证每个站码都在本交路上停车
   */
  record RouteDetail(
      RouteInfo info,
      List<String> waypoints,
      List<StopInfo> stops,
      TerminalInfo terminal,
      int totalDistanceBlocks,
      List<String> via) {

    public RouteDetail {
      via = via == null ? List.of() : List.copyOf(via);
    }

    /** 1.8.0 及以前的构造器（源码与二进制兼容）；{@code via} 为空。 */
    public RouteDetail(
        RouteInfo info,
        List<String> waypoints,
        List<StopInfo> stops,
        TerminalInfo terminal,
        int totalDistanceBlocks) {
      this(info, waypoints, stops, terminal, totalDistanceBlocks, List.of());
    }
  }

  /**
   * 终点信息（End of Route / End of Operation），与 HUD、站牌同一口径。
   *
   * <ul>
   *   <li><b>EOR (End of Route)</b>: 线路终点，即交路的最后一个节点（常为车库或折返线）
   *   <li><b>EOP (End of Operation)</b>: 运营终点，即车次最后停靠的车站（跳过 PASS；折返线上的 TERM 不算）。方向牌显示它
   * </ul>
   *
   * <p>对于大多数路线，EOR 和 EOP 通常相同。但在以下场景可能不同：
   *
   * <ul>
   *   <li>路线末尾有回库/折返点（Depot/Waypoint）
   *   <li>终点站后有咽喉节点
   * </ul>
   *
   * @param endOfRouteNodeId EOR 节点 ID（交路最后一个节点）
   * @param endOfRouteName EOR 站点名称
   * @param endOfOperationNodeId EOP 节点 ID（车次终点站）
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
   * <p>车站身份按节点解析：站台 {@code OP:S:CODE:TRACK}、咽喉 {@code OP:S:CODE:TRACK:SEQ}、DYNAMIC 占位 {@code
   * OP:S:CODE:fromTrack} 都归到站码 {@code CODE} 对应的车站；停靠点绑定了车站记录时以绑定为准。
   * 运营商代码按交路自己的运营商优先解析，同一站码在不同运营商下不会串站。
   *
   * @param sequence 停靠序号：交路节点的 <b>0 起下标</b>，即本条在 {@code RouteDetail.stops()} 与 {@code waypoints()}
   *     中的下标。与 TimetableApi 的 {@code stopSequence}、车站到发事件的 {@code getStopIndex()} 同一口径（1.5.0 起； 此前为
   *     1 起，展示序号请自行 +1）
   * @param nodeId 节点 ID（DYNAMIC stop 使用 placeholder nodeId，格式 {@code OP:S/D:NAME:fromTrack}）
   * @param stationName 站名：车站记录的真实站名，查不到记录时退回站码；车库、区间点等非车站节点为空（1.6.0 起；此前只有绑定车站记录的停靠点给站名， DYNAMIC
   *     停靠给的是站码）。线路终点落在车库时的 {@code LWN Depot} 标签见 {@link TerminalInfo}
   * @param dwellSeconds 路线上配置的停车时间（秒）；未配置为 0（运行时按默认停站）。是否停车看 {@code passType}，不看它
   * @param passType 通过类型（行为：停车/通过/终点）
   * @param dynamic 是否为动态站台选择（运行时根据占用情况选择轨道）
   * @param stationId 车站记录 ID；非车站节点、或站码查不到车站记录时为空（1.6.0）
   * @param stationCode 站码；非车站节点为空（1.6.0）
   * @param lineChange 直通运转（1.7.0）：从本站起列车对乘客显示的线路（管理归属不变，仍是交路自身的线路）。只在本站有 {@code CHANGE:<运营商>:<线路>}
   *     指令、且目标与此前所属线路不同时有值； 本站之前的各站属于交路自身线路（或更早一次换线的目标），本站及之后属于这里给出的线路，直到下一次换线。 列车以原线路到达本站、以新线路发车
   */
  record StopInfo(
      int sequence,
      String nodeId,
      Optional<String> stationName,
      int dwellSeconds,
      PassType passType,
      boolean dynamic,
      Optional<UUID> stationId,
      Optional<String> stationCode,
      Optional<LineRef> lineChange) {

    public StopInfo {
      stationName = stationName == null ? Optional.empty() : stationName;
      stationId = stationId == null ? Optional.empty() : stationId;
      stationCode = stationCode == null ? Optional.empty() : stationCode;
      lineChange = lineChange == null ? Optional.empty() : lineChange;
    }

    /** 1.6.0 的构造器（源码与二进制兼容）；{@code lineChange} 为空。 */
    public StopInfo(
        int sequence,
        String nodeId,
        Optional<String> stationName,
        int dwellSeconds,
        PassType passType,
        boolean dynamic,
        Optional<UUID> stationId,
        Optional<String> stationCode) {
      this(
          sequence,
          nodeId,
          stationName,
          dwellSeconds,
          passType,
          dynamic,
          stationId,
          stationCode,
          Optional.empty());
    }

    /** 1.5.0 及以前的构造器（源码兼容）；{@code stationId}、{@code stationCode}、{@code lineChange} 为空。 */
    public StopInfo(
        int sequence,
        String nodeId,
        Optional<String> stationName,
        int dwellSeconds,
        PassType passType,
        boolean dynamic) {
      this(
          sequence,
          nodeId,
          stationName,
          dwellSeconds,
          passType,
          dynamic,
          Optional.empty(),
          Optional.empty(),
          Optional.empty());
    }
  }

  /**
   * 线路标识（1.7.0）：运营商代码 + 线路代码。
   *
   * <p>线路存在时代码按主数据的写法给出（与 {@code LineApi}、{@code StationApi.ServingLine} 一致）；指令里写的线路不存在时原样给出。
   * 比较请不区分大小写。
   *
   * @param operatorCode 运营商代码
   * @param lineCode 线路代码
   */
  record LineRef(String operatorCode, String lineCode) {}

  /** 通过类型（描述停靠行为）。 */
  enum PassType {
    /** 停车 */
    STOP,
    /** 通过不停 */
    PASS,
    /** 终点 */
    TERMINATE
  }

  /**
   * 运营类型（停站模式）。
   *
   * <p>1.6.0 起按路线的 {@code pattern_type} 映射：{@code LOCAL → LOCAL}、{@code RAPID/NEO_RAPID → RAPID}、
   * {@code EXPRESS/LIMITED_EXPRESS → EXPRESS}。{@code NORMAL} 只在路线实体不可用时出现。
   */
  enum OperationType {
    /** 普通（未知停站模式时的默认值） */
    NORMAL,
    /** 快速（含新快速） */
    RAPID,
    /** 特急（含限定特急） */
    EXPRESS,
    /** 各停 */
    LOCAL,
    /** 其他 */
    OTHER
  }

  /**
   * 交路阶段（1.6.0），来自路线的 {@code operation_type}。与运营类型无关：一条各停路线可以是出库、运营或回库。
   *
   * <p>停靠线路查询（{@code StationApi#linesServing}）统计所有阶段，需要只看运营交路时按本字段过滤。
   */
  enum RouteStage {
    /** 出库（从车库注入运力） */
    CREATE,
    /** 回库 */
    RETURN,
    /** 运营 */
    OPERATION,
    /** 未知 */
    UNKNOWN
  }
}
