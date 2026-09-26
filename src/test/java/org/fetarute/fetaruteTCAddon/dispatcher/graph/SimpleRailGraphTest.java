package org.fetarute.fetaruteTCAddon.dispatcher.graph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.bukkit.util.Vector;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode;
import org.junit.jupiter.api.Test;

/**
 * {@link SimpleRailGraph} 的遍历顺序契约。
 *
 * <p>此前用 {@code Set.copyOf}/{@code Map.copyOf} 冻结，顺序由每个 JVM 随机一次的 SALT 决定——同一进程内稳定，跨进程二选一。
 * 这里刻意用"逆序插入"的输入：若实现退回按输入顺序或按哈希顺序遍历，断言会立刻失败，而不必等到换一个 JVM。
 */
class SimpleRailGraphTest {

  private static final NodeId WEST = NodeId.of("SWITCHER:B1:W");
  private static final NodeId EAST = NodeId.of("SWITCHER:B1:E");
  private static final NodeId TRACK_1 = NodeId.of("OP:S:B1:1");
  private static final NodeId TRACK_2 = NodeId.of("OP:S:B1:2");
  private static final NodeId APPROACH = NodeId.of("OP:S:A1:1");

  @Test
  void nodesEdgesAndAdjacencyIterateInNaturalOrderRegardlessOfInputOrder() {
    SimpleRailGraph graph = passingLoopInsertedInReverseOrder();

    assertEquals(
        List.of(APPROACH, TRACK_1, TRACK_2, EAST, WEST),
        graph.nodes().stream().map(RailNode::id).toList());

    List<EdgeId> edgeOrder = graph.edges().stream().map(RailEdge::id).toList();
    List<EdgeId> sorted = new ArrayList<>(edgeOrder);
    sorted.sort(null);
    assertEquals(sorted, edgeOrder);

    // 邻接集合是 edges() 的子序列：对规范化 EdgeId 等价于按另一端点排序。
    assertEquals(
        List.of(APPROACH, TRACK_1, TRACK_2),
        graph.edgesFrom(WEST).stream().map(edge -> otherEnd(edge, WEST)).toList());
    assertEquals(
        List.of(TRACK_1, TRACK_2),
        graph.edgesFrom(EAST).stream().map(edge -> otherEnd(edge, EAST)).toList());
  }

  @Test
  void exposedCollectionsAreUnmodifiable() {
    SimpleRailGraph graph = passingLoopInsertedInReverseOrder();

    assertThrows(UnsupportedOperationException.class, () -> graph.nodes().clear());
    assertThrows(UnsupportedOperationException.class, () -> graph.edges().clear());
    assertThrows(UnsupportedOperationException.class, () -> graph.edgesFrom(WEST).clear());
    assertThrows(UnsupportedOperationException.class, () -> graph.edgesFrom(TRACK_1).clear());
  }

  @Test
  void rejectsNullValuesLikeTheImmutableCopyItReplaces() {
    Map<NodeId, RailNode> nodes = new LinkedHashMap<>();
    nodes.put(WEST, null);

    assertThrows(NullPointerException.class, () -> new SimpleRailGraph(nodes, Map.of(), Set.of()));
  }

  /** 西咽喉接进站线与两条股道，东咽喉只接两条股道；插入顺序与自然序完全相反。 */
  private static SimpleRailGraph passingLoopInsertedInReverseOrder() {
    Map<NodeId, RailNode> nodes = new LinkedHashMap<>();
    for (NodeId id : List.of(WEST, EAST, TRACK_2, TRACK_1, APPROACH)) {
      nodes.put(id, node(id));
    }
    Map<EdgeId, RailEdge> edges = new LinkedHashMap<>();
    for (RailEdge edge :
        List.of(
            edge(TRACK_2, EAST),
            edge(TRACK_1, EAST),
            edge(WEST, TRACK_2),
            edge(WEST, TRACK_1),
            edge(APPROACH, WEST))) {
      edges.put(edge.id(), edge);
    }
    return new SimpleRailGraph(nodes, edges, Set.of());
  }

  private static NodeId otherEnd(RailEdge edge, NodeId self) {
    return edge.from().equals(self) ? edge.to() : edge.from();
  }

  private static RailNode node(NodeId id) {
    return new SignRailNode(
        id, NodeType.WAYPOINT, new Vector(0, 0, 0), Optional.empty(), Optional.empty());
  }

  private static RailEdge edge(NodeId a, NodeId b) {
    EdgeId id = EdgeId.undirected(a, b);
    return new RailEdge(id, id.a(), id.b(), 30, 0.0, true, Optional.empty());
  }
}
