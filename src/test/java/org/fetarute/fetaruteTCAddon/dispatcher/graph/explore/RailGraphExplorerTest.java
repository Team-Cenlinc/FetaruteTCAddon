package org.fetarute.fetaruteTCAddon.dispatcher.graph.explore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.junit.jupiter.api.Test;

final class RailGraphExplorerTest {

  @Test
  void exploresDirectDistanceOnSimpleLine() {
    InMemoryRailBlockAccess access = InMemoryRailBlockAccess.line(0, 5);

    NodeId a = NodeId.of("A");
    NodeId b = NodeId.of("B");
    Map<EdgeId, Integer> lengths =
        RailGraphExplorer.exploreEdgeLengths(
            Map.of(
                a, Set.of(new RailBlockPos(0, 0, 0)),
                b, Set.of(new RailBlockPos(5, 0, 0))),
            access,
            64);

    EdgeId edge = EdgeId.undirected(a, b);
    assertTrue(lengths.containsKey(edge));
    assertEquals(5, lengths.get(edge));
  }

  @Test
  void doesNotSkipIntermediateNodeWhenExploring() {
    InMemoryRailBlockAccess access = InMemoryRailBlockAccess.line(0, 5);

    NodeId a = NodeId.of("A");
    NodeId b = NodeId.of("B");
    NodeId c = NodeId.of("C");
    Map<EdgeId, Integer> lengths =
        RailGraphExplorer.exploreEdgeLengths(
            Map.of(
                a, Set.of(new RailBlockPos(0, 0, 0)),
                c, Set.of(new RailBlockPos(3, 0, 0)),
                b, Set.of(new RailBlockPos(5, 0, 0))),
            access,
            64);

    assertEquals(2, lengths.size());
    assertEquals(3, lengths.get(EdgeId.undirected(a, c)));
    assertEquals(2, lengths.get(EdgeId.undirected(c, b)));
    assertFalse(lengths.containsKey(EdgeId.undirected(a, b)));
  }

  @Test
  void choosesShortestAmongMultipleAnchors() {
    InMemoryRailBlockAccess access = InMemoryRailBlockAccess.line(0, 4);

    NodeId a = NodeId.of("A");
    NodeId b = NodeId.of("B");
    Map<EdgeId, Integer> lengths =
        RailGraphExplorer.exploreEdgeLengths(
            Map.of(
                a, Set.of(new RailBlockPos(0, 0, 0), new RailBlockPos(1, 0, 0)),
                b, Set.of(new RailBlockPos(4, 0, 0))),
            access,
            64);

    assertEquals(1, lengths.size());
    assertEquals(3, lengths.get(EdgeId.undirected(a, b)));
  }

  /** 用于单元测试的内存轨道图：直接用邻接表描述 railNeighbors。 */
  private static final class InMemoryRailBlockAccess implements RailBlockAccess {

    private final Map<RailBlockPos, Set<RailBlockPos>> adjacency;

    private InMemoryRailBlockAccess(Map<RailBlockPos, Set<RailBlockPos>> adjacency) {
      this.adjacency = Map.copyOf(adjacency);
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

    @Override
    public boolean isRail(RailBlockPos pos) {
      return adjacency.containsKey(pos);
    }

    @Override
    public Set<RailBlockPos> neighbors(RailBlockPos pos) {
      return adjacency.getOrDefault(pos, Set.of());
    }
  }
}
