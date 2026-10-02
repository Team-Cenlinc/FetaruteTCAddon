package org.fetarute.fetaruteTCAddon.dispatcher.eta;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import org.bukkit.util.Vector;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.company.model.RouteStopPassType;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.model.PathProgressModel;
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
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeStopState;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspect;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SpawnManager;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SpawnService;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SpawnServiceKey;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SpawnTicket;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.TicketAssigner;
import org.junit.jupiter.api.Test;

/** ETA 必须随列车被扣停、票据超时、在边上行驶而更新（用户实测：偏早、卡住不动、被 hold 不更新）。 */
class EtaHoldAwarenessTest {

  private static final String TRAIN = "SURC-MT-LP-0001";
  private final UUID routeUuid = UUID.randomUUID();
  private final UUID worldId = UUID.randomUUID();
  private final NodeId a = NodeId.of("SURN:S:AAA:1");
  private final NodeId b = NodeId.of("SURN:S:BBB:1");
  private final NodeId c = NodeId.of("SURN:S:CCC:1");
  private final RouteId routeId = RouteId.of("SURN:L1:R1");
  private final RouteDefinition route =
      new RouteDefinition(routeId, List.of(a, b, c), Optional.empty());
  private final TrainSnapshotStore snapshots = new TrainSnapshotStore();
  private final Map<String, RuntimeStopState> stopStates = new HashMap<>();

  private EtaService service() {
    RailGraph graph = linearGraph(List.of(a, b, c), 120);
    RailGraphService railGraphService = mock(RailGraphService.class);
    when(railGraphService.getSnapshot(worldId))
        .thenReturn(Optional.of(new RailGraphService.RailGraphSnapshot(graph, Instant.now())));
    when(railGraphService.findWorldIdForPath(anyList())).thenReturn(Optional.of(worldId));
    when(railGraphService.effectiveSpeedLimitBlocksPerSecond(any(), any(), any(), anyDouble()))
        .thenReturn(8.0);

    RouteStop stopA = stop(0, a, RouteStopPassType.STOP);
    RouteStop stopB = stop(1, b, RouteStopPassType.STOP);
    RouteStop stopC = stop(2, c, RouteStopPassType.TERMINATE);
    RouteDefinitionCache routes = mock(RouteDefinitionCache.class);
    when(routes.findById(routeUuid)).thenReturn(Optional.of(route));
    when(routes.listStops(routeId)).thenReturn(List.of(stopA, stopB, stopC));
    when(routes.findStop(routeId, 0)).thenReturn(Optional.of(stopA));
    when(routes.findStop(routeId, 1)).thenReturn(Optional.of(stopB));
    when(routes.findStop(routeId, 2)).thenReturn(Optional.of(stopC));

    EtaService service = new EtaService(snapshots, railGraphService, routes);
    service.attachRuntimeStopStates(name -> Optional.ofNullable(stopStates.get(name)));
    return service;
  }

  private void snapshot(int index, NodeId lastPassed, double speed, OptionalDouble traveled) {
    snapshots.update(
        TRAIN,
        new TrainRuntimeSnapshot(
            1L,
            Instant.now(),
            worldId,
            routeUuid,
            routeId,
            index,
            Optional.empty(),
            Optional.ofNullable(lastPassed),
            Optional.empty(),
            Optional.of(SignalAspect.PROCEED),
            Optional.empty(),
            OptionalDouble.of(speed),
            OptionalInt.empty(),
            OptionalInt.empty(),
            traveled));
  }

  private void hold(RuntimeStopState.ReleaseCondition release, long heldSeconds, String blocker) {
    stopStates.put(
        TRAIN,
        new RuntimeStopState(
            TRAIN,
            "HARD_BLOCKER_STOP",
            "test",
            release,
            RuntimeStopState.RetryTrigger.OCCUPANCY_CHANGE_OR_PERIODIC_RECHECK,
            blocker == null
                ? List.of()
                : List.of(new RuntimeStopState.Blocker(blocker, "OTHER", "PROTECTIVE_RETAIN")),
            true,
            Instant.now().minusSeconds(heldSeconds)));
  }

  @Test
  void heldTrainEtaGrowsWithHoldAndReportsDelay() {
    EtaService service = service();
    snapshot(0, a, 0.0, OptionalDouble.empty());
    EtaResult free = service.getForTrain(TRAIN, new EtaTarget.PlatformNode(b));
    assertEquals(0, free.waitSec());

    hold(
        RuntimeStopState.ReleaseCondition.BLOCKING_RESOURCES_RELEASED_OR_TRANSFERRED,
        90,
        "CONFLICT:single:SURN:AAA-BBB");
    service.invalidateTrainEta(TRAIN);
    EtaResult held = service.getForTrain(TRAIN, new EtaTarget.PlatformNode(b));

    // 修复前 wait 来自与现行准入不一致的占用预判，被扣 90 秒 ETA 仍与畅通时相同。
    assertTrue(held.waitSec() >= 89, "扣停 90 秒应顺延约 90 秒，实际 " + held.waitSec());
    assertTrue(held.etaEpochMillis() > free.etaEpochMillis() + 80_000L);
    assertEquals("Delayed 1m", held.statusText());
    assertTrue(held.reasons().contains(EtaReason.HOLD));
    assertTrue(held.reasons().contains(EtaReason.SINGLELINE));
    assertFalse(held.arriving(), "被扣停的车不能报即将到站");
    assertEquals(EtaConfidence.LOW, held.confidence());
  }

  @Test
  void holdEstimateIsCappedButDelayIsNot() {
    EtaService service = service();
    snapshot(0, a, 0.0, OptionalDouble.empty());
    hold(RuntimeStopState.ReleaseCondition.ACTIVE_AUTHORITY_REISSUED, 1200, null);
    EtaResult held = service.getForTrain(TRAIN, new EtaTarget.PlatformNode(b));
    assertEquals((int) EtaService.HOLD_ESTIMATE_CAP_SEC, held.waitSec());
    assertEquals("Delayed 20m", held.statusText());
  }

  @Test
  void plannedDwellIsNotAHold() {
    EtaService service = service();
    snapshots.update(
        TRAIN,
        new TrainRuntimeSnapshot(
            1L,
            Instant.now(),
            worldId,
            routeUuid,
            routeId,
            0,
            Optional.empty(),
            Optional.of(a),
            Optional.of(15),
            Optional.of(SignalAspect.STOP),
            Optional.empty()));
    hold(RuntimeStopState.ReleaseCondition.PLANNED_STOP_COMPLETED, 5, null);
    EtaResult result = service.getForTrain(TRAIN, new EtaTarget.PlatformNode(b));
    assertEquals(0, result.waitSec());
    assertFalse(result.reasons().contains(EtaReason.HOLD));
  }

  private void atStation(Instant dwellEndedAt, Instant holdSince) {
    snapshots.update(
        TRAIN,
        new TrainRuntimeSnapshot(
            1L,
            Instant.now(),
            worldId,
            routeUuid,
            routeId,
            0,
            Optional.empty(),
            Optional.of(a),
            Optional.empty(),
            Optional.of(SignalAspect.STOP),
            Optional.empty(),
            OptionalDouble.of(0.0),
            OptionalInt.empty(),
            OptionalInt.empty(),
            OptionalDouble.empty(),
            new TrainRuntimeSnapshot.HoldTimeline(
                Optional.ofNullable(holdSince), Optional.ofNullable(dwellEndedAt))));
  }

  @Test
  void overstayIsMeasuredFromDwellEndNotFromResettingStopState() {
    EtaService service = service();
    // 停站计时 65 秒前结束；门控停车状态刚被替换（enteredAt 只有 1 秒前）。
    atStation(Instant.now().minusSeconds(65), null);
    hold(RuntimeStopState.ReleaseCondition.PLANNED_STOP_COMPLETED, 1, null);
    EtaResult result = service.getForTrain(TRAIN, new EtaTarget.PlatformNode(b));
    // 修复前拿 enteredAt（每次替换都重置）去减整段在站时长，超时要晚一个停站时长才报。
    assertTrue(result.waitSec() >= 59 && result.waitSec() <= 61, "实际 " + result.waitSec());
    assertEquals("Delayed 1m", result.statusText());
    assertTrue(service.currentHold(TRAIN).orElseThrow().overstay());
  }

  @Test
  void holdStartSurvivesStopStateReplacement() {
    EtaService service = service();
    atStation(null, Instant.now().minusSeconds(90));
    // 运行时停车状态换了阻挡者，enteredAt 只有 2 秒前；采样器记下的连续扣停起点是 90 秒前。
    hold(RuntimeStopState.ReleaseCondition.BLOCKING_RESOURCES_RELEASED_OR_TRANSFERRED, 2, null);
    EtaResult result = service.getForTrain(TRAIN, new EtaTarget.PlatformNode(b));
    assertTrue(result.waitSec() >= 89, "实际 " + result.waitSec());
    assertEquals("Delayed 1m", result.statusText());
  }

  @Test
  void timetableWaitAtStationIsNotAHold() {
    EtaService service = service();
    Instant dwellEnded = Instant.now().minusSeconds(30);
    atStation(dwellEnded, null);
    hold(RuntimeStopState.ReleaseCondition.PLANNED_STOP_COMPLETED, 50, null);
    service.attachPlannedDepartures(
        (name, index) -> Optional.of(Instant.now().plusSeconds(10)), Duration.ofSeconds(150));
    assertTrue(service.currentHold(TRAIN).isEmpty(), "按表早到在站等点是计划内的");
    EtaResult result = service.getForTrain(TRAIN, new EtaTarget.PlatformNode(b));
    assertTrue(result.reasons().contains(EtaReason.WAIT));
    assertFalse(result.reasons().contains(EtaReason.HOLD));
  }

  @Test
  void gateStuckPastPlannedDepartureIsAHold() {
    EtaService service = service();
    Instant dwellEnded = Instant.now().minusSeconds(100);
    atStation(dwellEnded, null);
    hold(RuntimeStopState.ReleaseCondition.PLANNED_STOP_COMPLETED, 1, null);
    Instant planned = dwellEnded.plusSeconds(30);
    service.attachPlannedDepartures((name, index) -> Optional.of(planned), Duration.ofSeconds(150));
    TrainHold hold = service.currentHold(TRAIN).orElseThrow();
    // 修复前门控停车一律算例行停车：卡在站台（含互锁死锁）永远不报扣停。
    assertTrue(hold.overstay());
    assertEquals(planned.plusSeconds(EtaService.STATION_DEPARTURE_OVERHEAD_SEC), hold.since());
  }

  @Test
  void layoverIsNeverAHold() {
    EtaService service = service();
    atStation(Instant.now().minusSeconds(600), Instant.now().minusSeconds(600));
    hold(RuntimeStopState.ReleaseCondition.LAYOVER_READY_AND_AUTHORITY_REISSUED, 600, null);
    assertTrue(service.currentHold(TRAIN).isEmpty());
  }

  @Test
  void timetableEarlyArrivalWaitsUntilPlannedDeparture() {
    EtaService service = service();
    snapshot(0, a, 0.0, OptionalDouble.empty());
    Instant planned = Instant.now().plusSeconds(40);
    service.attachPlannedDepartures(
        (name, index) -> index == 0 ? Optional.of(planned) : Optional.empty(),
        Duration.ofSeconds(150));
    EtaResult result = service.getForTrain(TRAIN, new EtaTarget.PlatformNode(b));
    assertTrue(result.waitSec() >= 39 && result.waitSec() <= 40, "实际 " + result.waitSec());
    assertTrue(result.reasons().contains(EtaReason.WAIT));

    // 早于计划超过扣留上限的不会被扣留，ETA 也不能等。
    service.attachPlannedDepartures(
        (name, index) -> Optional.of(Instant.now().plusSeconds(600)), Duration.ofSeconds(150));
    service.invalidateTrainEta(TRAIN);
    assertEquals(0, service.getForTrain(TRAIN, new EtaTarget.PlatformNode(b)).waitSec());
  }

  @Test
  void etaCountsDownWhileTravellingAlongAnEdge() {
    EtaService service = service();
    snapshot(0, a, 8.0, OptionalDouble.of(0.0));
    int atNode = service.getForTrain(TRAIN, new EtaTarget.PlatformNode(b)).travelSec();

    snapshot(0, a, 8.0, OptionalDouble.of(80.0));
    service.invalidateTrainEta(TRAIN);
    int partWay = service.getForTrain(TRAIN, new EtaTarget.PlatformNode(b)).travelSec();

    // 修复前两者相等：ETA 只从上一节点起算，车在边上走了 80 格也不倒数。
    assertTrue(partWay < atNode - 5, "atNode=" + atNode + " partWay=" + partWay);
  }

  @Test
  void trimTraveledDropsWholeEdgesAndKeepsLastEdge() {
    List<NodeId> nodes = List.of(a, b, c);
    RailGraph graph = linearGraph(nodes, 50);
    List<RailEdge> edges =
        List.of(graph.edgesFrom(a).iterator().next(), graph.edgesFrom(c).iterator().next());
    PathProgressModel.PathProgress progress = new PathProgressModel.PathProgress(nodes, edges);

    PathProgressModel.PathProgress trimmed =
        EtaService.trimTraveled(progress, a, OptionalDouble.of(70.0));
    assertEquals(List.of(b, c), trimmed.remainingNodes());
    assertEquals(30.0, trimmed.firstEdgeRemainingBlocks().getAsDouble(), 1e-9);

    PathProgressModel.PathProgress overshoot =
        EtaService.trimTraveled(progress, a, OptionalDouble.of(500.0));
    assertEquals(1, overshoot.remainingEdgeCount());
    assertEquals(1.0, overshoot.firstEdgeRemainingBlocks().getAsDouble(), 1e-9);

    // 剩余路径不是从上一经过节点起算时不能扣：两个量起点不同。
    PathProgressModel.PathProgress mismatch =
        EtaService.trimTraveled(progress, c, OptionalDouble.of(70.0));
    assertEquals(progress, mismatch);
  }

  @Test
  void overdueTicketIsDelayedAndIncludesIntermediateDwell() {
    EtaService service = service();
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
    Instant dueAt = Instant.now().minusSeconds(150);
    SpawnTicket ticket =
        new SpawnTicket(
            UUID.randomUUID(),
            spawnService,
            dueAt,
            dueAt,
            0,
            0L,
            Optional.empty(),
            Optional.empty());
    SpawnManager spawnManager = mock(SpawnManager.class);
    when(spawnManager.snapshotQueue()).thenReturn(List.of(ticket));
    TicketAssigner assigner = mock(TicketAssigner.class);
    when(assigner.snapshotPendingTickets()).thenReturn(List.of());
    service.attachTicketSources(spawnManager, assigner);

    // 修复前：已超时的票据 wait=0，站牌一直报“马上就到”，且无任何延误标记。
    EtaResult origin = service.getForTicket(ticket.id().toString());
    assertTrue(origin.reasons().contains(EtaReason.OVERDUE));
    assertEquals("Delayed 2m", origin.statusText());
    assertTrue(origin.waitSec() >= 149, "超时 150 秒应顺延，实际 " + origin.waitSec());

    // 修复前：票据 ETA 不计中途停站，离起点越远越早。A→C 途经 B（STOP，20 秒）。
    EtaResult toC =
        service.computeForSpawnTicket(ticket, new EtaTarget.PlatformNode(c), Instant.now());
    assertEquals(20, toC.dwellSec());

    BoardResult board = service.getBoard("SURN", "CCC", null, Duration.ofMinutes(30));
    assertFalse(board.rows().isEmpty());
  }

  private RouteStop stop(int sequence, NodeId node, RouteStopPassType type) {
    return new RouteStop(
        routeUuid,
        sequence,
        Optional.empty(),
        Optional.of(node.value()),
        Optional.of(20),
        type,
        Optional.empty());
  }

  private static RailGraph linearGraph(List<NodeId> nodes, int length) {
    Map<NodeId, RailNode> nodeMap = new HashMap<>();
    Map<EdgeId, RailEdge> edgeMap = new HashMap<>();
    for (int i = 0; i < nodes.size(); i++) {
      NodeId id = nodes.get(i);
      nodeMap.put(
          id,
          new SignRailNode(
              id,
              NodeType.WAYPOINT,
              new Vector(i * length, 0, 0),
              Optional.empty(),
              Optional.empty()));
      if (i > 0) {
        NodeId prev = nodes.get(i - 1);
        EdgeId edgeId = EdgeId.undirected(prev, id);
        edgeMap.put(edgeId, new RailEdge(edgeId, prev, id, length, 0.0, true, Optional.empty()));
      }
    }
    return new SimpleRailGraph(nodeMap, edgeMap, Set.of());
  }
}
