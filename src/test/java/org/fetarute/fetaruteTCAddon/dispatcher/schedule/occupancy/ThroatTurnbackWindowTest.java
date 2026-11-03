package org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.bukkit.util.Vector;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailEdge;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.SignRailNode;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.SimpleRailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.interlocking.RailEdgeFootprint;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.interlocking.RailFootprintCell;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.interlocking.RailInterlockingState;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode;
import org.fetarute.fetaruteTCAddon.dispatcher.node.WaypointMetadata;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteId;
import org.junit.jupiter.api.Test;

/**
 * 交路在咽喉折返时的原子联锁窗口。
 *
 * <p>夹具照实服 OFL 车库：OFL:2 → 道岔 → 2 道库线咽喉 D:OFL:2:001–004 → 道岔 → 6 道咽喉 D:OFL:6:001（折返点）。
 * 进入联锁后一路都是咽喉与道岔， 既没有车站/车库也没有区间点；原子窗口须以折返咽喉为出口，不能因找不到清出点就 fail-closed、把车永远扣在站台。
 */
class ThroatTurnbackWindowTest {

  private static final Instant NOW = Instant.parse("2026-11-02T05:21:37Z");
  private static final RouteId ROUTE = RouteId.of("SURC:MT:MT-1O_ShortR");

  private static final NodeId OFL_2 = NodeId.of("SURC:S:OFL:2");
  private static final NodeId PLATFORM_SWITCH = NodeId.of("SWITCHER:Towny:-526:81:2151");
  private static final NodeId THROAT_2_001 = NodeId.of("SURC:D:OFL:2:001");
  private static final NodeId THROAT_2_002 = NodeId.of("SURC:D:OFL:2:002");
  private static final NodeId THROAT_2_003 = NodeId.of("SURC:D:OFL:2:003");
  private static final NodeId THROAT_2_004 = NodeId.of("SURC:D:OFL:2:004");
  private static final NodeId FAN_SWITCH = NodeId.of("SWITCHER:Towny:-419:74:2314");
  private static final NodeId THROAT_6_001 = NodeId.of("SURC:D:OFL:6:001");
  private static final NodeId STATION_THROAT = NodeId.of("SURC:S:OFL:6:001");

  @Test
  void runToDepotThroatTurnbackIsAuthorisedUpToTheTurnbackThroat() {
    Optional<OccupancyRequestContext> context = build(builder(), THROAT_6_001);

    assertTrue(context.isPresent(), "折返咽喉前的联锁进路不应 fail-closed");
    List<NodeId> path = context.orElseThrow().pathNodes();
    assertEquals(THROAT_6_001, path.get(path.size() - 1));
    assertTrue(path.containsAll(List.of(PLATFORM_SWITCH, THROAT_2_004, FAN_SWITCH)));
    assertEquals(
        ResourceIntent.MOVEMENT_REQUIRED,
        context.orElseThrow().request().intentFor(OccupancyResource.forNode(FAN_SWITCH)));
  }

  @Test
  void runToStationThroatTurnbackIsAuthorisedUpToTheTurnbackThroat() {
    Optional<OccupancyRequestContext> context = build(builder(), STATION_THROAT);

    assertTrue(context.isPresent(), "站咽喉折返同样以折返点为出口");
    List<NodeId> path = context.orElseThrow().pathNodes();
    assertEquals(STATION_THROAT, path.get(path.size() - 1));
  }

  /** 路径被截在中途（DYNAMIC 目标未选定）时，终点咽喉只是途经点，仍须 fail-closed。 */
  @Test
  void truncatedPathEndingAtThroatStillFailsClosed() {
    assertTrue(build(builder().withoutPathEndThroatExit(), THROAT_6_001).isEmpty());
  }

  /** 终点是道岔节点不算折返点：找不到清出点照旧 fail-closed。 */
  @Test
  void pathEndingAtSwitcherStillFailsClosed() {
    assertTrue(build(builder(), FAN_SWITCH).isEmpty());
  }

  /** 折返咽喉前有物理联锁（与对向正线共用足迹），联锁之后到折返咽喉不够"车长 + 停车余量"：冲突出口窗口也以折返咽喉为出口，不能先一步判缺清出边。 */
  @Test
  void physicalInterlockingRightBeforeTheTurnbackThroatExitsAtTheThroat() {
    NodeId approach = NodeId.of("SURC:OFL:MLU:2:002");
    NodeId opposing = NodeId.of("SURC:OFL:MLU:1:004");
    NodeId opposingSwitch = NodeId.of("SWITCHER:Towny:-516:77:2268");
    Map<NodeId, RailNode> nodes = new LinkedHashMap<>();
    nodes.put(
        approach,
        node(
            approach,
            NodeType.WAYPOINT,
            WaypointMetadata.interval("SURC", "OFL", "MLU", 2, "002")));
    nodes.put(
        FAN_SWITCH,
        new SignRailNode(
            FAN_SWITCH, NodeType.SWITCHER, new Vector(), Optional.empty(), Optional.empty()));
    nodes.put(THROAT_6_001, depotThroat(THROAT_6_001, 6, "001"));
    nodes.put(
        opposing,
        node(
            opposing,
            NodeType.WAYPOINT,
            WaypointMetadata.interval("SURC", "OFL", "MLU", 1, "004")));
    nodes.put(
        opposingSwitch,
        new SignRailNode(
            opposingSwitch, NodeType.SWITCHER, new Vector(), Optional.empty(), Optional.empty()));
    Map<EdgeId, RailEdge> edges = new LinkedHashMap<>();
    Map<EdgeId, RailEdgeFootprint> footprints = new LinkedHashMap<>();
    RailFootprintCell shared = new RailFootprintCell(-516, 77, 2272);
    footprintEdge(
        edges, footprints, approach, FAN_SWITCH, 40, new RailFootprintCell(-520, 77, 2200));
    footprintEdge(edges, footprints, FAN_SWITCH, THROAT_6_001, 17, shared);
    footprintEdge(edges, footprints, opposing, opposingSwitch, 26, shared);
    SimpleRailGraph graph =
        new SimpleRailGraph(
            nodes,
            edges,
            Set.of(),
            RailInterlockingState.from(
                UUID.fromString("11111111-2222-3333-4444-555555555555"),
                edges.keySet(),
                footprints));
    OccupancyRequestBuilder builder =
        new OccupancyRequestBuilder(graph, 1, 0, 0, 3, 200L, 8, message -> {})
            .withMinimumConflictExitDistanceBlocks(74L);

    Optional<OccupancyRequestContext> context =
        builder.buildContextFromNodes(
            "SURC-MT-LO-5237",
            Optional.of(ROUTE),
            List.of(approach, THROAT_6_001),
            0,
            NOW,
            0,
            AuthorizationPurpose.RUNTIME_MOVE);

    assertTrue(context.isPresent(), "折返咽喉前的物理联锁不应判缺清出边");
    List<NodeId> path = context.orElseThrow().pathNodes();
    assertEquals(THROAT_6_001, path.get(path.size() - 1));
    assertTrue(
        builder
            .withoutPathEndThroatExit()
            .buildContextFromNodes(
                "SURC-MT-LO-5237",
                Optional.of(ROUTE),
                List.of(approach, THROAT_6_001),
                0,
                NOW,
                0,
                AuthorizationPurpose.RUNTIME_MOVE)
            .isEmpty());
  }

  private static Optional<OccupancyRequestContext> build(
      OccupancyRequestBuilder builder, NodeId target) {
    return builder.buildContextFromNodes(
        "SURC-MT-LO-5237",
        Optional.of(ROUTE),
        List.of(OFL_2, target),
        0,
        NOW,
        0,
        AuthorizationPurpose.RUNTIME_MOVE);
  }

  private static OccupancyRequestBuilder builder() {
    return new OccupancyRequestBuilder(graph(), 1, 0, 0, 0);
  }

  private static SimpleRailGraph graph() {
    Map<NodeId, RailNode> nodes = new LinkedHashMap<>();
    nodes.put(OFL_2, node(OFL_2, NodeType.STATION, WaypointMetadata.station("SURC", "OFL", 2)));
    for (NodeId switcher : List.of(PLATFORM_SWITCH, FAN_SWITCH)) {
      nodes.put(
          switcher,
          new SignRailNode(
              switcher, NodeType.SWITCHER, new Vector(), Optional.empty(), Optional.empty()));
    }
    nodes.put(THROAT_2_001, depotThroat(THROAT_2_001, 2, "001"));
    nodes.put(THROAT_2_002, depotThroat(THROAT_2_002, 2, "002"));
    nodes.put(THROAT_2_003, depotThroat(THROAT_2_003, 2, "003"));
    nodes.put(THROAT_2_004, depotThroat(THROAT_2_004, 2, "004"));
    nodes.put(THROAT_6_001, depotThroat(THROAT_6_001, 6, "001"));
    nodes.put(
        STATION_THROAT,
        node(
            STATION_THROAT,
            NodeType.WAYPOINT,
            WaypointMetadata.stationThroat("SURC", "OFL", 6, "001")));
    Map<EdgeId, RailEdge> edges = new LinkedHashMap<>();
    edge(edges, OFL_2, PLATFORM_SWITCH, 56);
    edge(edges, PLATFORM_SWITCH, THROAT_2_001, 25);
    edge(edges, THROAT_2_001, THROAT_2_002, 71);
    edge(edges, THROAT_2_002, THROAT_2_003, 40);
    edge(edges, THROAT_2_003, THROAT_2_004, 62);
    edge(edges, THROAT_2_004, FAN_SWITCH, 42);
    edge(edges, FAN_SWITCH, THROAT_6_001, 21);
    edge(edges, FAN_SWITCH, STATION_THROAT, 21);
    return new SimpleRailGraph(nodes, edges, Set.of());
  }

  private static RailNode depotThroat(NodeId id, int track, String sequence) {
    return node(
        id, NodeType.WAYPOINT, WaypointMetadata.depotThroat("SURC", "OFL", track, sequence));
  }

  private static RailNode node(NodeId id, NodeType type, WaypointMetadata metadata) {
    return new SignRailNode(id, type, new Vector(), Optional.empty(), Optional.of(metadata));
  }

  private static void edge(Map<EdgeId, RailEdge> edges, NodeId from, NodeId to, int lengthBlocks) {
    EdgeId id = EdgeId.undirected(from, to);
    edges.put(id, new RailEdge(id, from, to, lengthBlocks, 8.0, true, Optional.empty()));
  }

  private static void footprintEdge(
      Map<EdgeId, RailEdge> edges,
      Map<EdgeId, RailEdgeFootprint> footprints,
      NodeId from,
      NodeId to,
      int lengthBlocks,
      RailFootprintCell cell) {
    edge(edges, from, to, lengthBlocks);
    footprints.put(EdgeId.undirected(from, to), new RailEdgeFootprint(1, true, Set.of(cell)));
  }
}
