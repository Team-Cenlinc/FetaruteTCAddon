package org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteId;

/**
 * 单条占用记录，包含释放时间与 headway。
 *
 * <p>释放后还需等待 headway 才可被下一列车进入。
 */
public record OccupancyClaim(
    OccupancyResource resource,
    String trainName,
    Optional<RouteId> routeId,
    Instant acquiredAt,
    Instant releaseAt,
    Duration headway) {

  public OccupancyClaim {
    Objects.requireNonNull(resource, "resource");
    Objects.requireNonNull(trainName, "trainName");
    Objects.requireNonNull(routeId, "routeId");
    Objects.requireNonNull(acquiredAt, "acquiredAt");
    Objects.requireNonNull(releaseAt, "releaseAt");
    Objects.requireNonNull(headway, "headway");
    if (trainName.isBlank()) {
      throw new IllegalArgumentException("trainName 不能为空");
    }
    if (headway.isNegative()) {
      throw new IllegalArgumentException("headway 不能为负");
    }
  }
}
