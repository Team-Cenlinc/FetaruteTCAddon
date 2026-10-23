package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchTestFixtures.routeStop;
import static org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchTestFixtures.sectionlessGraph;
import static org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchTestFixtures.testConfigView;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import com.bergerkiller.bukkit.tc.controller.components.RailState;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
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
 * 控车沿到下一停车点的整段路径与编表运行曲线对齐。
 *
 * <ul>
 *   <li>计划停车点已由进站控制接管时，授权终点不再按“刹到 0”压速：那条曲线从上一图节点量距、刹到站牌节点停住，车会停在站牌前压不上站牌；
 *       车站由速度天花板接管（沿自动运行曲线刹到站牌之后的停车点，站牌处留保底速度），与区间停车点的进站限速无关；
 *   <li>前瞻窗口只有几条边，编表运行曲线沿整段路径反推制动：窗口外的慢速边也要按同一条制动曲线提前减速。
 * </ul>
 *
 * <p>夹具减速度 1.0，线路限速 20 bps，前瞻窗口 1 条边，Smart Dispatcher 关闭以隔离运行时控车本身。
 */
class ApproachPathAlignmentTest {

  private static final String TRAIN = "SURC-WS-LC-5666";
  private static final double LINE_BPS = 20.0;

  private static final NodeId W = NodeId.of("SURC:HHU:LWN:1:001");
  private static final NodeId STATION = NodeId.of("SURC:S:HHU:1");

  private static final NodeId A = NodeId.of("SURC:LWN:SWN:1:001");
  private static final NodeId B = NodeId.of("SURC:LWN:SWN:1:002");
  private static final NodeId C = NodeId.of("SURC:LWN:SWN:1:003");
  private static final NodeId FAR_STATION = NodeId.of("SURC:S:SWN:1");

  /** 末段 30 格进站：车站由速度天花板接管，授权终点不再“刹到 0”；进站限速设成多少（含 0）都不影响车站。 */
  @Test
  void plannedStationStopIsGovernedByTheStationCurve() {
    for (double approach : new double[] {10.0, 0.0}) {
      ControlDiagnostics diagnostics = shortLastEdgeTick(approach, 5.0);

      assertEquals(SignalAspect.PROCEED, diagnostics.currentSignal(), diagnostics.toString());
      assertTrue(diagnostics.movementAuthorityLimitBps().isEmpty(), diagnostics.toString());
      assertEquals(stationCeilingAt(5.0), diagnostics.finalTargetBps(), 1.0e-6);
      assertEquals("approach_curve", diagnostics.finalLimiterSource(), diagnostics.toString());
    }
  }

  /** 车头离站牌只剩半格：天花板留着保底速度让车压上站牌（停车点就在站牌处时，保底速度不超过余下半格里刹得住的速度）；授权终点若照旧“刹到 0”， 这里会被压到 0、车停在站牌前。 */
  @Test
  void nearTheSignTheStationKeepsTheTriggerFloor() {
    ControlDiagnostics diagnostics = shortLastEdgeTick(10.0, 29.5);

    assertTrue(diagnostics.movementAuthorityLimitBps().isEmpty(), diagnostics.toString());
    assertTrue(diagnostics.finalTargetBps() > 0.0, diagnostics.toString());
    // 调度的车头进度按整格计（29.5 → 29）
    assertEquals(
        stationCeilingAt(29.0), diagnostics.finalTargetBps(), 1.0e-6, diagnostics.toString());
  }

  /**
   * 压上站牌后 AutoStation 的停站刹车动作在走（停车位置标在站牌之后 20 格，远于半个车长）：调度的限速按动作的余下距离沿同一条曲线给， 不按半个车长压成保底速度——否则
   * TrainCarts 截速，车以保底速度爬到标志处。
   */
  @Test
  void whileAutoStationBrakesTheCeilingFollowsItsRemainingDistance() {
    ControlDiagnostics diagnostics =
        shortLastEdgeTick(
            10.0, 29.5, train -> train.stopCurveRemainingBlocks = OptionalDouble.of(20.0));

    double exit = SpeedCeiling.brakingLimitBps(ATO, LINE_BPS, 0.0, 20.0);
    assertTrue(
        exit
            > org.fetarute
                    .fetaruteTCAddon
                    .dispatcher
                    .eta
                    .model
                    .RunCurveModel
                    .STATION_TRIGGER_FLOOR_BPS
                + 1.0,
        "离标志 20 格时远高于保底速度");
    // 一段刹车：路径延长到站牌之后 20 格的停车点、刹到 0
    assertEquals(
        SpeedCeiling.of(
                new double[] {30, 20}, new double[] {LINE_BPS, LINE_BPS}, List.of(), 0.0, ATO)
            .limitBps(29.0), // 调度的车头进度按整格计（29.5 → 29）
        diagnostics.finalTargetBps(),
        1.0e-6,
        diagnostics.toString());
  }

  /** 压牌前按半个车长估停车点：车厢间距逐 tick 有零点几格的抖动，估值取整格，同一条边上天花板的输入不变（缓存才命中）；向下取整，宁可压上站牌时慢一点。 */
  @Test
  void theHalfBodyEstimateIsRoundedDownToWholeBlocks() {
    ControlDiagnostics low =
        shortLastEdgeTick(10.0, 5.0, train -> train.headToCenterBlocks = OptionalDouble.of(10.2));
    ControlDiagnostics high =
        shortLastEdgeTick(10.0, 5.0, train -> train.headToCenterBlocks = OptionalDouble.of(10.8));

    // 两者都按 10 格算：路径延长到站牌之后 10 格的停车点、刹到 0
    double expected =
        SpeedCeiling.of(
                new double[] {30, 10}, new double[] {LINE_BPS, LINE_BPS}, List.of(), 0.0, ATO)
            .limitBps(5.0);
    assertEquals(expected, low.finalTargetBps(), 1.0e-6, low.toString());
    assertEquals(expected, high.finalTargetBps(), 1.0e-6, high.toString());
  }

  private static final SpeedCurve ATO =
      SpeedCurve.ato(
          1.0,
          1.0,
          org.fetarute
              .fetaruteTCAddon
              .config
              .ConfigManager
              .TrainConfigSettings
              .DEFAULT_ATO_BRAKE_FRACTION);

  /** W —30— 车站的天花板在车头 x 处的值：没有进站限速区，自动运行曲线刹到站牌、留保底速度。 */
  private static double stationCeilingAt(double headX) {
    return SpeedCeiling.of(
            new double[] {30},
            new double[] {LINE_BPS},
            List.of(),
            org.fetarute.fetaruteTCAddon.dispatcher.eta.model.RunCurveModel.stationControlExitBps(
                SpeedCurve.ato(
                    1.0,
                    1.0,
                    org.fetarute
                        .fetaruteTCAddon
                        .config
                        .ConfigManager
                        .TrainConfigSettings
                        .DEFAULT_ATO_BRAKE_FRACTION),
                LINE_BPS,
                0.0),
            SpeedCurve.ato(
                1.0,
                1.0,
                org.fetarute
                    .fetaruteTCAddon
                    .config
                    .ConfigManager
                    .TrainConfigSettings
                    .DEFAULT_ATO_BRAKE_FRACTION))
        .limitBps(headX);
  }

  /**
   * A —100— B —100（8 bps）— C —50— 车站，B、C 为通过点。前瞻窗口只到 B，编表运行曲线却从 A 起就按慢速边反推：车头在 x=10 时约限 √(64 +
   * 2·0.75·90) ≈ 14.3（减速度乘自动运行的舒适比例，S 形略低）。
   */
  @Test
  void slowEdgeBeyondTheLookaheadWindowBrakesLikeTheRunCurve() {
    Map<NodeId, Double> xs = new LinkedHashMap<>();
    xs.put(A, 0.0);
    xs.put(B, 100.0);
    xs.put(C, 200.0);
    xs.put(FAR_STATION, 250.0);
    Map<EdgeId, Double> slowEdges = Map.of(EdgeId.undirected(B, C), 8.0);
    Map<Integer, RouteStopPassType> stops =
        Map.of(1, RouteStopPassType.PASS, 2, RouteStopPassType.PASS, 3, RouteStopPassType.STOP);

    ControlDiagnostics diagnostics =
        signalTick(
            List.of(A, B, C, FAR_STATION),
            xs,
            slowEdges,
            stops,
            6.0,
            10.0,
            LINE_BPS,
            train -> {},
            service -> {});

    // 编表与控车共用的天花板：车头之后 90 格 @20、100 格 @8、50 格 @20，车站没有进站限速区，刹到站牌、留保底速度；
    // 减速度取自动运行的舒适比例。
    double expected =
        SpeedCeiling.of(
                new double[] {90, 100, 50},
                new double[] {LINE_BPS, 8.0, LINE_BPS},
                List.of(),
                org.fetarute.fetaruteTCAddon.dispatcher.eta.model.RunCurveModel
                    .stationControlExitBps(
                        SpeedCurve.ato(
                            1.0,
                            1.0,
                            org.fetarute
                                .fetaruteTCAddon
                                .config
                                .ConfigManager
                                .TrainConfigSettings
                                .DEFAULT_ATO_BRAKE_FRACTION),
                        LINE_BPS,
                        0.0),
                SpeedCurve.ato(
                    1.0,
                    1.0,
                    org.fetarute
                        .fetaruteTCAddon
                        .config
                        .ConfigManager
                        .TrainConfigSettings
                        .DEFAULT_ATO_BRAKE_FRACTION))
            .limitBps(0.0);
    assertTrue(expected < LINE_BPS, "x=10 应已在慢速边的制动段内");
    assertEquals(expected, diagnostics.finalTargetBps(), 1.0e-6, diagnostics.toString());
    assertEquals("edge_speed_lookahead", diagnostics.finalLimiterSource(), diagnostics.toString());
  }

  /** W —30— 车站，车头在 W 之后 {@code headX} 格、以 10 bps 行驶。 */
  private static ControlDiagnostics shortLastEdgeTick(double approachSpeedBps, double headX) {
    return shortLastEdgeTick(approachSpeedBps, headX, train -> {});
  }

  /** 压牌前就按停车位置标估停车点：估出停在站牌之后 40 格（例如先到联挂站的车按挂上以后的节数选的标志），天花板从一开始就刹向那里， 不按半个车长刹到站牌、压上站牌后再提速。 */
  @Test
  void beforeTheSignAFarStopMarkIsBrakedForFromTheStart() {
    ControlDiagnostics diagnostics =
        signalTickWithService(
            5.0,
            train -> train.headToCenterBlocks = OptionalDouble.of(10.0),
            service ->
                service
                    .stationStops()
                    .setStopTail((train, name, route, node) -> OptionalDouble.of(40.7)));

    double expected =
        SpeedCeiling.of(
                new double[] {30, 40}, new double[] {LINE_BPS, LINE_BPS}, List.of(), 0.0, ATO)
            .limitBps(5.0);
    assertEquals(expected, diagnostics.finalTargetBps(), 1.0e-6, diagnostics.toString());
  }

  private static ControlDiagnostics signalTickWithService(
      double headX,
      java.util.function.Consumer<FakeTrain> setup,
      java.util.function.Consumer<RuntimeDispatchService> serviceSetup) {
    Map<NodeId, Double> xs = new LinkedHashMap<>();
    xs.put(W, 0.0);
    xs.put(STATION, 30.0);
    return signalTick(
        List.of(W, STATION),
        xs,
        Map.of(),
        Map.of(1, RouteStopPassType.STOP),
        10.0,
        headX,
        10.0,
        setup,
        serviceSetup);
  }

  private static ControlDiagnostics shortLastEdgeTick(
      double approachSpeedBps, double headX, java.util.function.Consumer<FakeTrain> setup) {
    Map<NodeId, Double> xs = new LinkedHashMap<>();
    xs.put(W, 0.0);
    xs.put(STATION, 30.0);
    return signalTick(
        List.of(W, STATION),
        xs,
        Map.of(),
        Map.of(1, RouteStopPassType.STOP),
        approachSpeedBps,
        headX,
        10.0,
        setup,
        service -> {});
  }

  /**
   * 跑一拍信号 tick。
   *
   * @param waypoints 线路节点（相邻节点在图上直接相连，边长取坐标差）
   * @param xs 节点 x 坐标，供车头位置插值
   * @param slowEdges 与线路限速不同的边限速
   * @param stops 各 route index 的停车方式
   * @param headX 车头 x 坐标
   * @param speedBps 当前车速
   */
  private static ControlDiagnostics signalTick(
      List<NodeId> waypoints,
      Map<NodeId, Double> xs,
      Map<EdgeId, Double> slowEdges,
      Map<Integer, RouteStopPassType> stops,
      double approachSpeedBps,
      double headX,
      double speedBps,
      java.util.function.Consumer<FakeTrain> setup,
      java.util.function.Consumer<RuntimeDispatchService> serviceSetup) {
    UUID worldId = UUID.randomUUID();
    RouteDefinition route = new RouteDefinition(RouteId.of("WS-1L"), waypoints, Optional.empty());
    TagStore tags =
        new TagStore(
            TRAIN,
            "FTA_OPERATOR_CODE=op",
            "FTA_LINE_CODE=l1",
            "FTA_ROUTE_CODE=r1",
            "FTA_ROUTE_INDEX=0");
    ConfigManager configManager = mock(ConfigManager.class);
    ConfigManager.ConfigView base = testConfigView(20, LINE_BPS, 1, 1, SmartDispatcherMode.OFF);
    when(configManager.current()).thenReturn(withApproachSpeed(base, approachSpeedBps));
    RailGraphService railGraphService = mock(RailGraphService.class);
    when(railGraphService.getSnapshot(worldId))
        .thenReturn(
            Optional.of(
                new RailGraphService.RailGraphSnapshot(
                    sectionlessGraph(graph(waypoints, xs)), Instant.now())));
    when(railGraphService.effectiveSpeedLimitBlocksPerSecond(
            any(), any(), any(), anyDouble(), anyDouble()))
        .thenAnswer(
            invocation -> {
              RailEdge edge = invocation.getArgument(1);
              return slowEdges.getOrDefault(edge.id(), LINE_BPS);
            });
    RouteDefinitionCache routeDefinitions = mock(RouteDefinitionCache.class);
    when(routeDefinitions.findByCodes("op", "l1", "r1")).thenReturn(Optional.of(route));
    when(routeDefinitions.findStop(any(), anyInt()))
        .thenAnswer(
            invocation -> {
              int index = invocation.getArgument(1);
              RouteStopPassType pass = stops.get(index);
              return pass == null
                  ? Optional.empty()
                  : Optional.of(routeStop(index, waypoints.get(index), pass));
            });

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
    FakeTrain train = new FakeTrain(worldId, tags.properties(), true, speedBps / 20.0);
    RailState head = mock(RailState.class);
    when(head.positionLocation()).thenReturn(new Location(null, headX, 64.0, 0.0));
    train.railState = Optional.of(head);
    setup.accept(train);
    serviceSetup.accept(service);

    service.handleSignalTick(train, false);

    return service.getDiagnostics(TRAIN).orElseThrow();
  }

  /** 相邻线路节点直接相连，边长取 x 坐标差。 */
  private static SimpleRailGraph graph(List<NodeId> waypoints, Map<NodeId, Double> xs) {
    Map<NodeId, RailNode> nodes = new LinkedHashMap<>();
    for (NodeId id : waypoints) {
      NodeType type = id.value().contains(":S:") ? NodeType.STATION : NodeType.WAYPOINT;
      nodes.put(
          id,
          new SignRailNode(
              id, type, new Vector(xs.get(id), 64.0, 0.0), Optional.empty(), Optional.empty()));
    }
    Map<EdgeId, RailEdge> edges = new LinkedHashMap<>();
    for (int i = 0; i + 1 < waypoints.size(); i++) {
      NodeId from = waypoints.get(i);
      NodeId to = waypoints.get(i + 1);
      EdgeId id = EdgeId.undirected(from, to);
      int length = (int) Math.round(xs.get(to) - xs.get(from));
      edges.put(id, new RailEdge(id, from, to, length, -1.0, true, Optional.empty()));
    }
    return new SimpleRailGraph(nodes, edges, java.util.Set.of());
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
