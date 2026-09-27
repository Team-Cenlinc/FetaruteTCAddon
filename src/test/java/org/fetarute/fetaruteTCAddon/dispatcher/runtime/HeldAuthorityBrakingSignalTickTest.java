package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchTestFixtures.sectionlessGraph;
import static org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchTestFixtures.testConfigView;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bergerkiller.bukkit.tc.controller.components.RailState;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.util.Vector;
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
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspect;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspectPolicy;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SimpleOccupancyManager;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeRegistry;
import org.junit.jupiter.api.Test;

/**
 * 运行中延伸授权被拒：沿已持有的授权刹到终点前，而不是当拍清零速度、把前方授权放掉。
 *
 * <p>夹具 A —100— B —50— C —50— D —100— E（route A→E），列车 10 bps、减速度 1。车头 x=70 时一拍放行，窗口到 C；随后 DS 占住 D。
 * 车头走到 x=95，窗口要伸到 D、被拒——以前当拍停车并只留 A、A→B；现在前方 B→C、C 仍归本车，限速沿“到 C 前 2 格”的制动曲线收敛。
 * 夹具带完整的联锁足迹目录，停车保持才会按实际车体收缩；目录不完整时一律 fail-retain，分不出新旧行为。
 */
class HeldAuthorityBrakingSignalTickTest {

  private static final String TRAIN = "SURC-MT-LO-6900";
  private static final String BLOCKER = "SURC-DS-LH-0368";
  private static final NodeId A = NodeId.of("SURC:OFL:MLU:2:001");
  private static final NodeId B = NodeId.of("SURC:OFL:MLU:2:002");
  private static final NodeId C = NodeId.of("SURC:OFL:MLU:2:003");
  private static final NodeId D = NodeId.of("SURC:OFL:MLU:2:004");
  private static final NodeId E = NodeId.of("SURC:S:OFL:2");
  private static final OccupancyResource EDGE_BC =
      OccupancyResource.forEdge(EdgeId.undirected(B, C));
  private static final OccupancyResource NODE_C = OccupancyResource.forNode(C);

  /** 被拒后继续刹车、下一拍仍能证明授权在手；停稳以后照旧收缩到当前位置，停因明细去掉刹车后缀。 */
  @Test
  void refusedMovingTrainBrakesWithinItsHeldAuthorityUntilItStops() {
    Scenario scenario = new Scenario();
    scenario.grantWindowToC(ResourceIntent.MOVEMENT_REQUIRED);

    FakeTrain braking = scenario.tick(95.0, true);

    assertEquals(SignalAspect.STOP, scenario.signal());
    assertEquals(0, braking.stopCalls, "有授权可刹，不当拍清零速度");
    assertEquals(0, braking.hardStopCalls);
    assertHeldByTrain(scenario.manager, EDGE_BC);
    assertHeldByTrain(scenario.manager, NODE_C);
    ControlDiagnostics firstTick = scenario.diagnostics();
    assertEquals("stop_curve", firstTick.finalLimiterSource());
    assertEquals(10.0, firstTick.finalTargetBps(), 1.0e-6, "离停车点还远：保持当前速度，不借 STOP 加速");
    assertTrue(scenario.stopDetail().endsWith(":braking=held-authority"), scenario.stopDetail());

    FakeTrain stillBraking = scenario.tick(99.0, true);

    assertEquals(0, stillBraking.stopCalls, "上一拍保住的授权下一拍仍然证明得了");
    assertHeldByTrain(scenario.manager, EDGE_BC);
    assertEquals(Math.sqrt(2.0 * (150 - 99 - 2)), scenario.diagnostics().finalTargetBps(), 1.0e-6);

    scenario.tick(99.0, false);

    assertTrue(scenario.manager.getClaim(EDGE_BC).isEmpty(), "停稳以后照旧收缩到当前位置");
    assertFalse(scenario.stopDetail().contains(":braking="), scenario.stopDetail());
  }

  /** 车头位置读不准（插值被钳到首条边末端）：退回旧行为，当拍停车、只留当前位置。 */
  @Test
  void withoutAReliableHeadPositionTheRefusedTrainStopsAtOnce() {
    Scenario scenario = new Scenario();
    scenario.grantWindowToC(ResourceIntent.MOVEMENT_REQUIRED);

    FakeTrain instant = scenario.tick(120.0, true);

    assertEquals(1, instant.stopCalls);
    assertTrue(scenario.manager.getClaim(EDGE_BC).isEmpty());
    assertTrue(scenario.manager.getClaim(NODE_C).isEmpty());
    assertTrue(
        scenario.stopDetail().endsWith(":braking=instant:head-position-unknown"),
        scenario.stopDetail());
  }

  /** 挡在前面的只是保护性占用（PROTECTIVE_RETAIN_HOLD）：同样沿已持有授权刹车。 */
  @Test
  void protectiveBlockerAheadAlsoBrakesWithinTheHeldAuthority() {
    Scenario scenario = new Scenario();
    scenario.grantWindowToC(ResourceIntent.PROTECTIVE_RETAIN);

    FakeTrain braking = scenario.tick(95.0, true);

    assertEquals(0, braking.stopCalls);
    assertHeldByTrain(scenario.manager, EDGE_BC);
    RuntimeStopState stop = scenario.service.getActiveStopState(TRAIN).orElseThrow();
    assertEquals("PROTECTIVE_RETAIN_HOLD", stop.reasonCode());
    assertTrue(stop.detail().endsWith(":braking=held-authority"), stop.detail());
  }

  private static void assertHeldByTrain(
      SimpleOccupancyManager manager, OccupancyResource resource) {
    OccupancyClaim claim =
        manager.getClaim(resource).orElseThrow(() -> new AssertionError(resource + " 已被放掉"));
    assertEquals(TRAIN, claim.trainName());
    assertEquals(ClaimRole.MOVEMENT_REQUIRED, claim.role());
  }

  /** 一列 MT 与挡在 D 上的 DS。 */
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
            "FTA_ROUTE_INDEX=0");
    private final RouteProgressRegistry registry = new RouteProgressRegistry();
    private final RuntimeDispatchService service;

    private Scenario() {
      RouteDefinition route =
          new RouteDefinition(RouteId.of("MT-2F_Short"), List.of(A, E), Optional.empty());
      registry.initFromTags(TRAIN, tags.properties(), route);
      service = service(manager, worldId, route, registry);
    }

    /** 车头 x=70 放行一拍，窗口到 C；然后 DS 以 {@code blockerIntent} 占住 D。 */
    private void grantWindowToC(ResourceIntent blockerIntent) {
      tick(70.0, true);
      assertEquals(SignalAspect.PROCEED, signal());
      assertHeldByTrain(manager, NODE_C);
      assertTrue(
          manager
              .acquire(
                  new OccupancyRequest(
                      BLOCKER,
                      Optional.empty(),
                      Instant.now(),
                      List.of(OccupancyResource.forNode(D)),
                      Map.of(),
                      Map.of(),
                      0,
                      AuthorizationPurpose.RUNTIME_MOVE,
                      Map.of(),
                      Map.of(OccupancyResource.forNode(D), blockerIntent)))
              .allowed());
    }

    private FakeTrain tick(double headX, boolean moving) {
      FakeTrain train = new FakeTrain(worldId, tags.properties(), moving, moving ? 0.5 : 0.0);
      RailState railState = mock(RailState.class);
      when(railState.positionLocation()).thenReturn(new Location(null, headX, 64.0, 0.0));
      train.railState = Optional.of(railState);
      service.handleSignalTick(train, false);
      return train;
    }

    private SignalAspect signal() {
      return registry.get(TRAIN).orElseThrow().lastSignal();
    }

    private ControlDiagnostics diagnostics() {
      return service.getDiagnostics(TRAIN).orElseThrow();
    }

    private String stopDetail() {
      return service.getActiveStopState(TRAIN).orElseThrow().detail();
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
    when(railGraphService.effectiveSpeedLimitBlocksPerSecond(any(), any(), any(), anyDouble()))
        .thenReturn(1000.0);
    RouteDefinitionCache routeDefinitions = mock(RouteDefinitionCache.class);
    when(routeDefinitions.findByCodes("op", "l1", "r1")).thenReturn(Optional.of(route));
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

  /** A —100— B —50— C —50— D —100— E，节点沿 x 轴、坐标与边长一致。 */
  private static SimpleRailGraph chain(UUID worldId) {
    Map<NodeId, RailNode> nodes = new LinkedHashMap<>();
    nodes.put(A, railNode(A, NodeType.WAYPOINT, 0.0));
    nodes.put(B, railNode(B, NodeType.WAYPOINT, 100.0));
    nodes.put(C, railNode(C, NodeType.WAYPOINT, 150.0));
    nodes.put(D, railNode(D, NodeType.WAYPOINT, 200.0));
    nodes.put(E, railNode(E, NodeType.STATION, 300.0));
    Map<EdgeId, RailEdge> edges = new LinkedHashMap<>();
    putEdge(edges, A, B, 100);
    putEdge(edges, B, C, 50);
    putEdge(edges, C, D, 50);
    putEdge(edges, D, E, 100);
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
