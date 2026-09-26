package org.fetarute.fetaruteTCAddon.dispatcher.route;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import org.fetarute.fetaruteTCAddon.company.model.RouteOperationType;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.company.model.RouteStopPassType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.WaypointKind;
import org.fetarute.fetaruteTCAddon.dispatcher.node.WaypointMetadata;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignTextParser;

/**
 * 路线终点口径的唯一定义。
 *
 * <p>三个量各自回答一个问题，彼此不能互相顶替：
 *
 * <ul>
 *   <li><b>线路终点（EOR）</b>：交路的最后一个节点，常为车库或折返线。
 *   <li><b>运营终点（EOP）</b>：车次终点，乘客最远能坐到哪一站——最后一个非 PASS 的车站类 stop（回库途中只通过的车站不算）。 “开往 X”显示的就是它。
 *   <li><b>命名终点</b>：车名首字母与 {@code FTA_DEST_*} 标签。TERMINATE 优先（{@code DS-1F_Full} 终到 DYNAMIC WYB 得名
 *       {@code DS-LW} 是有意设计），但 TERMINATE 落在折返线等非车站节点上时，退回它之前最近的载客站—— 否则首字母会取到节点 ID
 *       的运营商前缀，标签里也会是一串原始节点 ID。
 * </ul>
 *
 * <p>本类只挑“哪一个 stop”，不负责显示：HUD 要双语、站牌要 label、API 要名称，格式各不相同； 但挑哪一站必须是同一把尺子。此前 HUD、站牌、公开
 * API、出车命名、折返改名各有一套实现，在实服数据上已经给出过不同答案。
 *
 * <p>索引是入参列表的下标。调用方传入 {@link RouteDefinitionCache#listStops} 时，下标与 {@link
 * RouteDefinition#waypoints()} 以及运行时进度的 {@code currentIndex} 对齐。
 */
public final class RouteTerminals {

  /** 回库列车越过运营终点之后的显示文本（与站牌历史口径一致）。 */
  public static final String OUT_OF_SERVICE_LABEL = "回库";

  /** 回库显示的第二语言文本。 */
  public static final String OUT_OF_SERVICE_LANG2 = "Not in Service";

  /** 回库显示的目的地 ID。 */
  public static final String OUT_OF_SERVICE_ID = "OUT_OF_SERVICE";

  /** 线路终点是车库时，接在站名后的后缀（如「林湾车库」）。 */
  public static final String DEPOT_SUFFIX = "车库";

  /** 同上，第二语言与站码显示用（如「Lym Won Depot」「LWN Depot」）。 */
  public static final String DEPOT_SUFFIX_LANG2 = "Depot";

  private RouteTerminals() {}

  /**
   * 线路终点：交路的最后一个节点（常为车库或折返线）。
   *
   * @param stops 停靠表（按 sequence 排序）
   * @return 下标；停靠表为空时为空
   */
  public static OptionalInt endOfRouteIndex(List<RouteStop> stops) {
    if (stops == null || stops.isEmpty()) {
      return OptionalInt.empty();
    }
    return OptionalInt.of(stops.size() - 1);
  }

  /**
   * 线路终点的显示锚点。
   *
   * <p>终点节点本身是车站或车库时就是它；是折返线、区间点时取它之前最近的车站。区间点的 ID 同时写着两端站码 （{@code
   * SURC:OFL:MLU:2:004}），按“前方站”显示会显示成一个车根本不去的站（MLU），而列车实际是在 OFL 后折返。 节点 ID 仍以 {@link
   * #endOfRouteIndex} 为准，本方法只决定显示哪个站名。
   *
   * @param stops 停靠表（按 sequence 排序）
   * @return 下标；停靠表为空时为空
   */
  public static OptionalInt endOfRouteLabelIndex(List<RouteStop> stops) {
    OptionalInt last = endOfRouteIndex(stops);
    if (last.isEmpty()) {
      return last;
    }
    RouteStop terminal = stops.get(last.getAsInt());
    if (isStationStop(terminal) || depotRef(terminal).isPresent()) {
      return last;
    }
    for (int i = last.getAsInt() - 1; i >= 0; i--) {
      if (isStationStop(stops.get(i))) {
        return OptionalInt.of(i);
      }
    }
    return last;
  }

  /**
   * 运营终点：最后一个非 PASS 的车站类 stop。
   *
   * @param stops 停靠表（按 sequence 排序）
   * @return 下标；没有载客停靠站时为空
   */
  public static OptionalInt endOfOperationIndex(List<RouteStop> stops) {
    if (stops == null) {
      return OptionalInt.empty();
    }
    return lastPassengerStationAtOrBefore(stops, stops.size() - 1);
  }

  /**
   * 命名终点：TERMINATE 优先，其次最后一个 STOP，最后回退到停靠表末尾； 选中的 stop 不是车站时，退回它之前最近的载客站。
   *
   * <p>找不到任何载客站时保留原选中项——宁可名字难看，也不能凭空换成别的站。
   *
   * @param stops 停靠表（按 sequence 排序）
   * @return 下标；停靠表为空时为空
   */
  public static OptionalInt namingIndex(List<RouteStop> stops) {
    if (stops == null || stops.isEmpty()) {
      return OptionalInt.empty();
    }
    int selected = lastIndexOf(stops, RouteStopPassType.TERMINATE);
    if (selected < 0) {
      selected = lastIndexOf(stops, RouteStopPassType.STOP);
    }
    if (selected < 0) {
      selected = stops.size() - 1;
    }
    if (isStationStop(stops.get(selected))) {
      return OptionalInt.of(selected);
    }
    OptionalInt fallback = lastPassengerStationAtOrBefore(stops, selected - 1);
    return fallback.isPresent() ? fallback : OptionalInt.of(selected);
  }

  /**
   * 回库列车是否已经越过运营终点、应当显示“回库 / Not in Service”。
   *
   * <p>只对 RETURN 生效：越过运营终点之前显示终点站名（站台乘客与车上乘客看到的是同一个终点）， 离开运营终点之后才改显示回库。 没有任何载客站的 RETURN 线路从一开始就是回库。
   *
   * @param operationType 线路运营类型；未知时视为非 RETURN
   * @param stops 与 {@code currentIndex} 对齐的停靠表
   * @param currentIndex 运行时进度下标；未知时传负数，视为尚未发车
   * @return 需要显示回库时返回 true
   */
  public static boolean outOfService(
      RouteOperationType operationType, List<RouteStop> stops, int currentIndex) {
    if (operationType != RouteOperationType.RETURN) {
      return false;
    }
    OptionalInt eop = endOfOperationIndex(stops);
    return eop.isEmpty() || currentIndex > eop.getAsInt();
  }

  /**
   * stop 是否是车站：绑定了 stationId、DYNAMIC 车站规范，或 waypoint 是车站本体节点（{@code OP:S:CODE:TRACK}）。
   *
   * <p>车站咽喉（{@code OP:S:CODE:TRACK:SEQ}）、区间点、车库都不算。
   */
  public static boolean isStationStop(RouteStop stop) {
    if (stop == null) {
      return false;
    }
    if (stop.stationId().isPresent()) {
      return true;
    }
    Optional<DynamicStopMatcher.DynamicSpec> dynamic = DynamicStopMatcher.parseDynamicSpec(stop);
    if (dynamic.isPresent()) {
      return dynamic.get().isStation();
    }
    return stop.waypointNodeId()
        .flatMap(RouteTerminals::parseWaypoint)
        .map(meta -> meta.kind() == WaypointKind.STATION)
        .orElse(false);
  }

  /**
   * 解析车站类 stop 的站点键（运营商代码 + 站点代码）。
   *
   * <p>只看 stop 自身：DYNAMIC 规范取其站名，waypoint 取节点里的站码。只绑定 stationId 的 stop 需要查库， 本方法返回空，由调用方按 stationId
   * 自行解析。
   *
   * @param stop 路线停靠
   * @return 站点键；非车站或只有 stationId 时为空
   */
  public static Optional<StationRef> stationRef(RouteStop stop) {
    if (stop == null) {
      return Optional.empty();
    }
    Optional<DynamicStopMatcher.DynamicSpec> dynamic = DynamicStopMatcher.parseDynamicSpec(stop);
    if (dynamic.isPresent()) {
      DynamicStopMatcher.DynamicSpec spec = dynamic.get();
      if (!spec.isStation()) {
        return Optional.empty();
      }
      return Optional.of(
          new StationRef(spec.operatorCode(), spec.nodeName(), spec.toPlaceholderNodeId()));
    }
    Optional<String> nodeId = stop.waypointNodeId().filter(id -> !id.isBlank());
    if (nodeId.isEmpty()) {
      return Optional.empty();
    }
    return parseWaypoint(nodeId.get())
        .filter(meta -> meta.kind() == WaypointKind.STATION)
        .map(meta -> new StationRef(meta.operator(), meta.originStation(), nodeId.get()));
  }

  /**
   * 解析车库类 stop 的车库引用（普通车库节点 {@code OP:D:CODE:TRACK} 或 DYNAMIC 车库规范）。
   *
   * <p>线路终点（EOR）常落在车库上；显示时接在同代码车站的名称后面，如「林湾车库」，与车次终点（EOP）区分开。
   *
   * @param stop 路线停靠
   * @return 车库引用；非车库时为空
   */
  public static Optional<StationRef> depotRef(RouteStop stop) {
    if (stop == null) {
      return Optional.empty();
    }
    Optional<DynamicStopMatcher.DynamicSpec> dynamic = DynamicStopMatcher.parseDynamicSpec(stop);
    if (dynamic.isPresent()) {
      DynamicStopMatcher.DynamicSpec spec = dynamic.get();
      return spec.isDepot()
          ? Optional.of(
              new StationRef(spec.operatorCode(), spec.nodeName(), spec.toPlaceholderNodeId()))
          : Optional.empty();
    }
    Optional<String> nodeId = stop.waypointNodeId().filter(id -> !id.isBlank());
    if (nodeId.isEmpty()) {
      return Optional.empty();
    }
    return parseWaypoint(nodeId.get())
        .filter(meta -> meta.kind() == WaypointKind.DEPOT)
        .map(meta -> new StationRef(meta.operator(), meta.originStation(), nodeId.get()));
  }

  /** 车库的站码式显示（站牌、公开 API 用站码作名称），如「LWN Depot」。 */
  public static String depotCodeLabel(String depotCode) {
    return depotCode + " " + DEPOT_SUFFIX_LANG2;
  }

  /**
   * 实际节点是否就是这个 stop（DYNAMIC 按股道范围、普通站台允许同站换股道）。
   *
   * <p>判断“是否已到终点”必须用它，而不是拿 DYNAMIC 的占位节点做字符串相等——占位节点只是范围里的第一条股道。
   */
  public static boolean matches(NodeId nodeId, RouteStop stop) {
    return DynamicStopMatcher.matchesStop(nodeId, stop);
  }

  private static OptionalInt lastPassengerStationAtOrBefore(List<RouteStop> stops, int from) {
    for (int i = Math.min(from, stops.size() - 1); i >= 0; i--) {
      RouteStop stop = stops.get(i);
      if (stop != null && stop.passType() != RouteStopPassType.PASS && isStationStop(stop)) {
        return OptionalInt.of(i);
      }
    }
    return OptionalInt.empty();
  }

  private static int lastIndexOf(List<RouteStop> stops, RouteStopPassType passType) {
    for (int i = stops.size() - 1; i >= 0; i--) {
      RouteStop stop = stops.get(i);
      if (stop != null && stop.passType() == passType) {
        return i;
      }
    }
    return -1;
  }

  private static Optional<WaypointMetadata> parseWaypoint(String nodeId) {
    if (nodeId == null || nodeId.isBlank()) {
      return Optional.empty();
    }
    return SignTextParser.parseWaypointLike(nodeId, NodeType.WAYPOINT)
        .flatMap(SignNodeDefinition::waypointMetadata);
  }

  /**
   * 车站类 stop 的站点引用。
   *
   * @param operatorCode 运营商代码
   * @param stationCode 站点代码
   * @param nodeId 图节点 ID（DYNAMIC 时为范围内第一条股道的占位节点）
   */
  public record StationRef(String operatorCode, String stationCode, String nodeId) {}
}
