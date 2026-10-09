package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinitionCache;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteId;
import org.junit.jupiter.api.Test;

/** 预先指定的站台（叫来的车）：时刻表没有排定时，选台偏好与站牌都读它；时刻表排定的站台优先。 */
class PinnedPlatformTest {

  private static final UUID ROUTE_ID = UUID.randomUUID();
  private static final RouteDefinition ROUTE =
      new RouteDefinition(
          new RouteId("SURC:WS:WS-N"),
          List.of(NodeId.of("SURC:S:AAA:1"), NodeId.of("DYNAMIC:SURC:S:PPK:[1:3]")),
          Optional.empty());

  @Test
  void aPinnedPlatformFillsInWhenTheTimetableHasNone() {
    StationStopCoordinator stops = coordinator();
    stops.setPinnedPlatforms(
        (train, routeId, index) ->
            train.equals("called") && routeId.equals(ROUTE_ID) && index == 1
                ? Optional.of("SURC:S:PPK:2")
                : Optional.empty());

    assertEquals(Optional.of(NodeId.of("SURC:S:PPK:2")), stops.plannedPlatform("called", ROUTE, 1));
    assertEquals(Optional.of(NodeId.of("SURC:S:PPK:2")), stops.displayPlatform("called", ROUTE, 1));
    assertEquals(Optional.empty(), stops.plannedPlatform("other", ROUTE, 1));
  }

  @Test
  void theTimetablePlanWinsOverAPin() {
    StationStopCoordinator stops = coordinator();
    stops.setPinnedPlatforms((train, routeId, index) -> Optional.of("SURC:S:PPK:2"));
    ScheduledDeparturePlan plan = mock(ScheduledDeparturePlan.class);
    when(plan.plannedPlatformOf(any(), any(), anyInt())).thenReturn(Optional.of("SURC:S:PPK:3"));
    stops.setPlan(plan);

    assertEquals(Optional.of(NodeId.of("SURC:S:PPK:3")), stops.plannedPlatform("called", ROUTE, 1));
  }

  private static StationStopCoordinator coordinator() {
    RouteDefinitionCache cache = mock(RouteDefinitionCache.class);
    when(cache.findUuid(ROUTE.id())).thenReturn(Optional.of(ROUTE_ID));
    return TestServices.minimal(new ArrayList<>(), cache).stationStops();
  }
}
