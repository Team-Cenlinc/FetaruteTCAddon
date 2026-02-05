package org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteId;

/**
 * 占用请求上下文：描述“列车想占用哪些资源”。
 *
 * <p>占用释放由事件驱动触发，不再依赖 travelTime。
 *
 * <p>corridorDirections 用于单线走廊的方向锁判定（同向跟驰、对向互斥）。
 *
 * <p>conflictEntryOrders 用于冲突区放行与死锁解除：记录列车在 lookahead 路径中“首次进入某冲突区”的边序号（越小越接近当前列车）。
 */
public record OccupancyRequest(
    String trainName,
    Optional<RouteId> routeId,
    Instant now,
    List<OccupancyResource> resources,
    Map<String, CorridorDirection> corridorDirections,
    Map<String, Integer> conflictEntryOrders,
    int priority) {

  public OccupancyRequest {
    Objects.requireNonNull(trainName, "trainName");
    Objects.requireNonNull(routeId, "routeId");
    Objects.requireNonNull(now, "now");
    Objects.requireNonNull(resources, "resources");
    Objects.requireNonNull(corridorDirections, "corridorDirections");
    Objects.requireNonNull(conflictEntryOrders, "conflictEntryOrders");
    if (trainName.isBlank()) {
      throw new IllegalArgumentException("trainName 不能为空");
    }
    resources = List.copyOf(resources);
    corridorDirections = Map.copyOf(corridorDirections);
    conflictEntryOrders = Map.copyOf(conflictEntryOrders);
  }

  public OccupancyRequest(
      String trainName,
      Optional<RouteId> routeId,
      Instant now,
      List<OccupancyResource> resources,
      Map<String, CorridorDirection> corridorDirections,
      int priority) {
    this(trainName, routeId, now, resources, corridorDirections, Map.of(), priority);
  }

  public OccupancyRequest(
      String trainName,
      Optional<RouteId> routeId,
      Instant now,
      List<OccupancyResource> resources,
      Map<String, CorridorDirection> corridorDirections) {
    this(trainName, routeId, now, resources, corridorDirections, Map.of(), 0);
  }

  public List<OccupancyResource> resourceList() {
    return resources;
  }
}
