package org.fetarute.fetaruteTCAddon.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.util.Vector;
import org.fetarute.fetaruteTCAddon.FetaruteTCAddon;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphService;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.SignRailNode;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.SimpleRailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.build.RailGraphSignature;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.explore.RailBlockPos;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.explore.TrainCartsRailBlockAccess;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.persist.RailGraphSnapshotRecord;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.persist.RailNodeRecord;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.repository.RailGraphSnapshotRepository;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.repository.RailNodeRepository;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.sync.RailNodeIncrementalSync;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SwitcherSignDefinitionParser;
import org.fetarute.fetaruteTCAddon.storage.StorageManager;
import org.fetarute.fetaruteTCAddon.storage.api.StorageException;
import org.fetarute.fetaruteTCAddon.storage.api.StorageProvider;
import org.fetarute.fetaruteTCAddon.storage.api.StorageTransactionManager;
import org.fetarute.fetaruteTCAddon.storage.api.TransactionCallback;
import org.junit.jupiter.api.Test;

/** refresh/extend 的道岔剪枝使用真实增量同步，删除与写库失败都会更新失效状态。 */
final class FtaGraphCommandSwitcherPruningTest {

  @Test
  void pruningUpdatesStorageAndMarksTheGraphStale() throws Exception {
    Fixture fixture = fixture();
    when(fixture.nodes().delete(fixture.world().getUID(), fixture.switcher().nodeId()))
        .thenAnswer(ignored -> fixture.stored().remove(fixture.switcher()) ? 1 : 0);

    assertEquals(List.of(fixture.waypoint()), prune(fixture));
    assertEquals(List.of(fixture.waypoint()), fixture.stored());
    assertTrue(fixture.graph().getStaleState(fixture.world()).isPresent());
    verify(fixture.nodes()).delete(fixture.world().getUID(), fixture.switcher().nodeId());
  }

  @Test
  void failedDeletionAbortsPruningAndKeepsTheGraphStale() throws Exception {
    Fixture fixture = fixture();
    when(fixture.nodes().delete(fixture.world().getUID(), fixture.switcher().nodeId()))
        .thenThrow(new StorageException("delete failed"));

    InvocationTargetException exception =
        assertThrows(InvocationTargetException.class, () -> prune(fixture));
    assertInstanceOf(IllegalStateException.class, exception.getCause());
    assertTrue(fixture.graph().getStaleState(fixture.world()).isPresent());
    assertEquals(2, fixture.stored().size());
  }

  @Test
  void preservesNodesWhenTheSwitcherCannotBeConfirmedStale() throws Exception {
    Fixture fixture = fixture();
    when(fixture.access().hasOnlyConditionalSwitchers(new RailBlockPos(12, 64, 3)))
        .thenReturn(false);
    assertEquals(List.copyOf(fixture.stored()), prune(fixture));
    assertTrue(fixture.graph().getStaleState(fixture.world()).isEmpty());
    verify(fixture.nodes(), never()).delete(any(), any());
  }

  private static Object prune(Fixture fixture) throws Exception {
    Method method =
        FtaGraphCommand.class.getDeclaredMethod(
            "pruneStaleSwitcherNodes", World.class, TrainCartsRailBlockAccess.class, List.class);
    assertTrue(method.trySetAccessible());
    return method.invoke(
        fixture.command(), fixture.world(), fixture.access(), List.copyOf(fixture.stored()));
  }

  private static Fixture fixture() throws Exception {
    World world = mock(World.class);
    UUID worldId = UUID.randomUUID();
    when(world.getUID()).thenReturn(worldId);
    when(world.getName()).thenReturn("world");
    RailBlockPos pos = new RailBlockPos(12, 64, 3);
    NodeId id = SwitcherSignDefinitionParser.nodeIdForRail("world", pos);
    RailNodeRecord switcher =
        new RailNodeRecord(
            worldId,
            id,
            NodeType.SWITCHER,
            12,
            64,
            3,
            Optional.of(SwitcherSignDefinitionParser.SWITCHER_SIGN_MARKER),
            Optional.empty());
    RailNodeRecord waypoint =
        new RailNodeRecord(
            worldId,
            NodeId.of("A"),
            NodeType.WAYPOINT,
            20,
            64,
            3,
            Optional.empty(),
            Optional.empty());
    List<RailNodeRecord> stored = new ArrayList<>(List.of(switcher, waypoint));
    RailNodeRepository nodes = mock(RailNodeRepository.class);
    when(nodes.listByWorld(worldId)).thenAnswer(ignored -> List.copyOf(stored));
    RailGraphSnapshotRepository snapshots = mock(RailGraphSnapshotRepository.class);
    when(snapshots.findByWorld(worldId))
        .thenReturn(
            Optional.of(
                new RailGraphSnapshotRecord(
                    worldId, Instant.EPOCH, 2, 0, RailGraphSignature.signatureForNodes(stored))));
    StorageTransactionManager transactions = mock(StorageTransactionManager.class);
    when(transactions.execute(any()))
        .thenAnswer(
            invocation -> ((TransactionCallback<?>) invocation.getArgument(0)).doInTransaction());
    StorageProvider provider = mock(StorageProvider.class);
    when(provider.railNodes()).thenReturn(nodes);
    when(provider.railGraphSnapshots()).thenReturn(snapshots);
    when(provider.transactionManager()).thenReturn(transactions);
    StorageManager storage = mock(StorageManager.class);
    when(storage.isReady()).thenReturn(true);
    when(storage.provider()).thenReturn(Optional.of(provider));
    RailGraphService graph = new RailGraphService(ignored -> SimpleRailGraph.empty());
    graph.putSnapshot(
        world,
        new SimpleRailGraph(
            Map.of(
                id,
                new SignRailNode(
                    id,
                    NodeType.SWITCHER,
                    new Vector(12, 64, 3),
                    switcher.trainCartsDestination(),
                    Optional.empty())),
            Map.of(),
            Set.of()),
        Instant.EPOCH);
    FetaruteTCAddon plugin = mock(FetaruteTCAddon.class);
    when(plugin.getStorageManager()).thenReturn(storage);
    when(plugin.getLogger()).thenReturn(Logger.getLogger("test"));
    when(plugin.getSignNodeStorageSync())
        .thenReturn(new RailNodeIncrementalSync(storage, graph, null));
    Block block = mock(Block.class);
    when(block.getWorld()).thenReturn(world);
    when(block.getX()).thenReturn(12);
    when(block.getY()).thenReturn(64);
    when(block.getZ()).thenReturn(3);
    when(world.getBlockAt(12, 64, 3)).thenReturn(block);
    TrainCartsRailBlockAccess access = mock(TrainCartsRailBlockAccess.class);
    when(access.hasOnlyConditionalSwitchers(pos)).thenReturn(true);
    return new Fixture(
        new FtaGraphCommand(plugin), world, access, nodes, graph, stored, switcher, waypoint);
  }

  private record Fixture(
      FtaGraphCommand command,
      World world,
      TrainCartsRailBlockAccess access,
      RailNodeRepository nodes,
      RailGraphService graph,
      List<RailNodeRecord> stored,
      RailNodeRecord switcher,
      RailNodeRecord waypoint) {}
}
