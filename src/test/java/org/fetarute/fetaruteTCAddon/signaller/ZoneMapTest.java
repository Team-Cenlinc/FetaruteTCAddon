package org.fetarute.fetaruteTCAddon.signaller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.query.RailGraphPathFinder;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteId;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.RouteProgressRegistry;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyResource;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspect;
import org.fetarute.fetaruteTCAddon.display.pids.yard.YardArea;
import org.fetarute.fetaruteTCAddon.display.pids.yard.YardFixtures;
import org.junit.jupiter.api.Test;

/** 控制区资源与前方进路：出入口区间不算控制区资源，入口与股道认得出来。 */
class ZoneMapTest {

  private static final EdgeId WEST = EdgeId.undirected(YardFixtures.IA, YardFixtures.SW1);
  private static final EdgeId EAST = EdgeId.undirected(YardFixtures.SW2, YardFixtures.IB);

  private static ZoneMap map() {
    RailGraph graph = YardFixtures.graph();
    YardArea area =
        YardArea.of(graph, "SURC", List.of(YardArea.Member.station("ABC"))).orElseThrow();
    return ZoneMap.of(graph, area);
  }

  @Test
  void exitEdgesAreNotZoneResources() {
    ZoneMap map = map();

    assertFalse(map.contains(OccupancyResource.forEdge(WEST)));
    assertFalse(map.contains(OccupancyResource.forNode(YardFixtures.IA)));
    assertTrue(map.contains(OccupancyResource.forNode(YardFixtures.SW1)));
    assertTrue(map.contains(OccupancyResource.forConflict("switcher:" + YardFixtures.SW1.value())));
    assertTrue(
        map.contains(
            OccupancyResource.forEdge(EdgeId.undirected(YardFixtures.T1, YardFixtures.P1))));
    assertEquals(2, map.exits().size());
  }

  @Test
  void entryIsTheExitEdgeInTheRequestOrTheOnlyExitIntoItsFirstNode() {
    ZoneMap map = map();

    assertEquals(
        Optional.of(WEST),
        map.entryOf(
            List.of(OccupancyResource.forEdge(WEST), OccupancyResource.forNode(YardFixtures.SW1))));
    assertEquals(
        Optional.of(EAST), map.entryOf(List.of(OccupancyResource.forNode(YardFixtures.SW2))));
    assertEquals(
        Optional.empty(),
        map.entryOf(List.of(OccupancyResource.forNode(NodeId.of("SURC:XYZ:ABC:1:02")))));
  }

  @Test
  void routeAheadFindsEntryZoneEdgesAndPlatform() {
    ZoneMap map = map();
    RouteDefinition route =
        new RouteDefinition(
            RouteId.of("SURC:L1:R1"),
            List.of(YardFixtures.IA, YardFixtures.P1, YardFixtures.IB),
            Optional.empty());
    RouteProgressRegistry.RouteProgressEntry progress =
        new RouteProgressRegistry.RouteProgressEntry(
            "T1",
            UUID.randomUUID(),
            route.id(),
            0,
            Optional.of(YardFixtures.P1),
            Optional.of(YardFixtures.IA),
            SignalAspect.PROCEED,
            Instant.EPOCH);

    RouteAhead ahead =
        RouteAhead.of(map, progress, route, Optional.empty(), new RailGraphPathFinder());

    assertTrue(ahead.reaches());
    assertEquals(Optional.of(WEST), ahead.entry());
    assertEquals(Optional.of(YardFixtures.P1), ahead.target());
    assertEquals(
        List.of(
            EdgeId.undirected(YardFixtures.SW1, YardFixtures.T1),
            EdgeId.undirected(YardFixtures.T1, YardFixtures.P1),
            EdgeId.undirected(YardFixtures.P1, YardFixtures.SW2)),
        ahead.zoneEdges());
  }

  @Test
  void dynamicStopReplacesThePlaceholderPlatform() {
    ZoneMap map = map();
    RouteDefinition route =
        new RouteDefinition(
            RouteId.of("SURC:L1:R1"),
            List.of(YardFixtures.IA, YardFixtures.P1, YardFixtures.IB),
            Optional.empty());
    RouteProgressRegistry.RouteProgressEntry progress =
        new RouteProgressRegistry.RouteProgressEntry(
            "T1",
            UUID.randomUUID(),
            route.id(),
            0,
            Optional.of(YardFixtures.P1),
            Optional.of(YardFixtures.IA),
            SignalAspect.PROCEED,
            Instant.EPOCH);

    RouteAhead ahead =
        RouteAhead.of(
            map,
            progress,
            route,
            Optional.of(new RouteAhead.DynamicStop(1, YardFixtures.P2)),
            new RailGraphPathFinder());

    assertEquals(Optional.of(YardFixtures.P2), ahead.target());
    assertTrue(ahead.zoneEdges().contains(EdgeId.undirected(YardFixtures.SW1, YardFixtures.P2)));
  }
}
