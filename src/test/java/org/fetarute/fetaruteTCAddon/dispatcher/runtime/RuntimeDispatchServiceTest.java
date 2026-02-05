package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.bergerkiller.bukkit.tc.properties.TrainProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.company.model.RouteStopPassType;
import org.fetarute.fetaruteTCAddon.config.ConfigManager;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailEdge;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphService;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinitionCache;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteId;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.config.SpeedCurveType;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.config.TrainConfigResolver;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyClaim;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyDecision;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyManager;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyRequest;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyResource;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.ResourceKind;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspect;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.stubbing.Answer;

/**
 * RuntimeDispatchService 终点停车与 Layover 注册逻辑回归测试。
 *
 * <p>覆盖场景：
 *
 * <ul>
 *   <li>终点停车不依赖 forceApply，确保不会穿站
 *   <li>终点 Layover 注册仅在 REUSE_AT_TERM 且未被 DSTY 销毁时触发
 *   <li>DSTY 销毁优先于终点停车，避免 deadlock
 *   <li>信号/占用/速度控制分支正常推进
 * </ul>
 */
class RuntimeDispatchServiceTest {

  @Test
  void handleSignalTickSkipsWhenRouteIndexMissing() {
    TagStore tags =
        new TagStore("train-1", "FTA_OPERATOR_CODE=op", "FTA_LINE_CODE=l1", "FTA_ROUTE_CODE=r1");
    UUID worldId = UUID.randomUUID();

    ConfigManager configManager = mock(ConfigManager.class);
    when(configManager.current()).thenReturn(testConfigView(20, 20.0));

    OccupancyManager occupancyManager = mock(OccupancyManager.class);

    RuntimeDispatchService service =
        new RuntimeDispatchService(
            occupancyManager,
            mock(RailGraphService.class),
            mock(RouteDefinitionCache.class),
            new RouteProgressRegistry(),
            mock(SignNodeRegistry.class),
            mock(LayoverRegistry.class),
            new DwellRegistry(),
            configManager,
            null,
            new TrainConfigResolver(),
            null);

    FakeTrain train = new FakeTrain(worldId, tags.properties(), false);

    service.handleSignalTick(train, false);

    verifyNoInteractions(occupancyManager);
  }

  @Test
  void handleSignalTickInitializesProgressRegistryAndAvoidsRepeatedLaunch() {
    RouteDefinition route =
        new RouteDefinition(
            RouteId.of("r"), List.of(NodeId.of("A"), NodeId.of("B")), Optional.empty());
    TagStore tags =
        new TagStore(
            "train-1",
            "FTA_OPERATOR_CODE=op",
            "FTA_LINE_CODE=l1",
            "FTA_ROUTE_CODE=r1",
            "FTA_ROUTE_INDEX=0");
    UUID worldId = UUID.randomUUID();

    ConfigManager configManager = mock(ConfigManager.class);
    when(configManager.current()).thenReturn(testConfigView(20, 20.0));

    RailGraphService railGraphService = mock(RailGraphService.class);
    when(railGraphService.getSnapshot(worldId))
        .thenReturn(
            Optional.of(
                new RailGraphService.RailGraphSnapshot(
                    graphWithSingleEdge(NodeId.of("A"), NodeId.of("B"), 10), Instant.now())));
    when(railGraphService.effectiveSpeedLimitBlocksPerSecond(any(), any(), any(), anyDouble()))
        .thenReturn(1000.0);

    RouteDefinitionCache routeDefinitions = mock(RouteDefinitionCache.class);
    when(routeDefinitions.findByCodes("op", "l1", "r1")).thenReturn(Optional.of(route));

    OccupancyManager occupancyManager = mock(OccupancyManager.class);
    when(occupancyManager.canEnter(any())).thenAnswer(allowProceed());

    SignNodeRegistry signNodeRegistry = mock(SignNodeRegistry.class);

    RuntimeDispatchService service =
        new RuntimeDispatchService(
            occupancyManager,
            railGraphService,
            routeDefinitions,
            new RouteProgressRegistry(),
            signNodeRegistry,
            mock(LayoverRegistry.class),
            new DwellRegistry(),
            configManager,
            null,
            new TrainConfigResolver(),
            null);

    FakeTrain train = new FakeTrain(worldId, tags.properties(), false);

    service.handleSignalTick(train, false);
    assertTrue(train.launchCalls == 1);

    service.handleSignalTick(train, false);
    assertTrue(train.launchCalls == 1);
  }

  @Test
  void handleSignalTickDoesNotLaunchWhenGroupIsMoving() {
    RouteDefinition route =
        new RouteDefinition(
            RouteId.of("r"), List.of(NodeId.of("A"), NodeId.of("B")), Optional.empty());
    TagStore tags =
        new TagStore(
            "train-1",
            "FTA_OPERATOR_CODE=op",
            "FTA_LINE_CODE=l1",
            "FTA_ROUTE_CODE=r1",
            "FTA_ROUTE_INDEX=0");
    UUID worldId = UUID.randomUUID();

    ConfigManager configManager = mock(ConfigManager.class);
    when(configManager.current()).thenReturn(testConfigView(20, 20.0));

    RailGraphService railGraphService = mock(RailGraphService.class);
    when(railGraphService.getSnapshot(worldId))
        .thenReturn(
            Optional.of(
                new RailGraphService.RailGraphSnapshot(
                    graphWithSingleEdge(NodeId.of("A"), NodeId.of("B"), 10), Instant.now())));
    when(railGraphService.effectiveSpeedLimitBlocksPerSecond(any(), any(), any(), anyDouble()))
        .thenReturn(1000.0);

    RouteDefinitionCache routeDefinitions = mock(RouteDefinitionCache.class);
    when(routeDefinitions.findByCodes("op", "l1", "r1")).thenReturn(Optional.of(route));

    OccupancyManager occupancyManager = mock(OccupancyManager.class);
    when(occupancyManager.canEnter(any())).thenAnswer(allowProceed());

    SignNodeRegistry signNodeRegistry = mock(SignNodeRegistry.class);

    RouteProgressRegistry registry = new RouteProgressRegistry();
    registry.initFromTags("train-1", tags.properties(), route);
    registry.updateSignal("train-1", SignalAspect.CAUTION, Instant.now());

    RuntimeDispatchService service =
        new RuntimeDispatchService(
            occupancyManager,
            railGraphService,
            routeDefinitions,
            registry,
            signNodeRegistry,
            mock(LayoverRegistry.class),
            new DwellRegistry(),
            configManager,
            null,
            new TrainConfigResolver(),
            null);

    FakeTrain train = new FakeTrain(worldId, tags.properties(), true);

    service.handleSignalTick(train, false);
    assertTrue(train.launchCalls == 0);
  }

  @Test
  void handleSignalTickKeepsCurrentNodeOccupancyDuringDwell() {
    NodeId current = NodeId.of("ST");
    NodeId next = NodeId.of("NEXT");
    RouteDefinition route =
        new RouteDefinition(RouteId.of("r"), List.of(current, next), Optional.empty());
    TagStore tags =
        new TagStore(
            "train-1",
            "FTA_OPERATOR_CODE=op",
            "FTA_LINE_CODE=l1",
            "FTA_ROUTE_CODE=r1",
            "FTA_ROUTE_INDEX=0");
    UUID worldId = UUID.randomUUID();

    ConfigManager configManager = mock(ConfigManager.class);
    when(configManager.current()).thenReturn(testConfigView(20, 20.0));

    RailGraphService railGraphService = mock(RailGraphService.class);
    when(railGraphService.getSnapshot(worldId))
        .thenReturn(
            Optional.of(
                new RailGraphService.RailGraphSnapshot(
                    graphWithSingleEdge(current, next, 10), Instant.now())));
    when(railGraphService.effectiveSpeedLimitBlocksPerSecond(any(), any(), any(), anyDouble()))
        .thenReturn(1000.0);

    RouteDefinitionCache routeDefinitions = mock(RouteDefinitionCache.class);
    when(routeDefinitions.findByCodes("op", "l1", "r1")).thenReturn(Optional.of(route));
    RouteStop stop =
        new RouteStop(
            UUID.randomUUID(),
            0,
            Optional.empty(),
            Optional.of(current.value()),
            Optional.empty(),
            RouteStopPassType.STOP,
            Optional.empty());
    when(routeDefinitions.findStop(route.id(), 0)).thenReturn(Optional.of(stop));

    OccupancyManager occupancyManager = mock(OccupancyManager.class);
    when(occupancyManager.canEnter(any())).thenAnswer(allowProceed());
    when(occupancyManager.acquire(any())).thenAnswer(allowProceed());

    OccupancyResource keepNode = OccupancyResource.forNode(current);
    OccupancyResource edge = OccupancyResource.forEdge(EdgeId.undirected(current, next));
    when(occupancyManager.snapshotClaims())
        .thenReturn(
            List.of(
                new OccupancyClaim(
                    keepNode,
                    "train-1",
                    Optional.of(route.id()),
                    Instant.now(),
                    Duration.ZERO,
                    Optional.empty()),
                new OccupancyClaim(
                    edge,
                    "train-1",
                    Optional.of(route.id()),
                    Instant.now(),
                    Duration.ZERO,
                    Optional.empty())));

    DwellRegistry dwellRegistry = new DwellRegistry();
    dwellRegistry.start("train-1", 10);

    RuntimeDispatchService service =
        new RuntimeDispatchService(
            occupancyManager,
            railGraphService,
            routeDefinitions,
            new RouteProgressRegistry(),
            mock(SignNodeRegistry.class),
            mock(LayoverRegistry.class),
            dwellRegistry,
            configManager,
            null,
            new TrainConfigResolver(),
            null);

    FakeTrain train = new FakeTrain(worldId, tags.properties(), false);
    service.handleSignalTick(train, false);

    verify(occupancyManager).releaseResource(edge, Optional.of("train-1"));
    verify(occupancyManager, org.mockito.Mockito.never())
        .releaseResource(keepNode, Optional.of("train-1"));
    verify(occupancyManager, org.mockito.Mockito.never()).releaseByTrain("train-1");
  }

  @Test
  void handleSignalTickGradesBlockedAspectByLookaheadPosition() {
    NodeId a = NodeId.of("A");
    NodeId b = NodeId.of("B");
    NodeId c = NodeId.of("C");
    RouteDefinition route =
        new RouteDefinition(RouteId.of("r"), List.of(a, b, c), Optional.empty());
    TagStore tags =
        new TagStore(
            "train-1",
            "FTA_OPERATOR_CODE=op",
            "FTA_LINE_CODE=l1",
            "FTA_ROUTE_CODE=r1",
            "FTA_ROUTE_INDEX=0");
    UUID worldId = UUID.randomUUID();

    ConfigManager configManager = mock(ConfigManager.class);
    ConfigManager.ConfigView base = testConfigView(20, 20.0);
    ConfigManager.RuntimeSettings runtime =
        new ConfigManager.RuntimeSettings(
            base.runtimeSettings().dispatchTickIntervalTicks(),
            base.runtimeSettings().launchCooldownTicks(),
            2,
            base.runtimeSettings().minClearEdges(),
            base.runtimeSettings().rearGuardEdges(),
            base.runtimeSettings().switcherZoneEdges(),
            base.runtimeSettings().approachSpeedBps(),
            base.runtimeSettings().cautionSpeedBps(),
            base.runtimeSettings().approachDepotSpeedBps(),
            base.runtimeSettings().speedCurveEnabled(),
            base.runtimeSettings().speedCurveType(),
            base.runtimeSettings().speedCurveFactor(),
            base.runtimeSettings().speedCurveEarlyBrakeBlocks(),
            base.runtimeSettings().failoverStallSpeedBps(),
            base.runtimeSettings().failoverStallTicks(),
            base.runtimeSettings().failoverUnreachableStop(),
            base.runtimeSettings().hudBossBarEnabled(),
            base.runtimeSettings().hudBossBarTickIntervalTicks(),
            Optional.empty(),
            base.runtimeSettings().hudActionBarEnabled(),
            base.runtimeSettings().hudActionBarTickIntervalTicks(),
            Optional.empty(),
            false,
            10,
            Optional.empty());
    when(configManager.current())
        .thenReturn(
            new ConfigManager.ConfigView(
                base.configVersion(),
                base.debugEnabled(),
                base.locale(),
                base.storageSettings(),
                base.graphSettings(),
                base.autoStationSettings(),
                runtime,
                base.spawnSettings(),
                base.trainConfigSettings(),
                base.reclaimSettings()));

    RailGraphService railGraphService = mock(RailGraphService.class);
    when(railGraphService.getSnapshot(worldId))
        .thenReturn(
            Optional.of(
                new RailGraphService.RailGraphSnapshot(
                    graphWithTwoEdges(a, b, c, 10, 10), Instant.now())));
    when(railGraphService.effectiveSpeedLimitBlocksPerSecond(any(), any(), any(), anyDouble()))
        .thenReturn(1000.0);

    RouteDefinitionCache routeDefinitions = mock(RouteDefinitionCache.class);
    when(routeDefinitions.findByCodes("op", "l1", "r1")).thenReturn(Optional.of(route));

    OccupancyManager occupancyManager = mock(OccupancyManager.class);
    when(occupancyManager.canEnter(any()))
        .thenAnswer(
            inv -> {
              OccupancyRequest request = inv.getArgument(0);
              OccupancyResource blocker = OccupancyResource.forEdge(EdgeId.undirected(b, c));
              return new OccupancyDecision(
                  false,
                  request.now(),
                  SignalAspect.STOP,
                  List.of(
                      new OccupancyClaim(
                          blocker,
                          "other",
                          Optional.empty(),
                          request.now(),
                          Duration.ZERO,
                          Optional.empty())));
            });

    RouteProgressRegistry registry = new RouteProgressRegistry();
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
            null);

    FakeTrain train = new FakeTrain(worldId, tags.properties(), false);
    service.handleSignalTick(train, false);

    SignalAspect aspect = registry.get("train-1").orElseThrow().lastSignal();
    assertEquals(SignalAspect.CAUTION, aspect);
  }

  @Test
  void handleSignalTickIgnoresForwardScanClaimsFromBehindSameRoute() {
    NodeId a = NodeId.of("A");
    NodeId b = NodeId.of("B");
    NodeId c = NodeId.of("C");
    RouteDefinition route =
        new RouteDefinition(RouteId.of("r"), List.of(a, b, c), Optional.empty());
    TagStore tags =
        new TagStore(
            "train-1",
            "FTA_OPERATOR_CODE=op",
            "FTA_LINE_CODE=l1",
            "FTA_ROUTE_CODE=r1",
            "FTA_ROUTE_INDEX=1");
    TagStore backTags = new TagStore("train-back", "FTA_ROUTE_INDEX=0");
    UUID worldId = UUID.randomUUID();

    ConfigManager configManager = mock(ConfigManager.class);
    when(configManager.current()).thenReturn(testConfigView(20, 20.0));

    RailGraphService railGraphService = mock(RailGraphService.class);
    when(railGraphService.getSnapshot(worldId))
        .thenReturn(
            Optional.of(
                new RailGraphService.RailGraphSnapshot(
                    graphWithTwoEdges(a, b, c, 10, 10), Instant.now())));
    when(railGraphService.effectiveSpeedLimitBlocksPerSecond(any(), any(), any(), anyDouble()))
        .thenReturn(1000.0);

    RouteDefinitionCache routeDefinitions = mock(RouteDefinitionCache.class);
    when(routeDefinitions.findByCodes("op", "l1", "r1")).thenReturn(Optional.of(route));

    OccupancyManager occupancyManager = mock(OccupancyManager.class);
    when(occupancyManager.canEnter(any())).thenAnswer(allowProceed());
    when(occupancyManager.acquire(any())).thenAnswer(allowProceed());

    OccupancyResource forwardEdge = OccupancyResource.forEdge(EdgeId.undirected(b, c));
    OccupancyClaim backClaim =
        new OccupancyClaim(
            forwardEdge,
            "train-back",
            Optional.of(route.id()),
            Instant.now(),
            Duration.ZERO,
            Optional.empty());
    when(occupancyManager.getClaim(any()))
        .thenAnswer(
            inv -> {
              OccupancyResource resource = inv.getArgument(0);
              if (forwardEdge.equals(resource)) {
                return Optional.of(backClaim);
              }
              return Optional.empty();
            });

    RouteProgressRegistry registry = new RouteProgressRegistry();
    registry.initFromTags("train-1", tags.properties(), route);
    registry.initFromTags("train-back", backTags.properties(), route);

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
            null);

    FakeTrain train = new FakeTrain(worldId, tags.properties(), false);
    service.handleSignalTick(train, false);

    SignalAspect aspect = registry.get("train-1").orElseThrow().lastSignal();
    assertEquals(SignalAspect.PROCEED, aspect);
  }

  @Test
  void handleSignalTickDowngradesWhenForwardClaimIsFromAheadSameRoute() {
    NodeId a = NodeId.of("A");
    NodeId b = NodeId.of("B");
    NodeId c = NodeId.of("C");
    RouteDefinition route =
        new RouteDefinition(RouteId.of("r"), List.of(a, b, c), Optional.empty());
    TagStore tags =
        new TagStore(
            "train-1",
            "FTA_OPERATOR_CODE=op",
            "FTA_LINE_CODE=l1",
            "FTA_ROUTE_CODE=r1",
            "FTA_ROUTE_INDEX=1");
    TagStore aheadTags = new TagStore("train-ahead", "FTA_ROUTE_INDEX=2");
    UUID worldId = UUID.randomUUID();

    ConfigManager configManager = mock(ConfigManager.class);
    when(configManager.current()).thenReturn(testConfigView(20, 20.0));

    RailGraphService railGraphService = mock(RailGraphService.class);
    when(railGraphService.getSnapshot(worldId))
        .thenReturn(
            Optional.of(
                new RailGraphService.RailGraphSnapshot(
                    graphWithTwoEdges(a, b, c, 10, 10), Instant.now())));
    when(railGraphService.effectiveSpeedLimitBlocksPerSecond(any(), any(), any(), anyDouble()))
        .thenReturn(1000.0);

    RouteDefinitionCache routeDefinitions = mock(RouteDefinitionCache.class);
    when(routeDefinitions.findByCodes("op", "l1", "r1")).thenReturn(Optional.of(route));

    OccupancyManager occupancyManager = mock(OccupancyManager.class);
    when(occupancyManager.canEnter(any())).thenAnswer(allowProceed());
    when(occupancyManager.acquire(any())).thenAnswer(allowProceed());

    OccupancyResource forwardEdge = OccupancyResource.forEdge(EdgeId.undirected(b, c));
    OccupancyClaim aheadClaim =
        new OccupancyClaim(
            forwardEdge,
            "train-ahead",
            Optional.of(route.id()),
            Instant.now(),
            Duration.ZERO,
            Optional.empty());
    when(occupancyManager.getClaim(any()))
        .thenAnswer(
            inv -> {
              OccupancyResource resource = inv.getArgument(0);
              if (forwardEdge.equals(resource)) {
                return Optional.of(aheadClaim);
              }
              return Optional.empty();
            });

    RouteProgressRegistry registry = new RouteProgressRegistry();
    registry.initFromTags("train-1", tags.properties(), route);
    registry.updateSignal("train-1", SignalAspect.PROCEED, Instant.now());
    registry.initFromTags("train-ahead", aheadTags.properties(), route);

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
            null);

    FakeTrain train = new FakeTrain(worldId, tags.properties(), false);
    service.handleSignalTick(train, false);

    SignalAspect aspect = registry.get("train-1").orElseThrow().lastSignal();
    assertEquals(SignalAspect.STOP, aspect);
  }

  @Test
  void handleSignalTickUsesHoldLease() {
    RouteDefinition route =
        new RouteDefinition(
            RouteId.of("r"), List.of(NodeId.of("A"), NodeId.of("B")), Optional.empty());
    TagStore tags =
        new TagStore(
            "train-1",
            "FTA_OPERATOR_CODE=op",
            "FTA_LINE_CODE=l1",
            "FTA_ROUTE_CODE=r1",
            "FTA_ROUTE_INDEX=0");
    UUID worldId = UUID.randomUUID();

    ConfigManager configManager = mock(ConfigManager.class);
    when(configManager.current()).thenReturn(testConfigView(20, 20.0));

    RailGraphService railGraphService = mock(RailGraphService.class);
    when(railGraphService.getSnapshot(worldId))
        .thenReturn(
            Optional.of(
                new RailGraphService.RailGraphSnapshot(
                    graphWithSingleEdge(NodeId.of("A"), NodeId.of("B"), 1), Instant.now())));
    when(railGraphService.effectiveSpeedLimitBlocksPerSecond(any(), any(), any(), anyDouble()))
        .thenReturn(1000.0);

    RouteDefinitionCache routeDefinitions = mock(RouteDefinitionCache.class);
    when(routeDefinitions.findByCodes("op", "l1", "r1")).thenReturn(Optional.of(route));

    OccupancyManager occupancyManager = mock(OccupancyManager.class);
    when(occupancyManager.canEnter(any())).thenAnswer(allowProceed());

    SignNodeRegistry signNodeRegistry = mock(SignNodeRegistry.class);

    RuntimeDispatchService service =
        new RuntimeDispatchService(
            occupancyManager,
            railGraphService,
            routeDefinitions,
            new RouteProgressRegistry(),
            signNodeRegistry,
            mock(LayoverRegistry.class),
            new DwellRegistry(),
            configManager,
            null,
            new TrainConfigResolver(),
            null);

    FakeTrain train = new FakeTrain(worldId, tags.properties(), false);

    service.handleSignalTick(train, false);

    ArgumentCaptor<OccupancyRequest> requestCaptor =
        ArgumentCaptor.forClass(OccupancyRequest.class);
    verify(occupancyManager).acquire(requestCaptor.capture());
    assertFalse(requestCaptor.getValue().resourceList().isEmpty());
  }

  @Test
  void handleTrainRemovedReleasesOccupancyAndClearsProgress() {
    String trainName = "train-1";
    RouteDefinition route =
        new RouteDefinition(
            RouteId.of("r"), List.of(NodeId.of("A"), NodeId.of("B")), Optional.empty());
    TagStore tags = new TagStore(trainName, "FTA_ROUTE_INDEX=0");

    ConfigManager configManager = mock(ConfigManager.class);
    when(configManager.current()).thenReturn(testConfigView(20, 20.0));

    RailGraphService railGraphService = mock(RailGraphService.class);
    RouteDefinitionCache routeDefinitions = mock(RouteDefinitionCache.class);
    SignNodeRegistry signNodeRegistry = mock(SignNodeRegistry.class);
    OccupancyManager occupancyManager = mock(OccupancyManager.class);

    RouteProgressRegistry registry = new RouteProgressRegistry();
    registry.initFromTags(trainName, tags.properties(), route);

    RuntimeDispatchService service =
        new RuntimeDispatchService(
            occupancyManager,
            railGraphService,
            routeDefinitions,
            registry,
            signNodeRegistry,
            mock(LayoverRegistry.class),
            new DwellRegistry(),
            configManager,
            null,
            new TrainConfigResolver(),
            null);

    service.handleTrainRemoved(trainName);

    verify(occupancyManager).releaseByTrain(trainName);
    assertTrue(registry.get(trainName).isEmpty());
  }

  @Test
  void rebuildOccupancySnapshotReleasesOrphanClaims() {
    RouteDefinition route =
        new RouteDefinition(
            RouteId.of("r"), List.of(NodeId.of("A"), NodeId.of("B")), Optional.empty());
    TagStore tags =
        new TagStore(
            "train-1",
            "FTA_OPERATOR_CODE=op",
            "FTA_LINE_CODE=l1",
            "FTA_ROUTE_CODE=r1",
            "FTA_ROUTE_INDEX=0");
    UUID worldId = UUID.randomUUID();

    ConfigManager configManager = mock(ConfigManager.class);
    when(configManager.current()).thenReturn(testConfigView(20, 20.0));

    RailGraphService railGraphService = mock(RailGraphService.class);
    when(railGraphService.getSnapshot(worldId))
        .thenReturn(
            Optional.of(
                new RailGraphService.RailGraphSnapshot(
                    graphWithSingleEdge(NodeId.of("A"), NodeId.of("B"), 10), Instant.now())));
    when(railGraphService.effectiveSpeedLimitBlocksPerSecond(any(), any(), any(), anyDouble()))
        .thenReturn(1000.0);

    RouteDefinitionCache routeDefinitions = mock(RouteDefinitionCache.class);
    when(routeDefinitions.findByCodes("op", "l1", "r1")).thenReturn(Optional.of(route));

    OccupancyManager occupancyManager = mock(OccupancyManager.class);
    when(occupancyManager.canEnter(any())).thenAnswer(allowProceed());
    when(occupancyManager.snapshotClaims())
        .thenReturn(
            List.of(
                new OccupancyClaim(
                    new OccupancyResource(ResourceKind.EDGE, "A~B"),
                    "ghost",
                    Optional.empty(),
                    Instant.now(),
                    Duration.ZERO,
                    Optional.empty())));

    SignNodeRegistry signNodeRegistry = mock(SignNodeRegistry.class);

    RuntimeDispatchService service =
        new RuntimeDispatchService(
            occupancyManager,
            railGraphService,
            routeDefinitions,
            new RouteProgressRegistry(),
            signNodeRegistry,
            mock(LayoverRegistry.class),
            new DwellRegistry(),
            configManager,
            null,
            new TrainConfigResolver(),
            null);

    FakeTrain train = new FakeTrain(worldId, tags.properties(), false);

    service.rebuildOccupancySnapshot(List.of(train));

    verify(occupancyManager).releaseByTrain("ghost");
  }

  @Test
  void cleanupOrphanOccupancyClaimsRemovesProgressEntries() {
    RouteDefinition route =
        new RouteDefinition(
            RouteId.of("r"), List.of(NodeId.of("A"), NodeId.of("B")), Optional.empty());
    TagStore tags = new TagStore("old-train", "FTA_ROUTE_INDEX=0");

    ConfigManager configManager = mock(ConfigManager.class);
    when(configManager.current()).thenReturn(testConfigView(20, 20.0));

    OccupancyManager occupancyManager = mock(OccupancyManager.class);
    when(occupancyManager.snapshotClaims()).thenReturn(List.of());

    RouteProgressRegistry registry = new RouteProgressRegistry();
    registry.initFromTags("old-train", tags.properties(), route);

    RuntimeDispatchService serviceWithRegistry =
        new RuntimeDispatchService(
            occupancyManager,
            mock(RailGraphService.class),
            mock(RouteDefinitionCache.class),
            registry,
            mock(SignNodeRegistry.class),
            mock(LayoverRegistry.class),
            new DwellRegistry(),
            configManager,
            null,
            new TrainConfigResolver(),
            null);

    serviceWithRegistry.cleanupOrphanOccupancyClaims(java.util.Set.of("new-train"));

    assertTrue(registry.get("old-train").isEmpty());
  }

  @Test
  void handleSignalTickMigratesProgressOnRename() {
    RouteDefinition route =
        new RouteDefinition(
            RouteId.of("r"), List.of(NodeId.of("A"), NodeId.of("B")), Optional.empty());
    TagStore tags =
        new TagStore(
            "new-train",
            "FTA_TRAIN_NAME=old-train",
            "FTA_OPERATOR_CODE=op",
            "FTA_LINE_CODE=l1",
            "FTA_ROUTE_CODE=r1",
            "FTA_ROUTE_INDEX=0");
    UUID worldId = UUID.randomUUID();

    ConfigManager configManager = mock(ConfigManager.class);
    when(configManager.current()).thenReturn(testConfigView(20, 20.0));

    RailGraphService railGraphService = mock(RailGraphService.class);
    when(railGraphService.getSnapshot(worldId))
        .thenReturn(
            Optional.of(
                new RailGraphService.RailGraphSnapshot(
                    graphWithSingleEdge(NodeId.of("A"), NodeId.of("B"), 10), Instant.now())));
    when(railGraphService.effectiveSpeedLimitBlocksPerSecond(any(), any(), any(), anyDouble()))
        .thenReturn(1000.0);

    RouteDefinitionCache routeDefinitions = mock(RouteDefinitionCache.class);
    when(routeDefinitions.findByCodes("op", "l1", "r1")).thenReturn(Optional.of(route));

    OccupancyManager occupancyManager = mock(OccupancyManager.class);
    when(occupancyManager.canEnter(any())).thenAnswer(allowProceed());

    SignNodeRegistry signNodeRegistry = mock(SignNodeRegistry.class);

    RouteProgressRegistry registry = new RouteProgressRegistry();
    registry.initFromTags("old-train", tags.properties(), route);

    RuntimeDispatchService service =
        new RuntimeDispatchService(
            occupancyManager,
            railGraphService,
            routeDefinitions,
            registry,
            signNodeRegistry,
            mock(LayoverRegistry.class),
            new DwellRegistry(),
            configManager,
            null,
            new TrainConfigResolver(),
            null);

    FakeTrain train = new FakeTrain(worldId, tags.properties(), false);

    service.handleSignalTick(train, false);

    verify(occupancyManager).releaseByTrain("old-train");
    assertTrue(registry.get("old-train").isEmpty());
    assertTrue(registry.get("new-train").isPresent());
  }

  private static Answer<OccupancyDecision> allowProceed() {
    return inv -> {
      OccupancyRequest request = inv.getArgument(0);
      return new OccupancyDecision(true, request.now(), SignalAspect.PROCEED, List.of());
    };
  }

  private static RailGraph graphWithSingleEdge(NodeId a, NodeId b, int lengthBlocks) {
    RailEdge edge =
        new RailEdge(EdgeId.undirected(a, b), a, b, lengthBlocks, -1.0, true, Optional.empty());
    return new RailGraph() {
      @Override
      public java.util.Collection<org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode> nodes() {
        return List.of(new RailNodeTest(a), new RailNodeTest(b));
      }

      @Override
      public java.util.Collection<RailEdge> edges() {
        return List.of(edge);
      }

      @Override
      public Optional<org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode> findNode(NodeId id) {
        if (id == null) {
          return Optional.empty();
        }
        if (id.equals(a)) {
          return Optional.of(new RailNodeTest(a));
        }
        if (id.equals(b)) {
          return Optional.of(new RailNodeTest(b));
        }
        return Optional.empty();
      }

      @Override
      public java.util.Set<RailEdge> edgesFrom(NodeId id) {
        if (id.equals(a) || id.equals(b)) {
          return java.util.Set.of(edge);
        }
        return java.util.Set.of();
      }

      @Override
      public boolean isBlocked(EdgeId id) {
        return false;
      }
    };
  }

  private static RailGraph graphWithTwoEdges(
      NodeId a, NodeId b, NodeId c, int length1, int length2) {
    RailEdge edge1 =
        new RailEdge(EdgeId.undirected(a, b), a, b, length1, -1.0, true, Optional.empty());
    RailEdge edge2 =
        new RailEdge(EdgeId.undirected(b, c), b, c, length2, -1.0, true, Optional.empty());
    return new RailGraph() {
      @Override
      public java.util.Collection<org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode> nodes() {
        return List.of(new RailNodeTest(a), new RailNodeTest(b), new RailNodeTest(c));
      }

      @Override
      public java.util.Collection<RailEdge> edges() {
        return List.of(edge1, edge2);
      }

      @Override
      public Optional<org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode> findNode(NodeId id) {
        if (id == null) {
          return Optional.empty();
        }
        if (id.equals(a)) {
          return Optional.of(new RailNodeTest(a));
        }
        if (id.equals(b)) {
          return Optional.of(new RailNodeTest(b));
        }
        if (id.equals(c)) {
          return Optional.of(new RailNodeTest(c));
        }
        return Optional.empty();
      }

      @Override
      public java.util.Set<RailEdge> edgesFrom(NodeId id) {
        if (id.equals(a)) {
          return java.util.Set.of(edge1);
        }
        if (id.equals(b)) {
          return java.util.Set.of(edge1, edge2);
        }
        if (id.equals(c)) {
          return java.util.Set.of(edge2);
        }
        return java.util.Set.of();
      }

      @Override
      public boolean isBlocked(EdgeId id) {
        return false;
      }
    };
  }

  private static ConfigManager.ConfigView testConfigView(
      int intervalTicks, double defaultSpeedBps) {
    ConfigManager.StorageSettings storage =
        new ConfigManager.StorageSettings(
            ConfigManager.StorageBackend.SQLITE,
            new ConfigManager.SqliteSettings("data/test.sqlite"),
            Optional.empty(),
            new ConfigManager.PoolSettings(1, 1000, 1000, 1000));
    ConfigManager.GraphSettings graph = new ConfigManager.GraphSettings(defaultSpeedBps, 6, 2);
    ConfigManager.AutoStationSettings autoStation =
        new ConfigManager.AutoStationSettings("", 1.0f, 1.0f);
    ConfigManager.RuntimeSettings runtime =
        new ConfigManager.RuntimeSettings(
            intervalTicks,
            10,
            1,
            1,
            1,
            3,
            0.0,
            6.0,
            3.5,
            true,
            SpeedCurveType.PHYSICS,
            1.0,
            0.0,
            0.2,
            60,
            true,
            true,
            10,
            Optional.empty(),
            false,
            10,
            Optional.empty(),
            false,
            10,
            Optional.empty());
    ConfigManager.TrainTypeSettings typeDefaults = new ConfigManager.TrainTypeSettings(1.0, 1.0);
    ConfigManager.TrainConfigSettings train =
        new ConfigManager.TrainConfigSettings(
            "emu", typeDefaults, typeDefaults, typeDefaults, typeDefaults);
    return new ConfigManager.ConfigView(
        10,
        false,
        "zh_CN",
        storage,
        graph,
        autoStation,
        runtime,
        new ConfigManager.SpawnSettings(false, 20, 200, 1, 5, 5, 40, 10),
        train,
        new ConfigManager.ReclaimSettings(false, 3600L, 100, 60L));
  }

  private static final class TagStore {
    private final TrainProperties properties;
    private final List<String> tags;

    private TagStore(String trainName, String... initial) {
      this.tags = new ArrayList<>(Arrays.asList(initial));
      this.properties = mock(TrainProperties.class);
      when(properties.getTrainName()).thenReturn(trainName);
      when(properties.hasTags()).thenAnswer(inv -> !tags.isEmpty());
      when(properties.getTags()).thenAnswer(inv -> List.copyOf(tags));
      when(properties.toString()).thenReturn("TrainProperties(" + trainName + ")");
      // 支持 addTags 和 removeTags 以测试 TrainTagHelper
      // varargs 在 Mockito doAnswer 时，整个 varargs 作为 Object[] 传入
      lenient()
          .doAnswer(
              inv -> {
                Object[] args = inv.getArguments();
                if (args != null) {
                  for (Object arg : args) {
                    if (arg instanceof String s && !s.isBlank()) {
                      tags.add(s);
                    }
                  }
                }
                return null;
              })
          .when(properties)
          .addTags(any(String[].class));
      lenient()
          .doAnswer(
              inv -> {
                Object[] args = inv.getArguments();
                if (args != null) {
                  for (Object arg : args) {
                    if (arg instanceof String s) {
                      tags.remove(s);
                    }
                  }
                }
                return null;
              })
          .when(properties)
          .removeTags(any(String[].class));
    }

    private TrainProperties properties() {
      return properties;
    }
  }

  @Test
  void handleSignalTickDoesNotDestroyWhenDstyTargetDoesNotMatchCurrentNode() {
    RouteDefinition route =
        new RouteDefinition(
            RouteId.of("r"), List.of(NodeId.of("TERM"), NodeId.of("NEXT")), Optional.empty());
    TagStore tags =
        new TagStore(
            "train-1",
            "FTA_OPERATOR_CODE=op",
            "FTA_LINE_CODE=l1",
            "FTA_ROUTE_CODE=r1",
            "FTA_ROUTE_INDEX=0");
    UUID worldId = UUID.randomUUID();

    ConfigManager configManager = mock(ConfigManager.class);
    when(configManager.current()).thenReturn(testConfigView(20, 20.0));

    RailGraphService railGraphService = mock(RailGraphService.class);
    when(railGraphService.getSnapshot(worldId))
        .thenReturn(
            Optional.of(
                new RailGraphService.RailGraphSnapshot(
                    graphWithSingleEdge(NodeId.of("TERM"), NodeId.of("NEXT"), 10), Instant.now())));
    when(railGraphService.effectiveSpeedLimitBlocksPerSecond(any(), any(), any(), anyDouble()))
        .thenReturn(1000.0);

    RouteDefinitionCache routeDefinitions = mock(RouteDefinitionCache.class);
    when(routeDefinitions.findByCodes("op", "l1", "r1")).thenReturn(Optional.of(route));
    RouteStop stop =
        new RouteStop(
            UUID.randomUUID(),
            0,
            Optional.empty(),
            Optional.of("TERM"),
            Optional.empty(),
            RouteStopPassType.TERMINATE,
            Optional.of("DSTY SURN:D:DEPOT:2"));
    when(routeDefinitions.findStop(route.id(), 0)).thenReturn(Optional.of(stop));

    OccupancyManager occupancyManager = mock(OccupancyManager.class);
    when(occupancyManager.canEnter(any())).thenAnswer(allowProceed());

    RuntimeDispatchService service =
        new RuntimeDispatchService(
            occupancyManager,
            railGraphService,
            routeDefinitions,
            new RouteProgressRegistry(),
            mock(SignNodeRegistry.class),
            mock(LayoverRegistry.class),
            new DwellRegistry(),
            configManager,
            null,
            new TrainConfigResolver(),
            null);

    FakeTrain train = new FakeTrain(worldId, tags.properties(), false);
    service.handleSignalTick(train, false);
    assertTrue(train.destroyCalls == 0);
    assertTrue(train.stopCalls == 0);
  }

  @Test
  void handleSignalTickStopsAtTerminalEvenWithoutForceApply() {
    RouteDefinition route =
        new RouteDefinition(RouteId.of("r"), List.of(NodeId.of("TERM")), Optional.empty());
    TagStore tags =
        new TagStore(
            "train-1",
            "FTA_OPERATOR_CODE=op",
            "FTA_LINE_CODE=l1",
            "FTA_ROUTE_CODE=r1",
            "FTA_ROUTE_INDEX=0");
    UUID worldId = UUID.randomUUID();

    ConfigManager configManager = mock(ConfigManager.class);
    when(configManager.current()).thenReturn(testConfigView(20, 20.0));

    RouteDefinitionCache routeDefinitions = mock(RouteDefinitionCache.class);
    when(routeDefinitions.findByCodes("op", "l1", "r1")).thenReturn(Optional.of(route));
    RouteStop stop =
        new RouteStop(
            UUID.randomUUID(),
            0,
            Optional.empty(),
            Optional.of("TERM"),
            Optional.empty(),
            RouteStopPassType.TERMINATE,
            Optional.empty());
    when(routeDefinitions.findStop(route.id(), 0)).thenReturn(Optional.of(stop));

    OccupancyManager occupancyManager = mock(OccupancyManager.class);
    RailGraphService railGraphService = mock(RailGraphService.class);

    RuntimeDispatchService service =
        new RuntimeDispatchService(
            occupancyManager,
            railGraphService,
            routeDefinitions,
            new RouteProgressRegistry(),
            mock(SignNodeRegistry.class),
            mock(LayoverRegistry.class),
            new DwellRegistry(),
            configManager,
            null,
            new TrainConfigResolver(),
            null);

    FakeTrain train = new FakeTrain(worldId, tags.properties(), false);
    service.handleSignalTick(train, false);
    assertTrue(train.stopCalls == 1);
  }

  @Test
  void handleSignalTickDestroysWhenDstyTargetMatchesCurrentNode() {
    RouteDefinition route =
        new RouteDefinition(
            RouteId.of("r"), List.of(NodeId.of("DEPOT"), NodeId.of("NEXT")), Optional.empty());
    TagStore tags =
        new TagStore(
            "train-1",
            "FTA_OPERATOR_CODE=op",
            "FTA_LINE_CODE=l1",
            "FTA_ROUTE_CODE=r1",
            "FTA_ROUTE_INDEX=0");
    UUID worldId = UUID.randomUUID();

    ConfigManager configManager = mock(ConfigManager.class);
    when(configManager.current()).thenReturn(testConfigView(20, 20.0));

    RouteDefinitionCache routeDefinitions = mock(RouteDefinitionCache.class);
    when(routeDefinitions.findByCodes("op", "l1", "r1")).thenReturn(Optional.of(route));
    RouteStop stop =
        new RouteStop(
            UUID.randomUUID(),
            0,
            Optional.empty(),
            Optional.of("DEPOT"),
            Optional.empty(),
            RouteStopPassType.PASS,
            Optional.of("DSTY DEPOT"));
    when(routeDefinitions.findStop(route.id(), 0)).thenReturn(Optional.of(stop));

    OccupancyManager occupancyManager = mock(OccupancyManager.class);
    RailGraphService railGraphService = mock(RailGraphService.class);

    RuntimeDispatchService service =
        new RuntimeDispatchService(
            occupancyManager,
            railGraphService,
            routeDefinitions,
            new RouteProgressRegistry(),
            mock(SignNodeRegistry.class),
            mock(LayoverRegistry.class),
            new DwellRegistry(),
            configManager,
            null,
            new TrainConfigResolver(),
            null);

    FakeTrain train = new FakeTrain(worldId, tags.properties(), false);
    service.handleSignalTick(train, false);
    assertTrue(train.destroyCalls == 1);
    verify(occupancyManager).releaseByTrain("train-1");
  }

  @Test
  void refreshSignalAllowsDepartureWhenNextNodeIsStoppingWaypoint() {
    RouteDefinition route =
        new RouteDefinition(
            RouteId.of("r"), List.of(NodeId.of("ST"), NodeId.of("TERM")), Optional.empty());
    TagStore tags =
        new TagStore(
            "train-1",
            "FTA_OPERATOR_CODE=op",
            "FTA_LINE_CODE=l1",
            "FTA_ROUTE_CODE=r1",
            "FTA_ROUTE_INDEX=0");
    UUID worldId = UUID.randomUUID();

    ConfigManager configManager = mock(ConfigManager.class);
    when(configManager.current()).thenReturn(testConfigView(1, 20.0));

    RailGraphService railGraphService = mock(RailGraphService.class);
    when(railGraphService.getSnapshot(worldId))
        .thenReturn(
            Optional.of(
                new RailGraphService.RailGraphSnapshot(
                    graphWithSingleEdge(NodeId.of("ST"), NodeId.of("TERM"), 20), Instant.now())));
    when(railGraphService.effectiveSpeedLimitBlocksPerSecond(any(), any(), any(), anyDouble()))
        .thenReturn(1000.0);

    RouteDefinitionCache routeDefinitions = mock(RouteDefinitionCache.class);
    when(routeDefinitions.findByCodes("op", "l1", "r1")).thenReturn(Optional.of(route));
    RouteStop termStop =
        new RouteStop(
            UUID.randomUUID(),
            1,
            Optional.empty(),
            Optional.of("TERM"),
            Optional.empty(),
            RouteStopPassType.TERMINATE,
            Optional.empty());
    when(routeDefinitions.findStop(route.id(), 1)).thenReturn(Optional.of(termStop));

    OccupancyManager occupancyManager = mock(OccupancyManager.class);
    when(occupancyManager.canEnter(any())).thenAnswer(allowProceed());
    when(occupancyManager.acquire(any())).thenAnswer(allowProceed());

    RuntimeDispatchService service =
        new RuntimeDispatchService(
            occupancyManager,
            railGraphService,
            routeDefinitions,
            new RouteProgressRegistry(),
            mock(SignNodeRegistry.class),
            mock(LayoverRegistry.class),
            new DwellRegistry(),
            configManager,
            null,
            new TrainConfigResolver(),
            null);

    FakeTrain train = new FakeTrain(worldId, tags.properties(), false, 0.0);
    service.handleSignalTick(train, true); // 模拟 AutoStation dwell 结束后的 refreshSignal(forceApply)
    assertTrue(train.launchCalls >= 1);
  }

  @Test
  void handleSignalTickCapsApproachSpeedAndDoesNotBrakeAgainstStopWaypointNodeDistance() {
    NodeId a = NodeId.of("A");
    NodeId stopWp = NodeId.of("B");
    RouteDefinition route =
        new RouteDefinition(RouteId.of("r"), List.of(a, stopWp), Optional.empty());
    TagStore tags =
        new TagStore(
            "train-1",
            "FTA_OPERATOR_CODE=op",
            "FTA_LINE_CODE=l1",
            "FTA_ROUTE_CODE=r1",
            "FTA_ROUTE_INDEX=0");
    UUID worldId = UUID.randomUUID();

    ConfigManager configManager = mock(ConfigManager.class);
    ConfigManager.ConfigView base = testConfigView(1, 20.0);
    ConfigManager.RuntimeSettings runtime =
        new ConfigManager.RuntimeSettings(
            base.runtimeSettings().dispatchTickIntervalTicks(),
            base.runtimeSettings().launchCooldownTicks(),
            base.runtimeSettings().lookaheadEdges(),
            base.runtimeSettings().minClearEdges(),
            base.runtimeSettings().rearGuardEdges(),
            base.runtimeSettings().switcherZoneEdges(),
            6.0, // approaching speed for STOP/TERM waypoint
            base.runtimeSettings().cautionSpeedBps(),
            base.runtimeSettings().approachDepotSpeedBps(),
            base.runtimeSettings().speedCurveEnabled(),
            base.runtimeSettings().speedCurveType(),
            base.runtimeSettings().speedCurveFactor(),
            base.runtimeSettings().speedCurveEarlyBrakeBlocks(),
            base.runtimeSettings().failoverStallSpeedBps(),
            base.runtimeSettings().failoverStallTicks(),
            base.runtimeSettings().failoverUnreachableStop(),
            base.runtimeSettings().hudBossBarEnabled(),
            base.runtimeSettings().hudBossBarTickIntervalTicks(),
            base.runtimeSettings().hudBossBarTemplate(),
            base.runtimeSettings().hudActionBarEnabled(),
            base.runtimeSettings().hudActionBarTickIntervalTicks(),
            base.runtimeSettings().hudActionBarTemplate(),
            base.runtimeSettings().hudPlayerDisplayEnabled(),
            base.runtimeSettings().hudPlayerDisplayTickIntervalTicks(),
            base.runtimeSettings().hudPlayerDisplayTemplate());
    when(configManager.current())
        .thenReturn(
            new ConfigManager.ConfigView(
                base.configVersion(),
                base.debugEnabled(),
                base.locale(),
                base.storageSettings(),
                base.graphSettings(),
                base.autoStationSettings(),
                runtime,
                base.spawnSettings(),
                base.trainConfigSettings(),
                base.reclaimSettings()));

    // A->B 只有 1 格：若错误地用“到下一节点距离”做 speed curve，会把速度压到非常低导致提前刹停。
    RailGraphService railGraphService = mock(RailGraphService.class);
    when(railGraphService.getSnapshot(worldId))
        .thenReturn(
            Optional.of(
                new RailGraphService.RailGraphSnapshot(
                    graphWithSingleEdge(a, stopWp, 1), Instant.now())));
    when(railGraphService.effectiveSpeedLimitBlocksPerSecond(any(), any(), any(), anyDouble()))
        .thenReturn(1000.0);

    RouteDefinitionCache routeDefinitions = mock(RouteDefinitionCache.class);
    when(routeDefinitions.findByCodes("op", "l1", "r1")).thenReturn(Optional.of(route));
    RouteStop stop =
        new RouteStop(
            UUID.randomUUID(),
            1,
            Optional.empty(),
            Optional.of(stopWp.value()),
            Optional.empty(),
            RouteStopPassType.STOP,
            Optional.empty());
    when(routeDefinitions.findStop(route.id(), 1)).thenReturn(Optional.of(stop));

    OccupancyManager occupancyManager = mock(OccupancyManager.class);
    when(occupancyManager.canEnter(any())).thenAnswer(allowProceed());
    when(occupancyManager.acquire(any())).thenAnswer(allowProceed());

    RuntimeDispatchService service =
        new RuntimeDispatchService(
            occupancyManager,
            railGraphService,
            routeDefinitions,
            new RouteProgressRegistry(),
            mock(SignNodeRegistry.class),
            mock(LayoverRegistry.class),
            new DwellRegistry(),
            configManager,
            null,
            new TrainConfigResolver(),
            null);

    FakeTrain train = new FakeTrain(worldId, tags.properties(), false, 0.0);
    service.handleSignalTick(train, true);

    ArgumentCaptor<Double> speedCaptor = ArgumentCaptor.forClass(Double.class);
    verify(tags.properties()).setSpeedLimit(speedCaptor.capture());

    // STOP/TERM waypoint 模式下应进入 approaching（6 bps）而非按 1 格距离提前刹到接近 0。
    double expectedBpt = runtime.approachSpeedBps() / 20.0;
    double actualBpt = speedCaptor.getValue();
    assertTrue(
        Math.abs(actualBpt - expectedBpt) < 1.0e-6,
        "expected speedLimitBpt=" + expectedBpt + " but got " + actualBpt);
  }

  private static final class FakeTrain implements RuntimeTrainHandle {
    private final UUID worldId;
    private final TrainProperties properties;
    private final boolean moving;
    private final double speedBlocksPerTick;
    private int launchCalls = 0;
    private int destroyCalls = 0;
    private int stopCalls = 0;

    private FakeTrain(UUID worldId, TrainProperties properties, boolean moving) {
      this(worldId, properties, moving, 0.0);
    }

    private FakeTrain(
        UUID worldId, TrainProperties properties, boolean moving, double speedBlocksPerTick) {
      this.worldId = worldId;
      this.properties = properties;
      this.moving = moving;
      this.speedBlocksPerTick = speedBlocksPerTick;
    }

    @Override
    public boolean isValid() {
      return true;
    }

    @Override
    public boolean isMoving() {
      return moving;
    }

    @Override
    public double currentSpeedBlocksPerTick() {
      return speedBlocksPerTick;
    }

    @Override
    public UUID worldId() {
      return worldId;
    }

    @Override
    public TrainProperties properties() {
      return properties;
    }

    @Override
    public void stop() {
      stopCalls++;
    }

    @Override
    public void launch(double targetBlocksPerTick, double accelBlocksPerTickSquared) {
      launchCalls++;
    }

    @Override
    public void destroy() {
      destroyCalls++;
    }

    @Override
    public void setRouteIndex(int index) {}

    @Override
    public void setRouteId(String routeId) {}

    @Override
    public void setDestination(String destination) {}

    @Override
    public java.util.Optional<org.bukkit.block.BlockFace> forwardDirection() {
      return java.util.Optional.empty();
    }

    @Override
    public void reverse() {}
  }

  // ====== CHANGE Action 测试 ======

  @Test
  void handleChangeAction_updatesOperatorAndLineTags() throws Exception {
    TagStore tags =
        new TagStore(
            "train-1",
            "FTA_OPERATOR_CODE=SURN",
            "FTA_LINE_CODE=L1",
            "FTA_ROUTE_CODE=R1",
            "FTA_ROUTE_INDEX=0");

    RouteStop stopWithChange =
        new RouteStop(
            UUID.randomUUID(),
            1,
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            RouteStopPassType.STOP,
            Optional.of("CHANGE:NEWOP:NEWLINE"));

    RuntimeDispatchService service = createMinimalService();
    // 使用反射调用 private 方法
    java.lang.reflect.Method method =
        RuntimeDispatchService.class.getDeclaredMethod(
            "handleChangeAction",
            String.class,
            TrainProperties.class,
            org.fetarute.fetaruteTCAddon.company.model.RouteStop.class);
    method.setAccessible(true);
    boolean result = (boolean) method.invoke(service, "train-1", tags.properties(), stopWithChange);

    assertTrue(result);
    assertEquals(
        "NEWOP", TrainTagHelper.readTagValue(tags.properties(), "FTA_OPERATOR_CODE").orElse(""));
    assertEquals(
        "NEWLINE", TrainTagHelper.readTagValue(tags.properties(), "FTA_LINE_CODE").orElse(""));
    // ROUTE_CODE 不应被修改
    assertEquals("R1", TrainTagHelper.readTagValue(tags.properties(), "FTA_ROUTE_CODE").orElse(""));
  }

  @Test
  void handleChangeAction_returnsFalseWhenNoChangeDirective() throws Exception {
    TagStore tags =
        new TagStore(
            "train-1",
            "FTA_OPERATOR_CODE=SURN",
            "FTA_LINE_CODE=L1",
            "FTA_ROUTE_CODE=R1",
            "FTA_ROUTE_INDEX=0");

    RouteStop stopWithoutChange =
        new RouteStop(
            UUID.randomUUID(),
            1,
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            RouteStopPassType.STOP,
            Optional.empty());

    RuntimeDispatchService service = createMinimalService();
    java.lang.reflect.Method method =
        RuntimeDispatchService.class.getDeclaredMethod(
            "handleChangeAction",
            String.class,
            TrainProperties.class,
            org.fetarute.fetaruteTCAddon.company.model.RouteStop.class);
    method.setAccessible(true);
    boolean result =
        (boolean) method.invoke(service, "train-1", tags.properties(), stopWithoutChange);

    assertFalse(result);
    // 原有 tags 不应被修改
    assertEquals(
        "SURN", TrainTagHelper.readTagValue(tags.properties(), "FTA_OPERATOR_CODE").orElse(""));
    assertEquals("L1", TrainTagHelper.readTagValue(tags.properties(), "FTA_LINE_CODE").orElse(""));
  }

  @Test
  void handleChangeAction_returnsFalseWhenInvalidFormat() throws Exception {
    TagStore tags =
        new TagStore(
            "train-1",
            "FTA_OPERATOR_CODE=SURN",
            "FTA_LINE_CODE=L1",
            "FTA_ROUTE_CODE=R1",
            "FTA_ROUTE_INDEX=0");

    // 格式错误：只有一个片段
    RouteStop stopWithInvalidChange =
        new RouteStop(
            UUID.randomUUID(),
            1,
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            RouteStopPassType.STOP,
            Optional.of("CHANGE:ONLYONE"));

    RuntimeDispatchService service = createMinimalService();
    java.lang.reflect.Method method =
        RuntimeDispatchService.class.getDeclaredMethod(
            "handleChangeAction",
            String.class,
            TrainProperties.class,
            org.fetarute.fetaruteTCAddon.company.model.RouteStop.class);
    method.setAccessible(true);
    boolean result =
        (boolean) method.invoke(service, "train-1", tags.properties(), stopWithInvalidChange);

    assertFalse(result);
    // 原有 tags 不应被修改
    assertEquals(
        "SURN", TrainTagHelper.readTagValue(tags.properties(), "FTA_OPERATOR_CODE").orElse(""));
  }

  @Test
  void handleChangeAction_handlesMultilineNotes() throws Exception {
    TagStore tags =
        new TagStore(
            "train-1",
            "FTA_OPERATOR_CODE=SURN",
            "FTA_LINE_CODE=L1",
            "FTA_ROUTE_CODE=R1",
            "FTA_ROUTE_INDEX=0");

    // 多行 notes，CHANGE 在第二行
    RouteStop stopWithMultilineNotes =
        new RouteStop(
            UUID.randomUUID(),
            1,
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            RouteStopPassType.STOP,
            Optional.of("Some comment\nCHANGE:NEWOP2:NEWLINE2\nAnother comment"));

    RuntimeDispatchService service = createMinimalService();
    java.lang.reflect.Method method =
        RuntimeDispatchService.class.getDeclaredMethod(
            "handleChangeAction",
            String.class,
            TrainProperties.class,
            org.fetarute.fetaruteTCAddon.company.model.RouteStop.class);
    method.setAccessible(true);
    boolean result =
        (boolean) method.invoke(service, "train-1", tags.properties(), stopWithMultilineNotes);

    assertTrue(result);
    assertEquals(
        "NEWOP2", TrainTagHelper.readTagValue(tags.properties(), "FTA_OPERATOR_CODE").orElse(""));
    assertEquals(
        "NEWLINE2", TrainTagHelper.readTagValue(tags.properties(), "FTA_LINE_CODE").orElse(""));
  }

  // 注意：handleStationArrival 测试需要 TrainCarts 依赖，改用功能文档验证
  // handleStationArrival 方法已在 AutoStationSignAction 中调用，功能集成测试由手动验证覆盖

  private RuntimeDispatchService createMinimalService() {
    ConfigManager configManager = mock(ConfigManager.class);
    when(configManager.current()).thenReturn(testConfigView(20, 20.0));

    return new RuntimeDispatchService(
        mock(OccupancyManager.class),
        mock(RailGraphService.class),
        mock(RouteDefinitionCache.class),
        new RouteProgressRegistry(),
        mock(SignNodeRegistry.class),
        mock(LayoverRegistry.class),
        new DwellRegistry(),
        configManager,
        null,
        mock(TrainConfigResolver.class),
        msg -> {});
  }
}
