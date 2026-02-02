package org.fetarute.fetaruteTCAddon.dispatcher.eta;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.bukkit.util.Vector;
import org.fetarute.fetaruteTCAddon.company.model.Route;
import org.fetarute.fetaruteTCAddon.company.model.RouteOperationType;
import org.fetarute.fetaruteTCAddon.company.model.RoutePatternType;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.company.model.RouteStopPassType;
import org.fetarute.fetaruteTCAddon.company.repository.RouteRepository;
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
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinitionCache;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteId;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.LayoverRegistry;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.HeadwayRule;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyDecision;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyManager;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspect;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SpawnManager;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SpawnService;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SpawnServiceKey;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SpawnTicket;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.TicketAssigner;
import org.fetarute.fetaruteTCAddon.storage.api.StorageProvider;
import org.junit.jupiter.api.Test;

class EtaServiceTest {

  @Test
  void trainEtaIncludesDwellTime() {
    UUID routeUuid = UUID.randomUUID();
    UUID worldId = UUID.randomUUID();
    NodeId start = NodeId.of("SURN:S:AAA:1");
    NodeId next = NodeId.of("SURN:S:BBB:1");

    RouteDefinition route =
        new RouteDefinition(RouteId.of("SURN:L1:R1"), List.of(start, next), Optional.empty());
    RailGraph graph = buildGraph(start, next, 60);

    RailGraphService railGraphService = mock(RailGraphService.class);
    when(railGraphService.getSnapshot(worldId))
        .thenReturn(Optional.of(new RailGraphService.RailGraphSnapshot(graph, Instant.now())));

    RouteDefinitionCache routeDefinitions = mock(RouteDefinitionCache.class);
    when(routeDefinitions.findById(routeUuid)).thenReturn(Optional.of(route));

    OccupancyManager occupancyManager = mock(OccupancyManager.class);
    when(occupancyManager.canEnter(any()))
        .thenReturn(new OccupancyDecision(true, Instant.now(), SignalAspect.PROCEED, List.of()));

    TrainSnapshotStore snapshotStore = new TrainSnapshotStore();
    snapshotStore.update(
        "train-1",
        new TrainRuntimeSnapshot(
            1L,
            Instant.now(),
            worldId,
            routeUuid,
            route.id(),
            0,
            Optional.empty(),
            Optional.empty(),
            Optional.of(30),
            Optional.of(SignalAspect.PROCEED),
            Optional.empty()));

    EtaService service =
        new EtaService(
            snapshotStore,
            railGraphService,
            routeDefinitions,
            occupancyManager,
            HeadwayRule.fixed(Duration.ZERO),
            () -> 2,
            () -> 0,
            () -> 2);

    EtaResult result = service.getForTrain("train-1", EtaTarget.nextStop());

    assertEquals(10, result.travelSec());
    assertEquals(30, result.dwellSec());
    assertEquals(0, result.waitSec());
    assertEquals(1, result.etaMinutesRounded());
  }

  @Test
  void boardUsesLayoverReadyAtForTickets() {
    UUID routeUuid = UUID.randomUUID();
    UUID worldId = UUID.randomUUID();
    NodeId start = NodeId.of("SURN:S:AAA:1");
    NodeId target = NodeId.of("SURN:S:BBB:1");
    RouteDefinition route =
        new RouteDefinition(RouteId.of("SURN:L1:R1"), List.of(start, target), Optional.empty());
    RailGraph graph = buildGraph(start, target, 60);

    RailGraphService railGraphService = mock(RailGraphService.class);
    when(railGraphService.findWorldIdForPath(anyList())).thenReturn(Optional.of(worldId));
    when(railGraphService.getSnapshot(worldId))
        .thenReturn(Optional.of(new RailGraphService.RailGraphSnapshot(graph, Instant.now())));

    RouteDefinitionCache routeDefinitions = mock(RouteDefinitionCache.class);
    when(routeDefinitions.findById(routeUuid)).thenReturn(Optional.of(route));

    OccupancyManager occupancyManager = mock(OccupancyManager.class);
    when(occupancyManager.canEnter(any()))
        .thenReturn(new OccupancyDecision(true, Instant.now(), SignalAspect.PROCEED, List.of()));

    TrainSnapshotStore snapshotStore = new TrainSnapshotStore();
    EtaService service =
        new EtaService(
            snapshotStore,
            railGraphService,
            routeDefinitions,
            occupancyManager,
            HeadwayRule.fixed(Duration.ZERO),
            () -> 2,
            () -> 0,
            () -> 2);

    SpawnService spawnService =
        new SpawnService(
            new SpawnServiceKey(routeUuid),
            UUID.randomUUID(),
            "COMP",
            UUID.randomUUID(),
            "SURN",
            UUID.randomUUID(),
            "L1",
            routeUuid,
            "R1",
            Duration.ofMinutes(5),
            "SURN:D:DEPOT:1");
    Instant dueAt = Instant.now();
    SpawnTicket ticket =
        new SpawnTicket(UUID.randomUUID(), spawnService, dueAt, dueAt, 0, Optional.empty());

    SpawnManager spawnManager = mock(SpawnManager.class);
    when(spawnManager.snapshotQueue()).thenReturn(List.of(ticket));
    TicketAssigner assigner = mock(TicketAssigner.class);
    when(assigner.snapshotPendingTickets()).thenReturn(List.of());

    Instant readyAt = Instant.now().plusSeconds(120);
    LayoverRegistry layoverRegistry = new LayoverRegistry();
    layoverRegistry.register("LAYOVER-1", start.value(), start, readyAt, java.util.Map.of());

    service.attachTicketSources(spawnManager, assigner);
    service.attachLayoverRegistry(layoverRegistry);

    BoardResult board = service.getBoard("SURN", "BBB", null, Duration.ofMinutes(10));

    assertFalse(board.rows().isEmpty());
    EtaResult result = service.getForTicket(ticket.id().toString());
    assertTrue(result.eta().isAfter(readyAt.minusSeconds(1)));
  }

  @Test
  void boardIncludesEndRouteAndEndOperation() {
    UUID routeUuid = UUID.randomUUID();
    UUID worldId = UUID.randomUUID();
    NodeId start = NodeId.of("SURN:S:AAA:1");
    NodeId passStation = NodeId.of("SURN:S:CCC:1");
    NodeId depot = NodeId.of("SURN:D:DEPOT:1");
    RouteDefinition route =
        new RouteDefinition(
            RouteId.of("SURN:L1:R1"), List.of(start, passStation, depot), Optional.empty());
    RailGraph graph = buildGraph(start, passStation, 60);

    RailGraphService railGraphService = mock(RailGraphService.class);
    when(railGraphService.getSnapshot(worldId))
        .thenReturn(Optional.of(new RailGraphService.RailGraphSnapshot(graph, Instant.now())));

    List<RouteStop> stops =
        List.of(
            new RouteStop(
                routeUuid,
                0,
                Optional.empty(),
                Optional.of(start.value()),
                Optional.empty(),
                RouteStopPassType.STOP,
                Optional.empty()),
            new RouteStop(
                routeUuid,
                1,
                Optional.empty(),
                Optional.of(passStation.value()),
                Optional.empty(),
                RouteStopPassType.PASS,
                Optional.empty()),
            new RouteStop(
                routeUuid,
                2,
                Optional.empty(),
                Optional.of(depot.value()),
                Optional.empty(),
                RouteStopPassType.PASS,
                Optional.empty()));

    RouteDefinitionCache routeDefinitions = mock(RouteDefinitionCache.class);
    when(routeDefinitions.findById(routeUuid)).thenReturn(Optional.of(route));
    when(routeDefinitions.listStops(any())).thenReturn(stops);

    OccupancyManager occupancyManager = mock(OccupancyManager.class);
    when(occupancyManager.canEnter(any()))
        .thenReturn(new OccupancyDecision(true, Instant.now(), SignalAspect.PROCEED, List.of()));

    TrainSnapshotStore snapshotStore = new TrainSnapshotStore();
    snapshotStore.update(
        "train-1",
        new TrainRuntimeSnapshot(
            1L,
            Instant.now(),
            worldId,
            routeUuid,
            route.id(),
            0,
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.of(SignalAspect.PROCEED),
            Optional.empty()));

    EtaService service =
        new EtaService(
            snapshotStore,
            railGraphService,
            routeDefinitions,
            occupancyManager,
            HeadwayRule.fixed(Duration.ZERO),
            () -> 2,
            () -> 0,
            () -> 2);

    BoardResult board = service.getBoard("SURN", "CCC", null, Duration.ofMinutes(10));

    assertFalse(board.rows().isEmpty());
    BoardResult.BoardRow row = board.rows().get(0);
    assertEquals("SURN:CCC", row.endRouteId().orElse(""));
    assertEquals("SURN:AAA", row.endOperationId().orElse(""));
  }

  @Test
  void boardReturnRouteUsesNotInServiceForEndOperation() {
    UUID routeUuid = UUID.randomUUID();
    UUID worldId = UUID.randomUUID();
    NodeId start = NodeId.of("SURN:S:AAA:1");
    NodeId end = NodeId.of("SURN:S:BBB:1");
    RouteDefinition route =
        new RouteDefinition(RouteId.of("SURN:L1:R1"), List.of(start, end), Optional.empty());
    RailGraph graph = buildGraph(start, end, 60);

    RailGraphService railGraphService = mock(RailGraphService.class);
    when(railGraphService.getSnapshot(worldId))
        .thenReturn(Optional.of(new RailGraphService.RailGraphSnapshot(graph, Instant.now())));

    List<RouteStop> stops =
        List.of(
            new RouteStop(
                routeUuid,
                0,
                Optional.empty(),
                Optional.of(start.value()),
                Optional.empty(),
                RouteStopPassType.STOP,
                Optional.empty()),
            new RouteStop(
                routeUuid,
                1,
                Optional.empty(),
                Optional.of(end.value()),
                Optional.empty(),
                RouteStopPassType.TERMINATE,
                Optional.empty()));

    RouteDefinitionCache routeDefinitions = mock(RouteDefinitionCache.class);
    when(routeDefinitions.findById(routeUuid)).thenReturn(Optional.of(route));
    when(routeDefinitions.listStops(any())).thenReturn(stops);

    OccupancyManager occupancyManager = mock(OccupancyManager.class);
    when(occupancyManager.canEnter(any()))
        .thenReturn(new OccupancyDecision(true, Instant.now(), SignalAspect.PROCEED, List.of()));

    TrainSnapshotStore snapshotStore = new TrainSnapshotStore();
    snapshotStore.update(
        "train-1",
        new TrainRuntimeSnapshot(
            1L,
            Instant.now(),
            worldId,
            routeUuid,
            route.id(),
            0,
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.of(SignalAspect.PROCEED),
            Optional.empty()));

    StorageProvider provider = mock(StorageProvider.class);
    RouteRepository routeRepo = mock(RouteRepository.class);
    when(provider.routes()).thenReturn(routeRepo);
    when(routeRepo.findById(routeUuid))
        .thenReturn(
            Optional.of(
                new Route(
                    routeUuid,
                    "R1",
                    UUID.randomUUID(),
                    "Return",
                    Optional.empty(),
                    RoutePatternType.LOCAL,
                    RouteOperationType.RETURN,
                    Optional.empty(),
                    Optional.empty(),
                    Map.of(),
                    Instant.now(),
                    Instant.now())));

    EtaService service =
        new EtaService(
            snapshotStore,
            railGraphService,
            routeDefinitions,
            occupancyManager,
            HeadwayRule.fixed(Duration.ZERO),
            () -> 2,
            () -> 0,
            () -> 2);
    service.attachStorageProvider(provider);

    BoardResult board = service.getBoard("SURN", "BBB", null, Duration.ofMinutes(10));

    assertFalse(board.rows().isEmpty());
    BoardResult.BoardRow row = board.rows().get(0);
    assertEquals("回库", row.endOperation());
    assertEquals("OUT_OF_SERVICE", row.endOperationId().orElse(""));
  }

  /**
   * 测试 resolveTargetSelection 在遇到 PASS 类型停靠时会跳过，查找实际的 STOP/TERMINATE。
   *
   * <p>此测试验证修复：当 route 中包含 INTERVAL 类型 waypoint 且 pass_type=PASS 时， arriving 检测应该找到后续真正的 STOP 而非仅按
   * index+1。
   */
  @Test
  void etaArrivingSkipsPassStopsToFindActualTarget() {
    UUID routeUuid = UUID.randomUUID();
    UUID worldId = UUID.randomUUID();

    // Route: A -> B(PASS) -> C(PASS) -> D(STOP) -> E(TERMINATE)
    NodeId a = NodeId.of("SURN:S:AAA:1");
    NodeId b = NodeId.of("SURN:OFL:MLU:2:001"); // INTERVAL 格式，PASS
    NodeId c = NodeId.of("SURN:OFL:MLU:2:002"); // INTERVAL 格式，PASS
    NodeId d = NodeId.of("SURN:S:DDD:1"); // STATION 格式，STOP
    NodeId e = NodeId.of("SURN:S:EEE:1"); // STATION 格式，TERMINATE

    RouteDefinition route =
        new RouteDefinition(RouteId.of("SURN:L1:R1"), List.of(a, b, c, d, e), Optional.empty());

    // 构建图：简化为直线 A-B-C-D-E
    RailGraph graph = buildLinearGraph(List.of(a, b, c, d, e), 10);

    RailGraphService railGraphService = mock(RailGraphService.class);
    when(railGraphService.getSnapshot(worldId))
        .thenReturn(Optional.of(new RailGraphService.RailGraphSnapshot(graph, Instant.now())));

    List<RouteStop> stops =
        List.of(
            new RouteStop(
                routeUuid,
                0,
                Optional.empty(),
                Optional.of(a.value()),
                Optional.empty(),
                RouteStopPassType.STOP,
                Optional.empty()),
            new RouteStop(
                routeUuid,
                1,
                Optional.empty(),
                Optional.of(b.value()),
                Optional.empty(),
                RouteStopPassType.PASS,
                Optional.empty()),
            new RouteStop(
                routeUuid,
                2,
                Optional.empty(),
                Optional.of(c.value()),
                Optional.empty(),
                RouteStopPassType.PASS,
                Optional.empty()),
            new RouteStop(
                routeUuid,
                3,
                Optional.empty(),
                Optional.of(d.value()),
                Optional.empty(),
                RouteStopPassType.STOP,
                Optional.empty()),
            new RouteStop(
                routeUuid,
                4,
                Optional.empty(),
                Optional.of(e.value()),
                Optional.empty(),
                RouteStopPassType.TERMINATE,
                Optional.empty()));

    RouteDefinitionCache routeDefinitions = mock(RouteDefinitionCache.class);
    when(routeDefinitions.findById(routeUuid)).thenReturn(Optional.of(route));
    when(routeDefinitions.listStops(any())).thenReturn(stops);

    OccupancyManager occupancyManager = mock(OccupancyManager.class);
    when(occupancyManager.canEnter(any()))
        .thenReturn(new OccupancyDecision(true, Instant.now(), SignalAspect.PROCEED, List.of()));

    TrainSnapshotStore snapshotStore = new TrainSnapshotStore();
    // 列车在 index=0 (站点 A)，下一个实际 STOP 应该是 D (index=3)
    snapshotStore.update(
        "train-1",
        new TrainRuntimeSnapshot(
            1L,
            Instant.now(),
            worldId,
            routeUuid,
            route.id(),
            0,
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.of(SignalAspect.PROCEED),
            Optional.empty()));

    EtaService service =
        new EtaService(
            snapshotStore,
            railGraphService,
            routeDefinitions,
            occupancyManager,
            HeadwayRule.fixed(Duration.ZERO),
            () -> 2,
            () -> 0,
            () -> 2);

    EtaResult result = service.getForTrain("train-1", EtaTarget.nextStop());

    // 应该返回到 D 站的 ETA，而非到 B 站（第一个 PASS）
    // ETA 应该可用（不是 unavailable，即 etaMinutesRounded >= 0）
    assertTrue(result.etaMinutesRounded() >= 0 || result.arriving(), "ETA 应该可用，因为实际下一 STOP 是 D 站");
  }

  private RailGraph buildLinearGraph(List<NodeId> nodes, int edgeLengthBlocks) {
    java.util.Map<NodeId, org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode> nodeMap =
        new java.util.HashMap<>();
    java.util.Map<EdgeId, RailEdge> edgeMap = new java.util.HashMap<>();
    for (int i = 0; i < nodes.size(); i++) {
      NodeId nodeId = nodes.get(i);
      nodeMap.put(
          nodeId,
          new SignRailNode(
              nodeId,
              NodeType.WAYPOINT,
              new Vector(i * 10, 0, 0),
              Optional.empty(),
              Optional.empty()));
      if (i > 0) {
        NodeId prev = nodes.get(i - 1);
        EdgeId edgeId = EdgeId.undirected(prev, nodeId);
        edgeMap.put(
            edgeId,
            new RailEdge(edgeId, prev, nodeId, edgeLengthBlocks, 0.0, true, Optional.empty()));
      }
    }
    return new SimpleRailGraph(nodeMap, edgeMap, Set.of());
  }

  private RailGraph buildGraph(NodeId start, NodeId end, int lengthBlocks) {
    SignRailNode startNode =
        new SignRailNode(
            start, NodeType.WAYPOINT, new Vector(0, 0, 0), Optional.empty(), Optional.empty());
    SignRailNode endNode =
        new SignRailNode(
            end, NodeType.WAYPOINT, new Vector(0, 0, 0), Optional.empty(), Optional.empty());
    EdgeId edgeId = EdgeId.undirected(start, end);
    RailEdge edge = new RailEdge(edgeId, start, end, lengthBlocks, 0.0, true, Optional.empty());
    return new SimpleRailGraph(
        java.util.Map.of(start, startNode, end, endNode), java.util.Map.of(edgeId, edge), Set.of());
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // 中途停站累加测试
  // ─────────────────────────────────────────────────────────────────────────────

  @Test
  void trainEtaIncludesIntermediateStopDwellTime() {
    // 测试场景：A -> B -> C -> D，列车在 A，目标是 D
    // B 和 C 都是 STOP 站点，各有 20 秒停车时间
    // 期望 dwellSec = 0（当前无停车）+ 20 + 20 = 40 秒
    UUID routeUuid = UUID.randomUUID();
    UUID worldId = UUID.randomUUID();
    NodeId nodeA = NodeId.of("SURN:S:AAA:1");
    NodeId nodeB = NodeId.of("SURN:S:BBB:1");
    NodeId nodeC = NodeId.of("SURN:S:CCC:1");
    NodeId nodeD = NodeId.of("SURN:S:DDD:1");

    RouteId routeId = RouteId.of("SURN:L1:R1");
    RouteDefinition route =
        new RouteDefinition(routeId, List.of(nodeA, nodeB, nodeC, nodeD), Optional.empty());
    RailGraph graph = buildLinearGraph(List.of(nodeA, nodeB, nodeC, nodeD), 60);

    RailGraphService railGraphService = mock(RailGraphService.class);
    when(railGraphService.getSnapshot(worldId))
        .thenReturn(Optional.of(new RailGraphService.RailGraphSnapshot(graph, Instant.now())));

    // 构建 RouteStop：B 和 C 都是 STOP，各 20 秒
    RouteStop stopB =
        new RouteStop(
            routeUuid,
            1,
            Optional.empty(),
            Optional.of("SURN:S:BBB:1"),
            Optional.of(20),
            RouteStopPassType.STOP,
            Optional.empty());
    RouteStop stopC =
        new RouteStop(
            routeUuid,
            2,
            Optional.empty(),
            Optional.of("SURN:S:CCC:1"),
            Optional.of(20),
            RouteStopPassType.STOP,
            Optional.empty());

    RouteDefinitionCache routeDefinitions = mock(RouteDefinitionCache.class);
    when(routeDefinitions.findById(routeUuid)).thenReturn(Optional.of(route));
    when(routeDefinitions.listStops(routeId)).thenReturn(List.of(stopB, stopC));
    when(routeDefinitions.findStop(routeId, 1)).thenReturn(Optional.of(stopB));
    when(routeDefinitions.findStop(routeId, 2)).thenReturn(Optional.of(stopC));
    when(routeDefinitions.findStop(routeId, 0)).thenReturn(Optional.empty());
    when(routeDefinitions.findStop(routeId, 3)).thenReturn(Optional.empty());

    OccupancyManager occupancyManager = mock(OccupancyManager.class);
    when(occupancyManager.canEnter(any()))
        .thenReturn(new OccupancyDecision(true, Instant.now(), SignalAspect.PROCEED, List.of()));

    TrainSnapshotStore snapshotStore = new TrainSnapshotStore();
    snapshotStore.update(
        "train-1",
        new TrainRuntimeSnapshot(
            1L,
            Instant.now(),
            worldId,
            routeUuid,
            routeId,
            0, // 当前在索引 0（nodeA）
            Optional.empty(),
            Optional.empty(),
            Optional.empty(), // 当前无停车
            Optional.of(SignalAspect.PROCEED),
            Optional.empty()));

    EtaService service =
        new EtaService(
            snapshotStore,
            railGraphService,
            routeDefinitions,
            occupancyManager,
            HeadwayRule.fixed(Duration.ZERO),
            () -> 2,
            () -> 0,
            () -> 4); // arrivingThreshold

    // 计算到 nodeD 的 ETA
    EtaResult result = service.getForTrain("train-1", new EtaTarget.PlatformNode(nodeD));

    // 期望中途停车时间 = stopB(20) + stopC(20) = 40 秒
    assertEquals(40, result.dwellSec(), "中途停车时间应为 40 秒（B + C 各 20 秒）");
  }

  @Test
  void trainEtaSkipsPassStopsForDwell() {
    // 测试场景：A -> B -> C -> D，列车在 A，目标是 D
    // B 是 PASS（通过不停），C 是 STOP（20 秒）
    // 期望 dwellSec = 0（当前无停车）+ 0（B 通过）+ 20（C 停车）= 20 秒
    UUID routeUuid = UUID.randomUUID();
    UUID worldId = UUID.randomUUID();
    NodeId nodeA = NodeId.of("SURN:S:AAA:1");
    NodeId nodeB = NodeId.of("SURN:S:BBB:1");
    NodeId nodeC = NodeId.of("SURN:S:CCC:1");
    NodeId nodeD = NodeId.of("SURN:S:DDD:1");

    RouteId routeId = RouteId.of("SURN:L1:R1");
    RouteDefinition route =
        new RouteDefinition(routeId, List.of(nodeA, nodeB, nodeC, nodeD), Optional.empty());
    RailGraph graph = buildLinearGraph(List.of(nodeA, nodeB, nodeC, nodeD), 60);

    RailGraphService railGraphService = mock(RailGraphService.class);
    when(railGraphService.getSnapshot(worldId))
        .thenReturn(Optional.of(new RailGraphService.RailGraphSnapshot(graph, Instant.now())));

    // B 是 PASS，C 是 STOP
    RouteStop stopB =
        new RouteStop(
            routeUuid,
            1,
            Optional.empty(),
            Optional.of("SURN:S:BBB:1"),
            Optional.of(20), // 有 dwell 但是 PASS 类型
            RouteStopPassType.PASS,
            Optional.empty());
    RouteStop stopC =
        new RouteStop(
            routeUuid,
            2,
            Optional.empty(),
            Optional.of("SURN:S:CCC:1"),
            Optional.of(20),
            RouteStopPassType.STOP,
            Optional.empty());

    RouteDefinitionCache routeDefinitions = mock(RouteDefinitionCache.class);
    when(routeDefinitions.findById(routeUuid)).thenReturn(Optional.of(route));
    when(routeDefinitions.listStops(routeId)).thenReturn(List.of(stopB, stopC));
    when(routeDefinitions.findStop(routeId, 1)).thenReturn(Optional.of(stopB));
    when(routeDefinitions.findStop(routeId, 2)).thenReturn(Optional.of(stopC));
    when(routeDefinitions.findStop(routeId, 0)).thenReturn(Optional.empty());
    when(routeDefinitions.findStop(routeId, 3)).thenReturn(Optional.empty());

    OccupancyManager occupancyManager = mock(OccupancyManager.class);
    when(occupancyManager.canEnter(any()))
        .thenReturn(new OccupancyDecision(true, Instant.now(), SignalAspect.PROCEED, List.of()));

    TrainSnapshotStore snapshotStore = new TrainSnapshotStore();
    snapshotStore.update(
        "train-1",
        new TrainRuntimeSnapshot(
            1L,
            Instant.now(),
            worldId,
            routeUuid,
            routeId,
            0,
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.of(SignalAspect.PROCEED),
            Optional.empty()));

    EtaService service =
        new EtaService(
            snapshotStore,
            railGraphService,
            routeDefinitions,
            occupancyManager,
            HeadwayRule.fixed(Duration.ZERO),
            () -> 2,
            () -> 0,
            () -> 4);

    EtaResult result = service.getForTrain("train-1", new EtaTarget.PlatformNode(nodeD));

    // B 是 PASS 不计入，只有 C 的 20 秒
    assertEquals(20, result.dwellSec(), "中途停车时间应为 20 秒（只有 C，B 是 PASS）");
  }
}
