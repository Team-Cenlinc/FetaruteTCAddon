package org.fetarute.fetaruteTCAddon.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.World;
import org.bukkit.util.Vector;
import org.fetarute.fetaruteTCAddon.FetaruteTCAddon;
import org.fetarute.fetaruteTCAddon.company.repository.RailEdgeRepository;
import org.fetarute.fetaruteTCAddon.company.repository.RailGraphSnapshotRepository;
import org.fetarute.fetaruteTCAddon.company.repository.RailNodeRepository;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailEdge;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphService;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.SignRailNode;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.SimpleRailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.build.RailGraphBuildCompletion;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.build.RailGraphBuildResult;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.build.RailGraphSignature;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.persist.RailEdgeRecord;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.persist.RailNodeRecord;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.storage.StorageManager;
import org.fetarute.fetaruteTCAddon.storage.api.StorageException;
import org.fetarute.fetaruteTCAddon.storage.api.StorageProvider;
import org.fetarute.fetaruteTCAddon.storage.api.StorageTransactionManager;
import org.fetarute.fetaruteTCAddon.storage.api.TransactionCallback;
import org.junit.jupiter.api.Test;

final class FtaGraphCommandApplyBuildSuccessTest {

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

    List<RailNodeRecord> baseNodes =
        List.of(
            nodeRecord(worldId, "A"),
            nodeRecord(worldId, "B"),
            nodeRecord(worldId, "X"),
            nodeRecord(worldId, "Y"));
    List<RailEdgeRecord> baseEdges =
        List.of(
            new RailEdgeRecord(
                worldId, EdgeId.undirected(NodeId.of("A"), NodeId.of("B")), 5, 0.0, true),
            new RailEdgeRecord(
                worldId, EdgeId.undirected(NodeId.of("X"), NodeId.of("Y")), 7, 0.0, true));
    when(nodeRepo.listByWorld(worldId)).thenReturn(baseNodes);
    when(edgeRepo.listByWorld(worldId)).thenReturn(baseEdges);
    when(snapshotRepo.save(org.mockito.ArgumentMatchers.any()))
        .thenAnswer(inv -> inv.getArgument(0));

    StorageTransactionManager txManager = new InlineTransactionManager();
    StorageProvider provider = mock(StorageProvider.class);
    when(provider.railNodes()).thenReturn(nodeRepo);
    when(provider.railEdges()).thenReturn(edgeRepo);
    when(provider.railGraphSnapshots()).thenReturn(snapshotRepo);
    when(provider.transactionManager()).thenReturn(txManager);
    when(storageManager.provider()).thenReturn(Optional.of(provider));

    FetaruteTCAddon plugin = mock(FetaruteTCAddon.class);
    when(plugin.getRailGraphService()).thenReturn(railGraphService);
    when(plugin.getStorageManager()).thenReturn(storageManager);
    when(plugin.getLogger()).thenReturn(Logger.getLogger("test"));

    FtaGraphCommand command = new FtaGraphCommand(plugin);

    RailGraph updateGraph =
        graph(
            Set.of(node("X"), node("Y"), node("Z")), Set.of(edge("X", "Z", 2), edge("Z", "Y", 3)));
    List<RailNodeRecord> updateNodes =
        List.of(nodeRecord(worldId, "X"), nodeRecord(worldId, "Y"), nodeRecord(worldId, "Z"));
    RailGraphBuildResult update =
        new RailGraphBuildResult(
            updateGraph,
            Instant.parse("2026-01-03T00:00:00Z"),
            RailGraphSignature.signatureForNodes(updateNodes),
            updateNodes,
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
    assertEquals(5, merged.nodes().size());
    assertEquals(3, merged.edges().size());
    assertTrue(merged.findNode(NodeId.of("A")).isPresent());
    assertTrue(merged.findNode(NodeId.of("B")).isPresent());
    assertTrue(merged.findNode(NodeId.of("Z")).isPresent());
    assertTrue(
        merged.edges().stream()
            .anyMatch(edge -> edge.id().equals(EdgeId.undirected(NodeId.of("A"), NodeId.of("B")))));
    assertTrue(
        merged.edges().stream()
            .anyMatch(edge -> edge.id().equals(EdgeId.undirected(NodeId.of("X"), NodeId.of("Z")))));
    assertTrue(
        merged.edges().stream()
            .anyMatch(edge -> edge.id().equals(EdgeId.undirected(NodeId.of("Z"), NodeId.of("Y")))));
  }

  private static RailNodeRecord nodeRecord(UUID worldId, String nodeId) {
    return new RailNodeRecord(
        worldId, NodeId.of(nodeId), NodeType.WAYPOINT, 0, 0, 0, Optional.empty(), Optional.empty());
  }

  private static RailGraph graph(
      Set<org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode> nodes, Set<RailEdge> edges) {
    Map<NodeId, org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode> nodesById =
        new java.util.HashMap<>();
    for (org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode node : nodes) {
      nodesById.put(node.id(), node);
    }
    Map<EdgeId, RailEdge> edgesById = new java.util.HashMap<>();
    for (RailEdge edge : edges) {
      edgesById.put(edge.id(), edge);
    }
    return new SimpleRailGraph(nodesById, edgesById, Set.of());
  }

  private static org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode node(String id) {
    return new SignRailNode(
        NodeId.of(id), NodeType.WAYPOINT, new Vector(0, 0, 0), Optional.empty(), Optional.empty());
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
}
