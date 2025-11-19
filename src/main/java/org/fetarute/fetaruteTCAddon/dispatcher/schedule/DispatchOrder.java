package org.fetarute.fetaruteTCAddon.dispatcher.schedule;

import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteId;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * 表示一次发车指令，用于排班系统提交给车库或站台。
 * 所有时间基于真实世界时钟而非游戏 tick。
 */
public record DispatchOrder(
        RouteId routeId,
        Instant earliestDepartureTime,
        Duration dwellDuration,
        int priority
) {

    public DispatchOrder {
        Objects.requireNonNull(routeId, "routeId");
        Objects.requireNonNull(earliestDepartureTime, "earliestDepartureTime");
        Objects.requireNonNull(dwellDuration, "dwellDuration");
        if (dwellDuration.isNegative()) {
            throw new IllegalArgumentException("停站时间不能为负");
        }
    }
}
