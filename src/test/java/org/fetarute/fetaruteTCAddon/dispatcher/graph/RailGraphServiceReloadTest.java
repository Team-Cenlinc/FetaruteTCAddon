package org.fetarute.fetaruteTCAddon.dispatcher.graph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.World;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.build.RailGraphSignature;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.persist.RailEdgeRecord;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.persist.RailGraphSnapshotRecord;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.persist.RailNodeRecord;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.repository.RailComponentCautionRepository;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.repository.RailEdgeOverrideRepository;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.repository.RailEdgeRepository;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.repository.RailGraphSnapshotRepository;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.repository.RailInterlockingSnapshotRepository;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.repository.RailNodeRepository;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.storage.api.StorageProvider;
import org.junit.jupiter.api.Test;

/** 运行时从库重载（{@link RailGraphService#reloadFromStorage}）：组装图与切换前准备在后台，回到主线程核验后才切换。 */
class RailGraphServiceReloadTest {

  private static final NodeId A = NodeId.of("A");
  private static final NodeId B = NodeId.of("B");

  @Test
  void runtimeReloadBuildsAndPreparesInBackgroundThenSwitchesOnTheMainThread() {
    Fixture fixture = fixture();
    List<Runnable> background = new ArrayList<>();
    List<Runnable> main = new ArrayList<>();
    fixture.service().setBackgroundExecutor(background::add);
    AtomicInteger afterLoad = new AtomicInteger();

    fixture
        .service()
        .reloadFromStorage(
            fixture.provider(), fixture.world(), main::add, afterLoad::incrementAndGet);

    assertTrue(fixture.service().getSnapshot(fixture.worldId()).isEmpty(), "读完库还没切换");
    assertEquals(0, afterLoad.get());
    assertEquals(1, background.size());
    background.get(0).run();
    assertTrue(fixture.service().getSnapshot(fixture.worldId()).isEmpty(), "后台准备好也不切换，等主线程");
    assertEquals(1, main.size());

    main.get(0).run();

    RailGraph graph = fixture.service().getSnapshot(fixture.worldId()).orElseThrow().graph();
    assertTrue(graph.findNode(A).isPresent());
    assertTrue(((SimpleRailGraph) graph).indexesBuilt(), "切换前已在后台建好索引");
    assertTrue(fixture.service().getStaleState(fixture.world()).isEmpty(), "失效标记随切换撤掉");
    assertEquals(1, afterLoad.get());
  }

  @Test
  void runtimeReloadIsDroppedWhenTheWorldChangedMeanwhile() {
    Fixture fixture = fixture();
    List<Runnable> background = new ArrayList<>();
    List<Runnable> main = new ArrayList<>();
    fixture.service().setBackgroundExecutor(background::add);
    AtomicInteger afterLoad = new AtomicInteger();

    fixture
        .service()
        .reloadFromStorage(
            fixture.provider(), fixture.world(), main::add, afterLoad::incrementAndGet);
    SimpleRailGraph rebuilt = SimpleRailGraph.empty();
    fixture.service().putSnapshot(fixture.world(), rebuilt, Instant.now());
    background.get(0).run();
    main.get(0).run();

    assertSame(
        rebuilt,
        fixture.service().getSnapshot(fixture.worldId()).orElseThrow().graph(),
        "期间建图命令换上的图不被旧记录盖掉");
    assertEquals(1, afterLoad.get());
  }

  @Test
  void runtimeReloadWithoutExecutorsSwitchesSynchronously() {
    Fixture fixture = fixture();
    AtomicInteger afterLoad = new AtomicInteger();

    fixture
        .service()
        .reloadFromStorage(fixture.provider(), fixture.world(), null, afterLoad::incrementAndGet);

    assertTrue(fixture.service().getSnapshot(fixture.worldId()).isPresent());
    assertEquals(1, afterLoad.get());
  }

  private record Fixture(
      RailGraphService service, StorageProvider provider, World world, UUID worldId) {}

  /** 库里有 A–B 一条线、签名一致；内存里本世界的旧图已因失效移出。 */
  private static Fixture fixture() {
    UUID worldId = UUID.randomUUID();
    World world = mock(World.class);
    when(world.getUID()).thenReturn(worldId);
    when(world.getName()).thenReturn("world");
    List<RailNodeRecord> nodes = List.of(nodeRecord(worldId, A, 0), nodeRecord(worldId, B, 10));
    String signature = RailGraphSignature.signatureForNodes(nodes);

    RailNodeRepository nodeRepo = mock(RailNodeRepository.class);
    when(nodeRepo.listByWorld(worldId)).thenReturn(nodes);
    RailEdgeRepository edgeRepo = mock(RailEdgeRepository.class);
    when(edgeRepo.listByWorld(worldId))
        .thenReturn(List.of(new RailEdgeRecord(worldId, EdgeId.undirected(A, B), 10, 8.0, true)));
    RailEdgeOverrideRepository overrideRepo = mock(RailEdgeOverrideRepository.class);
    when(overrideRepo.listByWorld(worldId)).thenReturn(List.of());
    RailComponentCautionRepository cautionRepo = mock(RailComponentCautionRepository.class);
    when(cautionRepo.listByWorld(worldId)).thenReturn(List.of());
    RailGraphSnapshotRepository snapshotRepo = mock(RailGraphSnapshotRepository.class);
    when(snapshotRepo.findByWorld(worldId))
        .thenReturn(
            Optional.of(new RailGraphSnapshotRecord(worldId, Instant.EPOCH, 2, 1, signature)));
    RailInterlockingSnapshotRepository interlockingRepo =
        mock(RailInterlockingSnapshotRepository.class);
    when(interlockingRepo.findByWorld(worldId)).thenReturn(Optional.empty());

    StorageProvider provider = mock(StorageProvider.class);
    when(provider.railNodes()).thenReturn(nodeRepo);
    when(provider.railEdges()).thenReturn(edgeRepo);
    when(provider.railEdgeOverrides()).thenReturn(overrideRepo);
    when(provider.railComponentCautions()).thenReturn(cautionRepo);
    when(provider.railGraphSnapshots()).thenReturn(snapshotRepo);
    when(provider.railInterlockingSnapshots()).thenReturn(interlockingRepo);

    RailGraphService service = new RailGraphService();
    service.markStale(
        world,
        new RailGraphService.RailGraphStaleState(Instant.EPOCH, signature, "other", 2, 1, 3));
    return new Fixture(service, provider, world, worldId);
  }

  private static RailNodeRecord nodeRecord(UUID worldId, NodeId id, int x) {
    return new RailNodeRecord(
        worldId, id, NodeType.WAYPOINT, x, 64, 0, Optional.empty(), Optional.empty());
  }
}
