package org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteId;

/**
 * 占用 headway 规则：可按 route/资源类型动态返回等待时间。
 *
 * <p>用于把“释放后仍需等待 X 秒”抽象成策略接口。
 */
public interface HeadwayRule {

  Duration headwayFor(Optional<RouteId> routeId, OccupancyResource resource);

  /** 返回固定 headway 的简单规则，便于“最小可用版本”与测试使用。 */
  static HeadwayRule fixed(Duration duration) {
    Objects.requireNonNull(duration, "duration");
    if (duration.isNegative()) {
      throw new IllegalArgumentException("duration 不能为负");
    }
    return (routeId, resource) -> duration;
  }
}
