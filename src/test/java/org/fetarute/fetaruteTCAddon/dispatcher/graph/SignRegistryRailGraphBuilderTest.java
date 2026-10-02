package org.fetarute.fetaruteTCAddon.dispatcher.graph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.bukkit.World;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.explore.RailBlockAccess;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.explore.RailBlockPos;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeRegistry;
import org.junit.jupiter.api.Test;

class SignRegistryRailGraphBuilderTest {

  @Test
  void builtGraphCarriesBfsFootprintAndWorldInterlockingState() {
    UUID worldId = UUID.fromString("11111111-2222-3333-4444-555555555555");
    World world = mock(World.class);
    when(world.getUID()).thenReturn(worldId);
    when(world.getName()).thenReturn("test-world");

    NodeId a = NodeId.of("A");
    NodeId b = NodeId.of("B");
    SignNodeRegistry registry = new SignNodeRegistry();
    registry.put(worldId, "test-world", 0, 0, 0, definition(a));
    registry.put(worldId, "test-world", 2, 0, 0, definition(b));
    InMemoryRailBlockAccess access = InMemoryRailBlockAccess.line(0, 2);
    SignRegistryRailGraphBuilder builder =
        new SignRegistryRailGraphBuilder(registry, ignored -> {}, 0, 64, ignored -> access);

    RailGraph graph = builder.build(world);

    RailGraphInterlockingSupport support =
        assertInstanceOf(RailGraphInterlockingSupport.class, graph);
    assertTrue(support.interlockingState().available());
    assertEquals(Optional.of(worldId), support.interlockingState().worldId());
    assertTrue(support.interlockingState().coverage().complete());
    assertEquals(0, support.interlockingState().indexedZoneCellCount());
  }

  @Test
  void unresolvedRegistryNodeDowngradesCapturedEdgesToFailClosedCoverage() {
    UUID worldId = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    World world = mock(World.class);
    when(world.getUID()).thenReturn(worldId);
    when(world.getName()).thenReturn("test-world");

    NodeId a = NodeId.of("A");
    NodeId b = NodeId.of("B");
    SignNodeRegistry registry = new SignNodeRegistry();
    registry.put(worldId, "test-world", 0, 0, 0, definition(a));
    registry.put(worldId, "test-world", 2, 0, 0, definition(b));
    registry.put(worldId, "test-world", 10, 0, 0, definition(NodeId.of("MISSING")));
    SignRegistryRailGraphBuilder builder =
        new SignRegistryRailGraphBuilder(
            registry, ignored -> {}, 0, 64, ignored -> InMemoryRailBlockAccess.line(0, 2));

    RailGraph graph = builder.build(world);

    RailEdge edge = graph.edges().iterator().next();
    RailGraphInterlockingSupport support =
        assertInstanceOf(RailGraphInterlockingSupport.class, graph);
    assertFalse(support.interlockingState().coverage().complete());
    assertFalse(support.interlockingState().zoneKeysForEdge(edge.id()).isEmpty());
  }

  private static SignNodeDefinition definition(NodeId nodeId) {
    return new SignNodeDefinition(nodeId, NodeType.WAYPOINT, Optional.empty(), Optional.empty());
  }

  private static final class InMemoryRailBlockAccess implements RailBlockAccess {

    private final Map<RailBlockPos, Set<RailBlockPos>> adjacency;

    private InMemoryRailBlockAccess(Map<RailBlockPos, Set<RailBlockPos>> adjacency) {
      this.adjacency = Map.copyOf(adjacency);
    }

    static InMemoryRailBlockAccess line(int from, int to) {
      Map<RailBlockPos, Set<RailBlockPos>> adjacency = new HashMap<>();
      for (int x = from; x <= to; x++) {
        RailBlockPos pos = new RailBlockPos(x, 0, 0);
        adjacency.putIfAbsent(pos, new HashSet<>());
        if (x > from) {
          RailBlockPos previous = new RailBlockPos(x - 1, 0, 0);
          adjacency.get(pos).add(previous);
          adjacency.computeIfAbsent(previous, ignored -> new HashSet<>()).add(pos);
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

    @Override
    public boolean supportsExactBlockFootprint() {
      return true;
    }
  }
}
