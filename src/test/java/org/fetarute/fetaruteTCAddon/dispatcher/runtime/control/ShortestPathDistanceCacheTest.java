package org.fetarute.fetaruteTCAddon.dispatcher.runtime.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.bukkit.util.Vector;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailEdge;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.SignRailNode;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.SimpleRailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.query.RailGraphPathFinder;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode;
import org.junit.jupiter.api.Test;

final class ShortestPathDistanceCacheTest {

  private static final NodeId A = NodeId.of("A");
  private static final NodeId B = NodeId.of("B");

  @Test
  void graphSwapInvalidatesCachedUnreachable() {
    AtomicLong version = new AtomicLong(1);
    ShortestPathDistanceCache cache =
        new ShortestPathDistanceCache(new RailGraphPathFinder(), Duration.ofHours(1), null);
    cache.setGraphVersion(version::get);

    RailGraph without = graph(false);
    assertEquals(OptionalLong.empty(), cache.resolve(without, A, B));

    // 增补连上了 A-B：图版本变了，旧的"不可达"不能再用。
    version.incrementAndGet();
    OptionalLong reachable = cache.resolve(graph(true), A, B);

    assertTrue(reachable.isPresent());
    assertEquals(12, reachable.getAsLong());
  }

  @Test
  void sameVersionKeepsServingTheCachedDistance() {
    ShortestPathDistanceCache cache =
        new ShortestPathDistanceCache(new RailGraphPathFinder(), Duration.ofHours(1), null);
    cache.setGraphVersion(() -> 7L);

    cache.resolve(graph(true), A, B);
    cache.resolve(graph(true), A, B);

    assertEquals(1, cache.stats().hits());
    assertEquals(1, cache.stats().misses());
  }

  private static RailGraph graph(boolean connected) {
    EdgeId id = EdgeId.undirected(A, B);
    Map<EdgeId, RailEdge> edges =
        connected
            ? Map.of(id, new RailEdge(id, id.a(), id.b(), 12, 0.0, true, Optional.empty()))
            : Map.of();
    return new SimpleRailGraph(Map.of(A, node(A), B, node(B)), edges, Set.of());
  }

  private static RailNode node(NodeId id) {
    return new SignRailNode(
        id, NodeType.WAYPOINT, new Vector(0, 64, 0), Optional.empty(), Optional.empty());
  }
}
