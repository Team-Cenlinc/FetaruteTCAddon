package org.fetarute.fetaruteTCAddon.dispatcher.graph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.World;
import org.fetarute.fetaruteTCAddon.company.repository.RailEdgeRepository;
import org.fetarute.fetaruteTCAddon.company.repository.RailGraphSnapshotRepository;
import org.fetarute.fetaruteTCAddon.company.repository.RailNodeRepository;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.build.RailGraphSignature;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.persist.RailGraphSnapshotRecord;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.persist.RailNodeRecord;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.storage.api.StorageProvider;
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

  @Test
  void loadFromStorageSkipsSnapshotWhenNodeSignatureMismatch() {
    RailGraphService service = new RailGraphService(world -> SimpleRailGraph.empty());
    World world = mock(World.class);
    UUID worldId = UUID.randomUUID();
    when(world.getUID()).thenReturn(worldId);

    RailNodeRecord node =
        new RailNodeRecord(
            worldId,
            NodeId.of("SURN:PTK:GPT:1:00"),
            NodeType.WAYPOINT,
            1,
            64,
            2,
            Optional.of("SURN:PTK:GPT:1:00"),
            Optional.empty());
    List<RailNodeRecord> nodes = List.of(node);
    String currentSignature = RailGraphSignature.signatureForNodes(nodes);
    RailGraphSnapshotRecord snapshot =
        new RailGraphSnapshotRecord(
            worldId, Instant.parse("2026-01-01T00:00:00Z"), 1, 0, "deadbeef");

    RailNodeRepository nodeRepo = mock(RailNodeRepository.class);
    when(nodeRepo.listByWorld(worldId)).thenReturn(nodes);
    RailEdgeRepository edgeRepo = mock(RailEdgeRepository.class);
    when(edgeRepo.listByWorld(worldId)).thenReturn(List.of());
    RailGraphSnapshotRepository snapshotRepo = mock(RailGraphSnapshotRepository.class);
    when(snapshotRepo.findByWorld(worldId)).thenReturn(Optional.of(snapshot));

    StorageProvider provider = mock(StorageProvider.class);
    when(provider.railNodes()).thenReturn(nodeRepo);
    when(provider.railEdges()).thenReturn(edgeRepo);
    when(provider.railGraphSnapshots()).thenReturn(snapshotRepo);

    service.loadFromStorage(provider, List.of(world));

    assertTrue(service.getSnapshot(world).isEmpty());
    var stale = service.getStaleState(world).orElseThrow();
    assertEquals(snapshot.builtAt(), stale.builtAt());
    assertEquals("deadbeef", stale.snapshotSignature());
    assertEquals(currentSignature, stale.currentSignature());
  }

  @Test
  void loadFromStorageLoadsSnapshotWhenNodeSignatureMatches() {
    RailGraphService service = new RailGraphService(world -> SimpleRailGraph.empty());
    World world = mock(World.class);
    UUID worldId = UUID.randomUUID();
    when(world.getUID()).thenReturn(worldId);

    RailNodeRecord node =
        new RailNodeRecord(
            worldId,
            NodeId.of("SURN:PTK:GPT:1:00"),
            NodeType.WAYPOINT,
            1,
            64,
            2,
            Optional.of("SURN:PTK:GPT:1:00"),
            Optional.empty());
    List<RailNodeRecord> nodes = List.of(node);
    String currentSignature = RailGraphSignature.signatureForNodes(nodes);
    RailGraphSnapshotRecord snapshot =
        new RailGraphSnapshotRecord(
            worldId, Instant.parse("2026-01-01T00:00:00Z"), 1, 0, currentSignature);

    RailNodeRepository nodeRepo = mock(RailNodeRepository.class);
    when(nodeRepo.listByWorld(worldId)).thenReturn(nodes);
    RailEdgeRepository edgeRepo = mock(RailEdgeRepository.class);
    when(edgeRepo.listByWorld(worldId)).thenReturn(List.of());
    RailGraphSnapshotRepository snapshotRepo = mock(RailGraphSnapshotRepository.class);
    when(snapshotRepo.findByWorld(worldId)).thenReturn(Optional.of(snapshot));

    StorageProvider provider = mock(StorageProvider.class);
    when(provider.railNodes()).thenReturn(nodeRepo);
    when(provider.railEdges()).thenReturn(edgeRepo);
    when(provider.railGraphSnapshots()).thenReturn(snapshotRepo);

    service.loadFromStorage(provider, List.of(world));

    assertTrue(service.getStaleState(world).isEmpty());
    assertTrue(service.getSnapshot(world).isPresent());
  }
}
