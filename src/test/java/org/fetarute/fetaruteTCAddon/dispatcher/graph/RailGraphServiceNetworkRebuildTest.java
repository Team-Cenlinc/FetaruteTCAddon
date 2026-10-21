package org.fetarute.fetaruteTCAddon.dispatcher.graph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.World;
import org.bukkit.util.Vector;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.control.EdgeOverrideRailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.network.RailNetwork;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.persist.RailEdgeOverrideRecord;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.portal.PortalLink;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.portal.PortalLinkRegistry;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("图服务：跨世界路网按到期重建、后台构建")
class RailGraphServiceNetworkRebuildTest {

  private static final UUID OVER = UUID.fromString("00000000-0000-0000-0000-00000000000a");
  private static final UUID NETHER = UUID.fromString("00000000-0000-0000-0000-00000000000b");
  private static final NodeId A = NodeId.of("OP:S:AAA:1");
  private static final NodeId PA = NodeId.of("PORTAL:over:10:64:0");
  private static final NodeId PB = NodeId.of("PORTAL:nether:1:64:0");
  private static final NodeId B = NodeId.of("OP:S:BBB:1");
  private static final EdgeId PB_B = EdgeId.undirected(PB, B);
  private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

  private final SimpleRailGraph overworld = world(node(A, 0), node(PA, 10), 10);
  private final SimpleRailGraph netherworld = world(node(PB, 1), node(B, 30), 29);
  private final MutableClock clock = new MutableClock(T0);
  private final Deque<Runnable> background = new ArrayDeque<>();
  private final AtomicInteger changes = new AtomicInteger();
  private final PortalLinkRegistry links = new PortalLinkRegistry();
  private final RailGraphService service = new RailGraphService();

  RailGraphServiceNetworkRebuildTest() {
    World over = world(OVER);
    World nether = world(NETHER);
    service.putSnapshot(over, overworld, T0);
    service.putSnapshot(nether, netherworld, T0);
    links.put(new PortalLink(OVER, PA, NETHER, PB, PortalLink.Source.MANUAL, 4.0, Instant.EPOCH));
    service.setNetworkClock(clock);
    service.setBackgroundExecutor(background::add);
    service.addRuntimeGraphListener(changes::incrementAndGet);
  }

  @Test
  @DisplayName("跨世界关闭：运行时图就是本世界的图，不建路网、不提交后台任务")
  void crossWorldDisabledUnchanged() {
    service.configureCrossWorld(false, links);
    int before = changes.get();

    assertSame(overworld, RailGraphService.runtimeGraph(service, OVER, overworld));
    assertSame(overworld, service.runtimeQueryGraph(OVER, T0).orElseThrow());
    assertSame(overworld, service.getNetworkSnapshot(OVER).orElseThrow().graph());
    assertTrue(service.findCrossWorldPath(java.util.List.of(A, B)).isEmpty());
    clock.advance(Duration.ofHours(1));
    assertSame(overworld, RailGraphService.runtimeGraph(service, OVER, overworld));

    assertTrue(background.isEmpty());
    assertEquals(before, changes.get());
  }

  @Test
  @DisplayName("运行时查询图：有覆盖时叠在同一个底层实例上")
  void runtimeQueryGraphWrapsSameBase() {
    service.configureCrossWorld(false, links);
    service.putEdgeOverride(manualBlock(OVER, EdgeId.undirected(A, PA)));

    RailGraph query = service.runtimeQueryGraph(OVER, T0).orElseThrow();

    EdgeOverrideRailGraph view = assertInstanceOf(EdgeOverrideRailGraph.class, query);
    assertSame(overworld, view.delegate());
    assertTrue(query.isBlocked(EdgeId.undirected(A, PA)));
    assertTrue(service.runtimeQueryGraph(UUID.randomUUID(), T0).isEmpty());
  }

  @Test
  @DisplayName("没有定时封锁时不因时间重建")
  void noRebuildOverTimeWithoutTimedBlocks() {
    service.configureCrossWorld(true, links);
    RailGraph view = RailGraphService.runtimeGraph(service, OVER, overworld);
    assertInstanceOf(RailNetwork.View.class, view);

    clock.advance(Duration.ofHours(2));

    assertSame(view, RailGraphService.runtimeGraph(service, OVER, overworld));
    assertTrue(background.isEmpty());
  }

  @Test
  @DisplayName("别的世界新加的封锁立即生效")
  void newBlockInOtherWorldIsImmediate() {
    service.configureCrossWorld(true, links);
    RailGraph before = RailGraphService.runtimeGraph(service, OVER, overworld);
    assertFalse(before.isBlocked(PB_B));

    service.putEdgeOverride(manualBlock(NETHER, PB_B));
    RailGraph after = RailGraphService.runtimeGraph(service, OVER, overworld);

    assertNotSame(before, after);
    assertTrue(after.isBlocked(PB_B));
    assertTrue(background.isEmpty());
  }

  @Test
  @DisplayName("解除封锁：后台重建，建好前返回旧视图，换上后通知")
  void unblockRebuildsInBackground() {
    service.configureCrossWorld(true, links);
    service.putEdgeOverride(manualBlock(NETHER, PB_B));
    RailGraph blocked = RailGraphService.runtimeGraph(service, OVER, overworld);
    assertTrue(blocked.isBlocked(PB_B));

    service.deleteEdgeOverride(NETHER, PB_B);
    assertSame(blocked, RailGraphService.runtimeGraph(service, OVER, overworld));
    assertEquals(1, background.size());

    int before = changes.get();
    runBackground();
    RailGraph rebuilt = RailGraphService.runtimeGraph(service, OVER, overworld);

    assertNotSame(blocked, rebuilt);
    assertFalse(rebuilt.isBlocked(PB_B));
    assertEquals(before + 1, changes.get(), "路网换新后通知一次");
  }

  @Test
  @DisplayName("定时封锁到期才重建")
  void timedBlockExpiry() {
    service.configureCrossWorld(true, links);
    service.putEdgeOverride(timedBlock(NETHER, PB_B, T0.plusSeconds(60)));
    RailGraph blocked = RailGraphService.runtimeGraph(service, OVER, overworld);
    assertTrue(blocked.isBlocked(PB_B));

    clock.set(T0.plusSeconds(59));
    assertSame(blocked, RailGraphService.runtimeGraph(service, OVER, overworld));
    assertTrue(background.isEmpty());

    clock.set(T0.plusSeconds(60));
    assertSame(blocked, RailGraphService.runtimeGraph(service, OVER, overworld));
    runBackground();

    assertFalse(RailGraphService.runtimeGraph(service, OVER, overworld).isBlocked(PB_B));
  }

  @Test
  @DisplayName("删掉传送门连接立即退回本世界；换图同步重建")
  void linkRemovalAndGraphSwapAreImmediate() {
    service.configureCrossWorld(true, links);
    assertInstanceOf(
        RailNetwork.View.class, RailGraphService.runtimeGraph(service, OVER, overworld));

    SimpleRailGraph rebuiltNether = world(node(PB, 1), node(B, 40), 39);
    service.putSnapshot(world(NETHER), rebuiltNether, T0);
    RailGraph swapped = RailGraphService.runtimeGraph(service, OVER, overworld);
    assertEquals(40.0, swapped.findNode(B).orElseThrow().worldPosition().getX(), 1e-9, "新图立即可见");
    assertTrue(background.isEmpty());

    links.remove(PA);
    assertSame(overworld, RailGraphService.runtimeGraph(service, OVER, overworld));
    assertTrue(background.isEmpty());
  }

  @Test
  @DisplayName("快照激活、覆盖增删都会通知运行时图变化")
  void listenersFireOnGraphChanges() {
    int before = changes.get();
    service.putSnapshot(world(OVER), overworld, T0);
    service.putEdgeOverride(manualBlock(OVER, EdgeId.undirected(A, PA)));
    service.deleteEdgeOverride(OVER, EdgeId.undirected(A, PA));
    assertEquals(before + 3, changes.get());
  }

  private void runBackground() {
    while (!background.isEmpty()) {
      background.poll().run();
    }
  }

  private static World world(UUID id) {
    World world = mock(World.class);
    when(world.getUID()).thenReturn(id);
    return world;
  }

  private static RailNode node(NodeId id, double x) {
    NodeType type = id.value().startsWith("PORTAL") ? NodeType.PORTAL : NodeType.STATION;
    return new SignRailNode(id, type, new Vector(x, 64, 0), Optional.empty(), Optional.empty());
  }

  private static SimpleRailGraph world(RailNode first, RailNode second, int length) {
    EdgeId id = EdgeId.undirected(first.id(), second.id());
    return new SimpleRailGraph(
        Map.of(first.id(), first, second.id(), second),
        Map.of(id, new RailEdge(id, id.a(), id.b(), length, -1.0, true, Optional.empty())),
        Set.of());
  }

  private static RailEdgeOverrideRecord manualBlock(UUID worldId, EdgeId id) {
    return new RailEdgeOverrideRecord(
        worldId,
        id,
        OptionalDouble.empty(),
        OptionalDouble.empty(),
        Optional.empty(),
        true,
        Optional.empty(),
        T0);
  }

  private static RailEdgeOverrideRecord timedBlock(UUID worldId, EdgeId id, Instant until) {
    return new RailEdgeOverrideRecord(
        worldId,
        id,
        OptionalDouble.empty(),
        OptionalDouble.empty(),
        Optional.empty(),
        false,
        Optional.of(until),
        T0);
  }

  /** 可拨动的时钟。 */
  private static final class MutableClock extends Clock {
    private Instant now;

    MutableClock(Instant now) {
      this.now = now;
    }

    void set(Instant instant) {
      now = instant;
    }

    void advance(Duration duration) {
      now = now.plus(duration);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }
}
