package org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteId;

/**
 * 单条占用记录，仅描述“当前占用者”与 headway 配置。
 *
 * <p>释放由事件驱动触发，headway 不再依赖 releaseAt 计算。
 */
public record OccupancyClaim(
    OccupancyResource resource,
    String trainName,
    Optional<RouteId> routeId,
    Instant acquiredAt,
    Duration headway) {

  public OccupancyClaim {
    Objects.requireNonNull(resource, "resource");
    Objects.requireNonNull(trainName, "trainName");
    Objects.requireNonNull(routeId, "routeId");
    Objects.requireNonNull(acquiredAt, "acquiredAt");
    Objects.requireNonNull(headway, "headway");
    if (trainName.isBlank()) {
      throw new IllegalArgumentException("trainName 不能为空");
    }
    if (headway.isNegative()) {
      throw new IllegalArgumentException("headway 不能为负");
    }
  }
}
