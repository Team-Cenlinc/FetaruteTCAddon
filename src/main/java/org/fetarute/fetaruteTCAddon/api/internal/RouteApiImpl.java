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

    // 终点站名 - RouteMetadata 没有 terminalStation 字段，使用 displayName
    Optional<String> terminalName = def.metadata().flatMap(RouteMetadata::displayName);

    // 总距离（需要从图计算，这里简化为 0）
    int totalDistance = 0;

    return new RouteDetail(
        info, List.copyOf(waypoints), List.copyOf(stops), terminalName, totalDistance);
  }

  private StopInfo convertStopInfo(RouteStop stop, int sequence) {
    // 尝试获取站点名称
    Optional<String> stationName = Optional.empty();
    if (storageProvider != null && stop.stationId().isPresent()) {
      Optional<Station> stationOpt = storageProvider.stations().findById(stop.stationId().get());
      stationName = stationOpt.map(Station::name);
    }

    String nodeId = stop.waypointNodeId().orElse("");

    return new StopInfo(
        sequence,
        nodeId,
        stationName,
        stop.dwellSeconds().orElse(0),
        convertPassType(stop.passType()));
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
