package org.fetarute.fetaruteTCAddon.call;

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
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.company.model.RouteStopPassType;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailEdge;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.SignRailNode;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.SimpleRailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.query.RailGraphPathFinder;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode;
import org.fetarute.fetaruteTCAddon.dispatcher.node.WaypointMetadata;
import org.junit.jupiter.api.Test;

/**
 * 区间生成沿交路走的图路径找生成点：交路节点表里只有车站，信号用的区间点只在图上。
 *
 * <p>夹具（括号里是到首节点的轨道距离）：车库 D(0) → 车站 A(10) → i1(30) → i2(60) → 道岔 SW(90) → i3(110) → i4(170) → 车站
 * B(210) → 车站 C(260)。交路节点表是 D、A、B、C；车长 30 格，车身段要 34 格。
 */
class CallPlannerGeometryTest {

  private static final long TRAIN_LENGTH = 30L;
  private static final UUID ROUTE = UUID.randomUUID();

  @Test
  void entryPointsComeFromTheGraphPathBetweenRouteNodes() {
    CallPlanner.RouteGeometry geometry = geometry(stops(false));

    List<CallPlanner.PathPoint> towardsB = geometry.candidatesFor(2);
    assertEquals(
        List.of(NodeId.of("OP:A:B:1:002")),
        towardsB.stream().map(CallPlanner.PathPoint::node).toList(),
        "i4 离 B 不到 64 格；i3 身后有道岔；i1 身后放不下车身（会越过首节点之后的车站 A）");
    CallPlanner.Entry entry = geometry.entryAt(towardsB.get(0));
    assertEquals(1, entry.index(), "生成点在交路节点 A 与 B 之间，入路下标是 A");
    assertEquals(50L, entry.offsetBlocks());
    assertEquals(200L, entry.legBlocks());

    List<CallPlanner.PathPoint> towardsC = geometry.candidatesFor(3);
    assertEquals(
        NodeId.of("OP:A:B:1:004"), towardsC.get(0).node(), "离 C 由近到远：i4 离 C 90 格，身后 60 格内没有别的节点");
    assertTrue(geometry.candidatesFor(1).isEmpty(), "A 上游只有车库");
  }

  /** 生成点所在区段的一端是 DYNAMIC 停靠时不在中途生成：选台后运行时按另一条路认车的位置。 */
  @Test
  void segmentsTouchingDynamicStopsAreSkipped() {
    CallPlanner.RouteGeometry geometry = geometry(stops(true));

    assertTrue(geometry.candidatesFor(2).isEmpty());
  }

  @Test
  void entryEtaInterpolatesWithinTheSegment() {
    int[][] timing = {{0, 30, 90, 130}, {0, 50, 110, 130}};
    // 生成点在 A(发车 50) 与 B(到达 90) 之间、走了一半：从 70 秒处起步，到 C(130) 再加起步开销 20
    assertEquals(
        80,
        CallPlanner.entryEtaSeconds(
            timing, 3, new CallPlanner.Entry(1, NodeId.of("X"), 100L, 200L)));
    assertEquals(
        100,
        CallPlanner.entryEtaSeconds(
            timing, 3, new CallPlanner.Entry(1, NodeId.of("OP:S:A:1"), 0L, 200L)),
        "生成点就是交路节点时从它发车算");
  }

  private static CallPlanner.RouteGeometry geometry(List<RouteStop> stops) {
    return CallPlanner.RouteGeometry.build(
        Instant.EPOCH,
        TRAIN_LENGTH,
        List.of(
            NodeId.of("OP:D:DEP:1"),
            NodeId.of("OP:S:A:1"),
            NodeId.of("OP:S:B:1"),
            NodeId.of("OP:S:C:1")),
        stops,
        graph(),
        new RailGraphPathFinder());
  }

  private static List<RouteStop> stops(boolean dynamicB) {
    return List.of(
        new RouteStop(
            ROUTE,
            0,
            Optional.empty(),
            Optional.of("OP:D:DEP:1"),
            Optional.empty(),
            RouteStopPassType.PASS,
            Optional.of("CRET OP:D:DEP:1")),
        stop(1, "OP:S:A:1", RouteStopPassType.STOP),
        dynamicB
            ? new RouteStop(
                ROUTE,
                2,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                RouteStopPassType.STOP,
                Optional.of("DYNAMIC:OP:S:B"))
            : stop(2, "OP:S:B:1", RouteStopPassType.STOP),
        stop(3, "OP:S:C:1", RouteStopPassType.TERMINATE));
  }

  private static RouteStop stop(int sequence, String node, RouteStopPassType type) {
    return new RouteStop(
        ROUTE,
        sequence,
        Optional.empty(),
        Optional.of(node),
        Optional.empty(),
        type,
        Optional.empty());
  }

  private static RailGraph graph() {
    Map<NodeId, RailNode> nodes = new LinkedHashMap<>();
    node(nodes, "OP:D:DEP:1", NodeType.DEPOT, Optional.empty());
    node(nodes, "OP:S:A:1", NodeType.STATION, Optional.empty());
    node(nodes, "OP:A:B:1:001", NodeType.WAYPOINT, interval("001"));
    node(nodes, "OP:A:B:1:002", NodeType.WAYPOINT, interval("002"));
    node(nodes, "SW:1", NodeType.SWITCHER, Optional.empty());
    node(nodes, "OP:A:B:1:003", NodeType.WAYPOINT, interval("003"));
    node(nodes, "OP:A:B:1:004", NodeType.WAYPOINT, interval("004"));
    node(nodes, "OP:S:B:1", NodeType.STATION, Optional.empty());
    node(nodes, "OP:S:C:1", NodeType.STATION, Optional.empty());
    List<String> order = List.copyOf(nodes.keySet().stream().map(NodeId::value).toList());
    int[] lengths = {10, 20, 30, 30, 20, 60, 40, 50};
    Map<EdgeId, RailEdge> edges = new LinkedHashMap<>();
    for (int i = 0; i + 1 < order.size(); i++) {
      NodeId from = NodeId.of(order.get(i));
      NodeId to = NodeId.of(order.get(i + 1));
      EdgeId id = EdgeId.undirected(from, to);
      edges.put(id, new RailEdge(id, from, to, lengths[i], 8.0, true, Optional.empty()));
    }
    return new SimpleRailGraph(nodes, edges, Set.of());
  }

  private static void node(
      Map<NodeId, RailNode> nodes, String id, NodeType type, Optional<WaypointMetadata> metadata) {
    nodes.put(
        NodeId.of(id),
        new SignRailNode(
            NodeId.of(id), type, new Vector(nodes.size() * 10, 64, 0), Optional.empty(), metadata));
  }

  private static Optional<WaypointMetadata> interval(String sequence) {
    return Optional.of(WaypointMetadata.interval("OP", "A", "B", 1, sequence));
  }
}
