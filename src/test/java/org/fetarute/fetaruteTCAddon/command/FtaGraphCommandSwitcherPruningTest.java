package org.fetarute.fetaruteTCAddon.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.util.Vector;
import org.fetarute.fetaruteTCAddon.FetaruteTCAddon;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphService;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.SignRailNode;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.SimpleRailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.build.NodeToNodeEdgeExplorer;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.build.SwitcherNodeAudit;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.explore.RailBlockPos;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.explore.TrainCartsRailBlockAccess;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.interlocking.RailInterlockingState;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.persist.RailNodeRecord;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.repository.RailEdgeRepository;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.repository.RailGraphSnapshotRepository;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.repository.RailInterlockingSnapshotRepository;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.repository.RailNodeRepository;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SwitcherSignDefinitionParser;
import org.fetarute.fetaruteTCAddon.storage.StorageManager;
import org.fetarute.fetaruteTCAddon.storage.api.StorageException;
import org.fetarute.fetaruteTCAddon.storage.api.StorageProvider;
import org.fetarute.fetaruteTCAddon.storage.api.StorageTransactionManager;
import org.fetarute.fetaruteTCAddon.storage.api.TransactionCallback;
import org.fetarute.fetaruteTCAddon.utils.LocaleManager;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** refresh 的作废道岔剪枝：核对时只记下作废节点，随刷新结果一起写库；刷新没写成时节点表与生效图都不动，也不触发图失效。 */
final class FtaGraphCommandSwitcherPruningTest {
  @org.junit.jupiter.api.io.TempDir java.nio.file.Path pluginFolder;

  @Test
  void staleSwitchersLeaveTheNodeTableTogetherWithTheRefreshedGraph() throws Exception {
    Fixture fixture = fixture(true);
    List<RailNodeRecord> loaded = List.copyOf(fixture.stored());
    List<RailNodeRecord> stale = audit(fixture, loaded).stale();
    assertEquals(List.of(fixture.switcher()), stale);

    fixture
        .command()
        .finishRefresh(
            fixture.sender(),
            fixture.world(),
            loaded,
            stale,
            emptyExplorer(fixture),
            0,
            System.nanoTime(),
            fixture.locale());

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Collection<RailNodeRecord>> written = ArgumentCaptor.forClass(Collection.class);
    verify(fixture.nodes()).replaceWorld(eq(fixture.world().getUID()), written.capture());
    assertEquals(Set.of(fixture.waypoint().nodeId()), ids(written.getValue()));
    RailGraph served = fixture.graph().getSnapshot(fixture.world()).orElseThrow().graph();
    assertTrue(served.findNode(fixture.switcher().nodeId()).isEmpty());
    assertTrue(served.findNode(fixture.waypoint().nodeId()).isPresent());
    assertTrue(fixture.graph().getStaleState(fixture.world()).isEmpty(), "剪枝不让图先进入失效状态");
    verify(fixture.nodes(), never()).delete(any(), any());
  }

  @Test
  void failedRefreshKeepsStaleSwitchersAndTheServedGraph() throws Exception {
    Fixture fixture = fixture(false);
    List<RailNodeRecord> loaded = List.copyOf(fixture.stored());
    List<RailNodeRecord> stale = audit(fixture, loaded).stale();
    RailGraph before = fixture.graph().getSnapshot(fixture.world()).orElseThrow().graph();

    fixture
        .command()
        .finishRefresh(
            fixture.sender(),
            fixture.world(),
            loaded,
            stale,
            emptyExplorer(fixture),
            0,
            System.nanoTime(),
            fixture.locale());

    assertEquals(2, fixture.stored().size());
    assertEquals(before, fixture.graph().getSnapshot(fixture.world()).orElseThrow().graph());
    assertTrue(fixture.graph().getStaleState(fixture.world()).isEmpty(), "写库失败也不发失效告警");
    verify(fixture.nodes(), never()).delete(any(), any());
  }

  @Test
  void nodeChangesDuringRefreshAbortWithoutPruning() throws Exception {
    Fixture fixture = fixture(true);
    List<RailNodeRecord> loaded = List.copyOf(fixture.stored());
    List<RailNodeRecord> stale = audit(fixture, loaded).stale();
    fixture.stored().add(record(fixture.world().getUID(), "B", NodeType.WAYPOINT, 30));

    fixture
        .command()
        .finishRefresh(
            fixture.sender(),
            fixture.world(),
            loaded,
            stale,
            emptyExplorer(fixture),
            0,
            System.nanoTime(),
            fixture.locale());

    verify(fixture.nodes(), never()).replaceWorld(any(), any());
    verify(fixture.locale()).component("command.graph.refresh.nodes-changed");
    assertTrue(
        fixture
            .graph()
            .getSnapshot(fixture.world())
            .orElseThrow()
            .graph()
            .findNode(fixture.switcher().nodeId())
            .isPresent());
  }

  @Test
  void switcherThatStillPathfindsIsNotReportedStale() throws Exception {
    Fixture fixture = fixture(true);
    when(fixture.access().hasOnlyConditionalSwitchers(new RailBlockPos(12, 64, 3)))
        .thenReturn(false);
    assertTrue(audit(fixture, List.copyOf(fixture.stored())).stale().isEmpty());
  }

  /** 跑完核对（区块都已加载）。 */
  private static SwitcherNodeAudit.Result audit(Fixture fixture, List<RailNodeRecord> loaded) {
    SwitcherNodeAudit audit =
        new SwitcherNodeAudit(
            loaded, ALL_LOADED, fixture.access()::hasOnlyConditionalSwitchers, 4, 256);
    while (!audit.step(Long.MAX_VALUE)) {
      // 区块都已加载，一步就能走完
    }
    return audit.result();
  }

  /** 没有可走的锚点：本轮探索不出新区间，刷新结果只由节点表与旧图决定。 */
  private static NodeToNodeEdgeExplorer emptyExplorer(Fixture fixture) {
    return new NodeToNodeEdgeExplorer(fixture.world(), Map.of(), message -> {});
  }

  private static Set<NodeId> ids(Collection<RailNodeRecord> records) {
    return records.stream().map(RailNodeRecord::nodeId).collect(Collectors.toSet());
  }

  private static final SwitcherNodeAudit.ChunkAccess ALL_LOADED =
      new SwitcherNodeAudit.ChunkAccess() {
        @Override
        public boolean isLoaded(int chunkX, int chunkZ) {
          return true;
        }

        @Override
        public CompletableFuture<?> load(int chunkX, int chunkZ) {
          return CompletableFuture.completedFuture(null);
        }

        @Override
        public boolean hold(int chunkX, int chunkZ) {
          return false;
        }

        @Override
        public void release(int chunkX, int chunkZ) {}
      };

  private static RailNodeRecord record(UUID worldId, String id, NodeType type, int x) {
    return new RailNodeRecord(
        worldId, NodeId.of(id), type, x, 64, 3, Optional.empty(), Optional.empty());
  }

  /**
   * @param storageWritable 写库事务是否成功
   */
  private Fixture fixture(boolean storageWritable) throws Exception {
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
    RailNodeRecord waypoint = record(worldId, "A", NodeType.WAYPOINT, 20);
    List<RailNodeRecord> stored = new ArrayList<>(List.of(switcher, waypoint));
    RailNodeRepository nodes = mock(RailNodeRepository.class);
    when(nodes.listByWorld(worldId)).thenAnswer(ignored -> List.copyOf(stored));
    StorageTransactionManager transactions = mock(StorageTransactionManager.class);
    if (storageWritable) {
      when(transactions.execute(any()))
          .thenAnswer(
              invocation -> ((TransactionCallback<?>) invocation.getArgument(0)).doInTransaction());
    } else {
      when(transactions.execute(any())).thenThrow(new StorageException("write failed"));
    }
    StorageProvider provider = mock(StorageProvider.class);
    when(provider.railNodes()).thenReturn(nodes);
    when(provider.railEdges()).thenReturn(mock(RailEdgeRepository.class));
    when(provider.railGraphSnapshots()).thenReturn(mock(RailGraphSnapshotRepository.class));
    when(provider.railInterlockingSnapshots())
        .thenReturn(mock(RailInterlockingSnapshotRepository.class));
    when(provider.transactionManager()).thenReturn(transactions);
    StorageManager storage = mock(StorageManager.class);
    when(storage.isReady()).thenReturn(true);
    when(storage.provider()).thenReturn(Optional.of(provider));
    RailGraphService graph = new RailGraphService();
    Map<NodeId, RailNode> served =
        Map.of(
            id,
            new SignRailNode(
                id,
                NodeType.SWITCHER,
                new Vector(12, 64, 3),
                switcher.trainCartsDestination(),
                Optional.empty()),
            waypoint.nodeId(),
            new SignRailNode(
                waypoint.nodeId(),
                NodeType.WAYPOINT,
                new Vector(20, 64, 3),
                Optional.empty(),
                Optional.empty()));
    graph.putSnapshot(
        world,
        new SimpleRailGraph(
            served, Map.of(), Set.of(), RailInterlockingState.incomplete(worldId, Set.of())),
        Instant.EPOCH);
    FetaruteTCAddon plugin = mock(FetaruteTCAddon.class);
    when(plugin.getDataFolder()).thenReturn(pluginFolder.toFile());
    when(plugin.getStorageManager()).thenReturn(storage);
    when(plugin.getRailGraphService()).thenReturn(graph);
    when(plugin.getLogger()).thenReturn(Logger.getLogger("test"));
    Server server = mock(Server.class);
    when(plugin.getServer()).thenReturn(server);
    when(server.getScheduler()).thenReturn(mock(BukkitScheduler.class));
    TrainCartsRailBlockAccess access = mock(TrainCartsRailBlockAccess.class);
    when(access.hasOnlyConditionalSwitchers(pos)).thenReturn(true);
    return new Fixture(
        new FtaGraphCommand(plugin),
        world,
        access,
        nodes,
        graph,
        stored,
        switcher,
        waypoint,
        mock(CommandSender.class),
        mock(LocaleManager.class));
  }

  private record Fixture(
      FtaGraphCommand command,
      World world,
      TrainCartsRailBlockAccess access,
      RailNodeRepository nodes,
      RailGraphService graph,
      List<RailNodeRecord> stored,
      RailNodeRecord switcher,
      RailNodeRecord waypoint,
      CommandSender sender,
      LocaleManager locale) {}
}
