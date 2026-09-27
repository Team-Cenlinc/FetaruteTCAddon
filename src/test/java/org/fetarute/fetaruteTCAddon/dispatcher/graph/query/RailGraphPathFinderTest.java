package org.fetarute.fetaruteTCAddon.dispatcher.graph.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.bukkit.util.Vector;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailEdge;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.SignRailNode;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.SimpleRailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.junit.jupiter.api.Test;

final class RailGraphPathFinderTest {

  @Test
  void returnsEmptyWhenNodeMissing() {
    RailGraph graph = SimpleRailGraph.empty();
    RailGraphPathFinder finder = new RailGraphPathFinder();

    assertTrue(
        finder
            .shortestPath(
                graph,
                NodeId.of("A"),
                NodeId.of("B"),
                RailGraphPathFinder.Options.shortestDistance())
            .isEmpty());
  }

  @Test
  void returnsTrivialPathWhenFromEqualsTo() {
    RailGraph graph = graph(Set.of(node("A")), Set.of(), Set.of());
    RailGraphPathFinder finder = new RailGraphPathFinder();

    RailGraphPath path =
        finder
            .shortestPath(
                graph,
                NodeId.of("A"),
                NodeId.of("A"),
                RailGraphPathFinder.Options.shortestDistance())
            .orElseThrow();

    assertEquals(1, path.nodes().size());
    assertEquals(0, path.edges().size());
    assertEquals(0L, path.totalLengthBlocks());
  }

  @Test
  void choosesShortestPathByLength() {
    RailGraph graph =
        graph(
            Set.of(node("A"), node("B"), node("C")),
            Set.of(edge("A", "B", 10), edge("A", "C", 3), edge("C", "B", 4)),
            Set.of());
    RailGraphPathFinder finder = new RailGraphPathFinder();

    RailGraphPath path =
        finder
            .shortestPath(
                graph,
                NodeId.of("A"),
                NodeId.of("B"),
                RailGraphPathFinder.Options.shortestDistance())
            .orElseThrow();

    assertEquals(List.of(NodeId.of("A"), NodeId.of("C"), NodeId.of("B")), path.nodes());
    assertEquals(2, path.edges().size());
    assertEquals(7L, path.totalLengthBlocks());
  }

  @Test
  void skipsBlockedEdgesByDefault() {
    EdgeId blocked = EdgeId.undirected(NodeId.of("A"), NodeId.of("B"));
    RailGraph graph =
        graph(
            Set.of(node("A"), node("B"), node("C")),
            Set.of(edge("A", "B", 5), edge("A", "C", 2), edge("C", "B", 2)),
            Set.of(blocked));
    RailGraphPathFinder finder = new RailGraphPathFinder();

    RailGraphPath path =
        finder
            .shortestPath(
                graph,
                NodeId.of("A"),
                NodeId.of("B"),
                RailGraphPathFinder.Options.shortestDistance())
            .orElseThrow();

    assertEquals(4L, path.totalLengthBlocks());
    assertEquals(List.of(NodeId.of("A"), NodeId.of("C"), NodeId.of("B")), path.nodes());
  }

  /**
   * 会让环两股道等长：两个方向都走较小的那条。
   *
   * <p>此前优先队列只比距离、松弛用严格 {@code <}，谁先被 {@code edgesFrom} 遍历到谁赢，而那个顺序每个 JVM 随机一次。
   */
  @Test
  void equalLengthPassingLoopResolvesToSmallerTrackInBothDirections() {
    RailGraph graph = passingLoop();
    RailGraphPathFinder finder = new RailGraphPathFinder();

    RailGraphPath eastbound =
        finder
            .shortestPath(
                graph,
                NodeId.of("SWITCHER:B1:W"),
                NodeId.of("SWITCHER:B1:E"),
                RailGraphPathFinder.Options.shortestDistance())
            .orElseThrow();
    RailGraphPath westbound =
        finder
            .shortestPath(
                graph,
                NodeId.of("SWITCHER:B1:E"),
                NodeId.of("SWITCHER:B1:W"),
                RailGraphPathFinder.Options.shortestDistance())
            .orElseThrow();

    assertEquals(
        List.of(NodeId.of("SWITCHER:B1:W"), NodeId.of("OP:S:B1:1"), NodeId.of("SWITCHER:B1:E")),
        eastbound.nodes());
    assertEquals(
        List.of(NodeId.of("SWITCHER:B1:E"), NodeId.of("OP:S:B1:1"), NodeId.of("SWITCHER:B1:W")),
        westbound.nodes());
  }

  /** 平局规则写在寻路器里，不依赖图的遍历顺序：把邻接顺序整体倒过来，结果不变。 */
  @Test
  void tieBreakDoesNotDependOnAdjacencyIterationOrder() {
    RailGraph natural = passingLoop();
    RailGraph reversed = reversedAdjacency(natural);
    RailGraphPathFinder finder = new RailGraphPathFinder();
    NodeId from = NodeId.of("OP:S:A1:1");
    NodeId to = NodeId.of("OP:S:C1:1");

    RailGraphPath viaNatural =
        finder
            .shortestPath(natural, from, to, RailGraphPathFinder.Options.shortestDistance())
            .orElseThrow();
    RailGraphPath viaReversed =
        finder
            .shortestPath(reversed, from, to, RailGraphPathFinder.Options.shortestDistance())
            .orElseThrow();

    assertEquals(viaNatural.nodes(), viaReversed.nodes());
    assertTrue(viaNatural.nodes().contains(NodeId.of("OP:S:B1:1")), viaNatural.nodes().toString());
  }

  /**
   * 区间数相同时的规则是"从终点倒推取最小前驱"，不是"优先 1 道"：股道两侧另有节点时，比的是靠终点一侧的节点。
   *
   * <p>这里 1 道东侧节点是 {@code Y9}、2 道东侧是 {@code Y1}，自东向西倒推先比 {@code Y1 < Y9}，于是走 2 道。钉住这条是为了让任何人读到"平局选
   * 1 道"时知道那只是简单会让环上的巧合。
   */
  @Test
  void tieBreakComparesDestinationSidePredecessorsNotTrackNumbers() {
    RailGraph graph =
        graph(
            Set.of(
                node("W"),
                node("X1"),
                node("T1"),
                node("Y9"),
                node("X2"),
                node("T2"),
                node("Y1"),
                node("E")),
            Set.of(
                edge("W", "X1", 5),
                edge("X1", "T1", 5),
                edge("T1", "Y9", 5),
                edge("Y9", "E", 5),
                edge("W", "X2", 5),
                edge("X2", "T2", 5),
                edge("T2", "Y1", 5),
                edge("Y1", "E", 5)),
            Set.of());
    RailGraphPathFinder finder = new RailGraphPathFinder();

    RailGraphPath path =
        finder
            .shortestPath(
                graph,
                NodeId.of("W"),
                NodeId.of("E"),
                RailGraphPathFinder.Options.shortestDistance())
            .orElseThrow();

    assertEquals(
        List.of(NodeId.of("W"), NodeId.of("X2"), NodeId.of("T2"), NodeId.of("Y1"), NodeId.of("E")),
        path.nodes());
  }

  /**
   * 剪刀渡线：直股一条区间 18 格，穿菱形"斜线 7 + 中心 4 + 斜线 7"也是 18 格。取直股。
   *
   * <p>坐标照抄实服 PPK 咽喉（2026-09-26）。只比节点序时终点的两个前驱是 {@code -583:65:630}（直股）与 {@code -581:65:643}（菱形），
   * 后者字典序更小，于是规划穿菱形；实车沿直股开出，菱形上的占用等不到"经过即释放"，一直挂到列车回库销毁。
   */
  @Test
  void equalLengthScissorsCrossoverPrefersTheStraightTrack() {
    NodeId station = NodeId.of("SURC:S:PPK:2");
    NodeId south = NodeId.of("SWITCHER:Towny:-583:65:630");
    NodeId north = NodeId.of("SWITCHER:Towny:-583:65:650");
    NodeId next = NodeId.of("SURC:PPK:JBS:2:001");
    RailGraph graph =
        graph(
            Set.of(
                node(station.value()),
                node(south.value()),
                node("SWITCHER:Towny:-581:65:637"),
                node("SWITCHER:Towny:-581:65:643"),
                node(north.value()),
                node(next.value())),
            Set.of(
                edge(station.value(), south.value(), 12),
                edge(south.value(), north.value(), 18),
                edge(south.value(), "SWITCHER:Towny:-581:65:637", 7),
                edge("SWITCHER:Towny:-581:65:637", "SWITCHER:Towny:-581:65:643", 4),
                edge("SWITCHER:Towny:-581:65:643", north.value(), 7),
                edge(north.value(), next.value(), 40)),
            Set.of());
    RailGraphPathFinder finder = new RailGraphPathFinder();

    RailGraphPath path =
        finder
            .shortestPath(graph, station, next, RailGraphPathFinder.Options.shortestDistance())
            .orElseThrow();

    assertEquals(List.of(station, south, north, next), path.nodes());
    assertEquals(70L, path.totalLengthBlocks());
  }

  /** 区间数优先于节点序：两条等长路径，节点序偏向的那条多一条区间，仍取区间少的。 */
  @Test
  void fewerEdgesWinOverNodeOrderAmongEqualLengthPaths() {
    RailGraph graph =
        graph(
            Set.of(node("A"), node("B1"), node("B2"), node("Z"), node("E")),
            Set.of(
                edge("A", "B1", 5),
                edge("B1", "B2", 5),
                edge("B2", "E", 5),
                edge("A", "Z", 7),
                edge("Z", "E", 8)),
            Set.of());
    RailGraphPathFinder finder = new RailGraphPathFinder();

    RailGraphPath path =
        finder
            .shortestPath(
                graph,
                NodeId.of("A"),
                NodeId.of("E"),
                RailGraphPathFinder.Options.shortestDistance())
            .orElseThrow();

    assertEquals(List.of(NodeId.of("A"), NodeId.of("Z"), NodeId.of("E")), path.nodes());
  }

  /** A1 —— B1 会让环（两股道等长）—— C1。 */
  private static RailGraph passingLoop() {
    return graph(
        Set.of(
            node("OP:S:A1:1"),
            node("SWITCHER:B1:W"),
            node("OP:S:B1:1"),
            node("OP:S:B1:2"),
            node("SWITCHER:B1:E"),
            node("OP:S:C1:1")),
        Set.of(
            edge("OP:S:A1:1", "SWITCHER:B1:W", 30),
            edge("SWITCHER:B1:W", "OP:S:B1:1", 30),
            edge("SWITCHER:B1:W", "OP:S:B1:2", 30),
            edge("OP:S:B1:1", "SWITCHER:B1:E", 30),
            edge("OP:S:B1:2", "SWITCHER:B1:E", 30),
            edge("SWITCHER:B1:E", "OP:S:C1:1", 30)),
        Set.of());
  }

  /** 委托给 {@code delegate}，但每个节点的邻接区间以相反顺序给出。 */
  private static RailGraph reversedAdjacency(RailGraph delegate) {
    return new RailGraph() {
      @Override
      public java.util.Collection<org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode> nodes() {
        return delegate.nodes();
      }

      @Override
      public java.util.Collection<RailEdge> edges() {
        return delegate.edges();
      }

      @Override
      public Optional<org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode> findNode(NodeId id) {
        return delegate.findNode(id);
      }

      @Override
      public Set<RailEdge> edgesFrom(NodeId id) {
        List<RailEdge> edges = new java.util.ArrayList<>(delegate.edgesFrom(id));
        java.util.Collections.reverse(edges);
        return new java.util.LinkedHashSet<>(edges);
      }

      @Override
      public boolean isBlocked(EdgeId id) {
        return delegate.isBlocked(id);
      }
    };
  }

  private static RailGraph graph(
      Set<org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode> nodes,
      Set<RailEdge> edges,
      Set<EdgeId> blockedEdges) {
    Map<NodeId, org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode> nodesById =
        new java.util.HashMap<>();
    for (org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode node : nodes) {
      nodesById.put(node.id(), node);
    }
    Map<EdgeId, RailEdge> edgesById = new java.util.HashMap<>();
    for (RailEdge edge : edges) {
      edgesById.put(edge.id(), edge);
    }
    return new SimpleRailGraph(nodesById, edgesById, blockedEdges);
  }

  private static org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode node(String id) {
    return new SignRailNode(
        NodeId.of(id), NodeType.WAYPOINT, new Vector(0, 0, 0), Optional.empty(), Optional.empty());
  }

  private static RailEdge edge(String a, String b, int lengthBlocks) {
    EdgeId id = EdgeId.undirected(NodeId.of(a), NodeId.of(b));
    return new RailEdge(id, id.a(), id.b(), lengthBlocks, 0.0, true, Optional.empty());
  }
}
