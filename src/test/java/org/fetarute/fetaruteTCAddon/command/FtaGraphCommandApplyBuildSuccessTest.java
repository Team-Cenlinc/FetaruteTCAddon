package org.fetarute.fetaruteTCAddon.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.InvocationTargetException;
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
import org.fetarute.fetaruteTCAddon.dispatcher.graph.build.RailGraphBuildCompletion;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.build.RailGraphBuildResult;
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
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

final class FtaGraphCommandApplyBuildSuccessTest {

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

    RailGraphService railGraphService = new RailGraphService(w -> SimpleRailGraph.empty());

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

    Method applyBuildSuccess =
        FtaGraphCommand.class.getDeclaredMethod(
            "applyBuildSuccess",
            World.class,
            RailGraphBuildResult.class,
            RailGraphBuildCompletion.class);
    assertTrue(applyBuildSuccess.trySetAccessible());
    applyBuildSuccess.invoke(command, world, update, RailGraphBuildCompletion.COMPLETE);

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
    assertEquals(
        Set.of(
            new RailEdgeRecord(worldId, firstEdge, 2, 0.0, true),
            new RailEdgeRecord(worldId, secondEdge, 3, 0.0, true)),
        Set.copyOf(persistedEdges.getValue()));

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
    RailGraphService railGraphService = new RailGraphService(ignored -> oldGraph);
    railGraphService.putSnapshot(world, oldGraph, Instant.parse("2026-01-01T00:00:00Z"));

    StorageManager storageManager = mock(StorageManager.class);
    when(storageManager.isReady()).thenReturn(true);
    StorageProvider provider = mock(StorageProvider.class);
    when(provider.transactionManager()).thenReturn(new FailingTransactionManager());
    when(storageManager.provider()).thenReturn(Optional.of(provider));
    FetaruteTCAddon plugin = mock(FetaruteTCAddon.class);
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
    Method applyBuildSuccess =
        FtaGraphCommand.class.getDeclaredMethod(
            "applyBuildSuccess",
            World.class,
            RailGraphBuildResult.class,
            RailGraphBuildCompletion.class);
    assertTrue(applyBuildSuccess.trySetAccessible());

    InvocationTargetException failure =
        assertThrows(
            InvocationTargetException.class,
            () ->
                applyBuildSuccess.invoke(
                    command, world, update, RailGraphBuildCompletion.COMPLETE));

    assertTrue(failure.getCause() instanceof IllegalStateException);
    assertSame(oldGraph, railGraphService.getSnapshot(world).orElseThrow().graph());
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
    assertEquals(0, state.coverage().participatingEdgeCount());
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
