package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchTestFixtures.routeStop;
import static org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchTestFixtures.sectionlessGraph;
import static org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchTestFixtures.testConfigView;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import com.bergerkiller.bukkit.tc.controller.components.RailState;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.util.Vector;
import org.fetarute.fetaruteTCAddon.company.model.RouteStopPassType;
import org.fetarute.fetaruteTCAddon.config.ConfigManager;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.model.SpeedCeiling;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.model.SpeedCurve;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailEdge;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphService;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.SignRailNode;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.SimpleRailGraph;
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
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.supervisor.SmartDispatcherMode;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.AuthorityHandoffSupport;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyDecision;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyManager;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyRequest;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspect;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeRegistry;
import org.junit.jupiter.api.Test;

/**
 * 进站限速按编表运行曲线的口径执行，距离从车头量起。
 *
 * <p>夹具：A —400— W —90— 车站，进站限速 6 bps，线路限速 20 bps，加减速 1.0，进站窗口 96 格。W 离站 90 格、在窗口内，
 * 按编表运行曲线的判据（{@code StopApproach#zones}）进站限速区从 W 起。目标速度就是编表与控车共用的速度天花板（{@link
 * SpeedCeiling}）在车头处的值：区外沿 S 形制动曲线降到区起点。旧口径从 A 量起、车头在 A 与 W 之间距离不变， 要等过 W 才一次性降下来；实服 2026-09-27
 * 进站时“一下就降下去”即此。
 */
class ApproachHeadDistanceTest {

  private static final String TRAIN = "SURC-MT-LP-5410";
  private static final NodeId A = NodeId.of("SURC:PPK:RVS:1:002");
  private static final NodeId W = NodeId.of("SURC:PPK:RVS:1:001");
  private static final NodeId STATION = NodeId.of("SURC:S:PPK:1");
  private static final double APPROACH_BPS = 6.0;
  private static final double LINE_BPS = 20.0;
  private static final double ZONE_START = 400.0;
  private static final double STATION_X = 490.0;

  /** 车头在 x=10：离限速区还有 390 格，远在制动段之外，按线路速度跑，诊断不显示进站。 */
  @Test
  void farFromTheStationKeepsLineSpeed() {
    ControlDiagnostics diagnostics = signalTick(10.0);

    assertEquals("none", diagnostics.approachKind());
    assertEquals(LINE_BPS, diagnostics.finalTargetBps(), 1.0e-6, diagnostics.toString());
  }

  /** 车头在 x=300（从 A 量起仍是 490 格）：按车头到限速区起点 100 格制动，与编表运行曲线同一个天花板。 */
  @Test
  void brakesIntoTheZoneThatStartsAtTheFirstNodeWithinTheWindow() {
    ControlDiagnostics diagnostics = signalTick(300.0);

    assertEquals("station", diagnostics.approachKind(), "进站限速在收紧时应显示进站诊断");
    assertEquals("approach_curve", diagnostics.finalLimiterSource(), diagnostics.toString());
    assertTrue(expectedCeiling(300.0) < LINE_BPS, "x=300 应已在制动段内");
    assertEquals(
        expectedCeiling(300.0), diagnostics.finalTargetBps(), 1.0e-6, diagnostics.toString());
    assertTrue(diagnostics.approachReason().contains("distance=190"), diagnostics.approachReason());
    assertTrue(
        diagnostics.approachReason().contains("node_distance=490"), diagnostics.approachReason());
    assertTrue(
        diagnostics.approachReason().contains("engaged=false"), diagnostics.approachReason());
  }

  /** 车头从远处一路开到限速区前，目标速度只随车头前进连续下降，不在节点处跳变。 */
  @Test
  void targetFallsContinuouslyWithHeadPosition() {
    double previous = Double.POSITIVE_INFINITY;
    for (int step = 0; step < 50; step++) {
      double target = signalTick(150.0 + step * 5.0).finalTargetBps();
      assertTrue(target <= previous + 1.0e-9, "目标速度不得随车头前进而升高");
      if (Double.isFinite(previous)) {
        assertTrue(previous - target < 1.0, "车头前进 5 格，目标速度不应跳变超过 1 bps");
      }
      previous = target;
    }
  }

  /** 信号 tick 把速度天花板交给逐 tick 斜坡：保持上限就是天花板；远处它等于线路速度，不会把推进放行压到线路速度以下。 */
  @Test
  void signalTickHandsTheApproachCurveToTheRamp() throws ReflectiveOperationException {
    SpeedLimitRamp approaching = new SpeedLimitRamp(tick -> () -> {});
    FakeTrain braking = signalTick(350.0, approaching).train();
    assertEquals(1, approaching.size(), "进站减速中的运行列车应登记到斜坡");
    assertEquals(expectedCeiling(350.0), approaching.holdLimitBps(braking).orElseThrow(), 1.0e-6);

    SpeedLimitRamp far = new SpeedLimitRamp(tick -> () -> {});
    FakeTrain farTrain = signalTick(10.0, far).train();
    assertEquals(LINE_BPS, far.holdLimitBps(farTrain).orElseThrow(), 1.0e-9, "远处的保持上限就是线路速度");
  }

  /** 编表与控车共用的天花板在车头 x 处的值：沿途两条边、进站限速区 [W, 车站] 与到站速度。 */
  private static double expectedCeiling(double headX) {
    return SpeedCeiling.of(
            new double[] {ZONE_START - headX, STATION_X - ZONE_START},
            new double[] {LINE_BPS, LINE_BPS},
            List.of(
                new SpeedCeiling.Cap(ZONE_START - headX, STATION_X - headX, APPROACH_BPS),
                new SpeedCeiling.Cap(STATION_X - headX, STATION_X - headX, APPROACH_BPS)),
            APPROACH_BPS,
            new SpeedCurve(1.0, 1.0))
        .limitBps(0.0);
  }

  private static ControlDiagnostics signalTick(double headX) {
    try {
      return signalTick(headX, null).diagnostics();
    } catch (ReflectiveOperationException ex) {
      throw new IllegalStateException(ex);
    }
  }

  /** 一拍信号 tick 的诊断与列车。 */
  private record TickRun(ControlDiagnostics diagnostics, FakeTrain train) {}

  /**
   * 跑一拍信号 tick。
   *
   * @param ramp 非空时换上由它驱动的控车门面，并让属性替身记住 speedLimit，以便观察斜坡登记
   */
  private static TickRun signalTick(double headX, SpeedLimitRamp ramp)
      throws ReflectiveOperationException {
    UUID worldId = UUID.randomUUID();
    RouteDefinition route =
        new RouteDefinition(RouteId.of("MT-2F_Short"), List.of(A, STATION), Optional.empty());
    TagStore tags =
        new TagStore(
            TRAIN,
            "FTA_OPERATOR_CODE=op",
            "FTA_LINE_CODE=l1",
            "FTA_ROUTE_CODE=r1",
            "FTA_ROUTE_INDEX=0");
    ConfigManager configManager = mock(ConfigManager.class);
    ConfigManager.ConfigView base = testConfigView(20, LINE_BPS, 1, 1, SmartDispatcherMode.OFF);
    when(configManager.current()).thenReturn(withApproachSpeed(base, APPROACH_BPS));
    RailGraphService railGraphService = mock(RailGraphService.class);
    when(railGraphService.getSnapshot(worldId))
        .thenReturn(
            Optional.of(
                new RailGraphService.RailGraphSnapshot(
                    sectionlessGraph(stationApproachGraph()), Instant.now())));
    when(railGraphService.effectiveSpeedLimitBlocksPerSecond(any(), any(), any(), anyDouble()))
        .thenReturn(LINE_BPS);
    RouteDefinitionCache routeDefinitions = mock(RouteDefinitionCache.class);
    when(routeDefinitions.findByCodes("op", "l1", "r1")).thenReturn(Optional.of(route));
    when(routeDefinitions.findStop(any(), eq(1)))
        .thenReturn(Optional.of(routeStop(1, STATION, RouteStopPassType.STOP)));

    OccupancyManager occupancyManager =
        mock(OccupancyManager.class, withSettings().extraInterfaces(AuthorityHandoffSupport.class));
    when(((AuthorityHandoffSupport) occupancyManager).holdsHardAuthority(any())).thenReturn(true);
    when(((AuthorityHandoffSupport) occupancyManager)
            .migrateAuthorityOwner(anyString(), anyString()))
        .thenReturn(true);
    when(occupancyManager.snapshotClaims()).thenReturn(List.of());
    when(occupancyManager.canEnter(any()))
        .thenAnswer(
            invocation -> {
              OccupancyRequest request = invocation.getArgument(0);
              return new OccupancyDecision(true, request.now(), SignalAspect.PROCEED, List.of());
            });
    when(occupancyManager.acquire(any()))
        .thenAnswer(
            invocation -> {
              OccupancyRequest request = invocation.getArgument(0);
              return new OccupancyDecision(true, request.now(), SignalAspect.PROCEED, List.of());
            });
    RouteProgressRegistry registry = new RouteProgressRegistry();
    registry.initFromTags(TRAIN, tags.properties(), route);
    RuntimeDispatchService service =
        new RuntimeDispatchService(
            occupancyManager,
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
    FakeTrain train = new FakeTrain(worldId, tags.properties(), true, LINE_BPS / 20.0);
    RailState head = mock(RailState.class);
    when(head.positionLocation()).thenReturn(new Location(null, headX, 64.0, 0.0));
    train.railState = Optional.of(head);

    if (ramp != null) {
      double[] speedLimit = {0.0};
      doAnswer(
              invocation -> {
                speedLimit[0] = invocation.getArgument(0, Double.class);
                return null;
              })
          .when(tags.properties())
          .setSpeedLimit(anyDouble());
      when(tags.properties().getSpeedLimit()).thenAnswer(invocation -> speedLimit[0]);
      Field controller = RuntimeDispatchService.class.getDeclaredField("runtimeTrainController");
      controller.setAccessible(true);
      controller.set(service, new RuntimeTrainController(new TrainLaunchManager(ramp)));
    }

    service.handleSignalTick(train, false);

    return new TickRun(service.getDiagnostics(TRAIN).orElseThrow(), train);
  }

  /** A —400— W —90— 车站，节点坐标沿 x 轴与边长一致，供车头位置插值。 */
  private static SimpleRailGraph stationApproachGraph() {
    Map<NodeId, RailNode> nodes = new LinkedHashMap<>();
    nodes.put(A, node(A, NodeType.WAYPOINT, 0.0));
    nodes.put(W, node(W, NodeType.WAYPOINT, ZONE_START));
    nodes.put(STATION, node(STATION, NodeType.STATION, STATION_X));
    Map<EdgeId, RailEdge> edges = new LinkedHashMap<>();
    edge(edges, A, W, 400);
    edge(edges, W, STATION, 90);
    return new SimpleRailGraph(nodes, edges, java.util.Set.of());
  }

  private static RailNode node(NodeId id, NodeType type, double x) {
    return new SignRailNode(id, type, new Vector(x, 64.0, 0.0), Optional.empty(), Optional.empty());
  }

  private static void edge(Map<EdgeId, RailEdge> edges, NodeId from, NodeId to, int lengthBlocks) {
    EdgeId id = EdgeId.undirected(from, to);
    edges.put(id, new RailEdge(id, from, to, lengthBlocks, -1.0, true, Optional.empty()));
  }

  private static ConfigManager.ConfigView withApproachSpeed(
      ConfigManager.ConfigView base, double approachSpeedBps) {
    ConfigManager.RuntimeSettings runtime = base.runtimeSettings();
    ConfigManager.RuntimeSettings withApproach =
        new ConfigManager.RuntimeSettings(
            runtime.dispatchTickIntervalTicks(),
            runtime.launchCooldownTicks(),
            runtime.lookaheadEdges(),
            runtime.minClearEdges(),
            runtime.rearGuardEdges(),
            runtime.switcherZoneEdges(),
            approachSpeedBps,
            runtime.cautionSpeedBps(),
            runtime.approachDepotSpeedBps(),
            runtime.speedCurveEnabled(),
            runtime.speedCurveType(),
            runtime.speedCurveFactor(),
            runtime.speedCurveEarlyBrakeBlocks(),
            runtime.failoverStallSpeedBps(),
            runtime.failoverStallTicks(),
            runtime.failoverUnreachableStop(),
            runtime.movementAuthorityEnabled(),
            runtime.movementAuthorityStopMarginBlocks(),
            runtime.movementAuthorityCautionMarginBlocks(),
            runtime.speedCommandHysteresisBps(),
            runtime.speedCommandAccelFactor(),
            runtime.speedCommandDecelFactor(),
            runtime.distanceCacheRefreshSeconds(),
            runtime.hudBossBarEnabled(),
            runtime.hudBossBarTickIntervalTicks(),
            runtime.hudBossBarTemplate(),
            runtime.hudActionBarEnabled(),
            runtime.hudActionBarTickIntervalTicks(),
            runtime.hudActionBarTemplate(),
            runtime.hudPlayerDisplayEnabled(),
            runtime.hudPlayerDisplayTickIntervalTicks(),
            runtime.hudPlayerDisplayTemplate());
    return new ConfigManager.ConfigView(
        base.configVersion(),
        base.debugEnabled(),
        base.locale(),
        base.storageSettings(),
        base.graphSettings(),
        base.autoStationSettings(),
        withApproach,
        base.spawnSettings(),
        base.trainConfigSettings(),
        base.reclaimSettings(),
        base.smartDispatcherSettings(),
        base.healthSettings());
  }
}
