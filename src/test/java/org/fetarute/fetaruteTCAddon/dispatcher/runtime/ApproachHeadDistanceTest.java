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
 * 进站包络按车头距离进入与求值。
 *
 * <p>夹具：A —200— W —100— 车站，进站限速 6 bps，线路限速 20 bps，减速度 1.0，最后 1 条边（100 格）须已降到进站限速。旧口径从 A 量起， 车头在 A 与
 * W 之间时距离恒为 300，进不了“窗口 96 + 预减速 64”，要等过 W 才一次性进入——那一拍目标速度从 20 直接掉到进站曲线上。 实服 2026-09-27
 * 进站时“一下就降下去”即此。区界（160 格）处区内曲线 √(36 + 2·60) ≈ 12.5 已低于线路速度，区外还要有导入制动， 从 √(36 + 2·(d − 100)) = 20 即约
 * 282 格处开始减速。
 */
class ApproachHeadDistanceTest {

  private static final String TRAIN = "SURC-MT-LP-5410";
  private static final NodeId A = NodeId.of("SURC:PPK:RVS:1:002");
  private static final NodeId W = NodeId.of("SURC:PPK:RVS:1:001");
  private static final NodeId STATION = NodeId.of("SURC:S:PPK:1");
  private static final double APPROACH_BPS = 6.0;
  private static final double LINE_BPS = 20.0;

  /** 车头离车站还有 290 格：导入制动尚未收紧，按线路速度跑，诊断不显示进站。 */
  @Test
  void farFromTheStationKeepsLineSpeed() {
    ControlDiagnostics diagnostics = signalTick(10.0);

    assertEquals("none", diagnostics.approachKind());
    assertEquals(LINE_BPS, diagnostics.finalTargetBps(), 1.0e-6, diagnostics.toString());
  }

  /** 车头离车站 200 格：还在进站区外，但导入制动已在收紧，保证到区界时降到区内曲线，而不是进区一刀切。 */
  @Test
  void leadInBrakingStartsBeforeThePreviewZone() {
    ControlDiagnostics diagnostics = signalTick(100.0);

    double expected = Math.sqrt(APPROACH_BPS * APPROACH_BPS + 2.0 * 1.0 * (200.0 - 100.0));
    assertEquals("station", diagnostics.approachKind(), "导入制动在限速时应显示进站诊断");
    assertEquals("approach_curve", diagnostics.finalLimiterSource(), diagnostics.toString());
    assertEquals(expected, diagnostics.finalTargetBps(), 1.0e-6, diagnostics.toString());
    assertTrue(
        diagnostics.approachReason().contains("engaged=false"), diagnostics.approachReason());
  }

  /** 车头离车站 140 格（从 A 量起仍是 300 格）：已进入预减速区，按车头距离算进站制动曲线。 */
  @Test
  void enteringThePreviewZoneBetweenNodesEngagesByHeadDistance() {
    ControlDiagnostics diagnostics = signalTick(160.0);

    double expected = Math.sqrt(APPROACH_BPS * APPROACH_BPS + 2.0 * 1.0 * (140.0 - 100.0));
    assertEquals("station", diagnostics.approachKind());
    assertEquals("approach_curve", diagnostics.finalLimiterSource(), diagnostics.toString());
    assertEquals(expected, diagnostics.finalTargetBps(), 1.0e-6, diagnostics.toString());
    assertTrue(diagnostics.approachReason().contains("distance=140"), diagnostics.approachReason());
    assertTrue(
        diagnostics.approachReason().contains("node_distance=300"), diagnostics.approachReason());
  }

  /** 车头从区外一路进到区内，目标速度只随车头前进连续下降，区界与节点处都不跳变。 */
  @Test
  void targetFallsContinuouslyWithHeadPosition() {
    double previous = Double.POSITIVE_INFINITY;
    for (int step = 0; step < 38; step++) {
      double target = signalTick(10.0 + step * 5.0).finalTargetBps();
      assertTrue(target <= previous + 1.0e-9, "目标速度不得随车头前进而升高");
      if (Double.isFinite(previous)) {
        assertTrue(previous - target < 1.0, "车头前进 5 格，目标速度不应跳变超过 1 bps");
      }
      previous = target;
    }
  }

  /** 信号 tick 把进站曲线交给逐 tick 斜坡：进站中登记并给出保持上限；远处只登记前方约束，不挡推进放行。 */
  @Test
  void signalTickHandsTheApproachCurveToTheRamp() throws ReflectiveOperationException {
    SpeedLimitRamp approaching = new SpeedLimitRamp(tick -> () -> {});
    FakeTrain inZone = signalTick(160.0, approaching).train();
    double expected = Math.sqrt(APPROACH_BPS * APPROACH_BPS + 2.0 * 1.0 * (140.0 - 100.0));
    assertEquals(1, approaching.size(), "进站中的运行列车应登记到斜坡");
    assertEquals(expected, approaching.holdLimitBps(inZone).orElseThrow(), 1.0e-6);

    SpeedLimitRamp far = new SpeedLimitRamp(tick -> () -> {});
    FakeTrain farTrain = signalTick(10.0, far).train();
    assertTrue(far.holdLimitBps(farTrain).isEmpty(), "导入制动尚未收紧时不得挡推进放行");
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

  /** A —200— W —100— 车站，节点坐标沿 x 轴与边长一致，供车头位置插值。 */
  private static SimpleRailGraph stationApproachGraph() {
    Map<NodeId, RailNode> nodes = new LinkedHashMap<>();
    nodes.put(A, node(A, NodeType.WAYPOINT, 0.0));
    nodes.put(W, node(W, NodeType.WAYPOINT, 200.0));
    nodes.put(STATION, node(STATION, NodeType.STATION, 300.0));
    Map<EdgeId, RailEdge> edges = new LinkedHashMap<>();
    edge(edges, A, W, 200);
    edge(edges, W, STATION, 100);
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
