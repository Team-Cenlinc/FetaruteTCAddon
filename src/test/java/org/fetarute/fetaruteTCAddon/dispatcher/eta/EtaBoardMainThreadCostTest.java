package org.fetarute.fetaruteTCAddon.dispatcher.eta;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import org.bukkit.World;
import org.bukkit.util.Vector;
import org.fetarute.fetaruteTCAddon.company.model.Line;
import org.fetarute.fetaruteTCAddon.company.model.Operator;
import org.fetarute.fetaruteTCAddon.company.model.Route;
import org.fetarute.fetaruteTCAddon.company.model.RouteOperationType;
import org.fetarute.fetaruteTCAddon.company.model.RoutePatternType;
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
import org.fetarute.fetaruteTCAddon.dispatcher.node.WaypointMetadata;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinitionCache;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteId;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteMetadata;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.model.TripSource;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspect;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SpawnForecastSupport;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SpawnManager;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SpawnService;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SpawnServiceKey;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SpawnTicket;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.TicketAssigner;
import org.junit.jupiter.api.Test;

/**
 * 合成场景下站牌查询在调用线程上的耗时：只打印（见测试报告的标准输出），不对时长断言。
 *
 * <p>场景：一条 20 站的线路，站间各 40 个区间点，每站前后各一个咽喉；另有 2000 个不相连的区间点，模拟全网规模。上下行各 20 列车分布在各站间， 另有 6 张待发票据与 10
 * 张预测票据。查询中间站的站牌。
 */
class EtaBoardMainThreadCostTest {

  private static final String OP = "SURN";
  private static final int STATIONS = 20;
  private static final int FILLER_PER_SEGMENT = 40;
  private static final int ISOLATED_FILLER = 2000;
  private static final int TRAINS_PER_DIRECTION = 20;
  private static final int WARMUP = 30;
  private static final int SAMPLES = 100;

  private final UUID worldId = UUID.randomUUID();
  private final UUID upUuid = UUID.randomUUID();
  private final UUID downUuid = UUID.randomUUID();
  private final Instant now = Instant.now();

  @Test
  void coldBoardComputeOnTheCallingThread() {
    Scenario scenario = new Scenario();
    EtaService service = scenario.service();

    for (int i = 0; i < WARMUP; i++) {
      service.getBoard(OP, station(STATIONS / 2), null, Duration.ofMinutes(30).plusMillis(i));
    }
    long[] samples = new long[SAMPLES];
    int rows = 0;
    for (int i = 0; i < SAMPLES; i++) {
      Duration horizon = Duration.ofMinutes(30).plusMillis(WARMUP + i);
      long start = System.nanoTime();
      BoardResult board = service.getBoard(OP, station(STATIONS / 2), null, horizon);
      samples[i] = System.nanoTime() - start;
      rows = board.rows().size();
    }

    assertFalse(rows == 0, "前置：站牌应有行，否则测的不是完整重算");
    print("cold", samples, rows);
  }

  /**
   * 过期后的查询：调用线程上只拷贝输入、交出刷新任务（任务由测试在计时之外执行，相当于后台线程）。
   *
   * <p>单站：每轮 2 秒后查一次，每次都要新拷贝一份输入。十站：每轮同一时刻查十个站，第一站拷贝、其余共用，按每站平均计。
   */
  @Test
  void staleBoardOnTheCallingThread() {
    Scenario scenario = new Scenario();
    Instant[] clock = {now};
    EtaService service = scenario.service(() -> clock[0]);
    List<Runnable> tasks = new ArrayList<>();
    service.attachBoardExecutor(tasks::add);
    String target = station(STATIONS / 2);
    service.getBoard(OP, target, null, Duration.ofMinutes(30));

    long[] samples = new long[SAMPLES];
    int rows = 0;
    for (int i = 0; i < WARMUP + SAMPLES; i++) {
      clock[0] = clock[0].plusSeconds(2);
      long start = System.nanoTime();
      BoardResult board = service.getBoard(OP, target, null, Duration.ofMinutes(30));
      long elapsed = System.nanoTime() - start;
      if (i >= WARMUP) {
        samples[i - WARMUP] = elapsed;
      }
      rows = board.rows().size();
      tasks.forEach(Runnable::run);
      tasks.clear();
    }
    assertFalse(rows == 0, "前置：站牌应有行");
    print("stale-1-station", samples, rows);

    List<String> stations = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      stations.add(station(i * 2));
      service.getBoard(OP, station(i * 2), null, Duration.ofMinutes(30));
    }
    long[] perStation = new long[SAMPLES];
    for (int i = 0; i < WARMUP + SAMPLES; i++) {
      clock[0] = clock[0].plusSeconds(2);
      long start = System.nanoTime();
      for (String code : stations) {
        service.getBoard(OP, code, null, Duration.ofMinutes(30));
      }
      long elapsed = (System.nanoTime() - start) / stations.size();
      if (i >= WARMUP) {
        perStation[i - WARMUP] = elapsed;
      }
      tasks.forEach(Runnable::run);
      tasks.clear();
    }
    print("stale-10-stations-per-station", perStation, rows);
    EtaBoardStats.Snapshot stats = service.boardStatsSnapshot();
    System.out.println(
        String.format(
            Locale.ROOT,
            "ETA_BOARD_COST stats captures=%d avgCapture=%.3fms refreshes=%d avgRefresh=%.3fms",
            stats.captures(),
            stats.averageCaptureMillis(),
            stats.refreshes(),
            stats.averageRefreshMillis()));
  }

  static void print(String label, long[] samples, int rows) {
    long[] sorted = samples.clone();
    Arrays.sort(sorted);
    double mean = Arrays.stream(sorted).average().orElse(0.0) / 1_000_000.0;
    System.out.println(
        String.format(
            Locale.ROOT,
            "ETA_BOARD_COST %s samples=%d rows=%d mean=%.3fms p50=%.3fms p95=%.3fms max=%.3fms",
            label,
            sorted.length,
            rows,
            mean,
            sorted[sorted.length / 2] / 1_000_000.0,
            sorted[(int) Math.floor(sorted.length * 0.95)] / 1_000_000.0,
            sorted[sorted.length - 1] / 1_000_000.0));
  }

  static String station(int index) {
    return "ST" + index;
  }

  static NodeId platform(int index) {
    return NodeId.of(OP + ":S:" + station(index) + ":1");
  }

  /** 合成线路、列车、票据与 ETA 服务。 */
  final class Scenario {
    final RailGraph graph;
    final TrainSnapshotStore snapshots = new TrainSnapshotStore();
    final RouteDefinitionCache routes = mock(RouteDefinitionCache.class, withSettings().stubOnly());
    final RailGraphService graphs = new RailGraphService();
    final Map<Integer, List<NodeId>> fillers = new HashMap<>();
    final RouteDefinition up;
    final RouteDefinition down;

    Scenario() {
      graph = buildGraph();
      World world = mock(World.class, withSettings().stubOnly());
      when(world.getUID()).thenReturn(worldId);
      graphs.putSnapshot(world, graph, now);

      List<NodeId> upNodes = new ArrayList<>();
      for (int i = 0; i < STATIONS; i++) {
        upNodes.add(platform(i));
      }
      List<NodeId> downNodes = new ArrayList<>(upNodes);
      java.util.Collections.reverse(downNodes);
      up =
          new RouteDefinition(
              RouteId.of(OP + ":L1:UP"),
              upNodes,
              Optional.of(RouteMetadata.of(OP, "L1", "UP", null)));
      down =
          new RouteDefinition(
              RouteId.of(OP + ":L1:DOWN"),
              downNodes,
              Optional.of(RouteMetadata.of(OP, "L1", "DOWN", null)));
      when(routes.findById(upUuid)).thenReturn(Optional.of(up));
      when(routes.findById(downUuid)).thenReturn(Optional.of(down));
      when(routes.listStops(up.id())).thenReturn(stops(upUuid, upNodes));
      when(routes.listStops(down.id())).thenReturn(stops(downUuid, downNodes));
      when(routes.findRecord(upUuid)).thenReturn(Optional.of(record(upUuid, "UP")));
      when(routes.findRecord(downUuid)).thenReturn(Optional.of(record(downUuid, "DOWN")));

      for (int k = 0; k < TRAINS_PER_DIRECTION; k++) {
        int index = k % (STATIONS - 1);
        snapshots.update("up-" + k, train(upUuid, up.id(), index, fillers.get(index).get(5)));
        int segment = STATIONS - 2 - index;
        snapshots.update(
            "down-" + k,
            train(downUuid, down.id(), index, fillers.get(segment).get(FILLER_PER_SEGMENT - 6)));
      }
    }

    EtaService service() {
      return service(java.time.InstantSource.system());
    }

    EtaService service(java.time.InstantSource clock) {
      EtaService service = new EtaService(snapshots, graphs, routes, clock);
      SpawnManager manager =
          mock(
              SpawnManager.class,
              withSettings().stubOnly().extraInterfaces(SpawnForecastSupport.class));
      List<SpawnTicket> pending = new ArrayList<>();
      for (int i = 0; i < 6; i++) {
        pending.add(ticket(i % 2 == 0 ? upUuid : downUuid, now.plusSeconds(60L + 90L * i)));
      }
      List<SpawnTicket> forecast = new ArrayList<>();
      for (int i = 0; i < 10; i++) {
        forecast.add(ticket(i % 2 == 0 ? upUuid : downUuid, now.plusSeconds(600L + 120L * i)));
      }
      when(manager.snapshotQueue()).thenReturn(pending);
      when(((SpawnForecastSupport) manager).snapshotForecast(any(), any(), anyInt()))
          .thenReturn(forecast);
      TicketAssigner assigner = mock(TicketAssigner.class, withSettings().stubOnly());
      when(assigner.snapshotPendingTickets()).thenReturn(List.of());
      service.attachTicketSources(manager, assigner);
      service.attachPlannedArrivals((name, index) -> Optional.of(now.plusSeconds(120L * index)));
      return service;
    }

    private RailGraph buildGraph() {
      Map<NodeId, RailNode> nodes = new HashMap<>();
      Map<EdgeId, RailEdge> edges = new HashMap<>();
      NodeId previous = null;
      int x = 0;
      for (int i = 0; i < STATIONS; i++) {
        NodeId before = NodeId.of(OP + ":S:" + station(i) + ":1:1");
        NodeId stationNode = platform(i);
        NodeId after = NodeId.of(OP + ":S:" + station(i) + ":1:2");
        for (NodeId node : List.of(before, stationNode, after)) {
          WaypointMetadata meta =
              node == stationNode
                  ? WaypointMetadata.station(OP, station(i), 1)
                  : WaypointMetadata.stationThroat(
                      OP, station(i), 1, node.value().substring(node.value().length() - 1));
          nodes.put(node, node(node, x, meta));
          previous = link(edges, previous, node, 20);
          x += 20;
        }
        if (i + 1 < STATIONS) {
          List<NodeId> segment = new ArrayList<>();
          for (int k = 0; k < FILLER_PER_SEGMENT; k++) {
            NodeId filler =
                NodeId.of(OP + ":" + station(i) + ":" + station(i + 1) + ":1:" + (k + 1));
            nodes.put(
                filler,
                node(
                    filler,
                    x,
                    WaypointMetadata.interval(
                        OP, station(i), station(i + 1), 1, String.valueOf(k + 1))));
            previous = link(edges, previous, filler, 30);
            x += 30;
            segment.add(filler);
          }
          fillers.put(i, segment);
        }
      }
      NodeId chain = null;
      for (int k = 0; k < ISOLATED_FILLER; k++) {
        NodeId filler = NodeId.of(OP + ":XA:XB:2:" + (k + 1));
        nodes.put(
            filler,
            node(filler, k * 10, WaypointMetadata.interval(OP, "XA", "XB", 2, "" + (k + 1))));
        chain = link(edges, chain, filler, 10);
      }
      return new SimpleRailGraph(nodes, edges, Set.of());
    }

    private RailNode node(NodeId id, int x, WaypointMetadata meta) {
      return new SignRailNode(
          id, NodeType.WAYPOINT, new Vector(x, 64, 0), Optional.empty(), Optional.of(meta));
    }

    private NodeId link(Map<EdgeId, RailEdge> edges, NodeId from, NodeId to, int length) {
      if (from != null) {
        EdgeId edgeId = EdgeId.undirected(from, to);
        edges.put(edgeId, new RailEdge(edgeId, from, to, length, 0.0, true, Optional.empty()));
      }
      return to;
    }

    private TrainRuntimeSnapshot train(
        UUID routeUuid, RouteId routeId, int routeIndex, NodeId lastPassed) {
      return new TrainRuntimeSnapshot(
          1L,
          now,
          worldId,
          routeUuid,
          routeId,
          routeIndex,
          Optional.of(lastPassed),
          Optional.of(lastPassed),
          Optional.empty(),
          Optional.of(SignalAspect.PROCEED),
          Optional.empty(),
          OptionalDouble.of(6.0),
          OptionalInt.empty(),
          OptionalInt.empty(),
          OptionalDouble.of(5.0),
          TrainRuntimeSnapshot.HoldTimeline.EMPTY,
          Optional.empty());
    }

    private List<RouteStop> stops(UUID routeUuid, List<NodeId> nodes) {
      List<RouteStop> out = new ArrayList<>();
      for (int i = 0; i < nodes.size(); i++) {
        out.add(
            new RouteStop(
                routeUuid,
                i,
                Optional.empty(),
                Optional.of(nodes.get(i).value()),
                Optional.empty(),
                i == nodes.size() - 1 ? RouteStopPassType.TERMINATE : RouteStopPassType.STOP,
                Optional.empty()));
      }
      return List.copyOf(out);
    }

    private RouteDefinitionCache.RouteRecord record(UUID routeUuid, String code) {
      return new RouteDefinitionCache.RouteRecord(
          mock(Operator.class, withSettings().stubOnly()),
          mock(Line.class, withSettings().stubOnly()),
          new Route(
              routeUuid,
              code,
              UUID.randomUUID(),
              code,
              Optional.empty(),
              RoutePatternType.LOCAL,
              RouteOperationType.OPERATION,
              Optional.empty(),
              Optional.empty(),
              Map.of(),
              now,
              now));
    }

    private SpawnTicket ticket(UUID routeUuid, Instant due) {
      SpawnService spawnService =
          new SpawnService(
              new SpawnServiceKey(routeUuid),
              UUID.randomUUID(),
              "COMP",
              UUID.randomUUID(),
              OP,
              UUID.randomUUID(),
              "L1",
              routeUuid,
              routeUuid.equals(upUuid) ? "UP" : "DOWN",
              Duration.ofMinutes(5),
              OP + ":D:DEPOT:1");
      return new SpawnTicket(
          UUID.randomUUID(),
          spawnService,
          due,
          due,
          due,
          0,
          0L,
          Optional.empty(),
          Optional.empty(),
          Optional.empty(),
          TripSource.SCHEDULED,
          0);
    }
  }
}
