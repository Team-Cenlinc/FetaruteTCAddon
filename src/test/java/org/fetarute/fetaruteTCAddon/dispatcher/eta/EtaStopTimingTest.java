package org.fetarute.fetaruteTCAddon.dispatcher.eta;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.StationPresenceTracker;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.StationStopEvent;
import org.junit.jupiter.api.Test;

/**
 * ETA 的停站口径：与运行时实际怎么停保持一致。
 *
 * <p>图上所有边无限速，按默认 6 格/秒；加速度 1 格/秒²，从静止起步多花 3 秒；未接入进站限速，所以不建模制动。
 */
class EtaStopTimingTest {

  private static final String TRAIN = "SURN-L1-0001";
  private final UUID routeUuid = UUID.randomUUID();
  private final UUID worldId = UUID.randomUUID();
  private final RouteId routeId = RouteId.of("SURN:L1:R1");
  private final TrainSnapshotStore snapshots = new TrainSnapshotStore();

  private EtaService service(List<NodeId> waypoints, List<RouteStop> stops, RailGraph graph) {
    RailGraphService railGraphService = mock(RailGraphService.class);
    when(railGraphService.getSnapshot(worldId))
        .thenReturn(Optional.of(new RailGraphService.RailGraphSnapshot(graph, Instant.now())));
    RouteDefinitionCache routes = mock(RouteDefinitionCache.class);
    when(routes.findById(routeUuid))
        .thenReturn(Optional.of(new RouteDefinition(routeId, waypoints, Optional.empty())));
    when(routes.listStops(routeId)).thenReturn(stops);
    return new EtaService(snapshots, railGraphService, routes);
  }

  private void snapshot(
      int index, OptionalDouble speed, TrainRuntimeSnapshot.HoldTimeline timeline) {
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
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            speed,
            OptionalInt.empty(),
            OptionalInt.empty(),
            OptionalDouble.empty(),
            timeline));
  }

  private RouteStop stop(int index, NodeId node, RouteStopPassType passType, Integer dwell) {
    return new RouteStop(
        routeUuid,
        index * 10,
        Optional.empty(),
        Optional.of(node.value()),
        Optional.ofNullable(dwell),
        passType,
        Optional.empty());
  }

  @Test
  void stopsWithoutDwellCountTheRuntimeDefaultAndTerminateStopsCount() {
    NodeId a = NodeId.of("SURN:S:AAA:1");
    NodeId b = NodeId.of("SURN:S:BBB:1");
    NodeId c = NodeId.of("SURN:S:CCC:1");
    NodeId d = NodeId.of("SURN:S:DDD:1");
    EtaService service =
        service(
            List.of(a, b, c, d),
            List.of(
                stop(0, a, RouteStopPassType.STOP, null),
                stop(1, b, RouteStopPassType.STOP, null),
                stop(2, c, RouteStopPassType.TERMINATE, 30),
                stop(3, d, RouteStopPassType.STOP, null)),
            linearGraph(List.of(a, b, c, d), 60));
    snapshot(0, OptionalDouble.of(6.0), TrainRuntimeSnapshot.HoldTimeline.EMPTY);

    EtaResult result = service.getForTrain(TRAIN, new EtaTarget.PlatformNode(d));

    // 修复前：没配 dwell 的 B 按 0 秒、TERMINATE 的 C 不计——运行时却分别停 20 秒与 30 秒。
    assertEquals(RouteStop.DEFAULT_DWELL_SECONDS + 30, result.dwellSec());
  }

  @Test
  void intermediateStopsSplitTravelIntoStopAndGo() {
    NodeId a = NodeId.of("SURN:S:AAA:1");
    NodeId b = NodeId.of("SURN:S:BBB:1");
    NodeId c = NodeId.of("SURN:S:CCC:1");
    NodeId d = NodeId.of("SURN:S:DDD:1");
    RailGraph graph = linearGraph(List.of(a, b, c, d), 60);
    snapshot(0, OptionalDouble.of(6.0), TrainRuntimeSnapshot.HoldTimeline.EMPTY);

    EtaService passing =
        service(
            List.of(a, b, c, d),
            List.of(
                stop(0, a, RouteStopPassType.STOP, 0),
                stop(1, b, RouteStopPassType.PASS, 0),
                stop(2, c, RouteStopPassType.PASS, 0),
                stop(3, d, RouteStopPassType.STOP, 0)),
            graph);
    // 180 格匀速 6 格/秒。
    assertEquals(30, passing.getForTrain(TRAIN, new EtaTarget.PlatformNode(d)).travelSec());

    EtaService stopping =
        service(
            List.of(a, b, c, d),
            List.of(
                stop(0, a, RouteStopPassType.STOP, 0),
                stop(1, b, RouteStopPassType.STOP, 0),
                stop(2, c, RouteStopPassType.STOP, 0),
                stop(3, d, RouteStopPassType.STOP, 0)),
            graph);
    // A→B 10 秒；B→C、C→D 各从静止起步：加速 6 秒走 18 格，余 42 格 7 秒。修复前当成不停车通过，同样是 30 秒。
    assertEquals(
        10 + 13 + 13, stopping.getForTrain(TRAIN, new EtaTarget.PlatformNode(d)).travelSec());
  }

  @Test
  void arrivedButDwellNotYetRegisteredCountsThePlannedDwell() {
    NodeId a = NodeId.of("SURN:S:AAA:1");
    NodeId b = NodeId.of("SURN:S:BBB:1");
    NodeId c = NodeId.of("SURN:S:CCC:1");
    EtaService service =
        service(
            List.of(a, b, c),
            List.of(
                stop(0, a, RouteStopPassType.STOP, null),
                stop(1, b, RouteStopPassType.STOP, 25),
                stop(2, c, RouteStopPassType.TERMINATE, null)),
            linearGraph(List.of(a, b, c), 60));
    StationPresenceTracker presence = new StationPresenceTracker();
    service.attachStationPresence(() -> Optional.of(presence));
    // 进度已推进到 B（到站事件已发），停站计时要等停稳几 tick 后才注册。
    snapshot(1, OptionalDouble.of(2.0), TrainRuntimeSnapshot.HoldTimeline.EMPTY);
    assertEquals(0, service.getForTrain(TRAIN, EtaTarget.nextStop()).dwellSec(), "尚未到站：不补停站");

    presence.onStationArrival(
        new StationStopEvent(
            TRAIN, Optional.of(routeUuid), routeId.value(), 1, 3, b.value(), Instant.now()));
    service.invalidateTrainEta(TRAIN);
    // 修复前这几秒按 0 计：ETA 先提前整段停站，计时注册后再跳回。
    assertEquals(25, service.getForTrain(TRAIN, EtaTarget.nextStop()).dwellSec());

    // 停站计时已结束（关门、等发车许可）：不再补。
    snapshot(
        1,
        OptionalDouble.of(0.0),
        new TrainRuntimeSnapshot.HoldTimeline(Optional.empty(), Optional.of(Instant.now())));
    service.invalidateTrainEta(TRAIN);
    assertEquals(0, service.getForTrain(TRAIN, EtaTarget.nextStop()).dwellSec());
  }

  @Test
  void dynamicPlaceholderTargetFollowsTheAllocatedPlatform() {
    NodeId a = NodeId.of("SURN:S:AAA:1");
    NodeId b1 = NodeId.of("SURN:S:BBB:1");
    NodeId b2 = NodeId.of("SURN:S:BBB:2");
    NodeId c = NodeId.of("SURN:S:CCC:1");
    RailGraph graph =
        graph(
            List.of(a, b1, b2, c),
            List.of(edge(a, b1, 42), edge(a, b2, 198), edge(b1, c, 60), edge(b2, c, 60)));
    List<RouteStop> stops =
        List.of(
            stop(0, a, RouteStopPassType.STOP, null),
            dynamicStop(1, "DYNAMIC:SURN:S:BBB:[1:2]"),
            stop(2, c, RouteStopPassType.TERMINATE, null));
    EtaService service = service(List.of(a, b1, c), stops, graph);
    snapshot(0, OptionalDouble.of(6.0), TrainRuntimeSnapshot.HoldTimeline.EMPTY);
    Map<Integer, NodeId> allocated = new HashMap<>();
    service.attachEffectiveWaypoints((name, route) -> List.of(a, allocated.getOrDefault(1, b1), c));

    // 未选台：车站级，取最早到达的 1 道（42 格 7 秒）。
    assertEquals(7, service.getForTrain(TRAIN, new EtaTarget.StopIndex(1)).travelSec());

    allocated.put(1, b2);
    service.invalidateTrainEta(TRAIN);
    // 选到 2 道：按 198 格 33 秒估算；拿占位的 1 道来问也落到 2 道。
    assertEquals(33, service.getForTrain(TRAIN, new EtaTarget.StopIndex(1)).travelSec());
    assertEquals(33, service.getForTrain(TRAIN, new EtaTarget.PlatformNode(b1)).travelSec());
    assertEquals(33, service.getForTrain(TRAIN, EtaTarget.nextStop()).travelSec());
    assertEquals(Optional.of(b2), service.effectiveStopNode(TRAIN, 1));
  }

  @Test
  void unresolvedDynamicWithUnreachablePlaceholderUsesAnotherCandidate() {
    NodeId a = NodeId.of("SURN:S:AAA:1");
    NodeId b2 = NodeId.of("SURN:S:BBB:2");
    NodeId c = NodeId.of("SURN:S:CCC:1");
    // 占位股道 BBB:1 在图上根本不存在（站台从 2 道起编）。
    NodeId placeholder = NodeId.of("SURN:S:BBB:1");
    RailGraph graph = graph(List.of(a, b2, c), List.of(edge(a, b2, 60), edge(b2, c, 60)));
    EtaService service =
        service(
            List.of(a, placeholder, c),
            List.of(
                stop(0, a, RouteStopPassType.STOP, null),
                dynamicStop(1, "DYNAMIC:SURN:S:BBB"),
                stop(2, c, RouteStopPassType.TERMINATE, null)),
            graph);
    snapshot(0, OptionalDouble.of(6.0), TrainRuntimeSnapshot.HoldTimeline.EMPTY);

    EtaResult result = service.getForTrain(TRAIN, EtaTarget.nextStop());

    // 修复前按占位股道寻路：NO_PATH，站牌与预计晚点都是空的。
    assertFalse(result.reasons().contains(EtaReason.NO_PATH));
    assertEquals(10, result.travelSec());
    assertTrue(result.etaEpochMillis() > 0L);
  }

  private RouteStop dynamicStop(int index, String directive) {
    return new RouteStop(
        routeUuid,
        index * 10,
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        RouteStopPassType.STOP,
        Optional.of(directive));
  }

  private static RailGraph linearGraph(List<NodeId> nodes, int edgeLengthBlocks) {
    List<RailEdge> edges = new java.util.ArrayList<>();
    for (int i = 1; i < nodes.size(); i++) {
      edges.add(edge(nodes.get(i - 1), nodes.get(i), edgeLengthBlocks));
    }
    return graph(nodes, edges);
  }

  private static RailEdge edge(NodeId from, NodeId to, int lengthBlocks) {
    EdgeId id = EdgeId.undirected(from, to);
    return new RailEdge(id, from, to, lengthBlocks, 0.0, true, Optional.empty());
  }

  private static RailGraph graph(List<NodeId> nodes, List<RailEdge> edges) {
    Map<NodeId, RailNode> nodeMap = new HashMap<>();
    for (int i = 0; i < nodes.size(); i++) {
      nodeMap.put(
          nodes.get(i),
          new SignRailNode(
              nodes.get(i),
              NodeType.WAYPOINT,
              new Vector(i * 10, 0, 0),
              Optional.empty(),
              Optional.empty()));
    }
    Map<EdgeId, RailEdge> edgeMap = new HashMap<>();
    for (RailEdge edge : edges) {
      edgeMap.put(edge.id(), edge);
    }
    return new SimpleRailGraph(nodeMap, edgeMap, Set.of());
  }
}
