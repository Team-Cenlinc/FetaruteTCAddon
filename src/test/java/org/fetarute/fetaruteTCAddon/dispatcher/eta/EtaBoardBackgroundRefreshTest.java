package org.fetarute.fetaruteTCAddon.dispatcher.eta;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import org.bukkit.util.Vector;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.company.model.RouteStopPassType;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.runtime.TrainRuntimeSnapshot;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.runtime.TrainSnapshotStore;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailEdge;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphService;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.SignRailNode;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.SimpleRailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinitionCache;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteId;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteMetadata;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * 站牌后台刷新：新鲜时直接返回、过期先给旧值并只刷新一次、换图后不再给旧图的结果、冷启动与后台停用时在调用线程上现算。
 *
 * <p>线路 AAA → BBB → CCC，一列车停在 AAA；查 CCC 的站牌。时钟与执行器都由测试控制。
 */
class EtaBoardBackgroundRefreshTest {

  private static final NodeId AAA = NodeId.of("SURN:S:AAA:1");
  private static final NodeId BBB = NodeId.of("SURN:S:BBB:1");
  private static final NodeId CCC = NodeId.of("SURN:S:CCC:1");
  private static final Duration HORIZON = Duration.ofMinutes(10);

  private final Instant start = Instant.parse("2026-10-20T08:00:00Z");
  private final Instant[] clock = {start};
  private final UUID worldId = UUID.randomUUID();
  private final UUID routeUuid = UUID.randomUUID();
  private final AtomicLong graphVersion = new AtomicLong(1L);
  private final TrainSnapshotStore snapshots = new TrainSnapshotStore();
  private final ManualExecutor executor = new ManualExecutor();
  private final List<String> logs = new ArrayList<>();
  private EtaService service;

  @AfterEach
  void closeService() {
    if (service != null) {
      service.close();
    }
  }

  @Test
  void coldStartComputesOnTheCallingThread() {
    service = service();

    BoardResult board = board();

    assertEquals(1, board.rows().size());
    assertEquals(0, executor.pending(), "没有旧值：在调用线程上现算，不交后台");
    assertEquals(1L, service.boardStatsSnapshot().computes());
  }

  @Test
  void aFreshResultIsReturnedWithoutRefreshing() {
    service = service();
    BoardResult first = board();
    advance(Duration.ofMillis(1000));

    assertSame(first, board());
    assertEquals(0, executor.pending());
    assertEquals(1L, service.boardStatsSnapshot().cacheHits());
  }

  @Test
  void aStaleResultIsReturnedWhileOneRefreshRunsInTheBackground() {
    service = service();
    BoardResult first = board();
    advance(Duration.ofSeconds(2));
    snapshots.remove("train-1");

    assertSame(first, board(), "过期：先给旧值");
    assertSame(first, board(), "刷新还没跑完：仍给旧值");
    assertEquals(1, executor.pending(), "同一块站牌同一时刻只有一个刷新任务");

    executor.runAll();

    BoardResult refreshed = board();
    assertNotSame(first, refreshed);
    assertTrue(refreshed.rows().isEmpty(), "刷新后反映列车已经不在");
    assertEquals(0, executor.pending());
    EtaBoardStats.Snapshot stats = service.boardStatsSnapshot();
    assertEquals(1L, stats.computes(), "只有冷启动那一次在调用线程上算");
    assertEquals(2L, stats.staleHits());
    assertEquals(1L, stats.refreshes());
  }

  @Test
  void afterTheGraphChangesTheOldResultIsNotReturned() {
    service = service();
    BoardResult first = board();

    graphVersion.incrementAndGet();

    BoardResult afterChange = board();
    assertNotSame(first, afterChange, "换图后在调用线程上重算，不给旧图的结果");
    assertEquals(0, executor.pending());
    assertEquals(2L, service.boardStatsSnapshot().computes());
  }

  @Test
  void aRefreshComputedOnTheOldGraphIsDiscarded() {
    service = service();
    BoardResult first = board();
    advance(Duration.ofSeconds(2));
    assertSame(first, board());
    snapshots.remove("train-1");

    graphVersion.incrementAndGet();
    executor.runAll();

    BoardResult afterChange = board();
    assertTrue(afterChange.rows().isEmpty());
    assertEquals(2L, service.boardStatsSnapshot().computes(), "刷新结果按旧图算的，作废后在调用线程上现算");
    assertEquals(0, executor.pending());
  }

  @Test
  void aResultTooOldIsRecomputedOnTheCallingThread() {
    service = service();
    board();
    advance(EtaService.BOARD_MAX_STALE.plusSeconds(1));
    snapshots.remove("train-1");

    assertTrue(board().rows().isEmpty());
    assertEquals(0, executor.pending());
  }

  @Test
  void aSynchronousExecutorServesTheRefreshedResultOnTheNextQuery() {
    service = service();
    service.attachBoardExecutor(Runnable::run);
    BoardResult first = board();
    advance(Duration.ofSeconds(2));
    snapshots.remove("train-1");

    assertSame(first, board(), "这一次仍给旧值");
    assertTrue(board().rows().isEmpty(), "刷新已在执行器里跑完，下一次拿到新结果");
    assertEquals(1L, service.boardStatsSnapshot().refreshes());
  }

  @Test
  void afterCloseBoardsAreComputedOnTheCallingThread() {
    service = service();
    board();
    service.close();
    advance(Duration.ofSeconds(2));
    snapshots.remove("train-1");

    assertTrue(board().rows().isEmpty(), "停用后过期即现算，与缓存过期即重算相同");
    assertEquals(0, executor.pending());
    assertEquals(2L, service.boardStatsSnapshot().computes());
  }

  @Test
  void aRejectingExecutorFallsBackToTheCallingThread() {
    service = service();
    service.attachBoardExecutor(
        task -> {
          throw new RejectedExecutionException("shut down");
        });
    board();
    advance(Duration.ofSeconds(2));
    snapshots.remove("train-1");

    assertTrue(board().rows().isEmpty());
    assertEquals(2L, service.boardStatsSnapshot().computes());
  }

  @Test
  void aRefreshTheExecutorNeverRanIsHandedOffAgain() {
    service = service();
    List<Runnable> dropped = new ArrayList<>();
    service.attachBoardExecutor(dropped::add);
    board();
    advance(Duration.ofSeconds(2));
    board();
    assertEquals(1, dropped.size());

    advance(EtaService.BOARD_MAX_STALE);
    board();
    advance(Duration.ofSeconds(2));
    board();

    assertEquals(2, dropped.size(), "上一次交出的任务一直没跑：超过旧值上限后接手重交");
  }

  @Test
  void aFailedCaptureKeepsServingTheStaleResult() {
    service = service();
    BoardResult first = board();
    service.attachStationPresence(
        () -> {
          throw new IllegalStateException("presence unavailable");
        });
    advance(Duration.ofSeconds(2));

    assertSame(first, board());
    assertSame(first, board(), "拷贝失败后不留刷新标记，下一次照样重试");
    assertEquals(0, executor.pending());
    assertTrue(logs.stream().anyMatch(line -> line.startsWith("ETA_BOARD_CAPTURE_FAILED")));
  }

  @Test
  void refreshesAtTheSameMomentShareOneInputCapture() {
    service = service();
    service.getBoard("SURN", "BBB", null, HORIZON);
    service.getBoard("SURN", "CCC", null, HORIZON);
    long capturesAfterColdStart = service.boardStatsSnapshot().captures();
    advance(Duration.ofSeconds(2));

    service.getBoard("SURN", "BBB", null, HORIZON);
    service.getBoard("SURN", "CCC", null, HORIZON);

    assertEquals(2, executor.pending(), "两块站牌各一个刷新任务");
    assertEquals(
        capturesAfterColdStart + 1, service.boardStatsSnapshot().captures(), "同一时刻的刷新共用一份输入");
  }

  @Test
  void theOwnBackgroundThreadRefreshesWithoutBeingAttached() throws InterruptedException {
    service = new EtaService(snapshots, graphs(), routes(), () -> clock[0]);
    snapshots.update("train-1", train());
    BoardResult first = board();
    advance(Duration.ofSeconds(2));
    snapshots.remove("train-1");

    assertSame(first, board());
    long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    while (service.boardStatsSnapshot().refreshes() == 0L && System.nanoTime() < deadline) {
      Thread.sleep(5L);
    }

    assertEquals(1L, service.boardStatsSnapshot().refreshes(), "自有后台线程跑完了刷新");
    assertTrue(board().rows().isEmpty());
  }

  private BoardResult board() {
    return service.getBoard("SURN", "CCC", null, HORIZON);
  }

  private void advance(Duration duration) {
    clock[0] = clock[0].plus(duration);
  }

  private EtaService service() {
    EtaService created = new EtaService(snapshots, graphs(), routes(), () -> clock[0]);
    created.attachBoardExecutor(executor);
    created.attachDebugLogger(logs::add);
    snapshots.update("train-1", train());
    return created;
  }

  private RouteDefinitionCache routes() {
    RouteDefinition route =
        new RouteDefinition(
            RouteId.of("SURN:L1:R1"),
            List.of(AAA, BBB, CCC),
            Optional.of(RouteMetadata.of("SURN", "L1", "R1", null)));
    RouteDefinitionCache routes = mock(RouteDefinitionCache.class);
    when(routes.findById(routeUuid)).thenReturn(Optional.of(route));
    when(routes.listStops(any()))
        .thenReturn(
            List.of(
                stop(0, AAA, RouteStopPassType.STOP),
                stop(1, BBB, RouteStopPassType.STOP),
                stop(2, CCC, RouteStopPassType.TERMINATE)));
    return routes;
  }

  private RailGraphService graphs() {
    RailGraphService graphs = mock(RailGraphService.class);
    when(graphs.getSnapshot(worldId))
        .thenReturn(
            Optional.of(new RailGraphService.RailGraphSnapshot(linearGraph(), Instant.EPOCH)));
    when(graphs.snapshotVersion()).thenAnswer(invocation -> graphVersion.get());
    return graphs;
  }

  private TrainRuntimeSnapshot train() {
    return new TrainRuntimeSnapshot(
        1L,
        start,
        worldId,
        routeUuid,
        RouteId.of("SURN:L1:R1"),
        0,
        Optional.of(AAA),
        Optional.of(AAA),
        Optional.empty(),
        Optional.of(SignalAspect.PROCEED),
        Optional.empty());
  }

  private RouteStop stop(int sequence, NodeId node, RouteStopPassType passType) {
    return new RouteStop(
        routeUuid,
        sequence,
        Optional.empty(),
        Optional.of(node.value()),
        Optional.empty(),
        passType,
        Optional.empty());
  }

  private static RailGraph linearGraph() {
    List<NodeId> all = List.of(AAA, BBB, CCC);
    Map<NodeId, RailNode> nodes = new HashMap<>();
    Map<EdgeId, RailEdge> edges = new HashMap<>();
    for (int i = 0; i < all.size(); i++) {
      nodes.put(
          all.get(i),
          new SignRailNode(
              all.get(i),
              NodeType.WAYPOINT,
              new Vector(i * 10, 0, 0),
              Optional.empty(),
              Optional.empty()));
      if (i > 0) {
        EdgeId id = EdgeId.undirected(all.get(i - 1), all.get(i));
        edges.put(
            id, new RailEdge(id, all.get(i - 1), all.get(i), 60, 0.0, true, Optional.empty()));
      }
    }
    return new SimpleRailGraph(nodes, edges, Set.of());
  }

  /** 攒下任务，由测试手动执行。 */
  private static final class ManualExecutor implements Executor {
    private final Deque<Runnable> tasks = new ArrayDeque<>();

    @Override
    public void execute(Runnable command) {
      tasks.add(command);
    }

    int pending() {
      return tasks.size();
    }

    void runAll() {
      while (!tasks.isEmpty()) {
        tasks.poll().run();
      }
    }
  }
}
