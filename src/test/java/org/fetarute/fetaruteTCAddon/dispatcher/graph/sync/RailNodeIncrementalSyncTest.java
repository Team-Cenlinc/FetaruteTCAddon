package org.fetarute.fetaruteTCAddon.dispatcher.graph.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphService;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.build.RailGraphSignature;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.persist.RailGraphSnapshotRecord;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.persist.RailNodeRecord;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.repository.RailGraphSnapshotRepository;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.repository.RailNodeRepository;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeDefinition;
import org.fetarute.fetaruteTCAddon.storage.StorageManager;
import org.fetarute.fetaruteTCAddon.storage.api.StorageException;
import org.fetarute.fetaruteTCAddon.storage.api.StorageProvider;
import org.fetarute.fetaruteTCAddon.storage.api.StorageTransaction;
import org.fetarute.fetaruteTCAddon.storage.api.StorageTransactionManager;
import org.fetarute.fetaruteTCAddon.storage.api.TransactionCallback;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

final class RailNodeIncrementalSyncTest {

  private static final class DirectTransactionManager implements StorageTransactionManager {

    @Override
    public StorageTransaction begin() {
      throw new UnsupportedOperationException("begin() 不应在该测试中被调用");
    }

    @Override
    public <T> T execute(TransactionCallback<T> callback) throws StorageException {
      try {
        return callback.doInTransaction();
      } catch (StorageException ex) {
        throw ex;
      } catch (Exception ex) {
        throw new StorageException("事务执行失败", ex);
      }
    }
  }

  @Test
  void upsertMarksGraphStaleWhenSignatureMismatch() {
    UUID worldId = UUID.randomUUID();
    World world = mock(World.class);
    when(world.getUID()).thenReturn(worldId);

    Block block = mockBlock(world, 1, 64, 2);
    SignNodeDefinition definition =
        new SignNodeDefinition(
            NodeId.of("SURN:PTK:GPT:1:00"),
            NodeType.WAYPOINT,
            Optional.of("SURN:PTK:GPT:1:00"),
            Optional.empty());

    RailNodeRecord existingNode =
        new RailNodeRecord(
            worldId,
            definition.nodeId(),
            definition.nodeType(),
            1,
            64,
            2,
            definition.trainCartsDestination(),
            Optional.empty());
    List<RailNodeRecord> nodes = List.of(existingNode);
    String currentSignature = RailGraphSignature.signatureForNodes(nodes);

    RailGraphSnapshotRecord snapshot =
        new RailGraphSnapshotRecord(worldId, Instant.EPOCH, 1, 0, "deadbeef");

    RailNodeRepository nodeRepo = mock(RailNodeRepository.class);
    when(nodeRepo.listByWorld(worldId)).thenReturn(nodes);

    RailGraphSnapshotRepository snapshotRepo = mock(RailGraphSnapshotRepository.class);
    when(snapshotRepo.findByWorld(worldId)).thenReturn(Optional.of(snapshot));

    StorageProvider provider = mock(StorageProvider.class);
    when(provider.railNodes()).thenReturn(nodeRepo);
    when(provider.railGraphSnapshots()).thenReturn(snapshotRepo);
    when(provider.transactionManager()).thenReturn(directTransactionManager());

    StorageManager storageManager = mock(StorageManager.class);
    when(storageManager.isReady()).thenReturn(true);
    when(storageManager.provider()).thenReturn(Optional.of(provider));

    RailGraphService railGraphService = mock(RailGraphService.class);
    RailNodeIncrementalSync sync =
        new RailNodeIncrementalSync(storageManager, railGraphService, null);

    sync.upsert(block, definition);

    verify(nodeRepo).deleteByPosition(worldId, 1, 64, 2);
    ArgumentCaptor<RailNodeRecord> recordCaptor = ArgumentCaptor.forClass(RailNodeRecord.class);
    verify(nodeRepo).upsert(recordCaptor.capture());
    assertEquals(definition.nodeId(), recordCaptor.getValue().nodeId());

    ArgumentCaptor<RailGraphService.RailGraphStaleState> staleCaptor =
        ArgumentCaptor.forClass(RailGraphService.RailGraphStaleState.class);
    verify(railGraphService).markStale(eq(world), staleCaptor.capture());
    RailGraphService.RailGraphStaleState stale = staleCaptor.getValue();
    assertEquals("deadbeef", stale.snapshotSignature());
    assertEquals(currentSignature, stale.currentSignature());
    verify(railGraphService, never()).loadFromStorage(any(), anyList());
  }

  @Test
  void upsertReloadsSnapshotWhenSignatureMatchesAndStalePresent() {
    UUID worldId = UUID.randomUUID();
    World world = mock(World.class);
    when(world.getUID()).thenReturn(worldId);

    Block block = mockBlock(world, 1, 64, 2);
    SignNodeDefinition definition =
        new SignNodeDefinition(
            NodeId.of("SURN:PTK:GPT:1:00"),
            NodeType.WAYPOINT,
            Optional.of("SURN:PTK:GPT:1:00"),
            Optional.empty());

    RailNodeRecord existingNode =
        new RailNodeRecord(
            worldId,
            definition.nodeId(),
            definition.nodeType(),
            1,
            64,
            2,
            definition.trainCartsDestination(),
            Optional.empty());
    List<RailNodeRecord> nodes = List.of(existingNode);
    String currentSignature = RailGraphSignature.signatureForNodes(nodes);

    RailGraphSnapshotRecord snapshot =
        new RailGraphSnapshotRecord(worldId, Instant.EPOCH, 1, 0, currentSignature);

    RailNodeRepository nodeRepo = mock(RailNodeRepository.class);
    when(nodeRepo.listByWorld(worldId)).thenReturn(nodes);

    RailGraphSnapshotRepository snapshotRepo = mock(RailGraphSnapshotRepository.class);
    when(snapshotRepo.findByWorld(worldId)).thenReturn(Optional.of(snapshot));

    StorageProvider provider = mock(StorageProvider.class);
    when(provider.railNodes()).thenReturn(nodeRepo);
    when(provider.railGraphSnapshots()).thenReturn(snapshotRepo);
    when(provider.transactionManager()).thenReturn(directTransactionManager());

    StorageManager storageManager = mock(StorageManager.class);
    when(storageManager.isReady()).thenReturn(true);
    when(storageManager.provider()).thenReturn(Optional.of(provider));

    RailGraphService railGraphService = mock(RailGraphService.class);
    when(railGraphService.getSnapshot(world)).thenReturn(Optional.empty());
    when(railGraphService.getStaleState(world))
        .thenReturn(
            Optional.of(
                new RailGraphService.RailGraphStaleState(Instant.EPOCH, "a", "b", 1, 0, 1)));

    RailNodeIncrementalSync sync =
        new RailNodeIncrementalSync(storageManager, railGraphService, null);

    sync.upsert(block, definition);

    verify(nodeRepo).deleteByPosition(worldId, 1, 64, 2);
    verify(nodeRepo).upsert(any(RailNodeRecord.class));
    verify(railGraphService).loadFromStorage(provider, List.of(world));
    verify(railGraphService, never()).markStale(any(), any());
  }

  @Test
  void deleteMarksGraphStaleWhenSignatureMismatch() {
    UUID worldId = UUID.randomUUID();
    World world = mock(World.class);
    when(world.getUID()).thenReturn(worldId);

    Block block = mockBlock(world, 1, 64, 2);
    SignNodeDefinition definition =
        new SignNodeDefinition(
            NodeId.of("SURN:PTK:GPT:1:00"),
            NodeType.WAYPOINT,
            Optional.of("SURN:PTK:GPT:1:00"),
            Optional.empty());

    RailNodeRecord existingNode =
        new RailNodeRecord(
            worldId,
            definition.nodeId(),
            definition.nodeType(),
            1,
            64,
            2,
            definition.trainCartsDestination(),
            Optional.empty());
    List<RailNodeRecord> nodes = List.of(existingNode);
    String currentSignature = RailGraphSignature.signatureForNodes(nodes);

    RailGraphSnapshotRecord snapshot =
        new RailGraphSnapshotRecord(worldId, Instant.EPOCH, 1, 0, "deadbeef");

    RailNodeRepository nodeRepo = mock(RailNodeRepository.class);
    when(nodeRepo.listByWorld(worldId)).thenReturn(nodes);

    RailGraphSnapshotRepository snapshotRepo = mock(RailGraphSnapshotRepository.class);
    when(snapshotRepo.findByWorld(worldId)).thenReturn(Optional.of(snapshot));

    StorageProvider provider = mock(StorageProvider.class);
    when(provider.railNodes()).thenReturn(nodeRepo);
    when(provider.railGraphSnapshots()).thenReturn(snapshotRepo);
    when(provider.transactionManager()).thenReturn(directTransactionManager());

    StorageManager storageManager = mock(StorageManager.class);
    when(storageManager.isReady()).thenReturn(true);
    when(storageManager.provider()).thenReturn(Optional.of(provider));

    RailGraphService railGraphService = mock(RailGraphService.class);
    RailNodeIncrementalSync sync =
        new RailNodeIncrementalSync(storageManager, railGraphService, null);

    sync.delete(block, definition);

    verify(nodeRepo).delete(worldId, definition.nodeId());
    ArgumentCaptor<RailGraphService.RailGraphStaleState> staleCaptor =
        ArgumentCaptor.forClass(RailGraphService.RailGraphStaleState.class);
    verify(railGraphService).markStale(eq(world), staleCaptor.capture());
    RailGraphService.RailGraphStaleState stale = staleCaptor.getValue();
    assertEquals("deadbeef", stale.snapshotSignature());
    assertEquals(currentSignature, stale.currentSignature());
  }

  @Test
  void noOpWhenStorageNotReady() {
    UUID worldId = UUID.randomUUID();
    World world = mock(World.class);
    when(world.getUID()).thenReturn(worldId);

    Block block = mockBlock(world, 1, 64, 2);
    SignNodeDefinition definition =
        new SignNodeDefinition(
            NodeId.of("SURN:PTK:GPT:1:00"),
            NodeType.WAYPOINT,
            Optional.of("SURN:PTK:GPT:1:00"),
            Optional.empty());

    StorageManager storageManager = mock(StorageManager.class);
    when(storageManager.isReady()).thenReturn(false);
    RailGraphService railGraphService = mock(RailGraphService.class);
    RailNodeIncrementalSync sync =
        new RailNodeIncrementalSync(storageManager, railGraphService, null);

    sync.upsert(block, definition);
    sync.delete(block, definition);

    verify(storageManager, never()).provider();
    verifyNoInteractions(railGraphService);
  }

  private Block mockBlock(World world, int x, int y, int z) {
    Location location = mock(Location.class);
    when(location.getWorld()).thenReturn(world);
    when(location.getBlockX()).thenReturn(x);
    when(location.getBlockY()).thenReturn(y);
    when(location.getBlockZ()).thenReturn(z);

    Block block = mock(Block.class);
    when(block.getWorld()).thenReturn(world);
    when(block.getLocation()).thenReturn(location);
    when(block.getX()).thenReturn(x);
    when(block.getY()).thenReturn(y);
    when(block.getZ()).thenReturn(z);
    return block;
  }

  /** 单节点、单快照的存储桩：库里只有 {@code definition} 这一个节点，快照签名为 {@code snapshotSignature}。 */
  private StorageManager storageWith(
      UUID worldId, SignNodeDefinition definition, String snapshotSignature) {
    RailNodeRecord node =
        new RailNodeRecord(
            worldId,
            definition.nodeId(),
            definition.nodeType(),
            1,
            64,
            2,
            definition.trainCartsDestination(),
            Optional.empty());
    return storageWith(worldId, List.of(node), snapshotSignature);
  }

  private StorageManager storageWith(
      UUID worldId, List<RailNodeRecord> nodes, String snapshotSignature) {
    RailNodeRepository nodeRepo = mock(RailNodeRepository.class);
    when(nodeRepo.listByWorld(worldId)).thenReturn(nodes);
    RailGraphSnapshotRepository snapshotRepo = mock(RailGraphSnapshotRepository.class);
    when(snapshotRepo.findByWorld(worldId))
        .thenReturn(
            Optional.of(
                new RailGraphSnapshotRecord(worldId, Instant.EPOCH, 1, 0, snapshotSignature)));
    StorageProvider provider = mock(StorageProvider.class);
    when(provider.railNodes()).thenReturn(nodeRepo);
    when(provider.railGraphSnapshots()).thenReturn(snapshotRepo);
    when(provider.transactionManager()).thenReturn(directTransactionManager());
    StorageManager storageManager = mock(StorageManager.class);
    when(storageManager.isReady()).thenReturn(true);
    when(storageManager.provider()).thenReturn(Optional.of(provider));
    return storageManager;
  }

  private static SignNodeDefinition waypoint() {
    return new SignNodeDefinition(
        NodeId.of("SURN:PTK:GPT:1:00"),
        NodeType.WAYPOINT,
        Optional.of("SURN:PTK:GPT:1:00"),
        Optional.empty());
  }

  private static RailGraphService.RailGraphStaleState staleState() {
    return new RailGraphService.RailGraphStaleState(Instant.EPOCH, "a", "b", 1, 0, 1);
  }

  @Test
  void usedAdditionRemovesServedGraph() {
    World world = world();
    SignNodeDefinition definition = waypoint();
    RailGraphService service = servedService(world);
    GraphStaleListener listener = mock(GraphStaleListener.class);
    RailNodeIncrementalSync sync =
        new RailNodeIncrementalSync(
            storageWith(world.getUID(), definition, "deadbeef"),
            service,
            null,
            listener,
            (w, def) -> Optional.of("交路 X 第 1 站"));

    sync.upsert(mockBlock(world, 1, 64, 2), definition);

    assertTrue(service.getSnapshot(world).isEmpty());
    verify(listener)
        .onStale(
            world,
            new GraphStaleListener.NodeChange(
                definition, 1, 64, 2, false, Optional.of("交路 X 第 1 站")),
            GraphStaleListener.Level.NONE,
            GraphStaleListener.Level.EVICTED);
    verify(listener, never()).onRecovered(any());
  }

  @Test
  void unusedRemovalKeepsServingTheOldGraph() {
    World world = world();
    SignNodeDefinition definition = waypoint();
    RailGraphService service = servedService(world, definition.nodeId());
    GraphStaleListener listener = mock(GraphStaleListener.class);
    RailNodeIncrementalSync sync =
        new RailNodeIncrementalSync(
            storageWith(world.getUID(), definition, "deadbeef"),
            service,
            null,
            listener,
            (w, def) -> Optional.empty());

    sync.delete(mockBlock(world, 1, 64, 2), definition);

    assertTrue(service.getSnapshot(world).isPresent());
    assertTrue(service.isServingRetainedStaleSnapshot(world.getUID()));
    verify(listener)
        .onStale(
            world,
            new GraphStaleListener.NodeChange(definition, 1, 64, 2, true),
            GraphStaleListener.Level.NONE,
            GraphStaleListener.Level.RETAINED);
  }

  @Test
  void usedChangeOnRetainedWorldEscalatesToEviction() {
    World world = world();
    SignNodeDefinition definition = waypoint();
    RailGraphService service = servedService(world, definition.nodeId());
    GraphStaleListener listener = mock(GraphStaleListener.class);
    java.util.concurrent.atomic.AtomicReference<Optional<String>> usage =
        new java.util.concurrent.atomic.AtomicReference<>(Optional.empty());
    RailNodeIncrementalSync sync =
        new RailNodeIncrementalSync(
            storageWith(world.getUID(), definition, "deadbeef"),
            service,
            null,
            listener,
            (w, def) -> usage.get());
    sync.delete(mockBlock(world, 1, 64, 2), definition);

    usage.set(Optional.of("交路 X 第 2 站"));
    sync.delete(mockBlock(world, 1, 64, 2), definition);

    assertTrue(service.getSnapshot(world).isEmpty());
    verify(listener)
        .onStale(
            eq(world),
            any(),
            eq(GraphStaleListener.Level.RETAINED),
            eq(GraphStaleListener.Level.EVICTED));
  }

  @Test
  void evictedWorldStaysEvictedWithoutConsultingUsage() {
    World world = world();
    SignNodeDefinition definition = waypoint();
    RailGraphService service = servedService(world);
    service.markStale(world, staleState());
    GraphStaleListener listener = mock(GraphStaleListener.class);
    GraphNodeUsage usage = mock(GraphNodeUsage.class);
    RailNodeIncrementalSync sync =
        new RailNodeIncrementalSync(
            storageWith(world.getUID(), definition, "deadbeef"), service, null, listener, usage);

    sync.delete(mockBlock(world, 1, 64, 2), definition);

    verifyNoInteractions(usage);
    assertTrue(service.getSnapshot(world).isEmpty());
    verify(listener)
        .onStale(
            world,
            new GraphStaleListener.NodeChange(definition, 1, 64, 2, true),
            GraphStaleListener.Level.EVICTED,
            GraphStaleListener.Level.EVICTED);
  }

  @Test
  void additionWhoseIdIsAlreadyInTheServedGraphCountsAsUsed() {
    World world = world();
    SignNodeDefinition definition = waypoint();
    RailGraphService service = servedService(world, definition.nodeId());
    GraphStaleListener listener = mock(GraphStaleListener.class);
    RailNodeIncrementalSync sync =
        new RailNodeIncrementalSync(
            storageWith(world.getUID(), List.of(), "deadbeef"),
            service,
            null,
            listener,
            (w, def) -> Optional.empty());

    // 拆掉后换了个地方放回：旧图里这个节点还在原位置。
    sync.upsert(mockBlock(world, 9, 64, 9), definition);

    assertTrue(service.getSnapshot(world).isEmpty());
    verify(listener)
        .onStale(
            world,
            new GraphStaleListener.NodeChange(
                definition, 9, 64, 9, false, Optional.of("旧图里已有同名节点")),
            GraphStaleListener.Level.NONE,
            GraphStaleListener.Level.EVICTED);
  }

  @Test
  void nodeReplacedAtTheSamePositionIsJudgedToo() {
    World world = world();
    SignNodeDefinition replacement =
        new SignNodeDefinition(
            NodeId.of("SURN:PTK:GPT:1:01"), NodeType.WAYPOINT, Optional.empty(), Optional.empty());
    RailNodeRecord old =
        new RailNodeRecord(
            world.getUID(),
            NodeId.of("SURN:PTK:GPT:1:00"),
            NodeType.WAYPOINT,
            1,
            64,
            2,
            Optional.empty(),
            Optional.empty());
    RailGraphService service = servedService(world, old.nodeId());
    GraphStaleListener listener = mock(GraphStaleListener.class);
    RailNodeIncrementalSync sync =
        new RailNodeIncrementalSync(
            storageWith(world.getUID(), List.of(old), "deadbeef"),
            service,
            null,
            listener,
            (w, def) ->
                def.nodeId().equals(old.nodeId())
                    ? Optional.of("交路 X 第 1 个途经点")
                    : Optional.empty());

    sync.upsert(mockBlock(world, 1, 64, 2), replacement);

    assertTrue(service.getSnapshot(world).isEmpty());
    verify(listener)
        .onStale(
            eq(world),
            eq(
                new GraphStaleListener.NodeChange(
                    new SignNodeDefinition(
                        old.nodeId(), NodeType.WAYPOINT, Optional.empty(), Optional.empty()),
                    1,
                    64,
                    2,
                    true,
                    Optional.of("交路 X 第 1 个途经点"))),
            eq(GraphStaleListener.Level.NONE),
            eq(GraphStaleListener.Level.EVICTED));
    verify(listener)
        .onStale(
            eq(world),
            eq(new GraphStaleListener.NodeChange(replacement, 1, 64, 2, false)),
            eq(GraphStaleListener.Level.NONE),
            eq(GraphStaleListener.Level.EVICTED));
  }

  @Test
  void usageFailureCountsAsInUse() {
    World world = world();
    SignNodeDefinition definition = waypoint();
    RailGraphService service = servedService(world, definition.nodeId());
    RailNodeIncrementalSync sync =
        new RailNodeIncrementalSync(
            storageWith(world.getUID(), definition, "deadbeef"),
            service,
            null,
            GraphStaleListener.noop(),
            (w, def) -> {
              throw new IllegalStateException("cache gone");
            });

    sync.delete(mockBlock(world, 1, 64, 2), definition);

    assertTrue(service.getSnapshot(world).isEmpty());
  }

  @Test
  void deleteOnAlreadyStaleWorldReportsNoTransition() {
    UUID worldId = UUID.randomUUID();
    World world = mock(World.class);
    when(world.getUID()).thenReturn(worldId);
    SignNodeDefinition definition = waypoint();
    RailGraphService railGraphService = mock(RailGraphService.class);
    when(railGraphService.getStaleState(world)).thenReturn(Optional.of(staleState()));
    GraphStaleListener listener = mock(GraphStaleListener.class);
    RailNodeIncrementalSync sync =
        new RailNodeIncrementalSync(
            storageWith(worldId, definition, "deadbeef"), railGraphService, null, listener);

    sync.delete(mockBlock(world, 7, 70, -3), definition);

    verify(listener)
        .onStale(
            world,
            new GraphStaleListener.NodeChange(definition, 7, 70, -3, true),
            GraphStaleListener.Level.EVICTED,
            GraphStaleListener.Level.EVICTED);
  }

  @Test
  void recoveryIsReportedOnlyWhenReloadClearsStale() {
    UUID worldId = UUID.randomUUID();
    World world = mock(World.class);
    when(world.getUID()).thenReturn(worldId);
    SignNodeDefinition definition = waypoint();
    String matching =
        RailGraphSignature.signatureForNodes(
            List.of(
                new RailNodeRecord(
                    worldId,
                    definition.nodeId(),
                    definition.nodeType(),
                    1,
                    64,
                    2,
                    definition.trainCartsDestination(),
                    Optional.empty())));
    RailGraphService railGraphService = mock(RailGraphService.class);
    when(railGraphService.getStaleState(world))
        .thenReturn(Optional.of(staleState()), Optional.empty());
    GraphStaleListener listener = mock(GraphStaleListener.class);
    RailNodeIncrementalSync sync =
        new RailNodeIncrementalSync(
            storageWith(worldId, definition, matching), railGraphService, null, listener);

    sync.upsert(mockBlock(world, 1, 64, 2), definition);

    verify(railGraphService).loadFromStorage(any(), eq(List.of(world)));
    verify(listener).onRecovered(world);
    verify(listener, never()).onStale(any(), any(), any(), any());
  }

  @Test
  void recoveryIsNotReportedWhenReloadLeavesGraphStale() {
    UUID worldId = UUID.randomUUID();
    World world = mock(World.class);
    when(world.getUID()).thenReturn(worldId);
    SignNodeDefinition definition = waypoint();
    String matching =
        RailGraphSignature.signatureForNodes(
            List.of(
                new RailNodeRecord(
                    worldId,
                    definition.nodeId(),
                    definition.nodeType(),
                    1,
                    64,
                    2,
                    definition.trainCartsDestination(),
                    Optional.empty())));
    RailGraphService railGraphService = mock(RailGraphService.class);
    when(railGraphService.getStaleState(world)).thenReturn(Optional.of(staleState()));
    GraphStaleListener listener = mock(GraphStaleListener.class);
    RailNodeIncrementalSync sync =
        new RailNodeIncrementalSync(
            storageWith(worldId, definition, matching), railGraphService, null, listener);

    sync.upsert(mockBlock(world, 1, 64, 2), definition);

    verify(railGraphService).loadFromStorage(any(), eq(List.of(world)));
    verify(listener, never()).onRecovered(any());
  }

  @Test
  void deleteOfAlreadyRemovedNodeOnStaleWorldChangesNothing() {
    UUID worldId = UUID.randomUUID();
    World world = mock(World.class);
    when(world.getUID()).thenReturn(worldId);
    SignNodeDefinition definition = waypoint();
    RailGraphService railGraphService = mock(RailGraphService.class);
    when(railGraphService.getStaleState(world)).thenReturn(Optional.of(staleState()));
    GraphStaleListener listener = mock(GraphStaleListener.class);
    RailNodeIncrementalSync sync =
        new RailNodeIncrementalSync(
            storageWith(worldId, List.of(), "deadbeef"), railGraphService, null, listener);

    // 拆牌监听已经删过一次；TC 下一 tick 的 destroy 再删同一个节点。
    sync.delete(mockBlock(world, 1, 64, 2), definition);

    verify(railGraphService, never()).markStale(any(), any());
    verify(listener, never()).onStale(any(), any(), any(), any());
  }

  @Test
  void listenerFailureDoesNotUndoStaleMarking() {
    UUID worldId = UUID.randomUUID();
    World world = mock(World.class);
    when(world.getUID()).thenReturn(worldId);
    SignNodeDefinition definition = waypoint();
    RailGraphService railGraphService = mock(RailGraphService.class);
    GraphStaleListener listener = mock(GraphStaleListener.class);
    doThrow(new IllegalStateException("boom")).when(listener).onStale(any(), any(), any(), any());
    java.util.List<String> debug = new java.util.ArrayList<>();
    RailNodeIncrementalSync sync =
        new RailNodeIncrementalSync(
            storageWith(worldId, definition, "deadbeef"), railGraphService, debug::add, listener);

    sync.delete(mockBlock(world, 1, 64, 2), definition);

    verify(railGraphService).markStale(eq(world), any());
    assertEquals(1, debug.size());
    assertTrue(debug.get(0).startsWith("调度图失效告警失败"), debug.get(0));
  }

  private static World world() {
    World world = mock(World.class);
    when(world.getUID()).thenReturn(UUID.randomUUID());
    when(world.getName()).thenReturn("surn");
    return world;
  }

  /** 真实的图服务，内存里供着一张只有这些节点的图。 */
  private static RailGraphService servedService(World world, NodeId... nodes) {
    java.util.Map<NodeId, org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode> byId =
        new java.util.HashMap<>();
    for (NodeId id : nodes) {
      byId.put(id, node(id));
    }
    org.fetarute.fetaruteTCAddon.dispatcher.graph.SimpleRailGraph graph =
        new org.fetarute.fetaruteTCAddon.dispatcher.graph.SimpleRailGraph(
            byId, java.util.Map.of(), java.util.Set.of());
    RailGraphService service = new RailGraphService(ignored -> graph);
    service.putSnapshot(world, graph, Instant.EPOCH);
    return service;
  }

  private static org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode node(NodeId id) {
    return new org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode() {
      @Override
      public NodeId id() {
        return id;
      }

      @Override
      public NodeType type() {
        return NodeType.WAYPOINT;
      }

      @Override
      public org.bukkit.util.Vector worldPosition() {
        return new org.bukkit.util.Vector(0, 0, 0);
      }

      @Override
      public Optional<String> trainCartsDestination() {
        return Optional.empty();
      }
    };
  }

  private StorageTransactionManager directTransactionManager() {
    return new DirectTransactionManager();
  }
}
