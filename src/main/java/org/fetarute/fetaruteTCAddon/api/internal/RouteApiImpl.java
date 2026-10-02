package org.fetarute.fetaruteTCAddon.api.internal;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.api.route.RouteApi;
import org.fetarute.fetaruteTCAddon.company.api.StationDirectory;
import org.fetarute.fetaruteTCAddon.company.api.StationDirectory.StopStation;
import org.fetarute.fetaruteTCAddon.company.model.Operator;
import org.fetarute.fetaruteTCAddon.company.model.RouteOperationType;
import org.fetarute.fetaruteTCAddon.company.model.RoutePatternType;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.company.model.RouteStopPassType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.route.DynamicStopMatcher;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinitionCache;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteLineChanges;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteMetadata;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteTerminals;

/**
 * RouteApi 内部实现：桥接到 RouteDefinitionCache 与车站目录。
 *
 * <p>全部数据来自内存：交路定义与停靠表来自 {@link RouteDefinitionCache}，停靠点的车站身份（站名、车站 ID、站码）来自 {@link
 * StationDirectory} 在交路缓存重建时算好的结果。查询不访问存储。
 *
 * <p>仅供内部使用，外部插件应通过 {@link org.fetarute.fetaruteTCAddon.api.FetaruteApi} 访问。
 */
public final class RouteApiImpl implements RouteApi {

  private final RouteDefinitionCache routeDefinitions;
  private final StationDirectory stations;

  /**
   * @param routeDefinitions 交路缓存
   * @param stations 车站目录；为 null 时站名退回站码、没有车站 ID
   */
  public RouteApiImpl(RouteDefinitionCache routeDefinitions, StationDirectory stations) {
    this.routeDefinitions = Objects.requireNonNull(routeDefinitions, "routeDefinitions");
    this.stations = stations;
  }

  @Override
  public Collection<RouteInfo> listRoutes() {
    List<RouteInfo> result = new ArrayList<>();
    for (Map.Entry<UUID, RouteDefinition> entry : routeDefinitions.snapshot().entrySet()) {
      result.add(convertRouteInfo(entry.getKey(), entry.getValue()));
    }
    return List.copyOf(result);
  }

  @Override
  public Optional<RouteDetail> getRoute(UUID routeId) {
    if (routeId == null) {
      return Optional.empty();
    }

    return routeDefinitions.findById(routeId).map(def -> convertRouteDetail(routeId, def));
  }

  @Override
  public Optional<RouteDetail> findByCode(String operatorCode, String lineCode, String routeCode) {
    if (operatorCode == null || lineCode == null || routeCode == null) {
      return Optional.empty();
    }

    // 经 UUID 取详情：与 getRoute() 同一条路径，RouteInfo#id 不会为空。
    return routeDefinitions
        .findByCodes(operatorCode, lineCode, routeCode)
        .flatMap(def -> routeDefinitions.findUuid(def.id()))
        .flatMap(this::getRoute);
  }

  @Override
  public int routeCount() {
    return routeDefinitions.snapshot().size();
  }

  private RouteInfo convertRouteInfo(UUID routeUuid, RouteDefinition def) {
    Optional<RouteMetadata> metaOpt = def.metadata();
    // RouteMetadata 字段: operator, lineId, serviceId, displayName
    String operatorCode = metaOpt.map(RouteMetadata::operator).orElse("");
    String lineCode = metaOpt.map(RouteMetadata::lineId).orElse("");
    String routeCode = metaOpt.map(RouteMetadata::serviceId).orElse("");
    Optional<String> displayName = metaOpt.flatMap(RouteMetadata::displayName);
    Optional<RouteDefinitionCache.RouteRecord> record = routeDefinitions.findRecord(routeUuid);

    return new RouteInfo(
        routeUuid,
        def.id().value(),
        operatorCode,
        lineCode,
        routeCode,
        displayName,
        record.map(r -> operationTypeOf(r.route().patternType())).orElse(OperationType.NORMAL),
        record.map(r -> stageOf(r.route().operationType())).orElse(RouteStage.UNKNOWN));
  }

  /** 停站模式 → 公开运营类型；新快速并入快速，限定特急并入特急。 */
  static OperationType operationTypeOf(RoutePatternType patternType) {
    if (patternType == null) {
      return OperationType.NORMAL;
    }
    return switch (patternType) {
      case LOCAL -> OperationType.LOCAL;
      case RAPID, NEO_RAPID -> OperationType.RAPID;
      case EXPRESS, LIMITED_EXPRESS -> OperationType.EXPRESS;
    };
  }

  /** 交路阶段（{@code operation_type}）。 */
  static RouteStage stageOf(RouteOperationType operationType) {
    if (operationType == null) {
      return RouteStage.UNKNOWN;
    }
    return switch (operationType) {
      case CREATE -> RouteStage.CREATE;
      case RETURN -> RouteStage.RETURN;
      case OPERATION -> RouteStage.OPERATION;
    };
  }

  private RouteDetail convertRouteDetail(UUID routeUuid, RouteDefinition def) {
    RouteInfo info = convertRouteInfo(routeUuid, def);

    // 获取 waypoints
    List<String> waypoints = new ArrayList<>();
    for (NodeId nodeId : def.waypoints()) {
      waypoints.add(nodeId.value());
    }

    // 停靠表与 waypoints 下标对齐；序号就是下标，与 TimetableApi、车站事件同一口径。
    List<RouteStop> routeStops = routeDefinitions.listStops(def.id());
    List<StopStation> resolved = resolveStops(routeUuid, routeStops);
    List<Optional<LineRef>> lineChanges = resolveLineChanges(info, routeStops);
    List<StopInfo> stops = new ArrayList<>();
    for (int i = 0; i < routeStops.size(); i++) {
      stops.add(convertStopInfo(routeStops.get(i), resolved.get(i), i, lineChanges.get(i)));
    }

    // 解析终点信息
    TerminalInfo terminal = resolveTerminalInfo(def, routeStops, resolved);

    // 总距离（需要从图计算，这里简化为 0）
    int totalDistance = 0;

    return new RouteDetail(
        info, List.copyOf(waypoints), List.copyOf(stops), terminal, totalDistance);
  }

  /** 各停靠点的车站身份（与 {@code stops} 下标对齐）；交路缓存重建时已由车站目录算好，这里只查表。 */
  private List<StopStation> resolveStops(UUID routeUuid, List<RouteStop> stops) {
    Optional<Operator> routeOperator =
        routeDefinitions.findRecord(routeUuid).map(RouteDefinitionCache.RouteRecord::operator);
    return snapshot().stopStations(routeUuid, stops, routeOperator);
  }

  /**
   * 各停靠点的直通换线（与 {@code stops} 下标对齐），口径见 {@link RouteLineChanges#changesByIndex}；代码按主数据的写法给出。
   *
   * <p>交路自身线路未知时（没有运营商或线路代码）无从比较，全部为空。
   */
  private List<Optional<LineRef>> resolveLineChanges(RouteInfo info, List<RouteStop> stops) {
    Optional<RouteLineChanges.LineRef> routeLine =
        RouteLineChanges.LineRef.of(info.operatorCode(), info.lineCode());
    if (routeLine.isEmpty()) {
      return java.util.Collections.nCopies(stops.size(), Optional.empty());
    }
    StationDirectory.Snapshot snapshot = snapshot();
    List<Optional<LineRef>> changes = new ArrayList<>(stops.size());
    for (Optional<RouteLineChanges.LineRef> change :
        RouteLineChanges.changesByIndex(stops, routeLine.get())) {
      changes.add(
          change
              .map(snapshot::canonicalLine)
              .map(line -> new LineRef(line.operatorCode(), line.lineCode())));
    }
    return changes;
  }

  /** 车站目录的当前快照；没有目录时用空快照（站名退回站码、没有车站 ID）。 */
  private StationDirectory.Snapshot snapshot() {
    return stations == null ? StationDirectory.detachedSnapshot() : stations.snapshot();
  }

  /** 解析终点信息：EOR 为交路最后一个节点，EOP 为车次终点站（与 HUD、站牌同一口径，见 {@link RouteTerminals}）。 */
  private TerminalInfo resolveTerminalInfo(
      RouteDefinition def, List<RouteStop> stops, List<StopStation> resolved) {
    StopRef eor = new StopRef("", Optional.empty());
    if (!def.waypoints().isEmpty()) {
      String last = def.waypoints().get(def.waypoints().size() - 1).value();
      eor = new StopRef(last, resolveEndOfRouteName(stops, resolved, last));
    }
    // 没有载客车站时 EOP 为空：不拿 EOR 顶替，调用方要能分辨“这条交路不载客”。
    StopRef eop = resolveStopRef(stops, resolved, RouteTerminals.endOfOperationIndex(stops));
    return new TerminalInfo(eor.nodeId(), eor.name(), eop.nodeId(), eop.name());
  }

  /** EOR 的显示名：车库「LWN Depot」；折返线、区间点取它之前最近的车站（与 HUD、站牌同一规则）；站名与停靠表同一口径。 */
  private Optional<String> resolveEndOfRouteName(
      List<RouteStop> stops, List<StopStation> resolved, String lastNodeId) {
    if (!stops.isEmpty()) {
      Optional<RouteTerminals.StationRef> depot =
          RouteTerminals.depotRef(stops.get(stops.size() - 1));
      if (depot.isPresent()) {
        return Optional.of(RouteTerminals.depotCodeLabel(depot.get().stationCode()));
      }
      StopRef label = resolveStopRef(stops, resolved, RouteTerminals.endOfRouteLabelIndex(stops));
      if (label.name().isPresent()) {
        return label.name();
      }
    }
    return resolveNodeStationName(lastNodeId);
  }

  private StopRef resolveStopRef(
      List<RouteStop> stops, List<StopStation> resolved, OptionalInt index) {
    if (index.isEmpty()) {
      return new StopRef("", Optional.empty());
    }
    RouteStop stop = stops.get(index.getAsInt());
    String nodeId = resolveStopNodeId(stop);
    if (nodeId.isEmpty()) {
      return new StopRef("", Optional.empty());
    }
    return new StopRef(nodeId, resolved.get(index.getAsInt()).stationName());
  }

  private record StopRef(String nodeId, Optional<String> name) {}

  /**
   * 解析 RouteStop 的 nodeId。
   *
   * <p>优先级：waypointNodeId → DYNAMIC placeholder。
   */
  private String resolveStopNodeId(RouteStop stop) {
    if (stop.waypointNodeId().isPresent()) {
      return stop.waypointNodeId().get();
    }
    Optional<DynamicStopMatcher.DynamicSpec> dynamicSpec =
        DynamicStopMatcher.parseDynamicSpec(stop);
    if (dynamicSpec.isPresent()) {
      return dynamicSpec.get().toPlaceholderNodeId();
    }
    return "";
  }

  /** 节点所属车站的站名（查不到车站记录退回站码）；非车站节点为空。与停靠表同一口径（{@link RouteTerminals#stationIdentityOfNode}）。 */
  private Optional<String> resolveNodeStationName(String nodeId) {
    if (nodeId == null || nodeId.isBlank()) {
      return Optional.empty();
    }
    Optional<StationDirectory.StationEntry> entry = snapshot().stationOfNode(nodeId);
    if (entry.isPresent()) {
      return Optional.of(entry.get().name());
    }
    return RouteTerminals.stationIdentityOfNode(nodeId).map(RouteTerminals.StationRef::stationCode);
  }

  private StopInfo convertStopInfo(
      RouteStop stop, StopStation station, int sequence, Optional<LineRef> lineChange) {
    // 检查是否为 DYNAMIC stop
    Optional<DynamicStopMatcher.DynamicSpec> dynamicSpec =
        DynamicStopMatcher.parseDynamicSpec(stop);
    boolean isDynamic = dynamicSpec.isPresent();

    // 解析 nodeId：优先 waypointNodeId，其次 DYNAMIC placeholder
    String nodeId = stop.waypointNodeId().orElse("");
    if (nodeId.isEmpty() && isDynamic) {
      nodeId = dynamicSpec.get().toPlaceholderNodeId();
    }

    return new StopInfo(
        sequence,
        nodeId,
        station.stationName(),
        stop.dwellSeconds().orElse(0),
        convertPassType(stop.passType()),
        isDynamic,
        station.stationId(),
        station.stationCode(),
        lineChange);
  }

  private PassType convertPassType(RouteStopPassType type) {
    if (type == null) {
      return PassType.STOP;
    }
    return switch (type) {
      case STOP -> PassType.STOP;
      case PASS -> PassType.PASS;
      case TERMINATE -> PassType.TERMINATE;
    };
  }
}
