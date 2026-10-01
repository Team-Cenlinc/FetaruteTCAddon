package org.fetarute.fetaruteTCAddon.dispatcher.eta;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
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
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.StationPresenceTracker;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.StationStopEvent;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.model.TripSource;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspect;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SpawnForecastSupport;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SpawnManager;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SpawnService;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SpawnServiceKey;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SpawnTicket;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.TicketAssigner;
import org.junit.jupiter.api.Test;

/** 站牌行的结构化字段：阶段、本站停靠属性、在站列车与按表晚点。 */
class EtaBoardRowFieldsTest {

  private static final NodeId AAA = NodeId.of("SURN:S:AAA:1");
  private static final NodeId BBB = NodeId.of("SURN:S:BBB:1");
  private static final NodeId CCC = NodeId.of("SURN:S:CCC:1");
  private static final String TRAIN = "train-1";

  @Test
  void runningRowsCarryStopFacts() {
    Fixture fixture = Fixture.running();

    BoardResult.BoardRow passing = onlyRow(fixture.service, "BBB");
    BoardResult.BoardRow terminal = onlyRow(fixture.service, "CCC");

    assertTrue(passing.passing());
    assertFalse(passing.terminating());
    assertEquals(1, passing.stopIndex());
    assertEquals(Optional.of(TRAIN), passing.trainName());
    assertTrue(
        passing.phase() == BoardPhase.EN_ROUTE || passing.phase() == BoardPhase.ARRIVING,
        "运行中列车只会是途中或进站");
    assertFalse(terminal.passing());
    assertTrue(terminal.terminating());
    assertFalse(terminal.outOfService());
    assertEquals(2, terminal.stopIndex());
  }

  @Test
  void withoutTimetableThereIsNoDelay() {
    assertTrue(onlyRow(Fixture.running().service, "CCC").delaySeconds().isEmpty());
  }

  @Test
  void delayIsMeasuredAgainstThePlannedArrival() {
    Fixture fixture = Fixture.running();
    Instant planned = Instant.now().minusSeconds(600);
    fixture.service.attachPlannedArrivals(
        (train, index) ->
            TRAIN.equals(train) && index == 2 ? Optional.of(planned) : Optional.empty());

    BoardResult.BoardRow row = onlyRow(fixture.service, "CCC");

    assertEquals(
        EtaService.ceilSeconds(Duration.between(planned, row.eta())),
        row.delaySeconds().getAsLong());
  }

  @Test
  void trainAtTheStationIsNotListedWithoutPresence() {
    assertTrue(
        Fixture.running()
            .service
            .getBoard("SURN", "AAA", null, Duration.ofMinutes(10))
            .rows()
            .isEmpty());
  }

  @Test
  // 进度到站后本站不再是“下一个目标”；不单独列出的话，列车一停稳就从站牌上消失。
  void trainStandingAtTheStationIsListed() {
    Fixture fixture = Fixture.running();
    StationPresenceTracker presence = new StationPresenceTracker();
    Instant arrivedAt = Instant.now();
    presence.onStationArrival(
        new StationStopEvent(
            TRAIN, Optional.of(fixture.routeUuid), "SURN:L1:R1", 0, 3, AAA.value(), arrivedAt));
    fixture.service.attachStationPresence(() -> Optional.of(presence));
    Instant plannedDeparture = arrivedAt.minusSeconds(120);
    fixture.service.attachPlannedDepartures(
        (train, index) -> Optional.of(plannedDeparture), Duration.ofSeconds(150));

    BoardResult.BoardRow row = onlyRow(fixture.service, "AAA");
    assertEquals(BoardPhase.AT_STATION, row.phase());
    assertEquals(0, row.stopIndex());
    assertEquals("Boarding", row.statusText());
    // 本站还要停 20 秒（默认停站），比计划发车晚 120 + 20 秒；查询时刻略晚于到站时刻，最多多出 1 秒。
    long delay = row.delaySeconds().getAsLong();
    assertTrue(delay >= 140 && delay <= 141, "实际 " + delay);
  }

  @Test
  void ticketRowsCarryPhaseAndTimetableDelay() {
    Fixture fixture = Fixture.tickets();
    Instant due = Instant.now().plusSeconds(120);
    SpawnTicket timetableTicket = fixture.ticket(due, Optional.of("TIMETABLE-TT-1001-2026-09-30"));
    SpawnTicket headwayTicket = fixture.ticket(due.plusSeconds(60), Optional.empty());
    SpawnTicket forecast = fixture.ticket(due.plusSeconds(240), Optional.empty());
    SpawnManager manager =
        mock(SpawnManager.class, withSettings().extraInterfaces(SpawnForecastSupport.class));
    when(manager.snapshotQueue()).thenReturn(List.of(timetableTicket, headwayTicket));
    when(((SpawnForecastSupport) manager).snapshotForecast(any(), any(), anyInt()))
        .thenReturn(List.of(forecast));
    TicketAssigner assigner = mock(TicketAssigner.class);
    when(assigner.snapshotPendingTickets()).thenReturn(List.of());
    fixture.service.attachTicketSources(manager, assigner);

    List<BoardResult.BoardRow> rows =
        fixture.service.getBoard("SURN", "BBB", null, Duration.ofMinutes(10)).rows();

    assertEquals(3, rows.size());
    assertEquals(BoardPhase.PENDING, rows.get(0).phase());
    assertTrue(rows.get(0).delaySeconds().getAsLong() <= 1, "未过表定发车即准点（取整误差 1 秒）");
    assertEquals(BoardPhase.PENDING, rows.get(1).phase());
    assertTrue(rows.get(1).delaySeconds().isEmpty(), "按间隔发车的票没有表定时刻");
    assertEquals(BoardPhase.FORECAST, rows.get(2).phase());
    assertTrue(rows.get(2).trainName().isEmpty());
  }

  private static BoardResult.BoardRow onlyRow(EtaService service, String station) {
    List<BoardResult.BoardRow> rows =
        service.getBoard("SURN", station, null, Duration.ofMinutes(10)).rows();
    assertEquals(1, rows.size(), station + " 应只有一行");
    return rows.get(0);
  }

  /** L1 交路 AAA（停）→ BBB（通过）→ CCC（终到）。 */
  private static final class Fixture {
    private final UUID routeUuid = UUID.randomUUID();
    private final UUID worldId = UUID.randomUUID();
    private final RouteDefinition route =
        new RouteDefinition(
            RouteId.of("SURN:L1:R1"),
            List.of(AAA, BBB, CCC),
            Optional.of(RouteMetadata.of("SURN", "L1", "R1", null)));
    private final TrainSnapshotStore snapshots = new TrainSnapshotStore();
    private final EtaService service;

    private Fixture() {
      List<RouteStop> stops =
          List.of(
              stop(0, AAA, RouteStopPassType.STOP),
              stop(1, BBB, RouteStopPassType.PASS),
              stop(2, CCC, RouteStopPassType.TERMINATE));
      RouteDefinitionCache routes = mock(RouteDefinitionCache.class);
      when(routes.findById(routeUuid)).thenReturn(Optional.of(route));
      when(routes.listStops(any())).thenReturn(stops);
      RailGraphService graphs = mock(RailGraphService.class);
      when(graphs.getSnapshot(worldId))
          .thenReturn(
              Optional.of(
                  new RailGraphService.RailGraphSnapshot(
                      linearGraph(AAA, BBB, CCC), Instant.now())));
      when(graphs.findWorldIdForPath(anyList())).thenReturn(Optional.of(worldId));
      service = new EtaService(snapshots, graphs, routes);
    }

    /** 列车停在 AAA（下标 0）。 */
    static Fixture running() {
      Fixture fixture = new Fixture();
      fixture.snapshots.update(
          TRAIN,
          new TrainRuntimeSnapshot(
              1L,
              Instant.now(),
              fixture.worldId,
              fixture.routeUuid,
              fixture.route.id(),
              0,
              Optional.of(AAA),
              Optional.of(AAA),
              Optional.empty(),
              Optional.of(SignalAspect.PROCEED),
              Optional.empty()));
      return fixture;
    }

    /** 没有运行中列车，只有票据。 */
    static Fixture tickets() {
      return new Fixture();
    }

    SpawnTicket ticket(Instant due, Optional<String> tripId) {
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
          tripId,
          TripSource.SCHEDULED,
          0);
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
  }

  private static RailGraph linearGraph(NodeId... nodes) {
    Map<NodeId, RailNode> nodeMap = new HashMap<>();
    Map<EdgeId, RailEdge> edgeMap = new HashMap<>();
    for (int i = 0; i < nodes.length; i++) {
      nodeMap.put(
          nodes[i],
          new SignRailNode(
              nodes[i],
              NodeType.WAYPOINT,
              new Vector(i * 10, 0, 0),
              Optional.empty(),
              Optional.empty()));
      if (i > 0) {
        EdgeId edgeId = EdgeId.undirected(nodes[i - 1], nodes[i]);
        edgeMap.put(
            edgeId, new RailEdge(edgeId, nodes[i - 1], nodes[i], 60, 0.0, true, Optional.empty()));
      }
    }
    return new SimpleRailGraph(nodeMap, edgeMap, Set.of());
  }
}
