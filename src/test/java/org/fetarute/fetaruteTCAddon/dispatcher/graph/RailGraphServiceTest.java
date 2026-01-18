package org.fetarute.fetaruteTCAddon.dispatcher.graph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.bukkit.World;
import org.bukkit.util.Vector;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode;
import org.junit.jupiter.api.Test;

class RailGraphServiceTest {

  @Test
  void findWorldIdForPathUsesSingleGraph() {
    NodeId a = NodeId.of("A");
    NodeId b = NodeId.of("B");
    NodeId c = NodeId.of("C");
    RailGraph graph = graphWithEdges(edge(a, b), edge(b, c));

    RailGraphService service = new RailGraphService(world -> graph);
    World world = mock(World.class);
    UUID worldId = UUID.randomUUID();
    when(world.getUID()).thenReturn(worldId);
    when(world.getName()).thenReturn("world");
    service.putSnapshot(world, graph, Instant.now());

    Optional<UUID> found = service.findWorldIdForPath(List.of(a, b, c));

    assertEquals(Optional.of(worldId), found);
  }

  @Test
  void findWorldIdForPathReturnsEmptyWhenEdgesSplitAcrossWorlds() {
    NodeId a = NodeId.of("A");
    NodeId b = NodeId.of("B");
    NodeId c = NodeId.of("C");
    RailGraph graphA = graphWithEdges(edge(a, b));
    RailGraph graphB = graphWithEdges(edge(b, c));

    RailGraphService service = new RailGraphService(world -> graphA);
    World world1 = mock(World.class);
    UUID worldId1 = UUID.randomUUID();
    when(world1.getUID()).thenReturn(worldId1);
    when(world1.getName()).thenReturn("world1");
    service.putSnapshot(world1, graphA, Instant.now());

    World world2 = mock(World.class);
    UUID worldId2 = UUID.randomUUID();
    when(world2.getUID()).thenReturn(worldId2);
    when(world2.getName()).thenReturn("world2");
    service.putSnapshot(world2, graphB, Instant.now());

    Optional<UUID> found = service.findWorldIdForPath(List.of(a, b, c));

    assertTrue(found.isEmpty());
  }

  private static RailEdge edge(NodeId a, NodeId b) {
    return new RailEdge(EdgeId.undirected(a, b), a, b, 10, -1.0, true, Optional.empty());
  }

  private static RailGraph graphWithEdges(RailEdge... edges) {
    List<RailEdge> edgeList = List.of(edges);
    Map<NodeId, RailNode> nodes =
        edgeList.stream()
            .flatMap(edge -> java.util.stream.Stream.of(edge.from(), edge.to()))
            .distinct()
            .collect(java.util.stream.Collectors.toMap(id -> id, RailGraphServiceTest::node));
    return new RailGraph() {
      @Override
      public java.util.Collection<RailNode> nodes() {
        return nodes.values();
      }

      @Override
      public java.util.Collection<RailEdge> edges() {
        return edgeList;
      }

      @Override
      public Optional<RailNode> findNode(NodeId id) {
        return Optional.ofNullable(nodes.get(id));
      }

      @Override
      public Set<RailEdge> edgesFrom(NodeId id) {
        return edgeList.stream()
            .filter(edge -> edge.from().equals(id) || edge.to().equals(id))
            .collect(java.util.stream.Collectors.toSet());
      }

      @Override
      public boolean isBlocked(EdgeId id) {
        return false;
      }
    };
  }

  private static RailNode node(NodeId id) {
    return new RailNode() {
      @Override
      public NodeId id() {
        return id;
      }

      @Override
      public NodeType type() {
        return NodeType.WAYPOINT;
      }

      @Override
      public Vector worldPosition() {
        return new Vector(0, 0, 0);
      }

      @Override
      public Optional<String> trainCartsDestination() {
        return Optional.empty();
      }
    };
  }
}
