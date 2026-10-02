package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.scope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableConflictChecker;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableTestFixtures;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableTimingCalculator;
import org.junit.jupiter.api.Test;

/**
 * 足迹是按展开后的路径算的，不是按申报的停靠点。
 *
 * <p>两条 route 申报的车站互不相同，但中间那段走行边是同一条：足迹必须相交。这正是"18 个共用点只是下界"那句话在用例里的形态。
 */
class TimetableFootprintTest {

  private static final String A = "OP:S:A:1";
  private static final String B = "OP:X:B:1";
  private static final String C = "OP:S:C:1";
  private static final String D = "OP:S:D:1";

  /** RA: A→C、RB: A→D，两条都要经过 A–B 这条边；申报的停靠点只在 A 重合，但边也算。 */
  @Test
  void footprintUsesExpandedEdgesNotDeclaredStops() {
    // A – B – C，B – D：B 是普通路径点。
    RailGraph graph = graph();
    TimetableConflictChecker.GraphIndex index = TimetableConflictChecker.GraphIndex.of(graph);
    TimetableFootprint ra = footprint("RA", List.of(A, C), graph, index);
    TimetableFootprint rb = footprint("RB", List.of(A, D), graph, index);

    assertTrue(
        ra.resourceKeys().contains("edge:" + A + "~" + B), () -> ra.resourceKeys().toString());
    assertTrue(rb.resourceKeys().contains("edge:" + A + "~" + B));
    assertTrue(ra.sharedKeys(rb).contains("edge:" + A + "~" + B), "共用的走行边必须出现在交集里");
    assertTrue(ra.sharedKeys(rb).contains("platform:" + A), "共同的起点站台也在交集里");
    assertEquals(ra.sharedWith(rb), ra.sharedKeys(rb).size());
  }

  /** 不共任何资源的两条 route：足迹交集为空。A→B 只用 A–B 边；C→D 走 C–B–D，穿过的 B 是普通路径点，不是站台。 */
  @Test
  void disjointRoutesShareNothing() {
    RailGraph graph = graph();
    TimetableConflictChecker.GraphIndex index = TimetableConflictChecker.GraphIndex.of(graph);
    TimetableFootprint ab = footprint("AB", List.of(A, B), graph, index);
    TimetableFootprint cd = footprint("CD", List.of(C, D), graph, index);

    assertEquals(0, ab.sharedWith(cd), () -> ab.sharedKeys(cd).toString());
  }

  private static RailGraph graph() {
    java.util.Map<
            org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId,
            org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode>
        nodes = new java.util.LinkedHashMap<>();
    for (String id : List.of(A, C, D)) {
      nodes.put(
          org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId.of(id),
          new org.fetarute.fetaruteTCAddon.dispatcher.graph.SignRailNode(
              org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId.of(id),
              org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType.STATION,
              new org.bukkit.util.Vector(0, 64, 0),
              java.util.Optional.empty(),
              java.util.Optional.empty()));
    }
    nodes.put(
        org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId.of(B),
        new org.fetarute.fetaruteTCAddon.dispatcher.graph.SignRailNode(
            org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId.of(B),
            org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType.WAYPOINT,
            new org.bukkit.util.Vector(1, 64, 0),
            java.util.Optional.empty(),
            java.util.Optional.empty()));
    java.util.Map<
            org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId,
            org.fetarute.fetaruteTCAddon.dispatcher.graph.RailEdge>
        edges = new java.util.LinkedHashMap<>();
    edge(edges, A, B);
    edge(edges, B, C);
    edge(edges, B, D);
    return new org.fetarute.fetaruteTCAddon.dispatcher.graph.SimpleRailGraph(
        nodes, edges, java.util.Set.of());
  }

  private static void edge(
      java.util.Map<
              org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId,
              org.fetarute.fetaruteTCAddon.dispatcher.graph.RailEdge>
          edges,
      String from,
      String to) {
    var a = org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId.of(from);
    var b = org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId.of(to);
    var id = org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId.undirected(a, b);
    edges.put(
        id,
        new org.fetarute.fetaruteTCAddon.dispatcher.graph.RailEdge(
            id, a, b, 100, 10.0, true, java.util.Optional.empty()));
  }

  private static TimetableFootprint footprint(
      String code, List<String> nodes, RailGraph graph, TimetableConflictChecker.GraphIndex index) {
    UUID id = TimetableTestFixtures.routeId(code);
    RouteDefinition route = TimetableTestFixtures.route(code, nodes);
    List<RouteStop> stops = TimetableTestFixtures.stops(id, nodes.size(), 0);
    TimetableTimingCalculator.TimingResult timing =
        new TimetableTimingCalculator()
            .compute(graph, TimetableTestFixtures.perEdgeSpeedModel(), route, stops, Duration.ZERO);
    assertTrue(timing.ok(), () -> code + ": " + timing.failure());
    TimetableConflictChecker.RouteProfile profile =
        new TimetableConflictChecker.RouteProfile(
            id,
            code,
            timing.stops(),
            timing.segments(),
            TimetableConflictChecker.platformsOf(timing.stops(), stops, route.waypoints()));
    return TimetableFootprint.of(id, code, List.of(profile), index);
  }
}
