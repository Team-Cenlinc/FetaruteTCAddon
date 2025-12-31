package org.fetarute.fetaruteTCAddon.dispatcher.graph;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.World;
import org.junit.jupiter.api.Test;

final class RailGraphServiceTest {

  @Test
  void clearSnapshotReturnsFalseWhenMissing() {
    RailGraphService service = new RailGraphService(world -> SimpleRailGraph.empty());
    World world = mock(World.class);
    when(world.getUID()).thenReturn(UUID.randomUUID());

    assertFalse(service.clearSnapshot(world));
  }

  @Test
  void clearSnapshotRemovesExistingSnapshot() {
    RailGraphService service = new RailGraphService(world -> SimpleRailGraph.empty());
    World world = mock(World.class);
    when(world.getUID()).thenReturn(UUID.randomUUID());

    service.putSnapshot(world, SimpleRailGraph.empty(), Instant.now());
    Optional<RailGraphService.RailGraphSnapshot> snapshot = service.getSnapshot(world);
    assertTrue(snapshot.isPresent());

    assertTrue(service.clearSnapshot(world));
    assertTrue(service.getSnapshot(world).isEmpty());
    assertFalse(service.clearSnapshot(world));
  }
}
