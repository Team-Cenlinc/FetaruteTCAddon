package org.fetarute.fetaruteTCAddon.api.internal;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.api.route.RouteApi;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.company.model.RouteStopPassType;
import org.fetarute.fetaruteTCAddon.company.model.Station;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.route.DynamicStopMatcher;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinitionCache;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteMetadata;
import org.fetarute.fetaruteTCAddon.storage.api.StorageProvider;

/**
 * RouteApi 内部实现：桥接到 RouteDefinitionCache。
 *
 * <p>仅供内部使用，外部插件应通过 {@link org.fetarute.fetaruteTCAddon.api.FetaruteApi} 访问。
 */
public final class RouteApiImpl implements RouteApi {

  private final RouteDefinitionCache routeDefinitions;
  private final StorageProvider storageProvider;

  public RouteApiImpl(RouteDefinitionCache routeDefinitions, StorageProvider storageProvider) {
    this.routeDefinitions = Objects.requireNonNull(routeDefinitions, "routeDefinitions");
    this.storageProvider = storageProvider;
  }

  @Override
  public Collection<RouteInfo> listRoutes() {
    List<RouteInfo> result = new ArrayList<>();

    for (RouteDefinition def : routeDefinitions.snapshot().values()) {
      result.add(convertRouteInfo(def));
    }

    return List.copyOf(result);
  }

  @Override
  public Optional<RouteDetail> getRoute(UUID routeId) {
    if (routeId == null) {
      return Optional.empty();
    }

    return routeDefinitions.findById(routeId).map(this::convertRouteDetail);
  }

  @Override
  public Optional<RouteDetail> findByCode(String operatorCode, String lineCode, String routeCode) {
    if (operatorCode == null || lineCode == null || routeCode == null) {
      return Optional.empty();
    }

    return routeDefinitions
        .findByCodes(operatorCode, lineCode, routeCode)
        .map(this::convertRouteDetail);
  }

  @Override
  public int routeCount() {
    return routeDefinitions.snapshot().size();
  }

  private RouteInfo convertRouteInfo(RouteDefinition def) {
    Optional<RouteMetadata> metaOpt = def.metadata();
    // RouteMetadata 字段: operator, lineId, serviceId, displayName
    String operatorCode = metaOpt.map(RouteMetadata::operator).orElse("");
    String lineCode = metaOpt.map(RouteMetadata::lineId).orElse("");
    String routeCode = metaOpt.map(RouteMetadata::serviceId).orElse("");
    Optional<String> displayName = metaOpt.flatMap(RouteMetadata::displayName);

    return new RouteInfo(
        null, // Route 实体的 UUID 需要从存储获取
        def.id().value(),
        operatorCode,
        lineCode,
        routeCode,
        displayName,
        OperationType.NORMAL);
  }

  private RouteDetail convertRouteDetail(RouteDefinition def) {
    RouteInfo info = convertRouteInfo(def);

    // 获取 waypoints
    List<String> waypoints = new ArrayList<>();
    for (NodeId nodeId : def.waypoints()) {
      waypoints.add(nodeId.value());
    }

    // 获取 stops
    List<StopInfo> stops = new ArrayList<>();
    List<RouteStop> routeStops = routeDefinitions.listStops(def.id());
    int seq = 1;
    for (RouteStop stop : routeStops) {
      stops.add(convertStopInfo(stop, seq++));
    }

    // 解析终点信息
    TerminalInfo terminal = resolveTerminalInfo(def, routeStops);

    // 总距离（需要从图计算，这里简化为 0）
    int totalDistance = 0;

    return new RouteDetail(
        info, List.copyOf(waypoints), List.copyOf(stops), terminal, totalDistance);
  }

  /**
   * 解析终点信息（EOR/EOP）。
   *
   * <p>EOR (End of Route): waypoints 列表的最后一个节点。 EOP (End of Operation): 最后一个 Station 类型的停靠点（跳过 PASS
   * 类型）。
   */
  private TerminalInfo resolveTerminalInfo(RouteDefinition def, List<RouteStop> stops) {
    // 解析 EOR（路线物理终点）
    String eorNodeId = "";
    Optional<String> eorName = Optional.empty();
    if (!def.waypoints().isEmpty()) {
      NodeId lastWaypoint = def.waypoints().get(def.waypoints().size() - 1);
      eorNodeId = lastWaypoint.value();
      eorName = resolveStationName(lastWaypoint.value());
    }

    // 解析 EOP（运营终点）：从后往前找最后一个非 PASS 的 Station 类型 stop
    String eopNodeId = "";
    Optional<String> eopName = Optional.empty();
    for (int i = stops.size() - 1; i >= 0; i--) {
      RouteStop stop = stops.get(i);
      if (stop == null || stop.passType() == RouteStopPassType.PASS) {
        continue;
      }
      // 检查是否为 Station 类型（AutoStation）
      String nodeId = resolveStopNodeId(stop);
      if (nodeId.isEmpty()) {
        continue;
      }
      if (!isStationTypeNode(nodeId, stop)) {
        continue;
      }
      eopNodeId = nodeId;
      eopName = resolveStopStationName(stop, nodeId);
      break;
    }

    // 若未找到 EOP，回退到 EOR
    if (eopNodeId.isEmpty()) {
      eopNodeId = eorNodeId;
      eopName = eorName;
    }

    return new TerminalInfo(eorNodeId, eorName, eopNodeId, eopName);
  }

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

  /**
   * 判断 nodeId 是否为 Station 类型节点。
   *
   * <p>Station 格式：{@code OP:S:NAME:TRACK}（4 段，第二段为 S）。 同时检查 DYNAMIC spec 的 nodeType。
   */
  private boolean isStationTypeNode(String nodeId, RouteStop stop) {
    // 先检查 DYNAMIC spec
    Optional<DynamicStopMatcher.DynamicSpec> dynamicSpec =
        DynamicStopMatcher.parseDynamicSpec(stop);
    if (dynamicSpec.isPresent()) {
      return dynamicSpec.get().isStation();
    }
    // 检查 nodeId 格式
    if (nodeId == null || nodeId.isBlank()) {
      return false;
    }
    String[] parts = nodeId.split(":");
    return parts.length >= 3 && "S".equalsIgnoreCase(parts[1]);
  }

  /**
   * 解析 RouteStop 的站点名称。
   *
   * <p>优先级：stationId 查询 → DYNAMIC spec nodeName → nodeId 解析。
   */
  private Optional<String> resolveStopStationName(RouteStop stop, String nodeId) {
    // 1. 通过 stationId 查询
    if (storageProvider != null && stop.stationId().isPresent()) {
      Optional<Station> stationOpt = storageProvider.stations().findById(stop.stationId().get());
      if (stationOpt.isPresent()) {
        return Optional.of(stationOpt.get().name());
      }
    }
    // 2. 从 DYNAMIC spec 提取
    Optional<DynamicStopMatcher.DynamicSpec> dynamicSpec =
        DynamicStopMatcher.parseDynamicSpec(stop);
    if (dynamicSpec.isPresent()) {
      return Optional.of(dynamicSpec.get().nodeName());
    }
    // 3. 从 nodeId 解析
    return resolveStationName(nodeId);
  }

  /**
   * 从 nodeId 解析站点名称。
   *
   * <p>Station 格式：{@code OP:S:NAME:TRACK} → 返回 NAME。
   */
  private Optional<String> resolveStationName(String nodeId) {
    if (nodeId == null || nodeId.isBlank()) {
      return Optional.empty();
    }
    String[] parts = nodeId.split(":");
    if (parts.length >= 3 && ("S".equalsIgnoreCase(parts[1]) || "D".equalsIgnoreCase(parts[1]))) {
      return Optional.of(parts[2]);
    }
    return Optional.empty();
  }

  private StopInfo convertStopInfo(RouteStop stop, int sequence) {
    // 检查是否为 DYNAMIC stop
    Optional<DynamicStopMatcher.DynamicSpec> dynamicSpec =
        DynamicStopMatcher.parseDynamicSpec(stop);
    boolean isDynamic = dynamicSpec.isPresent();

    // 解析 nodeId：优先 waypointNodeId，其次 DYNAMIC placeholder
    String nodeId = stop.waypointNodeId().orElse("");
    if (nodeId.isEmpty() && isDynamic) {
      nodeId = dynamicSpec.get().toPlaceholderNodeId();
    }

    // 解析站点名称：优先 stationId 查询，其次 DYNAMIC spec 的 nodeName
    Optional<String> stationName = Optional.empty();
    if (storageProvider != null && stop.stationId().isPresent()) {
      Optional<Station> stationOpt = storageProvider.stations().findById(stop.stationId().get());
      stationName = stationOpt.map(Station::name);
    }
    if (stationName.isEmpty() && isDynamic) {
      stationName = Optional.of(dynamicSpec.get().nodeName());
    }

    return new StopInfo(
        sequence,
        nodeId,
        stationName,
        stop.dwellSeconds().orElse(0),
        convertPassType(stop.passType()),
        isDynamic);
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
