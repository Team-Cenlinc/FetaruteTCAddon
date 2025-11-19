package org.fetarute.fetaruteTCAddon.dispatcher.schedule;

import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteId;

import java.time.Duration;

/**
 * Headway（列车间隔）策略接口，可根据线路或时段动态返回目标间隔。
 */
public interface HeadwayPolicy {

    Duration targetHeadway(RouteId routeId);
}
