package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.HashMap;
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
import org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinitionCache;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteId;
import org.junit.jupiter.api.Test;

/** 越站的换向判据：把这一站前后两段交路按调度图展开，比站前最后一个节点与站后第一个节点；判不了一律按换向算（不让越）。 */
class StopReversalTest {

  private static final String TRAIN = "SURC-WS-A-0001";
  private static final NodeId W1 = NodeId.of("SURC:AAA:SSS:1:001");
  private static final NodeId W2 = NodeId.of("SURC:SSS:BBB:1:001");
  private static final NodeId T = NodeId.of("SURC:AAA:SSS:1:002");
  private static final NodeId U = NodeId.of("SURC:SSS:BBB:1:002");
  private static final NodeId S1 = NodeId.of("SURC:S:SSS:1");
  private static final NodeId S2 = NodeId.of("SURC:S:SSS:2");

  /** 交路 W1 → SSS → W2，SSS 是第 1 个下标。 */
  private static final RouteDefinition ROUTE =
      new RouteDefinition(RouteId.of("SURC:WS:WS-N"), List.of(W1, S1, W2), Optional.empty());

  private final RouteDefinitionCache routes = mock(RouteDefinitionCache.class);

  private StopReversal reversal(RailGraph graph) {
    when(routes.findStop(any(), anyInt())).thenReturn(Optional.empty());
    return new StopReversal(() -> List.of(graph), () -> 1L, routes, (train, route) -> null);
  }

  /** 一张带坐标的小图。 */
  private static final class Graph {
    private final Map<NodeId, RailNode> nodes = new HashMap<>();
    private final Map<EdgeId, RailEdge> edges = new HashMap<>();

    Graph node(NodeId id, double x, double z) {
      NodeType type = id.value().contains(":S:") ? NodeType.STATION : NodeType.WAYPOINT;
      nodes.put(
          id, new SignRailNode(id, type, new Vector(x, 0, z), Optional.empty(), Optional.empty()));
      return this;
    }

    Graph edge(NodeId a, NodeId b) {
      EdgeId id = EdgeId.undirected(a, b);
      edges.put(id, new RailEdge(id, a, b, 10, 0.0, true, Optional.empty()));
      return this;
    }

    RailGraph build() {
      return new SimpleRailGraph(new HashMap<>(nodes), new HashMap<>(edges), Set.of());
    }
  }

  @Test
  void aThroughStationRunsThrough() {
    RailGraph graph =
        new Graph()
            .node(W1, 0, 0)
            .node(T, 5, 0)
            .node(S1, 10, 0)
            .node(U, 15, 0)
            .node(W2, 20, 0)
            .edge(W1, T)
            .edge(T, S1)
            .edge(S1, U)
            .edge(U, W2)
            .build();

    assertTrue(reversal(graph).through(TRAIN, ROUTE, 1));
  }

  /** 前后两段在站的同一头接上：车从哪来就往哪回，路径点不同也认得出。 */
  @Test
  void goingBackOutTheWayItCameIsAReversal() {
    RailGraph graph =
        new Graph()
            .node(W1, 0, 5)
            .node(W2, 0, -5)
            .node(T, 5, 0)
            .node(S1, 10, 0)
            .node(U, 15, 0)
            .edge(W1, T)
            .edge(W2, T)
            .edge(T, S1)
            .edge(S1, U)
            .build();

    assertFalse(reversal(graph).through(TRAIN, ROUTE, 1));
  }

  /** 车站两头接的节点在同一侧：看不出直通还是折返，不让越。 */
  @Test
  void neighboursOnTheSameSideAreRefused() {
    RailGraph graph =
        new Graph()
            .node(W1, -10, 5)
            .node(W2, -10, -5)
            .node(T, 0, 1)
            .node(U, 0, -1)
            .node(S1, 10, 0)
            .edge(W1, T)
            .edge(T, S1)
            .edge(S1, U)
            .edge(U, W2)
            .build();

    assertFalse(reversal(graph).through(TRAIN, ROUTE, 1));
  }

  /** 站节点接了三条以上区间：哪两条是“一进一出”说不准，不让越。 */
  @Test
  void aStationWithThreeLinksIsRefused() {
    NodeId spur = NodeId.of("SURC:SSS:CCC:1:001");
    RailGraph graph =
        new Graph()
            .node(W1, 0, 0)
            .node(S1, 10, 0)
            .node(W2, 20, 0)
            .node(spur, 10, 10)
            .edge(W1, S1)
            .edge(S1, W2)
            .edge(S1, spur)
            .build();

    assertFalse(reversal(graph).through(TRAIN, ROUTE, 1));
  }

  /** 站不在任何一张调度图上（图没建、跨了世界）：不让越。 */
  @Test
  void aStationMissingFromTheGraphIsRefused() {
    RailGraph graph = new Graph().node(W1, 0, 0).node(W2, 20, 0).edge(W1, W2).build();

    assertFalse(reversal(graph).through(TRAIN, ROUTE, 1));
  }

  /** DYNAMIC 停站：每一条候选股道都要直通，有一条是尽头线就不让越。 */
  @Test
  void everyDynamicTrackMustRunThrough() {
    Graph base =
        new Graph()
            .node(W1, 0, 0)
            .node(S1, 10, 0)
            .node(S2, 10, 4)
            .node(W2, 20, 0)
            .edge(W1, S1)
            .edge(S1, W2)
            .edge(W1, S2);
    StopReversal deadEnd = reversal(base.build());
    when(routes.findStop(ROUTE.id(), 1))
        .thenReturn(
            Optional.of(
                RuntimeDispatchTestFixtures.dynamicStop(1, S1, "DYNAMIC:SURC:S:SSS:[1:2]")));
    assertFalse(deadEnd.through(TRAIN, ROUTE, 1), "2 道是尽头线");

    StopReversal bothThrough = reversal(base.edge(S2, W2).build());
    when(routes.findStop(ROUTE.id(), 1))
        .thenReturn(
            Optional.of(
                RuntimeDispatchTestFixtures.dynamicStop(1, S1, "DYNAMIC:SURC:S:SSS:[1:2]")));
    assertTrue(bothThrough.through(TRAIN, ROUTE, 1));
  }

  /** 交路首尾没有前后两段：判不了。 */
  @Test
  void theEndsOfTheRouteCannotBeJudged() {
    RailGraph graph = new Graph().node(W1, 0, 0).node(S1, 10, 0).node(W2, 20, 0).build();
    List<NodeId> nodes = new ArrayList<>(ROUTE.waypoints());

    assertFalse(reversal(graph).through(TRAIN, ROUTE, 0));
    assertFalse(reversal(graph).through(TRAIN, ROUTE, nodes.size() - 1));
  }
}
