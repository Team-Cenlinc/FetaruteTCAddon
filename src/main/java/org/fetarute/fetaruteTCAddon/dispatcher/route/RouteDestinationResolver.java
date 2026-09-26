package org.fetarute.fetaruteTCAddon.dispatcher.route;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.function.Function;
import org.fetarute.fetaruteTCAddon.company.model.Route;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.company.model.Station;
import org.fetarute.fetaruteTCAddon.storage.api.StorageProvider;

/** 解析 route 的运营目的地，用于列车命名、标签与调度诊断保持同一口径。 */
public final class RouteDestinationResolver {

  private RouteDestinationResolver() {}

  /**
   * 解析 route 的目的地。
   *
   * <p>选站规则见 {@link RouteTerminals#namingIndex}：TERMINATE 优先，其次最后一个 STOP；选中的 stop 落在折返线等
   * 非车站节点时退回前一个载客站。解析结果同时保留站名与站码；列车名应使用 {@link DestinationInfo#code()}，避免 CRET 出车时误用 route/交路名称。
   *
   * @param provider 存储提供者
   * @param route route 实体
   * @return 目的地信息；停靠表缺失时为空
   */
  public static Optional<DestinationInfo> resolve(StorageProvider provider, Route route) {
    if (provider == null || route == null) {
      return Optional.empty();
    }
    return resolve(
        provider.routeStops().listByRoute(route.id()),
        stationId -> provider.stations().findById(stationId),
        route.name(),
        route.code());
  }

  /**
   * 按停靠表解析目的地，站点经存储查询（供运行时折返改名复用同一口径）。
   *
   * @param stops 停靠表（按 sequence 排序）
   * @param provider 存储；为空时只凭停靠表自身解析
   * @param fallbackName 停靠表无法给出站点时的显示名
   * @param fallbackCode 停靠表无法给出站点时的代码
   * @return 目的地信息；停靠表缺失时为空
   */
  public static Optional<DestinationInfo> resolve(
      List<RouteStop> stops,
      Optional<StorageProvider> provider,
      String fallbackName,
      String fallbackCode) {
    Function<UUID, Optional<Station>> stations =
        provider == null || provider.isEmpty()
            ? stationId -> Optional.empty()
            : stationId -> provider.get().stations().findById(stationId);
    return resolve(stops, stations, fallbackName, fallbackCode);
  }

  /**
   * 按停靠表解析目的地（不绑定存储实体）。
   *
   * @param stops 停靠表（按 sequence 排序）
   * @param stations 按 stationId 查站点；可返回空
   * @param fallbackName 停靠表无法给出站点时的显示名
   * @param fallbackCode 停靠表无法给出站点时的代码
   * @return 目的地信息；停靠表缺失时为空
   */
  public static Optional<DestinationInfo> resolve(
      List<RouteStop> stops,
      Function<UUID, Optional<Station>> stations,
      String fallbackName,
      String fallbackCode) {
    if (stops == null || stops.isEmpty()) {
      return Optional.empty();
    }
    OptionalInt index = RouteTerminals.namingIndex(stops);
    RouteStop candidate = index.isPresent() ? stops.get(index.getAsInt()) : null;
    if (candidate == null) {
      return Optional.of(new DestinationInfo(fallbackName, fallbackCode));
    }

    if (candidate.stationId().isPresent() && stations != null) {
      Optional<Station> stationOpt = stations.apply(candidate.stationId().get());
      if (stationOpt != null && stationOpt.isPresent()) {
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

    return Optional.of(new DestinationInfo(fallbackName, fallbackCode));
  }

  /** 目的地展示信息。 */
  public record DestinationInfo(String name, String code) {
    public DestinationInfo {
      name = name == null || name.isBlank() ? "?" : name.trim();
      code = code == null || code.isBlank() ? name : code.trim();
    }
  }
}
