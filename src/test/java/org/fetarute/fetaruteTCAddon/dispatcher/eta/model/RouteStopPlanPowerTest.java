package org.fetarute.fetaruteTCAddon.dispatcher.eta.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.company.model.RouteStopPassType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RoutePowerModes;
import org.junit.jupiter.api.Test;

/** 到站预测的停站秒数：多模车做涉及内燃换模的那一站加换模时间，与编表、现场停站同一口径。 */
class RouteStopPlanPowerTest {

  @Test
  void dieselSwitchStopsDwellLonger() {
    UUID route = UUID.randomUUID();
    List<RouteStop> stops =
        List.of(
            new RouteStop(
                route,
                0,
                Optional.empty(),
                Optional.of("A"),
                Optional.of(20),
                RouteStopPassType.STOP,
                Optional.of("POWER:ptg6")),
            new RouteStop(
                route,
                1,
                Optional.empty(),
                Optional.of("B"),
                Optional.of(20),
                RouteStopPassType.STOP,
                Optional.of("POWER:diesel")),
            new RouteStop(
                route,
                2,
                Optional.empty(),
                Optional.of("C"),
                Optional.of(20),
                RouteStopPassType.STOP,
                Optional.empty()));
    List<NodeId> nodes = List.of(NodeId.of("A"), NodeId.of("B"), NodeId.of("C"));

    RouteStopPlan plan = RouteStopPlan.of(nodes, nodes, stops);

    assertEquals(20, plan.plannedDwellSec(0));
    assertEquals(20 + RoutePowerModes.DIESEL_SWITCH_SECONDS, plan.plannedDwellSec(1));
    assertEquals(20, plan.plannedDwellSec(2));
  }
}
