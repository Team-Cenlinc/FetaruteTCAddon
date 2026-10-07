package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchTestFixtures.routeStop;
import static org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchTestFixtures.sectionlessGraph;
import static org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchTestFixtures.testConfigView;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bergerkiller.bukkit.tc.controller.components.RailState;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.util.Vector;
import org.fetarute.fetaruteTCAddon.company.model.RouteStopPassType;
import org.fetarute.fetaruteTCAddon.config.ConfigManager;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailEdge;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphService;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.SignRailNode;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.SimpleRailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.interlocking.RailEdgeFootprint;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.interlocking.RailFootprintCell;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.interlocking.RailInterlockingState;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinitionCache;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteId;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchTestFixtures.FakeTrain;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchTestFixtures.TagStore;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.config.TrainConfigResolver;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.AuthorizationPurpose;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyClaim;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyDecision;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyRequest;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyResource;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.ResourceIntent;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspectPolicy;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SimpleOccupancyManager;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeRegistry;
import org.junit.jupiter.api.Test;

/**
 * 可恢复保持（SMART_DISPATCH_RECOVERABLE_HOLD）不能把车身与列尾防护放空。
 *
 * <p>周期信号 tick 先按“硬窗口 + 当前位置”释放本车其余 claim，正常路径在同一拍末尾以列尾防护补回。可恢复保持分支提前返回，必须自己补回：
 * 硬窗口不含车头身后的区段，不补的话车身压着的 NODE/EDGE 在本车下一次完整 tick 之前不归任何车，后车可以对它们取得 MOVEMENT_REQUIRED。
 *
 * <p>夹具：车头刚过区间节点 {@code 3:007}，下一路径点是车站 {@code S:WSD:3}（车站不算 stopAtNextWaypoint，硬窗口被计划停车点封顶在站台）；
 * 预览窗口越过站台，看到另一列车占着站后的节点，本拍降为 STOP 并进入可恢复保持。站后不被占时同一位置走正常路径、车身照常保持。车长 34， 车身压着 {@code 3:006~3:007}
 * 整条边并伸进 {@code 3:005~3:006}。
 *
 * <p>现场方块故意落在任何区间之外，车身释放下限因此不参与：本用例只钉可恢复保持分支自己补回列尾防护这一件事，下限若生效会把车身照样取回，用例就测不出分支是否补了。
 */
class RecoverableHoldBodyRetainTest {

  private static final String TRAIN = "SURC-MT-LH-2689";
  private static final String BLOCKER = "SURC-DS-LH-3597";
  private static final String FOLLOWER = "SURC-MT-LH-0721";
  private static final NodeId P4 = NodeId.of("SURC:SPB:WSD:3:004");
  private static final NodeId P5 = NodeId.of("SURC:SPB:WSD:3:005");
  private static final NodeId P6 = NodeId.of("SURC:SPB:WSD:3:006");
  private static final NodeId P7 = NodeId.of("SURC:SPB:WSD:3:007");
  private static final NodeId WSD = NodeId.of("SURC:S:WSD:3");
  private static final NodeId BEYOND = NodeId.of("SURC:WSD:SCC:3:001");
  private static final NodeId SCC = NodeId.of("SURC:S:SCC:3");
  private static final RouteId ROUTE_ID = RouteId.of("MT-LH");
  private static final Set<RailFootprintCell> OFF_TRACK_CELLS =
      Set.of(new RailFootprintCell(0, 64, 0));
  private static final OccupancyResource EDGE_P6_P7 =
      OccupancyResource.forEdge(EdgeId.undirected(P6, P7));
  private static final OccupancyResource NODE_P6 = OccupancyResource.forNode(P6);
  private static final OccupancyResource EDGE_P5_P6 =
      OccupancyResource.forEdge(EdgeId.undirected(P5, P6));

  /** 可恢复保持的每一拍（包括仍在运动、再次进入该分支的下一拍）之后，车身所在的边与节点都在本车名下，后车的硬授权压不上去。 */
  @Test
  void recoverableHoldKeepsTheBodyAndRearGuardClaimed() {
    Scenario scenario = new Scenario();
    assertFalse(
        LiveBodyReleaseFloor.coverage(chain(scenario.worldId), OFF_TRACK_CELLS).complete(),
        "现场方块应落在区间之外，车身释放下限不参与，否则本用例测不出可恢复保持分支是否补回");
    scenario.tick(103.0);
    assertEquals(
        Optional.empty(),
        scenario.service.getActiveStopState(TRAIN).map(RuntimeStopState::reasonCode),
        () -> "第一拍应正常放行: " + scenario.debugTail());
    assertHeldByTrain(scenario.manager, EDGE_P6_P7);
    assertHeldByTrain(scenario.manager, NODE_P6);
    assertHeldByTrain(scenario.manager, EDGE_P5_P6);

    scenario.blockBeyondStation();
    scenario.tick(105.0);

    assertEquals(
        "SMART_DISPATCH_RECOVERABLE_HOLD",
        scenario.service.getActiveStopState(TRAIN).map(RuntimeStopState::reasonCode).orElse("-"),
        () -> "第二拍应进可恢复保持分支: " + scenario.debugTail());
    String held = "本车现存 claim: " + scenario.trainClaims();
    OccupancyDecision follower = scenario.manager.canEnter(scenario.followerRequest());
    assertAll(
        () -> assertHeldByTrain(scenario.manager, EDGE_P6_P7, held),
        () -> assertHeldByTrain(scenario.manager, NODE_P6, held),
        () -> assertHeldByTrain(scenario.manager, EDGE_P5_P6, held),
        () -> assertFalse(follower.allowed(), "后车不得对前车车身所在的边取得硬授权: " + follower));

    scenario.tick(106.0);

    assertEquals(
        "SMART_DISPATCH_RECOVERABLE_HOLD",
        scenario.service.getActiveStopState(TRAIN).map(RuntimeStopState::reasonCode).orElse("-"),
        () -> "仍在运动的下一拍应再次进入可恢复保持分支: " + scenario.debugTail());
    String heldAgain = "本车现存 claim: " + scenario.trainClaims();
    assertAll(
        () -> assertHeldByTrain(scenario.manager, EDGE_P6_P7, heldAgain),
        () -> assertHeldByTrain(scenario.manager, NODE_P6, heldAgain),
        () -> assertHeldByTrain(scenario.manager, EDGE_P5_P6, heldAgain));
  }

  private static void assertHeldByTrain(
      SimpleOccupancyManager manager, OccupancyResource resource, String context) {
    OccupancyClaim claim =
        manager
            .getClaim(resource)
            .orElseThrow(() -> new AssertionError(resource + " 已被放掉; " + context));
    assertEquals(TRAIN, claim.trainName(), resource + "; " + context);
  }

  private static void assertHeldByTrain(
      SimpleOccupancyManager manager, OccupancyResource resource) {
    assertHeldByTrain(manager, resource, "-");
  }

  private static final class Scenario {
    private final UUID worldId = UUID.randomUUID();
    private final SimpleOccupancyManager manager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    private final TagStore tags =
        new TagStore(
            TRAIN,
            "FTA_OPERATOR_CODE=op",
            "FTA_LINE_CODE=l1",
            "FTA_ROUTE_CODE=r1",
            "FTA_ROUTE_INDEX=3");
    private final RouteProgressRegistry registry = new RouteProgressRegistry();
    private final List<String> debug = new ArrayList<>();
    private final RuntimeDispatchService service;

    private Scenario() {
      RouteDefinition route =
          new RouteDefinition(
              ROUTE_ID, List.of(P4, P5, P6, P7, WSD, BEYOND, SCC), Optional.empty());
      registry.initFromTags(TRAIN, tags.properties(), route);
      service = service(manager, worldId, route, registry, debug);
    }

    /** DS 以保护性占用压住站后的节点：落在本车预览窗口内、硬窗口（封顶在站台）之外。 */
    private void blockBeyondStation() {
      OccupancyResource beyond = OccupancyResource.forNode(BEYOND);
      assertTrue(
          manager
              .acquire(
                  new OccupancyRequest(
                      BLOCKER,
                      Optional.empty(),
                      Instant.now(),
                      List.of(beyond),
                      Map.of(),
                      Map.of(),
                      0,
                      AuthorizationPurpose.RUNTIME_MOVE,
                      Map.of(),
                      Map.of(beyond, ResourceIntent.PROTECTIVE_RETAIN)))
              .allowed());
    }

    /** 后车对前车车身所在的边与节点申请硬授权。 */
    private OccupancyRequest followerRequest() {
      return new OccupancyRequest(
          FOLLOWER,
          Optional.of(ROUTE_ID),
          Instant.now(),
          List.of(EDGE_P5_P6, NODE_P6, EDGE_P6_P7),
          Map.of(),
          Map.of(),
          0,
          AuthorizationPurpose.RUNTIME_MOVE,
          Map.of(),
          Map.of(
              EDGE_P5_P6, ResourceIntent.MOVEMENT_REQUIRED,
              NODE_P6, ResourceIntent.MOVEMENT_REQUIRED,
              EDGE_P6_P7, ResourceIntent.MOVEMENT_REQUIRED));
    }

    private void tick(double headX) {
      FakeTrain train = new FakeTrain(worldId, tags.properties(), true, 0.75);
      train.estimatedTrainLengthBlocks = OptionalDouble.of(34.0);
      train.liveRailFootprintCells = Optional.of(OFF_TRACK_CELLS);
      RailState railState = mock(RailState.class);
      when(railState.positionLocation()).thenReturn(new Location(null, headX, 64.0, 0.0));
      train.railState = Optional.of(railState);
      service.handleSignalTick(train, false);
    }

    private List<String> trainClaims() {
      return manager.snapshotClaims().stream()
          .filter(claim -> TRAIN.equals(claim.trainName()))
          .map(claim -> claim.resource() + "@" + claim.role())
          .toList();
    }

    private String debugTail() {
      return String.join(
          "\n",
          debug.stream()
              .filter(
                  line ->
                      line.contains("SMART_DISPATCH")
                          || line.contains("STOP_LIFECYCLE")
                          || line.contains("信号Tick")
                          || line.contains("RECOVERABLE"))
              .toList());
    }
  }

  private static RuntimeDispatchService service(
      SimpleOccupancyManager manager,
      UUID worldId,
      RouteDefinition route,
      RouteProgressRegistry registry,
      List<String> debug) {
    ConfigManager configManager = mock(ConfigManager.class);
    when(configManager.current()).thenReturn(testConfigView(20, 20.0));
    RailGraphService railGraphService = mock(RailGraphService.class);
    when(railGraphService.getSnapshot(worldId))
        .thenReturn(
            Optional.of(
                new RailGraphService.RailGraphSnapshot(
                    sectionlessGraph(chain(worldId)), Instant.now())));
    when(railGraphService.effectiveSpeedLimitBlocksPerSecond(
            any(), any(), any(), anyDouble(), anyDouble()))
        .thenReturn(1000.0);
    RouteDefinitionCache routeDefinitions = mock(RouteDefinitionCache.class);
    when(routeDefinitions.findByCodes("op", "l1", "r1")).thenReturn(Optional.of(route));
    List<NodeId> waypoints = route.waypoints();
    for (int index = 0; index < waypoints.size(); index++) {
      NodeId node = waypoints.get(index);
      RouteStopPassType passType =
          node.equals(WSD) || node.equals(SCC) ? RouteStopPassType.STOP : RouteStopPassType.PASS;
      when(routeDefinitions.findStop(eq(ROUTE_ID), eq(index)))
          .thenReturn(Optional.of(routeStop(index, node, passType)));
    }
    return new RuntimeDispatchService(
        manager,
        railGraphService,
        routeDefinitions,
        registry,
        mock(SignNodeRegistry.class),
        mock(LayoverRegistry.class),
        new DwellRegistry(),
        configManager,
        null,
        new TrainConfigResolver(),
        debug::add);
  }

  /**
   * 3:004 —40— 3:005 —40— 3:006 —20— 3:007 —30— S:WSD:3 —30— 站后节点 —100— S:SCC:3，沿 x
   * 轴、坐标与边长一致；联锁足迹目录完整。
   */
  private static SimpleRailGraph chain(UUID worldId) {
    Map<NodeId, RailNode> nodes = new LinkedHashMap<>();
    nodes.put(P4, railNode(P4, NodeType.WAYPOINT, 0.0));
    nodes.put(P5, railNode(P5, NodeType.WAYPOINT, 40.0));
    nodes.put(P6, railNode(P6, NodeType.WAYPOINT, 80.0));
    nodes.put(P7, railNode(P7, NodeType.WAYPOINT, 100.0));
    nodes.put(WSD, railNode(WSD, NodeType.STATION, 130.0));
    nodes.put(BEYOND, railNode(BEYOND, NodeType.WAYPOINT, 160.0));
    nodes.put(SCC, railNode(SCC, NodeType.STATION, 260.0));
    Map<EdgeId, RailEdge> edges = new LinkedHashMap<>();
    putEdge(edges, P4, P5, 40);
    putEdge(edges, P5, P6, 40);
    putEdge(edges, P6, P7, 20);
    putEdge(edges, P7, WSD, 30);
    putEdge(edges, WSD, BEYOND, 30);
    putEdge(edges, BEYOND, SCC, 100);
    Map<EdgeId, RailEdgeFootprint> footprints = new LinkedHashMap<>();
    int z = 0;
    for (EdgeId id : edges.keySet()) {
      footprints.put(
          id, new RailEdgeFootprint(1, true, Set.of(new RailFootprintCell(0, 64, 100 + z++))));
    }
    return new SimpleRailGraph(
        nodes, edges, Set.of(), RailInterlockingState.from(worldId, edges.keySet(), footprints));
  }

  private static RailNode railNode(NodeId id, NodeType type, double x) {
    return new SignRailNode(id, type, new Vector(x, 64.0, 0.0), Optional.empty(), Optional.empty());
  }

  private static void putEdge(Map<EdgeId, RailEdge> edges, NodeId from, NodeId to, int length) {
    EdgeId id = EdgeId.undirected(from, to);
    edges.put(id, new RailEdge(id, from, to, length, -1.0, true, Optional.empty()));
  }
}
