package org.fetarute.fetaruteTCAddon.dispatcher.eta;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import org.fetarute.fetaruteTCAddon.company.model.Line;
import org.fetarute.fetaruteTCAddon.company.model.Operator;
import org.fetarute.fetaruteTCAddon.company.model.Route;
import org.fetarute.fetaruteTCAddon.company.model.RouteOperationType;
import org.fetarute.fetaruteTCAddon.company.model.RoutePatternType;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.company.model.RouteStopPassType;
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
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.model.TripSource;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SpawnForecastSupport;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SpawnManager;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SpawnService;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SpawnServiceKey;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SpawnTicket;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.TicketAssigner;
import org.junit.jupiter.api.Test;

/**
 * 站牌的票据与预测行：表定票据按车次逐条列出、按车次去重；只有按间隔发车的回库交路才合并成一行。
 *
 * <p>回库交路 AAA → BBB → CCC，在 BBB 看站牌。
 */
class EtaBoardTicketRowsTest {

  private static final NodeId AAA = NodeId.of("SURN:S:AAA:1");
  private static final NodeId BBB = NodeId.of("SURN:S:BBB:1");
  private static final NodeId CCC = NodeId.of("SURN:S:CCC:1");

  private final UUID routeUuid = UUID.randomUUID();
  private final UUID worldId = UUID.randomUUID();
  private final Instant now = Instant.now();

  @Test
  void everyTimetableReturnTripIsListed() {
    EtaService service =
        board(
            List.of(
                ticket(now.plusSeconds(60), "TIMETABLE-T-D132-2026-10-02"),
                ticket(now.plusSeconds(360), "TIMETABLE-T-D133-2026-10-02")),
            List.of());

    assertEquals(2, rows(service).size(), "带客回库班各是一个车次，不能只留一行");
  }

  @Test
  void headwayReturnTicketsCollapseToTheEarliest() {
    EtaService service =
        board(
            List.of(ticket(now.plusSeconds(360), null), ticket(now.plusSeconds(60), null)),
            List.of());

    List<BoardResult.BoardRow> rows = rows(service);

    assertEquals(1, rows.size());
    assertEquals(BoardPhase.PENDING, rows.get(0).phase());
  }

  @Test
  void anIssuedTripIsNotForecastAgainEvenIfItsDepartureMoved() {
    String trip = "TIMETABLE-T-D132-2026-10-02";
    EtaService service =
        board(
            List.of(ticket(now.plusSeconds(90), trip)), List.of(ticket(now.plusSeconds(60), trip)));

    List<BoardResult.BoardRow> rows = rows(service);

    assertEquals(1, rows.size(), "已出票的车次发车时刻被待命车就绪时刻改写，仍是同一班");
    assertEquals(BoardPhase.PENDING, rows.get(0).phase());
  }

  private List<BoardResult.BoardRow> rows(EtaService service) {
    return service.getBoard("SURN", "BBB", null, Duration.ofMinutes(10)).rows();
  }

  private EtaService board(List<SpawnTicket> pending, List<SpawnTicket> forecast) {
    RouteDefinition route =
        new RouteDefinition(
            RouteId.of("SURN:L1:R1D"),
            List.of(AAA, BBB, CCC),
            Optional.of(RouteMetadata.of("SURN", "L1", "R1D", null)));
    RouteDefinitionCache routes = mock(RouteDefinitionCache.class);
    when(routes.findById(routeUuid)).thenReturn(Optional.of(route));
    when(routes.listStops(any()))
        .thenReturn(
            List.of(
                stop(0, AAA, RouteStopPassType.STOP),
                stop(1, BBB, RouteStopPassType.STOP),
                stop(2, CCC, RouteStopPassType.TERMINATE)));
    when(routes.findRecord(routeUuid))
        .thenReturn(
            Optional.of(
                new RouteDefinitionCache.RouteRecord(
                    mock(Operator.class),
                    mock(Line.class),
                    new Route(
                        routeUuid,
                        "R1D",
                        UUID.randomUUID(),
                        "Return",
                        Optional.empty(),
                        RoutePatternType.LOCAL,
                        RouteOperationType.RETURN,
                        Optional.empty(),
                        Optional.empty(),
                        Map.of(),
                        now,
                        now))));
    RailGraphService graphs = mock(RailGraphService.class);
    when(graphs.getSnapshot(worldId))
        .thenReturn(
            Optional.of(new RailGraphService.RailGraphSnapshot(linearGraph(AAA, BBB, CCC), now)));
    when(graphs.findWorldIdForPath(anyList())).thenReturn(Optional.of(worldId));
    EtaService service = new EtaService(new TrainSnapshotStore(), graphs, routes);
    SpawnManager manager =
        mock(SpawnManager.class, withSettings().extraInterfaces(SpawnForecastSupport.class));
    when(manager.snapshotQueue()).thenReturn(pending);
    when(((SpawnForecastSupport) manager).snapshotForecast(any(), any(), anyInt()))
        .thenReturn(forecast);
    TicketAssigner assigner = mock(TicketAssigner.class);
    when(assigner.snapshotPendingTickets()).thenReturn(List.of());
    service.attachTicketSources(manager, assigner);
    return service;
  }

  private SpawnTicket ticket(Instant due, String tripId) {
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
            "R1D",
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
        Optional.ofNullable(tripId),
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
