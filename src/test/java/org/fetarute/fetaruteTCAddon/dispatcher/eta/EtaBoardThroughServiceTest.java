package org.fetarute.fetaruteTCAddon.dispatcher.eta;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
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
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteLineChanges;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteMetadata;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspect;
import org.junit.jupiter.api.Test;

/** 站牌：直通车到本站时属于哪条线，就按哪条线显示与过滤。 */
class EtaBoardThroughServiceTest {

  private static final NodeId AAA = NodeId.of("SURN:S:AAA:1");
  private static final NodeId BBB = NodeId.of("SURN:S:BBB:1");
  private static final NodeId CCC = NodeId.of("SURN:S:CCC:1");

  /** L1 交路 AAA → BBB（本站起直通 L2）→ CCC，列车停在 AAA。 */
  private static EtaService service() {
    UUID routeUuid = UUID.randomUUID();
    UUID worldId = UUID.randomUUID();
    RouteDefinition route =
        new RouteDefinition(
            RouteId.of("SURN:L1:R1"),
            List.of(AAA, BBB, CCC),
            Optional.of(RouteMetadata.of("SURN", "L1", "R1", null)));
    List<RouteStop> stops =
        List.of(
            stop(routeUuid, 0, AAA, RouteStopPassType.STOP, null),
            stop(routeUuid, 1, BBB, RouteStopPassType.STOP, "CHANGE:surn:l2"),
            stop(routeUuid, 2, CCC, RouteStopPassType.TERMINATE, null));
    RouteDefinitionCache routes = mock(RouteDefinitionCache.class);
    when(routes.findById(routeUuid)).thenReturn(Optional.of(route));
    when(routes.listStops(any())).thenReturn(stops);

    RailGraphService graphs = mock(RailGraphService.class);
    when(graphs.getSnapshot(worldId))
        .thenReturn(
            Optional.of(
                new RailGraphService.RailGraphSnapshot(linearGraph(AAA, BBB, CCC), Instant.now())));

    TrainSnapshotStore snapshots = new TrainSnapshotStore();
    snapshots.update(
        "train-1",
        new TrainRuntimeSnapshot(
            1L,
            Instant.now(),
            worldId,
            routeUuid,
            route.id(),
            0,
            Optional.of(AAA),
            Optional.of(AAA),
            Optional.empty(),
            Optional.of(SignalAspect.PROCEED),
            Optional.empty()));
    EtaService service = new EtaService(snapshots, graphs, routes);
    // 生产环境接的是车站目录（StationDirectory.Snapshot#canonicalLine）；这里用大写代替主数据写法。
    service.attachLineCanonicalizer(
        line ->
            new RouteLineChanges.LineRef(
                line.operatorCode().toUpperCase(java.util.Locale.ROOT),
                line.lineCode().toUpperCase(java.util.Locale.ROOT)));
    return service;
  }

  @Test
  void rowsAfterTheChangeShowTheNewLine() {
    EtaService service = service();

    List<BoardResult.BoardRow> atChange =
        service.getBoard("SURN", "BBB", null, Duration.ofMinutes(10)).rows();
    assertEquals(1, atChange.size());
    assertEquals("L2", atChange.get(0).lineName(), "换线站以新线路发车");

    List<BoardResult.BoardRow> after =
        service.getBoard("SURN", "CCC", null, Duration.ofMinutes(10)).rows();
    assertEquals("L2", after.get(0).lineName());
  }

  @Test
  void lineNamesUseTheCanonicalCodes() {
    EtaService service = service();
    service.attachLineCanonicalizer(null);

    assertEquals(
        "l2",
        service.getBoard("SURN", "CCC", null, Duration.ofMinutes(10)).rows().get(0).lineName(),
        "未接入车站目录时原样显示指令里的代码");
  }

  @Test
  void lineFilterUsesTheLineAtTheStation() {
    EtaService service = service();

    assertEquals(1, service.getBoard("SURN", "CCC", "l2", Duration.ofMinutes(10)).rows().size());
    assertTrue(
        service.getBoard("SURN", "CCC", "L1", Duration.ofMinutes(10)).rows().isEmpty(),
        "换线之后的车站不再算原线路");
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
