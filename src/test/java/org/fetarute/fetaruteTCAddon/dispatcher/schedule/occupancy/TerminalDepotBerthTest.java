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
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteId;
import org.junit.jupiter.api.Test;

/**
 * 路线以车库终止时，联锁区之后的库线容得下整列车即算清出。
 *
 * <p>夹具照实服 OFL 车库口的尺寸：A —40— 车库岔口 SW —17— 库线咽喉 THROAT —45— 车库 END；SW—THROAT 这条边与对向正线 P—Q
 * 共用一个足迹方块，是一个物理联锁区。清出要求 74 格（车长 + 停车余量），库线只有 45 格。
 */
class TerminalDepotBerthTest {

  private static final Instant NOW = Instant.parse("2026-09-27T23:43:40Z");
  private static final NodeId A = NodeId.of("SURC:OFL:MLU:2:002");
  private static final NodeId SW = NodeId.of("SWITCHER:Towny:-515:77:2272");
  private static final NodeId THROAT = NodeId.of("SURC:D:OFL:1:001");
  private static final NodeId END = NodeId.of("SURC:D:OFL:1");
  private static final NodeId P = NodeId.of("SURC:OFL:MLU:1:004");
  private static final NodeId Q = NodeId.of("SWITCHER:Towny:-516:77:2268");

  /** 修复前：库线不够"车长 + 停车余量"，判缺少清出边，硬授权构建失败。 */
  @Test
  void withoutTheTerminalRuleAShortStorageTrackFailsTheHardAuthority() {
    assertTrue(build(NodeType.DEPOT, Long.MAX_VALUE).isEmpty());
  }

  /** 列车 34 格、库线 45 格：车停进车库就已清出联锁区，窗口一直到车库。 */
  @Test
  void aTrainThatFitsTheStorageTrackIsAuthorisedIntoTheDepot() {
    OccupancyRequestContext context = build(NodeType.DEPOT, 34L).orElseThrow();

    assertEquals(END, context.pathNodes().get(context.pathNodes().size() - 1));
  }

  /** 列车长过库线：停下时车尾仍压着联锁区，照旧 fail-closed。 */
  @Test
  void aTrainLongerThanTheStorageTrackStillFails() {
    assertTrue(build(NodeType.DEPOT, 46L).isEmpty());
  }

  /** 只认车库终点：终点是车站时仍要完整泊位（停站车尾可能压着进站咽喉）。 */
  @Test
  void aStationTerminusGetsNoExemption() {
    assertTrue(build(NodeType.STATION, 34L).isEmpty());
  }

  private static Optional<OccupancyRequestContext> build(NodeType endType, long terminalBerth) {
    return new OccupancyRequestBuilder(graph(endType), 1, 0, 0, 3, 200L, 8, message -> {})
        .withMinimumConflictExitDistanceBlocks(74L)
        .withTerminalDepotBerthBlocks(terminalBerth)
        .buildContextFromNodes(
            "SURC-MT-LO-9304",
            Optional.of(RouteId.of("SURC:MT:MT-1O_ShortD")),
            List.of(A, END),
            0,
            NOW,
            0,
            AuthorizationPurpose.RUNTIME_MOVE);
  }

  private static SimpleRailGraph graph(NodeType endType) {
    Map<NodeId, RailNode> nodes = new LinkedHashMap<>();
    nodes.put(A, node(A, NodeType.WAYPOINT));
    nodes.put(SW, node(SW, NodeType.SWITCHER));
    nodes.put(THROAT, node(THROAT, NodeType.WAYPOINT));
    nodes.put(END, node(END, endType));
    nodes.put(P, node(P, NodeType.WAYPOINT));
    nodes.put(Q, node(Q, NodeType.SWITCHER));
    Map<EdgeId, RailEdge> edges = new LinkedHashMap<>();
    Map<EdgeId, RailEdgeFootprint> footprints = new LinkedHashMap<>();
    RailFootprintCell shared = new RailFootprintCell(-516, 77, 2272);
    edge(edges, footprints, A, SW, 40, new RailFootprintCell(-520, 77, 2200));
    edge(edges, footprints, SW, THROAT, 17, shared);
    edge(edges, footprints, THROAT, END, 45, new RailFootprintCell(-514, 77, 2300));
    edge(edges, footprints, P, Q, 26, shared);
    return new SimpleRailGraph(
        nodes,
        edges,
        Set.of(),
        RailInterlockingState.from(
            UUID.fromString("11111111-2222-3333-4444-555555555555"), edges.keySet(), footprints));
  }

  private static RailNode node(NodeId id, NodeType type) {
    return new SignRailNode(id, type, new Vector(), Optional.empty(), Optional.empty());
  }

  private static void edge(
      Map<EdgeId, RailEdge> edges,
      Map<EdgeId, RailEdgeFootprint> footprints,
      NodeId from,
      NodeId to,
      int length,
      RailFootprintCell cell) {
    EdgeId id = EdgeId.undirected(from, to);
    edges.put(id, new RailEdge(id, from, to, length, 8.0, true, Optional.empty()));
    footprints.put(id, new RailEdgeFootprint(1, true, Set.of(cell)));
  }
}
