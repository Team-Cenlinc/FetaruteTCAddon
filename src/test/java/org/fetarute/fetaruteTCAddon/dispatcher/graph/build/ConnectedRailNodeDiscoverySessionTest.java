package org.fetarute.fetaruteTCAddon.dispatcher.graph.build;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.World;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.explore.RailBlockAccess;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.explore.RailBlockPos;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.persist.RailNodeRecord;
import org.junit.jupiter.api.Test;

final class ConnectedRailNodeDiscoverySessionTest {

  @Test
  void doesNotCreateAutoSwitcherWhenNeighborCountAtLeast3() {
    World world = mock(World.class);
    when(world.getUID()).thenReturn(UUID.randomUUID());
    when(world.getName()).thenReturn("world");

    RailBlockPos center = new RailBlockPos(0, 64, 0);
    RailBlockPos a = new RailBlockPos(1, 64, 0);
    RailBlockPos b = new RailBlockPos(-1, 64, 0);
    RailBlockPos c = new RailBlockPos(0, 64, 1);

    RailBlockAccess access =
        new FakeRailBlockAccess(
            Set.of(center, a, b, c),
            Map.of(
                center, Set.of(a, b, c), a, Set.of(center), b, Set.of(center), c, Set.of(center)));

    ConnectedRailNodeDiscoverySession session =
        new ConnectedRailNodeDiscoverySession(
            world, Set.of(center), access, message -> {}, ChunkLoadOptions.disabled(), null);

    Map<String, RailNodeRecord> nodes = new HashMap<>();
    session.step(System.nanoTime() + 1_000_000_000L, nodes);

    assertTrue(nodes.isEmpty());
  }

  @Test
  void doesNotCreateAutoSwitcherWhenNeighborCountLessThan3() {
    World world = mock(World.class);
    when(world.getUID()).thenReturn(UUID.randomUUID());
    when(world.getName()).thenReturn("world");

    RailBlockPos center = new RailBlockPos(0, 64, 0);
    RailBlockPos a = new RailBlockPos(1, 64, 0);
    RailBlockPos b = new RailBlockPos(-1, 64, 0);

    RailBlockAccess access =
        new FakeRailBlockAccess(
            Set.of(center, a, b),
            Map.of(center, Set.of(a, b), a, Set.of(center), b, Set.of(center)));

    ConnectedRailNodeDiscoverySession session =
        new ConnectedRailNodeDiscoverySession(
            world, Set.of(center), access, message -> {}, ChunkLoadOptions.disabled(), null);

    Map<String, RailNodeRecord> nodes = new HashMap<>();
    session.step(System.nanoTime() + 1_000_000_000L, nodes);

    assertTrue(nodes.isEmpty());
  }

  private static final class FakeRailBlockAccess implements RailBlockAccess {
    private final Set<RailBlockPos> rails;
    private final Map<RailBlockPos, Set<RailBlockPos>> neighborsByPos;

    private FakeRailBlockAccess(
        Set<RailBlockPos> rails, Map<RailBlockPos, Set<RailBlockPos>> neighborsByPos) {
      this.rails = Set.copyOf(rails);
      this.neighborsByPos = Map.copyOf(neighborsByPos);
    }

    @Override
    public boolean isRail(RailBlockPos pos) {
      return rails.contains(pos);
    }

    @Override
    public Set<RailBlockPos> neighbors(RailBlockPos pos) {
      return neighborsByPos.getOrDefault(pos, Set.of());
    }
  }
}
