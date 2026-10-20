package org.fetarute.fetaruteTCAddon.dispatcher.eta;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspect;
import org.junit.jupiter.api.Test;

/** 站牌：回程支线的车在联挂站之前开往联挂后主干的终点，联挂站上它仍是终到。 */
class EtaBoardJoinTest {

  private static final NodeId AAA = NodeId.of("SURN:S:AAA:1");
  private static final NodeId BBB = NodeId.of("SURN:S:BBB:1");
  private static final NodeId CCC = NodeId.of("SURN:S:CCC:1");
  private static final NodeId DDD = NodeId.of("SURN:S:DDD:1");

  private static final UUID FEEDER = UUID.randomUUID();
  private static final UUID TRUNK = UUID.randomUUID();

  /** 回程支线 AAA → BBB → CCC 终到（联挂站），主干 CCC → DDD 终到；回程支线车停在 AAA。 */
  private static EtaService service() {
    UUID worldId = UUID.randomUUID();
    RouteDefinition feeder =
        new RouteDefinition(
            RouteId.of("SURN:L1:BB"),
            List.of(AAA, BBB, CCC),
            Optional.of(RouteMetadata.of("SURN", "L1", "BB", null)));
    RouteDefinition trunk =
        new RouteDefinition(
            RouteId.of("SURN:L1:RR"),
            List.of(CCC, DDD),
            Optional.of(RouteMetadata.of("SURN", "L1", "RR", null)));
    RouteDefinitionCache routes = mock(RouteDefinitionCache.class);
    when(routes.findById(FEEDER)).thenReturn(Optional.of(feeder));
    when(routes.findById(TRUNK)).thenReturn(Optional.of(trunk));
    when(routes.listStops(feeder.id()))
        .thenReturn(
            List.of(
                stop(FEEDER, 0, AAA, RouteStopPassType.STOP, null),
                stop(FEEDER, 1, BBB, RouteStopPassType.STOP, null),
                stop(FEEDER, 2, CCC, RouteStopPassType.TERMINATE, null)));
    when(routes.listStops(trunk.id()))
        .thenReturn(
            List.of(
                stop(TRUNK, 0, CCC, RouteStopPassType.STOP, "COPL FROM:BB"),
                stop(TRUNK, 1, DDD, RouteStopPassType.TERMINATE, null)));

    RailGraphService graphs = mock(RailGraphService.class);
    when(graphs.getSnapshot(worldId))
        .thenReturn(
            Optional.of(
                new RailGraphService.RailGraphSnapshot(
                    linearGraph(AAA, BBB, CCC, DDD), Instant.now())));

    TrainSnapshotStore snapshots = new TrainSnapshotStore();
    snapshots.update(
        "train-1",
        new TrainRuntimeSnapshot(
            1L,
            Instant.now(),
            worldId,
            FEEDER,
            feeder.id(),
            0,
            Optional.of(AAA),
            Optional.of(AAA),
            Optional.empty(),
            Optional.of(SignalAspect.PROCEED),
            Optional.empty()));
    return new EtaService(snapshots, graphs, routes);
  }

  @Test
  void beforeTheJoinStationTheRowHeadsForTheTrunkTerminus() {
    EtaService service = service();
    service.attachThroughRoutes(
        route ->
            FEEDER.equals(route)
                ? Optional.of(new EtaService.ThroughRoute(TRUNK, 1))
                : Optional.empty());

    BoardResult.BoardRow row =
        service.getBoard("SURN", "BBB", null, Duration.ofMinutes(10)).rows().get(0);

    assertEquals("DDD", row.destination(), row.toString());
    assertEquals("CCC", row.endOperation(), "运营终点仍是本交路自己的（联挂站）");
    BoardResult.Joining joining = row.joining().orElseThrow();
    assertEquals(2, joining.stopIndex());
    assertEquals("CCC", joining.station());
  }

  @Test
  void atTheJoinStationTheFeederStillTerminates() {
    EtaService service = service();
    service.attachThroughRoutes(
        route ->
            FEEDER.equals(route)
                ? Optional.of(new EtaService.ThroughRoute(TRUNK, 1))
                : Optional.empty());

    BoardResult.BoardRow row =
        service.getBoard("SURN", "CCC", null, Duration.ofMinutes(10)).rows().get(0);

    assertTrue(row.terminating());
    assertEquals("CCC", row.destination());
    assertTrue(row.joining().isEmpty());
  }

  @Test
  void withoutTheCouplerTheFeederKeepsItsOwnTerminus() {
    BoardResult.BoardRow row =
        service().getBoard("SURN", "BBB", null, Duration.ofMinutes(10)).rows().get(0);

    assertEquals("CCC", row.destination());
    assertTrue(row.joining().isEmpty());
  }

  private static RouteStop stop(
      UUID routeUuid, int sequence, NodeId node, RouteStopPassType passType, String notes) {
    return new RouteStop(
        routeUuid,
        sequence,
        Optional.empty(),
        Optional.of(node.value()),
        Optional.empty(),
        passType,
        Optional.ofNullable(notes));
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
