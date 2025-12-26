package org.fetarute.fetaruteTCAddon.dispatcher.graph.explore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.junit.jupiter.api.Test;

final class RailGraphMultiSourceExplorerSessionTest {

  @Test
  void exploresDirectDistanceOnSimpleLine() {
    InMemoryRailBlockAccess access = InMemoryRailBlockAccess.line(0, 5);
    NodeId a = NodeId.of("A");
    NodeId b = NodeId.of("B");
    RailGraphMultiSourceExplorerSession session =
        new RailGraphMultiSourceExplorerSession(
            Map.of(
                a, Set.of(new RailBlockPos(0, 0, 0)),
                b, Set.of(new RailBlockPos(5, 0, 0))),
            access,
            64);

    while (!session.isDone()) {
      session.step(1);
    }

    EdgeId edge = EdgeId.undirected(a, b);
    Map<EdgeId, Integer> lengths = session.edgeLengths();
    assertTrue(lengths.containsKey(edge));
    assertEquals(5, lengths.get(edge));
  }

  @Test
  void doesNotSkipIntermediateNodeWhenExploring() {
    InMemoryRailBlockAccess access = InMemoryRailBlockAccess.line(0, 5);

    NodeId a = NodeId.of("A");
    NodeId b = NodeId.of("B");
    NodeId c = NodeId.of("C");
    RailGraphMultiSourceExplorerSession session =
        new RailGraphMultiSourceExplorerSession(
            Map.of(
                a, Set.of(new RailBlockPos(0, 0, 0)),
                c, Set.of(new RailBlockPos(3, 0, 0)),
                b, Set.of(new RailBlockPos(5, 0, 0))),
            access,
            64);

    while (!session.isDone()) {
      session.step(10);
    }

    Map<EdgeId, Integer> lengths = session.edgeLengths();
    assertEquals(2, lengths.size());
    assertEquals(3, lengths.get(EdgeId.undirected(a, c)));
    assertEquals(2, lengths.get(EdgeId.undirected(c, b)));
    assertFalse(lengths.containsKey(EdgeId.undirected(a, b)));
  }

  @Test
  void usesWeightedCostsToChooseCheapestPath() {
    RailBlockPos n0 = new RailBlockPos(0, 0, 0);
    RailBlockPos n1 = new RailBlockPos(1, 0, 0);
    RailBlockPos n2 = new RailBlockPos(2, 0, 0);
    RailBlockPos a = new RailBlockPos(0, 0, 1);
    RailBlockPos b = new RailBlockPos(1, 0, 1);
    RailBlockPos c = new RailBlockPos(2, 0, 1);

    InMemoryRailBlockAccess access = new InMemoryRailBlockAccess();
    access.connect(n0, n1, 100.0);
    access.connect(n1, n2, 100.0);
    access.connect(n0, a, 1.0);
    access.connect(a, b, 1.0);
    access.connect(b, c, 1.0);
    access.connect(c, n2, 1.0);

    NodeId nodeA = NodeId.of("A");
    NodeId nodeB = NodeId.of("B");
    RailGraphMultiSourceExplorerSession session =
        new RailGraphMultiSourceExplorerSession(
            Map.of(nodeA, Set.of(n0), nodeB, Set.of(n2)), access, 4096);

    while (!session.isDone()) {
      session.step(10);
    }

    Map<EdgeId, Integer> lengths = session.edgeLengths();
    assertEquals(1, lengths.size());
    assertEquals(4, lengths.get(EdgeId.undirected(nodeA, nodeB)));
  }

  @Test
  void choosesShortestAmongMultipleAnchors() {
    InMemoryRailBlockAccess access = InMemoryRailBlockAccess.line(0, 4);

    NodeId a = NodeId.of("A");
    NodeId b = NodeId.of("B");
    RailGraphMultiSourceExplorerSession session =
        new RailGraphMultiSourceExplorerSession(
            Map.of(
                a, Set.of(new RailBlockPos(0, 0, 0), new RailBlockPos(1, 0, 0)),
                b, Set.of(new RailBlockPos(4, 0, 0))),
            access,
            64);

    while (!session.isDone()) {
      session.step(2);
    }

    Map<EdgeId, Integer> lengths = session.edgeLengths();
    assertEquals(1, lengths.size());
    assertEquals(3, lengths.get(EdgeId.undirected(a, b)));
  }

  @Test
  void notifiesJunctionBlocksWhenBranching() {
    RailBlockPos center = new RailBlockPos(0, 0, 0);
    RailBlockPos a = new RailBlockPos(1, 0, 0);
    RailBlockPos b = new RailBlockPos(-1, 0, 0);
    RailBlockPos c = new RailBlockPos(0, 0, 1);

    InMemoryRailBlockAccess access = new InMemoryRailBlockAccess();
    access.connect(center, a, 1.0);
    access.connect(center, b, 1.0);
    access.connect(center, c, 1.0);

    List<RailBlockPos> junctions = new CopyOnWriteArrayList<>();
    RailGraphMultiSourceExplorerSession session =
        new RailGraphMultiSourceExplorerSession(
            Map.of(NodeId.of("A"), Set.of(center)), access, 64, junctions::add);

    while (!session.isDone()) {
      session.step(10);
    }

    assertTrue(junctions.contains(center));
  }

  /** 用于单元测试的内存轨道图：直接用邻接表描述 railNeighbors。 */
  private static final class InMemoryRailBlockAccess implements RailBlockAccess {

    private final Map<RailBlockPos, Set<RailBlockPos>> adjacency = new HashMap<>();
    private final Map<RailBlockPos, Map<RailBlockPos, Double>> weights = new HashMap<>();

    private InMemoryRailBlockAccess() {}

    private InMemoryRailBlockAccess(Map<RailBlockPos, Set<RailBlockPos>> adjacency) {
      this.adjacency.putAll(adjacency);
    }

    static InMemoryRailBlockAccess line(int from, int to) {
      if (to < from) {
        throw new IllegalArgumentException("to 必须 >= from");
      }
      Map<RailBlockPos, Set<RailBlockPos>> adjacency = new HashMap<>();
      for (int x = from; x <= to; x++) {
        RailBlockPos pos = new RailBlockPos(x, 0, 0);
        adjacency.putIfAbsent(pos, new HashSet<>());
        if (x > from) {
          RailBlockPos prev = new RailBlockPos(x - 1, 0, 0);
          adjacency.get(pos).add(prev);
          adjacency.computeIfAbsent(prev, ignored -> new HashSet<>()).add(pos);
        }
      }
      return new InMemoryRailBlockAccess(adjacency);
    }

    void connect(RailBlockPos a, RailBlockPos b, double cost) {
      adjacency.computeIfAbsent(a, ignored -> new HashSet<>()).add(b);
      adjacency.computeIfAbsent(b, ignored -> new HashSet<>()).add(a);
      weights.computeIfAbsent(a, ignored -> new HashMap<>()).put(b, cost);
      weights.computeIfAbsent(b, ignored -> new HashMap<>()).put(a, cost);
    }

    @Override
    public boolean isRail(RailBlockPos pos) {
      return adjacency.containsKey(pos);
    }

    @Override
    public Set<RailBlockPos> neighbors(RailBlockPos pos) {
      return adjacency.getOrDefault(pos, Set.of());
    }

    @Override
    public double stepCost(RailBlockPos from, RailBlockPos to) {
      return weights.getOrDefault(from, Map.of()).getOrDefault(to, 1.0);
    }
  }
}
