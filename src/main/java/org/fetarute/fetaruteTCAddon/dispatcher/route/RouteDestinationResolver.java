package org.fetarute.fetaruteTCAddon.dispatcher.route;

import java.util.List;
import java.util.Optional;
import org.fetarute.fetaruteTCAddon.company.model.Route;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.company.model.RouteStopPassType;
import org.fetarute.fetaruteTCAddon.company.model.Station;
import org.fetarute.fetaruteTCAddon.storage.api.StorageProvider;

/** 解析 route 的运营目的地，用于列车命名、标签与调度诊断保持同一口径。 */
public final class RouteDestinationResolver {

  private RouteDestinationResolver() {}

  /**
   * 解析 route 的目的地。
   *
   * <p>优先取最后一个 {@link RouteStopPassType#TERMINATE}，其次取最后一个 STOP，最后回退到停靠表末尾。解析结果同时保留站名与站码； 列车名应使用
   * {@link DestinationInfo#code()}，避免 CRET 出车时误用 route/交路名称。
   *
   * @param provider 存储提供者
   * @param route route 实体
   * @return 目的地信息；停靠表缺失时为空
   */
  public static Optional<DestinationInfo> resolve(StorageProvider provider, Route route) {
    if (provider == null || route == null) {
      return Optional.empty();
    }
    List<RouteStop> stops = provider.routeStops().listByRoute(route.id());
    if (stops.isEmpty()) {
      return Optional.empty();
    }
    RouteStop candidate = selectDestinationStop(stops);
    if (candidate == null) {
      return Optional.of(new DestinationInfo(route.name(), route.code()));
    }

    if (candidate.stationId().isPresent()) {
      Optional<Station> stationOpt = provider.stations().findById(candidate.stationId().get());
      if (stationOpt.isPresent()) {
        Station station = stationOpt.get();
        return Optional.of(new DestinationInfo(station.name(), station.code()));
      }
    }

    Optional<DynamicStopMatcher.DynamicSpec> dynamicSpec =
        DynamicStopMatcher.parseDynamicSpec(candidate);
    if (dynamicSpec.isPresent() && dynamicSpec.get().isStation()) {
      DynamicStopMatcher.DynamicSpec spec = dynamicSpec.get();
      return Optional.of(new DestinationInfo(spec.nodeName(), spec.nodeName()));
    }

    if (candidate.waypointNodeId().isPresent()) {
      String node = candidate.waypointNodeId().get();
      String[] parts = node.split(":", -1);
      if (parts.length >= 4 && "S".equalsIgnoreCase(parts[1])) {
        String stationCode = parts[2];
        return Optional.of(new DestinationInfo(stationCode, stationCode));
      }
      return Optional.of(new DestinationInfo(node, node));
    }

    return Optional.of(new DestinationInfo(route.name(), route.code()));
  }

  private static RouteStop selectDestinationStop(List<RouteStop> stops) {
    RouteStop candidate = null;
    for (RouteStop stop : stops) {
      if (stop != null && stop.passType() == RouteStopPassType.TERMINATE) {
        candidate = stop;
      }
    }
    if (candidate != null) {
      return candidate;
    }
    for (RouteStop stop : stops) {
      if (stop != null && stop.passType() == RouteStopPassType.STOP) {
        candidate = stop;
      }
    }
    return candidate != null ? candidate : stops.get(stops.size() - 1);
  }

  /** 目的地展示信息。 */
  public record DestinationInfo(String name, String code) {
    public DestinationInfo {
      name = name == null || name.isBlank() ? "?" : name.trim();
      code = code == null || code.isBlank() ? name : code.trim();
    }
  }
}
