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

  /** 大区的站场推到节点上限被截断时，资源比不全：小区的资源有不在大区里的，改按成员判嵌套；没截断时仍只按资源判。 */
  @Test
  void aTruncatedOuterAreaNestsByMembers() {
    RailGraph graph = YardFixtures.graph();
    java.util.Set<NodeId> many = new java.util.HashSet<>();
    for (int i = 0; i < YardArea.MAX_NODES; i++) {
      many.add(NodeId.of("SURC:ABC:XYZ:9:" + i));
    }
    ZoneMap truncated =
        ZoneMap.of(graph, new YardArea(many, java.util.Set.of(), List.of(), List.of(), List.of()));
    ZoneMap small =
        ZoneMap.of(
            graph,
            new YardArea(
                java.util.Set.of(YardFixtures.P1),
                java.util.Set.of(),
                List.of(),
                List.of(),
                List.of()));
    java.util.Set<YardArea.Member> outer =
        java.util.Set.of(YardArea.Member.station("ABC"), YardArea.Member.station("DEF"));
    java.util.Set<YardArea.Member> inner = java.util.Set.of(YardArea.Member.station("ABC"));

    assertTrue(truncated.area().truncated());
    assertTrue(truncated.nests(small, outer, inner));
    assertFalse(truncated.nests(small, outer, outer), "成员一样多不算嵌套");
    ZoneMap whole = map();
    assertFalse(whole.area().truncated());
    assertFalse(
        whole.nests(
            ZoneMap.of(
                graph,
                new YardArea(
                    java.util.Set.of(NodeId.of("SURC:S:QQQ:1")),
                    java.util.Set.of(),
                    List.of(),
                    List.of(),
                    List.of())),
            outer,
            inner),
        "没截断时仍按资源判");
  }

  /** 区内起步的车（车库出车、区内折返）没有入口：以申请里第一条区内区间代替。 */
  @Test
  void aStartInsideTheZoneIsItsFirstZoneEdge() {
    ZoneMap map = map();
    EdgeId first = EdgeId.undirected(YardFixtures.T1, YardFixtures.P1);
    List<OccupancyResource> requested =
        List.of(
            OccupancyResource.forNode(YardFixtures.P1),
            OccupancyResource.forEdge(EdgeId.undirected(YardFixtures.P1, YardFixtures.T1)),
            OccupancyResource.forNode(YardFixtures.T1),
            OccupancyResource.forEdge(EdgeId.undirected(YardFixtures.T1, YardFixtures.SW1)));

    assertEquals(Optional.empty(), map.entryOf(requested));
    assertEquals(Optional.of(first), map.startOf(requested));
    assertEquals(Optional.empty(), map.startOf(List.of(OccupancyResource.forEdge(WEST))));
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
