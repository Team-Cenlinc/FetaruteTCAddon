package org.fetarute.fetaruteTCAddon.dispatcher.eta;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
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
import org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinitionCache;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteId;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteMetadata;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspect;
import org.fetarute.fetaruteTCAddon.storage.api.StorageProvider;
import org.junit.jupiter.api.Test;

/** 站牌查询统计：缓存命中与重算分开计数，重算期间的存储读取与实际发生的读取一致。 */
class EtaBoardStatsServiceTest {

  private static final NodeId AAA = NodeId.of("SURN:S:AAA:1");
  private static final NodeId BBB = NodeId.of("SURN:S:BBB:1");
  private static final NodeId CCC = NodeId.of("SURN:S:CCC:1");

  @Test
  void repeatedQueryWithinCacheWindowCountsAsHit() {
    Fixture fixture = new Fixture();

    fixture.service.getBoard("SURN", "CCC", null, Duration.ofMinutes(10));
    fixture.service.getBoard("SURN", "CCC", null, Duration.ofMinutes(10));

    EtaBoardStats.Snapshot stats = fixture.service.boardStatsSnapshot();
    assertEquals(1L, stats.computes());
    assertEquals(1L, stats.cacheHits());
    assertEquals(2L, stats.calls());
  }

  @Test
  void differentStationsComputeSeparately() {
    Fixture fixture = new Fixture();

    fixture.service.getBoard("SURN", "BBB", null, Duration.ofMinutes(10));
    fixture.service.getBoard("SURN", "CCC", null, Duration.ofMinutes(10));

    assertEquals(2L, fixture.service.boardStatsSnapshot().computes());
  }

  @Test
  // 统计里的存储读取必须等于真实打到仓库上的调用次数：少计会掩盖热点，多计会误导优化方向。
  void storageReadsMatchActualRepositoryCalls() {
    Fixture fixture = new Fixture();

    BoardResult board = fixture.service.getBoard("SURN", "CCC", null, Duration.ofMinutes(10));

    assertFalse(board.rows().isEmpty(), "前置：站牌应有一行，重算才会解析终点");
    long actualCalls = mockingDetails(fixture.routeRepository).getInvocations().size();
    EtaBoardStats.Snapshot stats = fixture.service.boardStatsSnapshot();
    assertTrue(actualCalls > 0, "前置：重算应读取过路线");
    assertEquals(actualCalls, stats.totalStorageReads());
    assertEquals(actualCalls, stats.maxStorageReads());
  }

  @Test
  void resetStartsAFreshWindow() {
    Fixture fixture = new Fixture();
    fixture.service.getBoard("SURN", "CCC", null, Duration.ofMinutes(10));
    Instant before = fixture.service.boardStatsSnapshot().since();

    fixture.service.resetBoardStats();

    EtaBoardStats.Snapshot stats = fixture.service.boardStatsSnapshot();
    assertEquals(0L, stats.calls());
    assertEquals(0L, stats.totalStorageReads());
    assertFalse(stats.since().isBefore(before));
  }

  /** L1 交路 AAA → BBB → CCC，列车停在 AAA；存储里只有这条运营交路。 */
  private static final class Fixture {
    private final RouteRepository routeRepository = mock(RouteRepository.class);
    private final EtaService service;

    private Fixture() {
      UUID routeUuid = UUID.randomUUID();
      UUID worldId = UUID.randomUUID();
      RouteDefinition route =
          new RouteDefinition(
              RouteId.of("SURN:L1:R1"),
              List.of(AAA, BBB, CCC),
              Optional.of(RouteMetadata.of("SURN", "L1", "R1", null)));
      List<RouteStop> stops =
          List.of(
              stop(routeUuid, 0, AAA, RouteStopPassType.STOP),
              stop(routeUuid, 1, BBB, RouteStopPassType.STOP),
              stop(routeUuid, 2, CCC, RouteStopPassType.TERMINATE));
      RouteDefinitionCache routes = mock(RouteDefinitionCache.class);
      when(routes.findById(routeUuid)).thenReturn(Optional.of(route));
      when(routes.listStops(any())).thenReturn(stops);

      RailGraphService graphs = mock(RailGraphService.class);
      when(graphs.getSnapshot(worldId))
          .thenReturn(
              Optional.of(
                  new RailGraphService.RailGraphSnapshot(
                      linearGraph(AAA, BBB, CCC), Instant.now())));

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

      StorageProvider provider = mock(StorageProvider.class);
      when(provider.routes()).thenReturn(routeRepository);
      when(routeRepository.findById(routeUuid))
          .thenReturn(
              Optional.of(
                  new Route(
                      routeUuid,
                      "R1",
                      UUID.randomUUID(),
                      "Local",
                      Optional.empty(),
                      RoutePatternType.LOCAL,
                      RouteOperationType.OPERATION,
                      Optional.empty(),
                      Optional.empty(),
                      Map.of(),
                      Instant.now(),
                      Instant.now())));

      service = new EtaService(snapshots, graphs, routes);
      service.attachStorageProvider(provider);
    }
  }

  private static RouteStop stop(
      UUID routeUuid, int sequence, NodeId node, RouteStopPassType passType) {
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
