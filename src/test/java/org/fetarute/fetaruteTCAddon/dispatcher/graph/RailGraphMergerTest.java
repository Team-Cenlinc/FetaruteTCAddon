package org.fetarute.fetaruteTCAddon.dispatcher.graph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.bukkit.util.Vector;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.junit.jupiter.api.Test;

final class RailGraphMergerTest {

  @Test
  void appendsWhenNoNodeOverlap() {
    RailGraph base = graph(Set.of(node("A"), node("B")), Set.of(edge("A", "B", 5)), Set.of());
    RailGraph update = graph(Set.of(node("X"), node("Y")), Set.of(edge("X", "Y", 7)), Set.of());

    RailGraphMerger.MergeResult merged = RailGraphMerger.appendOrReplaceComponents(base, update);

    assertEquals(RailGraphMerger.MergeAction.APPEND, merged.action());
    assertEquals(0, merged.replacedComponentCount());
    assertEquals(0, merged.removedNodes());
    assertEquals(0, merged.removedEdges());
    assertEquals(4, merged.totalNodes());
    assertEquals(2, merged.totalEdges());
    assertTrue(merged.graph().findNode(NodeId.of("A")).isPresent());
    assertTrue(merged.graph().findNode(NodeId.of("X")).isPresent());
  }

  @Test
  void replacesOverlappedComponent() {
    RailGraph base =
        graph(
            Set.of(node("A"), node("B"), node("X"), node("Y")),
            Set.of(edge("A", "B", 5), edge("X", "Y", 7)),
            Set.of());
    RailGraph update =
        graph(
            Set.of(node("X"), node("Y"), node("Z")),
            Set.of(edge("X", "Z", 2), edge("Z", "Y", 3)),
            Set.of());

    RailGraphMerger.MergeResult merged = RailGraphMerger.appendOrReplaceComponents(base, update);

    // 新行为：保守合并不会删除分量外的节点，只会替换"两端都在 update 中"的边
    assertEquals(RailGraphMerger.MergeAction.REPLACE_COMPONENTS, merged.action());
    // 不再删除整个分量，所以 replacedComponentCount/removedNodes 为 0
    assertEquals(0, merged.replacedComponentCount());
    assertEquals(0, merged.removedNodes());
    // X-Y 边会被删除（因为 X、Y 都在 update 中）
    assertEquals(1, merged.removedEdges());
    // 所有 5 个节点都保留
    assertEquals(5, merged.totalNodes());
    // A-B (保留) + X-Z (新) + Z-Y (新) = 3
    assertEquals(3, merged.totalEdges());

    assertTrue(merged.graph().findNode(NodeId.of("A")).isPresent());
    assertTrue(merged.graph().findNode(NodeId.of("B")).isPresent());
    assertTrue(merged.graph().findNode(NodeId.of("X")).isPresent());
    assertTrue(merged.graph().findNode(NodeId.of("Y")).isPresent());
    assertTrue(merged.graph().findNode(NodeId.of("Z")).isPresent());
    // X-Y 旧边被 update 的新边替换（update 没有 X-Y，所以被删除）
    assertFalse(
        merged.graph().edges().stream()
            .anyMatch(e -> e.id().equals(EdgeId.undirected(NodeId.of("X"), NodeId.of("Y")))));
  }

  @Test
  void preservesBlockedEdgesWhenAppending() {
    EdgeId blocked = EdgeId.undirected(NodeId.of("A"), NodeId.of("B"));
    RailGraph base =
        graph(Set.of(node("A"), node("B")), Set.of(edge("A", "B", 5)), Set.of(blocked));
    RailGraph update = graph(Set.of(node("X"), node("Y")), Set.of(edge("X", "Y", 7)), Set.of());

    RailGraphMerger.MergeResult merged = RailGraphMerger.appendOrReplaceComponents(base, update);

    assertTrue(merged.graph().isBlocked(blocked));
  }

  @Test
  void upsertKeepsBaseNodesAndOverwritesEdges() {
    EdgeId blocked = EdgeId.undirected(NodeId.of("A"), NodeId.of("B"));
    RailGraph base =
        graph(
            Set.of(node("A"), node("B"), node("X"), node("Y")),
            Set.of(edge("A", "B", 5), edge("X", "Y", 7)),
            Set.of(blocked));
    RailGraph update =
        graph(
            Set.of(node("X"), node("Y"), node("Z")),
            Set.of(edge("X", "Y", 9), edge("Y", "Z", 2)),
            Set.of());

    RailGraphMerger.MergeResult merged = RailGraphMerger.upsert(base, update);

    assertEquals(RailGraphMerger.MergeAction.UPSERT, merged.action());
    assertEquals(0, merged.replacedComponentCount());
    assertEquals(0, merged.removedNodes());
    assertEquals(0, merged.removedEdges());
    assertEquals(5, merged.totalNodes());
    assertEquals(3, merged.totalEdges());
    assertTrue(merged.graph().findNode(NodeId.of("A")).isPresent());
    assertTrue(merged.graph().findNode(NodeId.of("B")).isPresent());
    assertTrue(merged.graph().isBlocked(blocked));
    // 边合并时取最短长度：base X-Y=7, update X-Y=9 -> 合并后应为 7
    assertTrue(
        merged.graph().edges().stream()
            .anyMatch(
                edge ->
                    edge.id().equals(EdgeId.undirected(NodeId.of("X"), NodeId.of("Y")))
                        && edge.lengthBlocks() == 7));
  }

  @Test
  void removesComponentBySeed() {
    RailGraph base =
        graph(
            Set.of(node("A"), node("B"), node("X"), node("Y"), node("Z")),
            Set.of(edge("A", "B", 5), edge("X", "Y", 7), edge("Y", "Z", 2)),
            Set.of());

    RailGraphMerger.RemoveResult removed =
        RailGraphMerger.removeComponents(base, Set.of(NodeId.of("X")));

    assertEquals(1, removed.removedComponentCount());
    assertEquals(3, removed.removedNodes());
    assertEquals(2, removed.removedEdges());
    assertEquals(2, removed.totalNodes());
    assertEquals(1, removed.totalEdges());
    assertTrue(removed.graph().findNode(NodeId.of("A")).isPresent());
    assertTrue(removed.graph().findNode(NodeId.of("B")).isPresent());
    assertFalse(removed.graph().findNode(NodeId.of("X")).isPresent());
  }

  @Test
  void removeComponentIgnoresUnknownSeed() {
    RailGraph base = graph(Set.of(node("A"), node("B")), Set.of(edge("A", "B", 5)), Set.of());

    RailGraphMerger.RemoveResult removed =
        RailGraphMerger.removeComponents(base, Set.of(NodeId.of("unknown")));

    assertEquals(0, removed.removedComponentCount());
    assertEquals(0, removed.removedNodes());
    assertEquals(0, removed.removedEdges());
    assertEquals(2, removed.totalNodes());
    assertEquals(1, removed.totalEdges());
  }

  @Test
  void removesMultipleComponentsWhenSeedsInDifferentComponents() {
    RailGraph base =
        graph(
            Set.of(node("A"), node("B"), node("X"), node("Y"), node("Z"), node("P"), node("Q")),
            Set.of(edge("A", "B", 5), edge("X", "Y", 7), edge("Y", "Z", 2), edge("P", "Q", 1)),
            Set.of());

    RailGraphMerger.RemoveResult removed =
        RailGraphMerger.removeComponents(base, Set.of(NodeId.of("X"), NodeId.of("P")));

    assertEquals(2, removed.removedComponentCount());
    assertEquals(5, removed.removedNodes());
    assertEquals(3, removed.removedEdges());
    assertEquals(2, removed.totalNodes());
    assertEquals(1, removed.totalEdges());
    assertTrue(removed.graph().findNode(NodeId.of("A")).isPresent());
    assertTrue(removed.graph().findNode(NodeId.of("B")).isPresent());
    assertTrue(removed.graph().findNode(NodeId.of("X")).isEmpty());
    assertTrue(removed.graph().findNode(NodeId.of("P")).isEmpty());
  }

  @Test
  void removesComponentOnceWhenSeedsOverlapSameComponent() {
    RailGraph base =
        graph(
            Set.of(node("A"), node("B"), node("X"), node("Y"), node("Z")),
            Set.of(edge("A", "B", 5), edge("X", "Y", 7), edge("Y", "Z", 2)),
            Set.of());

    RailGraphMerger.RemoveResult removed =
        RailGraphMerger.removeComponents(base, Set.of(NodeId.of("X"), NodeId.of("Z")));

    assertEquals(1, removed.removedComponentCount());
    assertEquals(3, removed.removedNodes());
    assertEquals(2, removed.removedEdges());
    assertEquals(2, removed.totalNodes());
    assertEquals(1, removed.totalEdges());
    assertTrue(removed.graph().findNode(NodeId.of("A")).isPresent());
    assertTrue(removed.graph().findNode(NodeId.of("B")).isPresent());
    assertTrue(removed.graph().findNode(NodeId.of("X")).isEmpty());
    assertTrue(removed.graph().findNode(NodeId.of("Z")).isEmpty());
  }

  @Test
  void preservesBlockedEdgesWhenRemovingOtherComponent() {
    EdgeId blocked = EdgeId.undirected(NodeId.of("A"), NodeId.of("B"));
    RailGraph base =
        graph(
            Set.of(node("A"), node("B"), node("X"), node("Y")),
            Set.of(edge("A", "B", 5), edge("X", "Y", 7)),
            Set.of(blocked));

    RailGraphMerger.RemoveResult removed =
        RailGraphMerger.removeComponents(base, Set.of(NodeId.of("X")));

    assertEquals(1, removed.removedComponentCount());
    assertEquals(2, removed.removedNodes());
    assertEquals(1, removed.removedEdges());
    assertEquals(2, removed.totalNodes());
    assertEquals(1, removed.totalEdges());
    assertTrue(removed.graph().isBlocked(blocked));
  }

  private static SimpleRailGraph graph(
      Set<org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode> nodes,
      Set<RailEdge> edges,
      Set<EdgeId> blockedEdges) {
    Map<NodeId, org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode> nodesById = new HashMap<>();
    for (org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode node : nodes) {
      nodesById.put(node.id(), node);
    }
    Map<EdgeId, RailEdge> edgesById = new HashMap<>();
    for (RailEdge edge : edges) {
      edgesById.put(edge.id(), edge);
    }
    return new SimpleRailGraph(nodesById, edgesById, blockedEdges);
  }

  private static org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode node(String id) {
    return new SignRailNode(
        NodeId.of(id), NodeType.WAYPOINT, new Vector(0, 0, 0), Optional.empty(), Optional.empty());
  }

  private static RailEdge edge(String a, String b, int length) {
    EdgeId id = EdgeId.undirected(NodeId.of(a), NodeId.of(b));
    return new RailEdge(id, id.a(), id.b(), length, 0.0, true, Optional.empty());
  }

  /**
   * 回归测试：用户在 A 区域 build 后，再去 B 区域 build，应保留 A 区域的节点。
   *
   * <p>场景：A 区域有节点 [A1, A2, S]，B 区域有节点 [B1, B2, S]，两者共享中央 switcher S。 旧实现会在合并时删除整个 A 分量（因为有交集
   * S），新实现应保留所有节点。
   */
  @Test
  void preservesPreviousBuildWhenSharedSwitcher() {
    // A 区域 build 结果
    RailGraph regionA =
        graph(
            Set.of(node("A1"), node("A2"), node("S")),
            Set.of(edge("A1", "A2", 10), edge("A2", "S", 20)),
            Set.of());
    // B 区域 build 结果（共享 S）
    RailGraph regionB =
        graph(
            Set.of(node("B1"), node("B2"), node("S")),
            Set.of(edge("B1", "B2", 15), edge("B2", "S", 25)),
            Set.of());

    // 模拟：先 build A（A 成为 base），再 build B（B 成为 update）
    RailGraphMerger.MergeResult merged =
        RailGraphMerger.appendOrReplaceComponents(regionA, regionB);

    // 新行为验证
    assertEquals(RailGraphMerger.MergeAction.REPLACE_COMPONENTS, merged.action());
    // 不应删除任何节点
    assertEquals(0, merged.removedNodes());
    // A 区域的 3 个节点 + B 区域的 2 个新节点（S 重复）= 5
    assertEquals(5, merged.totalNodes());
    // 所有节点都应该存在
    assertTrue(merged.graph().findNode(NodeId.of("A1")).isPresent(), "A1 should exist");
    assertTrue(merged.graph().findNode(NodeId.of("A2")).isPresent(), "A2 should exist");
    assertTrue(merged.graph().findNode(NodeId.of("S")).isPresent(), "S should exist");
    assertTrue(merged.graph().findNode(NodeId.of("B1")).isPresent(), "B1 should exist");
    assertTrue(merged.graph().findNode(NodeId.of("B2")).isPresent(), "B2 should exist");
    // 所有边都应该存在（A1-A2, A2-S, B1-B2, B2-S）
    assertEquals(4, merged.totalEdges());
  }

  /**
   * 回归测试：多次 build 同一区域时，只替换该区域的边，不影响其他区域。
   *
   * <p>场景：已有图包含两个不连通分量 [A, B] 和 [X, Y]。 用户重新 build 了 [A, B] 区域，得到新边长。 合并后 [X, Y] 分量应完全保留，[A, B]
   * 的边应被更新。
   */
  @Test
  void preservesOtherComponentsWhenRebuildingOneArea() {
    // 已有图：两个分量
    RailGraph base =
        graph(
            Set.of(node("A"), node("B"), node("X"), node("Y")),
            Set.of(edge("A", "B", 100), edge("X", "Y", 50)),
            Set.of());
    // 用户重新 build 了 [A, B] 区域，得到更短的边长
    RailGraph update = graph(Set.of(node("A"), node("B")), Set.of(edge("A", "B", 80)), Set.of());

    RailGraphMerger.MergeResult merged = RailGraphMerger.appendOrReplaceComponents(base, update);

    assertEquals(RailGraphMerger.MergeAction.REPLACE_COMPONENTS, merged.action());
    // [X, Y] 分量完整保留
    assertTrue(merged.graph().findNode(NodeId.of("X")).isPresent());
    assertTrue(merged.graph().findNode(NodeId.of("Y")).isPresent());
    // X-Y 边保留
    EdgeId xyEdge = EdgeId.undirected(NodeId.of("X"), NodeId.of("Y"));
    RailEdge xyEdgeObj =
        merged.graph().edges().stream().filter(e -> e.id().equals(xyEdge)).findFirst().orElse(null);
    assertTrue(xyEdgeObj != null, "X-Y edge should be preserved");
    assertEquals(50, xyEdgeObj.lengthBlocks());
    // A-B 边被更新为更短的值
    EdgeId abEdge = EdgeId.undirected(NodeId.of("A"), NodeId.of("B"));
    RailEdge abEdgeObj =
        merged.graph().edges().stream().filter(e -> e.id().equals(abEdge)).findFirst().orElse(null);
    assertEquals(80, abEdgeObj.lengthBlocks());
    // 总共 4 个节点、2 条边
    assertEquals(4, merged.totalNodes());
    assertEquals(2, merged.totalEdges());
  }
}
