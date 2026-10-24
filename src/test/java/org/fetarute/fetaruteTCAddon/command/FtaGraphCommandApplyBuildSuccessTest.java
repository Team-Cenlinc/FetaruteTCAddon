package org.fetarute.fetaruteTCAddon.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import org.fetarute.fetaruteTCAddon.FetaruteTCAddon;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailEdge;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphInterlockingSupport;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphService;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.SignRailNode;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.SimpleRailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.build.ConnectedRailNodeDiscoverySession;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.build.NodeToNodeEdgeExplorer;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.build.RailGraphBuildCompletion;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.build.RailGraphBuildContinuation;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.build.RailGraphBuildOutcome;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.build.RailGraphBuildResult;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.build.RailGraphBuildTask;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.build.RailGraphSignature;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.interlocking.RailEdgeFootprint;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.interlocking.RailFootprintCell;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.interlocking.RailInterlockingEdgeSignature;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.interlocking.RailInterlockingState;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.persist.RailEdgeRecord;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.persist.RailInterlockingSnapshotRecord;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.persist.RailNodeRecord;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.repository.RailEdgeRepository;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.repository.RailGraphSnapshotRepository;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.repository.RailInterlockingSnapshotRepository;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.repository.RailNodeRepository;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeRegistry;
import org.fetarute.fetaruteTCAddon.storage.StorageManager;
import org.fetarute.fetaruteTCAddon.storage.api.StorageException;
import org.fetarute.fetaruteTCAddon.storage.api.StorageProvider;
import org.fetarute.fetaruteTCAddon.storage.api.StorageTransactionManager;
import org.fetarute.fetaruteTCAddon.storage.api.TransactionCallback;
import org.fetarute.fetaruteTCAddon.utils.LocaleManager;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

final class FtaGraphCommandApplyBuildSuccessTest {
  @org.junit.jupiter.api.io.TempDir java.nio.file.Path pluginFolder;

  /** 占用阻止发布时保留完整候选：基线在主线程取，整图序列化与写盘在后台线程；占用释放后的重试才写 SQL 和内存。 */
  @Test
  void activeClaimsKeepOldGraphAndPersistCandidateForRetry() throws Exception {
    UUID worldId = UUID.randomUUID();
    World world = mock(World.class);
    when(world.getUID()).thenReturn(worldId);
    var nodes = List.of(nodeRecord(worldId, "A", 1), nodeRecord(worldId, "B", 2));
    EdgeId edgeId = EdgeId.undirected(NodeId.of("A"), NodeId.of("B"));
    RailGraph old =
        graph(
            Set.of(node("A", 1), node("B", 2)),
            Set.of(edge("A", "B", 12)),
            RailInterlockingState.incomplete(worldId, Set.of(edgeId)));
    RailGraph next =
        graph(
            worldId,
            Set.of(node("A", 1), node("B", 2)),
            Set.of(edge("A", "B", 12)),
            Map.of(
                edgeId, new RailEdgeFootprint(1, true, Set.of(new RailFootprintCell(1, 64, 1)))));
    RailGraphService service = new RailGraphService();
    Instant baseTime = Instant.parse("2026-10-12T00:00:00Z");
    service.putSnapshot(world, old, baseTime);
    service.setSnapshotActivationGuard(() -> false);
    var provider = mock(StorageProvider.class);
    var nodeRepo = mock(RailNodeRepository.class);
    when(provider.railNodes()).thenReturn(nodeRepo);
    when(nodeRepo.listByWorld(worldId)).thenReturn(nodes);
    var edgeRepo = mock(RailEdgeRepository.class);
    when(provider.railEdges()).thenReturn(edgeRepo);
    when(provider.railGraphSnapshots()).thenReturn(mock(RailGraphSnapshotRepository.class));
    when(provider.railInterlockingSnapshots())
        .thenReturn(mock(RailInterlockingSnapshotRepository.class));
    when(provider.transactionManager()).thenReturn(new InlineTransactionManager());
    var storage = mock(StorageManager.class);
    when(storage.isReady()).thenReturn(true);
    when(storage.provider()).thenReturn(Optional.of(provider));
    var plugin = mock(FetaruteTCAddon.class);
    when(plugin.getDataFolder()).thenReturn(pluginFolder.toFile());
    when(plugin.getRailGraphService()).thenReturn(service);
    when(plugin.getStorageManager()).thenReturn(storage);
    when(plugin.getLogger()).thenReturn(Logger.getLogger("test"));
    Scheduled scheduled = scheduled(plugin);
    var sender = mock(CommandSender.class);
    var locale = mock(LocaleManager.class);
    var command = new FtaGraphCommand(plugin);
    var result =
        new RailGraphBuildResult(
            next,
            baseTime.plusSeconds(60),
            RailGraphSignature.signatureForNodes(nodes),
            nodes,
            List.of(),
            List.of());
    assertThrows(
        IllegalStateException.class,
        () -> command.activatePersistedGraph(sender, world, result, locale));
    assertSame(old, service.getSnapshot(world).orElseThrow().graph());
    org.mockito.Mockito.verify(nodeRepo, org.mockito.Mockito.never()).replaceWorld(any(), any());
    var store =
        new org.fetarute.fetaruteTCAddon.dispatcher.graph.persist.PendingRailGraphStore(
            pluginFolder.resolve("pending-graphs"));
    assertTrue(store.load(worldId).isEmpty(), "整图写盘交给后台线程，被拦下的这一拍不写");
    // 基线已在主线程取好：后台写盘时节点表再变，也不能让候选认新基线。
    when(nodeRepo.listByWorld(worldId)).thenReturn(List.of());
    scheduled.runAll();
    when(nodeRepo.listByWorld(worldId)).thenReturn(nodes);
    verify(locale).component("command.graph.pending.stored");
    var candidate = store.load(worldId).orElseThrow();
    assertTrue(
        candidate.matchesBase(Optional.of(baseTime), RailGraphSignature.signatureForNodes(nodes)));
    // 候选带回逐边足迹：激活写库时不能把库里的足迹清空。
    assertTrue(
        ((RailGraphInterlockingSupport) candidate.result().graph())
            .interlockingState()
            .cellCoverageAvailable());
    assertTrue(
        ((RailGraphInterlockingSupport) next)
            .interlockingState()
            .sameResourceProjection(
                ((RailGraphInterlockingSupport) candidate.result().graph()).interlockingState()));
    service.setSnapshotActivationGuard(() -> false);
    command.activatePendingGraph(sender, world, locale);
    verify(locale).component("command.graph.pending.still-blocked");
    assertTrue(scheduled.isEmpty(), "重试仍被拦下时候选原样保留，不再重写");
    assertSame(old, service.getSnapshot(world).orElseThrow().graph());
    service.setSnapshotActivationGuard(() -> true);
    command.activatePendingGraph(sender, world, locale);
    verify(locale).component("command.graph.pending.activated");
    assertEquals(result.builtAt(), service.getSnapshot(world).orElseThrow().builtAt());
    verify(nodeRepo).replaceWorld(eq(worldId), eq(nodes));
    verify(edgeRepo)
        .replaceWorld(
            eq(worldId),
            org.mockito.ArgumentMatchers.argThat(
                edges ->
                    edges.stream()
                        .anyMatch(
                            record ->
                                record
                                    .footprintCells()
                                    .contains(new RailFootprintCell(1, 64, 1)))));
    assertTrue(store.load(worldId).isEmpty());
  }

  /** 存储未就绪时取不到基线：如实提示存储问题，不说成被占用拦下。 */
  @Test
  void activatingPendingGraphWithoutStorageReportsStorageNotReady() throws Exception {
    UUID worldId = UUID.randomUUID();
    World world = mock(World.class);
    when(world.getUID()).thenReturn(worldId);
    var nodes = List.of(nodeRecord(worldId, "A", 1), nodeRecord(worldId, "B", 2));
    RailGraph graph =
        graph(
            worldId,
            Set.of(node("A", 1), node("B", 2)),
            Set.of(edge("A", "B", 12)),
            Map.of(
                EdgeId.undirected(NodeId.of("A"), NodeId.of("B")),
                new RailEdgeFootprint(1, true, Set.of(new RailFootprintCell(1, 64, 1)))));
    new org.fetarute.fetaruteTCAddon.dispatcher.graph.persist.PendingRailGraphStore(
            pluginFolder.resolve("pending-graphs"))
        .save(
            worldId,
            new org.fetarute
                .fetaruteTCAddon
                .dispatcher
                .graph
                .persist
                .PendingRailGraphStore
                .Candidate(
                new RailGraphBuildResult(
                    graph,
                    Instant.EPOCH,
                    RailGraphSignature.signatureForNodes(nodes),
                    nodes,
                    List.of(),
                    List.of()),
                Optional.empty(),
                ""));
    var storage = mock(StorageManager.class);
    when(storage.isReady()).thenReturn(false);
    var plugin = mock(FetaruteTCAddon.class);
    when(plugin.getDataFolder()).thenReturn(pluginFolder.toFile());
    when(plugin.getRailGraphService()).thenReturn(new RailGraphService());
    when(plugin.getStorageManager()).thenReturn(storage);
    when(plugin.getLogger()).thenReturn(Logger.getLogger("test"));
    var locale = mock(LocaleManager.class);

    new FtaGraphCommand(plugin).activatePendingGraph(mock(CommandSender.class), world, locale);

    verify(locale).component("command.graph.build.storage-not-ready");
    verify(locale, org.mockito.Mockito.never()).component("command.graph.build.activation-blocked");
    verify(locale, org.mockito.Mockito.never()).component("command.graph.pending.still-blocked");
  }

  @Test
  void refreshWithAllAnchorsMissingCannotRetainCompleteInterlockingCoverage() {
    UUID worldId = UUID.randomUUID();
    EdgeId edgeId = EdgeId.undirected(NodeId.of("A"), NodeId.of("B"));
    RailEdgeFootprint footprint =
        new RailEdgeFootprint(1, true, Set.of(new RailFootprintCell(4, 64, 8)));
    RailGraph existingGraph =
        graph(
            worldId,
            Set.of(node("A", 1), node("B", 2)),
            Set.of(edge("A", "B", 12)),
            Map.of(edgeId, footprint));
    RailGraph mergedWithEmptyRefresh =
        org.fetarute
            .fetaruteTCAddon
            .dispatcher
            .graph
            .RailGraphMerger
            .upsert(existingGraph, SimpleRailGraph.empty())
            .graph();

    RailGraph result =
        FtaGraphCommand.markRefreshInterlockingCatalogIncomplete(mergedWithEmptyRefresh);

    RailEdge retained = result.edges().stream().findFirst().orElseThrow();
    assertEquals(edgeId, retained.id());
    assertEquals(12, retained.lengthBlocks());
    assertIncompleteInterlocking(result, worldId, Set.of(edgeId));
  }

  @Test
  void refreshWithResolvedAnchorsStillCannotPublishCompleteInterlockingCoverage() {
    UUID worldId = UUID.randomUUID();
    EdgeId edgeId = EdgeId.undirected(NodeId.of("A"), NodeId.of("B"));
    RailEdgeFootprint footprint =
        new RailEdgeFootprint(1, true, Set.of(new RailFootprintCell(4, 64, 8)));
    RailGraph graph =
        graph(
            worldId,
            Set.of(node("A", 1), node("B", 2)),
            Set.of(edge("A", "B", 12)),
            Map.of(edgeId, footprint));

    RailGraph result = FtaGraphCommand.markRefreshInterlockingCatalogIncomplete(graph);

    RailEdge retained = result.edges().stream().findFirst().orElseThrow();
    assertEquals(edgeId, retained.id());
    assertEquals(12, retained.lengthBlocks());
    assertIncompleteInterlocking(result, worldId, Set.of(edgeId));
  }

  @Test
  void appliesBuildUsingStoredGraphWhenNoInMemorySnapshot() throws Exception {
    UUID worldId = UUID.randomUUID();
    World world = mock(World.class);
    when(world.getUID()).thenReturn(worldId);
    when(world.getName()).thenReturn("world");

    RailGraphService railGraphService = new RailGraphService();

    StorageManager storageManager = mock(StorageManager.class);
    when(storageManager.isReady()).thenReturn(true);

    RailNodeRepository nodeRepo = mock(RailNodeRepository.class);
    RailEdgeRepository edgeRepo = mock(RailEdgeRepository.class);
    RailGraphSnapshotRepository snapshotRepo = mock(RailGraphSnapshotRepository.class);
    RailInterlockingSnapshotRepository interlockingSnapshotRepo =
        mock(RailInterlockingSnapshotRepository.class);

    List<RailNodeRecord> baseNodes =
        List.of(nodeRecord(worldId, "A", 1), nodeRecord(worldId, "B", 2));
    List<RailEdgeRecord> baseEdges = List.of();
    when(nodeRepo.listByWorld(worldId)).thenReturn(baseNodes);
    when(edgeRepo.listByWorld(worldId)).thenReturn(baseEdges);
    when(interlockingSnapshotRepo.findByWorld(worldId)).thenReturn(Optional.empty());
    when(snapshotRepo.save(org.mockito.ArgumentMatchers.any()))
        .thenAnswer(inv -> inv.getArgument(0));
    when(interlockingSnapshotRepo.save(org.mockito.ArgumentMatchers.any()))
        .thenAnswer(inv -> inv.getArgument(0));

    StorageTransactionManager txManager = new InlineTransactionManager();
    StorageProvider provider = mock(StorageProvider.class);
    when(provider.railNodes()).thenReturn(nodeRepo);
    when(provider.railEdges()).thenReturn(edgeRepo);
    when(provider.railGraphSnapshots()).thenReturn(snapshotRepo);
    when(provider.railInterlockingSnapshots()).thenReturn(interlockingSnapshotRepo);
    when(provider.transactionManager()).thenReturn(txManager);
    when(storageManager.provider()).thenReturn(Optional.of(provider));

    SignNodeRegistry registry = new SignNodeRegistry();
    FetaruteTCAddon plugin = mock(FetaruteTCAddon.class);
    when(plugin.getDataFolder()).thenReturn(pluginFolder.toFile());
    when(plugin.getRailGraphService()).thenReturn(railGraphService);
    when(plugin.getSignNodeRegistry()).thenReturn(registry);
    when(plugin.getStorageManager()).thenReturn(storageManager);
    when(plugin.getLogger()).thenReturn(Logger.getLogger("test"));
    Server server = mock(Server.class);
    BukkitScheduler scheduler = mock(BukkitScheduler.class);
    when(plugin.getServer()).thenReturn(server);
    when(server.getScheduler()).thenReturn(scheduler);
    when(scheduler.runTaskAsynchronously(any(), any(Runnable.class)))
        .thenReturn(mock(BukkitTask.class));

    FtaGraphCommand command = new FtaGraphCommand(plugin);

    EdgeId firstEdge = EdgeId.undirected(NodeId.of("A"), NodeId.of("X"));
    EdgeId secondEdge = EdgeId.undirected(NodeId.of("B"), NodeId.of("Y"));
    RailFootprintCell crossingCell = new RailFootprintCell(11, 64, 0);
    RailEdgeFootprint firstFootprint = new RailEdgeFootprint(1, true, Set.of(crossingCell));
    RailEdgeFootprint secondFootprint = new RailEdgeFootprint(1, true, Set.of(crossingCell));

    RailGraph updateGraph =
        graph(
            worldId,
            Set.of(node("A", 1), node("B", 2), node("X", 10), node("Y", 11)),
            Set.of(edge("A", "X", 2), edge("B", "Y", 3)),
            Map.of(firstEdge, firstFootprint, secondEdge, secondFootprint));
    List<RailNodeRecord> updateNodes =
        List.of(
            nodeRecord(worldId, "A", 1),
            nodeRecord(worldId, "B", 2),
            nodeRecord(worldId, "X", 10),
            nodeRecord(worldId, "Y", 11));
    RailGraphBuildResult update =
        new RailGraphBuildResult(
            updateGraph,
            Instant.parse("2026-01-03T00:00:00Z"),
            RailGraphSignature.signatureForNodes(updateNodes),
            updateNodes,
            List.of(),
            List.of());

    command.applyBuildSuccess(
        mock(CommandSender.class),
        world,
        update,
        RailGraphBuildCompletion.COMPLETE,
        mock(LocaleManager.class));

    RailGraph merged = railGraphService.getSnapshot(world).orElseThrow().graph();
    assertEquals(4, merged.nodes().size());
    assertEquals(2, merged.edges().size());
    assertTrue(merged.findNode(NodeId.of("A")).isPresent());
    assertTrue(merged.findNode(NodeId.of("B")).isPresent());
    assertTrue(merged.findNode(NodeId.of("X")).isPresent());
    assertTrue(merged.findNode(NodeId.of("Y")).isPresent());
    assertTrue(merged.edges().stream().anyMatch(edge -> edge.id().equals(firstEdge)));
    assertTrue(merged.edges().stream().anyMatch(edge -> edge.id().equals(secondEdge)));

    assertEquals(4, registry.snapshotInfos().size());
    assertEquals(1, registry.findByNodeId(NodeId.of("A"), null).orElseThrow().x());
    assertEquals(2, registry.findByNodeId(NodeId.of("B"), null).orElseThrow().x());
    assertEquals(10, registry.findByNodeId(NodeId.of("X"), null).orElseThrow().x());
    assertEquals(11, registry.findByNodeId(NodeId.of("Y"), null).orElseThrow().x());

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Collection<RailEdgeRecord>> persistedEdges =
        ArgumentCaptor.forClass(Collection.class);
    verify(edgeRepo).replaceWorld(eq(worldId), persistedEdges.capture());
    // 拓扑照旧逐字段钉住。
    assertEquals(
        Set.of(firstEdge, secondEdge),
        persistedEdges.getValue().stream()
            .map(RailEdgeRecord::edgeId)
            .collect(java.util.stream.Collectors.toSet()));
    assertEquals(
        Set.of(2, 3),
        persistedEdges.getValue().stream()
            .map(RailEdgeRecord::lengthBlocks)
            .collect(java.util.stream.Collectors.toSet()));

    // 而逐边足迹**必须跟着一起落库**——这是本次改动的要害。
    // 不落库的话，足迹只在完整图构建时存在，从快照恢复时 cell→edge 索引必然为空、
    // cellCoverageAvailable() 为假，一切以实测覆盖为放行条件的机制全部 fail-closed 到一个都不放。
    // 实服第十二轮实测 cellCoverageAvailable=false，而尾部保护占全网滞留的 38%。
    assertTrue(
        persistedEdges.getValue().stream().anyMatch(record -> !record.footprintCells().isEmpty()),
        () -> "逐边足迹必须落库，否则 Phase 4 的地基永远是空的：" + persistedEdges.getValue());

    ArgumentCaptor<RailInterlockingSnapshotRecord> persistedInterlocking =
        ArgumentCaptor.forClass(RailInterlockingSnapshotRecord.class);
    verify(interlockingSnapshotRepo).save(persistedInterlocking.capture());
    RailInterlockingSnapshotRecord sparseSnapshot = persistedInterlocking.getValue();
    assertEquals(worldId, sparseSnapshot.worldId());
    assertEquals(
        RailInterlockingEdgeSignature.of(Set.of(firstEdge, secondEdge)),
        sparseSnapshot.edgeSignature());
    assertTrue(sparseSnapshot.coverage().complete());
    assertEquals(2, sparseSnapshot.coverage().inputEdgeCount());
    assertEquals(2, sparseSnapshot.coverage().participatingEdgeCount());
    assertEquals(1, sparseSnapshot.zones().size());
    assertEquals(
        Set.of(crossingCell), sparseSnapshot.zones().values().iterator().next().overlapCells());
  }

  @Test
  void persistenceFailureKeepsPreviousInMemoryInterlockingProjection() throws Exception {
    UUID worldId = UUID.randomUUID();
    World world = mock(World.class);
    when(world.getUID()).thenReturn(worldId);
    when(world.getName()).thenReturn("world");
    RailEdgeFootprint oldFootprint =
        new RailEdgeFootprint(1, true, Set.of(new RailFootprintCell(1, 64, 1)));
    EdgeId edgeId = EdgeId.undirected(NodeId.of("A"), NodeId.of("B"));
    RailGraph oldGraph =
        graph(
            worldId,
            Set.of(node("A", 1), node("B", 2)),
            Set.of(edge("A", "B", 12)),
            Map.of(edgeId, oldFootprint));
    RailGraphService railGraphService = new RailGraphService();
    railGraphService.putSnapshot(world, oldGraph, Instant.parse("2026-01-01T00:00:00Z"));

    StorageManager storageManager = mock(StorageManager.class);
    when(storageManager.isReady()).thenReturn(true);
    StorageProvider provider = mock(StorageProvider.class);
    when(provider.transactionManager()).thenReturn(new FailingTransactionManager());
    when(storageManager.provider()).thenReturn(Optional.of(provider));
    FetaruteTCAddon plugin = mock(FetaruteTCAddon.class);
    when(plugin.getDataFolder()).thenReturn(pluginFolder.toFile());
    when(plugin.getRailGraphService()).thenReturn(railGraphService);
    when(plugin.getStorageManager()).thenReturn(storageManager);
    when(plugin.getLogger()).thenReturn(Logger.getLogger("test"));
    FtaGraphCommand command = new FtaGraphCommand(plugin);

    RailGraph changedGraph =
        graph(
            Set.of(node("A", 1), node("B", 2)),
            Set.of(edge("A", "B", 12)),
            RailInterlockingState.incomplete(worldId, Set.of(edgeId)));
    List<RailNodeRecord> nodes = List.of(nodeRecord(worldId, "A", 1), nodeRecord(worldId, "B", 2));
    RailGraphBuildResult update =
        new RailGraphBuildResult(
            changedGraph,
            Instant.parse("2026-01-02T00:00:00Z"),
            RailGraphSignature.signatureForNodes(nodes),
            nodes,
            List.of(),
            List.of());
    assertThrows(
        IllegalStateException.class,
        () ->
            command.applyBuildSuccess(
                mock(CommandSender.class),
                world,
                update,
                RailGraphBuildCompletion.COMPLETE,
                mock(LocaleManager.class)));

    assertSame(oldGraph, railGraphService.getSnapshot(world).orElseThrow().graph());
  }

  /** 捕获交给调度器的任务，由测试决定何时执行（后台线程与回主线程的任务都按提交顺序跑）。 */
  @Test
  void buildFinishedMergesOffMainThreadAndSwitchesOnlyOnTheMainThreadStep() throws Exception {
    Harness harness = harness();
    RailGraphBuildTask task = harness.registerJob();

    harness.finish(harness.updateAddingX(Optional.empty()));

    assertSame(harness.base(), harness.current(), "交出去之后还没切换");
    assertSame(task, harness.jobs().get(harness.worldId()), "后台合并期间仍占着本世界的建图任务");
    assertEquals(1, harness.asyncTasks().size());
    harness.asyncTasks().get(0).run();
    assertSame(harness.base(), harness.current(), "后台算完也不切换，等主线程核验");
    assertEquals(1, harness.mainTasks().size());

    harness.mainTasks().get(0).run();

    RailGraph merged = harness.current();
    assertTrue(merged.findNode(NodeId.of("B")).isPresent(), "与旧图合并");
    assertTrue(merged.findNode(NodeId.of("X")).isPresent());
    assertFalse(harness.jobs().containsKey(harness.worldId()));
  }

  @Test
  void buildFinishedRemergesInTheBackgroundWhenTheGraphChangedMeanwhile() throws Exception {
    Harness harness = harness();
    harness.registerJob();

    harness.finish(harness.updateAddingX(Optional.empty()));
    harness.asyncTasks().get(0).run();
    RailGraph replaced = harness.lineWithC();
    harness.service().putSnapshot(harness.world(), replaced, Instant.now());
    harness.mainTasks().get(0).run();

    assertSame(replaced, harness.current(), "旧图上算的结果不拿来切换");
    assertEquals(2, harness.asyncTasks().size(), "按当前图再交一次后台");
    harness.asyncTasks().get(1).run();
    harness.mainTasks().get(1).run();

    RailGraph merged = harness.current();
    assertTrue(merged.findNode(NodeId.of("C")).isPresent(), "按期间换上的图重新合并");
    assertTrue(merged.findNode(NodeId.of("X")).isPresent());
    assertFalse(harness.jobs().containsKey(harness.worldId()));
  }

  @Test
  void buildFinishedDoesNotSwitchWhenCancelledMeanwhile() throws Exception {
    Harness harness = harness();
    harness.registerJob();

    harness.finish(harness.updateAddingX(Optional.empty()));
    harness.asyncTasks().get(0).run();
    harness.jobs().remove(harness.worldId());
    RailGraphBuildTask next = mock(RailGraphBuildTask.class);
    harness.jobs().put(harness.worldId(), next);
    harness.mainTasks().get(0).run();

    assertSame(harness.base(), harness.current());
    assertSame(next, harness.jobs().get(harness.worldId()), "不能顺手把取消后新开的任务移出");
  }

  @Test
  void buildFinishedSwitchesSynchronouslyWhenTheBackgroundIsUnavailable() throws Exception {
    Harness harness = harness();
    harness.rejectAsyncCall(1);
    harness.registerJob();

    harness.finish(harness.updateAddingX(Optional.empty()));

    assertTrue(harness.current().findNode(NodeId.of("X")).isPresent());
    assertTrue(harness.current().findNode(NodeId.of("B")).isPresent());
    assertFalse(harness.jobs().containsKey(harness.worldId()));
  }

  @Test
  void backgroundErrorStillReleasesTheJobAndReportsTheFailure() throws Exception {
    Harness harness = harness();
    harness.registerJob();

    harness.finish(harness.updateThatOverflows(Optional.empty()));
    harness.asyncTasks().get(0).run();
    assertTrue(harness.jobs().containsKey(harness.worldId()), "回到主线程之前仍占着");
    harness.mainTasks().get(0).run();

    assertSame(harness.base(), harness.current());
    assertFalse(harness.jobs().containsKey(harness.worldId()), "栈溢出这类 Error 也解锁");
    verify(harness.locale()).component(eq("command.graph.build.failed"), anyMap());
  }

  @Test
  void cancelledBuildIgnoresALaterBackgroundFailure() throws Exception {
    Harness harness = harness();
    harness.registerJob();
    ConnectedRailNodeDiscoverySession previous = mock(ConnectedRailNodeDiscoverySession.class);
    harness.continuations().put(harness.cacheKey(), continuation(previous));
    ConnectedRailNodeDiscoverySession paused = mock(ConnectedRailNodeDiscoverySession.class);

    harness.finish(harness.updateThatOverflows(Optional.of(continuation(paused))));
    harness.asyncTasks().get(0).run();
    harness.jobs().remove(harness.worldId());
    harness.mainTasks().get(0).run();

    verify(harness.locale(), never()).component(eq("command.graph.build.failed"), anyMap());
    verify(paused).releaseChunkTickets();
    verify(previous, never()).releaseChunkTickets();
    assertSame(previous, harness.continuations().get(harness.cacheKey()).discoverySession());
  }

  @Test
  void failureAfterTheSwitchKeepsTheContinuation() throws Exception {
    Harness harness = harness();
    // 第 2 次排后台是切换后的站点自愈：让它抛出，模拟切换之后才出的错。
    harness.rejectAsyncCall(2);
    harness.registerJob();
    ConnectedRailNodeDiscoverySession previous = mock(ConnectedRailNodeDiscoverySession.class);
    harness.continuations().put(harness.cacheKey(), continuation(previous));
    ConnectedRailNodeDiscoverySession paused = mock(ConnectedRailNodeDiscoverySession.class);

    harness.finish(harness.updateAddingX(Optional.of(continuation(paused))));
    harness.asyncTasks().get(0).run();
    harness.mainTasks().get(0).run();

    assertTrue(harness.current().findNode(NodeId.of("X")).isPresent(), "新图已经切上去");
    verify(harness.locale()).component(eq("command.graph.build.failed"), anyMap());
    verify(previous, never()).releaseChunkTickets();
    verify(paused, never()).releaseChunkTickets();
    assertSame(previous, harness.continuations().get(harness.cacheKey()).discoverySession());
    assertFalse(harness.jobs().containsKey(harness.worldId()));
  }

  @Test
  void refreshMergesOffMainThreadAndSwitchesOnTheMainThreadStep() throws Exception {
    Harness harness = harness();
    RailGraphBuildTask task = harness.registerJob();
    List<RailNodeRecord> nodes = harness.refreshNodes();
    harness.storedNodes(nodes);

    harness.refresh(nodes, task);

    assertSame(harness.base(), harness.current());
    harness.asyncTasks().get(0).run();
    assertSame(harness.base(), harness.current());
    harness.mainTasks().get(0).run();

    RailGraph refreshed = harness.current();
    assertTrue(refreshed.findNode(NodeId.of("X")).isPresent());
    assertTrue(
        refreshed.findEdge(EdgeId.undirected(NodeId.of("A"), NodeId.of("B"))).isPresent(),
        "沿用仍在节点表里的旧区间");
    assertFalse(harness.jobs().containsKey(harness.worldId()));
  }

  @Test
  void refreshDoesNotOverwriteNodesChangedDuringTheBackgroundStep() throws Exception {
    Harness harness = harness();
    RailGraphBuildTask task = harness.registerJob();
    List<RailNodeRecord> nodes = harness.refreshNodes();
    harness.storedNodes(nodes);

    harness.refresh(nodes, task);
    harness.asyncTasks().get(0).run();
    // 后台合并期间又放下了一块节点牌子，库里的节点表变了。
    List<RailNodeRecord> changed = new java.util.ArrayList<>(nodes);
    changed.add(nodeRecord(harness.worldId(), "Y", 20));
    harness.storedNodes(changed);
    harness.mainTasks().get(0).run();

    assertSame(harness.base(), harness.current(), "不拿开始时的节点表写库、切换");
    verify(harness.edgeRepo(), never()).replaceWorld(any(), any());
    verify(harness.locale()).component("command.graph.refresh.nodes-changed");
    assertFalse(harness.jobs().containsKey(harness.worldId()));
  }

  @Test
  void refreshRemergesInTheBackgroundWhenTheGraphChangedMeanwhile() throws Exception {
    Harness harness = harness();
    RailGraphBuildTask task = harness.registerJob();
    List<RailNodeRecord> nodes = harness.refreshNodes();
    harness.storedNodes(nodes);

    harness.refresh(nodes, task);
    harness.asyncTasks().get(0).run();
    RailGraph shorter = harness.lineAb(7);
    harness.service().putSnapshot(harness.world(), shorter, Instant.now());
    harness.mainTasks().get(0).run();

    assertSame(shorter, harness.current());
    assertEquals(2, harness.asyncTasks().size(), "按当前图再交一次后台");
    harness.asyncTasks().get(1).run();
    harness.mainTasks().get(1).run();

    RailEdge ab =
        harness.current().findEdge(EdgeId.undirected(NodeId.of("A"), NodeId.of("B"))).orElseThrow();
    assertEquals(7, ab.lengthBlocks(), "按期间换上的图重新合并");
    assertTrue(harness.current().findNode(NodeId.of("X")).isPresent());
  }

  @Test
  void deleteHereHoldsTheWorldWhileItComputesAndSwitchesOnTheMainThreadStep() throws Exception {
    Harness harness = harness();
    harness.service().putSnapshot(harness.world(), harness.twoComponents(), Instant.now());

    harness.deleteHere(NodeId.of("C"));

    assertTrue(
        harness.jobs().containsKey(harness.worldId()), "后台计算期间占着本世界：build、continue、整世界删图都要等");
    assertTrue(harness.current().findNode(NodeId.of("C")).isPresent());
    harness.asyncTasks().get(0).run();
    harness.mainTasks().get(0).run();

    assertTrue(harness.current().findNode(NodeId.of("C")).isEmpty());
    assertTrue(harness.current().findNode(NodeId.of("A")).isPresent());
    assertFalse(harness.jobs().containsKey(harness.worldId()));
  }

  @Test
  void deleteHereOfTheLastComponentClearsTheGraph() throws Exception {
    Harness harness = harness();

    harness.deleteHere(NodeId.of("A"));
    harness.asyncTasks().get(0).run();
    harness.mainTasks().get(0).run();

    assertTrue(harness.service().getSnapshot(harness.worldId()).isEmpty());
    verify(harness.edgeRepo()).deleteWorld(harness.worldId());
    assertFalse(harness.jobs().containsKey(harness.worldId()));
  }

  @Test
  void deleteHereReportsTheSeedGoneWhenTheGraphChangedMeanwhile() throws Exception {
    Harness harness = harness();
    harness.service().putSnapshot(harness.world(), harness.twoComponents(), Instant.now());

    harness.deleteHere(NodeId.of("C"));
    harness.asyncTasks().get(0).run();
    harness.service().putSnapshot(harness.world(), harness.base(), Instant.now());
    harness.mainTasks().get(0).run();

    assertSame(harness.base(), harness.current());
    assertEquals(1, harness.asyncTasks().size(), "种子已不在图里，不再交后台");
    verify(harness.locale()).component("command.graph.delete.here.no-seed");
    assertFalse(harness.jobs().containsKey(harness.worldId()));
  }

  private static RailGraphBuildContinuation continuation(
      ConnectedRailNodeDiscoverySession session) {
    return new RailGraphBuildContinuation(Instant.EPOCH, session, List.of());
  }

  /**
   * 已激活旧图 A–B、存储可写的命令；调度器把后台与主线程任务各自记下，由测试按顺序执行。
   *
   * @param rejectedAsyncCalls 第几次排后台（从 1 数）时抛出，模拟插件停用
   */
  private record Harness(
      FtaGraphCommand command,
      RailGraphService service,
      World world,
      UUID worldId,
      RailGraph base,
      List<Runnable> asyncTasks,
      List<Runnable> mainTasks,
      Set<Integer> rejectedAsyncCalls,
      CommandSender sender,
      Player player,
      LocaleManager locale,
      RailNodeRepository nodeRepo,
      RailEdgeRepository edgeRepo) {

    RailGraph current() {
      return service.getSnapshot(worldId).orElseThrow().graph();
    }

    void rejectAsyncCall(int call) {
      rejectedAsyncCalls.add(call);
    }

    void storedNodes(List<RailNodeRecord> nodes) {
      when(nodeRepo.listByWorld(worldId)).thenReturn(List.copyOf(nodes));
    }

    @SuppressWarnings("unchecked")
    java.util.concurrent.ConcurrentMap<UUID, RailGraphBuildTask> jobs() throws Exception {
      java.lang.reflect.Field field = FtaGraphCommand.class.getDeclaredField("jobs");
      assertTrue(field.trySetAccessible());
      return (java.util.concurrent.ConcurrentMap<UUID, RailGraphBuildTask>) field.get(command);
    }

    @SuppressWarnings("unchecked")
    Map<Object, RailGraphBuildContinuation> continuations() throws Exception {
      java.lang.reflect.Field field = FtaGraphCommand.class.getDeclaredField("continuations");
      assertTrue(field.trySetAccessible());
      return (Map<Object, RailGraphBuildContinuation>) field.get(command);
    }

    Object cacheKey() throws Exception {
      Method cacheKey =
          FtaGraphCommand.class.getDeclaredMethod("cacheKey", UUID.class, CommandSender.class);
      assertTrue(cacheKey.trySetAccessible());
      return cacheKey.invoke(command, worldId, sender);
    }

    RailGraphBuildTask registerJob() throws Exception {
      RailGraphBuildTask task = mock(RailGraphBuildTask.class);
      jobs().put(worldId, task);
      return task;
    }

    /** A–B，区间长度可调。 */
    RailGraph lineAb(int length) {
      return graph(
          worldId,
          Set.of(node("A", 1), node("B", 2)),
          Set.of(edge("A", "B", length)),
          Map.of(
              EdgeId.undirected(NodeId.of("A"), NodeId.of("B")),
              new RailEdgeFootprint(1, true, Set.of(new RailFootprintCell(1, 64, 0)))));
    }

    /** A–B–C 一条线。 */
    RailGraph lineWithC() {
      return graph(
          worldId,
          Set.of(node("A", 1), node("B", 2), node("C", 3)),
          Set.of(edge("A", "B", 12), edge("B", "C", 5)),
          Map.of(
              EdgeId.undirected(NodeId.of("A"), NodeId.of("B")),
              new RailEdgeFootprint(1, true, Set.of(new RailFootprintCell(1, 64, 0))),
              EdgeId.undirected(NodeId.of("B"), NodeId.of("C")),
              new RailEdgeFootprint(1, true, Set.of(new RailFootprintCell(2, 64, 0)))));
    }

    /** A–B 与 C–D 两个连通分量。 */
    RailGraph twoComponents() {
      return graph(
          worldId,
          Set.of(node("A", 1), node("B", 2), node("C", 30), node("D", 31)),
          Set.of(edge("A", "B", 12), edge("C", "D", 4)),
          Map.of(
              EdgeId.undirected(NodeId.of("A"), NodeId.of("B")),
              new RailEdgeFootprint(1, true, Set.of(new RailFootprintCell(1, 64, 0))),
              EdgeId.undirected(NodeId.of("C"), NodeId.of("D")),
              new RailEdgeFootprint(1, true, Set.of(new RailFootprintCell(30, 64, 0)))));
    }

    /** 快速刷新开始时库里的节点表：A、B 之外新放了 X。 */
    List<RailNodeRecord> refreshNodes() {
      return List.of(
          nodeRecord(worldId, "A", 1), nodeRecord(worldId, "B", 2), nodeRecord(worldId, "X", 10));
    }

    /** build 结果：在 A 上接出 X；结果可能不完整，按 upsert 与旧图合并。 */
    RailGraphBuildOutcome updateAddingX(Optional<RailGraphBuildContinuation> continuation) {
      EdgeId edgeId = EdgeId.undirected(NodeId.of("A"), NodeId.of("X"));
      RailGraph update =
          graph(
              worldId,
              Set.of(node("A", 1), node("X", 10)),
              Set.of(edge("A", "X", 2)),
              Map.of(
                  edgeId, new RailEdgeFootprint(1, true, Set.of(new RailFootprintCell(5, 64, 0)))));
      return outcome(update, continuation);
    }

    /** build 结果：合并时一读节点就栈溢出。 */
    RailGraphBuildOutcome updateThatOverflows(Optional<RailGraphBuildContinuation> continuation) {
      RailGraph overflowing = mock(RailGraph.class);
      when(overflowing.nodes()).thenThrow(new StackOverflowError("deep"));
      when(overflowing.edges()).thenThrow(new StackOverflowError("deep"));
      return outcome(overflowing, continuation);
    }

    private RailGraphBuildOutcome outcome(
        RailGraph update, Optional<RailGraphBuildContinuation> continuation) {
      List<RailNodeRecord> nodes =
          List.of(nodeRecord(worldId, "A", 1), nodeRecord(worldId, "X", 10));
      return new RailGraphBuildOutcome(
          new RailGraphBuildResult(
              update,
              Instant.parse("2026-01-03T00:00:00Z"),
              RailGraphSignature.signatureForNodes(nodes),
              nodes,
              List.of(),
              List.of()),
          RailGraphBuildCompletion.PARTIAL_UNLOADED_CHUNKS,
          continuation,
          List.of());
    }

    @SuppressWarnings("unchecked")
    void finish(RailGraphBuildOutcome outcome) throws Exception {
      Object key = cacheKey();
      Method buildFinished =
          FtaGraphCommand.class.getDeclaredMethod(
              "buildFinished",
              CommandSender.class,
              World.class,
              key.getClass(),
              long.class,
              LocaleManager.class);
      assertTrue(buildFinished.trySetAccessible());
      java.util.function.Consumer<RailGraphBuildOutcome> consumer =
          (java.util.function.Consumer<RailGraphBuildOutcome>)
              buildFinished.invoke(command, sender, world, key, System.nanoTime(), locale);
      consumer.accept(outcome);
    }

    /** 快速刷新收尾交给后台：探索没发现区间（新图只有节点），没有作废道岔。 */
    void refresh(List<RailNodeRecord> nodes, RailGraphBuildTask task) throws Exception {
      NodeToNodeEdgeExplorer explorer = mock(NodeToNodeEdgeExplorer.class);
      when(explorer.getExploredEdges()).thenReturn(Map.of());
      when(explorer.unterminatedDirections()).thenReturn(List.of());
      Method startRefreshSwitch =
          FtaGraphCommand.class.getDeclaredMethod(
              "startRefreshSwitch",
              CommandSender.class,
              World.class,
              List.class,
              List.class,
              NodeToNodeEdgeExplorer.class,
              int.class,
              long.class,
              LocaleManager.class,
              RailGraphBuildTask.class);
      assertTrue(startRefreshSwitch.trySetAccessible());
      startRefreshSwitch.invoke(
          command, sender, world, nodes, List.of(), explorer, 0, System.nanoTime(), locale, task);
    }

    void deleteHere(NodeId seed) throws Exception {
      Method startDeleteHere =
          FtaGraphCommand.class.getDeclaredMethod(
              "startDeleteHere", Player.class, World.class, NodeId.class, LocaleManager.class);
      assertTrue(startDeleteHere.trySetAccessible());
      startDeleteHere.invoke(command, player, world, seed, locale);
    }
  }

  private Harness harness() {
    UUID worldId = UUID.randomUUID();
    World world = mock(World.class);
    when(world.getUID()).thenReturn(worldId);
    when(world.getName()).thenReturn("world");
    EdgeId baseEdge = EdgeId.undirected(NodeId.of("A"), NodeId.of("B"));
    RailGraph base =
        graph(
            worldId,
            Set.of(node("A", 1), node("B", 2)),
            Set.of(edge("A", "B", 12)),
            Map.of(
                baseEdge, new RailEdgeFootprint(1, true, Set.of(new RailFootprintCell(1, 64, 0)))));
    RailGraphService service = new RailGraphService();
    service.putSnapshot(world, base, Instant.parse("2026-01-01T00:00:00Z"));

    StorageManager storageManager = mock(StorageManager.class);
    when(storageManager.isReady()).thenReturn(true);
    StorageProvider provider = mock(StorageProvider.class);
    RailNodeRepository nodeRepo = mock(RailNodeRepository.class);
    RailEdgeRepository edgeRepo = mock(RailEdgeRepository.class);
    when(provider.railNodes()).thenReturn(nodeRepo);
    when(provider.railEdges()).thenReturn(edgeRepo);
    when(provider.railGraphSnapshots()).thenReturn(mock(RailGraphSnapshotRepository.class));
    when(provider.railInterlockingSnapshots())
        .thenReturn(mock(RailInterlockingSnapshotRepository.class));
    when(provider.transactionManager()).thenReturn(new InlineTransactionManager());
    when(storageManager.provider()).thenReturn(Optional.of(provider));

    FetaruteTCAddon plugin = mock(FetaruteTCAddon.class);
    when(plugin.getDataFolder()).thenReturn(pluginFolder.toFile());
    when(plugin.getRailGraphService()).thenReturn(service);
    when(plugin.getSignNodeRegistry()).thenReturn(new SignNodeRegistry());
    when(plugin.getStorageManager()).thenReturn(storageManager);
    when(plugin.getLogger()).thenReturn(Logger.getLogger("test"));
    Server server = mock(Server.class);
    BukkitScheduler scheduler = mock(BukkitScheduler.class);
    when(plugin.getServer()).thenReturn(server);
    when(server.getScheduler()).thenReturn(scheduler);
    List<Runnable> asyncTasks = new java.util.ArrayList<>();
    List<Runnable> mainTasks = new java.util.ArrayList<>();
    Set<Integer> rejectedAsyncCalls = new java.util.HashSet<>();
    java.util.concurrent.atomic.AtomicInteger asyncCalls =
        new java.util.concurrent.atomic.AtomicInteger();
    when(scheduler.runTaskAsynchronously(any(), any(Runnable.class)))
        .thenAnswer(
            invocation -> {
              if (rejectedAsyncCalls.contains(asyncCalls.incrementAndGet())) {
                throw new org.bukkit.plugin.IllegalPluginAccessException("plugin disabled");
              }
              asyncTasks.add(invocation.getArgument(1));
              return mock(BukkitTask.class);
            });
    when(scheduler.runTask(any(), any(Runnable.class)))
        .thenAnswer(
            invocation -> {
              mainTasks.add(invocation.getArgument(1));
              return mock(BukkitTask.class);
            });
    return new Harness(
        new FtaGraphCommand(plugin),
        service,
        world,
        worldId,
        base,
        asyncTasks,
        mainTasks,
        rejectedAsyncCalls,
        mock(CommandSender.class),
        mock(Player.class),
        mock(LocaleManager.class),
        nodeRepo,
        edgeRepo);
  }

  private static Scheduled scheduled(FetaruteTCAddon plugin) {
    Server server = mock(Server.class);
    BukkitScheduler scheduler = mock(BukkitScheduler.class);
    when(plugin.getServer()).thenReturn(server);
    when(server.getScheduler()).thenReturn(scheduler);
    Scheduled scheduled = new Scheduled(new java.util.ArrayDeque<>());
    when(scheduler.runTaskAsynchronously(any(), any(Runnable.class)))
        .thenAnswer(
            invocation -> {
              scheduled.tasks().add(invocation.getArgument(1));
              return mock(BukkitTask.class);
            });
    when(scheduler.runTask(any(), any(Runnable.class)))
        .thenAnswer(
            invocation -> {
              scheduled.tasks().add(invocation.getArgument(1));
              return mock(BukkitTask.class);
            });
    return scheduled;
  }

  private record Scheduled(java.util.Deque<Runnable> tasks) {
    void runAll() {
      while (!tasks.isEmpty()) {
        tasks.poll().run();
      }
    }

    boolean isEmpty() {
      return tasks.isEmpty();
    }
  }

  private static RailNodeRecord nodeRecord(UUID worldId, String nodeId, int x) {
    return new RailNodeRecord(
        worldId,
        NodeId.of(nodeId),
        NodeType.WAYPOINT,
        x,
        64,
        0,
        Optional.empty(),
        Optional.empty());
  }

  private static RailGraph graph(
      UUID worldId,
      Set<org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode> nodes,
      Set<RailEdge> edges,
      Map<EdgeId, RailEdgeFootprint> footprints) {
    return graph(
        nodes,
        edges,
        RailInterlockingState.from(
            worldId, edges.stream().map(RailEdge::id).collect(Collectors.toSet()), footprints));
  }

  private static RailGraph graph(
      Set<org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode> nodes,
      Set<RailEdge> edges,
      RailInterlockingState interlockingState) {
    Map<NodeId, org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode> nodesById =
        new java.util.HashMap<>();
    for (org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode node : nodes) {
      nodesById.put(node.id(), node);
    }
    Map<EdgeId, RailEdge> edgesById = new java.util.HashMap<>();
    for (RailEdge edge : edges) {
      edgesById.put(edge.id(), edge);
    }
    return new SimpleRailGraph(nodesById, edgesById, Set.of(), interlockingState);
  }

  private static void assertIncompleteInterlocking(
      RailGraph graph, UUID worldId, Set<EdgeId> expectedEdges) {
    RailGraphInterlockingSupport support =
        assertInstanceOf(RailGraphInterlockingSupport.class, graph);
    RailInterlockingState state = support.interlockingState();
    assertTrue(state.available());
    assertEquals(expectedEdges, state.expectedEdges());
    assertFalse(state.coverage().complete());
    // 按不完整发布，但已测到的足迹留在索引里，写库时不会被抹掉。
    assertEquals(expectedEdges, state.participatingFootprints().keySet());
    for (EdgeId edgeId : expectedEdges) {
      assertEquals(Set.of("interlocking:incomplete:" + worldId), state.zoneKeysForEdge(edgeId));
    }
  }

  private static org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode node(String id, int x) {
    return new SignRailNode(
        NodeId.of(id), NodeType.WAYPOINT, new Vector(x, 64, 0), Optional.empty(), Optional.empty());
  }

  private static RailEdge edge(String a, String b, int length) {
    EdgeId id = EdgeId.undirected(NodeId.of(a), NodeId.of(b));
    return new RailEdge(id, id.a(), id.b(), length, 0.0, true, Optional.empty());
  }

  private static final class InlineTransactionManager implements StorageTransactionManager {

    @Override
    public org.fetarute.fetaruteTCAddon.storage.api.StorageTransaction begin()
        throws StorageException {
      throw new UnsupportedOperationException("not used");
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

  private static final class FailingTransactionManager implements StorageTransactionManager {

    @Override
    public org.fetarute.fetaruteTCAddon.storage.api.StorageTransaction begin()
        throws StorageException {
      throw new UnsupportedOperationException("not used");
    }

    @Override
    public <T> T execute(TransactionCallback<T> callback) throws StorageException {
      throw new StorageException("forced persistence failure");
    }
  }
}
