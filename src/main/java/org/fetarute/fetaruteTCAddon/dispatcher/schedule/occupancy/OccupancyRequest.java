package org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteId;

/**
 * 占用请求上下文：描述“列车想占用哪些资源”。
 *
 * <p>占用释放由事件驱动触发，不再依赖 travelTime。
 */
public record OccupancyRequest(
    String trainName, Optional<RouteId> routeId, Instant now, List<OccupancyResource> resources) {

  public OccupancyRequest {
    Objects.requireNonNull(trainName, "trainName");
    Objects.requireNonNull(routeId, "routeId");
    Objects.requireNonNull(now, "now");
    Objects.requireNonNull(resources, "resources");
    if (trainName.isBlank()) {
      throw new IllegalArgumentException("trainName 不能为空");
    }
    resources = List.copyOf(resources);
  }

  public List<OccupancyResource> resourceList() {
    return resources;
  }
}
