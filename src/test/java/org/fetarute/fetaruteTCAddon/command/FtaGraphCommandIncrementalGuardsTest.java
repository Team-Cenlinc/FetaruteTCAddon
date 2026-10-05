package org.fetarute.fetaruteTCAddon.command;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Logger;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.util.Vector;
import org.fetarute.fetaruteTCAddon.FetaruteTCAddon;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailEdge;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphService;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.SignRailNode;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.SimpleRailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.build.ConnectedRailNodeDiscoverySession;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.build.RailGraphBuildCompletion;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.build.RailGraphBuildContinuation;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.build.RailGraphBuildOutcome;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.build.RailGraphBuildResult;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.persist.RailNodeRecord;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.repository.RailNodeRepository;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.sync.GraphStaleNotifier;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyResource;
import org.fetarute.fetaruteTCAddon.storage.StorageManager;
import org.fetarute.fetaruteTCAddon.storage.api.StorageProvider;
import org.fetarute.fetaruteTCAddon.utils.LocaleManager;
import org.junit.jupiter.api.Test;

/** refresh/extend 收尾前的核验，以及构建激活失败时的续跑状态处理。 */
final class FtaGraphCommandIncrementalGuardsTest {

  @Test
  void extendRefusesWhenTheOccupancyManagerIsNotReady() throws Exception {
    FetaruteTCAddon plugin = plugin();
    when(plugin.getOccupancyManager()).thenReturn(null);
    FtaGraphCommand command = new FtaGraphCommand(plugin);
    Method findHeldResource =
        FtaGraphCommand.class.getDeclaredMethod("findHeldResource", Set.class);
    assertTrue(findHeldResource.trySetAccessible());

    Optional<?> held =
        (Optional<?>)
            findHeldResource.invoke(
                command,
                Set.of(
                    OccupancyResource.forEdge(EdgeId.undirected(NodeId.of("A"), NodeId.of("B")))));

    assertTrue(held.isPresent(), "占用管理器缺席时不能当作没人占用");
  }

  @Test
  void signsPlacedRemovedOrMovedDuringExplorationAreDetected() throws Exception {
    UUID worldId = UUID.randomUUID();
    List<RailNodeRecord> expected = List.of(record(worldId, "A", 1), record(worldId, "B", 2));
    RailNodeRepository nodeRepo = mock(RailNodeRepository.class);
    StorageManager ready = storage(nodeRepo, true);
    StorageManager notReady = storage(nodeRepo, false);
    FetaruteTCAddon plugin = plugin();
    when(plugin.getStorageManager()).thenReturn(ready);
    FtaGraphCommand command = new FtaGraphCommand(plugin);
    Method differ =
        FtaGraphCommand.class.getDeclaredMethod("storedNodesDiffer", UUID.class, Collection.class);
    assertTrue(differ.trySetAccessible());

    when(nodeRepo.listByWorld(worldId)).thenReturn(List.of(expected.get(1), expected.get(0)));
    assertFalse((boolean) differ.invoke(command, worldId, expected));

    when(nodeRepo.listByWorld(worldId))
        .thenReturn(List.of(expected.get(0), expected.get(1), record(worldId, "C", 3)));
    assertTrue((boolean) differ.invoke(command, worldId, expected), "期间新放的牌子");

    when(nodeRepo.listByWorld(worldId)).thenReturn(List.of(expected.get(0)));
    assertTrue((boolean) differ.invoke(command, worldId, expected), "期间拆掉的牌子");

    when(nodeRepo.listByWorld(worldId))
        .thenReturn(List.of(expected.get(0), record(worldId, "B", 9)));
    assertTrue((boolean) differ.invoke(command, worldId, expected), "期间挪了位置的牌子");

    when(plugin.getStorageManager()).thenReturn(notReady);
    assertTrue((boolean) differ.invoke(command, worldId, expected), "存储不可用按不一致处理");
  }

  @Test
  void failedActivationDropsTheContinuationAndReleasesItsChunkTickets() throws Exception {
    UUID worldId = UUID.randomUUID();
    World world = mock(World.class);
    when(world.getUID()).thenReturn(worldId);
    when(world.getName()).thenReturn("world");
    RailGraphService service = new RailGraphService(ignored -> SimpleRailGraph.empty());
    // 存储未就绪：写库失败，走激活失败分支。
    StorageManager notReady = storage(mock(RailNodeRepository.class), false);
    FetaruteTCAddon plugin = plugin();
    when(plugin.getRailGraphService()).thenReturn(service);
    when(plugin.getStorageManager()).thenReturn(notReady);
    FtaGraphCommand command = new FtaGraphCommand(plugin);

    Object cacheKey = cacheKey(worldId);
    ConnectedRailNodeDiscoverySession previousSession =
        mock(ConnectedRailNodeDiscoverySession.class);
    continuations(command)
        .put(cacheKey, new RailGraphBuildContinuation(Instant.EPOCH, previousSession, List.of()));
    ConnectedRailNodeDiscoverySession pausedSession = mock(ConnectedRailNodeDiscoverySession.class);
    RailGraphBuildOutcome outcome =
        new RailGraphBuildOutcome(
            result(worldId),
            RailGraphBuildCompletion.PARTIAL_MAX_CHUNKS,
            Optional.of(new RailGraphBuildContinuation(Instant.EPOCH, pausedSession, List.of())),
            List.of());

    Method buildFinished =
        FtaGraphCommand.class.getDeclaredMethod(
            "buildFinished",
            CommandSender.class,
            World.class,
            cacheKey.getClass(),
            long.class,
            LocaleManager.class);
    assertTrue(buildFinished.trySetAccessible());
    @SuppressWarnings("unchecked")
    Consumer<RailGraphBuildOutcome> finish =
        (Consumer<RailGraphBuildOutcome>)
            buildFinished.invoke(
                command,
                mock(CommandSender.class),
                world,
                cacheKey,
                System.nanoTime(),
                mock(LocaleManager.class));

    finish.accept(outcome);

    assertTrue(continuations(command).isEmpty());
    verify(previousSession).releaseChunkTickets();
    verify(pausedSession).releaseChunkTickets();
  }

  @Test
  void activationOfAStaleWorldTellsTheStaleNotifier() throws Exception {
    UUID worldId = UUID.randomUUID();
    World world = mock(World.class);
    when(world.getUID()).thenReturn(worldId);
    when(world.getName()).thenReturn("world");
    RailGraphService service = new RailGraphService(ignored -> SimpleRailGraph.empty());
    service.markStale(
        world, new RailGraphService.RailGraphStaleState(Instant.EPOCH, "old", "new", 0, 0, 0));
    GraphStaleNotifier notifier = mock(GraphStaleNotifier.class);
    FetaruteTCAddon plugin = plugin();
    when(plugin.getRailGraphService()).thenReturn(service);
    when(plugin.getGraphStaleNotifier()).thenReturn(notifier);
    FtaGraphCommand command = new FtaGraphCommand(plugin);
    Method notifyRecovered =
        FtaGraphCommand.class.getDeclaredMethod("notifyRecovered", World.class, boolean.class);
    assertTrue(notifyRecovered.trySetAccessible());

    // 仍失效：不报恢复。
    notifyRecovered.invoke(command, world, true);
    verify(notifier, never()).onRecovered(any());

    service.putSnapshot(world, SimpleRailGraph.empty(), Instant.EPOCH);
    notifyRecovered.invoke(command, world, false);
    verify(notifier, never()).onRecovered(any());
    notifyRecovered.invoke(command, world, true);
    verify(notifier).onRecovered(world);
  }

  private static FetaruteTCAddon plugin() {
    FetaruteTCAddon plugin = mock(FetaruteTCAddon.class);
    when(plugin.getLogger()).thenReturn(Logger.getLogger("test"));
    return plugin;
  }

  private static StorageManager storage(RailNodeRepository nodeRepo, boolean ready) {
    StorageProvider provider = mock(StorageProvider.class);
    when(provider.railNodes()).thenReturn(nodeRepo);
    StorageManager storageManager = mock(StorageManager.class);
    when(storageManager.isReady()).thenReturn(ready);
    when(storageManager.provider()).thenReturn(Optional.of(provider));
    return storageManager;
  }

  private static RailNodeRecord record(UUID worldId, String id, int x) {
    return new RailNodeRecord(
        worldId, NodeId.of(id), NodeType.WAYPOINT, x, 64, 0, Optional.empty(), Optional.empty());
  }

  private static RailGraphBuildResult result(UUID worldId) {
    NodeId a = NodeId.of("A");
    NodeId b = NodeId.of("B");
    EdgeId edgeId = EdgeId.undirected(a, b);
    RailGraph graph =
        new SimpleRailGraph(
            Map.of(a, sign(a, 1), b, sign(b, 2)),
            Map.of(edgeId, new RailEdge(edgeId, a, b, 1, 0.0, true, Optional.empty())),
            Set.of());
    return new RailGraphBuildResult(
        graph,
        Instant.EPOCH,
        "sig",
        List.of(record(worldId, "A", 1), record(worldId, "B", 2)),
        List.of(),
        List.of());
  }

  private static SignRailNode sign(NodeId id, int x) {
    return new SignRailNode(
        id, NodeType.WAYPOINT, new Vector(x, 64, 0), Optional.empty(), Optional.empty());
  }

  private static Object cacheKey(UUID worldId) throws Exception {
    Class<?> keyType =
        Class.forName("org.fetarute.fetaruteTCAddon.command.FtaGraphCommand$GraphBuildCacheKey");
    Constructor<?> constructor = keyType.getDeclaredConstructor(UUID.class, Optional.class);
    assertTrue(constructor.trySetAccessible());
    return constructor.newInstance(worldId, Optional.empty());
  }

  @SuppressWarnings("unchecked")
  private static Map<Object, RailGraphBuildContinuation> continuations(FtaGraphCommand command)
      throws Exception {
    Field field = FtaGraphCommand.class.getDeclaredField("continuations");
    assertTrue(field.trySetAccessible());
    return (Map<Object, RailGraphBuildContinuation>) field.get(command);
  }
}
