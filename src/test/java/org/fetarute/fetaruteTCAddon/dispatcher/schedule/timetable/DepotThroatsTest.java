package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
import org.junit.jupiter.api.Test;

/**
 * 车库咽喉按路网自己算：出库走到第一个车站前的边，与回库从最后一个车站走到车库的边，交集才是咽喉。
 *
 * <p>路网：车库 DEP（两股）经 W1 或 W2 到车站 S，再经 T 到终点 E。每条边 100 blocks、10 bps，逐边 10 秒。
 */
class DepotThroatsTest {

  private static final String DEP1 = "OP:D:DEP:1";
  private static final String DEP2 = "OP:D:DEP:2";
  private static final String W1 = "OP:W:W1:1";
  private static final String W2 = "OP:W:W2:1";
  private static final String S = "OP:S:S:1";
  private static final String T = "OP:W:T:1";
  private static final String E = "OP:S:E:1";

  /** 出入段共用 DEP–W1–S：咽喉就是这两条边；出库 0–20 秒占着它，回库在到车库前的最后 20 秒占着它。 */
  @Test
  void sharedAccessTrackIsTheThroat() {
    RailGraph graph = graph();
    Map<UUID, TimetableConflictChecker.RouteProfile> profiles = new LinkedHashMap<>();
    UUID out = profile(graph, profiles, "OUT", List.of(DEP1, W1, S, T, E));
    UUID in = profile(graph, profiles, "IN", List.of(E, T, S, W1, DEP1));

    DepotThroats throats = DepotThroats.of(profiles, List.of(out), List.of(in));

    assertEquals(List.of("OP:D:DEP"), throats.depots());
    assertEquals(2, throats.edgeCount("OP:D:DEP"));
    assertEquals(new DepotThroats.Passage("OP:D:DEP", 0, 20), throats.outbound(out).orElseThrow());
    assertEquals(new DepotThroats.Passage("OP:D:DEP", 20, 40), throats.inbound(in).orElseThrow());
    assertTrue(throats.inbound(out).isEmpty(), "出库线路不回库");
  }

  /** 出入段分线（出库走 W1、回库走 W2）：没有共用的边，没有咽喉，也就不串行。 */
  @Test
  void separateAccessTracksHaveNoThroat() {
    RailGraph graph = graph();
    Map<UUID, TimetableConflictChecker.RouteProfile> profiles = new LinkedHashMap<>();
    UUID out = profile(graph, profiles, "OUT", List.of(DEP1, W1, S, T, E));
    UUID in = profile(graph, profiles, "IN", List.of(E, T, S, W2, DEP2));

    DepotThroats throats = DepotThroats.of(profiles, List.of(out), List.of(in));

    assertTrue(throats.isEmpty());
    assertTrue(throats.outbound(out).isEmpty());
  }

  /** 首末站都不是车库的 route 不参与：它既不出库也不回库。 */
  @Test
  void routesNotTouchingADepotAreIgnored() {
    RailGraph graph = graph();
    Map<UUID, TimetableConflictChecker.RouteProfile> profiles = new LinkedHashMap<>();
    UUID through = profile(graph, profiles, "THRU", List.of(S, T, E));

    DepotThroats throats = DepotThroats.of(profiles, List.of(through), List.of(through));

    assertTrue(throats.isEmpty());
  }

  private static RailGraph graph() {
    Map<String, NodeType> nodes = new LinkedHashMap<>();
    nodes.put(DEP1, NodeType.DEPOT);
    nodes.put(DEP2, NodeType.DEPOT);
    nodes.put(W1, NodeType.WAYPOINT);
    nodes.put(W2, NodeType.WAYPOINT);
    nodes.put(S, NodeType.STATION);
    nodes.put(T, NodeType.WAYPOINT);
    nodes.put(E, NodeType.STATION);
    return TimetableTestFixtures.graph(
        nodes,
        List.of(
            new TimetableTestFixtures.Edge(DEP1, W1, 100, 10.0),
            new TimetableTestFixtures.Edge(W1, S, 100, 10.0),
            new TimetableTestFixtures.Edge(DEP2, W2, 100, 10.0),
            new TimetableTestFixtures.Edge(W2, S, 100, 10.0),
            new TimetableTestFixtures.Edge(S, T, 100, 10.0),
            new TimetableTestFixtures.Edge(T, E, 100, 10.0)));
  }

  private static UUID profile(
      RailGraph graph,
      Map<UUID, TimetableConflictChecker.RouteProfile> profiles,
      String code,
      List<String> nodes) {
    UUID id = TimetableTestFixtures.routeId(code);
    RouteDefinition route = TimetableTestFixtures.route(code, nodes);
    List<RouteStop> stops = TimetableTestFixtures.stops(id, nodes.size(), 0);
    TimetableTimingCalculator.TimingResult timing =
        new TimetableTimingCalculator()
            .compute(graph, TimetableTestFixtures.perEdgeSpeedModel(), route, stops, Duration.ZERO);
    assertTrue(timing.ok(), () -> code + ": " + timing.failure());
    profiles.put(
        id,
        new TimetableConflictChecker.RouteProfile(
            id,
            code,
            timing.stops(),
            timing.segments(),
            TimetableConflictChecker.platformsOf(
                timing.stops(),
                stops,
                nodes.stream().map(NodeId::of).toList(),
                TimetableConflictChecker.GraphIndex.of(graph).nodeTypes())));
    return id;
  }
}
