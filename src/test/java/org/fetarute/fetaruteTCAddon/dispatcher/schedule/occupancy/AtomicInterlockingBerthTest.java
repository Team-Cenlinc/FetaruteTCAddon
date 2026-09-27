package org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.bukkit.util.Vector;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailEdge;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.SignRailNode;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.SimpleRailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode;
import org.fetarute.fetaruteTCAddon.dispatcher.node.WaypointMetadata;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteId;
import org.junit.jupiter.api.Test;

/**
 * 道岔群原子窗口的清出点必须给整列车留出泊位：两组道岔之间的直线段容不下整列车时，它们对这列车是同一组联锁。
 *
 * <p>夹具照实服 2026-09-27 OFL 车库口（边长取自实服图）：2 道 OFL:2 → MLU:2:001 → MLU:2:002 →
 * 剪刀渡线（-520:2212、-518:2220、 -518:2227、-516:2236）→ 1 道 MLU:1:003 → 车库岔口（-516:2268、-515:2272）→ 库线咽喉
 * D:OFL:1:001 → 车库 D:OFL:1。 回库车穿过渡线后只有 MLU:1:003 一个清出点，离渡线出口 17 格、离车库岔口 11 格；1 道下行车从另一侧来，经
 * MLU:1:004 过岔口、MLU:1:003、 渡线的 1 道道岔（-516:2236、-516:2212）到 MLU:1:002。
 */
class AtomicInterlockingBerthTest {

  private static final Instant NOW = Instant.parse("2026-09-27T07:37:09Z");

  private static final NodeId OFL_2 = NodeId.of("SURC:S:OFL:2");
  private static final NodeId MLU_2_001 = NodeId.of("SURC:OFL:MLU:2:001");
  private static final NodeId MLU_2_002 = NodeId.of("SURC:OFL:MLU:2:002");
  private static final NodeId CROSSOVER_2 = NodeId.of("SWITCHER:Towny:-520:77:2212");
  private static final NodeId DIAMOND_A = NodeId.of("SWITCHER:Towny:-518:77:2220");
  private static final NodeId DIAMOND_B = NodeId.of("SWITCHER:Towny:-518:77:2227");
  private static final NodeId CROSSOVER_1_EAST = NodeId.of("SWITCHER:Towny:-516:77:2236");
  private static final NodeId CROSSOVER_1_WEST = NodeId.of("SWITCHER:Towny:-516:77:2212");
  private static final NodeId MLU_1_003 = NodeId.of("SURC:OFL:MLU:1:003");
  private static final NodeId JUNCTION = NodeId.of("SWITCHER:Towny:-516:77:2268");
  private static final NodeId DEPOT_SWITCH = NodeId.of("SWITCHER:Towny:-515:77:2272");
  private static final NodeId DEPOT_THROAT = NodeId.of("SURC:D:OFL:1:001");
  private static final NodeId DEPOT = NodeId.of("SURC:D:OFL:1");
  private static final NodeId MLU_1_004 = NodeId.of("SURC:OFL:MLU:1:004");
  private static final NodeId MLU_1_002 = NodeId.of("SURC:OFL:MLU:1:002");
  private static final NodeId MLU_1_001 = NodeId.of("SURC:OFL:MLU:1:001");

  /** 列车长过两组道岔之间的直线段：回库窗口穿过渡线与车库岔口一直到车库，不能停在 MLU:1:003。 */
  @Test
  void depotRunInThatCannotBerthBetweenTheClustersIsAuthorisedIntoTheDepot() {
    OccupancyRequestContext context = runIn(MLU_2_002, 40L);

    assertEquals(DEPOT, context.pathNodes().get(context.pathNodes().size() - 1));
    assertTrue(context.pathNodes().containsAll(List.of(MLU_1_003, JUNCTION, DEPOT_SWITCH)));
    assertEquals(
        ResourceIntent.MOVEMENT_REQUIRED,
        context.request().intentFor(OccupancyResource.forNode(JUNCTION)));
    assertEquals(
        ResourceIntent.MOVEMENT_REQUIRED,
        context.request().intentFor(OccupancyResource.forNode(DEPOT)));
  }

  /** 列车停在道岔上（疏通场景）时同样不能只清到 MLU:1:003：窗口要穿过车库岔口，否则不许动。 */
  @Test
  void trainStandingOnTheCrossoverIsNotDrainedIntoTheGap() {
    OccupancyRequestContext context = runIn(CROSSOVER_2, 40L);

    assertEquals(DEPOT, context.pathNodes().get(context.pathNodes().size() - 1));
  }

  /** 容得下的短车仍停在首个清出点：规则只在夹缝放不下整列车时生效。 */
  @Test
  void trainThatFitsBetweenTheClustersStillStopsAtTheFirstClearance() {
    assertEquals(MLU_1_003, last(runIn(MLU_2_002, 17L)));
  }

  /** 未配置泊位距离（0）时与只看首个清出点等价。 */
  @Test
  void withoutBerthDistanceTheFirstClearanceStillEndsTheWindow() {
    assertEquals(MLU_1_003, last(runIn(MLU_2_002, 0L)));
  }

  /** 反方向同理：1 道下行车过岔口后不能停在 MLU:1:003，要一次穿过渡线的 1 道道岔；渡线后接长直线，停在首个清出点 MLU:1:002（行为不变）。 */
  @Test
  void opposingTrainCrossesBothClustersAndThenKeepsTheFirstClearance() {
    OccupancyRequestContext context =
        builder(40L)
            .buildContextFromNodes(
                "SURC-DS-LH-0368",
                Optional.of(RouteId.of("SURC:DS:DS-1W_FullD")),
                List.of(MLU_1_004, MLU_1_001),
                0,
                NOW,
                0,
                AuthorizationPurpose.RUNTIME_MOVE)
            .orElseThrow();

    assertEquals(MLU_1_002, last(context));
    assertTrue(context.pathNodes().containsAll(List.of(MLU_1_003, CROSSOVER_1_EAST)));
  }

  /** 短清出点后面接的是车站而不是道岔：不算夹缝，窗口仍停在清出点，不延伸去占车站。 */
  @Test
  void shortClearanceFollowedByAStationIsNotASandwich() {
    NodeId approach = NodeId.of("SURC:A:B:1:001");
    NodeId switcher = NodeId.of("SWITCHER:Towny:0:64:0");
    NodeId clearance = NodeId.of("SURC:A:B:1:002");
    NodeId station = NodeId.of("SURC:S:B:1");
    Map<NodeId, RailNode> nodes = new LinkedHashMap<>();
    nodes.put(
        approach,
        node(approach, NodeType.WAYPOINT, WaypointMetadata.interval("SURC", "A", "B", 1, "001")));
    nodes.put(
        switcher,
        new SignRailNode(
            switcher, NodeType.SWITCHER, new Vector(), Optional.empty(), Optional.empty()));
    nodes.put(
        clearance,
        node(clearance, NodeType.WAYPOINT, WaypointMetadata.interval("SURC", "A", "B", 1, "002")));
    nodes.put(station, node(station, NodeType.STATION, WaypointMetadata.station("SURC", "B", 1)));
    Map<EdgeId, RailEdge> edges = new LinkedHashMap<>();
    edge(edges, approach, switcher, 10);
    edge(edges, switcher, clearance, 5);
    edge(edges, clearance, station, 10);

    OccupancyRequestContext context =
        new OccupancyRequestBuilder(new SimpleRailGraph(nodes, edges, Set.of()), 1, 0, 0, 0)
            .withMinimumConflictExitDistanceBlocks(40L)
            .buildContextFromNodes(
                "Train-1",
                Optional.of(RouteId.of("SURC:A:B")),
                List.of(approach, station),
                0,
                NOW,
                0,
                AuthorizationPurpose.RUNTIME_MOVE)
            .orElseThrow();

    assertEquals(clearance, last(context));
  }

  private static OccupancyRequestContext runIn(NodeId from, long berthBlocks) {
    return builder(berthBlocks)
        .buildContextFromNodes(
            "SURC-MT-LO-6900",
            Optional.of(RouteId.of("SURC:MT:MT-1O_ShortD")),
            List.of(from, DEPOT),
            0,
            NOW,
            0,
            AuthorizationPurpose.RUNTIME_MOVE)
        .orElseThrow();
  }

  private static NodeId last(OccupancyRequestContext context) {
    return context.pathNodes().get(context.pathNodes().size() - 1);
  }

  private static OccupancyRequestBuilder builder(long berthBlocks) {
    return new OccupancyRequestBuilder(oflDepotJunction(), 1, 0, 0, 0)
        .withMinimumConflictExitDistanceBlocks(berthBlocks);
  }

  private static SimpleRailGraph oflDepotJunction() {
    Map<NodeId, RailNode> nodes = new LinkedHashMap<>();
    nodes.put(OFL_2, node(OFL_2, NodeType.STATION, WaypointMetadata.station("SURC", "OFL", 2)));
    nodes.put(MLU_2_001, interval(MLU_2_001, 2, "001"));
    nodes.put(MLU_2_002, interval(MLU_2_002, 2, "002"));
    for (NodeId switcher :
        List.of(
            CROSSOVER_2,
            DIAMOND_A,
            DIAMOND_B,
            CROSSOVER_1_EAST,
            CROSSOVER_1_WEST,
            JUNCTION,
            DEPOT_SWITCH)) {
      nodes.put(
          switcher,
          new SignRailNode(
              switcher, NodeType.SWITCHER, new Vector(), Optional.empty(), Optional.empty()));
    }
    nodes.put(MLU_1_003, interval(MLU_1_003, 1, "003"));
    nodes.put(MLU_1_004, interval(MLU_1_004, 1, "004"));
    nodes.put(MLU_1_002, interval(MLU_1_002, 1, "002"));
    nodes.put(MLU_1_001, interval(MLU_1_001, 1, "001"));
    nodes.put(
        DEPOT_THROAT,
        node(
            DEPOT_THROAT,
            NodeType.WAYPOINT,
            WaypointMetadata.depotThroat("SURC", "OFL", 1, "001")));
    nodes.put(DEPOT, node(DEPOT, NodeType.DEPOT, WaypointMetadata.depot("SURC", "OFL", 1)));
    Map<EdgeId, RailEdge> edges = new LinkedHashMap<>();
    edge(edges, OFL_2, MLU_2_001, 50);
    edge(edges, MLU_2_001, MLU_2_002, 47);
    edge(edges, MLU_2_002, CROSSOVER_2, 15);
    edge(edges, CROSSOVER_2, DIAMOND_A, 8);
    edge(edges, DIAMOND_A, DIAMOND_B, 5);
    edge(edges, DIAMOND_B, CROSSOVER_1_EAST, 9);
    edge(edges, CROSSOVER_1_EAST, MLU_1_003, 17);
    edge(edges, MLU_1_003, JUNCTION, 11);
    edge(edges, JUNCTION, DEPOT_SWITCH, 3);
    edge(edges, DEPOT_SWITCH, DEPOT_THROAT, 17);
    edge(edges, DEPOT_THROAT, DEPOT, 45);
    edge(edges, MLU_1_004, JUNCTION, 26);
    edge(edges, CROSSOVER_1_EAST, CROSSOVER_1_WEST, 22);
    edge(edges, CROSSOVER_1_WEST, MLU_1_002, 15);
    edge(edges, MLU_1_002, MLU_1_001, 47);
    return new SimpleRailGraph(nodes, edges, Set.of());
  }

  private static RailNode interval(NodeId id, int track, String sequence) {
    return node(
        id, NodeType.WAYPOINT, WaypointMetadata.interval("SURC", "OFL", "MLU", track, sequence));
  }

  private static RailNode node(NodeId id, NodeType type, WaypointMetadata metadata) {
    return new SignRailNode(id, type, new Vector(), Optional.empty(), Optional.of(metadata));
  }

  private static void edge(Map<EdgeId, RailEdge> edges, NodeId from, NodeId to, int lengthBlocks) {
    EdgeId id = EdgeId.undirected(from, to);
    edges.put(id, new RailEdge(id, from, to, lengthBlocks, 8.0, true, Optional.empty()));
  }
}
