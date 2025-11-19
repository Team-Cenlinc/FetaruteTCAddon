package org.fetarute.fetaruteTCAddon.dispatcher.schedule;

import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteId;

/**
 * 表示一次发车指令，用于排班系统提交给车库或站台。
 */
public record DispatchOrder(
        RouteId routeId,
        long earliestDepartureTick,
        int priority
) {
}
