package org.fetarute.fetaruteTCAddon.dispatcher.graph;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.UUID;
import org.bukkit.World;
import org.fetarute.fetaruteTCAddon.company.repository.RailEdgeOverrideRepository;
import org.fetarute.fetaruteTCAddon.company.repository.RailEdgeRepository;
import org.fetarute.fetaruteTCAddon.company.repository.RailGraphSnapshotRepository;
import org.fetarute.fetaruteTCAddon.company.repository.RailNodeRepository;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.build.RailGraphSignature;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.persist.RailEdgeOverrideRecord;
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
    RailEdgeOverrideRepository overrideRepo = mock(RailEdgeOverrideRepository.class);
    when(overrideRepo.listByWorld(worldId)).thenReturn(List.of());
    when(provider.railEdgeOverrides()).thenReturn(overrideRepo);
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
    RailEdgeOverrideRepository overrideRepo = mock(RailEdgeOverrideRepository.class);
    when(overrideRepo.listByWorld(worldId)).thenReturn(List.of());
    when(provider.railEdgeOverrides()).thenReturn(overrideRepo);
    when(provider.railGraphSnapshots()).thenReturn(snapshotRepo);

    service.loadFromStorage(provider, List.of(world));

    assertTrue(service.getStaleState(world).isEmpty());
    assertTrue(service.getSnapshot(world).isPresent());
  }

  @Test
  void loadFromStorageLoadsEdgeOverridesEvenWhenSnapshotMissing() {
    RailGraphService service = new RailGraphService(world -> SimpleRailGraph.empty());
    World world = mock(World.class);
    UUID worldId = UUID.randomUUID();
    when(world.getUID()).thenReturn(worldId);

    EdgeId edgeId = EdgeId.undirected(NodeId.of("A"), NodeId.of("B"));
    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    RailEdgeOverrideRecord override =
        new RailEdgeOverrideRecord(
            worldId,
            edgeId,
            OptionalDouble.of(8.0),
            OptionalDouble.empty(),
            Optional.empty(),
            false,
            Optional.empty(),
            now);

    RailNodeRepository nodeRepo = mock(RailNodeRepository.class);
    RailEdgeRepository edgeRepo = mock(RailEdgeRepository.class);
    RailEdgeOverrideRepository overrideRepo = mock(RailEdgeOverrideRepository.class);
    when(overrideRepo.listByWorld(worldId)).thenReturn(List.of(override));
    RailGraphSnapshotRepository snapshotRepo = mock(RailGraphSnapshotRepository.class);
    when(snapshotRepo.findByWorld(worldId)).thenReturn(Optional.empty());

    StorageProvider provider = mock(StorageProvider.class);
    when(provider.railNodes()).thenReturn(nodeRepo);
    when(provider.railEdges()).thenReturn(edgeRepo);
    when(provider.railEdgeOverrides()).thenReturn(overrideRepo);
    when(provider.railGraphSnapshots()).thenReturn(snapshotRepo);

    service.loadFromStorage(provider, List.of(world));

    assertTrue(service.getSnapshot(world).isEmpty());
    var loaded = service.getEdgeOverride(worldId, edgeId).orElseThrow();
    assertEquals(8.0, loaded.speedLimitBlocksPerSecond().getAsDouble(), 1e-9);
  }

  @Test
  void loadFromStorageDoesNotFailWhenOverrideRepoThrows() {
    RailGraphService service = new RailGraphService(world -> SimpleRailGraph.empty());
    World world = mock(World.class);
    UUID worldId = UUID.randomUUID();
    when(world.getUID()).thenReturn(worldId);

    RailNodeRepository nodeRepo = mock(RailNodeRepository.class);
    RailEdgeRepository edgeRepo = mock(RailEdgeRepository.class);
    RailEdgeOverrideRepository overrideRepo = mock(RailEdgeOverrideRepository.class);
    when(overrideRepo.listByWorld(worldId)).thenThrow(new RuntimeException("boom"));
    RailGraphSnapshotRepository snapshotRepo = mock(RailGraphSnapshotRepository.class);
    when(snapshotRepo.findByWorld(worldId)).thenReturn(Optional.empty());

    StorageProvider provider = mock(StorageProvider.class);
    when(provider.railNodes()).thenReturn(nodeRepo);
    when(provider.railEdges()).thenReturn(edgeRepo);
    when(provider.railEdgeOverrides()).thenReturn(overrideRepo);
    when(provider.railGraphSnapshots()).thenReturn(snapshotRepo);

    assertDoesNotThrow(() -> service.loadFromStorage(provider, List.of(world)));
  }

  @Test
  void effectiveSpeedFallsBackToDefaultWhenEdgeBaseUnset() {
    RailGraphService service = new RailGraphService(world -> SimpleRailGraph.empty());
    UUID worldId = UUID.randomUUID();
    EdgeId id = EdgeId.undirected(NodeId.of("A"), NodeId.of("B"));
    RailEdge edge = new RailEdge(id, id.a(), id.b(), 10, 0.0, true, Optional.empty());

    double effective =
        service.effectiveSpeedLimitBlocksPerSecond(worldId, edge, Instant.EPOCH, 8.0);
    assertEquals(8.0, effective, 1e-9);
  }

  @Test
  void effectiveSpeedPrefersEdgeBaseSpeedWhenPresent() {
    RailGraphService service = new RailGraphService(world -> SimpleRailGraph.empty());
    UUID worldId = UUID.randomUUID();
    EdgeId id = EdgeId.undirected(NodeId.of("A"), NodeId.of("B"));
    RailEdge edge = new RailEdge(id, id.a(), id.b(), 10, 5.0, true, Optional.empty());

    double effective =
        service.effectiveSpeedLimitBlocksPerSecond(worldId, edge, Instant.EPOCH, 8.0);
    assertEquals(5.0, effective, 1e-9);
  }

  @Test
  void effectiveSpeedUsesOverrideWhenPresent() {
    RailGraphService service = new RailGraphService(world -> SimpleRailGraph.empty());
    UUID worldId = UUID.randomUUID();
    EdgeId id = EdgeId.undirected(NodeId.of("A"), NodeId.of("B"));
    RailEdge edge = new RailEdge(id, id.a(), id.b(), 10, 5.0, true, Optional.empty());

    service.putEdgeOverride(
        new RailEdgeOverrideRecord(
            worldId,
            id,
            OptionalDouble.of(12.0),
            OptionalDouble.empty(),
            Optional.empty(),
            false,
            Optional.empty(),
            Instant.EPOCH));

    double effective =
        service.effectiveSpeedLimitBlocksPerSecond(worldId, edge, Instant.EPOCH, 8.0);
    assertEquals(12.0, effective, 1e-9);
  }

  @Test
  void effectiveSpeedAppliesTempRestrictionAsMinimumWhenActive() {
    RailGraphService service = new RailGraphService(world -> SimpleRailGraph.empty());
    UUID worldId = UUID.randomUUID();
    EdgeId id = EdgeId.undirected(NodeId.of("A"), NodeId.of("B"));
    RailEdge edge = new RailEdge(id, id.a(), id.b(), 10, 5.0, true, Optional.empty());

    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    service.putEdgeOverride(
        new RailEdgeOverrideRecord(
            worldId,
            id,
            OptionalDouble.of(10.0),
            OptionalDouble.of(6.0),
            Optional.of(now.plusSeconds(60)),
            false,
            Optional.empty(),
            now));

    assertEquals(6.0, service.effectiveSpeedLimitBlocksPerSecond(worldId, edge, now, 8.0), 1e-9);
    assertEquals(
        10.0,
        service.effectiveSpeedLimitBlocksPerSecond(worldId, edge, now.plusSeconds(120), 8.0),
        1e-9);
  }

  @Test
  void effectiveSpeedNormalizesEdgeIdWhenLookupOverride() {
    RailGraphService service = new RailGraphService(world -> SimpleRailGraph.empty());
    UUID worldId = UUID.randomUUID();
    EdgeId canonical = EdgeId.undirected(NodeId.of("A"), NodeId.of("B"));
    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    service.putEdgeOverride(
        new RailEdgeOverrideRecord(
            worldId,
            canonical,
            OptionalDouble.of(12.0),
            OptionalDouble.empty(),
            Optional.empty(),
            false,
            Optional.empty(),
            now));

    EdgeId reversed = new EdgeId(NodeId.of("B"), NodeId.of("A"));
    RailEdge edge =
        new RailEdge(reversed, reversed.a(), reversed.b(), 10, 0.0, true, Optional.empty());

    double effective = service.effectiveSpeedLimitBlocksPerSecond(worldId, edge, now, 8.0);
    assertEquals(12.0, effective, 1e-9);
  }

  @Test
  void effectiveSpeedFallsBackWhenEdgeIdMissing() {
    RailGraphService service = new RailGraphService(world -> SimpleRailGraph.empty());
    UUID worldId = UUID.randomUUID();
    RailEdge edge =
        new RailEdge(null, NodeId.of("A"), NodeId.of("B"), 10, 0.0, true, Optional.empty());

    assertEquals(
        8.0, service.effectiveSpeedLimitBlocksPerSecond(worldId, edge, Instant.EPOCH, 8.0), 1e-9);
  }

  @Test
  void effectiveSpeedRejectsNonPositiveDefaultSpeed() {
    RailGraphService service = new RailGraphService(world -> SimpleRailGraph.empty());
    UUID worldId = UUID.randomUUID();
    EdgeId id = EdgeId.undirected(NodeId.of("A"), NodeId.of("B"));
    RailEdge edge = new RailEdge(id, id.a(), id.b(), 10, 0.0, true, Optional.empty());

    assertThrows(
        IllegalArgumentException.class,
        () -> service.effectiveSpeedLimitBlocksPerSecond(worldId, edge, Instant.EPOCH, 0.0));
  }
}
