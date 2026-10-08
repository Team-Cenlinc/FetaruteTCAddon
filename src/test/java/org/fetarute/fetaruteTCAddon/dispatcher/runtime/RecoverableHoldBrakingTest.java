package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchTestFixtures.routeStop;
import static org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchTestFixtures.sectionlessGraph;
import static org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchTestFixtures.testConfigView;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bergerkiller.bukkit.tc.controller.components.RailState;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
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
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.ControlDiagnostics;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.AuthorizationPurpose;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.ClaimRole;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyClaim;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyRequest;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyResource;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.ResourceIntent;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspectPolicy;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SimpleOccupancyManager;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeRegistry;
import org.junit.jupiter.api.Test;

/**
 * 可恢复保持（SMART_DISPATCH_RECOVERABLE_HOLD）时运行中的车沿上一拍已授予的授权刹车，而不是当拍清零速度。
 *
 * <p>可恢复回滚只放掉本拍新拿到的硬授权，上一拍授予、仍以 {@code MOVEMENT_REQUIRED} 归本车的那段原样留下——与延伸被拒时沿已持有授权刹车是同一份证据。
 * 本拍新签的令牌不能拿来刹车：它描述的窗口可能已被回滚放掉，且 TrainCarts 还留着 destination 时它会被转成未激活的待定令牌。
 *
 * <p>夹具与 {@link RecoverableHoldBodyRetainTest} 相同：车头刚过 {@code 3:007}，下一路径点是车站 {@code
 * S:WSD:3}（硬窗口封顶在站台）， 列车 15 bps、减速度 1，离站台太近、移动授权降为 STOP 进入可恢复保持。TrainCarts destination
 * 在这里会真正写入并读回，与实服一致。
 */
class RecoverableHoldBrakingTest {

  private static final String TRAIN = "SURC-MT-LH-2689";
  private static final String BLOCKER = "SURC-DS-LH-3597";
  private static final NodeId P4 = NodeId.of("SURC:SPB:WSD:3:004");
  private static final NodeId P5 = NodeId.of("SURC:SPB:WSD:3:005");
  private static final NodeId P6 = NodeId.of("SURC:SPB:WSD:3:006");
  private static final NodeId P7 = NodeId.of("SURC:SPB:WSD:3:007");
  private static final NodeId WSD = NodeId.of("SURC:S:WSD:3");
  private static final NodeId BEYOND = NodeId.of("SURC:WSD:SCC:3:001");
  private static final NodeId SCC = NodeId.of("SURC:S:SCC:3");
  private static final RouteId ROUTE_ID = RouteId.of("MT-LH");
  private static final OccupancyResource EDGE_P7_WSD =
      OccupancyResource.forEdge(EdgeId.undirected(P7, WSD));
  private static final OccupancyResource NODE_WSD = OccupancyResource.forNode(WSD);

  /** 进入可恢复保持的那一拍与仍在运动的下一拍，都沿上一拍授予到站台的授权刹到“站台 − 停车余量”前；那段授权与它的令牌都留在本车名下。 */
  @Test
  void movingRecoverableHoldBrakesWithinTheAuthorityGrantedTheTickBefore() {
    Scenario scenario = new Scenario();
    scenario.tick(103.0);
    assertEquals(
        Optional.empty(),
        scenario.service.getActiveStopState(TRAIN).map(RuntimeStopState::reasonCode),
        "第一拍应正常放行");
    MovementAuthorizationToken granted =
        scenario.service.movementAuthorityView(TRAIN).orElseThrow();
    assertTrue(granted.active());
    assertEquals(Optional.of(WSD), granted.authorityEndNode());
    scenario.blockBeyondStation();

    FakeTrain braking = scenario.tick(107.5);

    RuntimeStopState stop = scenario.stop();
    assertEquals("SMART_DISPATCH_RECOVERABLE_HOLD", stop.reasonCode());
    assertEquals(0, braking.stopCalls, "上一拍授予的授权仍归本车，沿它刹车，不当拍清零速度");
    assertEquals(0, braking.hardStopCalls);
    assertTrue(stop.detail().endsWith(":braking=held-authority"), stop.detail());
    assertHeldMovementRequired(scenario.manager, EDGE_P7_WSD);
    assertHeldMovementRequired(scenario.manager, NODE_WSD);
    ControlDiagnostics diagnostics = scenario.service.getDiagnostics(TRAIN).orElseThrow();
    assertEquals("stop_curve", diagnostics.finalLimiterSource());
    assertEquals(Math.sqrt(2.0 * (30 - 8 - 2)), diagnostics.finalTargetBps(), 1.0e-6);
    assertEquals(
        granted,
        scenario.service.movementAuthorityView(TRAIN).orElseThrow(),
        "刹车所依据的那张令牌仍是本车生效的授权");

    FakeTrain stillBraking = scenario.tick(115.0);

    assertEquals("SMART_DISPATCH_RECOVERABLE_HOLD", scenario.stop().reasonCode());
    assertEquals(0, stillBraking.stopCalls, "下一拍仍能证明同一份授权在手");
    assertEquals(
        Math.sqrt(2.0 * (30 - 15 - 2)),
        scenario.service.getDiagnostics(TRAIN).orElseThrow().finalTargetBps(),
        1.0e-6);
  }

  /** 上一拍授予的站台节点本拍开头已不在本车名下：本拍重新拿到的又被回滚放掉，证明不了授权在手，照旧当拍停车，也不把上一拍的令牌放回去。 */
  @Test
  void withoutTheWholeEarlierAuthorityTheHoldStopsAtOnce() {
    Scenario scenario = new Scenario();
    scenario.tick(103.0);
    scenario.manager.releaseResource(NODE_WSD, Optional.of(TRAIN));
    scenario.blockBeyondStation();

    FakeTrain instant = scenario.tick(107.5);

    RuntimeStopState stop = scenario.stop();
    assertEquals("SMART_DISPATCH_RECOVERABLE_HOLD", stop.reasonCode());
    assertEquals(1, instant.stopCalls);
    assertTrue(stop.detail().endsWith(":braking=instant:authority-released"), stop.detail());
    assertFalse(
        scenario.service.movementAuthorityView(TRAIN).orElseThrow().active(), "没刹车就保持可恢复保持原有的待定令牌");
  }

  private static void assertHeldMovementRequired(
      SimpleOccupancyManager manager, OccupancyResource resource) {
    OccupancyClaim claim =
        manager.getClaim(resource).orElseThrow(() -> new AssertionError(resource + " 已被放掉"));
    assertEquals(TRAIN, claim.trainName());
    assertEquals(ClaimRole.MOVEMENT_REQUIRED, claim.role());
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
    private final AtomicReference<String> destination = new AtomicReference<>("");
    private final RouteProgressRegistry registry = new RouteProgressRegistry();
    private final RuntimeDispatchService service;

    private Scenario() {
      doAnswer(
              invocation -> {
                destination.set(invocation.getArgument(0));
                return null;
              })
          .when(tags.properties())
          .setDestination(anyString());
      when(tags.properties().getDestination()).thenAnswer(invocation -> destination.get());
      RouteDefinition route =
          new RouteDefinition(
              ROUTE_ID, List.of(P4, P5, P6, P7, WSD, BEYOND, SCC), Optional.empty());
      registry.initFromTags(TRAIN, tags.properties(), route);
      service = service(manager, worldId, route, registry);
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

    /** 以 15 bps 运行、车头在 {@code headX} 的一拍。 */
    private FakeTrain tick(double headX) {
      FakeTrain train = new FakeTrain(worldId, tags.properties(), true, 0.75);
      train.estimatedTrainLengthBlocks = OptionalDouble.of(34.0);
      RailState railState = mock(RailState.class);
      when(railState.positionLocation()).thenReturn(new Location(null, headX, 64.0, 0.0));
      train.railState = Optional.of(railState);
      service.handleSignalTick(train, false);
      return train;
    }

    private RuntimeStopState stop() {
      return service.getActiveStopState(TRAIN).orElseThrow();
    }
  }

  private static RuntimeDispatchService service(
      SimpleOccupancyManager manager,
      UUID worldId,
      RouteDefinition route,
      RouteProgressRegistry registry) {
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
        message -> {});
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
