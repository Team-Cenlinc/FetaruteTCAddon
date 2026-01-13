package org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteId;

/**
 * 占用请求上下文：描述“列车想占用哪些资源，以及预计占用多久”。
 *
 * <p>travelTime 用于估算 releaseAt，运行时可在更精确的释放点更新/覆盖。
 */
public record OccupancyRequest(
    String trainName,
    Optional<RouteId> routeId,
    Instant now,
    Duration travelTime,
    List<OccupancyResource> resources) {

  public OccupancyRequest {
    Objects.requireNonNull(trainName, "trainName");
    Objects.requireNonNull(routeId, "routeId");
    Objects.requireNonNull(now, "now");
    Objects.requireNonNull(travelTime, "travelTime");
    Objects.requireNonNull(resources, "resources");
    if (trainName.isBlank()) {
      throw new IllegalArgumentException("trainName 不能为空");
    }
    if (travelTime.isNegative()) {
      throw new IllegalArgumentException("travelTime 不能为负");
    }
    resources = List.copyOf(resources);
  }

  public List<OccupancyResource> resourceList() {
    return resources;
  }
}
