package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.bukkit.util.Vector;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailEdge;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.SignRailNode;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.SimpleRailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.interlocking.RailEdgeFootprint;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.interlocking.RailFootprintCell;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.interlocking.RailInterlockingState;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyResource;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspectPolicy;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SimpleOccupancyManager;
import org.junit.jupiter.api.Test;

/** 车身释放下限：只取车体压着的区间与车体跨过的节点，证据不全时为空。 */
class LiveBodyReleaseFloorTest {

  private static final NodeId A = NodeId.of("OP:A:B:1:001");
  private static final NodeId B = NodeId.of("OP:A:B:1:002");
  private static final NodeId C = NodeId.of("OP:A:B:1:003");
  private static final NodeId D = NodeId.of("OP:A:B:1:004");
  private static final NodeId SW = NodeId.of("SWITCHER:Test:10:64:0");
  private static final NodeId MAIN = NodeId.of("OP:A:B:1:005");
  private static final NodeId BRANCH = NodeId.of("OP:A:C:1:001");

  /** 车体跨过两个节点：区间三条、节点只有被两条已覆盖区间共用的 B、C；车尾后方的 A、车头前方的 D 不算。 */
  @Test
  void floorHoldsTheCoveredEdgesAndOnlyTheNodesTheBodySpans() {
    Chain chain = new Chain();

    LiveBodyReleaseFloor.Coverage coverage =
        LiveBodyReleaseFloor.coverage(chain.graph, cellsAlongX(15, 85, 0));

    assertTrue(coverage.complete(), coverage.incompleteReason());
    assertEquals(
        Set.of(
            edge(A, B),
            edge(B, C),
            edge(C, D),
            OccupancyResource.forNode(B),
            OccupancyResource.forNode(C)),
        coverage.releaseFloor());
    assertEquals(
        Set.of(
            edge(A, B),
            edge(B, C),
            edge(C, D),
            OccupancyResource.forNode(A),
            OccupancyResource.forNode(B),
            OccupancyResource.forNode(C),
            OccupancyResource.forNode(D)),
        coverage.edgesWithEndpoints(),
        "证明“已离开”的判据照旧把两端点也算作可能压着");
  }

  /** 整列车在一条区间内：下限只有这条区间，两端节点都不算。 */
  @Test
  void bodyInsideOneEdgeHoldsNoNode() {
    Chain chain = new Chain();

    LiveBodyReleaseFloor.Coverage coverage =
        LiveBodyReleaseFloor.coverage(chain.graph, cellsAlongX(42, 58, 0));

    assertEquals(Set.of(edge(B, C)), coverage.releaseFloor());
  }

  /** 过道岔：直股两条区间连同道岔节点在下限里，没压着的侧线区间不在。 */
  @Test
  void bodyOverASwitchHoldsTheSwitchNodeButNotTheOtherBranch() {
    Map<NodeId, RailNode> nodes = new LinkedHashMap<>();
    nodes.put(A, railNode(A, NodeType.WAYPOINT, 0.0, 0.0));
    nodes.put(SW, railNode(SW, NodeType.SWITCHER, 10.0, 0.0));
    nodes.put(MAIN, railNode(MAIN, NodeType.WAYPOINT, 20.0, 0.0));
    nodes.put(BRANCH, railNode(BRANCH, NodeType.WAYPOINT, 10.0, 10.0));
    Map<EdgeId, RailEdge> edges = new LinkedHashMap<>();
    Map<EdgeId, RailEdgeFootprint> footprints = new LinkedHashMap<>();
    putEdge(edges, footprints, A, SW, cellsAlongX(0, 9, 0));
    putEdge(edges, footprints, SW, MAIN, cellsAlongX(10, 19, 0));
    Set<RailFootprintCell> branchCells = new LinkedHashSet<>();
    for (int z = 1; z <= 10; z++) {
      branchCells.add(new RailFootprintCell(10, 64, z));
    }
    putEdge(edges, footprints, SW, BRANCH, branchCells);
    RailGraph graph = graph(nodes, edges, footprints);

    LiveBodyReleaseFloor.Coverage coverage =
        LiveBodyReleaseFloor.coverage(graph, cellsAlongX(5, 15, 0));

    assertEquals(
        Set.of(edge(A, SW), edge(SW, MAIN), OccupancyResource.forNode(SW)),
        coverage.releaseFloor());
  }

  /** 无从判断不是“没覆盖”：图不带联锁能力、车体一条区间都定位不到、或观测里混进空方块，下限都为空并说明原因。 */
  @Test
  void incompleteEvidenceYieldsAnEmptyFloorWithAReason() {
    Chain chain = new Chain();

    LiveBodyReleaseFloor.Coverage unsupported =
        LiveBodyReleaseFloor.coverage(mock(RailGraph.class), cellsAlongX(15, 95, 0));
    LiveBodyReleaseFloor.Coverage offTrack =
        LiveBodyReleaseFloor.coverage(chain.graph, cellsAlongX(15, 95, 50));
    LiveBodyReleaseFloor.Coverage nullCell =
        LiveBodyReleaseFloor.coverage(
            chain.graph, Arrays.asList(new RailFootprintCell(20, 64, 0), null));

    assertFalse(unsupported.complete());
    assertEquals("interlocking-unsupported-graph", unsupported.incompleteReason());
    assertFalse(offTrack.complete());
    assertEquals("no-edge-located-for-live-cells", offTrack.incompleteReason());
    assertFalse(nullCell.complete());
    assertEquals("live-footprint-null-cell", nullCell.incompleteReason());
    assertTrue(unsupported.releaseFloor().isEmpty());
    assertTrue(offTrack.releaseFloor().isEmpty());
    assertTrue(offTrack.edgesWithEndpoints().isEmpty());
  }

  /** 取回时刻必须由调用方给出（服务与回归骨架注入的时钟），不得自己去读墙钟。 */
  @Test
  void reacquireRequiresTheCallersClock() {
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    OccupancyResource body = edge(A, B);

    assertThrows(
        NullPointerException.class,
        () ->
            LiveBodyReleaseFloor.reacquireBody(
                manager, "SURC-MT-LH-2689", Optional.empty(), null, List.of(body), Set.of(body)));
  }

  /** A —30— B —30— C —30— D —30— E，沿 x 轴，逐边足迹互不相交。 */
  private static final class Chain {
    private final RailGraph graph;

    private Chain() {
      NodeId e = NodeId.of("OP:A:B:1:006");
      Map<NodeId, RailNode> nodes = new LinkedHashMap<>();
      nodes.put(A, railNode(A, NodeType.WAYPOINT, 0.0, 0.0));
      nodes.put(B, railNode(B, NodeType.WAYPOINT, 30.0, 0.0));
      nodes.put(C, railNode(C, NodeType.WAYPOINT, 60.0, 0.0));
      nodes.put(D, railNode(D, NodeType.WAYPOINT, 90.0, 0.0));
      nodes.put(e, railNode(e, NodeType.WAYPOINT, 120.0, 0.0));
      Map<EdgeId, RailEdge> edges = new LinkedHashMap<>();
      Map<EdgeId, RailEdgeFootprint> footprints = new LinkedHashMap<>();
      putEdge(edges, footprints, A, B, cellsAlongX(0, 29, 0));
      putEdge(edges, footprints, B, C, cellsAlongX(30, 59, 0));
      putEdge(edges, footprints, C, D, cellsAlongX(60, 89, 0));
      putEdge(edges, footprints, D, e, cellsAlongX(90, 120, 0));
      graph = graph(nodes, edges, footprints);
    }
  }

  private static RailGraph graph(
      Map<NodeId, RailNode> nodes,
      Map<EdgeId, RailEdge> edges,
      Map<EdgeId, RailEdgeFootprint> footprints) {
    return new SimpleRailGraph(
        nodes,
        edges,
        Set.of(),
        RailInterlockingState.from(UUID.randomUUID(), edges.keySet(), footprints));
  }

  private static Set<RailFootprintCell> cellsAlongX(int fromX, int toX, int z) {
    Set<RailFootprintCell> cells = new LinkedHashSet<>();
    for (int x = fromX; x <= toX; x++) {
      cells.add(new RailFootprintCell(x, 64, z));
    }
    return cells;
  }

  private static OccupancyResource edge(NodeId from, NodeId to) {
    return OccupancyResource.forEdge(EdgeId.undirected(from, to));
  }

  private static RailNode railNode(NodeId id, NodeType type, double x, double z) {
    return new SignRailNode(id, type, new Vector(x, 64.0, z), Optional.empty(), Optional.empty());
  }

  private static void putEdge(
      Map<EdgeId, RailEdge> edges,
      Map<EdgeId, RailEdgeFootprint> footprints,
      NodeId from,
      NodeId to,
      Set<RailFootprintCell> cells) {
    EdgeId id = EdgeId.undirected(from, to);
    edges.put(id, new RailEdge(id, from, to, cells.size(), -1.0, true, Optional.empty()));
    footprints.put(
        id, new RailEdgeFootprint(RailEdgeFootprint.CURRENT_FORMAT_VERSION, true, cells));
  }
}
