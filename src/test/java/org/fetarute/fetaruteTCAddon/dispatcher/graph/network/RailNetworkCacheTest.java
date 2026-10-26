package org.fetarute.fetaruteTCAddon.dispatcher.graph.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.bukkit.util.Vector;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailEdge;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.SignRailNode;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.SimpleRailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.persist.RailEdgeOverrideRecord;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.portal.PortalLink;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("跨世界路网缓存：按到期重建、后台构建")
class RailNetworkCacheTest {

  private static final UUID OVER = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final UUID NETHER = UUID.fromString("00000000-0000-0000-0000-000000000002");
  private static final NodeId A = NodeId.of("OP:S:AAA:1");
  private static final NodeId X = NodeId.of("OP:S:XXX:1");
  private static final NodeId PA = NodeId.of("PORTAL:over:10:64:0");
  private static final NodeId PB = NodeId.of("PORTAL:nether:1:64:0");
  private static final NodeId B = NodeId.of("OP:S:BBB:1");
  private static final EdgeId A_PA = EdgeId.undirected(A, PA);
  private static final EdgeId X_PA = EdgeId.undirected(X, PA);
  private static final EdgeId PORTAL = EdgeId.undirected(PA, PB);
  private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

  private static final SimpleRailGraph OVERWORLD = overworld();
  private static final SimpleRailGraph NETHERWORLD = netherworld();
  private static final List<PortalLink> LINKS = List.of(link(PA, PB));

  private final MutableClock clock = new MutableClock(T0);
  private final Deque<Runnable> background = new ArrayDeque<>();
  private final AtomicInteger installs = new AtomicInteger();
  private final RailNetworkCache cache = new RailNetworkCache(installs::incrementAndGet, m -> {});

  RailNetworkCacheTest() {
    cache.setClock(clock);
    cache.setExecutor(background::add);
  }

  @Test
  @DisplayName("冷启动同步建，没有封锁时不因时间重建")
  void noTimeRebuildWithoutTimedBlocks() {
    RailNetwork first = cache.get(1, 1, () -> inputs(1, 1, Map.of(), LINKS));
    assertTrue(background.isEmpty(), "冷启动同步建");
    assertEquals(1, installs.get());

    clock.advance(Duration.ofHours(1));
    RailNetwork later = cache.get(1, 1, () -> inputs(1, 1, Map.of(), LINKS));

    assertSame(first, later);
    assertTrue(background.isEmpty());
    assertEquals(1, installs.get());
  }

  @Test
  @DisplayName("手动封锁不随时间解除，不因时间重建")
  void manualBlockNeverExpires() {
    Map<EdgeId, RailEdgeOverrideRecord> overrides =
        Map.of(A_PA, block(A_PA, true, Optional.of(T0.plusSeconds(60))));
    RailNetwork first = cache.get(1, 1, () -> inputs(1, 1, overrides, LINKS));

    clock.advance(Duration.ofHours(1));

    assertSame(first, cache.get(1, 1, () -> inputs(1, 1, overrides, LINKS)));
    assertTrue(background.isEmpty());
    assertTrue(first.view(NETHER).isBlocked(A_PA));
  }

  @Test
  @DisplayName("定时封锁到期才重建：放到后台，期间旧网照用")
  void timedBlockExpiryRebuildsInBackground() {
    Map<EdgeId, RailEdgeOverrideRecord> overrides =
        Map.of(A_PA, block(A_PA, false, Optional.of(T0.plusSeconds(60))));
    Supplier<RailNetworkCache.Inputs> in = () -> inputs(1, 1, overrides, LINKS);
    RailNetwork first = cache.get(1, 1, in);
    assertTrue(first.view(NETHER).isBlocked(A_PA));

    clock.set(T0.plusSeconds(30));
    assertSame(first, cache.get(1, 1, in), "没到期不重建");
    assertTrue(background.isEmpty());

    clock.set(T0.plusSeconds(60));
    assertSame(first, cache.get(1, 1, in), "到期：后台建好之前旧网照用");
    assertSame(first, cache.get(1, 1, in));
    assertEquals(1, background.size(), "同时只有一个后台重建");

    runBackground();
    RailNetwork rebuilt = cache.get(1, 1, in);

    assertNotSame(first, rebuilt);
    assertFalse(rebuilt.view(NETHER).isBlocked(A_PA));
    assertEquals(2, installs.get());
    clock.advance(Duration.ofHours(1));
    assertSame(rebuilt, cache.get(1, 1, in), "到期的封锁解除后不再有定时重建");
  }

  @Test
  @DisplayName("版本变了但只是解除封锁：后台重建，期间返回旧网，判定只做一次")
  void unblockRebuildsInBackgroundAndServesOld() {
    Map<EdgeId, RailEdgeOverrideRecord> blocked = Map.of(A_PA, block(A_PA, true, Optional.empty()));
    RailNetwork first = cache.get(1, 1, () -> inputs(1, 1, blocked, LINKS));

    AtomicInteger collected = new AtomicInteger();
    Supplier<RailNetworkCache.Inputs> unblocked =
        () -> {
          collected.incrementAndGet();
          return inputs(2, 1, Map.of(), LINKS);
        };
    for (int i = 0; i < 5; i++) {
      assertSame(first, cache.get(2, 1, unblocked));
    }
    assertEquals(1, collected.get(), "判定能用旧网后，在建期间不再重复采集");
    assertEquals(1, background.size());
    assertTrue(first.view(NETHER).isBlocked(A_PA), "旧网多封着一条边，只会让车多等");

    runBackground();
    RailNetwork rebuilt = cache.get(2, 1, unblocked);

    assertNotSame(first, rebuilt);
    assertFalse(rebuilt.view(NETHER).isBlocked(A_PA));
  }

  @Test
  @DisplayName("新加的封锁立即生效：同步重建，不等后台")
  void newBlockTakesEffectImmediately() {
    RailNetwork first = cache.get(1, 1, () -> inputs(1, 1, Map.of(), LINKS));

    Map<EdgeId, RailEdgeOverrideRecord> blocked =
        Map.of(A_PA, block(A_PA, false, Optional.of(T0.plusSeconds(600))));
    RailNetwork now = cache.get(2, 1, () -> inputs(2, 1, blocked, LINKS));

    assertNotSame(first, now);
    assertTrue(now.view(NETHER).isBlocked(A_PA));
    assertTrue(background.isEmpty());
  }

  @Test
  @DisplayName("后台在建时又加了封锁：同步重建的那份为准，后台结果丢弃")
  void inFlightRebuildDiscardedWhenSyncRebuildWins() {
    Map<EdgeId, RailEdgeOverrideRecord> timed =
        Map.of(A_PA, block(A_PA, false, Optional.of(T0.plusSeconds(60))));
    cache.get(1, 1, () -> inputs(1, 1, timed, LINKS));
    clock.set(T0.plusSeconds(61));
    cache.get(1, 1, () -> inputs(1, 1, timed, LINKS));
    assertEquals(1, background.size());

    Map<EdgeId, RailEdgeOverrideRecord> both =
        Map.of(A_PA, timed.get(A_PA), X_PA, block(X_PA, true, Optional.empty()));
    RailNetwork synced = cache.get(2, 1, () -> inputs(2, 1, both, LINKS));
    assertTrue(synced.view(NETHER).isBlocked(X_PA));
    assertFalse(synced.view(NETHER).isBlocked(A_PA), "同步重建按此刻的时间算，到期的已解除");

    runBackground();

    assertSame(synced, cache.get(2, 1, () -> inputs(2, 1, both, LINKS)));
    assertEquals(2, installs.get(), "后台那份没有换上");
  }

  @Test
  @DisplayName("换了图（实例不同）同步重建")
  void replacedGraphRebuildsSynchronously() {
    RailNetwork first = cache.get(1, 1, () -> inputs(1, 1, Map.of(), LINKS));
    SimpleRailGraph rebuilt = overworld();

    RailNetwork now =
        cache.get(
            2,
            1,
            () ->
                new RailNetworkCache.Inputs(
                    2, 1, Map.of(OVER, rebuilt, NETHER, NETHERWORLD), Map.of(), LINKS));

    assertNotSame(first, now);
    assertTrue(background.isEmpty());
  }

  @Test
  @DisplayName("删了传送门连接同步重建；加连接放后台")
  void portalLinkChanges() {
    RailNetwork withPortal = cache.get(1, 1, () -> inputs(1, 1, Map.of(), LINKS));
    assertTrue(withPortal.hasPortalEdges());

    RailNetwork removed = cache.get(1, 2, () -> inputs(1, 2, Map.of(), List.of()));
    assertFalse(removed.hasPortalEdges(), "旧网会穿过已删的门，不能再用");
    assertTrue(background.isEmpty());

    RailNetwork stillOld = cache.get(1, 3, () -> inputs(1, 3, Map.of(), LINKS));
    assertSame(removed, stillOld, "少一条新连接只是暂时绕不过去");
    assertEquals(1, background.size());

    runBackground();
    RailNetwork added = cache.get(1, 3, () -> inputs(1, 3, Map.of(), LINKS));
    assertTrue(added.hasPortalEdges());
    assertTrue(added.view(OVER).findEdge(PORTAL).isPresent());
  }

  @Test
  @DisplayName("没设执行器时一律同步重建")
  void noExecutorAlwaysSynchronous() {
    cache.setExecutor(null);
    Map<EdgeId, RailEdgeOverrideRecord> timed =
        Map.of(A_PA, block(A_PA, false, Optional.of(T0.plusSeconds(60))));
    RailNetwork first = cache.get(1, 1, () -> inputs(1, 1, timed, LINKS));

    clock.set(T0.plusSeconds(60));
    RailNetwork expired = cache.get(1, 1, () -> inputs(1, 1, timed, LINKS));
    assertNotSame(first, expired);
    assertFalse(expired.view(NETHER).isBlocked(A_PA));

    RailNetwork changed = cache.get(2, 1, () -> inputs(2, 1, Map.of(), LINKS));
    assertNotSame(expired, changed);
    assertTrue(background.isEmpty());
  }

  @Test
  @DisplayName("reset 后同步重建，在建的后台结果不换上")
  void resetDropsInFlightRebuild() {
    Map<EdgeId, RailEdgeOverrideRecord> timed =
        Map.of(A_PA, block(A_PA, false, Optional.of(T0.plusSeconds(60))));
    cache.get(1, 1, () -> inputs(1, 1, timed, LINKS));
    clock.set(T0.plusSeconds(61));
    cache.get(1, 1, () -> inputs(1, 1, timed, LINKS));
    assertEquals(1, background.size());

    cache.reset();
    RailNetwork fresh = cache.get(1, 1, () -> inputs(1, 1, timed, LINKS));
    runBackground();

    assertSame(fresh, cache.get(1, 1, () -> inputs(1, 1, timed, LINKS)));
  }

  private void runBackground() {
    while (!background.isEmpty()) {
      background.poll().run();
    }
  }

  private static RailNetworkCache.Inputs inputs(
      long version,
      long revision,
      Map<EdgeId, RailEdgeOverrideRecord> overworldOverrides,
      List<PortalLink> links) {
    return new RailNetworkCache.Inputs(
        version,
        revision,
        Map.of(OVER, OVERWORLD, NETHER, NETHERWORLD),
        Map.of(OVER, overworldOverrides, NETHER, Map.of()),
        links);
  }

  private static RailEdgeOverrideRecord block(
      EdgeId id, boolean manual, Optional<Instant> blockedUntil) {
    return new RailEdgeOverrideRecord(
        OVER,
        id,
        OptionalDouble.empty(),
        OptionalDouble.empty(),
        Optional.empty(),
        manual,
        blockedUntil,
        T0);
  }

  private static PortalLink link(NodeId from, NodeId to) {
    return new PortalLink(OVER, from, NETHER, to, PortalLink.Source.MANUAL, 4.0, Instant.EPOCH);
  }

  /** A–PA、X–PA 两条进门线。 */
  private static SimpleRailGraph overworld() {
    RailNode a = node(A, NodeType.STATION, 0);
    RailNode x = node(X, NodeType.STATION, 20);
    RailNode pa = node(PA, NodeType.PORTAL, 10);
    return new SimpleRailGraph(
        Map.of(A, a, X, x, PA, pa), Map.of(A_PA, edge(A, PA, 10), X_PA, edge(X, PA, 10)), Set.of());
  }

  private static SimpleRailGraph netherworld() {
    RailNode pb = node(PB, NodeType.PORTAL, 1);
    RailNode b = node(B, NodeType.STATION, 30);
    return new SimpleRailGraph(
        Map.of(PB, pb, B, b), Map.of(EdgeId.undirected(PB, B), edge(PB, B, 29)), Set.of());
  }

  private static RailNode node(NodeId id, NodeType type, double x) {
    return new SignRailNode(id, type, new Vector(x, 64, 0), Optional.empty(), Optional.empty());
  }

  private static RailEdge edge(NodeId a, NodeId b, int length) {
    return new RailEdge(EdgeId.undirected(a, b), a, b, length, -1.0, true, Optional.empty());
  }

  /** 可拨动的时钟。 */
  static final class MutableClock extends Clock {
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
