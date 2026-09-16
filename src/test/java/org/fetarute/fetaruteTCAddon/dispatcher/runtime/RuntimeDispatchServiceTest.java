package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchTestFixtures.dynamicStop;
import static org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchTestFixtures.graphWithConflictFreeLinearPath;
import static org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchTestFixtures.routeStop;
import static org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchTestFixtures.testConfigView;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import com.bergerkiller.bukkit.tc.TrainCarts;
import com.bergerkiller.bukkit.tc.offline.train.OfflineGroupManager;
import com.bergerkiller.bukkit.tc.properties.TrainProperties;
import com.bergerkiller.bukkit.tc.properties.TrainPropertiesStore;
import com.bergerkiller.bukkit.tc.signactions.SignActionType;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.company.model.RouteStopPassType;
import org.fetarute.fetaruteTCAddon.config.ConfigManager;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailEdge;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphConflictSupport;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphInterlockingSupport;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphService;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.SimpleRailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.interlocking.InterlockingZoneInfo;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.interlocking.RailFootprintCell;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.interlocking.RailInterlockingState;
import org.fetarute.fetaruteTCAddon.dispatcher.health.HealthAlertBus;
import org.fetarute.fetaruteTCAddon.dispatcher.health.TrainHealthMonitor;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode;
import org.fetarute.fetaruteTCAddon.dispatcher.node.WaypointMetadata;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinitionCache;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteId;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteLifecycleMode;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchTestFixtures.FakeTrain;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchTestFixtures.TagStore;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.config.TrainConfigResolver;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.ControlDiagnostics;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.supervisor.CanonicalForwardPathEvidence;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.supervisor.RiskFreshness;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.supervisor.RiskSource;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.supervisor.SmartDispatcherController;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.supervisor.SmartDispatcherMode;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.supervisor.SmartDispatcherPlannerMode;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.supervisor.SmartWaitForPlanner;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.AdvisoryRisk;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.AdvisoryRiskSource;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.AuthorityHandoffSupport;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.AuthorizationPurpose;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.ClaimRole;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.ConflictClearingEvidenceKind;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.CorridorDirection;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.DirectedTraversalContext;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.ExpandedPathPlan;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.HeadwayRule;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.MovementPlanSnapshot;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyAdvisoryPreviewSupport;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyClaim;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyDecision;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyManager;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyQueueEntry;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyQueueSnapshot;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyQueueSupport;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyRequest;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyRequestBuilder;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyRequestContext;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyResource;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.PhysicalFootprintHydrationSupport;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.ResourceIntent;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.ResourceKind;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspect;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspectPolicy;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SimpleOccupancyManager;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.StartupOccupancyReconstructionSupport;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SwitcherMovementTopology;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.TrainNameNormalizer;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.VerifiedSwitcherDrainClaims;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeRegistry;
import org.fetarute.fetaruteTCAddon.dispatcher.signal.SignalComputationTrace;
import org.fetarute.fetaruteTCAddon.dispatcher.signal.SignalDecisionInputClassifier;
import org.fetarute.fetaruteTCAddon.dispatcher.signal.SignalDecisionInputType;
import org.fetarute.fetaruteTCAddon.dispatcher.signal.SignalPublicationGate;
import org.fetarute.fetaruteTCAddon.dispatcher.signal.event.OccupancyReleasedEvent;
import org.fetarute.fetaruteTCAddon.dispatcher.signal.event.SignalEventBus;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
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
  void handleSignalTickFailsClosedAndRetainsTurnbackGuardWhenRouteIndexMissing() {
    RouteDefinition route =
        new RouteDefinition(
            RouteId.of("r"), List.of(NodeId.of("A"), NodeId.of("B")), Optional.empty());
    TagStore tags =
        new TagStore("train-1", "FTA_OPERATOR_CODE=op", "FTA_LINE_CODE=l1", "FTA_ROUTE_CODE=r1");
    UUID worldId = UUID.randomUUID();

    ConfigManager configManager = mock(ConfigManager.class);
    when(configManager.current()).thenReturn(testConfigView(20, 20.0));

    OccupancyManager occupancyManager = mockOccupancyManager();
    RouteDefinitionCache routeDefinitions = mock(RouteDefinitionCache.class);
    when(routeDefinitions.findByCodes("op", "l1", "r1")).thenReturn(Optional.of(route));
    RouteProgressRegistry registry = new RouteProgressRegistry();
    registry.initFromTags("train-1", tags.properties(), route);
    registry.updateSignal("train-1", SignalAspect.PROCEED, Instant.now());

    RuntimeDispatchService service =
        new RuntimeDispatchService(
            occupancyManager,
            mock(RailGraphService.class),
            routeDefinitions,
            registry,
            mock(SignNodeRegistry.class),
            mock(LayoverRegistry.class),
            new DwellRegistry(),
            configManager,
            null,
            new TrainConfigResolver(),
            null);
    OccupancyResource guarded = OccupancyResource.forConflict("stale-turnback");
    RuntimeTrainHandle guardedTrain = mock(RuntimeTrainHandle.class);
    when(guardedTrain.estimatedTrainLengthBlocks()).thenReturn(OptionalDouble.of(1.0));
    service.registerTurnbackFootprintGuard(
        guardedTrain,
        "train-1",
        NodeId.of("A"),
        Set.of(guarded),
        List.of(
            new TurnbackFootprintGuardRegistry.ForwardPathEdge(
                NodeId.of("A"), NodeId.of("B"), 10.0)),
        1);
    OccupancyDecision protectedDecision =
        new OccupancyDecision(
            false,
            Instant.now(),
            SignalAspect.STOP,
            List.of(
                new OccupancyClaim(
                    guarded,
                    "other",
                    Optional.empty(),
                    Instant.now(),
                    Duration.ZERO,
                    Optional.empty(),
                    ClaimRole.MOVEMENT_REQUIRED)),
            false);
    assertTrue(service.hasTurnbackProtectedBlocker("train-1", protectedDecision));

    FakeTrain train = new FakeTrain(worldId, tags.properties(), false);

    service.handleSignalTick(train, false);

    assertEquals(SignalAspect.STOP, registry.get("train-1").orElseThrow().lastSignal());
    assertTrue(service.isMovementInhibited("train-1"));
    assertEquals(1, train.hardStopCalls);
    verify(occupancyManager, never()).releaseByTrain(anyString());
    assertTrue(service.hasTurnbackProtectedBlocker("train-1", protectedDecision));
  }

  @Test
  void handleSignalTickInitializesProgressRegistryAndAvoidsRepeatedLaunch() throws Exception {
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

    SimpleOccupancyManager occupancyManager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());

    SignNodeRegistry signNodeRegistry = mock(SignNodeRegistry.class);

    List<String> debugMessages = new ArrayList<>();
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
            debugMessages::add);

    FakeTrain train = new FakeTrain(worldId, tags.properties(), false);

    service.handleSignalTick(train, false);
    assertEquals(1, train.launchCalls, debugMessages.toString());
    long occupancyVersionAfterInitialAuthority = occupancyManager.version();
    MovementAuthorizationToken tokenAfterInitialAuthority = movementTokens(service).getFirst();

    // 同一物理授权下，静止不代表可以反复重置 TrainCarts action queue、换发 token 或收缩再申请占用。
    // 清掉旧冷却 tag 模拟实际冷却时间持续流逝；真正的失速恢复由显式健康状态机决定。
    tags.removeTagKey("FTA_LAST_LAUNCH_AT");
    for (int iteration = 0; iteration < 1_000; iteration++) {
      service.handleSignalTick(train, false);
    }
    assertEquals(1, train.launchCalls, debugMessages.toString());
    assertEquals(
        tokenAfterInitialAuthority,
        movementTokens(service).getFirst(),
        "无物理推进的相同授权不得换发 movement token");
    assertEquals(
        occupancyVersionAfterInitialAuthority,
        occupancyManager.version(),
        "无物理推进的相同授权不得推进 occupancy version: " + debugMessages);

    // 已在移动的列车信号未变化时不重复 launch。
    tags.removeTagKey("FTA_LAST_LAUNCH_AT");
    FakeTrain movingTrain = new FakeTrain(worldId, tags.properties(), true, 1.0);
    service.handleSignalTick(movingTrain, false);
    assertEquals(0, movingTrain.launchCalls);
  }

  @Test
  void handleSignalTickRevokesProceedUntilGraphSnapshotReturns() {
    NodeId currentNode = NodeId.of("A");
    NodeId nextNode = NodeId.of("B");
    RouteDefinition route =
        new RouteDefinition(RouteId.of("r"), List.of(currentNode, nextNode), Optional.empty());
    TagStore tags =
        new TagStore(
            "train-1",
            "FTA_OPERATOR_CODE=op",
            "FTA_LINE_CODE=l1",
            "FTA_ROUTE_CODE=r1",
            "FTA_ROUTE_INDEX=0");
    UUID worldId = UUID.randomUUID();
    RailGraph graph = graphWithSingleEdge(currentNode, nextNode, 10);
    AtomicBoolean graphAvailable = new AtomicBoolean(true);

    ConfigManager configManager = mock(ConfigManager.class);
    when(configManager.current()).thenReturn(testConfigView(20, 20.0));

    RailGraphService railGraphService = mock(RailGraphService.class);
    when(railGraphService.getSnapshot(worldId))
        .thenAnswer(
            ignored ->
                graphAvailable.get()
                    ? Optional.of(new RailGraphService.RailGraphSnapshot(graph, Instant.now()))
                    : Optional.empty());
    when(railGraphService.effectiveSpeedLimitBlocksPerSecond(any(), any(), any(), anyDouble()))
        .thenReturn(1000.0);

    RouteDefinitionCache routeDefinitions = mock(RouteDefinitionCache.class);
    when(routeDefinitions.findByCodes("op", "l1", "r1")).thenReturn(Optional.of(route));

    OccupancyManager occupancyManager = mockOccupancyManager();
    when(occupancyManager.canEnter(any())).thenAnswer(allowProceed());
    when(occupancyManager.acquire(any())).thenAnswer(allowProceed());
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
    assertEquals(SignalAspect.PROCEED, registry.get("train-1").orElseThrow().lastSignal());

    graphAvailable.set(false);
    service.handleSignalTick(train, false);

    assertEquals(SignalAspect.STOP, registry.get("train-1").orElseThrow().lastSignal());
    assertTrue(service.isMovementInhibited("train-1"));
    assertEquals(1, train.hardStopCalls);
    RuntimeStopState stopState = service.getActiveStopState("train-1").orElseThrow();
    assertEquals(HardStopReason.SAFETY_STATE_UNAVAILABLE.name(), stopState.reasonCode());
    assertEquals(
        RuntimeStopState.ReleaseCondition.SAFETY_STATE_RESTORED_AND_AUTHORITY_REISSUED,
        stopState.releaseCondition());
    assertEquals(
        RuntimeStopState.RetryTrigger.GRAPH_ROUTE_OR_PROGRESS_REFRESH, stopState.retryTrigger());
    assertTrue(stopState.invalidatesAuthority());

    graphAvailable.set(true);
    service.handleSignalTick(train, false);

    assertEquals(SignalAspect.PROCEED, registry.get("train-1").orElseThrow().lastSignal());
    assertFalse(service.isMovementInhibited("train-1"));
    assertTrue(service.getActiveStopState("train-1").isEmpty());
  }

  @Test
  void handleSignalTickRevokesProceedUntilRouteDefinitionReturns() {
    NodeId currentNode = NodeId.of("A");
    NodeId nextNode = NodeId.of("B");
    RouteDefinition route =
        new RouteDefinition(RouteId.of("r"), List.of(currentNode, nextNode), Optional.empty());
    TagStore tags =
        new TagStore(
            "train-1",
            "FTA_OPERATOR_CODE=op",
            "FTA_LINE_CODE=l1",
            "FTA_ROUTE_CODE=r1",
            "FTA_ROUTE_INDEX=0");
    UUID worldId = UUID.randomUUID();
    AtomicBoolean routeAvailable = new AtomicBoolean(true);

    ConfigManager configManager = mock(ConfigManager.class);
    when(configManager.current()).thenReturn(testConfigView(20, 20.0));

    RailGraphService railGraphService = mock(RailGraphService.class);
    when(railGraphService.getSnapshot(worldId))
        .thenReturn(
            Optional.of(
                new RailGraphService.RailGraphSnapshot(
                    graphWithSingleEdge(currentNode, nextNode, 10), Instant.now())));
    when(railGraphService.effectiveSpeedLimitBlocksPerSecond(any(), any(), any(), anyDouble()))
        .thenReturn(1000.0);

    RouteDefinitionCache routeDefinitions = mock(RouteDefinitionCache.class);
    when(routeDefinitions.findByCodes("op", "l1", "r1"))
        .thenAnswer(ignored -> routeAvailable.get() ? Optional.of(route) : Optional.empty());

    OccupancyManager occupancyManager = mockOccupancyManager();
    when(occupancyManager.canEnter(any())).thenAnswer(allowProceed());
    when(occupancyManager.acquire(any())).thenAnswer(allowProceed());
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
    assertEquals(SignalAspect.PROCEED, registry.get("train-1").orElseThrow().lastSignal());

    routeAvailable.set(false);
    service.handleSignalTick(train, false);

    assertEquals(SignalAspect.STOP, registry.get("train-1").orElseThrow().lastSignal());
    assertTrue(service.isMovementInhibited("train-1"));
    assertEquals(1, train.hardStopCalls);

    routeAvailable.set(true);
    service.handleSignalTick(train, false);

    assertEquals(SignalAspect.PROCEED, registry.get("train-1").orElseThrow().lastSignal());
    assertFalse(service.isMovementInhibited("train-1"));
  }

  @Test
  void handleSignalTickRevokesProceedUntilNegativeRouteIndexIsRepaired() {
    NodeId currentNode = NodeId.of("A");
    NodeId nextNode = NodeId.of("B");
    RouteDefinition route =
        new RouteDefinition(RouteId.of("r"), List.of(currentNode, nextNode), Optional.empty());
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
                    graphWithSingleEdge(currentNode, nextNode, 10), Instant.now())));
    when(railGraphService.effectiveSpeedLimitBlocksPerSecond(any(), any(), any(), anyDouble()))
        .thenReturn(1000.0);

    RouteDefinitionCache routeDefinitions = mock(RouteDefinitionCache.class);
    when(routeDefinitions.findByCodes("op", "l1", "r1")).thenReturn(Optional.of(route));

    OccupancyManager occupancyManager = mockOccupancyManager();
    when(occupancyManager.canEnter(any())).thenAnswer(allowProceed());
    when(occupancyManager.acquire(any())).thenAnswer(allowProceed());
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
    assertEquals(SignalAspect.PROCEED, registry.get("train-1").orElseThrow().lastSignal());

    tags.removeTagKey(RouteProgressRegistry.TAG_ROUTE_INDEX);
    tags.tags.add(RouteProgressRegistry.TAG_ROUTE_INDEX + "=-1");
    service.handleSignalTick(train, false);

    assertEquals(SignalAspect.STOP, registry.get("train-1").orElseThrow().lastSignal());
    assertTrue(service.isMovementInhibited("train-1"));
    assertEquals(1, train.hardStopCalls);
    verify(occupancyManager, never()).releaseByTrain(anyString());
    RuntimeStopState stopState = service.getActiveStopState("train-1").orElseThrow();
    assertEquals(HardStopReason.SAFETY_STATE_UNAVAILABLE.name(), stopState.reasonCode());
    assertEquals(
        RuntimeStopState.ReleaseCondition.SAFETY_STATE_RESTORED_AND_AUTHORITY_REISSUED,
        stopState.releaseCondition());

    tags.removeTagKey(RouteProgressRegistry.TAG_ROUTE_INDEX);
    tags.tags.add(RouteProgressRegistry.TAG_ROUTE_INDEX + "=0");
    service.handleSignalTick(train, false);

    assertEquals(SignalAspect.PROCEED, registry.get("train-1").orElseThrow().lastSignal());
    assertFalse(service.isMovementInhibited("train-1"));
    assertTrue(service.getActiveStopState("train-1").isEmpty());
  }

  @Test
  void handleSignalTickPublishesStopForProtectiveRetainWithoutInvalidatingAuthority()
      throws Exception {
    NodeId currentNode = NodeId.of("A");
    NodeId nextNode = NodeId.of("B");
    RouteDefinition route =
        new RouteDefinition(RouteId.of("r"), List.of(currentNode, nextNode), Optional.empty());
    TagStore tags =
        new TagStore(
            "train-1",
            "FTA_OPERATOR_CODE=op",
            "FTA_LINE_CODE=l1",
            "FTA_ROUTE_CODE=r1",
            "FTA_ROUTE_INDEX=0");
    UUID worldId = UUID.randomUUID();
    AtomicBoolean protectiveBlocked = new AtomicBoolean(false);

    ConfigManager configManager = mock(ConfigManager.class);
    when(configManager.current()).thenReturn(testConfigView(20, 20.0));

    RailGraphService railGraphService = mock(RailGraphService.class);
    when(railGraphService.getSnapshot(worldId))
        .thenReturn(
            Optional.of(
                new RailGraphService.RailGraphSnapshot(
                    graphWithSingleEdge(currentNode, nextNode, 10), Instant.now())));
    when(railGraphService.effectiveSpeedLimitBlocksPerSecond(any(), any(), any(), anyDouble()))
        .thenReturn(1000.0);

    RouteDefinitionCache routeDefinitions = mock(RouteDefinitionCache.class);
    when(routeDefinitions.findByCodes("op", "l1", "r1")).thenReturn(Optional.of(route));

    OccupancyManager occupancyManager = mockOccupancyManager();
    when(occupancyManager.snapshotClaims()).thenReturn(List.of());
    when(occupancyManager.canEnter(any()))
        .thenAnswer(
            invocation -> {
              OccupancyRequest request = invocation.getArgument(0);
              if (!protectiveBlocked.get()) {
                return new OccupancyDecision(true, request.now(), SignalAspect.PROCEED, List.of());
              }
              OccupancyResource resource = request.resourceList().get(0);
              return new OccupancyDecision(
                  false,
                  request.now(),
                  SignalAspect.STOP,
                  List.of(
                      new OccupancyClaim(
                          resource,
                          "protecting-train",
                          Optional.empty(),
                          request.now(),
                          Duration.ZERO,
                          Optional.empty(),
                          ClaimRole.PROTECTIVE_RETAIN)));
            });
    when(occupancyManager.acquire(any())).thenAnswer(allowProceed());

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
    assertEquals(SignalAspect.PROCEED, registry.get("train-1").orElseThrow().lastSignal());

    registry.updateSignal("train-1", SignalAspect.PROCEED_WITH_CAUTION, Instant.now());
    installPublishedPhysicalSignal(service, "train-1", SignalAspect.PROCEED_WITH_CAUTION);
    protectiveBlocked.set(true);
    service.handleSignalTick(train, false);

    assertEquals(SignalAspect.STOP, registry.get("train-1").orElseThrow().lastSignal());
    assertFalse(service.isMovementInhibited("train-1"));
    RuntimeStopState stopState = service.getActiveStopState("train-1").orElseThrow();
    assertEquals("PROTECTIVE_RETAIN_HOLD", stopState.reasonCode());
    assertEquals(
        RuntimeStopState.ReleaseCondition.BLOCKING_RESOURCES_RELEASED_OR_TRANSFERRED,
        stopState.releaseCondition());
    assertEquals(
        RuntimeStopState.RetryTrigger.OCCUPANCY_CHANGE_OR_PERIODIC_RECHECK,
        stopState.retryTrigger());
    assertFalse(stopState.invalidatesAuthority());
    assertEquals("protecting-train", stopState.blockers().get(0).owner());
  }

  @Test
  void reloadAtLayoverRouteIndexZeroDoesNotRestoreOriginalDepotAsCurrentNode() throws Exception {
    NodeId originalDepot = NodeId.of("SURC:D:LWN:2");
    NodeId terminalStation = NodeId.of("SURC:S:CHT:3");
    NodeId outboundThroat = NodeId.of("SURC:S:CHT:3:003");
    RouteDefinition route =
        new RouteDefinition(
            RouteId.of("turnback-route"),
            List.of(terminalStation, outboundThroat),
            Optional.empty());
    TagStore tags =
        new TagStore(
            "turnback-train",
            "FTA_OPERATOR_CODE=op",
            "FTA_LINE_CODE=l1",
            "FTA_ROUTE_CODE=turnback",
            "FTA_ROUTE_INDEX=0",
            "FTA_ROUTE_UPDATED_AT=1767225600000",
            TrainSpawnTagInitializer.TAG_SPAWN_ORIGIN_PENDING + "=false",
            "FTA_DEPOT_ID=" + originalDepot.value());
    UUID worldId = UUID.randomUUID();
    RailGraph graph = graphWithTwoEdges(originalDepot, terminalStation, outboundThroat, 100, 10);

    ConfigManager configManager = mock(ConfigManager.class);
    when(configManager.current()).thenReturn(testConfigView(20, 20.0));

    RailGraphService railGraphService = mock(RailGraphService.class);
    when(railGraphService.getSnapshot(worldId))
        .thenReturn(Optional.of(new RailGraphService.RailGraphSnapshot(graph, Instant.now())));
    when(railGraphService.effectiveSpeedLimitBlocksPerSecond(any(), any(), any(), anyDouble()))
        .thenReturn(1000.0);

    RouteDefinitionCache routeDefinitions = mock(RouteDefinitionCache.class);
    when(routeDefinitions.findByCodes("op", "l1", "turnback")).thenReturn(Optional.of(route));
    when(routeDefinitions.findStop(route.id(), 0))
        .thenReturn(Optional.of(dynamicStop(0, terminalStation, "CRET DYNAMIC:SURC:D:LWN:[1:2]")));

    OccupancyManager occupancyManager = mockOccupancyManager();
    when(occupancyManager.snapshotClaims()).thenReturn(List.of());
    when(occupancyManager.canEnter(any())).thenAnswer(allowProceed());
    when(occupancyManager.acquire(any())).thenAnswer(allowProceed());
    List<String> debugMessages = new ArrayList<>();

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
            debugMessages::add);

    FakeTrain train = new FakeTrain(worldId, tags.properties(), false);
    java.lang.reflect.Method forceOverride =
        RuntimeDispatchService.class.getDeclaredMethod(
            "forceRecordEffectiveNode",
            String.class,
            RouteDefinition.class,
            int.class,
            NodeId.class);
    forceOverride.setAccessible(true);
    forceOverride.invoke(service, "turnback-train", route, 0, originalDepot);
    assertTrue(
        service.checkDeparture(
            train,
            new SignNodeDefinition(
                terminalStation, NodeType.STATION, Optional.empty(), Optional.empty())));
    service.handleSignalTick(train, false);

    ArgumentCaptor<OccupancyRequest> requestCaptor =
        ArgumentCaptor.forClass(OccupancyRequest.class);
    verify(occupancyManager, atLeastOnce()).canEnter(requestCaptor.capture());
    OccupancyResource staleDepotResource = OccupancyResource.forNode(originalDepot);
    assertFalse(
        requestCaptor.getAllValues().stream()
            .anyMatch(request -> request.resourceList().contains(staleDepotResource)),
        requestCaptor.getAllValues().toString());
    assertTrue(
        requestCaptor.getAllValues().stream()
            .map(OccupancyRequest::movementPlanSnapshot)
            .flatMap(Optional::stream)
            .anyMatch(plan -> plan.expandedPathNodes().get(0).equals(terminalStation)),
        requestCaptor.getAllValues().toString());
    assertTrue(
        debugMessages.stream()
            .anyMatch(message -> message.contains("SMART_EFFECTIVE_NODE_OVERRIDE_CLEARED")),
        debugMessages.toString());
    assertFalse(
        debugMessages.stream().anyMatch(message -> message.contains("首站位置初始化")),
        debugMessages.toString());
  }

  @Test
  void freshCretSpawnRestoresMaterializedDepotBeforeFirstProgressCommit() throws Exception {
    NodeId dynamicPlaceholder = NodeId.of("SURC:D:LWN:1");
    NodeId materializedDepot = NodeId.of("SURC:D:LWN:2");
    NodeId firstStation = NodeId.of("SURC:S:LWN:1");
    RouteDefinition route =
        new RouteDefinition(
            RouteId.of("fresh-cret-route"),
            List.of(dynamicPlaceholder, firstStation),
            Optional.empty());
    RouteDefinitionCache routeDefinitions = mock(RouteDefinitionCache.class);
    when(routeDefinitions.findStop(route.id(), 0))
        .thenReturn(
            Optional.of(dynamicStop(0, dynamicPlaceholder, "CRET DYNAMIC:SURC:D:LWN:[1:2]")));
    TagStore tags =
        new TagStore(
            "fresh-cret-train",
            "FTA_ROUTE_INDEX=0",
            "FTA_RUN_AT=1767225600000",
            "FTA_ROUTE_UPDATED_AT=1767225600000",
            TrainSpawnTagInitializer.TAG_SPAWN_ORIGIN_PENDING + "=true",
            "FTA_DEPOT_ID=" + materializedDepot.value());
    List<String> debugMessages = new ArrayList<>();
    RuntimeDispatchService service =
        createMinimalService(
            mockOccupancyManager(), routeDefinitions, new RouteProgressRegistry(), debugMessages);

    java.lang.reflect.Method restoreSpawnOrigin =
        RuntimeDispatchService.class.getDeclaredMethod(
            "restoreSpawnOriginOverrideIfNeeded",
            String.class,
            RouteDefinition.class,
            int.class,
            TrainProperties.class);
    restoreSpawnOrigin.setAccessible(true);
    restoreSpawnOrigin.invoke(service, "fresh-cret-train", route, 0, tags.properties());

    assertEquals(
        List.of(materializedDepot, firstStation),
        service.resolveDirectionContextWaypointsForEvent("fresh-cret-train", route));
    assertTrue(
        debugMessages.stream()
            .anyMatch(
                message ->
                    message.contains("首站位置初始化") && message.contains("reason=cret-spawn-origin")),
        debugMessages.toString());
  }

  @Test
  void acceptedLaunchBeforeFirstGraphNodeKeepsSpawnOriginBootstrap() throws Exception {
    NodeId dynamicPlaceholder = NodeId.of("SURC:D:LWN:1");
    NodeId materializedDepot = NodeId.of("SURC:D:LWN:2");
    NodeId firstStation = NodeId.of("SURC:S:LWN:1");
    RouteDefinition route =
        new RouteDefinition(
            RouteId.of("fresh-cret-route"),
            List.of(dynamicPlaceholder, firstStation),
            Optional.empty());
    RouteDefinitionCache routeDefinitions = mock(RouteDefinitionCache.class);
    when(routeDefinitions.findByCodes("op", "l1", "r1")).thenReturn(Optional.of(route));
    when(routeDefinitions.findStop(route.id(), 0))
        .thenReturn(
            Optional.of(dynamicStop(0, dynamicPlaceholder, "CRET DYNAMIC:SURC:D:LWN:[1:2]")));
    TagStore tags =
        new TagStore(
            "fresh-cret-train",
            "FTA_OPERATOR_CODE=op",
            "FTA_LINE_CODE=l1",
            "FTA_ROUTE_CODE=r1",
            "FTA_ROUTE_INDEX=0",
            "FTA_RUN_AT=1767225600000",
            "FTA_ROUTE_UPDATED_AT=1767225600000",
            TrainSpawnTagInitializer.TAG_SPAWN_ORIGIN_PENDING + "=true",
            "FTA_DEPOT_ID=" + materializedDepot.value());
    UUID worldId = UUID.randomUUID();
    ConfigManager configManager = mock(ConfigManager.class);
    when(configManager.current()).thenReturn(testConfigView(20, 20.0));
    RailGraphService railGraphService = mock(RailGraphService.class);
    when(railGraphService.getSnapshot(worldId))
        .thenReturn(
            Optional.of(
                new RailGraphService.RailGraphSnapshot(
                    graphWithSingleEdge(materializedDepot, firstStation, 10), Instant.now())));
    when(railGraphService.effectiveSpeedLimitBlocksPerSecond(any(), any(), any(), anyDouble()))
        .thenReturn(20.0);
    OccupancyManager occupancyManager = mockOccupancyManager();
    when(occupancyManager.canEnter(any())).thenAnswer(allowProceed());
    when(occupancyManager.acquire(any())).thenAnswer(allowProceed());
    RouteProgressRegistry registry = new RouteProgressRegistry();
    registry.initFromTags("fresh-cret-train", tags.properties(), route);
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

    service.handleSignalTick(train, true);

    assertTrue(train.launchCalls > 0);
    assertEquals(
        Optional.of("true"),
        TrainTagHelper.readTagValue(
            tags.properties(), TrainSpawnTagInitializer.TAG_SPAWN_ORIGIN_PENDING));

    RuntimeDispatchService rebuiltService =
        createMinimalService(
            mockOccupancyManager(),
            routeDefinitions,
            new RouteProgressRegistry(),
            new ArrayList<>());
    java.lang.reflect.Method restoreSpawnOrigin =
        RuntimeDispatchService.class.getDeclaredMethod(
            "restoreSpawnOriginOverrideIfNeeded",
            String.class,
            RouteDefinition.class,
            int.class,
            TrainProperties.class);
    restoreSpawnOrigin.setAccessible(true);
    restoreSpawnOrigin.invoke(rebuiltService, "fresh-cret-train", route, 0, tags.properties());

    assertEquals(
        List.of(materializedDepot, firstStation),
        rebuiltService.resolveDirectionContextWaypointsForEvent("fresh-cret-train", route));
  }

  @Test
  void firstNonDepotGraphObservationCompletesSpawnOriginBootstrap() throws Exception {
    NodeId dynamicPlaceholder = NodeId.of("SURC:D:LWN:1");
    NodeId materializedDepot = NodeId.of("SURC:D:LWN:2");
    NodeId firstStation = NodeId.of("SURC:S:LWN:1");
    RouteDefinition route =
        new RouteDefinition(
            RouteId.of("fresh-cret-route"),
            List.of(dynamicPlaceholder, firstStation),
            Optional.empty());
    RouteDefinitionCache routeDefinitions = mock(RouteDefinitionCache.class);
    when(routeDefinitions.findStop(route.id(), 0))
        .thenReturn(
            Optional.of(dynamicStop(0, dynamicPlaceholder, "CRET DYNAMIC:SURC:D:LWN:[1:2]")));
    TagStore tags =
        new TagStore(
            "fresh-cret-train",
            "FTA_ROUTE_INDEX=0",
            TrainSpawnTagInitializer.TAG_SPAWN_ORIGIN_PENDING + "=true",
            "FTA_DEPOT_ID=" + materializedDepot.value());
    RuntimeDispatchService service =
        createMinimalService(
            mockOccupancyManager(),
            routeDefinitions,
            new RouteProgressRegistry(),
            new ArrayList<>());
    java.lang.reflect.Method observePhysicalNode =
        RuntimeDispatchService.class.getDeclaredMethod(
            "observePhysicalNodeForSpawnOrigin", TrainProperties.class, NodeId.class, int.class);
    observePhysicalNode.setAccessible(true);

    observePhysicalNode.invoke(service, tags.properties(), firstStation, 1);

    assertEquals(
        Optional.of("false"),
        TrainTagHelper.readTagValue(
            tags.properties(), TrainSpawnTagInitializer.TAG_SPAWN_ORIGIN_PENDING));
    java.lang.reflect.Method restoreSpawnOrigin =
        RuntimeDispatchService.class.getDeclaredMethod(
            "restoreSpawnOriginOverrideIfNeeded",
            String.class,
            RouteDefinition.class,
            int.class,
            TrainProperties.class);
    restoreSpawnOrigin.setAccessible(true);
    restoreSpawnOrigin.invoke(service, "fresh-cret-train", route, 0, tags.properties());
    assertEquals(
        List.of(dynamicPlaceholder, firstStation),
        service.resolveDirectionContextWaypointsForEvent("fresh-cret-train", route));
  }

  @Test
  void routeEpochChangeRejectsExistingEffectiveNodeOverrideWithoutServiceRebuild()
      throws Exception {
    NodeId staleDepot = NodeId.of("SURC:D:LWN:2");
    RouteDefinition oldRoute =
        new RouteDefinition(
            RouteId.of("old-route"),
            List.of(NodeId.of("SURC:D:LWN:1"), NodeId.of("SURC:S:LWN:1")),
            Optional.empty());
    NodeId terminal = NodeId.of("SURC:S:CHT:3");
    NodeId next = NodeId.of("SURC:S:CHT:3:003");
    RouteDefinition newRoute =
        new RouteDefinition(RouteId.of("new-route"), List.of(terminal, next), Optional.empty());
    List<String> debugMessages = new ArrayList<>();
    RuntimeDispatchService service =
        createMinimalService(
            mockOccupancyManager(),
            mock(RouteDefinitionCache.class),
            new RouteProgressRegistry(),
            debugMessages);
    java.lang.reflect.Method forceOverride =
        RuntimeDispatchService.class.getDeclaredMethod(
            "forceRecordEffectiveNode",
            String.class,
            RouteDefinition.class,
            int.class,
            NodeId.class);
    forceOverride.setAccessible(true);
    forceOverride.invoke(service, "turnback-train", oldRoute, 0, staleDepot);

    assertEquals(
        List.of(terminal, next),
        service.resolveDirectionContextWaypointsForEvent("turnback-train", newRoute));
    assertTrue(
        debugMessages.stream()
            .anyMatch(
                message ->
                    message.contains("SMART_EFFECTIVE_NODE_OVERRIDE_ROUTE_REJECTED")
                        && message.contains("staleRoute=old-route")
                        && message.contains("currentRoute=new-route")),
        debugMessages.toString());
  }

  @Test
  void sameRouteIdDefinitionChangeRejectsExistingEffectiveNodeOverride() throws Exception {
    RouteId routeId = RouteId.of("same-route");
    NodeId oldPlaceholder = NodeId.of("SURC:D:LWN:1");
    NodeId newPlaceholder = NodeId.of("SURC:D:HHU:1");
    NodeId materializedDepot = NodeId.of("SURC:D:LWN:2");
    NodeId next = NodeId.of("SURC:S:LWN:1");
    RouteDefinition oldRoute =
        new RouteDefinition(routeId, List.of(oldPlaceholder, next), Optional.empty());
    RouteDefinition refreshedRoute =
        new RouteDefinition(routeId, List.of(newPlaceholder, next), Optional.empty());
    List<String> debugMessages = new ArrayList<>();
    RuntimeDispatchService service =
        createMinimalService(
            mockOccupancyManager(),
            mock(RouteDefinitionCache.class),
            new RouteProgressRegistry(),
            debugMessages);
    java.lang.reflect.Method forceOverride =
        RuntimeDispatchService.class.getDeclaredMethod(
            "forceRecordEffectiveNode",
            String.class,
            RouteDefinition.class,
            int.class,
            NodeId.class);
    forceOverride.setAccessible(true);
    forceOverride.invoke(service, "same-route-train", oldRoute, 0, materializedDepot);

    assertEquals(
        List.of(newPlaceholder, next),
        service.resolveDirectionContextWaypointsForEvent("same-route-train", refreshedRoute));
    assertTrue(
        debugMessages.stream()
            .anyMatch(
                message ->
                    message.contains("SMART_EFFECTIVE_NODE_OVERRIDE_DEFINITION_REJECTED")
                        && message.contains("staleDeclared=SURC:D:LWN:1")
                        && message.contains("currentDeclared=SURC:D:HHU:1")),
        debugMessages.toString());
  }

  @Test
  void samePlaceholderDynamicRuleChangeRejectsMaterializedNodeOverride() throws Exception {
    RouteId routeId = RouteId.of("dynamic-route");
    NodeId placeholder = NodeId.of("SURC:D:LWN:1");
    NodeId materializedDepot = NodeId.of("SURC:D:LWN:2");
    NodeId next = NodeId.of("SURC:S:LWN:1");
    RouteDefinition route =
        new RouteDefinition(routeId, List.of(placeholder, next), Optional.empty());
    UUID persistentRouteId = UUID.randomUUID();
    RouteStop oldStop =
        new RouteStop(
            persistentRouteId,
            0,
            Optional.empty(),
            Optional.of(placeholder.value()),
            Optional.empty(),
            RouteStopPassType.STOP,
            Optional.of("CRET DYNAMIC:SURC:D:LWN:[1:2]"));
    RouteStop refreshedStop =
        new RouteStop(
            persistentRouteId,
            0,
            Optional.empty(),
            Optional.of(placeholder.value()),
            Optional.empty(),
            RouteStopPassType.STOP,
            Optional.of("CRET DYNAMIC:SURC:D:LWN:[3:4]"));
    RouteDefinitionCache routeDefinitions = mock(RouteDefinitionCache.class);
    when(routeDefinitions.findStop(route.id(), 0))
        .thenReturn(Optional.of(oldStop), Optional.of(refreshedStop));
    List<String> debugMessages = new ArrayList<>();
    RuntimeDispatchService service =
        createMinimalService(
            mockOccupancyManager(), routeDefinitions, new RouteProgressRegistry(), debugMessages);
    java.lang.reflect.Method forceOverride =
        RuntimeDispatchService.class.getDeclaredMethod(
            "forceRecordEffectiveNode",
            String.class,
            RouteDefinition.class,
            int.class,
            NodeId.class);
    forceOverride.setAccessible(true);
    forceOverride.invoke(service, "dynamic-rule-train", route, 0, materializedDepot);

    assertEquals(
        List.of(placeholder, next),
        service.resolveDirectionContextWaypointsForEvent("dynamic-rule-train", route));
    assertTrue(
        debugMessages.stream()
            .anyMatch(
                message ->
                    message.contains("SMART_EFFECTIVE_NODE_OVERRIDE_DEFINITION_REJECTED")
                        && message.contains("index=0")),
        debugMessages.toString());
  }

  @Test
  void handleSignalTickStopsWhenAcquireFailsAfterCanEnter() {
    NodeId current = NodeId.of("A");
    NodeId next = NodeId.of("B");
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

    OccupancyManager occupancyManager = mockOccupancyManager();
    when(occupancyManager.canEnter(any())).thenAnswer(allowProceed());
    OccupancyResource nextNodeResource = OccupancyResource.forNode(next);
    when(occupancyManager.acquire(any()))
        .thenAnswer(
            inv -> {
              OccupancyRequest request = inv.getArgument(0);
              if (!request.resourceList().contains(nextNodeResource)) {
                return new OccupancyDecision(true, request.now(), SignalAspect.PROCEED, List.of());
              }
              OccupancyClaim blocker =
                  new OccupancyClaim(
                      nextNodeResource,
                      "train-2",
                      Optional.of(route.id()),
                      request.now(),
                      Duration.ZERO,
                      Optional.empty());
              return new OccupancyDecision(
                  false, request.now(), SignalAspect.STOP, List.of(blocker));
            });

    RouteProgressRegistry registry = new RouteProgressRegistry();
    registry.initFromTags("train-1", tags.properties(), route);
    registry.updateSignal("train-1", SignalAspect.PROCEED, Instant.now());

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

    assertEquals(SignalAspect.STOP, registry.get("train-1").orElseThrow().lastSignal());
    assertEquals(0, train.launchCalls);
    verify(tags.properties(), never()).setDestination(any());
  }

  @Test
  void handleSignalTickHardStopsWhenInterlockingPlanCannotReachClearanceExit() {
    NodeId station = NodeId.of("OP:S:TERM:1");
    NodeId throat = NodeId.of("OP:S:TERM:1:001");
    NodeId switcher = NodeId.of("SWITCHER:Towny:1:64:1");
    RouteDefinition route =
        new RouteDefinition(RouteId.of("r"), List.of(station, switcher), Optional.empty());
    TagStore tags =
        new TagStore(
            "train-1",
            "FTA_OPERATOR_CODE=op",
            "FTA_LINE_CODE=l1",
            "FTA_ROUTE_CODE=r1",
            "FTA_ROUTE_INDEX=0");
    UUID worldId = UUID.randomUUID();

    RailEdge stationThroat =
        new RailEdge(
            EdgeId.undirected(station, throat), station, throat, 8, -1.0, true, Optional.empty());
    RailEdge throatSwitcher =
        new RailEdge(
            EdgeId.undirected(throat, switcher), throat, switcher, 6, -1.0, true, Optional.empty());
    RailGraph graph =
        new SimpleRailGraph(
            Map.of(
                station,
                    new RailNodeTest(
                        station,
                        NodeType.STATION,
                        Optional.of(WaypointMetadata.station("OP", "TERM", 1))),
                throat,
                    new RailNodeTest(
                        throat,
                        NodeType.WAYPOINT,
                        Optional.of(WaypointMetadata.stationThroat("OP", "TERM", 1, "001"))),
                switcher, new RailNodeTest(switcher, NodeType.SWITCHER, Optional.empty())),
            Map.of(
                stationThroat.id(), stationThroat,
                throatSwitcher.id(), throatSwitcher),
            Set.of());

    ConfigManager configManager = mock(ConfigManager.class);
    when(configManager.current()).thenReturn(testConfigView(20, 20.0));
    RailGraphService railGraphService = mock(RailGraphService.class);
    when(railGraphService.getSnapshot(worldId))
        .thenReturn(Optional.of(new RailGraphService.RailGraphSnapshot(graph, Instant.now())));
    RouteDefinitionCache routeDefinitions = mock(RouteDefinitionCache.class);
    when(routeDefinitions.findByCodes("op", "l1", "r1")).thenReturn(Optional.of(route));
    OccupancyManager occupancyManager = mockOccupancyManager();
    RouteProgressRegistry registry = new RouteProgressRegistry();
    registry.initFromTags("train-1", tags.properties(), route);
    registry.updateSignal("train-1", SignalAspect.PROCEED, Instant.now());
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

    assertEquals(1, train.hardStopCalls);
    assertEquals(0, train.launchCalls);
    assertEquals(SignalAspect.STOP, registry.get("train-1").orElseThrow().lastSignal());
    assertTrue(service.isMovementInhibited("train-1"));
  }

  @Test
  void handleSignalTickDoesNotWriteDynamicDestinationWhenAcquireFails() {
    NodeId current = NodeId.of("A");
    NodeId dynamic = NodeId.of("DYNAMIC");
    NodeId allocated = NodeId.of("OP:S:DEST:1");
    RouteDefinition route =
        new RouteDefinition(RouteId.of("r"), List.of(current, dynamic), Optional.empty());
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
                    graphWithSingleEdge(current, allocated, 10), Instant.now())));
    when(railGraphService.effectiveSpeedLimitBlocksPerSecond(any(), any(), any(), anyDouble()))
        .thenReturn(1000.0);

    RouteDefinitionCache routeDefinitions = mock(RouteDefinitionCache.class);
    when(routeDefinitions.findByCodes("op", "l1", "r1")).thenReturn(Optional.of(route));
    when(routeDefinitions.findStop(route.id(), 1))
        .thenReturn(Optional.of(dynamicStop(1, dynamic, "DYNAMIC:OP:S:DEST:[1:1]")));

    OccupancyManager occupancyManager = mockOccupancyManager();
    when(occupancyManager.canEnter(any())).thenAnswer(allowProceed());
    OccupancyResource allocatedResource = OccupancyResource.forNode(allocated);
    when(occupancyManager.acquire(any()))
        .thenAnswer(
            inv -> {
              OccupancyRequest request = inv.getArgument(0);
              if (!request.resourceList().contains(allocatedResource)) {
                return new OccupancyDecision(true, request.now(), SignalAspect.PROCEED, List.of());
              }
              OccupancyClaim blocker =
                  new OccupancyClaim(
                      allocatedResource,
                      "train-2",
                      Optional.of(route.id()),
                      request.now(),
                      Duration.ZERO,
                      Optional.empty());
              return new OccupancyDecision(
                  false, request.now(), SignalAspect.STOP, List.of(blocker));
            });

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

    service.handleSignalTick(new FakeTrain(worldId, tags.properties(), false), false);

    verify(tags.properties(), never()).setDestination(any());
  }

  @Test
  void handleSignalTickHoldsWithoutForwardAuthorityWhenDynamicPlatformsAreFull() {
    NodeId approach = NodeId.of("OP:W:PPK:1:001");
    NodeId platformOne = NodeId.of("OP:S:PPK:1");
    NodeId platformTwo = NodeId.of("OP:S:PPK:2");
    RouteDefinition route =
        new RouteDefinition(
            RouteId.of("ppk-arrival"), List.of(approach, platformOne), Optional.empty());
    TagStore tags =
        new TagStore(
            "incoming-train",
            "FTA_OPERATOR_CODE=op",
            "FTA_LINE_CODE=l1",
            "FTA_ROUTE_CODE=ppk-arrival",
            "FTA_ROUTE_INDEX=0");
    UUID worldId = UUID.randomUUID();
    RailEdge firstApproach =
        new RailEdge(
            EdgeId.undirected(approach, platformOne),
            approach,
            platformOne,
            10,
            -1.0,
            true,
            Optional.empty());
    RailEdge secondApproach =
        new RailEdge(
            EdgeId.undirected(approach, platformTwo),
            approach,
            platformTwo,
            10,
            -1.0,
            true,
            Optional.empty());
    RailGraph graph =
        new SimpleRailGraph(
            Map.of(
                approach, new RailNodeTest(approach),
                platformOne, new RailNodeTest(platformOne),
                platformTwo, new RailNodeTest(platformTwo)),
            Map.of(
                firstApproach.id(), firstApproach,
                secondApproach.id(), secondApproach),
            Set.of());

    ConfigManager configManager = mock(ConfigManager.class);
    when(configManager.current()).thenReturn(testConfigView(20, 20.0));
    RailGraphService railGraphService = mock(RailGraphService.class);
    when(railGraphService.getSnapshot(worldId))
        .thenReturn(Optional.of(new RailGraphService.RailGraphSnapshot(graph, Instant.now())));
    when(railGraphService.effectiveSpeedLimitBlocksPerSecond(any(), any(), any(), anyDouble()))
        .thenReturn(1000.0);
    RouteDefinitionCache routeDefinitions = mock(RouteDefinitionCache.class);
    when(routeDefinitions.findByCodes("op", "l1", "ppk-arrival")).thenReturn(Optional.of(route));
    when(routeDefinitions.findStop(route.id(), 1))
        .thenReturn(Optional.of(dynamicStop(1, platformOne, "DYNAMIC:OP:S:PPK:[1:2]")));

    OccupancyManager occupancyManager =
        mock(
            OccupancyManager.class,
            org.mockito.Mockito.withSettings()
                .extraInterfaces(OccupancyQueueSupport.class, AuthorityHandoffSupport.class));
    when(((AuthorityHandoffSupport) occupancyManager).holdsHardAuthority(any())).thenReturn(true);
    AtomicBoolean platformsFull = new AtomicBoolean(true);
    when(occupancyManager.isNodeOccupied(platformOne))
        .thenAnswer(invocation -> platformsFull.get());
    when(occupancyManager.isNodeOccupied(platformTwo))
        .thenAnswer(invocation -> platformsFull.get());
    OccupancyResource retainedPhysicalFootprint =
        OccupancyResource.forEdge(
            EdgeId.undirected(NodeId.of("PHYSICAL-A"), NodeId.of("PHYSICAL-B")));
    OccupancyClaim physicalFootprintClaim =
        new OccupancyClaim(
            retainedPhysicalFootprint,
            "incoming-train",
            Optional.of(route.id()),
            Instant.EPOCH,
            Duration.ZERO,
            Optional.empty(),
            ClaimRole.PHYSICAL_FOOTPRINT);
    OccupancyClaim occupiedPlatformClaim =
        new OccupancyClaim(
            OccupancyResource.forNode(platformOne),
            "turning-train",
            Optional.of(route.id()),
            Instant.EPOCH,
            Duration.ZERO,
            Optional.empty());
    when(occupancyManager.snapshotClaims())
        .thenAnswer(
            invocation ->
                platformsFull.get()
                    ? List.of(physicalFootprintClaim, occupiedPlatformClaim)
                    : List.of(physicalFootprintClaim));
    when(occupancyManager.canEnter(any()))
        .thenAnswer(
            invocation -> {
              OccupancyRequest request = invocation.getArgument(0);
              return platformsFull.get()
                  ? new OccupancyDecision(
                      false, request.now(), SignalAspect.STOP, List.of(occupiedPlatformClaim))
                  : new OccupancyDecision(true, request.now(), SignalAspect.PROCEED, List.of());
            });
    when(occupancyManager.acquire(any())).thenAnswer(allowProceed());
    OccupancyQueueSupport queueSupport = (OccupancyQueueSupport) occupancyManager;
    OccupancyResource staleForwardQueue =
        OccupancyResource.forConflict("single:ppk-arrival-throat");
    when(queueSupport.snapshotQueues())
        .thenReturn(
            List.of(
                new OccupancyQueueSnapshot(
                    staleForwardQueue,
                    Optional.of(CorridorDirection.B_TO_A),
                    0,
                    List.of(
                        new OccupancyQueueEntry(
                            "incoming-train",
                            CorridorDirection.B_TO_A,
                            Instant.EPOCH,
                            Instant.EPOCH,
                            10,
                            0)))));
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
    service.acquireDepartureGate("incoming-train", "ppk-dwell", "station-dwell");

    service.handleSignalTick(train, false);

    ArgumentCaptor<OccupancyRequest> capacityHoldCaptor =
        ArgumentCaptor.forClass(OccupancyRequest.class);
    verify(occupancyManager, atLeastOnce()).acquire(capacityHoldCaptor.capture());
    OccupancyResource approachResource = OccupancyResource.forNode(approach);
    assertTrue(
        capacityHoldCaptor.getAllValues().stream()
            .anyMatch(
                request ->
                    request.resourceList().equals(List.of(approachResource))
                        && request.intentFor(approachResource) == ResourceIntent.HOLD_ONLY));
    verify(occupancyManager, never())
        .canEnter(argThat(OccupancyRequest::hasMovementRequiredResources));
    verify(occupancyManager, never())
        .acquire(argThat(OccupancyRequest::hasMovementRequiredResources));
    verify(queueSupport, never()).touchQueues(any());
    verify(queueSupport)
        .removeQueueEntries(
            eq("incoming-train"),
            argThat(resources -> resources.equals(List.of(staleForwardQueue))));
    verify(occupancyManager, never()).releaseResource(any(), any());
    verify(tags.properties(), never()).setDestination(any());
    assertEquals(0, train.launchCalls);
    assertEquals(SignalAspect.STOP, registry.get("incoming-train").orElseThrow().lastSignal());

    platformsFull.set(false);
    assertTrue(service.releaseDepartureGate("incoming-train", "ppk-dwell"));
    service.handleSignalTick(train, false);

    verify(tags.properties()).setDestination(platformOne.value());
    assertTrue(train.launchCalls > 0);
  }

  @Test
  void progressTriggerHoldsWithoutForwardAuthorityWhenDynamicPlatformsAreFull() {
    NodeId approach = NodeId.of("OP:W:PPK:1:001");
    NodeId platformOne = NodeId.of("OP:S:PPK:1");
    NodeId platformTwo = NodeId.of("OP:S:PPK:2");
    RouteDefinition route =
        new RouteDefinition(
            RouteId.of("ppk-progress"), List.of(approach, platformOne), Optional.empty());
    TagStore tags =
        new TagStore(
            "incoming-progress",
            "FTA_OPERATOR_CODE=op",
            "FTA_LINE_CODE=l1",
            "FTA_ROUTE_CODE=ppk-progress",
            "FTA_ROUTE_INDEX=0");
    UUID worldId = UUID.randomUUID();
    RailEdge firstApproach =
        new RailEdge(
            EdgeId.undirected(approach, platformOne),
            approach,
            platformOne,
            10,
            -1.0,
            true,
            Optional.empty());
    RailEdge secondApproach =
        new RailEdge(
            EdgeId.undirected(approach, platformTwo),
            approach,
            platformTwo,
            10,
            -1.0,
            true,
            Optional.empty());
    RailGraph graph =
        new SimpleRailGraph(
            Map.of(
                approach, new RailNodeTest(approach),
                platformOne, new RailNodeTest(platformOne),
                platformTwo, new RailNodeTest(platformTwo)),
            Map.of(
                firstApproach.id(), firstApproach,
                secondApproach.id(), secondApproach),
            Set.of());

    ConfigManager configManager = mock(ConfigManager.class);
    when(configManager.current()).thenReturn(testConfigView(20, 20.0));
    RailGraphService railGraphService = mock(RailGraphService.class);
    when(railGraphService.getSnapshot(worldId))
        .thenReturn(Optional.of(new RailGraphService.RailGraphSnapshot(graph, Instant.now())));
    RouteDefinitionCache routeDefinitions = mock(RouteDefinitionCache.class);
    when(routeDefinitions.findByCodes("op", "l1", "ppk-progress")).thenReturn(Optional.of(route));
    when(routeDefinitions.findStop(route.id(), 1))
        .thenReturn(Optional.of(dynamicStop(1, platformOne, "DYNAMIC:OP:S:PPK:[1:2]")));
    SignNodeRegistry signNodeRegistry = mock(SignNodeRegistry.class);
    SignNodeRegistry.SignNodeInfo platformInfo =
        new SignNodeRegistry.SignNodeInfo(
            new SignNodeDefinition(
                platformOne, NodeType.STATION, Optional.empty(), Optional.empty()),
            worldId,
            "world",
            0,
            64,
            0);
    when(signNodeRegistry.findByNodeId(eq(platformOne), any()))
        .thenReturn(Optional.of(platformInfo));
    when(signNodeRegistry.findByNodeId(eq(platformTwo), any()))
        .thenReturn(Optional.of(platformInfo));

    OccupancyManager occupancyManager =
        mock(
            OccupancyManager.class,
            org.mockito.Mockito.withSettings()
                .extraInterfaces(
                    OccupancyQueueSupport.class,
                    org.fetarute
                        .fetaruteTCAddon
                        .dispatcher
                        .schedule
                        .occupancy
                        .OccupancyPreviewSupport
                        .class));
    when(occupancyManager.snapshotClaims())
        .thenReturn(
            List.of(
                new OccupancyClaim(
                    OccupancyResource.forNode(platformOne),
                    "turning-one",
                    Optional.empty(),
                    Instant.EPOCH,
                    Duration.ZERO,
                    Optional.empty()),
                new OccupancyClaim(
                    OccupancyResource.forNode(platformTwo),
                    "turning-two",
                    Optional.empty(),
                    Instant.EPOCH,
                    Duration.ZERO,
                    Optional.empty())));
    when(occupancyManager.canEnter(any()))
        .thenAnswer(
            invocation -> {
              OccupancyRequest request = invocation.getArgument(0);
              return new OccupancyDecision(false, request.now(), SignalAspect.STOP, List.of());
            });
    when(occupancyManager.acquire(any())).thenAnswer(allowProceed());

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
    com.bergerkiller.bukkit.tc.events.SignActionEvent event =
        mock(com.bergerkiller.bukkit.tc.events.SignActionEvent.class);
    org.bukkit.World world = mock(org.bukkit.World.class);
    when(world.getUID()).thenReturn(worldId);
    when(event.getWorld()).thenReturn(world);
    when(event.getAction()).thenReturn(SignActionType.MEMBER_ENTER);
    FakeTrain train = new FakeTrain(worldId, tags.properties(), false);
    SignNodeDefinition definition =
        new SignNodeDefinition(approach, NodeType.WAYPOINT, Optional.empty(), Optional.empty());

    assertFalse(service.checkDeparture(train, definition));
    service.handleWaypointMemberEnter(train, event, definition);

    RouteDefinition stopRoute =
        new RouteDefinition(
            RouteId.of("ppk-waypoint-stop"), List.of(approach, platformOne), Optional.empty());
    TagStore stopTags =
        new TagStore(
            "incoming-waypoint-stop",
            "FTA_OPERATOR_CODE=op",
            "FTA_LINE_CODE=l1",
            "FTA_ROUTE_CODE=ppk-waypoint-stop",
            "FTA_ROUTE_INDEX=0");
    when(routeDefinitions.findByCodes("op", "l1", "ppk-waypoint-stop"))
        .thenReturn(Optional.of(stopRoute));
    when(routeDefinitions.findStop(stopRoute.id(), 0))
        .thenReturn(Optional.of(routeStop(0, approach, RouteStopPassType.STOP)));
    when(routeDefinitions.findStop(stopRoute.id(), 1))
        .thenReturn(Optional.of(dynamicStop(1, platformOne, "DYNAMIC:OP:S:PPK:[1:2]")));
    RuntimeDispatchService stopService =
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

    stopService.handleWaypointMemberEnter(
        new FakeTrain(worldId, stopTags.properties(), false), event, definition);

    verify(occupancyManager, never())
        .canEnter(argThat(OccupancyRequest::hasMovementRequiredResources));
    verify(occupancyManager, never())
        .acquire(argThat(OccupancyRequest::hasMovementRequiredResources));
    verify((OccupancyQueueSupport) occupancyManager, never()).touchQueues(any());
    verify(tags.properties(), never()).setDestination(any());
    verify(stopTags.properties(), never()).setDestination(any());
  }

  @Test
  void waypointMemberEnterKeepsMaterializedDynamicPlatformWithoutGroupEnter() throws Exception {
    NodeId approach = NodeId.of("OP:W:PPK:1:001");
    NodeId platformOne = NodeId.of("OP:S:PPK:1");
    NodeId platformTwo = NodeId.of("OP:S:PPK:2");
    RouteDefinition route =
        new RouteDefinition(
            RouteId.of("ppk-materialized"), List.of(approach, platformOne), Optional.empty());
    TagStore tags =
        new TagStore(
            "incoming-materialized",
            "FTA_OPERATOR_CODE=op",
            "FTA_LINE_CODE=l1",
            "FTA_ROUTE_CODE=ppk-materialized",
            "FTA_ROUTE_INDEX=0");
    UUID worldId = UUID.randomUUID();
    RailEdge firstApproach =
        new RailEdge(
            EdgeId.undirected(approach, platformOne),
            approach,
            platformOne,
            10,
            -1.0,
            true,
            Optional.empty());
    RailEdge secondApproach =
        new RailEdge(
            EdgeId.undirected(approach, platformTwo),
            approach,
            platformTwo,
            10,
            -1.0,
            true,
            Optional.empty());
    RailGraph graph =
        new SimpleRailGraph(
            Map.of(
                approach, new RailNodeTest(approach),
                platformOne, new RailNodeTest(platformOne),
                platformTwo, new RailNodeTest(platformTwo)),
            Map.of(firstApproach.id(), firstApproach, secondApproach.id(), secondApproach),
            Set.of());

    ConfigManager configManager = mock(ConfigManager.class);
    when(configManager.current()).thenReturn(testConfigView(20, 20.0));
    RailGraphService railGraphService = mock(RailGraphService.class);
    when(railGraphService.getSnapshot(worldId))
        .thenReturn(Optional.of(new RailGraphService.RailGraphSnapshot(graph, Instant.now())));
    RouteDefinitionCache routeDefinitions = mock(RouteDefinitionCache.class);
    when(routeDefinitions.findByCodes("op", "l1", "ppk-materialized"))
        .thenReturn(Optional.of(route));
    when(routeDefinitions.findStop(route.id(), 1))
        .thenReturn(Optional.of(dynamicStop(1, platformOne, "DYNAMIC:OP:S:PPK:[1:2]")));
    SignNodeRegistry signNodeRegistry = mock(SignNodeRegistry.class);
    SignNodeRegistry.SignNodeInfo platformInfo =
        new SignNodeRegistry.SignNodeInfo(
            new SignNodeDefinition(
                platformOne, NodeType.STATION, Optional.empty(), Optional.empty()),
            worldId,
            "world",
            0,
            64,
            0);
    when(signNodeRegistry.findByNodeId(eq(platformOne), any()))
        .thenReturn(Optional.of(platformInfo));
    when(signNodeRegistry.findByNodeId(eq(platformTwo), any()))
        .thenReturn(Optional.of(platformInfo));

    OccupancyManager occupancyManager =
        mock(
            OccupancyManager.class,
            org.mockito.Mockito.withSettings()
                .extraInterfaces(
                    OccupancyQueueSupport.class,
                    org.fetarute
                        .fetaruteTCAddon
                        .dispatcher
                        .schedule
                        .occupancy
                        .OccupancyPreviewSupport
                        .class));
    when(occupancyManager.snapshotClaims()).thenReturn(List.of());
    when(((org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyPreviewSupport)
                occupancyManager)
            .canEnterPreview(any()))
        .thenAnswer(allowProceed());
    when(occupancyManager.canEnter(any())).thenAnswer(allowProceed());
    when(occupancyManager.acquire(any())).thenAnswer(allowProceed());

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
    java.lang.reflect.Method forceOverride =
        RuntimeDispatchService.class.getDeclaredMethod(
            "forceRecordEffectiveNode",
            String.class,
            RouteDefinition.class,
            int.class,
            NodeId.class);
    forceOverride.setAccessible(true);
    forceOverride.invoke(service, "incoming-materialized", route, 1, platformTwo);
    com.bergerkiller.bukkit.tc.events.SignActionEvent event =
        mock(com.bergerkiller.bukkit.tc.events.SignActionEvent.class);
    org.bukkit.World world = mock(org.bukkit.World.class);
    when(world.getUID()).thenReturn(worldId);
    when(event.getWorld()).thenReturn(world);
    when(event.getAction()).thenReturn(SignActionType.MEMBER_ENTER);

    service.handleWaypointMemberEnter(
        new FakeTrain(worldId, tags.properties(), false),
        event,
        new SignNodeDefinition(approach, NodeType.WAYPOINT, Optional.empty(), Optional.empty()));
    service.handleProgressTrigger(
        new FakeTrain(worldId, tags.properties(), false),
        event,
        new SignNodeDefinition(approach, NodeType.WAYPOINT, Optional.empty(), Optional.empty()));

    verify(tags.properties(), times(1)).setDestination(platformTwo.value());
    verify(tags.properties(), never()).setDestination(platformOne.value());
    assertEquals(
        List.of(approach, platformTwo),
        service.resolveEffectiveWaypointsForEvent("incoming-materialized", route));
  }

  @Test
  void freeDynamicPlatformWithBusyThroatKeepsNormalQueuePosition() {
    NodeId approach = NodeId.of("OP:W:PPK:1:001");
    NodeId platform = NodeId.of("OP:S:PPK:1");
    RouteDefinition route =
        new RouteDefinition(
            RouteId.of("ppk-throat-busy"), List.of(approach, platform), Optional.empty());
    TagStore tags =
        new TagStore(
            "incoming-throat-wait",
            "FTA_OPERATOR_CODE=op",
            "FTA_LINE_CODE=l1",
            "FTA_ROUTE_CODE=ppk-throat-busy",
            "FTA_ROUTE_INDEX=0");
    UUID worldId = UUID.randomUUID();
    RailGraph graph = graphWithSingleEdge(approach, platform, 10);

    ConfigManager configManager = mock(ConfigManager.class);
    when(configManager.current()).thenReturn(testConfigView(20, 20.0));
    RailGraphService railGraphService = mock(RailGraphService.class);
    when(railGraphService.getSnapshot(worldId))
        .thenReturn(Optional.of(new RailGraphService.RailGraphSnapshot(graph, Instant.now())));
    RouteDefinitionCache routeDefinitions = mock(RouteDefinitionCache.class);
    when(routeDefinitions.findByCodes("op", "l1", "ppk-throat-busy"))
        .thenReturn(Optional.of(route));
    when(routeDefinitions.findStop(route.id(), 1))
        .thenReturn(Optional.of(dynamicStop(1, platform, "DYNAMIC:OP:S:PPK:[1:1]")));
    SignNodeRegistry signNodeRegistry = mock(SignNodeRegistry.class);
    SignNodeRegistry.SignNodeInfo platformInfo =
        new SignNodeRegistry.SignNodeInfo(
            new SignNodeDefinition(platform, NodeType.STATION, Optional.empty(), Optional.empty()),
            worldId,
            "world",
            0,
            64,
            0);
    when(signNodeRegistry.findByNodeId(eq(platform), any())).thenReturn(Optional.of(platformInfo));

    OccupancyManager occupancyManager =
        mock(
            OccupancyManager.class,
            org.mockito.Mockito.withSettings()
                .extraInterfaces(
                    OccupancyQueueSupport.class,
                    org.fetarute
                        .fetaruteTCAddon
                        .dispatcher
                        .schedule
                        .occupancy
                        .OccupancyPreviewSupport
                        .class));
    when(occupancyManager.snapshotClaims()).thenReturn(List.of());
    org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyPreviewSupport preview =
        (org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyPreviewSupport)
            occupancyManager;
    when(preview.canEnterPreview(any()))
        .thenAnswer(
            invocation -> {
              OccupancyRequest request = invocation.getArgument(0);
              return new OccupancyDecision(false, request.now(), SignalAspect.STOP, List.of());
            });
    when(occupancyManager.canEnter(any()))
        .thenAnswer(
            invocation -> {
              OccupancyRequest request = invocation.getArgument(0);
              return new OccupancyDecision(false, request.now(), SignalAspect.STOP, List.of());
            });
    when(occupancyManager.acquire(any())).thenAnswer(allowProceed());
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

    assertFalse(
        service.checkDeparture(
            new FakeTrain(worldId, tags.properties(), false),
            new SignNodeDefinition(
                approach, NodeType.WAYPOINT, Optional.empty(), Optional.empty())));

    OccupancyQueueSupport queueSupport = (OccupancyQueueSupport) occupancyManager;
    verify(preview, atLeastOnce())
        .canEnterPreview(argThat(OccupancyRequest::hasMovementRequiredResources));
    verify(queueSupport, atLeastOnce())
        .touchQueues(argThat(OccupancyRequest::hasMovementRequiredResources));
    verify(queueSupport, never()).removeQueueEntries(anyString(), any());
    verify(tags.properties(), never()).setDestination(any());
  }

  @Test
  void depotSpawnAuthorityMaterializesImmediateDynamicPlatformBeforeGate() {
    NodeId depot = NodeId.of("OP:D:DEPOT:1");
    NodeId platformOne = NodeId.of("OP:S:PPK:1");
    NodeId platformTwo = NodeId.of("OP:S:PPK:2");
    RouteDefinition route =
        new RouteDefinition(
            RouteId.of("depot-immediate-dynamic"), List.of(depot, platformOne), Optional.empty());
    RailEdge firstApproach =
        new RailEdge(
            EdgeId.undirected(depot, platformOne),
            depot,
            platformOne,
            10,
            -1.0,
            true,
            Optional.empty());
    RailEdge secondApproach =
        new RailEdge(
            EdgeId.undirected(depot, platformTwo),
            depot,
            platformTwo,
            10,
            -1.0,
            true,
            Optional.empty());
    RailGraph graph =
        new SimpleRailGraph(
            Map.of(
                depot, new RailNodeTest(depot),
                platformOne, new RailNodeTest(platformOne),
                platformTwo, new RailNodeTest(platformTwo)),
            Map.of(
                firstApproach.id(), firstApproach,
                secondApproach.id(), secondApproach),
            Set.of());

    RouteDefinitionCache routeDefinitions = mock(RouteDefinitionCache.class);
    when(routeDefinitions.findStop(route.id(), 1))
        .thenReturn(Optional.of(dynamicStop(1, platformOne, "DYNAMIC:OP:S:PPK:[1:2]")));
    OccupancyManager occupancyManager = mockOccupancyManager();
    when(occupancyManager.snapshotClaims())
        .thenReturn(
            List.of(
                new OccupancyClaim(
                    OccupancyResource.forNode(platformOne),
                    "platform-incumbent",
                    Optional.empty(),
                    Instant.EPOCH,
                    Duration.ZERO,
                    Optional.empty())));
    when(occupancyManager.isNodeOccupied(platformTwo)).thenReturn(false);
    RuntimeDispatchService service =
        new RuntimeDispatchService(
            occupancyManager,
            mock(RailGraphService.class),
            routeDefinitions,
            new RouteProgressRegistry(),
            mock(SignNodeRegistry.class),
            mock(LayoverRegistry.class),
            new DwellRegistry(),
            mock(ConfigManager.class),
            null,
            new TrainConfigResolver(),
            null);

    Optional<List<NodeId>> prepared =
        service.prepareDepotSpawnDynamicAuthority(
            "spawn-dynamic-train", route, route.waypoints(), graph, Instant.EPOCH);

    assertEquals(Optional.of(List.of(depot, platformTwo)), prepared);
    assertEquals(
        List.of(depot, platformTwo),
        service.resolveEffectiveWaypointsForEvent("spawn-dynamic-train", route));

    service.cancelPreparedDepotSpawnDynamicAuthority("spawn-dynamic-train");

    assertEquals(
        route.waypoints(), service.resolveEffectiveWaypointsForEvent("spawn-dynamic-train", route));
    verify(occupancyManager, never()).canEnter(any());
    verify(occupancyManager, never()).acquire(any());
  }

  @Test
  void depotSpawnAuthorityStopsBeforeFutureUnmaterializedDynamicPlaceholder() {
    List<NodeId> nodes = new ArrayList<>();
    nodes.add(NodeId.of("OP:D:DEPOT:1"));
    for (int index = 1; index <= DynamicPlatformAllocator.ALLOCATION_EDGE_THRESHOLD + 1; index++) {
      nodes.add(NodeId.of("OP:W:MID:" + index));
    }
    NodeId dynamicPlaceholder = NodeId.of("OP:S:PPK:1");
    nodes.add(dynamicPlaceholder);
    RouteDefinition route =
        new RouteDefinition(
            RouteId.of("depot-future-dynamic"), List.copyOf(nodes), Optional.empty());
    int dynamicIndex = nodes.size() - 1;
    RailGraph graph = graphWithLinearPath(nodes, 10);
    RouteDefinitionCache routeDefinitions = mock(RouteDefinitionCache.class);
    when(routeDefinitions.findStop(route.id(), dynamicIndex))
        .thenReturn(
            Optional.of(dynamicStop(dynamicIndex, dynamicPlaceholder, "DYNAMIC:OP:S:PPK:[1:2]")));
    OccupancyManager occupancyManager = mockOccupancyManager();
    when(occupancyManager.snapshotClaims()).thenReturn(List.of());
    RuntimeDispatchService service =
        new RuntimeDispatchService(
            occupancyManager,
            mock(RailGraphService.class),
            routeDefinitions,
            new RouteProgressRegistry(),
            mock(SignNodeRegistry.class),
            mock(LayoverRegistry.class),
            new DwellRegistry(),
            mock(ConfigManager.class),
            null,
            new TrainConfigResolver(),
            null);

    List<NodeId> prepared =
        service
            .prepareDepotSpawnDynamicAuthority(
                "spawn-boundary-train", route, route.waypoints(), graph, Instant.EPOCH)
            .orElseThrow();

    assertEquals(nodes.subList(0, dynamicIndex), prepared);
    assertFalse(prepared.contains(dynamicPlaceholder));
    verify(occupancyManager, never()).canEnter(any());
    verify(occupancyManager, never()).acquire(any());
  }

  @Test
  void layoverDispatchDoesNotClaimTicketWhenNextDynamicPlatformsAreFull() {
    NodeId terminal = NodeId.of("OP:S:PPK:1");
    NodeId platformOne = NodeId.of("OP:S:NEXT:1");
    NodeId platformTwo = NodeId.of("OP:S:NEXT:2");
    UUID ticketRouteId = UUID.randomUUID();
    RouteDefinition route =
        new RouteDefinition(
            RouteId.of(ticketRouteId.toString()), List.of(terminal, platformOne), Optional.empty());
    RouteStop terminalStop = routeStop(0, terminal, RouteStopPassType.STOP);
    RouteStop dynamicStop = dynamicStop(1, platformOne, "DYNAMIC:OP:S:NEXT:[1:2]");
    TagStore tags = new TagStore("turning-train", "FTA_ROUTE_INDEX=0");
    UUID worldId = UUID.randomUUID();
    RailEdge firstApproach =
        new RailEdge(
            EdgeId.undirected(terminal, platformOne),
            terminal,
            platformOne,
            10,
            -1.0,
            true,
            Optional.empty());
    RailEdge secondApproach =
        new RailEdge(
            EdgeId.undirected(terminal, platformTwo),
            terminal,
            platformTwo,
            10,
            -1.0,
            true,
            Optional.empty());
    RailGraph graph =
        new SimpleRailGraph(
            Map.of(
                terminal, new RailNodeTest(terminal),
                platformOne, new RailNodeTest(platformOne),
                platformTwo, new RailNodeTest(platformTwo)),
            Map.of(
                firstApproach.id(), firstApproach,
                secondApproach.id(), secondApproach),
            Set.of());

    ConfigManager configManager = mock(ConfigManager.class);
    when(configManager.current()).thenReturn(testConfigView(20, 20.0));
    RailGraphService railGraphService = mock(RailGraphService.class);
    when(railGraphService.getSnapshot(worldId))
        .thenReturn(Optional.of(new RailGraphService.RailGraphSnapshot(graph, Instant.now())));
    RouteDefinitionCache routeDefinitions = mock(RouteDefinitionCache.class);
    when(routeDefinitions.findById(ticketRouteId)).thenReturn(Optional.of(route));
    when(routeDefinitions.listStops(route.id())).thenReturn(List.of(terminalStop, dynamicStop));
    when(routeDefinitions.findStop(route.id(), 0)).thenReturn(Optional.of(terminalStop));
    when(routeDefinitions.findStop(route.id(), 1)).thenReturn(Optional.of(dynamicStop));
    SignNodeRegistry signNodeRegistry = mock(SignNodeRegistry.class);
    SignNodeRegistry.SignNodeInfo platformInfo =
        new SignNodeRegistry.SignNodeInfo(
            new SignNodeDefinition(
                platformOne, NodeType.STATION, Optional.empty(), Optional.empty()),
            worldId,
            "world",
            0,
            64,
            0);
    when(signNodeRegistry.findByNodeId(any(), any())).thenReturn(Optional.of(platformInfo));

    OccupancyManager occupancyManager =
        mock(
            OccupancyManager.class,
            org.mockito.Mockito.withSettings()
                .extraInterfaces(
                    OccupancyQueueSupport.class,
                    org.fetarute
                        .fetaruteTCAddon
                        .dispatcher
                        .schedule
                        .occupancy
                        .OccupancyPreviewSupport
                        .class));
    when(occupancyManager.snapshotClaims())
        .thenReturn(
            List.of(
                new OccupancyClaim(
                    OccupancyResource.forNode(platformOne),
                    "occupant-one",
                    Optional.empty(),
                    Instant.EPOCH,
                    Duration.ZERO,
                    Optional.empty()),
                new OccupancyClaim(
                    OccupancyResource.forNode(platformTwo),
                    "occupant-two",
                    Optional.empty(),
                    Instant.EPOCH,
                    Duration.ZERO,
                    Optional.empty())));
    LayoverRegistry layoverRegistry = mock(LayoverRegistry.class);
    RuntimeDispatchService service =
        new RuntimeDispatchService(
            occupancyManager,
            railGraphService,
            routeDefinitions,
            new RouteProgressRegistry(),
            signNodeRegistry,
            layoverRegistry,
            new DwellRegistry(),
            configManager,
            null,
            new TrainConfigResolver(),
            null);
    LayoverRegistry.LayoverCandidate candidate =
        new LayoverRegistry.LayoverCandidate(
            "turning-train",
            TerminalKeyResolver.toTerminalKey(terminal),
            terminal,
            Instant.EPOCH,
            Map.of());
    ServiceTicket ticket =
        new ServiceTicket(
            "ticket-dynamic-full",
            Instant.EPOCH,
            ticketRouteId,
            candidate.terminalKey(),
            10,
            ServiceTicket.TicketMode.OPERATION);

    LayoverDispatchResult result =
        service.dispatchLayover(
            candidate, ticket, tags.properties(), new FakeTrain(worldId, tags.properties(), false));

    assertFalse(result.dispatched());
    assertEquals("dynamic-target-unavailable", result.reason());
    verify(layoverRegistry, never()).claimDispatch(anyString(), anyString(), anyString());
    verify(occupancyManager, never())
        .canEnter(argThat(OccupancyRequest::hasMovementRequiredResources));
    verify(occupancyManager, never())
        .acquire(argThat(OccupancyRequest::hasMovementRequiredResources));
    verify(tags.properties(), never()).setDestination(any());
  }

  @Test
  void handleSignalTickWritesDynamicDestinationAfterAcquireSucceeds() {
    NodeId current = NodeId.of("A");
    NodeId dynamic = NodeId.of("DYNAMIC");
    NodeId allocated = NodeId.of("OP:S:DEST:1");
    RouteDefinition route =
        new RouteDefinition(RouteId.of("r"), List.of(current, dynamic), Optional.empty());
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
                    graphWithSingleEdge(current, allocated, 10), Instant.now())));
    when(railGraphService.effectiveSpeedLimitBlocksPerSecond(any(), any(), any(), anyDouble()))
        .thenReturn(1000.0);

    RouteDefinitionCache routeDefinitions = mock(RouteDefinitionCache.class);
    when(routeDefinitions.findByCodes("op", "l1", "r1")).thenReturn(Optional.of(route));
    when(routeDefinitions.findStop(route.id(), 1))
        .thenReturn(Optional.of(dynamicStop(1, dynamic, "DYNAMIC:OP:S:DEST:[1:1]")));

    OccupancyManager occupancyManager = mockOccupancyManager();
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

    service.handleSignalTick(new FakeTrain(worldId, tags.properties(), false), false);

    verify(tags.properties()).setDestination(allocated.value());
  }

  @Test
  void handleSignalTickAuthorizesImmediateDynamicDestinationBeyondPreviewDistance()
      throws Exception {
    List<String> debugMessages = new ArrayList<>();
    NodeId current = NodeId.of("OP:S:ORIGIN:1");
    NodeId allocated = NodeId.of("OP:S:DEST:1");
    List<NodeId> physicalPath = new ArrayList<>();
    physicalPath.add(current);
    for (int index = 1; index <= DynamicPlatformAllocator.ALLOCATION_EDGE_THRESHOLD; index++) {
      physicalPath.add(NodeId.of("OP:W:ORIGIN:DEST:1:" + index));
    }
    physicalPath.add(allocated);
    RailGraph graph = graphWithConflictFreeLinearPath(physicalPath, 10);
    RouteDefinition route =
        new RouteDefinition(
            RouteId.of("immediate-dynamic-long-path"),
            List.of(current, allocated),
            Optional.empty());
    TagStore tags =
        new TagStore(
            "train-dynamic-long-path",
            "FTA_OPERATOR_CODE=op",
            "FTA_LINE_CODE=l1",
            "FTA_ROUTE_CODE=immediate-dynamic-long-path",
            "FTA_ROUTE_INDEX=0");
    UUID worldId = UUID.randomUUID();

    ConfigManager configManager = mock(ConfigManager.class);
    when(configManager.current()).thenReturn(testConfigView(20, 20.0));
    RailGraphService railGraphService = mock(RailGraphService.class);
    when(railGraphService.getSnapshot(worldId))
        .thenReturn(Optional.of(new RailGraphService.RailGraphSnapshot(graph, Instant.now())));
    when(railGraphService.effectiveSpeedLimitBlocksPerSecond(any(), any(), any(), anyDouble()))
        .thenReturn(1000.0);
    RouteDefinitionCache routeDefinitions = mock(RouteDefinitionCache.class);
    when(routeDefinitions.findByCodes("op", "l1", "immediate-dynamic-long-path"))
        .thenReturn(Optional.of(route));
    when(routeDefinitions.findStop(route.id(), 1))
        .thenReturn(Optional.of(dynamicStop(1, allocated, "DYNAMIC:OP:S:DEST:[1:1]")));
    OccupancyManager occupancyManager = mockOccupancyManager();
    when(occupancyManager.canEnter(any())).thenAnswer(allowProceed());
    when(occupancyManager.acquire(any())).thenAnswer(allowProceed());
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
            debugMessages::add);
    FakeTrain train = new FakeTrain(worldId, tags.properties(), false);

    service.handleSignalTick(train, false);

    assertTrue(
        debugMessages.stream()
            .anyMatch(
                message ->
                    message.contains("DYNAMIC 紧邻目标越过预选距离") && message.contains("edgeDistance=9")),
        debugMessages::toString);
    assertTrue(
        debugMessages.stream().anyMatch(message -> message.contains("DYNAMIC effective node 写入")),
        debugMessages::toString);
    verify(occupancyManager, atLeastOnce())
        .acquire(argThat(OccupancyRequest::hasMovementRequiredResources));
    assertEquals(1, movementTokenCount(service));
    assertEquals(
        SignalAspect.PROCEED, registry.get("train-dynamic-long-path").orElseThrow().lastSignal());
    verify(tags.properties()).clearDestinationRoute();
    verify(tags.properties()).setDestination(allocated.value());
    assertEquals(1, train.launchCalls);
    assertEquals(0, train.hardStopCalls);
  }

  @Test
  void departureAuthorityStopsBeforeUnmaterializedFutureDynamicPlaceholder() {
    List<String> debugMessages = new ArrayList<>();
    NodeId current = NodeId.of("OP:S:START:1");
    NodeId ordinary = NodeId.of("OP:W:MID:1:001");
    NodeId dynamicPlaceholder = NodeId.of("OP:S:PPK:1");
    RouteDefinition route =
        new RouteDefinition(
            RouteId.of("future-dynamic-boundary"),
            List.of(current, ordinary, dynamicPlaceholder),
            Optional.empty());
    TagStore tags =
        new TagStore(
            "boundary-train",
            "FTA_OPERATOR_CODE=op",
            "FTA_LINE_CODE=l1",
            "FTA_ROUTE_CODE=future-dynamic-boundary",
            "FTA_ROUTE_INDEX=0");
    UUID worldId = UUID.randomUUID();
    RailGraph graph = graphWithConflictFreeLinearPath(route.waypoints(), 10);

    ConfigManager configManager = mock(ConfigManager.class);
    when(configManager.current()).thenReturn(testConfigView(20, 20.0));
    RailGraphService railGraphService = mock(RailGraphService.class);
    when(railGraphService.getSnapshot(worldId))
        .thenReturn(Optional.of(new RailGraphService.RailGraphSnapshot(graph, Instant.now())));
    RouteDefinitionCache routeDefinitions = mock(RouteDefinitionCache.class);
    when(routeDefinitions.findByCodes("op", "l1", "future-dynamic-boundary"))
        .thenReturn(Optional.of(route));
    when(routeDefinitions.findStop(route.id(), 2))
        .thenReturn(Optional.of(dynamicStop(2, dynamicPlaceholder, "DYNAMIC:OP:S:PPK:[1:2]")));
    OccupancyManager occupancyManager = mockOccupancyManager();
    when(occupancyManager.snapshotClaims()).thenReturn(List.of());
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
            debugMessages::add);

    assertTrue(
        service.checkDeparture(
            new FakeTrain(worldId, tags.properties(), false),
            new SignNodeDefinition(current, NodeType.STATION, Optional.empty(), Optional.empty())),
        debugMessages::toString);

    ArgumentCaptor<OccupancyRequest> requestCaptor =
        ArgumentCaptor.forClass(OccupancyRequest.class);
    verify(occupancyManager, atLeastOnce()).acquire(requestCaptor.capture());
    List<OccupancyRequest> movementRequests =
        requestCaptor.getAllValues().stream()
            .filter(OccupancyRequest::hasMovementRequiredResources)
            .toList();
    assertFalse(movementRequests.isEmpty());
    assertTrue(
        movementRequests.stream()
            .allMatch(
                request ->
                    !request
                        .resourceList()
                        .contains(OccupancyResource.forNode(dynamicPlaceholder))));
    assertTrue(
        movementRequests.stream()
            .anyMatch(
                request -> request.resourceList().contains(OccupancyResource.forNode(ordinary))));
  }

  @Test
  void signalAuthorityStopsAtFirstMaterializedDynamicTarget() {
    NodeId current = NodeId.of("OP:W:APPROACH:1:001");
    NodeId firstPlatform = NodeId.of("OP:S:FIRST:1");
    NodeId secondPlaceholder = NodeId.of("OP:S:SECOND:1");
    RouteDefinition route =
        new RouteDefinition(
            RouteId.of("consecutive-dynamic-boundary"),
            List.of(current, firstPlatform, secondPlaceholder),
            Optional.empty());
    TagStore tags =
        new TagStore(
            "dynamic-boundary-train",
            "FTA_OPERATOR_CODE=op",
            "FTA_LINE_CODE=l1",
            "FTA_ROUTE_CODE=consecutive-dynamic-boundary",
            "FTA_ROUTE_INDEX=0");
    UUID worldId = UUID.randomUUID();
    RailGraph graph = graphWithConflictFreeLinearPath(route.waypoints(), 10);

    ConfigManager configManager = mock(ConfigManager.class);
    when(configManager.current()).thenReturn(testConfigView(20, 20.0));
    RailGraphService railGraphService = mock(RailGraphService.class);
    when(railGraphService.getSnapshot(worldId))
        .thenReturn(Optional.of(new RailGraphService.RailGraphSnapshot(graph, Instant.now())));
    when(railGraphService.effectiveSpeedLimitBlocksPerSecond(any(), any(), any(), anyDouble()))
        .thenReturn(1000.0);
    RouteDefinitionCache routeDefinitions = mock(RouteDefinitionCache.class);
    when(routeDefinitions.findByCodes("op", "l1", "consecutive-dynamic-boundary"))
        .thenReturn(Optional.of(route));
    when(routeDefinitions.findStop(route.id(), 1))
        .thenReturn(Optional.of(dynamicStop(1, firstPlatform, "DYNAMIC:OP:S:FIRST:[1:1]")));
    when(routeDefinitions.findStop(route.id(), 2))
        .thenReturn(Optional.of(dynamicStop(2, secondPlaceholder, "DYNAMIC:OP:S:SECOND:[1:1]")));
    OccupancyManager occupancyManager = mockOccupancyManager();
    when(occupancyManager.snapshotClaims()).thenReturn(List.of());
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

    service.handleSignalTick(new FakeTrain(worldId, tags.properties(), false), false);

    ArgumentCaptor<OccupancyRequest> requestCaptor =
        ArgumentCaptor.forClass(OccupancyRequest.class);
    verify(occupancyManager, atLeastOnce()).acquire(requestCaptor.capture());
    List<OccupancyRequest> movementRequests =
        requestCaptor.getAllValues().stream()
            .filter(OccupancyRequest::hasMovementRequiredResources)
            .toList();
    assertFalse(movementRequests.isEmpty());
    assertTrue(
        movementRequests.stream()
            .allMatch(
                request ->
                    !request
                        .resourceList()
                        .contains(OccupancyResource.forNode(secondPlaceholder))));
    verify(tags.properties()).setDestination(firstPlatform.value());
  }

  @Test
  void unresolvedDynamicAfterSwitcherCannotExpandAtomicAuthority() {
    NodeId current = NodeId.of("OP:S:START:1");
    NodeId switcher = NodeId.of("switcher:dynamic-boundary");
    NodeId dynamicPlaceholder = NodeId.of("OP:S:PPK:1");
    RouteDefinition route =
        new RouteDefinition(
            RouteId.of("switcher-dynamic-boundary"),
            List.of(current, switcher, dynamicPlaceholder),
            Optional.empty());
    TagStore tags =
        new TagStore(
            "switcher-boundary-train",
            "FTA_OPERATOR_CODE=op",
            "FTA_LINE_CODE=l1",
            "FTA_ROUTE_CODE=switcher-dynamic-boundary",
            "FTA_ROUTE_INDEX=0");
    UUID worldId = UUID.randomUUID();
    RailEdge entry =
        new RailEdge(
            EdgeId.undirected(current, switcher),
            current,
            switcher,
            10,
            -1.0,
            true,
            Optional.empty());
    RailEdge exit =
        new RailEdge(
            EdgeId.undirected(switcher, dynamicPlaceholder),
            switcher,
            dynamicPlaceholder,
            10,
            -1.0,
            true,
            Optional.empty());
    RailGraph graph =
        new SimpleRailGraph(
            Map.of(
                current, new RailNodeTest(current, NodeType.STATION, Optional.empty()),
                switcher, new RailNodeTest(switcher, NodeType.SWITCHER, Optional.empty()),
                dynamicPlaceholder,
                    new RailNodeTest(dynamicPlaceholder, NodeType.STATION, Optional.empty())),
            Map.of(entry.id(), entry, exit.id(), exit),
            Set.of());

    ConfigManager configManager = mock(ConfigManager.class);
    when(configManager.current()).thenReturn(testConfigView(20, 20.0));
    RailGraphService railGraphService = mock(RailGraphService.class);
    when(railGraphService.getSnapshot(worldId))
        .thenReturn(Optional.of(new RailGraphService.RailGraphSnapshot(graph, Instant.now())));
    RouteDefinitionCache routeDefinitions = mock(RouteDefinitionCache.class);
    when(routeDefinitions.findByCodes("op", "l1", "switcher-dynamic-boundary"))
        .thenReturn(Optional.of(route));
    when(routeDefinitions.findStop(route.id(), 2))
        .thenReturn(Optional.of(dynamicStop(2, dynamicPlaceholder, "DYNAMIC:OP:S:PPK:[1:2]")));
    OccupancyManager occupancyManager = mockOccupancyManager();
    when(occupancyManager.snapshotClaims()).thenReturn(List.of());
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

    assertFalse(
        service.checkDeparture(
            new FakeTrain(worldId, tags.properties(), false),
            new SignNodeDefinition(current, NodeType.STATION, Optional.empty(), Optional.empty())));

    verify(occupancyManager, never())
        .canEnter(argThat(OccupancyRequest::hasMovementRequiredResources));
    verify(occupancyManager, never())
        .acquire(argThat(OccupancyRequest::hasMovementRequiredResources));
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

    OccupancyManager occupancyManager = mockOccupancyManager();
    when(occupancyManager.canEnter(any())).thenAnswer(allowProceed());
    when(occupancyManager.acquire(any())).thenAnswer(allowProceed());

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
  void shouldSkipDuplicateAbnormalCleanupWithinDedupWindow() throws Exception {
    RuntimeDispatchService service = createMinimalService();
    java.lang.reflect.Method method =
        RuntimeDispatchService.class.getDeclaredMethod(
            "shouldSkipDuplicateAbnormalCleanup", String.class);
    method.setAccessible(true);

    boolean first = (boolean) method.invoke(service, "train-1");
    boolean second = (boolean) method.invoke(service, "train-1");

    assertFalse(first);
    assertTrue(second);
  }

  @Test
  void handleRenameIfNeededKeepsTaggedCanonicalNameForSplitAlias() {
    RuntimeDispatchService service = createMinimalService();
    TagStore tags = new TagStore("train-1~a", "FTA_TRAIN_NAME=train-1");

    assertEquals("train-1", service.resolveTrackedTrainName(tags.properties()).orElseThrow());
    assertTrue(service.resolveManagedTrainName(tags.properties()).isEmpty());
    assertEquals("train-1", service.handleRenameIfNeeded(tags.properties()));
    assertEquals(
        "train-1",
        TrainTagHelper.readTagValue(tags.properties(), RouteProgressRegistry.TAG_TRAIN_NAME)
            .orElseThrow());
  }

  @Test
  void buildAbnormalCleanupWarningIncludesReasonLocationsAndProgress() {
    RuntimeDispatchService.AbnormalProgressSnapshot progress =
        new RuntimeDispatchService.AbnormalProgressSnapshot(
            "route-1", 4, "NEXT", "LAST", SignalAspect.STOP.name());
    RuntimeDispatchService.AbnormalGroupSnapshot snapshot =
        new RuntimeDispatchService.AbnormalGroupSnapshot(
            "unexpected-split-source",
            "removedMember=abc@world(1.00,2.00,3.00) sourceTrain=train-1",
            "train-1~a",
            "train-1",
            "train-1",
            "train-1",
            true,
            3,
            "world",
            "world(1.00,2.00,3.00)",
            "world(0.50,2.00,2.50)",
            "world(0.00,2.00,2.00)",
            progress);

    String message = RuntimeDispatchService.buildAbnormalCleanupWarning(snapshot);

    assertTrue(message.contains("reason=unexpected-split-source"));
    assertTrue(message.contains("rawTrain=train-1~a"));
    assertTrue(message.contains("logicalTrain=train-1"));
    assertTrue(message.contains("head=world(1.00,2.00,3.00)"));
    assertTrue(message.contains("route=route-1"));
    assertTrue(message.contains("index=4"));
    assertTrue(message.contains("signal=STOP"));
  }

  @Test
  void abnormalCleanupPolicySkipsUnmanagedUnexpectedSplit() {
    RuntimeDispatchService.AbnormalCleanupPolicy policy =
        RuntimeDispatchService.resolveAbnormalCleanupPolicy(false, "unexpected-split-source");

    assertFalse(policy.process());
    assertFalse(policy.cleanupRuntimeState());
    assertFalse(policy.destroyEntities());
  }

  @Test
  void abnormalCleanupPolicyDestroysUnmanagedDerailedWithoutRuntimeCleanup() {
    RuntimeDispatchService.AbnormalCleanupPolicy policy =
        RuntimeDispatchService.resolveAbnormalCleanupPolicy(false, "status-derailed");

    assertTrue(policy.process());
    assertFalse(policy.cleanupRuntimeState());
    assertTrue(policy.destroyEntities());
  }

  @Test
  void abnormalCleanupPolicyCleansFtaRuntimeTaggedTrain() {
    RuntimeDispatchService.AbnormalCleanupPolicy policy =
        RuntimeDispatchService.resolveAbnormalCleanupPolicy(true, "unexpected-split-source");

    assertTrue(policy.process());
    assertTrue(policy.cleanupRuntimeState());
    assertTrue(policy.destroyEntities());
  }

  @Test
  void hasFtaRuntimeTagRecognizesTrainNameOnlySplitRemainder() {
    RuntimeDispatchService service = createMinimalService();
    TagStore tags = new TagStore("train-main~a", "FTA_TRAIN_NAME=train-main");

    assertTrue(service.hasFtaRuntimeTag(tags.properties()));
    assertFalse(service.hasCompleteFtaRouteIdentity(tags.properties()));
  }

  @Test
  void completeFtaRouteIdentityRequiresRouteLevelEvidence() {
    RuntimeDispatchService service = createMinimalService();
    TagStore tags =
        new TagStore(
            "train-main",
            "FTA_TRAIN_NAME=train-main",
            "FTA_OPERATOR_CODE=op",
            "FTA_LINE_CODE=line",
            "FTA_ROUTE_CODE=route");

    assertTrue(service.hasCompleteFtaRouteIdentity(tags.properties()));
  }

  @Test
  void handleSignalTickBlocksDeadlockReleaseWhenHardOccupancyBlockersExist() {
    NodeId current = NodeId.of("A");
    NodeId next = NodeId.of("B");
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

    OccupancyManager occupancyManager = mockOccupancyManager();
    when(occupancyManager.getClaim(any())).thenReturn(Optional.empty());
    when(occupancyManager.snapshotClaims()).thenReturn(List.of());
    when(occupancyManager.acquire(any())).thenAnswer(allowProceed());
    when(occupancyManager.canEnter(any()))
        .thenAnswer(
            invocation -> {
              OccupancyRequest request = invocation.getArgument(0);
              OccupancyClaim blocker =
                  new OccupancyClaim(
                      OccupancyResource.forNode(next),
                      "front-train",
                      Optional.of(route.id()),
                      request.now(),
                      Duration.ZERO,
                      Optional.empty());
              return new OccupancyDecision(
                  true, request.now(), SignalAspect.PROCEED, List.of(blocker));
            });

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

    assertEquals(0, train.launchCalls);
    assertTrue(train.stopCalls >= 1);
    SignalAspect aspect = service.snapshotProgressEntries().get("train-1").lastSignal();
    assertEquals(SignalAspect.STOP, aspect);
    verify(tags.properties(), never()).setDestination(any());
    assertTrue(
        service.recentBlockerTrains("train-1", Duration.ofSeconds(30)).contains("front-train"));
  }

  @Test
  void handleSignalTickAllowsConflictOnlyBlockerWhileRecordingSnapshot() {
    NodeId current = NodeId.of("A");
    NodeId next = NodeId.of("B");
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

    OccupancyManager occupancyManager = mockOccupancyManager();
    when(occupancyManager.getClaim(any())).thenReturn(Optional.empty());
    when(occupancyManager.snapshotClaims()).thenReturn(List.of());
    Answer<OccupancyDecision> allowWithConflictBlocker =
        invocation -> {
          OccupancyRequest request = invocation.getArgument(0);
          OccupancyClaim blocker =
              new OccupancyClaim(
                  OccupancyResource.forConflict("switcher:test"),
                  "front-train",
                  Optional.of(route.id()),
                  request.now(),
                  Duration.ZERO,
                  Optional.empty());
          return new OccupancyDecision(true, request.now(), SignalAspect.PROCEED, List.of(blocker));
        };
    when(occupancyManager.canEnter(any())).thenAnswer(allowWithConflictBlocker);
    when(occupancyManager.acquire(any())).thenAnswer(allowWithConflictBlocker);

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

    assertTrue(
        service.recentBlockerTrains("train-1", Duration.ofSeconds(30)).contains("front-train"));
    assertEquals(
        SignalAspect.PROCEED, service.snapshotProgressEntries().get("train-1").lastSignal());
  }

  @Test
  void handleSignalTickBlockerSnapshotSuppressesHealthRecoveryWhileFresh() {
    RuntimeDispatchService service = createServiceWithHardNodeBlocker();
    UUID worldId = UUID.randomUUID();
    TagStore tags =
        new TagStore(
            "train-1",
            "FTA_OPERATOR_CODE=op",
            "FTA_LINE_CODE=l1",
            "FTA_ROUTE_CODE=r1",
            "FTA_ROUTE_INDEX=0");

    FakeTrain train = new FakeTrain(worldId, tags.properties(), false);
    service.handleSignalTick(train, false);
    assertTrue(
        service.recentBlockerTrains("train-1", Duration.ofSeconds(30)).contains("front-train"));

    RuntimeDispatchService dispatchFacade = mock(RuntimeDispatchService.class);
    when(dispatchFacade.smartDispatcherMode()).thenReturn(SmartDispatcherMode.ENFORCE);
    stubSmartRecoveryNoCandidate(dispatchFacade);
    when(dispatchFacade.getTrainState("train-1"))
        .thenReturn(
            Optional.of(
                new RuntimeDispatchService.TrainRuntimeState(
                    "train-1", 0, SignalAspect.STOP, 0.0)));
    when(dispatchFacade.recentBlockerTrains(eq("train-1"), any()))
        .thenAnswer(
            invocation -> service.recentBlockerTrains("train-1", invocation.getArgument(1)));

    DwellRegistry dwellRegistry = mock(DwellRegistry.class);
    when(dwellRegistry.remainingSeconds("train-1")).thenReturn(Optional.empty());

    TrainHealthMonitor monitor =
        new TrainHealthMonitor(dispatchFacade, dwellRegistry, new HealthAlertBus(), msg -> {});
    monitor.setProgressStuckThreshold(Duration.ofSeconds(10));
    monitor.setProgressStopGraceThreshold(Duration.ofSeconds(20));

    Instant t0 = Instant.now();
    monitor.check(Set.of("train-1"), t0);
    TrainHealthMonitor.CheckResult result = monitor.check(Set.of("train-1"), t0.plusSeconds(15));

    assertEquals(0, result.progressStuckCount());
    verify(dispatchFacade, never()).refreshSignalByName("train-1");
    verify(dispatchFacade, never()).reissueDestinationByName("train-1");
    verify(dispatchFacade, never()).forceRelaunchByName("train-1");
  }

  @Test
  void staleBlockerSnapshotAllowsHealthRecoveryAfterSignalTickChain() throws Exception {
    RuntimeDispatchService service = createServiceWithHardNodeBlocker();
    UUID worldId = UUID.randomUUID();
    TagStore tags =
        new TagStore(
            "train-1",
            "FTA_OPERATOR_CODE=op",
            "FTA_LINE_CODE=l1",
            "FTA_ROUTE_CODE=r1",
            "FTA_ROUTE_INDEX=0");

    FakeTrain train = new FakeTrain(worldId, tags.properties(), false);
    service.handleSignalTick(train, false);
    backdateBlockerSnapshot(service, "train-1", Instant.now().minusSeconds(120));

    RuntimeDispatchService dispatchFacade = mock(RuntimeDispatchService.class);
    when(dispatchFacade.smartDispatcherMode()).thenReturn(SmartDispatcherMode.ENFORCE);
    stubSmartRecoveryNoCandidate(dispatchFacade);
    when(dispatchFacade.getTrainState("train-1"))
        .thenReturn(
            Optional.of(
                new RuntimeDispatchService.TrainRuntimeState(
                    "train-1", 0, SignalAspect.STOP, 0.0)));
    when(dispatchFacade.recentBlockerTrains(eq("train-1"), any()))
        .thenAnswer(
            invocation -> service.recentBlockerTrains("train-1", invocation.getArgument(1)));

    DwellRegistry dwellRegistry = mock(DwellRegistry.class);
    when(dwellRegistry.remainingSeconds("train-1")).thenReturn(Optional.empty());

    TrainHealthMonitor monitor =
        new TrainHealthMonitor(dispatchFacade, dwellRegistry, new HealthAlertBus(), msg -> {});
    monitor.setProgressStuckThreshold(Duration.ofSeconds(10));
    monitor.setProgressStopGraceThreshold(Duration.ofSeconds(20));
    monitor.setBlockerSnapshotMaxAge(Duration.ofSeconds(30));

    Instant t0 = Instant.now();
    monitor.check(Set.of("train-1"), t0);
    TrainHealthMonitor.CheckResult result = monitor.check(Set.of("train-1"), t0.plusSeconds(25));

    assertEquals(1, result.progressStuckCount());
    verify(dispatchFacade).refreshSignalByName("train-1");
    verify(dispatchFacade, never()).reissueDestinationByName("train-1");
    verify(dispatchFacade, never()).forceRelaunchByName("train-1");
  }

  @Test
  void handleSignalTickHoldsWhenAuthorityEndTooCloseEvenWithoutBlocker() {
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

    OccupancyManager occupancyManager = mockOccupancyManager();
    when(occupancyManager.canEnter(any())).thenAnswer(allowProceed());
    when(occupancyManager.acquire(any())).thenAnswer(allowProceed());

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

    FakeTrain train = new FakeTrain(worldId, tags.properties(), true, 0.5);
    service.handleSignalTick(train, true);

    SignalAspect aspect = registry.get("train-1").orElseThrow().lastSignal();
    assertEquals(
        SignalAspect.STOP,
        aspect,
        () -> service.getDiagnostics("train-1").map(Object::toString).orElse("no diagnostics"));
    assertEquals(0, train.hardStopCalls);
    verify(tags.properties(), never()).clearDestinationRoute();
    verify(tags.properties(), never()).clearDestination();
  }

  @Test
  void authorityWindowArtificialEndDoesNotDowngradeToCaution() {
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

    OccupancyManager occupancyManager = mockOccupancyManager();
    when(occupancyManager.canEnter(any())).thenAnswer(allowProceed());
    when(occupancyManager.acquire(any())).thenAnswer(allowProceed());

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

    FakeTrain train = new FakeTrain(worldId, tags.properties(), true, 0.0);
    service.handleSignalTick(train, true);

    assertEquals(SignalAspect.PROCEED, registry.get("train-1").orElseThrow().lastSignal());
    assertEquals(0, train.hardStopCalls);
  }

  @Test
  void hardAuthorityWindowExtendsToBrakingDistanceWhenTrainIsMoving() {
    NodeId a = NodeId.of("A");
    NodeId b = NodeId.of("B");
    NodeId c = NodeId.of("C");
    NodeId d = NodeId.of("D");
    RouteDefinition route =
        new RouteDefinition(RouteId.of("r"), List.of(a, b, c, d), Optional.empty());
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
                    graphWithConflictFreeLinearPath(List.of(a, b, c, d), 10), Instant.now())));
    when(railGraphService.effectiveSpeedLimitBlocksPerSecond(any(), any(), any(), anyDouble()))
        .thenReturn(1000.0);

    RouteDefinitionCache routeDefinitions = mock(RouteDefinitionCache.class);
    when(routeDefinitions.findByCodes("op", "l1", "r1")).thenReturn(Optional.of(route));

    OccupancyManager occupancyManager = mockOccupancyManager();
    when(occupancyManager.canEnter(any())).thenAnswer(allowProceed());
    when(occupancyManager.acquire(any())).thenAnswer(allowProceed());

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

    FakeTrain train = new FakeTrain(worldId, tags.properties(), true, 0.5);
    service.handleSignalTick(train, true);

    OccupancyResource secondEdge = OccupancyResource.forEdge(EdgeId.undirected(b, c));
    ArgumentCaptor<OccupancyRequest> requestCaptor =
        ArgumentCaptor.forClass(OccupancyRequest.class);
    verify(occupancyManager, atLeastOnce()).acquire(requestCaptor.capture());
    assertTrue(
        requestCaptor.getAllValues().stream()
            .anyMatch(request -> request.resourceList().contains(secondEdge)),
        () -> "hard authority requests=" + requestCaptor.getAllValues());
  }

  @Test
  void recoverableSmartHoldDoesNotInvalidateTokenClearDestinationOrSelfSchedule() {
    List<String> debugMessages = new ArrayList<>();
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
    java.util.concurrent.atomic.AtomicReference<String> destination =
        new java.util.concurrent.atomic.AtomicReference<>("NEXT");
    when(tags.properties().getDestination()).thenAnswer(inv -> destination.get());
    org.mockito.Mockito.doAnswer(
            inv -> {
              destination.set("");
              return null;
            })
        .when(tags.properties())
        .clearDestination();
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

    OccupancyManager occupancyManager =
        mock(
            OccupancyManager.class,
            org.mockito.Mockito.withSettings()
                .extraInterfaces(
                    OccupancyAdvisoryPreviewSupport.class, AuthorityHandoffSupport.class));
    when(((AuthorityHandoffSupport) occupancyManager).holdsHardAuthority(any())).thenReturn(true);
    when(occupancyManager.canEnter(any())).thenAnswer(allowProceed());
    when(occupancyManager.acquire(any())).thenAnswer(allowProceed());
    OccupancyAdvisoryPreviewSupport advisorySupport =
        (OccupancyAdvisoryPreviewSupport) occupancyManager;
    when(advisorySupport.scanAdvisoryRisks(any()))
        .thenAnswer(
            inv -> {
              OccupancyRequest request = inv.getArgument(0);
              OccupancyResource resource = OccupancyResource.forNode(a);
              OccupancyClaim claim =
                  new OccupancyClaim(
                      resource,
                      "front-train",
                      Optional.of(route.id()),
                      request.now(),
                      Duration.ZERO,
                      Optional.empty());
              return List.of(
                  new AdvisoryRisk(
                      resource, claim, AdvisoryRiskSource.OCCUPIED_NODE, "ahead-occupied"));
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
            debugMessages::add);
    List<String> reevaluationRequests = new ArrayList<>();
    service.setSignalReevaluationRequester(reevaluationRequests::add);

    FakeTrain train = new FakeTrain(worldId, tags.properties(), true, 0.0);
    service.handleSignalTick(train, true);

    assertEquals(SignalAspect.STOP, registry.get("train-1").orElseThrow().lastSignal());
    assertEquals(0, train.hardStopCalls);
    verify(tags.properties(), never()).clearDestinationRoute();
    verify(tags.properties(), never()).clearDestination();
    ControlDiagnostics diagnostics = service.getDiagnostics("train-1").orElseThrow();
    assertTrue(diagnostics.destinationPresentWhileBlocked());
    assertEquals("NEXT", diagnostics.retainedDestination());
    assertEquals("SMART_DISPATCH_RECOVERABLE_HOLD", diagnostics.blockedReason());
    RuntimeDispatchService.SmartRecoveryInput recovery =
        service.smartRecoveryInput("train-1", Duration.ZERO, SignalAspect.STOP);
    assertTrue(recovery.destinationPresent());
    assertEquals(SignalComputationTrace.TokenState.PENDING, recovery.movementTokenState());
    assertFalse(recovery.movementInhibited());
    assertTrue(
        debugMessages.stream()
            .anyMatch(message -> message.contains("SMART_DISPATCH_RECOVERABLE_HOLD")));
    assertFalse(
        debugMessages.stream()
            .anyMatch(message -> message.contains("SMART_AUTHORITY_WINDOW_EXCEEDED_REAL")));
    assertTrue(
        reevaluationRequests.isEmpty(), "静态 recoverable STOP 只能等待事实变化或周期巡检，不能自行排入下一 tick 授权链");
  }

  @Test
  void recoverableNoActionSwitcherRiskDoesNotApplyStopWhenHardAuthorityAllowed() {
    List<String> debugMessages = new ArrayList<>();
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
    when(configManager.current()).thenReturn(testConfigView(20, 20.0));

    RailGraphService railGraphService = mock(RailGraphService.class);
    when(railGraphService.getSnapshot(worldId))
        .thenReturn(
            Optional.of(
                new RailGraphService.RailGraphSnapshot(
                    graphWithTwoEdges(a, b, c, 10, 10), Instant.now())));
    when(railGraphService.effectiveSpeedLimitBlocksPerSecond(any(), any(), any(), anyDouble()))
        .thenReturn(20.0);

    RouteDefinitionCache routeDefinitions = mock(RouteDefinitionCache.class);
    when(routeDefinitions.findByCodes("op", "l1", "r1")).thenReturn(Optional.of(route));

    OccupancyManager occupancyManager =
        mock(
            OccupancyManager.class,
            org.mockito.Mockito.withSettings()
                .extraInterfaces(
                    OccupancyAdvisoryPreviewSupport.class, AuthorityHandoffSupport.class));
    when(((AuthorityHandoffSupport) occupancyManager).holdsHardAuthority(any())).thenReturn(true);
    when(occupancyManager.snapshotClaims()).thenReturn(List.of());
    when(occupancyManager.canEnter(any())).thenAnswer(allowProceed());
    when(occupancyManager.acquire(any())).thenAnswer(allowProceed());
    OccupancyAdvisoryPreviewSupport advisorySupport =
        (OccupancyAdvisoryPreviewSupport) occupancyManager;
    when(advisorySupport.scanAdvisoryRisks(any()))
        .thenAnswer(
            inv -> {
              OccupancyRequest request = inv.getArgument(0);
              OccupancyResource resource = OccupancyResource.forConflict("switcher:SW");
              OccupancyClaim claim =
                  new OccupancyClaim(
                      resource,
                      "front-train",
                      Optional.of(route.id()),
                      request.now(),
                      Duration.ZERO,
                      Optional.empty());
              return List.of(
                  new AdvisoryRisk(
                      resource,
                      claim,
                      AdvisoryRiskSource.ACTIVE_SWITCHER_CONFLICT,
                      "switcher-risk"));
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
            debugMessages::add);

    FakeTrain train = new FakeTrain(worldId, tags.properties(), false, 0.0);
    service.handleSignalTick(train, true);

    assertEquals(
        SignalAspect.PROCEED_WITH_CAUTION,
        registry.get("train-1").orElseThrow().lastSignal(),
        debugMessages.toString());
    assertEquals(0, train.hardStopCalls);
    assertFalse(
        service
            .getDiagnostics("train-1")
            .map(ControlDiagnostics::destinationPresentWhileBlocked)
            .orElse(false));
    assertFalse(
        debugMessages.stream()
            .anyMatch(message -> message.contains("SMART_DISPATCH_RECOVERABLE_HOLD")));
    verify(tags.properties(), never()).clearDestination();
  }

  @Test
  void forwardRiskSourceTreatsProvenSameDirectionSwitcherAsTraceOnly() throws Exception {
    OccupancyManager occupancyManager = mockOccupancyManager();
    RuntimeDispatchService service = createMinimalService(occupancyManager, new ArrayList<>());
    OccupancyResource section = OccupancyResource.forConflict("single:test:A~B");
    OccupancyResource switcher = OccupancyResource.forConflict("switcher:SWITCHER:test");
    OccupancyRequest request =
        new OccupancyRequest(
            "follower",
            Optional.empty(),
            Instant.parse("2026-01-01T00:00:00Z"),
            List.of(section, switcher),
            Map.of(section.key(), CorridorDirection.A_TO_B),
            Map.of(section.key(), 0, switcher.key(), 1),
            0);
    OccupancyClaim blocker =
        new OccupancyClaim(
            switcher,
            "leader",
            Optional.empty(),
            request.now(),
            Duration.ZERO,
            Optional.of(CorridorDirection.A_TO_B));
    when(occupancyManager.isProvenSameDirectionFollower(request, switcher, "leader", false))
        .thenReturn(true);

    RiskSource source = invokeRiskSourceForBlocker(service, request, blocker);

    assertEquals(RiskSource.SAME_DIRECTION_FOLLOW, source);
  }

  @Test
  void forwardRiskSourceRequiresSwitcherMovementSignatureEvenWhenRouteProgressMatches()
      throws Exception {
    SimpleOccupancyManager occupancyManager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    RouteProgressRegistry registry = new RouteProgressRegistry();
    UUID routeUuid = UUID.randomUUID();
    RouteDefinition route =
        new RouteDefinition(
            RouteId.of("same-route"),
            List.of(NodeId.of("A"), NodeId.of("B"), NodeId.of("C")),
            Optional.empty());
    registry.initFromTags(
        "follower",
        new TagStore("follower", "FTA_ROUTE_ID=" + routeUuid, "FTA_ROUTE_INDEX=0").properties(),
        route);
    registry.initFromTags(
        "leader",
        new TagStore("leader", "FTA_ROUTE_ID=" + routeUuid, "FTA_ROUTE_INDEX=1").properties(),
        route);
    RuntimeDispatchService service =
        createMinimalService(
            occupancyManager,
            routeDefinitionCacheWith(route, routeUuid),
            registry,
            new ArrayList<>());
    OccupancyResource switcher = OccupancyResource.forConflict("switcher:SWITCHER:test");
    OccupancyRequest request =
        new OccupancyRequest(
            "follower",
            Optional.empty(),
            Instant.parse("2026-01-01T00:00:00Z"),
            List.of(switcher),
            Map.of());
    OccupancyClaim blocker =
        new OccupancyClaim(
            switcher, "leader", Optional.empty(), request.now(), Duration.ZERO, Optional.empty());

    RiskSource source = invokeRiskSourceForBlocker(service, request, blocker);

    assertEquals(RiskSource.SWITCHER_NOT_VERIFIED, source);
  }

  @Test
  void provenSameRouteLeaderRequiresSameRouteAheadProgressAndNonTurnbackRoute() throws Exception {
    RouteDefinition route =
        new RouteDefinition(
            RouteId.of("same-route"),
            List.of(NodeId.of("A"), NodeId.of("B"), NodeId.of("C"), NodeId.of("D")),
            Optional.empty());
    RouteDefinition otherRoute =
        new RouteDefinition(
            RouteId.of("other-route"),
            List.of(NodeId.of("A"), NodeId.of("X"), NodeId.of("Y")),
            Optional.empty());
    UUID routeUuid = UUID.randomUUID();
    UUID otherRouteUuid = UUID.randomUUID();
    RouteProgressRegistry registry = new RouteProgressRegistry();
    registry.initFromTags(
        "follower",
        new TagStore("follower", "FTA_ROUTE_ID=" + routeUuid, "FTA_ROUTE_INDEX=1").properties(),
        route);
    registry.initFromTags(
        "leader",
        new TagStore("leader", "FTA_ROUTE_ID=" + routeUuid, "FTA_ROUTE_INDEX=2").properties(),
        route);
    registry.initFromTags(
        "behind",
        new TagStore("behind", "FTA_ROUTE_ID=" + routeUuid, "FTA_ROUTE_INDEX=0").properties(),
        route);
    registry.initFromTags(
        "other",
        new TagStore("other", "FTA_ROUTE_ID=" + otherRouteUuid, "FTA_ROUTE_INDEX=2").properties(),
        otherRoute);
    RuntimeDispatchService service =
        createMinimalService(
            mockOccupancyManager(),
            routeDefinitionCacheWith(Map.of(routeUuid, route, otherRouteUuid, otherRoute)),
            registry,
            new ArrayList<>());

    assertTrue(invokeProvenSameRouteLeader(service, "follower", "leader"));
    assertFalse(invokeProvenSameRouteLeader(service, "follower", "behind"));
    assertFalse(invokeProvenSameRouteLeader(service, "follower", "other"));
    assertFalse(invokeProvenSameRouteLeader(service, "follower", "missing"));
  }

  @Test
  void provenSameRouteLeaderFailsClosedForRouteWithTurnbackBoundary() throws Exception {
    RouteDefinition turnbackRoute =
        new RouteDefinition(
            RouteId.of("turnback-route"),
            List.of(NodeId.of("A"), NodeId.of("B"), NodeId.of("A")),
            Optional.empty());
    UUID routeUuid = UUID.randomUUID();
    RouteProgressRegistry registry = new RouteProgressRegistry();
    registry.initFromTags(
        "follower",
        new TagStore("follower", "FTA_ROUTE_ID=" + routeUuid, "FTA_ROUTE_INDEX=0").properties(),
        turnbackRoute);
    registry.initFromTags(
        "leader",
        new TagStore("leader", "FTA_ROUTE_ID=" + routeUuid, "FTA_ROUTE_INDEX=1").properties(),
        turnbackRoute);
    RuntimeDispatchService service =
        createMinimalService(
            mockOccupancyManager(),
            routeDefinitionCacheWith(turnbackRoute, routeUuid),
            registry,
            new ArrayList<>());

    assertFalse(invokeProvenSameRouteLeader(service, "follower", "leader"));
  }

  @Test
  void forwardRiskSourceKeepsPhysicalNodeAsHardBlocker() throws Exception {
    OccupancyManager occupancyManager = mockOccupancyManager();
    RuntimeDispatchService service = createMinimalService(occupancyManager, new ArrayList<>());
    OccupancyResource node = OccupancyResource.forNode(NodeId.of("B"));
    OccupancyRequest request =
        new OccupancyRequest(
            "follower",
            Optional.empty(),
            Instant.parse("2026-01-01T00:00:00Z"),
            List.of(node),
            Map.of());
    OccupancyClaim blocker =
        new OccupancyClaim(
            node, "leader", Optional.empty(), request.now(), Duration.ZERO, Optional.empty());
    when(occupancyManager.isProvenSameDirectionFollower(request, node, "leader", false))
        .thenReturn(true);

    RiskSource source = invokeRiskSourceForBlocker(service, request, blocker);

    assertEquals(RiskSource.HARD_BLOCKER, source);
    verify(occupancyManager, never()).isProvenSameDirectionFollower(request, node, "leader", false);
  }

  @Test
  void routeStopPublishesCautionWithoutRecoverableHold() {
    List<String> debugMessages = new ArrayList<>();
    NodeId a = NodeId.of("A");
    NodeId station = NodeId.of("OP:S:CENTRAL:1");
    RouteDefinition route =
        new RouteDefinition(RouteId.of("r"), List.of(a, station), Optional.empty());
    TagStore tags =
        new TagStore(
            "train-1",
            "FTA_OPERATOR_CODE=op",
            "FTA_LINE_CODE=l1",
            "FTA_ROUTE_CODE=r1",
            "FTA_ROUTE_INDEX=0");
    java.util.concurrent.atomic.AtomicReference<String> destination =
        new java.util.concurrent.atomic.AtomicReference<>(station.value());
    when(tags.properties().getDestination()).thenAnswer(inv -> destination.get());
    org.mockito.Mockito.doAnswer(
            inv -> {
              destination.set("");
              return null;
            })
        .when(tags.properties())
        .clearDestination();
    UUID worldId = UUID.randomUUID();

    ConfigManager configManager = mock(ConfigManager.class);
    when(configManager.current()).thenReturn(testConfigView(1, 40.0));

    RailGraphService railGraphService = mock(RailGraphService.class);
    when(railGraphService.getSnapshot(worldId))
        .thenReturn(
            Optional.of(
                new RailGraphService.RailGraphSnapshot(
                    graphWithSingleEdge(a, station, 25), Instant.now())));
    when(railGraphService.effectiveSpeedLimitBlocksPerSecond(any(), any(), any(), anyDouble()))
        .thenReturn(40.0);

    RouteDefinitionCache routeDefinitions = mock(RouteDefinitionCache.class);
    when(routeDefinitions.findByCodes("op", "l1", "r1")).thenReturn(Optional.of(route));
    when(routeDefinitions.findStop(route.id(), 1))
        .thenReturn(Optional.of(routeStop(1, station, RouteStopPassType.STOP)));

    OccupancyManager occupancyManager = mockOccupancyManager();
    when(occupancyManager.canEnter(any())).thenAnswer(allowProceed());
    when(occupancyManager.acquire(any())).thenAnswer(allowProceed());

    RouteProgressRegistry registry = new RouteProgressRegistry();
    RuntimeDispatchService service =
        new RuntimeDispatchService(
            occupancyManager,
            railGraphService,
            routeDefinitions,
            registry,
            registryWithStation(worldId, station),
            mock(LayoverRegistry.class),
            new DwellRegistry(),
            configManager,
            null,
            new TrainConfigResolver(),
            debugMessages::add);

    FakeTrain train = new FakeTrain(worldId, tags.properties(), true, 0.5);
    service.handleSignalTick(train, true);

    assertEquals(
        SignalAspect.PROCEED_WITH_CAUTION, registry.get("train-1").orElseThrow().lastSignal());
    assertEquals(0, train.hardStopCalls);
    verify(tags.properties(), never()).clearDestination();
    assertEquals(station.value(), destination.get());
    ArgumentCaptor<Double> speedCaptor = ArgumentCaptor.forClass(Double.class);
    verify(tags.properties()).setSpeedLimit(speedCaptor.capture());
    assertTrue(speedCaptor.getValue() > 0.0);
    assertFalse(
        debugMessages.stream()
            .anyMatch(
                message ->
                    message.contains("SMART_DISPATCH_RECOVERABLE_HOLD")
                        && message.contains("riskSource=ROUTE_STOP_OR_TERMINAL")));
    assertTrue(
        debugMessages.stream()
            .anyMatch(
                message ->
                    message.contains("MOVEMENT_AUTHORITY_PLANNED_STOP_ADVISORY")
                        && message.contains("reason=ROUTE_STOP_OR_TERMINAL")));
    assertTrue(
        debugMessages.stream()
            .anyMatch(
                message ->
                    message.contains("SMART_DISPATCH_ACTION_OBSERVED")
                        && message.contains("effectClass=SIGNAL_ADVISORY")));
    assertFalse(
        debugMessages.stream()
            .anyMatch(message -> message.contains("SMART_AUTHORITY_WINDOW_EXCEEDED_REAL")));
  }

  @Test
  void authorityWindowPhysicalEndMayDowngradeToCaution() {
    NodeId a = NodeId.of("A");
    NodeId b = NodeId.of("B");
    RouteDefinition route = new RouteDefinition(RouteId.of("r"), List.of(a, b), Optional.empty());
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
                    graphWithSingleEdge(a, b, 10), Instant.now())));
    when(railGraphService.effectiveSpeedLimitBlocksPerSecond(any(), any(), any(), anyDouble()))
        .thenReturn(1000.0);

    RouteDefinitionCache routeDefinitions = mock(RouteDefinitionCache.class);
    when(routeDefinitions.findByCodes("op", "l1", "r1")).thenReturn(Optional.of(route));

    OccupancyManager occupancyManager = mockOccupancyManager();
    when(occupancyManager.canEnter(any())).thenAnswer(allowProceed());
    when(occupancyManager.acquire(any())).thenAnswer(allowProceed());

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

    FakeTrain train = new FakeTrain(worldId, tags.properties(), true, 0.2);
    service.handleSignalTick(train, true);

    assertEquals(
        SignalAspect.PROCEED_WITH_CAUTION, registry.get("train-1").orElseThrow().lastSignal());
    assertEquals(0, train.hardStopCalls);
  }

  /**
   * 前向扫描会看到硬授权窗口之外的前车，但该前车已远在 caution 阈值之外，因此不施加任何降级。
   *
   * <p>旧契约在这里断言 {@code PROCEED_WITH_CAUTION}：只要扫描范围内出现任何一辆车就无条件限速。 实服代价很大——{@code
   * caution-speed-bps} 为 10，而线路边限速多为 16.7/22.2 bps， 等于对每一个后车掉速一半以上；日志里 {@code PROCEED_WITH_CAUTION}
   * 占已发布信号 45%， 而真正的 STOP 只有 6 次。STOP 与 CAUTION 两个距离阈值未改动，见 {@code distanceToForwardTrainSignal}。
   */
  @Test
  void forwardTrainBeyondCautionThresholdDoesNotDowngrade() {
    NodeId a = NodeId.of("A");
    NodeId b = NodeId.of("B");
    NodeId c = NodeId.of("C");
    NodeId d = NodeId.of("D");
    RouteDefinition route = new RouteDefinition(RouteId.of("r"), List.of(a, d), Optional.empty());
    TagStore tags =
        new TagStore(
            "train-1",
            "FTA_OPERATOR_CODE=op",
            "FTA_LINE_CODE=l1",
            "FTA_ROUTE_CODE=r1",
            "FTA_ROUTE_INDEX=0");
    UUID worldId = UUID.randomUUID();

    ConfigManager configManager = mock(ConfigManager.class);
    ConfigManager.ConfigView base = testConfigView(1, 40.0);
    when(configManager.current())
        .thenReturn(withRuntimeSettings(base, runtimeWithLookaheadEdges(base, 3)));

    RailGraphService railGraphService = mock(RailGraphService.class);
    when(railGraphService.getSnapshot(worldId))
        .thenReturn(
            Optional.of(
                new RailGraphService.RailGraphSnapshot(
                    graphWithConflictFreeLinearPath(List.of(a, b, c, d), 10), Instant.now())));
    when(railGraphService.effectiveSpeedLimitBlocksPerSecond(any(), any(), any(), anyDouble()))
        .thenReturn(40.0);

    RouteDefinitionCache routeDefinitions = mock(RouteDefinitionCache.class);
    when(routeDefinitions.findByCodes("op", "l1", "r1")).thenReturn(Optional.of(route));

    OccupancyClaim leaderAtSecondEdge =
        new OccupancyClaim(
            OccupancyResource.forNode(c),
            "front",
            Optional.of(route.id()),
            Instant.now(),
            Duration.ZERO,
            Optional.empty());
    OccupancyManager occupancyManager = mockOccupancyManager();
    when(occupancyManager.snapshotClaims()).thenReturn(List.of());
    when(occupancyManager.getClaim(OccupancyResource.forNode(c)))
        .thenReturn(Optional.of(leaderAtSecondEdge));
    when(occupancyManager.getClaim(
            argThat(resource -> !OccupancyResource.forNode(c).equals(resource))))
        .thenReturn(Optional.empty());
    when(occupancyManager.canEnter(any())).thenAnswer(allowProceed());
    when(occupancyManager.acquire(any())).thenAnswer(allowProceed());

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

    FakeTrain train = new FakeTrain(worldId, tags.properties(), false, 0.0);
    service.handleSignalTick(train, true);

    assertEquals(SignalAspect.PROCEED, registry.get("train-1").orElseThrow().lastSignal());
    assertEquals(0, train.hardStopCalls);
  }

  @Test
  void resolveMovementAuthorityDistanceUsesAuthorityEndWithoutPathFallback() throws Exception {
    java.lang.reflect.Method method =
        RuntimeDispatchService.class.getDeclaredMethod(
            "resolveMovementAuthorityDistance", OptionalLong.class, OptionalLong.class);
    method.setAccessible(true);

    OptionalLong proceedDistance =
        (OptionalLong) method.invoke(null, OptionalLong.empty(), OptionalLong.of(44L));
    assertTrue(proceedDistance.isPresent());
    assertEquals(44L, proceedDistance.getAsLong());

    OptionalLong hardConstraint =
        (OptionalLong) method.invoke(null, OptionalLong.of(12L), OptionalLong.of(44L));
    assertTrue(hardConstraint.isPresent());
    assertEquals(12L, hardConstraint.getAsLong());
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

    OccupancyManager occupancyManager = mockOccupancyManager();
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

    verify(occupancyManager, org.mockito.Mockito.never())
        .releaseResource(edge, Optional.of("train-1"));
    verify(occupancyManager, org.mockito.Mockito.never())
        .releaseResource(keepNode, Optional.of("train-1"));
    verify(occupancyManager, org.mockito.Mockito.never()).releaseByTrain("train-1");
  }

  @Test
  void handleSignalTickKeepsStopWhileDepartureGateIsHeld() {
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

    OccupancyManager occupancyManager = mockOccupancyManager();
    when(occupancyManager.getClaim(any())).thenReturn(Optional.empty());
    when(occupancyManager.snapshotClaims()).thenReturn(List.of());
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
    service.acquireDepartureGate("train-1", "sid-1", "test_hold");

    FakeTrain train = new FakeTrain(worldId, tags.properties(), false);
    service.handleSignalTick(train, false);

    assertEquals(0, train.launchCalls);
    assertTrue(train.stopCalls >= 1);
    assertTrue(service.hasDepartureGate("train-1"));
    SignalAspect aspect = service.snapshotProgressEntries().get("train-1").lastSignal();
    assertEquals(SignalAspect.STOP, aspect);
  }

  @Test
  void handleSignalTickRefreshesForwardQueueWhileDepartureGateIsHeld() {
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

    OccupancyManager occupancyManager =
        mock(
            OccupancyManager.class,
            org.mockito.Mockito.withSettings().extraInterfaces(OccupancyQueueSupport.class));
    OccupancyQueueSupport queueSupport = (OccupancyQueueSupport) occupancyManager;
    when(occupancyManager.snapshotClaims()).thenReturn(List.of());
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
    service.acquireDepartureGate("train-1", "sid-1", "test_hold");

    FakeTrain train = new FakeTrain(worldId, tags.properties(), false);
    service.handleSignalTick(train, false);

    ArgumentCaptor<OccupancyRequest> holdCaptor = ArgumentCaptor.forClass(OccupancyRequest.class);
    ArgumentCaptor<OccupancyRequest> queueCaptor = ArgumentCaptor.forClass(OccupancyRequest.class);
    verify(occupancyManager).acquire(holdCaptor.capture());
    verify(queueSupport).touchQueues(queueCaptor.capture());
    MovementPlanSnapshot holdPlan = holdCaptor.getValue().movementPlanSnapshot().orElseThrow();
    MovementPlanSnapshot queuePlan = queueCaptor.getValue().movementPlanSnapshot().orElseThrow();
    assertEquals(queuePlan.requestId(), holdPlan.requestId());
    assertEquals(queuePlan.expandedPathNodes(), holdPlan.expandedPathNodes());
  }

  @Test
  void departureGateRequiresMatchingSessionToRelease() {
    RuntimeDispatchService service = createMinimalService();
    service.acquireDepartureGate("train-1", "sid-new", "test");
    assertTrue(service.hasDepartureGate("train-1"));

    assertFalse(service.releaseDepartureGate("train-1", "sid-old"));
    assertTrue(service.hasDepartureGate("train-1"));

    assertTrue(service.releaseDepartureGate("train-1", "sid-new"));
    assertFalse(service.hasDepartureGate("train-1"));
  }

  @Test
  void stationArrivalDestinationDefersOnlyForHeldMiddleStationGate() {
    RuntimeDispatchService service = createMinimalService();
    RouteDefinition route =
        new RouteDefinition(
            RouteId.of("r"),
            List.of(NodeId.of("A"), NodeId.of("STATION"), NodeId.of("NEXT")),
            Optional.empty());

    assertFalse(service.shouldDeferStationDepartureDestination("train-1", route, 1));

    service.acquireDepartureGate("train-1", "sid-1", "autostation_dwell");

    assertTrue(service.shouldDeferStationDepartureDestination("train-1", route, 1));
    assertFalse(service.shouldDeferStationDepartureDestination("train-1", route, 2));
    assertFalse(service.shouldDeferStationDepartureDestination("other-train", route, 1));
    assertFalse(service.shouldDeferStationDepartureDestination("train-1", route, -1));
  }

  @Test
  void checkDepartureFailsClosedWhenRouteDefinitionIsUnavailable() {
    NodeId station = NodeId.of("OP:S:JBS:1");
    TagStore tags =
        new TagStore(
            "train-1",
            "FTA_OPERATOR_CODE=op",
            "FTA_LINE_CODE=l1",
            "FTA_ROUTE_CODE=missing",
            "FTA_ROUTE_INDEX=0");
    ConfigManager configManager = mock(ConfigManager.class);
    when(configManager.current()).thenReturn(testConfigView(20, 20.0));
    OccupancyManager occupancyManager = mockOccupancyManager();
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
    FakeTrain train = new FakeTrain(UUID.randomUUID(), tags.properties(), false);
    SignNodeDefinition definition =
        new SignNodeDefinition(station, NodeType.STATION, Optional.empty(), Optional.empty());

    assertFalse(service.checkDeparture(train, definition));

    assertEquals(1, train.hardStopCalls);
    assertTrue(service.isMovementInhibited("train-1"));
    verify(occupancyManager, never()).canEnter(any());
    verify(occupancyManager, never()).acquire(any());
    verify(occupancyManager, never()).releaseByTrain(anyString());
  }

  @Test
  void checkDepartureFailsClosedWhenGraphSnapshotIsUnavailable() {
    NodeId station = NodeId.of("OP:S:JBS:1");
    NodeId throat = NodeId.of("OP:S:JBS:1:001");
    RouteDefinition route =
        new RouteDefinition(RouteId.of("r"), List.of(station, throat), Optional.empty());
    TagStore tags =
        new TagStore(
            "train-1",
            "FTA_OPERATOR_CODE=op",
            "FTA_LINE_CODE=l1",
            "FTA_ROUTE_CODE=r1",
            "FTA_ROUTE_INDEX=0");
    ConfigManager configManager = mock(ConfigManager.class);
    when(configManager.current()).thenReturn(testConfigView(20, 20.0));
    RouteDefinitionCache routeDefinitions = mock(RouteDefinitionCache.class);
    when(routeDefinitions.findByCodes("op", "l1", "r1")).thenReturn(Optional.of(route));
    OccupancyManager occupancyManager = mockOccupancyManager();
    RuntimeDispatchService service =
        new RuntimeDispatchService(
            occupancyManager,
            mock(RailGraphService.class),
            routeDefinitions,
            new RouteProgressRegistry(),
            mock(SignNodeRegistry.class),
            mock(LayoverRegistry.class),
            new DwellRegistry(),
            configManager,
            null,
            new TrainConfigResolver(),
            null);
    FakeTrain train = new FakeTrain(UUID.randomUUID(), tags.properties(), false);
    SignNodeDefinition definition =
        new SignNodeDefinition(station, NodeType.STATION, Optional.empty(), Optional.empty());

    assertFalse(service.checkDeparture(train, definition));

    assertEquals(1, train.hardStopCalls);
    assertTrue(service.isMovementInhibited("train-1"));
    verify(occupancyManager, never()).canEnter(any());
    verify(occupancyManager, never()).acquire(any());
  }

  @Test
  void checkDepartureFailsClosedWhenStationCannotBeLocatedOnRoute() {
    NodeId routeStart = NodeId.of("OP:S:OTHER:1");
    NodeId routeEnd = NodeId.of("OP:S:OTHER:2");
    NodeId observedStation = NodeId.of("OP:S:JBS:1");
    RouteDefinition route =
        new RouteDefinition(RouteId.of("r"), List.of(routeStart, routeEnd), Optional.empty());
    TagStore tags =
        new TagStore(
            "train-1",
            "FTA_OPERATOR_CODE=op",
            "FTA_LINE_CODE=l1",
            "FTA_ROUTE_CODE=r1",
            "FTA_ROUTE_INDEX=0");
    ConfigManager configManager = mock(ConfigManager.class);
    when(configManager.current()).thenReturn(testConfigView(20, 20.0));
    RouteDefinitionCache routeDefinitions = mock(RouteDefinitionCache.class);
    when(routeDefinitions.findByCodes("op", "l1", "r1")).thenReturn(Optional.of(route));
    OccupancyManager occupancyManager = mockOccupancyManager();
    RuntimeDispatchService service =
        new RuntimeDispatchService(
            occupancyManager,
            mock(RailGraphService.class),
            routeDefinitions,
            new RouteProgressRegistry(),
            mock(SignNodeRegistry.class),
            mock(LayoverRegistry.class),
            new DwellRegistry(),
            configManager,
            null,
            new TrainConfigResolver(),
            null);
    FakeTrain train = new FakeTrain(UUID.randomUUID(), tags.properties(), false);
    SignNodeDefinition definition =
        new SignNodeDefinition(
            observedStation, NodeType.STATION, Optional.empty(), Optional.empty());

    assertFalse(service.checkDeparture(train, definition));

    assertEquals(1, train.hardStopCalls);
    assertTrue(service.isMovementInhibited("train-1"));
    verify(occupancyManager, never()).canEnter(any());
    verify(occupancyManager, never()).acquire(any());
  }

  @Test
  void checkDepartureDoesNotAcquireForwardWindowWhenHardBlockerBypassIsSuppressed() {
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

    OccupancyManager occupancyManager = mockOccupancyManager();
    when(occupancyManager.snapshotClaims()).thenReturn(List.of());
    when(occupancyManager.canEnter(any()))
        .thenAnswer(
            invocation -> {
              OccupancyRequest request = invocation.getArgument(0);
              OccupancyClaim blocker =
                  new OccupancyClaim(
                      OccupancyResource.forNode(next),
                      "front-train",
                      Optional.of(route.id()),
                      request.now(),
                      Duration.ZERO,
                      Optional.empty());
              return new OccupancyDecision(
                  true, request.now(), SignalAspect.PROCEED, List.of(blocker));
            });
    when(occupancyManager.acquire(any())).thenAnswer(allowProceed());

    List<String> debugMessages = new ArrayList<>();
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
            debugMessages::add);

    FakeTrain train = new FakeTrain(worldId, tags.properties(), false);
    SignNodeDefinition definition =
        new SignNodeDefinition(current, NodeType.STATION, Optional.empty(), Optional.empty());

    assertFalse(service.checkDeparture(train, definition));

    ArgumentCaptor<OccupancyRequest> acquireCaptor =
        ArgumentCaptor.forClass(OccupancyRequest.class);
    verify(occupancyManager).acquire(acquireCaptor.capture());
    assertEquals(
        List.of(
            OccupancyResource.forNode(current),
            OccupancyResource.forEdge(EdgeId.undirected(next, current))),
        acquireCaptor.getValue().resourceList());
    assertTrue(
        service.recentBlockerTrains("train-1", Duration.ofSeconds(30)).contains("front-train"));
    assertFalse(
        debugMessages.stream()
            .anyMatch(message -> message.contains("SMART_STOP_RETAIN_RESOURCE_SHRINK")),
        "没有候选或实际资源释放的 STOP retain 不得伪造 shrink 生命周期: " + debugMessages);
  }

  @Test
  void checkDepartureBlocksMarkedConflictReleaseWithHardBlockers() {
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

    OccupancyManager occupancyManager = mockOccupancyManager();
    when(occupancyManager.snapshotClaims()).thenReturn(List.of());
    when(occupancyManager.canEnter(any()))
        .thenAnswer(
            invocation -> {
              OccupancyRequest request = invocation.getArgument(0);
              OccupancyClaim blocker =
                  new OccupancyClaim(
                      OccupancyResource.forNode(next),
                      "opposite-train",
                      Optional.of(route.id()),
                      request.now(),
                      Duration.ZERO,
                      Optional.empty());
              return new OccupancyDecision(
                  true, request.now(), SignalAspect.PROCEED, List.of(blocker), true);
            });
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

    FakeTrain train = new FakeTrain(worldId, tags.properties(), false);
    SignNodeDefinition definition =
        new SignNodeDefinition(current, NodeType.STATION, Optional.empty(), Optional.empty());

    assertFalse(service.checkDeparture(train, definition));
    verify(occupancyManager, atLeastOnce()).acquire(any());
    assertTrue(
        service.recentBlockerTrains("train-1", Duration.ofSeconds(30)).contains("opposite-train"));
  }

  @Test
  void checkDepartureIgnoresRearGuardClaimsWhenAuthorizingFirstStationDeparture() {
    NodeId depot = NodeId.of("D");
    NodeId station = NodeId.of("S");
    NodeId next = NodeId.of("N");
    RouteDefinition route =
        new RouteDefinition(RouteId.of("r"), List.of(depot, station, next), Optional.empty());
    TagStore tags =
        new TagStore(
            "train-1",
            "FTA_OPERATOR_CODE=op",
            "FTA_LINE_CODE=l1",
            "FTA_ROUTE_CODE=r1",
            "FTA_ROUTE_INDEX=1");
    TagStore backTags = new TagStore("train-back", "FTA_ROUTE_INDEX=0");
    UUID worldId = UUID.randomUUID();
    RailGraph graph = graphWithTwoEdges(depot, station, next, 10, 10);

    ConfigManager configManager = mock(ConfigManager.class);
    when(configManager.current()).thenReturn(testConfigView(20, 20.0));

    RailGraphService railGraphService = mock(RailGraphService.class);
    when(railGraphService.getSnapshot(worldId))
        .thenReturn(Optional.of(new RailGraphService.RailGraphSnapshot(graph, Instant.now())));
    when(railGraphService.effectiveSpeedLimitBlocksPerSecond(any(), any(), any(), anyDouble()))
        .thenReturn(1000.0);

    RouteDefinitionCache routeDefinitions = mock(RouteDefinitionCache.class);
    when(routeDefinitions.findByCodes("op", "l1", "r1")).thenReturn(Optional.of(route));

    SimpleOccupancyManager occupancyManager =
        new SimpleOccupancyManager(
            HeadwayRule.fixed(Duration.ZERO), SignalAspectPolicy.defaultPolicy());
    OccupancyResource rearEdge = OccupancyResource.forEdge(EdgeId.undirected(depot, station));
    occupancyManager.acquire(
        new OccupancyRequest(
            "train-back", Optional.of(route.id()), Instant.now(), List.of(rearEdge), Map.of(), 0));

    RouteProgressRegistry registry = new RouteProgressRegistry();
    registry.initFromTags("train-1", tags.properties(), route);
    registry.initFromTags("train-back", backTags.properties(), route);
    List<String> debugMessages = new ArrayList<>();

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
            debugMessages::add);

    FakeTrain train = new FakeTrain(worldId, tags.properties(), false);
    SignNodeDefinition definition =
        new SignNodeDefinition(station, NodeType.STATION, Optional.empty(), Optional.empty());

    assertTrue(service.checkDeparture(train, definition));
    assertEquals("train-back", occupancyManager.getClaim(rearEdge).orElseThrow().trainName());
    assertEquals(
        "train-1",
        occupancyManager
            .getClaim(OccupancyResource.forEdge(EdgeId.undirected(station, next)))
            .orElseThrow()
            .trainName());
  }

  @Test
  void checkDepartureStillStopsForRealForwardBlocker() {
    NodeId depot = NodeId.of("D");
    NodeId station = NodeId.of("S");
    NodeId next = NodeId.of("N");
    RouteDefinition route =
        new RouteDefinition(RouteId.of("r"), List.of(depot, station, next), Optional.empty());
    TagStore tags =
        new TagStore(
            "train-1",
            "FTA_OPERATOR_CODE=op",
            "FTA_LINE_CODE=l1",
            "FTA_ROUTE_CODE=r1",
            "FTA_ROUTE_INDEX=1");
    UUID worldId = UUID.randomUUID();
    RailGraph graph = graphWithTwoEdges(depot, station, next, 10, 10);

    ConfigManager configManager = mock(ConfigManager.class);
    when(configManager.current()).thenReturn(testConfigView(20, 20.0));

    RailGraphService railGraphService = mock(RailGraphService.class);
    when(railGraphService.getSnapshot(worldId))
        .thenReturn(Optional.of(new RailGraphService.RailGraphSnapshot(graph, Instant.now())));
    when(railGraphService.effectiveSpeedLimitBlocksPerSecond(any(), any(), any(), anyDouble()))
        .thenReturn(1000.0);

    RouteDefinitionCache routeDefinitions = mock(RouteDefinitionCache.class);
    when(routeDefinitions.findByCodes("op", "l1", "r1")).thenReturn(Optional.of(route));

    SimpleOccupancyManager occupancyManager =
        new SimpleOccupancyManager(
            HeadwayRule.fixed(Duration.ZERO), SignalAspectPolicy.defaultPolicy());
    OccupancyResource forwardEdge = OccupancyResource.forEdge(EdgeId.undirected(station, next));
    occupancyManager.acquire(
        new OccupancyRequest(
            "front-train",
            Optional.of(route.id()),
            Instant.now(),
            List.of(forwardEdge),
            Map.of(),
            0));

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
    SignNodeDefinition definition =
        new SignNodeDefinition(station, NodeType.STATION, Optional.empty(), Optional.empty());

    assertFalse(service.checkDeparture(train, definition));
    assertTrue(
        service.recentBlockerTrains("train-1", Duration.ofSeconds(30)).contains("front-train"));
  }

  @Test
  void blockedDepartureUsesActualTrainLengthWhenShrinkingRearHold() {
    String trainName = "train-1";
    NodeId a = NodeId.of("A");
    NodeId b = NodeId.of("B");
    NodeId c = NodeId.of("C");
    NodeId station = NodeId.of("D");
    NodeId next = NodeId.of("E");
    RouteDefinition route =
        new RouteDefinition(RouteId.of("r"), List.of(a, b, c, station, next), Optional.empty());
    TagStore tags =
        new TagStore(
            trainName,
            "FTA_OPERATOR_CODE=op",
            "FTA_LINE_CODE=l1",
            "FTA_ROUTE_CODE=r1",
            "FTA_ROUTE_INDEX=3");
    UUID worldId = UUID.randomUUID();
    PhysicalGraphFixture physicalGraph =
        withSyntheticPhysicalFootprints(graphWithLinearPath(route.waypoints(), 10), worldId);
    RailGraph graph = physicalGraph.graph();

    ConfigManager configManager = mock(ConfigManager.class);
    when(configManager.current()).thenReturn(testConfigView(20, 20.0));
    RailGraphService railGraphService = mock(RailGraphService.class);
    when(railGraphService.getSnapshot(worldId))
        .thenReturn(Optional.of(new RailGraphService.RailGraphSnapshot(graph, Instant.now())));
    when(railGraphService.effectiveSpeedLimitBlocksPerSecond(any(), any(), any(), anyDouble()))
        .thenReturn(1000.0);
    RouteDefinitionCache routeDefinitions = mock(RouteDefinitionCache.class);
    when(routeDefinitions.findByCodes("op", "l1", "r1")).thenReturn(Optional.of(route));

    SignalEventBus eventBus = new SignalEventBus();
    List<OccupancyReleasedEvent> releaseEvents = new ArrayList<>();
    eventBus.subscribe(OccupancyReleasedEvent.class, releaseEvents::add);
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(
            HeadwayRule.fixed(Duration.ZERO), SignalAspectPolicy.defaultPolicy(), eventBus);
    OccupancyResource clearedRearEdge = OccupancyResource.forEdge(EdgeId.undirected(a, b));
    OccupancyResource protectedRearEdge = OccupancyResource.forEdge(EdgeId.undirected(b, c));
    OccupancyResource blockedForwardEdge =
        OccupancyResource.forEdge(EdgeId.undirected(station, next));
    assertTrue(
        manager
            .acquire(
                new OccupancyRequest(
                        trainName,
                        Optional.of(route.id()),
                        Instant.now(),
                        List.of(clearedRearEdge, protectedRearEdge),
                        Map.of(),
                        Map.of(),
                        0,
                        AuthorizationPurpose.RUNTIME_MOVE)
                    .withResourceIntents(
                        Map.of(
                            clearedRearEdge,
                            ResourceIntent.PROTECTIVE_RETAIN,
                            protectedRearEdge,
                            ResourceIntent.PROTECTIVE_RETAIN)))
            .allowed());
    assertTrue(
        manager
            .acquire(
                new OccupancyRequest(
                    "front-train",
                    Optional.of(route.id()),
                    Instant.now(),
                    List.of(blockedForwardEdge),
                    Map.of(),
                    0))
            .allowed());

    RuntimeDispatchService service =
        new RuntimeDispatchService(
            manager,
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
    RuntimeTrainHandle train = mock(RuntimeTrainHandle.class);
    when(train.isValid()).thenReturn(true);
    when(train.isMoving()).thenReturn(false);
    when(train.worldId()).thenReturn(worldId);
    when(train.properties()).thenReturn(tags.properties());
    when(train.estimatedTrainLengthBlocks()).thenReturn(OptionalDouble.of(1.0));
    when(train.liveRailFootprintCells())
        .thenReturn(Optional.of(Set.of(physicalGraph.cellsByEdge().get(EdgeId.undirected(b, c)))));
    SignNodeDefinition definition =
        new SignNodeDefinition(station, NodeType.STATION, Optional.empty(), Optional.empty());

    assertFalse(service.checkDeparture(train, definition));

    assertTrue(manager.getClaim(clearedRearEdge).isEmpty(), "已超过配置余量与真实车长的后向资源必须及时释放");
    assertEquals(trainName, manager.getClaim(protectedRearEdge).orElseThrow().trainName());
    assertFalse(
        releaseEvents.stream()
            .flatMap(event -> event.releasedResources().stream())
            .anyMatch(protectedRearEdge::equals),
        "仍被列尾覆盖的资源不能先发布 release 再重新 acquire");
    assertEquals("front-train", manager.getClaim(blockedForwardEdge).orElseThrow().trainName());
  }

  @Test
  void departureRetainsActualTrainLengthWhenConfiguredRearGuardEdgesIsZero() {
    String trainName = "long-train";
    NodeId a = NodeId.of("A");
    NodeId b = NodeId.of("B");
    NodeId station = NodeId.of("C");
    NodeId next = NodeId.of("D");
    RouteDefinition route =
        new RouteDefinition(RouteId.of("r"), List.of(a, b, station, next), Optional.empty());
    TagStore tags =
        new TagStore(
            trainName,
            "FTA_OPERATOR_CODE=op",
            "FTA_LINE_CODE=l1",
            "FTA_ROUTE_CODE=r1",
            "FTA_ROUTE_INDEX=2");
    UUID worldId = UUID.randomUUID();
    RailGraph graph = graphWithConflictFreeCyclePath(route.waypoints(), 10);

    ConfigManager configManager = mock(ConfigManager.class);
    ConfigManager.ConfigView base = testConfigView(20, 20.0);
    when(configManager.current())
        .thenReturn(withRuntimeSettings(base, runtimeWithRearGuardEdges(base, 0)));
    RailGraphService railGraphService = mock(RailGraphService.class);
    when(railGraphService.getSnapshot(worldId))
        .thenReturn(Optional.of(new RailGraphService.RailGraphSnapshot(graph, Instant.now())));
    when(railGraphService.effectiveSpeedLimitBlocksPerSecond(any(), any(), any(), anyDouble()))
        .thenReturn(1000.0);
    RouteDefinitionCache routeDefinitions = mock(RouteDefinitionCache.class);
    when(routeDefinitions.findByCodes("op", "l1", "r1")).thenReturn(Optional.of(route));
    List<String> debugMessages = new ArrayList<>();
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(
            HeadwayRule.fixed(Duration.ZERO), SignalAspectPolicy.defaultPolicy());
    RuntimeDispatchService service =
        new RuntimeDispatchService(
            manager,
            railGraphService,
            routeDefinitions,
            new RouteProgressRegistry(),
            mock(SignNodeRegistry.class),
            mock(LayoverRegistry.class),
            new DwellRegistry(),
            configManager,
            null,
            new TrainConfigResolver(),
            debugMessages::add);
    RuntimeTrainHandle train = mock(RuntimeTrainHandle.class);
    when(train.isValid()).thenReturn(true);
    when(train.isMoving()).thenReturn(false);
    when(train.worldId()).thenReturn(worldId);
    when(train.properties()).thenReturn(tags.properties());
    when(train.estimatedTrainLengthBlocks()).thenReturn(OptionalDouble.of(15.0));
    SignNodeDefinition definition =
        new SignNodeDefinition(station, NodeType.STATION, Optional.empty(), Optional.empty());

    assertTrue(service.checkDeparture(train, definition), debugMessages.toString());

    assertEquals(
        trainName,
        manager
            .getClaim(OccupancyResource.forEdge(EdgeId.undirected(a, b)))
            .orElseThrow()
            .trainName(),
        "固定 rearGuardEdges 为零时仍必须覆盖真实列车长度");
  }

  @Test
  void checkDepartureUsesFullAdmissionLookaheadBeyondHardAuthorityWindow() {
    NodeId depot = NodeId.of("D");
    NodeId station = NodeId.of("S");
    NodeId throat = NodeId.of("T");
    NodeId occupiedAhead = NodeId.of("OP:S:OCC:1");
    RouteDefinition route =
        new RouteDefinition(
            RouteId.of("r"), List.of(depot, station, throat, occupiedAhead), Optional.empty());
    TagStore tags =
        new TagStore(
            "train-1",
            "FTA_OPERATOR_CODE=op",
            "FTA_LINE_CODE=l1",
            "FTA_ROUTE_CODE=r1",
            "FTA_ROUTE_INDEX=1");
    UUID worldId = UUID.randomUUID();
    RailGraph graph = graphWithLinearPath(route.waypoints(), 10);

    ConfigManager configManager = mock(ConfigManager.class);
    ConfigManager.ConfigView baseConfig = testConfigView(20, 20.0);
    when(configManager.current())
        .thenReturn(withRuntimeSettings(baseConfig, runtimeWithLookaheadEdges(baseConfig, 3)));

    RailGraphService railGraphService = mock(RailGraphService.class);
    when(railGraphService.getSnapshot(worldId))
        .thenReturn(Optional.of(new RailGraphService.RailGraphSnapshot(graph, Instant.now())));
    when(railGraphService.effectiveSpeedLimitBlocksPerSecond(any(), any(), any(), anyDouble()))
        .thenReturn(1000.0);

    RouteDefinitionCache routeDefinitions = mock(RouteDefinitionCache.class);
    when(routeDefinitions.findByCodes("op", "l1", "r1")).thenReturn(Optional.of(route));

    OccupancyResource distantNode = OccupancyResource.forNode(occupiedAhead);
    OccupancyManager occupancyManager = mockOccupancyManager();
    when(occupancyManager.snapshotClaims()).thenReturn(List.of());
    when(occupancyManager.canEnter(any()))
        .thenAnswer(
            invocation -> {
              OccupancyRequest request = invocation.getArgument(0);
              if (!request.resourceList().contains(distantNode)) {
                return new OccupancyDecision(true, request.now(), SignalAspect.PROCEED, List.of());
              }
              OccupancyClaim blocker =
                  new OccupancyClaim(
                      distantNode,
                      "front-train",
                      Optional.of(route.id()),
                      request.now(),
                      Duration.ZERO,
                      Optional.empty());
              return new OccupancyDecision(
                  false, request.now(), SignalAspect.STOP, List.of(blocker));
            });
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

    FakeTrain train = new FakeTrain(worldId, tags.properties(), false);
    SignNodeDefinition definition =
        new SignNodeDefinition(station, NodeType.STATION, Optional.empty(), Optional.empty());

    assertFalse(service.checkDeparture(train, definition));
    ArgumentCaptor<OccupancyRequest> canEnterRequest =
        ArgumentCaptor.forClass(OccupancyRequest.class);
    verify(occupancyManager).canEnter(canEnterRequest.capture());
    assertTrue(canEnterRequest.getValue().resourceList().contains(distantNode));
    assertTrue(
        service.recentBlockerTrains("train-1", Duration.ofSeconds(30)).contains("front-train"));
  }

  @Test
  void handleSignalTickShrinksForwardOccupancyWhenBlocked() {
    NodeId current = NodeId.of("A");
    NodeId next = NodeId.of("B");
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

    OccupancyManager occupancyManager = mockOccupancyManager();
    when(occupancyManager.canEnter(any()))
        .thenAnswer(
            inv -> {
              OccupancyRequest request = inv.getArgument(0);
              return new OccupancyDecision(
                  false,
                  request.now(),
                  SignalAspect.STOP,
                  List.of(
                      new OccupancyClaim(
                          OccupancyResource.forNode(next),
                          "other",
                          Optional.empty(),
                          request.now(),
                          Duration.ZERO,
                          Optional.empty())));
            });

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

    verify(occupancyManager, org.mockito.Mockito.never())
        .releaseResource(edge, Optional.of("train-1"));
    verify(occupancyManager, org.mockito.Mockito.never())
        .releaseResource(keepNode, Optional.of("train-1"));
    assertEquals(0, train.hardStopCalls);
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
            base.runtimeSettings().movementAuthorityEnabled(),
            base.runtimeSettings().movementAuthorityStopMarginBlocks(),
            base.runtimeSettings().movementAuthorityCautionMarginBlocks(),
            base.runtimeSettings().speedCommandHysteresisBps(),
            base.runtimeSettings().speedCommandAccelFactor(),
            base.runtimeSettings().speedCommandDecelFactor(),
            base.runtimeSettings().distanceCacheRefreshSeconds(),
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
                base.reclaimSettings(),
                base.healthSettings()));

    RailGraphService railGraphService = mock(RailGraphService.class);
    when(railGraphService.getSnapshot(worldId))
        .thenReturn(
            Optional.of(
                new RailGraphService.RailGraphSnapshot(
                    graphWithTwoEdges(a, b, c, 10, 2), Instant.now())));
    when(railGraphService.effectiveSpeedLimitBlocksPerSecond(any(), any(), any(), anyDouble()))
        .thenReturn(1000.0);

    RouteDefinitionCache routeDefinitions = mock(RouteDefinitionCache.class);
    when(routeDefinitions.findByCodes("op", "l1", "r1")).thenReturn(Optional.of(route));

    OccupancyManager occupancyManager = mockOccupancyManager();
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
    assertEquals(SignalAspect.STOP, aspect);
    assertEquals(0, train.hardStopCalls);
  }

  @Test
  void shouldTrackIntermediateGraphNodeSupportsWaypointAndSwitcher() throws Exception {
    java.lang.reflect.Method method =
        RuntimeDispatchService.class.getDeclaredMethod(
            "shouldTrackIntermediateGraphNode", SignNodeDefinition.class);
    method.setAccessible(true);

    boolean waypoint =
        (boolean)
            method.invoke(
                null,
                new SignNodeDefinition(
                    NodeId.of("W"), NodeType.WAYPOINT, Optional.empty(), Optional.empty()));
    boolean switcher =
        (boolean)
            method.invoke(
                null,
                new SignNodeDefinition(
                    NodeId.of("S"), NodeType.SWITCHER, Optional.empty(), Optional.empty()));
    boolean station =
        (boolean)
            method.invoke(
                null,
                new SignNodeDefinition(
                    NodeId.of("ST"), NodeType.STATION, Optional.empty(), Optional.empty()));

    assertTrue(waypoint);
    assertTrue(switcher);
    assertFalse(station);
  }

  @Test
  void shouldApplyEventSignalOnlyForMoreRestrictiveAspect() throws Exception {
    java.lang.reflect.Method method =
        RuntimeDispatchService.class.getDeclaredMethod(
            "shouldApplyEventSignal", SignalAspect.class, SignalAspect.class);
    method.setAccessible(true);

    boolean strictFromNull = (boolean) method.invoke(null, null, SignalAspect.STOP);
    boolean cautionFromNull = (boolean) method.invoke(null, null, SignalAspect.CAUTION);
    boolean proceedFromNull = (boolean) method.invoke(null, null, SignalAspect.PROCEED);
    boolean strictUpgrade = (boolean) method.invoke(null, SignalAspect.CAUTION, SignalAspect.STOP);
    boolean sameLevel = (boolean) method.invoke(null, SignalAspect.STOP, SignalAspect.STOP);
    boolean permissive = (boolean) method.invoke(null, SignalAspect.STOP, SignalAspect.PROCEED);

    assertTrue(strictFromNull);
    assertFalse(cautionFromNull);
    assertFalse(proceedFromNull);
    assertTrue(strictUpgrade);
    assertFalse(sameLevel);
    assertFalse(permissive);
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

    OccupancyManager occupancyManager = mockOccupancyManager();
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
  void lookaheadPreviewCannotBlockFrontTrainAuthorization() {
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
    RailGraph graph = graphWithConflictFreeLinearPath(route.waypoints(), 10);
    ConfigManager configManager = mock(ConfigManager.class);
    when(configManager.current()).thenReturn(testConfigView(20, 20.0));

    RailGraphService railGraphService = mock(RailGraphService.class);
    when(railGraphService.getSnapshot(worldId))
        .thenReturn(Optional.of(new RailGraphService.RailGraphSnapshot(graph, Instant.now())));
    when(railGraphService.effectiveSpeedLimitBlocksPerSecond(any(), any(), any(), anyDouble()))
        .thenReturn(1000.0);

    RouteDefinitionCache routeDefinitions = mock(RouteDefinitionCache.class);
    when(routeDefinitions.findByCodes("op", "l1", "r1")).thenReturn(Optional.of(route));

    SimpleOccupancyManager occupancyManager =
        new SimpleOccupancyManager(
            HeadwayRule.fixed(Duration.ZERO), SignalAspectPolicy.defaultPolicy());
    OccupancyResource frontNode = OccupancyResource.forNode(b);
    OccupancyResource forwardEdge = OccupancyResource.forEdge(EdgeId.undirected(b, c));
    occupancyManager.acquire(
        new OccupancyRequest(
                "train-back",
                Optional.of(route.id()),
                Instant.now(),
                List.of(frontNode, forwardEdge),
                Map.of(),
                0)
            .withResourceIntents(
                Map.of(
                    frontNode,
                    ResourceIntent.LOOKAHEAD_PREVIEW,
                    forwardEdge,
                    ResourceIntent.LOOKAHEAD_PREVIEW)));

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
    assertEquals("train-1", occupancyManager.getClaim(forwardEdge).orElseThrow().trainName());
  }

  @Test
  void handleSignalTickNeverReleasesBehindTrainHardAuthorityByPlannedProgress() {
    NodeId a = NodeId.of("A");
    NodeId b = NodeId.of("B");
    NodeId c = NodeId.of("C");
    RouteDefinition route =
        new RouteDefinition(RouteId.of("r"), List.of(a, b, c), Optional.empty());
    TagStore frontTags =
        new TagStore(
            "train-front",
            "FTA_OPERATOR_CODE=op",
            "FTA_LINE_CODE=l1",
            "FTA_ROUTE_CODE=r1",
            "FTA_ROUTE_INDEX=1");
    TagStore behindTags = new TagStore("train-behind", "FTA_ROUTE_INDEX=0");
    UUID worldId = UUID.randomUUID();

    RailGraph graph = graphWithTwoEdges(a, b, c, 10, 10);
    ConfigManager configManager = mock(ConfigManager.class);
    when(configManager.current()).thenReturn(testConfigView(20, 20.0));

    RailGraphService railGraphService = mock(RailGraphService.class);
    when(railGraphService.getSnapshot(worldId))
        .thenReturn(Optional.of(new RailGraphService.RailGraphSnapshot(graph, Instant.now())));
    when(railGraphService.effectiveSpeedLimitBlocksPerSecond(any(), any(), any(), anyDouble()))
        .thenReturn(1000.0);

    RouteDefinitionCache routeDefinitions = mock(RouteDefinitionCache.class);
    when(routeDefinitions.findByCodes("op", "l1", "r1")).thenReturn(Optional.of(route));

    SimpleOccupancyManager occupancyManager =
        new SimpleOccupancyManager(
            HeadwayRule.fixed(Duration.ZERO), SignalAspectPolicy.defaultPolicy());
    OccupancyResource forwardEdge = OccupancyResource.forEdge(EdgeId.undirected(b, c));
    OccupancyRequest behindAuthority =
        new OccupancyRequest(
            "train-behind",
            Optional.of(route.id()),
            Instant.now(),
            List.of(forwardEdge),
            Map.of(),
            0);
    assertTrue(occupancyManager.acquire(behindAuthority).allowed());

    RouteProgressRegistry registry = new RouteProgressRegistry();
    registry.initFromTags("train-front", frontTags.properties(), route);
    registry.initFromTags("train-behind", behindTags.properties(), route);

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

    service.handleSignalTick(new FakeTrain(worldId, frontTags.properties(), false), false);

    assertTrue(
        occupancyManager.snapshotClaims().stream()
            .anyMatch(
                claim ->
                    claim.resource().equals(forwardEdge)
                        && claim.trainName().equals("train-behind")
                        && claim.role() == ClaimRole.MOVEMENT_REQUIRED),
        "运行图进度只能清理软前瞻，不能撤销另一列车的硬移动授权");
  }

  @Test
  void handleSignalTickClearsSameSegmentBehindLookaheadClaims() {
    NodeId a = NodeId.of("A");
    NodeId mid = NodeId.of("M");
    NodeId b = NodeId.of("B");
    RouteDefinition route = new RouteDefinition(RouteId.of("r"), List.of(a, b), Optional.empty());
    TagStore frontTags =
        new TagStore(
            "train-front",
            "FTA_OPERATOR_CODE=op",
            "FTA_LINE_CODE=l1",
            "FTA_ROUTE_CODE=r1",
            "FTA_ROUTE_INDEX=0");
    TagStore rearTags = new TagStore("train-rear", "FTA_ROUTE_INDEX=0");
    UUID worldId = UUID.randomUUID();

    RailGraph graph = graphWithTwoEdges(a, mid, b, 10, 10);
    ConfigManager configManager = mock(ConfigManager.class);
    when(configManager.current()).thenReturn(testConfigView(20, 20.0));

    RailGraphService railGraphService = mock(RailGraphService.class);
    when(railGraphService.getSnapshot(worldId))
        .thenReturn(Optional.of(new RailGraphService.RailGraphSnapshot(graph, Instant.now())));
    when(railGraphService.effectiveSpeedLimitBlocksPerSecond(any(), any(), any(), anyDouble()))
        .thenReturn(1000.0);

    RouteDefinitionCache routeDefinitions = mock(RouteDefinitionCache.class);
    when(routeDefinitions.findByCodes("op", "l1", "r1")).thenReturn(Optional.of(route));

    SimpleOccupancyManager occupancyManager =
        new SimpleOccupancyManager(
            HeadwayRule.fixed(Duration.ZERO), SignalAspectPolicy.defaultPolicy());
    OccupancyResource forwardEdge = OccupancyResource.forEdge(EdgeId.undirected(mid, b));
    occupancyManager.acquire(
        new OccupancyRequest(
                "train-rear",
                Optional.of(route.id()),
                Instant.now(),
                List.of(forwardEdge),
                Map.of(),
                0)
            .withResourceIntents(Map.of(forwardEdge, ResourceIntent.LOOKAHEAD_PREVIEW)));

    RouteProgressRegistry registry = new RouteProgressRegistry();
    registry.initFromTags("train-front", frontTags.properties(), route);
    registry.initFromTags("train-rear", rearTags.properties(), route);
    registry.updateLastPassedGraphNode("train-front", mid, Instant.now());

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

    FakeTrain train = new FakeTrain(worldId, frontTags.properties(), false);
    service.handleSignalTick(train, false);

    assertEquals("train-front", occupancyManager.getClaim(forwardEdge).orElseThrow().trainName());
    SignalAspect aspect = registry.get("train-front").orElseThrow().lastSignal();
    assertEquals(SignalAspect.PROCEED, aspect);
  }

  @Test
  void handleSignalTickClearsBehindLookaheadFromDifferentRouteOnSharedDirectedPath() {
    NodeId a = NodeId.of("A");
    NodeId mid = NodeId.of("M");
    NodeId b = NodeId.of("B");
    RouteDefinition frontRoute =
        new RouteDefinition(RouteId.of("front-route"), List.of(a, b), Optional.empty());
    RouteDefinition rearRoute =
        new RouteDefinition(RouteId.of("rear-route"), List.of(a, b), Optional.empty());
    UUID rearRouteUuid = UUID.randomUUID();
    TagStore frontTags =
        new TagStore(
            "train-front",
            "FTA_OPERATOR_CODE=op",
            "FTA_LINE_CODE=l1",
            "FTA_ROUTE_CODE=front",
            "FTA_ROUTE_INDEX=0");
    TagStore rearTags =
        new TagStore("train-rear", "FTA_ROUTE_ID=" + rearRouteUuid, "FTA_ROUTE_INDEX=0");
    UUID worldId = UUID.randomUUID();
    RailGraph graph = graphWithTwoEdges(a, mid, b, 10, 10);
    ConfigManager configManager = mock(ConfigManager.class);
    when(configManager.current()).thenReturn(testConfigView(20, 20.0));

    RailGraphService railGraphService = mock(RailGraphService.class);
    when(railGraphService.getSnapshot(worldId))
        .thenReturn(Optional.of(new RailGraphService.RailGraphSnapshot(graph, Instant.now())));
    when(railGraphService.effectiveSpeedLimitBlocksPerSecond(any(), any(), any(), anyDouble()))
        .thenReturn(1000.0);

    RouteDefinitionCache routeDefinitions = mock(RouteDefinitionCache.class);
    when(routeDefinitions.findByCodes("op", "l1", "front")).thenReturn(Optional.of(frontRoute));
    when(routeDefinitions.findById(rearRouteUuid)).thenReturn(Optional.of(rearRoute));

    SimpleOccupancyManager occupancyManager =
        new SimpleOccupancyManager(
            HeadwayRule.fixed(Duration.ZERO), SignalAspectPolicy.defaultPolicy());
    OccupancyResource forwardEdge = OccupancyResource.forEdge(EdgeId.undirected(mid, b));
    occupancyManager.acquire(
        new OccupancyRequest(
                "train-rear",
                Optional.of(rearRoute.id()),
                Instant.now(),
                List.of(forwardEdge),
                Map.of(),
                0)
            .withResourceIntents(Map.of(forwardEdge, ResourceIntent.LOOKAHEAD_PREVIEW)));

    RouteProgressRegistry registry = new RouteProgressRegistry();
    registry.initFromTags("train-front", frontTags.properties(), frontRoute);
    registry.initFromTags("train-rear", rearTags.properties(), rearRoute);
    registry.updateLastPassedGraphNode("train-front", mid, Instant.now());

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

    service.handleSignalTick(new FakeTrain(worldId, frontTags.properties(), false), false);

    assertEquals("train-front", occupancyManager.getClaim(forwardEdge).orElseThrow().trainName());
    assertEquals(SignalAspect.PROCEED, registry.get("train-front").orElseThrow().lastSignal());
  }

  @Test
  void handleSignalTickClearsBehindQueueEntryBeforeAuthorizingFrontTrain() {
    List<String> debugMessages = new ArrayList<>();
    NodeId a = NodeId.of("A");
    NodeId approach = NodeId.of("M");
    NodeId switcher = NodeId.of("SW");
    NodeId b = NodeId.of("B");
    RouteDefinition route =
        new RouteDefinition(RouteId.of("r"), List.of(a, approach, switcher, b), Optional.empty());
    TagStore frontTags =
        new TagStore(
            "train-front",
            "FTA_OPERATOR_CODE=op",
            "FTA_LINE_CODE=l1",
            "FTA_ROUTE_CODE=r1",
            "FTA_ROUTE_INDEX=1");
    TagStore rearTags = new TagStore("train-rear", "FTA_ROUTE_INDEX=0");
    UUID worldId = UUID.randomUUID();
    RailGraph graph = graphWithApproachAndSwitcher(a, approach, switcher, b, 10, 10, 10);

    ConfigManager configManager = mock(ConfigManager.class);
    when(configManager.current()).thenReturn(testConfigView(20, 20.0));

    RailGraphService railGraphService = mock(RailGraphService.class);
    when(railGraphService.getSnapshot(worldId))
        .thenReturn(Optional.of(new RailGraphService.RailGraphSnapshot(graph, Instant.now())));
    when(railGraphService.effectiveSpeedLimitBlocksPerSecond(any(), any(), any(), anyDouble()))
        .thenReturn(1000.0);

    RouteDefinitionCache routeDefinitions = mock(RouteDefinitionCache.class);
    when(routeDefinitions.findByCodes("op", "l1", "r1")).thenReturn(Optional.of(route));

    SimpleOccupancyManager occupancyManager =
        new SimpleOccupancyManager(
            HeadwayRule.fixed(Duration.ZERO), SignalAspectPolicy.defaultPolicy());
    OccupancyResource switcherConflict = OccupancyResource.forConflict("switcher:SW");
    occupancyManager.touchQueues(
        switcherTraversalRequest(
            "train-rear",
            Instant.now(),
            0,
            List.of(a, approach, switcher, b),
            List.of(
                new RailEdge(
                    EdgeId.undirected(a, approach), a, approach, 10, -1.0, true, Optional.empty()),
                new RailEdge(
                    EdgeId.undirected(approach, switcher),
                    approach,
                    switcher,
                    10,
                    -1.0,
                    true,
                    Optional.empty()),
                new RailEdge(
                    EdgeId.undirected(switcher, b), switcher, b, 10, -1.0, true, Optional.empty())),
            List.of(switcherConflict)));

    RouteProgressRegistry registry = new RouteProgressRegistry();
    registry.initFromTags("train-front", frontTags.properties(), route);
    registry.initFromTags("train-rear", rearTags.properties(), route);

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
            debugMessages::add);

    FakeTrain train = new FakeTrain(worldId, frontTags.properties(), false);
    service.handleSignalTick(train, false);

    assertTrue(
        occupancyManager.snapshotQueues().stream()
            .flatMap(snapshot -> snapshot.entries().stream())
            .noneMatch(entry -> entry.trainName().equalsIgnoreCase("train-rear")));
    assertFalse(
        service.recentBlockerTrains("train-front", Duration.ofSeconds(30)).contains("train-rear"),
        debugMessages::toString);
  }

  @Test
  void handleSignalTickClearsBehindQueueFromDifferentRouteOnSharedDirectedPath() {
    NodeId a = NodeId.of("A");
    NodeId approach = NodeId.of("M");
    NodeId switcher = NodeId.of("SW");
    NodeId b = NodeId.of("B");
    RouteDefinition frontRoute =
        new RouteDefinition(
            RouteId.of("front-route"), List.of(a, approach, switcher, b), Optional.empty());
    RouteDefinition rearRoute =
        new RouteDefinition(
            RouteId.of("rear-route"), List.of(a, approach, switcher, b), Optional.empty());
    UUID rearRouteUuid = UUID.randomUUID();
    TagStore frontTags =
        new TagStore(
            "train-front",
            "FTA_OPERATOR_CODE=op",
            "FTA_LINE_CODE=l1",
            "FTA_ROUTE_CODE=front",
            "FTA_ROUTE_INDEX=1");
    TagStore rearTags =
        new TagStore("train-rear", "FTA_ROUTE_ID=" + rearRouteUuid, "FTA_ROUTE_INDEX=0");
    UUID worldId = UUID.randomUUID();
    RailGraph graph = graphWithApproachAndSwitcher(a, approach, switcher, b, 10, 10, 10);

    ConfigManager configManager = mock(ConfigManager.class);
    when(configManager.current()).thenReturn(testConfigView(20, 20.0));
    RailGraphService railGraphService = mock(RailGraphService.class);
    when(railGraphService.getSnapshot(worldId))
        .thenReturn(Optional.of(new RailGraphService.RailGraphSnapshot(graph, Instant.now())));
    when(railGraphService.effectiveSpeedLimitBlocksPerSecond(any(), any(), any(), anyDouble()))
        .thenReturn(1000.0);
    RouteDefinitionCache routeDefinitions = mock(RouteDefinitionCache.class);
    when(routeDefinitions.findByCodes("op", "l1", "front")).thenReturn(Optional.of(frontRoute));
    when(routeDefinitions.findById(rearRouteUuid)).thenReturn(Optional.of(rearRoute));

    SimpleOccupancyManager occupancyManager =
        new SimpleOccupancyManager(
            HeadwayRule.fixed(Duration.ZERO), SignalAspectPolicy.defaultPolicy());
    OccupancyResource switcherConflict = OccupancyResource.forConflict("switcher:SW");
    occupancyManager.touchQueues(
        switcherTraversalRequest(
            "train-rear",
            Instant.now(),
            0,
            List.of(a, approach, switcher, b),
            List.of(
                new RailEdge(
                    EdgeId.undirected(a, approach), a, approach, 10, -1.0, true, Optional.empty()),
                new RailEdge(
                    EdgeId.undirected(approach, switcher),
                    approach,
                    switcher,
                    10,
                    -1.0,
                    true,
                    Optional.empty()),
                new RailEdge(
                    EdgeId.undirected(switcher, b), switcher, b, 10, -1.0, true, Optional.empty())),
            List.of(switcherConflict)));

    RouteProgressRegistry registry = new RouteProgressRegistry();
    registry.initFromTags("train-front", frontTags.properties(), frontRoute);
    registry.initFromTags("train-rear", rearTags.properties(), rearRoute);

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

    service.handleSignalTick(new FakeTrain(worldId, frontTags.properties(), false), false);

    assertTrue(
        occupancyManager.snapshotQueues().stream()
            .flatMap(snapshot -> snapshot.entries().stream())
            .noneMatch(entry -> entry.trainName().equalsIgnoreCase("train-rear")));
    assertFalse(
        service.recentBlockerTrains("train-front", Duration.ofSeconds(30)).contains("train-rear"));
  }

  @Test
  void handleSignalTickKeepsUnknownSwitcherQueueFromDifferentRouteOnSharedDirectedPath() {
    NodeId a = NodeId.of("A");
    NodeId switcher = NodeId.of("SW");
    NodeId b = NodeId.of("B");
    RouteDefinition frontRoute =
        new RouteDefinition(RouteId.of("front-route"), List.of(a, switcher, b), Optional.empty());
    RouteDefinition rearRoute =
        new RouteDefinition(RouteId.of("rear-route"), List.of(a, switcher, b), Optional.empty());
    UUID rearRouteUuid = UUID.randomUUID();
    TagStore frontTags =
        new TagStore(
            "train-front",
            "FTA_OPERATOR_CODE=op",
            "FTA_LINE_CODE=l1",
            "FTA_ROUTE_CODE=front",
            "FTA_ROUTE_INDEX=1");
    TagStore rearTags =
        new TagStore("train-rear", "FTA_ROUTE_ID=" + rearRouteUuid, "FTA_ROUTE_INDEX=0");
    UUID worldId = UUID.randomUUID();
    RailGraph graph = graphWithSwitcher(a, switcher, b, 10, 10);

    ConfigManager configManager = mock(ConfigManager.class);
    when(configManager.current()).thenReturn(testConfigView(20, 20.0));

    RailGraphService railGraphService = mock(RailGraphService.class);
    when(railGraphService.getSnapshot(worldId))
        .thenReturn(Optional.of(new RailGraphService.RailGraphSnapshot(graph, Instant.now())));
    when(railGraphService.effectiveSpeedLimitBlocksPerSecond(any(), any(), any(), anyDouble()))
        .thenReturn(1000.0);

    RouteDefinitionCache routeDefinitions = mock(RouteDefinitionCache.class);
    when(routeDefinitions.findByCodes("op", "l1", "front")).thenReturn(Optional.of(frontRoute));
    when(routeDefinitions.findById(rearRouteUuid)).thenReturn(Optional.of(rearRoute));

    SimpleOccupancyManager occupancyManager =
        new SimpleOccupancyManager(
            HeadwayRule.fixed(Duration.ZERO), SignalAspectPolicy.defaultPolicy());
    OccupancyResource switcherConflict = OccupancyResource.forConflict("switcher:SW");
    occupancyManager.touchQueues(
        new OccupancyRequest(
            "train-rear",
            Optional.of(rearRoute.id()),
            Instant.now(),
            List.of(switcherConflict),
            Map.of(),
            Map.of(switcherConflict.key(), 0),
            0));

    RouteProgressRegistry registry = new RouteProgressRegistry();
    registry.initFromTags("train-front", frontTags.properties(), frontRoute);
    registry.initFromTags("train-rear", rearTags.properties(), rearRoute);

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

    FakeTrain train = new FakeTrain(worldId, frontTags.properties(), false);
    service.handleSignalTick(train, false);

    SignalAspect aspect = registry.get("train-front").orElseThrow().lastSignal();
    assertEquals(SignalAspect.STOP, aspect);
    assertTrue(
        service.recentBlockerTrains("train-front", Duration.ofSeconds(30)).contains("train-rear"));
    assertTrue(
        occupancyManager.snapshotQueues().stream()
            .flatMap(snapshot -> snapshot.entries().stream())
            .anyMatch(entry -> entry.trainName().equalsIgnoreCase("train-rear")));
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
                    graphWithTwoEdges(a, b, c, 10, 2), Instant.now())));
    when(railGraphService.effectiveSpeedLimitBlocksPerSecond(any(), any(), any(), anyDouble()))
        .thenReturn(1000.0);

    RouteDefinitionCache routeDefinitions = mock(RouteDefinitionCache.class);
    when(routeDefinitions.findByCodes("op", "l1", "r1")).thenReturn(Optional.of(route));

    OccupancyManager occupancyManager = mockOccupancyManager();
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
  void holdPositionRetainsSwitcherConflictWhileInsideZone() {
    NodeId a = NodeId.of("A");
    NodeId switcher = NodeId.of("SW");
    NodeId exit = NodeId.of("M");
    NodeId b = NodeId.of("B");
    RouteDefinition route = new RouteDefinition(RouteId.of("r"), List.of(a, b), Optional.empty());
    TagStore tags =
        new TagStore(
            "train-1",
            "FTA_OPERATOR_CODE=op",
            "FTA_LINE_CODE=l1",
            "FTA_ROUTE_CODE=r1",
            "FTA_ROUTE_INDEX=0");
    UUID worldId = UUID.randomUUID();
    RailGraph graph = graphWithSwitcherExit(a, switcher, exit, b, 10, 10, 10);

    ConfigManager configManager = mock(ConfigManager.class);
    when(configManager.current()).thenReturn(testConfigView(20, 20.0));

    RailGraphService railGraphService = mock(RailGraphService.class);
    when(railGraphService.getSnapshot(worldId))
        .thenReturn(Optional.of(new RailGraphService.RailGraphSnapshot(graph, Instant.now())));
    when(railGraphService.effectiveSpeedLimitBlocksPerSecond(any(), any(), any(), anyDouble()))
        .thenReturn(1000.0);

    RouteDefinitionCache routeDefinitions = mock(RouteDefinitionCache.class);
    when(routeDefinitions.findByCodes("op", "l1", "r1")).thenReturn(Optional.of(route));

    SimpleOccupancyManager occupancyManager =
        new SimpleOccupancyManager(
            HeadwayRule.fixed(Duration.ZERO), SignalAspectPolicy.defaultPolicy());
    OccupancyResource switcherConflict = OccupancyResource.forConflict("switcher:SW");
    occupancyManager.acquire(
        new OccupancyRequest(
            "train-1",
            Optional.of(route.id()),
            Instant.now(),
            List.of(switcherConflict),
            Map.of(),
            Map.of(switcherConflict.key(), 0),
            0));
    occupancyManager.acquire(
        new OccupancyRequest(
            "front-train",
            Optional.empty(),
            Instant.now(),
            List.of(OccupancyResource.forNode(b)),
            Map.of()));

    RouteProgressRegistry registry = new RouteProgressRegistry();
    registry.initFromTags("train-1", tags.properties(), route);
    registry.updateLastPassedGraphNode("train-1", exit, Instant.now());

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

    service.handleSignalTick(new FakeTrain(worldId, tags.properties(), false), false);

    assertTrue(occupancyManager.getClaim(switcherConflict).isPresent());
    OccupancyDecision entrant =
        occupancyManager.canEnter(
            new OccupancyRequest(
                "entrant",
                Optional.empty(),
                Instant.now(),
                List.of(switcherConflict),
                Map.of(),
                Map.of(switcherConflict.key(), 0),
                0));
    assertFalse(entrant.allowed());
  }

  @Test
  void switcherClaimReleasedAfterTrainLeavesSwitcherZone() {
    NodeId a = NodeId.of("A");
    NodeId switcher = NodeId.of("SW");
    NodeId exit = NodeId.of("M");
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
    UUID worldId = UUID.randomUUID();
    PhysicalGraphFixture physicalGraph =
        withSyntheticPhysicalFootprints(
            graphWithSwitcherExit(a, switcher, exit, b, c, 10, 10, 10, 10), worldId);
    RailGraph graph = physicalGraph.graph();

    ConfigManager configManager = mock(ConfigManager.class);
    ConfigManager.ConfigView base = testConfigView(20, 20.0);
    when(configManager.current())
        .thenReturn(withRuntimeSettings(base, runtimeWithRearGuardEdges(base, 0)));

    RailGraphService railGraphService = mock(RailGraphService.class);
    when(railGraphService.getSnapshot(worldId))
        .thenReturn(Optional.of(new RailGraphService.RailGraphSnapshot(graph, Instant.now())));
    when(railGraphService.effectiveSpeedLimitBlocksPerSecond(any(), any(), any(), anyDouble()))
        .thenReturn(1000.0);

    RouteDefinitionCache routeDefinitions = mock(RouteDefinitionCache.class);
    when(routeDefinitions.findByCodes("op", "l1", "r1")).thenReturn(Optional.of(route));

    SimpleOccupancyManager occupancyManager =
        new SimpleOccupancyManager(
            HeadwayRule.fixed(Duration.ZERO), SignalAspectPolicy.defaultPolicy());
    OccupancyResource switcherConflict = OccupancyResource.forConflict("switcher:SW");
    occupancyManager.acquire(
        new OccupancyRequest(
            "train-1",
            Optional.of(route.id()),
            Instant.now(),
            List.of(switcherConflict),
            Map.of(),
            Map.of(switcherConflict.key(), 0),
            0));

    RouteProgressRegistry registry = new RouteProgressRegistry();
    registry.initFromTags("train-1", tags.properties(), route);
    registry.updateLastPassedGraphNode("train-1", b, Instant.now());

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
    train.liveRailFootprintCells =
        Optional.of(Set.of(physicalGraph.cellsByEdge().get(EdgeId.undirected(b, c))));
    service.handleSignalTick(train, false);

    assertTrue(occupancyManager.getClaim(switcherConflict).isEmpty());
  }

  @Test
  void eventAndPeriodicBuildSameRequestAfterIntermediateWaypoint() {
    NodeId a = NodeId.of("A");
    NodeId switcher = NodeId.of("SW");
    NodeId middle = NodeId.of("M");
    NodeId d = NodeId.of("D");
    RouteDefinition route = new RouteDefinition(RouteId.of("r"), List.of(a, d), Optional.empty());
    RailGraph graph = graphWithSwitcherExit(a, switcher, middle, d, 10, 10, 10);
    TagStore tags = new TagStore("train-1", "FTA_ROUTE_INDEX=0");
    RouteProgressRegistry registry = new RouteProgressRegistry();
    registry.initFromTags("train-1", tags.properties(), route);
    registry.updateLastPassedGraphNode("train-1", switcher, Instant.now());
    RuntimeDispatchService service =
        createMinimalService(
            mockOccupancyManager(), mock(RouteDefinitionCache.class), registry, new ArrayList<>());

    List<NodeId> eventWaypoints =
        service.resolveEffectiveWaypointsForEvent("train-1", route, 0, graph);
    List<NodeId> directionContext =
        service.resolveDirectionContextWaypointsForEvent("train-1", route);

    assertEquals(List.of(switcher, d), eventWaypoints);
    assertEquals(List.of(a, d), directionContext);
  }

  @Test
  /**
   * 前向扫描按展开路径计数边数；本例中的前车同样远在 caution 阈值之外，因此不降级。
   *
   * <p>断言随 {@code distanceToForwardTrainSignal} 的新契约更新：远处前车不再无条件限速。
   */
  void handleSignalTickForwardScanCountsExpandedPathEdges() {
    NodeId a = NodeId.of("A");
    NodeId m1 = NodeId.of("M1");
    NodeId m2 = NodeId.of("M2");
    NodeId m3 = NodeId.of("M3");
    NodeId d = NodeId.of("D");
    RouteDefinition route = new RouteDefinition(RouteId.of("r"), List.of(a, d), Optional.empty());
    TagStore tags =
        new TagStore(
            "train-1",
            "FTA_OPERATOR_CODE=op",
            "FTA_LINE_CODE=l1",
            "FTA_ROUTE_CODE=r1",
            "FTA_ROUTE_INDEX=0");
    TagStore aheadTags = new TagStore("train-ahead", "FTA_ROUTE_INDEX=1");
    UUID worldId = UUID.randomUUID();

    ConfigManager configManager = mock(ConfigManager.class);
    when(configManager.current()).thenReturn(testConfigView(20, 20.0));

    RailGraphService railGraphService = mock(RailGraphService.class);
    when(railGraphService.getSnapshot(worldId))
        .thenReturn(
            Optional.of(
                new RailGraphService.RailGraphSnapshot(
                    graphWithConflictFreeLinearPath(List.of(a, m1, m2, m3, d), 10),
                    Instant.now())));
    when(railGraphService.effectiveSpeedLimitBlocksPerSecond(any(), any(), any(), anyDouble()))
        .thenReturn(1000.0);

    RouteDefinitionCache routeDefinitions = mock(RouteDefinitionCache.class);
    when(routeDefinitions.findByCodes("op", "l1", "r1")).thenReturn(Optional.of(route));

    OccupancyManager occupancyManager = mockOccupancyManager();
    when(occupancyManager.canEnter(any())).thenAnswer(allowProceed());
    when(occupancyManager.acquire(any())).thenAnswer(allowProceed());

    OccupancyResource farNode = OccupancyResource.forNode(d);
    OccupancyClaim farClaim =
        new OccupancyClaim(
            farNode,
            "train-ahead",
            Optional.of(route.id()),
            Instant.now(),
            Duration.ZERO,
            Optional.empty());
    when(occupancyManager.getClaim(any()))
        .thenAnswer(
            inv -> {
              OccupancyResource resource = inv.getArgument(0);
              if (farNode.equals(resource)) {
                return Optional.of(farClaim);
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
    assertEquals(SignalAspect.PROCEED, aspect);
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

    OccupancyManager occupancyManager = mockOccupancyManager();
    when(occupancyManager.canEnter(any())).thenAnswer(allowProceed());
    when(occupancyManager.acquire(any())).thenAnswer(allowProceed());

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
    verify(occupancyManager, atLeastOnce()).acquire(requestCaptor.capture());
    boolean hasNonEmptyRequest =
        requestCaptor.getAllValues().stream().anyMatch(req -> !req.resourceList().isEmpty());
    assertTrue(hasNonEmptyRequest);
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
    OccupancyManager occupancyManager = mockOccupancyManager();

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
  void handleTrainRemovedRefreshesScheduledDeadlockSurvivorAfterRelease() {
    String trainName = "leader";
    RouteDefinition route =
        new RouteDefinition(
            RouteId.of("r"), List.of(NodeId.of("A"), NodeId.of("B")), Optional.empty());
    TagStore tags = new TagStore(trainName, "FTA_ROUTE_INDEX=0");

    ConfigManager configManager = mock(ConfigManager.class);
    when(configManager.current()).thenReturn(testConfigView(20, 20.0));

    RouteProgressRegistry registry = new RouteProgressRegistry();
    registry.initFromTags(trainName, tags.properties(), route);
    RuntimeDispatchService service =
        spy(
            new RuntimeDispatchService(
                mockOccupancyManager(),
                mock(RailGraphService.class),
                mock(RouteDefinitionCache.class),
                registry,
                mock(SignNodeRegistry.class),
                mock(LayoverRegistry.class),
                new DwellRegistry(),
                configManager,
                null,
                new TrainConfigResolver(),
                null));
    doReturn(RuntimeDispatchService.SignalRefreshResult.unresolved("survivor", "test"))
        .when(service)
        .refreshSignalByName("survivor");

    service.scheduleSurvivorRefreshAfterTrainRemoved("leader", "survivor");
    service.handleTrainRemoved("leader");

    verify(service).refreshSignalByName("survivor");
  }

  @Test
  void rebuildOccupancySnapshotAtomicallyReplacesOrphanClaims() {
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
                    startupPhysicalGraph(worldId), Instant.now())));
    when(railGraphService.effectiveSpeedLimitBlocksPerSecond(any(), any(), any(), anyDouble()))
        .thenReturn(1000.0);

    RouteDefinitionCache routeDefinitions = mock(RouteDefinitionCache.class);
    when(routeDefinitions.findByCodes("op", "l1", "r1")).thenReturn(Optional.of(route));

    SimpleOccupancyManager occupancyManager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    assertTrue(
        occupancyManager
            .acquire(
                new OccupancyRequest(
                    "ghost",
                    Optional.empty(),
                    Instant.now(),
                    List.of(new OccupancyResource(ResourceKind.EDGE, "A~B")),
                    Map.of()))
            .allowed());

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

    assertTrue(service.rebuildOccupancySnapshot(List.of(train)));

    assertFalse(
        occupancyManager.snapshotClaims().stream()
            .anyMatch(claim -> claim.trainName().equals("ghost")));
    assertTrue(
        occupancyManager.snapshotClaims().stream()
            .anyMatch(claim -> claim.trainName().equals("train-1")));
  }

  @Test
  void startupReconstructionGateStopsEventTickBeforeFieldScan() {
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
    RuntimeDispatchService service =
        startupReconstructionService(worldId, route, mockOccupancyManager());
    FakeTrain train = new FakeTrain(worldId, tags.properties(), false);

    service.beginStartupOccupancyReconstruction();
    service.handleSignalTick(train, false);

    assertTrue(train.hardStopCalls > 0);
    assertEquals(0, train.launchCalls);
    assertTrue(service.isMovementInhibited("train-1"));
  }

  @Test
  void startupReconstructionEpochInvalidatesInFlightSpawnFenceUntilReady() {
    RouteDefinition route =
        new RouteDefinition(
            RouteId.of("r"), List.of(NodeId.of("A"), NodeId.of("B")), Optional.empty());
    UUID worldId = UUID.randomUUID();
    SimpleOccupancyManager occupancyManager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    List<String> debugMessages = new ArrayList<>();
    RuntimeDispatchService service =
        startupReconstructionService(worldId, route, occupancyManager, debugMessages::add);

    OptionalLong initialEpoch = service.captureReadyStartupRecoveryEpoch();
    assertTrue(initialEpoch.isPresent());

    service.beginStartupOccupancyReconstruction();

    assertTrue(service.captureReadyStartupRecoveryEpoch().isEmpty());
    assertFalse(service.isStartupRecoveryEpochReady(initialEpoch.getAsLong()));

    assertTrue(service.rebuildOccupancySnapshot(List.of()));
    OptionalLong rebuiltEpoch = service.captureReadyStartupRecoveryEpoch();
    assertTrue(rebuiltEpoch.isPresent());
    assertFalse(rebuiltEpoch.getAsLong() == initialEpoch.getAsLong());
    assertTrue(service.isStartupRecoveryEpochReady(rebuiltEpoch.getAsLong()));
  }

  @Test
  void startupReconstructionGateBlocksDepartureBeforeAuthorization() {
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
    SimpleOccupancyManager occupancyManager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    List<String> debugMessages = new ArrayList<>();
    RuntimeDispatchService service =
        startupReconstructionService(worldId, route, occupancyManager, debugMessages::add);
    FakeTrain train = new FakeTrain(worldId, tags.properties(), false);
    SignNodeDefinition definition =
        new SignNodeDefinition(
            NodeId.of("A"), NodeType.STATION, Optional.empty(), Optional.empty());

    service.beginStartupOccupancyReconstruction();

    assertFalse(service.checkDeparture(train, definition));
    assertTrue(train.hardStopCalls > 0);
    assertEquals(0, train.launchCalls);
    assertTrue(occupancyManager.snapshotClaims().isEmpty());
    verify(tags.properties(), never()).setDestination(anyString());
  }

  @Test
  void startupReconstructionGateBlocksStationArrivalBeforeProgressMutation() {
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
    RuntimeDispatchService service =
        startupReconstructionService(worldId, route, mockOccupancyManager());
    FakeTrain train = new FakeTrain(worldId, tags.properties(), false);
    SignNodeDefinition definition =
        new SignNodeDefinition(
            NodeId.of("A"), NodeType.STATION, Optional.empty(), Optional.empty());

    service.beginStartupOccupancyReconstruction();
    service.handleStationArrival(train, definition);

    assertTrue(train.hardStopCalls > 0);
    verify(tags.properties(), never()).clearDestinationRoute();
    verify(tags.properties(), never()).setDestination(anyString());
  }

  @Test
  void startupReconstructionGateBlocksProgressTriggerBeforeAuthorization() {
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
    SimpleOccupancyManager occupancyManager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    RuntimeDispatchService service = startupReconstructionService(worldId, route, occupancyManager);
    FakeTrain train = new FakeTrain(worldId, tags.properties(), false);
    com.bergerkiller.bukkit.tc.events.SignActionEvent event =
        mock(com.bergerkiller.bukkit.tc.events.SignActionEvent.class);
    org.bukkit.World world = mock(org.bukkit.World.class);
    when(world.getUID()).thenReturn(worldId);
    when(event.getWorld()).thenReturn(world);
    SignNodeDefinition definition =
        new SignNodeDefinition(
            NodeId.of("A"), NodeType.WAYPOINT, Optional.empty(), Optional.empty());

    service.beginStartupOccupancyReconstruction();
    service.handleProgressTrigger(train, event, definition);

    assertTrue(train.hardStopCalls > 0);
    assertEquals(0, train.launchCalls);
    assertTrue(occupancyManager.snapshotClaims().isEmpty());
    verify(tags.properties(), never()).setDestination(anyString());
  }

  @Test
  void startupReconstructionFailsClosedForResidualFtaTrainWithoutRouteEvidence() {
    RouteDefinition route =
        new RouteDefinition(
            RouteId.of("r"), List.of(NodeId.of("A"), NodeId.of("B")), Optional.empty());
    UUID worldId = UUID.randomUUID();
    SimpleOccupancyManager occupancyManager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    RuntimeDispatchService service = startupReconstructionService(worldId, route, occupancyManager);
    FakeTrain residual =
        new FakeTrain(
            worldId,
            new TagStore("residual", "FTA_TRAIN_NAME=residual", "FTA_ROUTE_INDEX=0").properties(),
            false);

    assertFalse(service.rebuildOccupancySnapshot(List.of(residual)));

    assertTrue(residual.hardStopCalls > 0);
    assertTrue(service.isMovementInhibited("residual"));
    assertTrue(occupancyManager.snapshotClaims().isEmpty());
  }

  @Test
  void startupSignalGateStopsPartiallyTaggedFtaTrainBeforeManagedFilter() {
    RouteDefinition route =
        new RouteDefinition(
            RouteId.of("r"), List.of(NodeId.of("A"), NodeId.of("B")), Optional.empty());
    UUID worldId = UUID.randomUUID();
    RuntimeDispatchService service =
        startupReconstructionService(worldId, route, mockOccupancyManager());
    FakeTrain residual =
        new FakeTrain(
            worldId,
            new TagStore("residual", "FTA_TRAIN_NAME=residual", "FTA_ROUTE_INDEX=0").properties(),
            false);

    service.beginStartupOccupancyReconstruction();
    service.handleSignalTick(residual, false);

    assertTrue(residual.hardStopCalls > 0);
    assertTrue(service.isMovementInhibited("residual"));
    assertEquals(0, residual.launchCalls);
  }

  @Test
  void startupReconstructionFailsClosedWithoutCompleteLiveRailFootprint() {
    RouteDefinition route =
        new RouteDefinition(
            RouteId.of("r"), List.of(NodeId.of("A"), NodeId.of("B")), Optional.empty());
    UUID worldId = UUID.randomUUID();
    SimpleOccupancyManager occupancyManager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    List<String> debugMessages = new ArrayList<>();
    RuntimeDispatchService service =
        startupReconstructionService(worldId, route, occupancyManager, debugMessages::add);
    FakeTrain train =
        new FakeTrain(
            worldId,
            new TagStore(
                    "train-1",
                    "FTA_OPERATOR_CODE=op",
                    "FTA_LINE_CODE=l1",
                    "FTA_ROUTE_CODE=r1",
                    "FTA_ROUTE_INDEX=0")
                .properties(),
            false);
    train.liveRailFootprintCells = Optional.empty();

    assertFalse(service.rebuildOccupancySnapshot(List.of(train)));

    assertTrue(train.hardStopCalls > 0);
    assertTrue(occupancyManager.snapshotClaims().isEmpty());
    assertTrue(service.isMovementInhibited("train-1"));
    assertTrue(
        debugMessages.stream()
            .anyMatch(
                message ->
                    message.contains("SMART_LIVE_RAIL_FOOTPRINT_OBSERVATION")
                        && message.contains("reason=LEGACY_UNAVAILABLE")),
        debugMessages::toString);
  }

  @Test
  void startupReconstructionFailsClosedWhenLiveRailApiHasLinkageError() {
    RouteDefinition route =
        new RouteDefinition(
            RouteId.of("r"), List.of(NodeId.of("A"), NodeId.of("B")), Optional.empty());
    UUID worldId = UUID.randomUUID();
    SimpleOccupancyManager occupancyManager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    RuntimeDispatchService service = startupReconstructionService(worldId, route, occupancyManager);
    FakeTrain train =
        new FakeTrain(
            worldId,
            new TagStore(
                    "train-1",
                    "FTA_OPERATOR_CODE=op",
                    "FTA_LINE_CODE=l1",
                    "FTA_ROUTE_CODE=r1",
                    "FTA_ROUTE_INDEX=0")
                .properties(),
            false);
    train.liveRailFootprintFailure = new NoSuchMethodError("TrainCarts ABI mismatch");

    assertFalse(service.rebuildOccupancySnapshot(List.of(train)));

    assertTrue(train.hardStopCalls > 0);
    assertTrue(occupancyManager.snapshotClaims().isEmpty());
    assertTrue(service.isMovementInhibited("train-1"));
  }

  @Test
  void startupSparseZoneClaimCannotReleaseBeforeLiveRailProofClearsIt() {
    RouteDefinition route =
        new RouteDefinition(
            RouteId.of("r"), List.of(NodeId.of("A"), NodeId.of("B")), Optional.empty());
    UUID worldId = UUID.randomUUID();
    SimpleOccupancyManager occupancyManager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    RailGraph graph = startupPhysicalCrossingGraph(worldId);
    RuntimeDispatchService service =
        startupReconstructionService(worldId, route, occupancyManager, message -> {}, graph);
    FakeTrain train =
        new FakeTrain(
            worldId,
            new TagStore(
                    "train-1",
                    "FTA_OPERATOR_CODE=op",
                    "FTA_LINE_CODE=l1",
                    "FTA_ROUTE_CODE=r1",
                    "FTA_ROUTE_INDEX=0")
                .properties(),
            false);
    OccupancyResource logicalEdge =
        OccupancyResource.forEdge(EdgeId.undirected(NodeId.of("A"), NodeId.of("B")));
    String zoneKey =
        ((RailGraphInterlockingSupport) graph)
            .interlockingState()
            .exactZones()
            .keySet()
            .iterator()
            .next();
    OccupancyResource physicalZone = OccupancyResource.forConflict(zoneKey);

    assertTrue(service.rebuildOccupancySnapshot(List.of(train)));
    service.releaseResourcesNotInRequest("train-1", List.of(), Set.of());

    Map<OccupancyResource, ClaimRole> retainedClaims =
        occupancyManager.snapshotClaims().stream()
            .filter(claim -> claim.trainName().equals("train-1"))
            .collect(
                java.util.stream.Collectors.toMap(OccupancyClaim::resource, OccupancyClaim::role));
    assertFalse(retainedClaims.containsKey(logicalEdge));
    assertEquals(ClaimRole.PHYSICAL_FOOTPRINT, retainedClaims.get(physicalZone));
  }

  @Test
  void releaseResourcesNotInRequestShrinksCanonicalOwnerThroughSplitAlias() {
    SimpleOccupancyManager occupancyManager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    OccupancyResource retained = OccupancyResource.forNode(NodeId.of("CURRENT"));
    OccupancyResource stale = OccupancyResource.forNode(NodeId.of("BEHIND"));
    OccupancyRequest canonicalRequest =
        new OccupancyRequest(
            "train-main",
            Optional.empty(),
            Instant.parse("2026-01-01T00:00:00Z"),
            List.of(retained, stale),
            Map.of());
    assertTrue(occupancyManager.acquire(canonicalRequest).allowed());
    RuntimeDispatchService service = createMinimalService(occupancyManager, new ArrayList<>());

    List<OccupancyResource> released =
        service.releaseResourcesNotInRequest("train-main~a", List.of(retained), Set.of());

    assertEquals(List.of(stale), released);
    assertEquals("train-main", occupancyManager.getClaim(retained).orElseThrow().trainName());
    assertTrue(occupancyManager.getClaim(stale).isEmpty());
  }

  @Test
  void lateLoadedTrainHydratesAtomicallyBeforeNormalAuthorization() {
    RouteDefinition route =
        new RouteDefinition(
            RouteId.of("r"), List.of(NodeId.of("A"), NodeId.of("B")), Optional.empty());
    UUID worldId = UUID.randomUUID();
    SimpleOccupancyManager occupancyManager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    RuntimeDispatchService service = startupReconstructionService(worldId, route, occupancyManager);
    FakeTrain existing =
        new FakeTrain(
            worldId,
            new TagStore(
                    "existing",
                    "FTA_OPERATOR_CODE=op",
                    "FTA_LINE_CODE=l1",
                    "FTA_ROUTE_CODE=r1",
                    "FTA_ROUTE_INDEX=0")
                .properties(),
            false);
    FakeTrain late =
        new FakeTrain(
            worldId,
            new TagStore(
                    "late",
                    "FTA_OPERATOR_CODE=op",
                    "FTA_LINE_CODE=l1",
                    "FTA_ROUTE_CODE=r1",
                    "FTA_ROUTE_INDEX=0")
                .properties(),
            false);
    OccupancyResource physicalEdge =
        OccupancyResource.forEdge(EdgeId.undirected(NodeId.of("A"), NodeId.of("B")));

    assertTrue(service.rebuildOccupancySnapshot(List.of(existing)));
    service.handleSignalTick(late, false);

    assertTrue(late.hardStopCalls > 0);
    assertEquals(0, late.launchCalls);
    assertEquals(
        Set.of("existing", "late"),
        occupancyManager.snapshotClaims().stream()
            .filter(claim -> claim.resource().equals(physicalEdge))
            .map(OccupancyClaim::trainName)
            .collect(java.util.stream.Collectors.toSet()));
  }

  @Test
  void unresolvedLateLoadedTrainClosesGlobalGateAndRequestsRecovery() {
    RouteDefinition route =
        new RouteDefinition(
            RouteId.of("r"), List.of(NodeId.of("A"), NodeId.of("B")), Optional.empty());
    UUID worldId = UUID.randomUUID();
    SimpleOccupancyManager occupancyManager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    RuntimeDispatchService service = startupReconstructionService(worldId, route, occupancyManager);
    FakeTrain existing =
        new FakeTrain(
            worldId,
            new TagStore(
                    "existing",
                    "FTA_OPERATOR_CODE=op",
                    "FTA_LINE_CODE=l1",
                    "FTA_ROUTE_CODE=r1",
                    "FTA_ROUTE_INDEX=0")
                .properties(),
            false);
    FakeTrain unresolved =
        new FakeTrain(
            worldId,
            new TagStore(
                    "unresolved",
                    "FTA_OPERATOR_CODE=op",
                    "FTA_LINE_CODE=l1",
                    "FTA_ROUTE_CODE=r1",
                    "FTA_ROUTE_INDEX=0")
                .properties(),
            false);
    unresolved.liveRailFootprintCells = Optional.empty();
    AtomicBoolean recoveryRequested = new AtomicBoolean();
    service.setStartupRecoveryRequestedListener(() -> recoveryRequested.set(true));

    assertTrue(service.rebuildOccupancySnapshot(List.of(existing)));
    int existingStopsBeforeLateLoad = existing.hardStopCalls;
    service.handleSignalTick(unresolved, false);
    service.handleSignalTick(existing, false);

    assertTrue(recoveryRequested.get());
    assertTrue(unresolved.hardStopCalls > 0);
    assertTrue(existing.hardStopCalls > existingStopsBeforeLateLoad);
    assertEquals(0, unresolved.launchCalls);
  }

  @Test
  void legacyLiveRailFootprintAdapterExposesUnavailableReason() {
    FakeTrain train =
        new FakeTrain(
            UUID.randomUUID(), new TagStore("train-1", "FTA_ROUTE_INDEX=0").properties(), false);
    train.liveRailFootprintCells = Optional.empty();

    LiveRailFootprintObservation unavailable = train.observeLiveRailFootprint();

    assertFalse(unavailable.available());
    assertEquals(
        LiveRailFootprintObservation.FailureReason.LEGACY_UNAVAILABLE, unavailable.failureReason());

    Set<RailFootprintCell> cells = Set.of(new RailFootprintCell(1, 64, 1));
    train.liveRailFootprintCells = Optional.of(cells);

    LiveRailFootprintObservation available = train.observeLiveRailFootprint();

    assertTrue(available.available());
    assertEquals(cells, available.cells().orElseThrow());
    assertEquals(LiveRailFootprintObservation.FailureReason.NONE, available.failureReason());
  }

  @Test
  void expectedDepotSpawnRegistersPhysicalIdentityBeforeFirstSignalRefresh() {
    RouteDefinition route =
        new RouteDefinition(
            RouteId.of("r"), List.of(NodeId.of("A"), NodeId.of("B")), Optional.empty());
    UUID worldId = UUID.randomUUID();
    SimpleOccupancyManager occupancyManager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    RailGraph graph = startupPhysicalCrossingGraph(worldId);
    List<String> debugMessages = new ArrayList<>();
    RuntimeDispatchService service =
        startupReconstructionService(worldId, route, occupancyManager, debugMessages::add, graph);
    FakeTrain train =
        new FakeTrain(
            worldId,
            new TagStore(
                    "train-1",
                    "FTA_OPERATOR_CODE=op",
                    "FTA_LINE_CODE=l1",
                    "FTA_ROUTE_CODE=r1",
                    "FTA_ROUTE_INDEX=0",
                    TrainSpawnTagInitializer.TAG_SPAWN_ORIGIN_PENDING + "=true")
                .properties(),
            false);
    train.liveRailFootprintCells = Optional.empty();
    OccupancyResource edge =
        OccupancyResource.forEdge(EdgeId.undirected(NodeId.of("A"), NodeId.of("B")));
    OccupancyResource zone =
        OccupancyResource.forConflict(
            ((RailGraphInterlockingSupport) graph)
                .interlockingState()
                .exactZones()
                .keySet()
                .iterator()
                .next());
    OccupancyRequest spawnAuthority =
        new OccupancyRequest(
            "train-1",
            Optional.of(route.id()),
            Instant.now(),
            List.of(edge, zone),
            Map.of(),
            Map.of(),
            10,
            AuthorizationPurpose.DEPOT_SPAWN,
            Map.of(),
            Map.of(
                edge, ResourceIntent.MOVEMENT_REQUIRED,
                zone, ResourceIntent.MOVEMENT_REQUIRED));
    assertTrue(service.rebuildOccupancySnapshot(List.of()));
    long recoveryEpoch = service.captureReadyStartupRecoveryEpoch().orElseThrow();
    assertTrue(occupancyManager.acquire(spawnAuthority).allowed());
    AtomicBoolean recoveryRequested = new AtomicBoolean();
    service.setStartupRecoveryRequestedListener(() -> recoveryRequested.set(true));

    assertFalse(
        service.registerExpectedMaterializedSpawn(train, spawnAuthority, recoveryEpoch + 1));
    assertEquals(0, train.hardStopCalls);
    assertTrue(service.registerExpectedMaterializedSpawn(train, spawnAuthority, recoveryEpoch));
    assertTrue(service.isCurrentMaterializedSpawnTransactionIdentity(train));
    FakeTrain duplicate =
        new FakeTrain(
            worldId,
            new TagStore(
                    "train-1",
                    "FTA_OPERATOR_CODE=op",
                    "FTA_LINE_CODE=l1",
                    "FTA_ROUTE_CODE=r1",
                    "FTA_ROUTE_INDEX=0",
                    TrainSpawnTagInitializer.TAG_SPAWN_ORIGIN_PENDING + "=true")
                .properties(),
            false);
    assertFalse(
        service.registerExpectedMaterializedSpawn(duplicate, spawnAuthority, recoveryEpoch));
    assertFalse(service.isCurrentMaterializedSpawnTransactionIdentity(duplicate));
    assertTrue(duplicate.hardStopCalls > 0);
    service.handleSignalTick(train, false);
    assertTrue(train.hardStopCalls > 0);
    assertEquals(0, train.launchCalls);
    assertEquals(
        RuntimeDispatchService.ExpectedMaterializedSpawnStatus.PROVISIONAL,
        service.expectedMaterializedSpawnStatus(train, recoveryEpoch));
    assertFalse(recoveryRequested.get());
    assertTrue(service.captureReadyStartupRecoveryEpoch().isPresent());

    train.liveRailFootprintCells = Optional.of(Set.of(new RailFootprintCell(0, 64, 0)));
    service.handleSignalTick(train, false);
    long waitingFootprintLogs =
        debugMessages.stream()
            .filter(message -> message.contains("result=waiting-footprint"))
            .count();
    train.liveRailFootprintCells = Optional.empty();
    service.handleSignalTick(train, false);
    service.releaseResourcesNotInRequest("train-1", List.of(), Set.of());

    assertFalse(recoveryRequested.get());
    assertTrue(service.captureReadyStartupRecoveryEpoch().isPresent());
    assertTrue(
        debugMessages.stream().anyMatch(message -> message.contains("result=promoted")),
        debugMessages::toString);
    assertEquals(
        RuntimeDispatchService.ExpectedMaterializedSpawnStatus.PROMOTED,
        service.expectedMaterializedSpawnStatus(train, recoveryEpoch));
    assertTrue(service.isCurrentMaterializedSpawnTransactionIdentity(train));
    assertEquals(
        waitingFootprintLogs,
        debugMessages.stream()
            .filter(message -> message.contains("result=waiting-footprint"))
            .count());
    assertTrue(
        occupancyManager.snapshotClaims().stream()
            .anyMatch(
                claim ->
                    claim.resource().equals(zone)
                        && TrainNameNormalizer.sameLogicalTrain(
                            claim.trainName(), spawnAuthority.trainName())));
  }

  @Test
  void removingExpectedDepotSpawnCleansProvisionalStateWithoutStartingGlobalRecovery() {
    RouteDefinition route =
        new RouteDefinition(
            RouteId.of("r"), List.of(NodeId.of("A"), NodeId.of("B")), Optional.empty());
    UUID worldId = UUID.randomUUID();
    SimpleOccupancyManager occupancyManager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    RuntimeDispatchService service = startupReconstructionService(worldId, route, occupancyManager);
    FakeTrain train =
        new FakeTrain(
            worldId,
            new TagStore(
                    "train-1",
                    "FTA_OPERATOR_CODE=op",
                    "FTA_LINE_CODE=l1",
                    "FTA_ROUTE_CODE=r1",
                    "FTA_ROUTE_INDEX=0",
                    TrainSpawnTagInitializer.TAG_SPAWN_ORIGIN_PENDING + "=true")
                .properties(),
            false);
    train.liveRailFootprintCells = Optional.empty();
    OccupancyResource edge =
        OccupancyResource.forEdge(EdgeId.undirected(NodeId.of("A"), NodeId.of("B")));
    OccupancyRequest spawnAuthority =
        new OccupancyRequest(
            "train-1",
            Optional.of(route.id()),
            Instant.now(),
            List.of(edge),
            Map.of(),
            Map.of(),
            10,
            AuthorizationPurpose.DEPOT_SPAWN,
            Map.of(),
            Map.of(edge, ResourceIntent.MOVEMENT_REQUIRED));
    assertTrue(service.rebuildOccupancySnapshot(List.of()));
    long recoveryEpoch = service.captureReadyStartupRecoveryEpoch().orElseThrow();
    assertTrue(occupancyManager.acquire(spawnAuthority).allowed());
    AtomicBoolean recoveryRequested = new AtomicBoolean();
    service.setStartupRecoveryRequestedListener(() -> recoveryRequested.set(true));
    assertTrue(service.registerExpectedMaterializedSpawn(train, spawnAuthority, recoveryEpoch));

    service.handleTrainRemoved(train);

    assertFalse(recoveryRequested.get());
    assertTrue(service.isStartupRecoveryEpochReady(recoveryEpoch));
    assertTrue(occupancyManager.snapshotClaims().isEmpty());
  }

  @Test
  void recoveryStartedDuringExpectedSpawnHydrationCannotPublishStalePromotion() {
    RouteDefinition route =
        new RouteDefinition(
            RouteId.of("r"), List.of(NodeId.of("A"), NodeId.of("B")), Optional.empty());
    UUID worldId = UUID.randomUUID();
    SimpleOccupancyManager delegate =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    OccupancyManager occupancyManager =
        mock(
            OccupancyManager.class,
            withSettings()
                .extraInterfaces(
                    AuthorityHandoffSupport.class,
                    StartupOccupancyReconstructionSupport.class,
                    PhysicalFootprintHydrationSupport.class)
                .defaultAnswer(org.mockito.AdditionalAnswers.delegatesTo(delegate)));
    List<String> debugMessages = new ArrayList<>();
    RuntimeDispatchService service =
        startupReconstructionService(
            worldId, route, occupancyManager, debugMessages::add, startupPhysicalGraph(worldId));
    java.util.concurrent.atomic.AtomicReference<RuntimeDispatchService> serviceRef =
        new java.util.concurrent.atomic.AtomicReference<>(service);
    PhysicalFootprintHydrationSupport hydrationSupport =
        (PhysicalFootprintHydrationSupport) occupancyManager;
    when(hydrationSupport.replaceTrainPhysicalFootprint(any()))
        .thenAnswer(
            invocation -> {
              StartupOccupancyReconstructionSupport.ReconstructionResult result =
                  delegate.replaceTrainPhysicalFootprint(invocation.getArgument(0));
              serviceRef.get().beginStartupOccupancyReconstruction();
              return result;
            });
    FakeTrain train =
        new FakeTrain(
            worldId,
            new TagStore(
                    "train-1",
                    "FTA_OPERATOR_CODE=op",
                    "FTA_LINE_CODE=l1",
                    "FTA_ROUTE_CODE=r1",
                    "FTA_ROUTE_INDEX=0",
                    TrainSpawnTagInitializer.TAG_SPAWN_ORIGIN_PENDING + "=true")
                .properties(),
            false);
    train.liveRailFootprintCells = Optional.of(Set.of(new RailFootprintCell(0, 64, 0)));
    OccupancyResource edge =
        OccupancyResource.forEdge(EdgeId.undirected(NodeId.of("A"), NodeId.of("B")));
    OccupancyRequest spawnAuthority =
        new OccupancyRequest(
            "train-1",
            Optional.of(route.id()),
            Instant.now(),
            List.of(edge),
            Map.of(),
            Map.of(),
            10,
            AuthorizationPurpose.DEPOT_SPAWN,
            Map.of(),
            Map.of(edge, ResourceIntent.MOVEMENT_REQUIRED));
    assertTrue(service.rebuildOccupancySnapshot(List.of()));
    long recoveryEpoch = service.captureReadyStartupRecoveryEpoch().orElseThrow();
    assertTrue(occupancyManager.acquire(spawnAuthority).allowed());
    assertTrue(service.registerExpectedMaterializedSpawn(train, spawnAuthority, recoveryEpoch));

    service.handleSignalTick(train, false);

    assertFalse(service.isStartupRecoveryEpochReady(recoveryEpoch));
    assertTrue(train.hardStopCalls > 0);
    assertEquals(0, train.launchCalls);
    assertTrue(
        debugMessages.stream()
            .anyMatch(message -> message.contains("result=stale-after-hydration-commit")),
        debugMessages::toString);
    assertFalse(
        debugMessages.stream().anyMatch(message -> message.contains("result=promoted")),
        debugMessages::toString);
  }

  @Test
  void duplicatePhysicalGroupWithSameLogicalOwnerClosesGlobalGateWithoutReplacingFootprint() {
    RouteDefinition route =
        new RouteDefinition(
            RouteId.of("r"), List.of(NodeId.of("A"), NodeId.of("B")), Optional.empty());
    UUID worldId = UUID.randomUUID();
    SimpleOccupancyManager occupancyManager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    List<String> debugMessages = new ArrayList<>();
    RuntimeDispatchService service =
        startupReconstructionService(worldId, route, occupancyManager, debugMessages::add);
    FakeTrain existing =
        new FakeTrain(
            worldId,
            new TagStore(
                    "shared-owner",
                    "FTA_OPERATOR_CODE=op",
                    "FTA_LINE_CODE=l1",
                    "FTA_ROUTE_CODE=r1",
                    "FTA_ROUTE_INDEX=0")
                .properties(),
            false);
    FakeTrain duplicate =
        new FakeTrain(
            worldId,
            new TagStore(
                    "shared-owner",
                    "FTA_OPERATOR_CODE=op",
                    "FTA_LINE_CODE=l1",
                    "FTA_ROUTE_CODE=r1",
                    "FTA_ROUTE_INDEX=0")
                .properties(),
            false);
    AtomicBoolean recoveryRequested = new AtomicBoolean();
    service.setStartupRecoveryRequestedListener(() -> recoveryRequested.set(true));

    assertTrue(service.rebuildOccupancySnapshot(List.of(existing)));
    long versionBeforeDuplicate = occupancyManager.version();

    service.handleSignalTick(duplicate, false);

    assertTrue(recoveryRequested.get());
    assertTrue(duplicate.hardStopCalls > 0);
    assertEquals(0, duplicate.launchCalls);
    assertEquals(versionBeforeDuplicate, occupancyManager.version());
    assertTrue(
        debugMessages.stream()
            .anyMatch(message -> message.contains("SMART_DUPLICATE_LOGICAL_OWNER_IDENTITY")),
        debugMessages::toString);
  }

  @Test
  void removingOldSameNameGroupDoesNotClearSurvivorPhysicalSnapshot() {
    RouteDefinition route =
        new RouteDefinition(
            RouteId.of("r"), List.of(NodeId.of("A"), NodeId.of("B")), Optional.empty());
    UUID worldId = UUID.randomUUID();
    SimpleOccupancyManager occupancyManager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    RuntimeDispatchService service = startupReconstructionService(worldId, route, occupancyManager);
    FakeTrain survivor =
        new FakeTrain(
            worldId,
            new TagStore(
                    "shared-owner",
                    "FTA_OPERATOR_CODE=op",
                    "FTA_LINE_CODE=l1",
                    "FTA_ROUTE_CODE=r1",
                    "FTA_ROUTE_INDEX=0")
                .properties(),
            false);
    FakeTrain removedOldGroup =
        new FakeTrain(
            worldId,
            new TagStore(
                    "shared-owner",
                    "FTA_OPERATOR_CODE=op",
                    "FTA_LINE_CODE=l1",
                    "FTA_ROUTE_CODE=r1",
                    "FTA_ROUTE_INDEX=0")
                .properties(),
            false);
    AtomicBoolean recoveryRequested = new AtomicBoolean();
    service.setStartupRecoveryRequestedListener(() -> recoveryRequested.set(true));

    assertTrue(service.rebuildOccupancySnapshot(List.of(survivor)));
    List<OccupancyClaim> claimsBeforeRemoval = occupancyManager.snapshotClaims();
    long versionBeforeRemoval = occupancyManager.version();

    service.handleTrainRemoved(removedOldGroup);

    assertTrue(recoveryRequested.get());
    assertEquals(claimsBeforeRemoval, occupancyManager.snapshotClaims());
    assertEquals(versionBeforeRemoval, occupancyManager.version());
  }

  @Test
  void latePhysicalHydrationExceptionClosesGlobalGateAndRequestsRecovery() {
    RouteDefinition route =
        new RouteDefinition(
            RouteId.of("r"), List.of(NodeId.of("A"), NodeId.of("B")), Optional.empty());
    UUID worldId = UUID.randomUUID();
    SimpleOccupancyManager delegate =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    OccupancyManager occupancyManager =
        mock(
            OccupancyManager.class,
            withSettings()
                .extraInterfaces(
                    StartupOccupancyReconstructionSupport.class,
                    PhysicalFootprintHydrationSupport.class)
                .defaultAnswer(org.mockito.AdditionalAnswers.delegatesTo(delegate)));
    PhysicalFootprintHydrationSupport hydrationSupport =
        (PhysicalFootprintHydrationSupport) occupancyManager;
    when(hydrationSupport.replaceTrainPhysicalFootprint(any()))
        .thenThrow(new IllegalStateException("tracker-unavailable"));
    List<String> debugMessages = new ArrayList<>();
    RuntimeDispatchService service =
        startupReconstructionService(worldId, route, occupancyManager, debugMessages::add);
    FakeTrain existing =
        new FakeTrain(
            worldId,
            new TagStore(
                    "existing",
                    "FTA_OPERATOR_CODE=op",
                    "FTA_LINE_CODE=l1",
                    "FTA_ROUTE_CODE=r1",
                    "FTA_ROUTE_INDEX=0")
                .properties(),
            false);
    FakeTrain late =
        new FakeTrain(
            worldId,
            new TagStore(
                    "late",
                    "FTA_OPERATOR_CODE=op",
                    "FTA_LINE_CODE=l1",
                    "FTA_ROUTE_CODE=r1",
                    "FTA_ROUTE_INDEX=0")
                .properties(),
            false);
    AtomicBoolean recoveryRequested = new AtomicBoolean();
    service.setStartupRecoveryRequestedListener(() -> recoveryRequested.set(true));

    assertTrue(service.rebuildOccupancySnapshot(List.of(existing)));
    int existingStopsBeforeLateLoad = existing.hardStopCalls;

    service.handleSignalTick(late, false);
    service.handleSignalTick(existing, false);

    assertTrue(recoveryRequested.get());
    assertTrue(late.hardStopCalls > 0);
    assertTrue(existing.hardStopCalls > existingStopsBeforeLateLoad);
    assertTrue(
        debugMessages.stream()
            .anyMatch(
                message -> message.contains("SMART_LATE_LOAD_PHYSICAL_HYDRATION result=exception")),
        debugMessages::toString);
  }

  @Test
  void rebuildOccupancySnapshotStopsEveryTrainBeforeAtomicCommit() {
    RouteDefinition route =
        new RouteDefinition(
            RouteId.of("r"), List.of(NodeId.of("A"), NodeId.of("B")), Optional.empty());
    UUID worldId = UUID.randomUUID();
    SimpleOccupancyManager occupancyManager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    RuntimeDispatchService service = startupReconstructionService(worldId, route, occupancyManager);
    List<String> controlEvents = new ArrayList<>();
    FakeTrain first =
        new FakeTrain(
            worldId,
            new TagStore(
                    "first",
                    "FTA_OPERATOR_CODE=op",
                    "FTA_LINE_CODE=l1",
                    "FTA_ROUTE_CODE=r1",
                    "FTA_ROUTE_INDEX=0")
                .properties(),
            false,
            0.0,
            controlEvents,
            "first");
    FakeTrain second =
        new FakeTrain(
            worldId,
            new TagStore(
                    "second",
                    "FTA_OPERATOR_CODE=op",
                    "FTA_LINE_CODE=l1",
                    "FTA_ROUTE_CODE=r1",
                    "FTA_ROUTE_INDEX=0")
                .properties(),
            false,
            0.0,
            controlEvents,
            "second");

    assertTrue(service.rebuildOccupancySnapshot(List.of(first, second)));

    assertTrue(firstEventIndex(controlEvents, "first:hard-stop") >= 0, controlEvents::toString);
    assertTrue(firstEventIndex(controlEvents, "second:hard-stop") >= 0, controlEvents::toString);
    assertEquals(
        2,
        occupancyManager.snapshotClaims().stream()
            .map(OccupancyClaim::trainName)
            .distinct()
            .count());
    assertEquals(0, first.launchCalls);
    assertEquals(0, second.launchCalls);
  }

  @Test
  void preparedPhysicalSnapshotKeepsAuthorizationGateClosedUntilExplicitCompletion() {
    RouteDefinition route =
        new RouteDefinition(
            RouteId.of("r"), List.of(NodeId.of("A"), NodeId.of("B")), Optional.empty());
    UUID worldId = UUID.randomUUID();
    SimpleOccupancyManager occupancyManager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    RuntimeDispatchService service = startupReconstructionService(worldId, route, occupancyManager);
    FakeTrain train =
        new FakeTrain(
            worldId,
            new TagStore(
                    "train-1",
                    "FTA_OPERATOR_CODE=op",
                    "FTA_LINE_CODE=l1",
                    "FTA_ROUTE_CODE=r1",
                    "FTA_ROUTE_INDEX=0")
                .properties(),
            false);

    assertTrue(service.prepareStartupOccupancySnapshot(List.of(train)));
    int stopsAfterPrepare = train.hardStopCalls;
    long versionAfterPrepare = occupancyManager.version();

    service.handleSignalTick(train, false);

    assertTrue(train.hardStopCalls > stopsAfterPrepare);
    assertEquals(0, train.launchCalls);
    assertEquals(versionAfterPrepare, occupancyManager.version());

    assertTrue(service.completeStartupOccupancyReconstruction(List.of(train)));

    assertTrue(occupancyManager.version() >= versionAfterPrepare);
  }

  @Test
  void startupRefreshFailureReclosesGlobalAuthorizationGate() {
    RouteDefinition route =
        new RouteDefinition(
            RouteId.of("r"), List.of(NodeId.of("A"), NodeId.of("B")), Optional.empty());
    UUID worldId = UUID.randomUUID();
    SimpleOccupancyManager occupancyManager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    List<String> debugMessages = new ArrayList<>();
    RuntimeDispatchService service =
        startupReconstructionService(worldId, route, occupancyManager, debugMessages::add);
    FakeTrain train =
        new FakeTrain(
            worldId,
            new TagStore(
                    "train-1",
                    "FTA_OPERATOR_CODE=op",
                    "FTA_LINE_CODE=l1",
                    "FTA_ROUTE_CODE=r1",
                    "FTA_ROUTE_INDEX=0")
                .properties(),
            false);
    AtomicBoolean recoveryRequested = new AtomicBoolean();
    service.setStartupRecoveryRequestedListener(() -> recoveryRequested.set(true));

    assertTrue(service.prepareStartupOccupancySnapshot(List.of(train)));
    train.physicalIdentityFailure = new LinkageError("runtime ABI mismatch");

    assertFalse(service.completeStartupOccupancyReconstruction(List.of(train)));

    assertTrue(service.captureReadyStartupRecoveryEpoch().isEmpty());
    assertTrue(recoveryRequested.get());
    assertTrue(train.hardStopCalls >= 2);
    assertTrue(
        debugMessages.stream()
            .anyMatch(
                message ->
                    message.contains("state=STOP_FIRST")
                        && message.contains("startup-authorization-refresh-failed")));
  }

  @Test
  void abnormalPhysicalQuarantineIncludesTaglessSplitRemainderUntilExactEntityRemoval() {
    RouteDefinition route =
        new RouteDefinition(
            RouteId.of("r"), List.of(NodeId.of("A"), NodeId.of("B")), Optional.empty());
    UUID worldId = UUID.randomUUID();
    SimpleOccupancyManager occupancyManager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    RuntimeDispatchService service = startupReconstructionService(worldId, route, occupancyManager);
    FakeTrain abnormal =
        new FakeTrain(
            worldId,
            new TagStore(
                    "abnormal",
                    "FTA_OPERATOR_CODE=op",
                    "FTA_LINE_CODE=l1",
                    "FTA_ROUTE_CODE=r1",
                    "FTA_ROUTE_INDEX=0")
                .properties(),
            false);
    FakeTrain taglessSplitRemainder =
        new FakeTrain(worldId, new TagStore("abnormal~a").properties(), false);

    assertTrue(service.rebuildOccupancySnapshot(List.of(abnormal)));
    List<OccupancyClaim> claimsBeforeQuarantine = occupancyManager.snapshotClaims();
    long versionBeforeQuarantine = occupancyManager.version();
    int launchesBeforeQuarantine = abnormal.launchCalls;
    AtomicBoolean abnormalRemoved = new AtomicBoolean();
    List<Boolean> recoveryResults = new ArrayList<>();
    service.setStartupRecoveryRequestedListener(
        () -> {
          List<FakeTrain> liveTrains =
              abnormalRemoved.get()
                  ? List.of(taglessSplitRemainder)
                  : List.of(abnormal, taglessSplitRemainder);
          boolean prepared = service.prepareStartupOccupancySnapshot(liveTrains);
          recoveryResults.add(prepared);
          if (prepared) {
            service.completeStartupOccupancyReconstruction(liveTrains);
          }
        });

    service.beginFtaAbnormalPhysicalQuarantine(
        List.of(abnormal, taglessSplitRemainder), "abnormal", "unexpected-split-source");

    assertEquals(List.of(false), recoveryResults);
    assertEquals(claimsBeforeQuarantine, occupancyManager.snapshotClaims());
    assertEquals(versionBeforeQuarantine, occupancyManager.version());
    assertEquals(launchesBeforeQuarantine, abnormal.launchCalls);
    assertTrue(taglessSplitRemainder.hardStopCalls > 0);
    RuntimeStopState quarantineStop = service.getActiveStopState("abnormal").orElseThrow();
    assertEquals("ABNORMAL_PHYSICAL_QUARANTINE", quarantineStop.reasonCode());
    assertEquals(
        RuntimeStopState.ReleaseCondition.FIELD_RECONSTRUCTION_COMMITTED,
        quarantineStop.releaseCondition());
    assertEquals(
        RuntimeStopState.RetryTrigger.STARTUP_RECONSTRUCTION_RETRY, quarantineStop.retryTrigger());

    abnormalRemoved.set(true);
    service.handleTrainRemoved(abnormal);

    assertEquals(List.of(false, false), recoveryResults);
    assertEquals(claimsBeforeQuarantine, occupancyManager.snapshotClaims());
    assertEquals(launchesBeforeQuarantine, abnormal.launchCalls);

    service.handleTrainRemoved(taglessSplitRemainder);
    assertTrue(service.prepareStartupOccupancySnapshot(List.of()));
    service.completeStartupOccupancyReconstruction(List.of());

    assertTrue(occupancyManager.snapshotClaims().isEmpty());
    assertEquals(launchesBeforeQuarantine, abnormal.launchCalls);
  }

  @Test
  void materializedSpawnRollbackQuarantineBlocksExternalRefreshUntilExactRemoval() {
    RouteDefinition route =
        new RouteDefinition(
            RouteId.of("r"), List.of(NodeId.of("A"), NodeId.of("B")), Optional.empty());
    UUID worldId = UUID.randomUUID();
    SimpleOccupancyManager occupancyManager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    List<String> debugMessages = new ArrayList<>();
    RuntimeDispatchService service =
        startupReconstructionService(worldId, route, occupancyManager, debugMessages::add);
    FakeTrain train =
        new FakeTrain(
            worldId,
            new TagStore(
                    "rollback-train",
                    "FTA_OPERATOR_CODE=op",
                    "FTA_LINE_CODE=l1",
                    "FTA_ROUTE_CODE=r1",
                    "FTA_ROUTE_INDEX=0")
                .properties(),
            false);

    assertTrue(service.rebuildOccupancySnapshot(List.of(train)));
    assertFalse(occupancyManager.snapshotClaims().isEmpty());
    int launchesBeforeRollback = train.launchCalls;

    assertTrue(
        service.quarantineMaterializedSpawnRollback(
            train, "rollback-train", "test-rollback", true));
    service.handleSignalTick(train, false);

    assertTrue(train.hardStopCalls > 0);
    assertEquals(launchesBeforeRollback, train.launchCalls);
    assertFalse(occupancyManager.snapshotClaims().isEmpty());
    assertTrue(
        debugMessages.stream().anyMatch(message -> message.contains("rollback-quarantined")));

    service.handleTrainRemoved(train);

    assertTrue(occupancyManager.snapshotClaims().isEmpty());
  }

  @Test
  void materializedSpawnRollbackKeepsLogicalClaimsUntilDuplicateIdentityIsRemoved() {
    RouteDefinition route =
        new RouteDefinition(
            RouteId.of("r"), List.of(NodeId.of("A"), NodeId.of("B")), Optional.empty());
    UUID worldId = UUID.randomUUID();
    SimpleOccupancyManager occupancyManager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    RuntimeDispatchService service =
        startupReconstructionService(worldId, route, occupancyManager, ignored -> {});
    TagStore ownerTags =
        new TagStore(
            "rollback-train",
            "FTA_OPERATOR_CODE=op",
            "FTA_LINE_CODE=l1",
            "FTA_ROUTE_CODE=r1",
            "FTA_ROUTE_INDEX=0");
    FakeTrain owner = new FakeTrain(worldId, ownerTags.properties(), false);
    FakeTrain duplicate = new FakeTrain(worldId, ownerTags.properties(), false);

    assertTrue(service.rebuildOccupancySnapshot(List.of(owner)));
    assertFalse(occupancyManager.snapshotClaims().isEmpty());
    assertTrue(
        service.quarantineMaterializedSpawnRollback(
            owner, "rollback-train", "owner-rollback", true));
    assertTrue(
        service.quarantineMaterializedSpawnRollback(
            duplicate, "rollback-train", "duplicate-rollback", false));
    assertTrue(service.hasMaterializedSpawnRollbackQuarantine("ROLLBACK-TRAIN"));

    service.handleTrainRemoved(owner);

    assertFalse(occupancyManager.snapshotClaims().isEmpty(), "owner 先移除时仍有重复物理实体，不能提前释放逻辑 claims");

    service.handleTrainRemoved(duplicate);

    assertTrue(occupancyManager.snapshotClaims().isEmpty());
    assertFalse(service.hasMaterializedSpawnRollbackQuarantine("rollback-train"));
  }

  @Test
  void materializedSpawnRollbackUnloadTransfersAndRetriesUntilReloadedIdentityIsRemoved() {
    RouteDefinition route =
        new RouteDefinition(
            RouteId.of("r"), List.of(NodeId.of("A"), NodeId.of("B")), Optional.empty());
    UUID worldId = UUID.randomUUID();
    SimpleOccupancyManager occupancyManager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    RuntimeDispatchService service =
        startupReconstructionService(worldId, route, occupancyManager, ignored -> {});
    TagStore tags =
        new TagStore(
            "rollback-train",
            "FTA_OPERATOR_CODE=op",
            "FTA_LINE_CODE=l1",
            "FTA_ROUTE_CODE=r1",
            "FTA_ROUTE_INDEX=0");
    FakeTrain original = new FakeTrain(worldId, tags.properties(), false);

    assertTrue(service.rebuildOccupancySnapshot(List.of(original)));
    assertFalse(occupancyManager.snapshotClaims().isEmpty());
    assertTrue(
        service.quarantineMaterializedSpawnRollback(
            original, "rollback-train", "unload-rollback", true));

    service.handleTrainUnloaded(original);

    assertTrue(service.isMaterializedSpawnRollbackUnloaded(original));
    assertTrue(service.hasMaterializedSpawnRollbackQuarantine("rollback-train"));
    assertFalse(occupancyManager.snapshotClaims().isEmpty());

    FakeTrain reloaded = new FakeTrain(worldId, tags.properties(), false);
    assertTrue(service.resumeMaterializedSpawnRollback(reloaded));
    assertEquals(1, reloaded.destroyCalls);
    assertTrue(service.resumeMaterializedSpawnRollback(reloaded));
    assertEquals(2, reloaded.destroyCalls, "同一重载 identity 必须允许恢复循环持续重试销毁");
    assertFalse(occupancyManager.snapshotClaims().isEmpty());

    service.handleTrainRemoved(reloaded);

    assertTrue(service.consumeMaterializedSpawnRollbackRemoval(original));
    assertFalse(service.consumeMaterializedSpawnRollbackRemoval(original));
    assertFalse(service.hasMaterializedSpawnRollbackQuarantine("rollback-train"));
    assertTrue(occupancyManager.snapshotClaims().isEmpty());
    assertFalse(service.hasMaterializedSpawnRollbackTag(tags.properties()));
  }

  @Test
  void persistentMaterializedSpawnRollbackWaitsForOfficialOfflineRemovalProof() {
    RuntimeDispatchService service =
        startupReconstructionService(
            UUID.randomUUID(),
            new RouteDefinition(
                RouteId.of("r"), List.of(NodeId.of("A"), NodeId.of("B")), Optional.empty()),
            new SimpleOccupancyManager(
                (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy()),
            ignored -> {});
    TagStore tags =
        new TagStore(
            "offline-rollback",
            TrainSpawnTagInitializer.TAG_MATERIALIZED_ROLLBACK_PENDING + "=true");
    when(tags.properties().getHolder()).thenReturn(null);
    TrainCarts trainCarts = mock(TrainCarts.class);
    OfflineGroupManager offlineGroups = mock(OfflineGroupManager.class);
    when(tags.properties().getTrainCarts()).thenReturn(trainCarts);
    when(trainCarts.getOfflineGroups()).thenReturn(offlineGroups);
    when(offlineGroups.destroyGroupAsync("offline-rollback"))
        .thenReturn(CompletableFuture.completedFuture(Boolean.TRUE));

    try (var propertiesStore = mockStatic(TrainPropertiesStore.class)) {
      propertiesStore
          .when(TrainPropertiesStore::getAll)
          .thenReturn(List.of(tags.properties()), List.of());

      assertFalse(service.retryPersistentMaterializedSpawnRollbackRemovals());
      assertTrue(service.retryPersistentMaterializedSpawnRollbackRemovals());
      verify(offlineGroups).destroyGroupAsync("offline-rollback");
    }
  }

  @Test
  void potentialFtaSplitIsContainedImmediatelyWithoutReleasingPhysicalClaims() throws Exception {
    RouteDefinition route =
        new RouteDefinition(
            RouteId.of("r"), List.of(NodeId.of("A"), NodeId.of("B")), Optional.empty());
    UUID worldId = UUID.randomUUID();
    SimpleOccupancyManager occupancyManager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    List<String> debugMessages = new ArrayList<>();
    RuntimeDispatchService service =
        startupReconstructionService(worldId, route, occupancyManager, debugMessages::add);
    FakeTrain source =
        new FakeTrain(
            worldId,
            new TagStore(
                    "source",
                    "FTA_OPERATOR_CODE=op",
                    "FTA_LINE_CODE=l1",
                    "FTA_ROUTE_CODE=r1",
                    "FTA_ROUTE_INDEX=0")
                .properties(),
            false);
    FakeTrain taglessRemainder =
        new FakeTrain(worldId, new TagStore("source~detached").properties(), false);

    assertTrue(service.rebuildOccupancySnapshot(List.of(source)));
    List<OccupancyClaim> claimsBefore = occupancyManager.snapshotClaims();
    long occupancyVersionBefore = occupancyManager.version();
    List<OccupancyResource> authorityResources =
        claimsBefore.stream().map(OccupancyClaim::resource).distinct().toList();
    installMovementToken(
        service,
        new MovementAuthorizationToken(
                "source",
                1L,
                Instant.now(),
                NodeId.of("A"),
                NodeId.of("B"),
                authorityResources,
                SignalAspect.PROCEED)
            .activate("B"));
    assertEquals(1, movementTokenCount(service));

    service.containPotentialFtaPhysicalChange(List.of(source, taglessRemainder), "member-remove");

    assertTrue(source.hardStopCalls > 0);
    assertTrue(taglessRemainder.hardStopCalls > 0);
    assertEquals(0, movementTokenCount(service));
    assertFalse(
        validMovementAuthorization(
            service, "source", NodeId.of("A"), NodeId.of("B"), authorityResources));
    assertEquals(claimsBefore, occupancyManager.snapshotClaims());
    assertEquals(occupancyVersionBefore, occupancyManager.version());
    assertTrue(
        debugMessages.stream()
            .anyMatch(
                message ->
                    message.contains("SMART_POTENTIAL_PHYSICAL_CHANGE_CONTAINED")
                        && message.contains("source=member-remove")),
        debugMessages::toString);
  }

  @Test
  void nonFtaDerailedTrainCannotEnterFtaPhysicalQuarantine() {
    RouteDefinition route =
        new RouteDefinition(
            RouteId.of("r"), List.of(NodeId.of("A"), NodeId.of("B")), Optional.empty());
    UUID worldId = UUID.randomUUID();
    RuntimeDispatchService service =
        startupReconstructionService(
            worldId,
            route,
            new SimpleOccupancyManager(
                (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy()));
    FakeTrain nonFta = new FakeTrain(worldId, new TagStore("ordinary-train").properties(), false);

    assertFalse(service.quarantineAbnormalPhysicalIdentity(nonFta));
  }

  @Test
  void rebuildOccupancySnapshotWithNoTrainsStillReleasesGhostClaims() {
    RouteDefinition route =
        new RouteDefinition(
            RouteId.of("r"), List.of(NodeId.of("A"), NodeId.of("B")), Optional.empty());
    SimpleOccupancyManager occupancyManager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    assertTrue(
        occupancyManager
            .acquire(
                new OccupancyRequest(
                    "ghost",
                    Optional.empty(),
                    Instant.now(),
                    List.of(new OccupancyResource(ResourceKind.EDGE, "A~B")),
                    Map.of()))
            .allowed());
    RuntimeDispatchService service =
        startupReconstructionService(UUID.randomUUID(), route, occupancyManager);

    assertTrue(service.rebuildOccupancySnapshot(List.of()));

    assertTrue(occupancyManager.snapshotClaims().isEmpty());
  }

  @Test
  void failedStartupHydrationKeepsGateClosedUntilRetrySucceeds() {
    RouteDefinition route =
        new RouteDefinition(
            RouteId.of("r"), List.of(NodeId.of("A"), NodeId.of("B")), Optional.empty());
    UUID worldId = UUID.randomUUID();
    SimpleOccupancyManager delegate =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    OccupancyManager occupancyManager =
        mock(
            OccupancyManager.class,
            withSettings()
                .extraInterfaces(StartupOccupancyReconstructionSupport.class)
                .defaultAnswer(org.mockito.AdditionalAnswers.delegatesTo(delegate)));
    StartupOccupancyReconstructionSupport reconstructionSupport =
        (StartupOccupancyReconstructionSupport) occupancyManager;
    when(reconstructionSupport.reconstructPhysicalSnapshot(any()))
        .thenReturn(
            StartupOccupancyReconstructionSupport.ReconstructionResult.rejected(
                "atomic-commit-failed"))
        .thenAnswer(invocation -> delegate.reconstructPhysicalSnapshot(invocation.getArgument(0)));
    List<String> debugMessages = new ArrayList<>();
    RuntimeDispatchService service =
        startupReconstructionService(worldId, route, occupancyManager, debugMessages::add);
    FakeTrain train =
        new FakeTrain(
            worldId,
            new TagStore(
                    "train-1",
                    "FTA_OPERATOR_CODE=op",
                    "FTA_LINE_CODE=l1",
                    "FTA_ROUTE_CODE=r1",
                    "FTA_ROUTE_INDEX=0")
                .properties(),
            false);

    assertFalse(service.rebuildOccupancySnapshot(List.of(train)));
    service.handleSignalTick(train, false);
    assertEquals(0, train.launchCalls);
    assertTrue(service.isMovementInhibited("train-1"));

    assertTrue(service.rebuildOccupancySnapshot(List.of(train)));
    verify(occupancyManager, atLeastOnce()).acquire(any());
    assertTrue(
        debugMessages.stream().anyMatch(message -> message.contains("state=READY trains=1")),
        debugMessages::toString);
  }

  @Test
  void expectedPhysicalTopologyChangeDefersAuthorityUntilAtomicFieldReconstruction() {
    RouteDefinition route =
        new RouteDefinition(
            RouteId.of("r"), List.of(NodeId.of("A"), NodeId.of("B")), Optional.empty());
    UUID worldId = UUID.randomUUID();
    SimpleOccupancyManager occupancyManager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    List<String> debugMessages = new ArrayList<>();
    RuntimeDispatchService service =
        startupReconstructionService(worldId, route, occupancyManager, debugMessages::add);
    FakeTrain train =
        new FakeTrain(
            worldId,
            new TagStore(
                    "train-1",
                    "FTA_OPERATOR_CODE=op",
                    "FTA_LINE_CODE=l1",
                    "FTA_ROUTE_CODE=r1",
                    "FTA_ROUTE_INDEX=0")
                .properties(),
            false);
    FakeTrain linkingGroup =
        new FakeTrain(worldId, new TagStore("unmanaged-linking-group").properties(), false);
    linkingGroup.physicalIdentityUnavailable = true;

    assertTrue(service.rebuildOccupancySnapshot(List.of(train)));
    List<OccupancyClaim> claimsBeforeLink = occupancyManager.snapshotClaims();
    debugMessages.clear();
    AtomicBoolean recoveryRequested = new AtomicBoolean();
    service.setStartupRecoveryRequestedListener(() -> recoveryRequested.set(true));

    service.beginExpectedPhysicalTopologyRecovery(List.of(train, linkingGroup), "group-link");

    assertTrue(service.captureReadyStartupRecoveryEpoch().isEmpty());
    assertTrue(recoveryRequested.get());
    assertTrue(train.hardStopCalls > 0);
    assertTrue(linkingGroup.hardStopCalls > 0);
    assertEquals(claimsBeforeLink, occupancyManager.snapshotClaims());
    assertTrue(service.isMovementInhibited("train-1"));
    assertTrue(
        debugMessages.stream()
            .anyMatch(
                message ->
                    message.contains("SMART_EXPECTED_PHYSICAL_TOPOLOGY_RECOVERY")
                        && message.contains("state=STOP_FIRST")
                        && message.contains("claimsRetained=true")),
        debugMessages::toString);
  }

  private static RuntimeDispatchService startupReconstructionService(
      UUID worldId, RouteDefinition route, OccupancyManager occupancyManager) {
    return startupReconstructionService(worldId, route, occupancyManager, null);
  }

  private static RuntimeDispatchService startupReconstructionService(
      UUID worldId,
      RouteDefinition route,
      OccupancyManager occupancyManager,
      java.util.function.Consumer<String> debugLogger) {
    return startupReconstructionService(
        worldId, route, occupancyManager, debugLogger, startupPhysicalGraph(worldId));
  }

  private static RuntimeDispatchService startupReconstructionService(
      UUID worldId,
      RouteDefinition route,
      OccupancyManager occupancyManager,
      java.util.function.Consumer<String> debugLogger,
      RailGraph graph) {
    ConfigManager configManager = mock(ConfigManager.class);
    when(configManager.current()).thenReturn(testConfigView(20, 20.0));
    RailGraphService railGraphService = mock(RailGraphService.class);
    when(railGraphService.getSnapshot(worldId))
        .thenReturn(Optional.of(new RailGraphService.RailGraphSnapshot(graph, Instant.now())));
    when(railGraphService.effectiveSpeedLimitBlocksPerSecond(any(), any(), any(), anyDouble()))
        .thenReturn(1000.0);
    RouteDefinitionCache routeDefinitions = mock(RouteDefinitionCache.class);
    when(routeDefinitions.findByCodes("op", "l1", "r1")).thenReturn(Optional.of(route));
    return new RuntimeDispatchService(
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
        debugLogger);
  }

  private static RailGraph startupPhysicalGraph(UUID worldId) {
    NodeId from = NodeId.of("A");
    NodeId to = NodeId.of("B");
    RailEdge edge =
        new RailEdge(EdgeId.undirected(from, to), from, to, 10, -1.0, true, Optional.empty());
    Map<NodeId, RailNode> nodes = Map.of(from, new RailNodeTest(from), to, new RailNodeTest(to));
    Map<EdgeId, RailEdge> edges = Map.of(edge.id(), edge);
    return new SimpleRailGraph(
        nodes,
        edges,
        Set.of(),
        RailInterlockingState.fromSnapshot(
            worldId,
            edges.keySet(),
            new org.fetarute.fetaruteTCAddon.dispatcher.graph.interlocking.RailInterlockingCoverage(
                1, 1, true),
            Map.of()));
  }

  private static RailGraph startupPhysicalCrossingGraph(UUID worldId) {
    NodeId a = NodeId.of("A");
    NodeId b = NodeId.of("B");
    NodeId c = NodeId.of("C");
    NodeId d = NodeId.of("D");
    RailEdge routeEdge =
        new RailEdge(EdgeId.undirected(a, b), a, b, 10, -1.0, true, Optional.empty());
    RailEdge crossingEdge =
        new RailEdge(EdgeId.undirected(c, d), c, d, 10, -1.0, true, Optional.empty());
    Map<NodeId, RailNode> nodes =
        Map.of(
            a,
            new RailNodeTest(a),
            b,
            new RailNodeTest(b),
            c,
            new RailNodeTest(c),
            d,
            new RailNodeTest(d));
    Map<EdgeId, RailEdge> edges =
        Map.of(routeEdge.id(), routeEdge, crossingEdge.id(), crossingEdge);
    String zoneKey = "interlocking:test-startup-crossing";
    InterlockingZoneInfo zone =
        new InterlockingZoneInfo(
            zoneKey, routeEdge.id(), crossingEdge.id(), Set.of(new RailFootprintCell(0, 64, 0)));
    return new SimpleRailGraph(
        nodes,
        edges,
        Set.of(),
        RailInterlockingState.fromSnapshot(
            worldId,
            edges.keySet(),
            new org.fetarute.fetaruteTCAddon.dispatcher.graph.interlocking.RailInterlockingCoverage(
                2, 2, true),
            Map.of(zoneKey, zone)));
  }

  private static int firstEventIndex(List<String> events, String suffix) {
    for (int index = 0; index < events.size(); index++) {
      if (events.get(index).endsWith(suffix)) {
        return index;
      }
    }
    return -1;
  }

  @Test
  void cleanupOrphanOccupancyClaimsRemovesProgressEntries() {
    RouteDefinition route =
        new RouteDefinition(
            RouteId.of("r"), List.of(NodeId.of("A"), NodeId.of("B")), Optional.empty());
    TagStore tags = new TagStore("old-train", "FTA_ROUTE_INDEX=0");

    ConfigManager configManager = mock(ConfigManager.class);
    when(configManager.current()).thenReturn(testConfigView(20, 20.0));

    OccupancyManager occupancyManager = mockOccupancyManager();
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
  void cleanupOrphanOccupancyClaimsPreservesTaggedOwnerAlias() {
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    OccupancyResource crossing = OccupancyResource.forConflict("switcher:rename-failure-crossing");
    assertTrue(
        manager
            .acquire(
                new OccupancyRequest(
                    "old-train", Optional.empty(), Instant.now(), List.of(crossing), Map.of()))
            .allowed());
    RuntimeDispatchService service = createMinimalService(manager, new ArrayList<>());

    service.cleanupOrphanOccupancyClaims(Set.of("new-train", "old-train"));

    assertTrue(
        manager.snapshotClaims().stream()
            .anyMatch(
                claim ->
                    crossing.equals(claim.resource())
                        && TrainNameNormalizer.sameLogicalTrain(claim.trainName(), "old-train")));
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

    OccupancyManager occupancyManager = mockOccupancyManager();
    when(occupancyManager.canEnter(any())).thenAnswer(allowProceed());
    when(occupancyManager.acquire(any())).thenAnswer(allowProceed());

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

    verify(occupancyManager, never()).releaseByTrain("old-train");
    assertTrue(registry.get("old-train").isEmpty());
    assertTrue(registry.get("new-train").isPresent());
  }

  @Test
  void handleRenameIfNeededMigratesOccupancyOwnerWithoutReleasingAuthority() {
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    OccupancyResource node = OccupancyResource.forNode(NodeId.of("A"));
    OccupancyRequest request =
        new OccupancyRequest("old-train", Optional.empty(), Instant.now(), List.of(node), Map.of());
    assertTrue(manager.acquire(request).allowed());

    RuntimeDispatchService service = createMinimalService(manager, new ArrayList<>());
    TagStore tags = new TagStore("new-train", "FTA_TRAIN_NAME=old-train");

    assertEquals("new-train", service.handleRenameIfNeeded(tags.properties()));
    assertTrue(
        manager.snapshotClaims().stream()
            .noneMatch(
                claim -> TrainNameNormalizer.sameLogicalTrain(claim.trainName(), "old-train")));
    assertTrue(
        manager.snapshotClaims().stream()
            .anyMatch(
                claim -> TrainNameNormalizer.sameLogicalTrain(claim.trainName(), "new-train")));
  }

  @Test
  void handleRenameDefersOwnerMigrationDuringWaypointDwell() {
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    OccupancyResource crossing = OccupancyResource.forConflict("switcher:dwell-rename");
    assertTrue(
        manager
            .acquire(
                new OccupancyRequest(
                    "old-train", Optional.empty(), Instant.now(), List.of(crossing), Map.of()))
            .allowed());
    DwellRegistry dwellRegistry = new DwellRegistry();
    dwellRegistry.start("old-train", 30);
    ConfigManager configManager = mock(ConfigManager.class);
    when(configManager.current()).thenReturn(testConfigView(20, 20.0));
    RuntimeDispatchService service =
        new RuntimeDispatchService(
            manager,
            mock(RailGraphService.class),
            mock(RouteDefinitionCache.class),
            new RouteProgressRegistry(),
            mock(SignNodeRegistry.class),
            new LayoverRegistry(),
            dwellRegistry,
            configManager,
            null,
            new TrainConfigResolver(),
            null);
    TagStore tags = new TagStore("new-train", "FTA_TRAIN_NAME=old-train");

    assertEquals("old-train", service.handleRenameIfNeeded(tags.properties()));
    assertTrue(dwellRegistry.remainingSeconds("old-train").isPresent());
    assertTrue(
        manager.snapshotClaims().stream()
            .anyMatch(
                claim -> TrainNameNormalizer.sameLogicalTrain(claim.trainName(), "old-train")));
    assertFalse(
        manager.snapshotClaims().stream()
            .anyMatch(
                claim -> TrainNameNormalizer.sameLogicalTrain(claim.trainName(), "new-train")));

    dwellRegistry.clear("old-train");

    assertEquals("new-train", service.handleRenameIfNeeded(tags.properties()));
    assertTrue(
        manager.snapshotClaims().stream()
            .anyMatch(
                claim -> TrainNameNormalizer.sameLogicalTrain(claim.trainName(), "new-train")));
  }

  @Test
  void physicalFootprintIsReportedAsLiveHardRisk() throws Exception {
    OccupancyResource crossing = OccupancyResource.forConflict("switcher:terminal-crossing");
    OccupancyClaim footprint =
        new OccupancyClaim(
            crossing,
            "turning-train",
            Optional.empty(),
            Instant.now(),
            Duration.ZERO,
            Optional.empty(),
            ClaimRole.PHYSICAL_FOOTPRINT);
    java.lang.reflect.Method freshnessMethod =
        RuntimeDispatchService.class.getDeclaredMethod("freshnessForClaim", OccupancyClaim.class);
    freshnessMethod.setAccessible(true);
    java.lang.reflect.Method footprintMethod =
        RuntimeDispatchService.class.getDeclaredMethod(
            "claimPhysicalFootprintText", ClaimRole.class);
    footprintMethod.setAccessible(true);

    assertEquals(RiskFreshness.LIVE, freshnessMethod.invoke(null, footprint));
    assertEquals("true", footprintMethod.invoke(null, ClaimRole.PHYSICAL_FOOTPRINT));
    assertEquals(
        RiskSource.HARD_BLOCKER,
        invokeRiskSourceForBlocker(
            createMinimalService(),
            new OccupancyRequest(
                "approaching", Optional.empty(), Instant.now(), List.of(crossing), Map.of()),
            footprint));
  }

  @Test
  void behindRouteCleanupCannotReleaseTurnbackPhysicalFootprint() throws Exception {
    NodeId a = NodeId.of("A");
    NodeId b = NodeId.of("B");
    NodeId c = NodeId.of("C");
    RouteDefinition route =
        new RouteDefinition(RouteId.of("r"), List.of(a, b, c), Optional.empty());
    OccupancyResource forwardEdge = OccupancyResource.forEdge(EdgeId.undirected(b, c));
    OccupancyClaim footprint =
        new OccupancyClaim(
            forwardEdge,
            "train-behind",
            Optional.of(route.id()),
            Instant.now(),
            Duration.ZERO,
            Optional.empty(),
            ClaimRole.PHYSICAL_FOOTPRINT);
    RouteProgressRegistry registry = new RouteProgressRegistry();
    registry.initFromTags(
        "train-behind", new TagStore("train-behind", "FTA_ROUTE_INDEX=0").properties(), route);
    RuntimeDispatchService service =
        new RuntimeDispatchService(
            mockOccupancyManager(),
            mock(RailGraphService.class),
            mock(RouteDefinitionCache.class),
            registry,
            mock(SignNodeRegistry.class),
            mock(LayoverRegistry.class),
            new DwellRegistry(),
            mock(ConfigManager.class),
            null,
            new TrainConfigResolver(),
            null);
    java.lang.reflect.Method method =
        RuntimeDispatchService.class.getDeclaredMethod(
            "isSpeculativeBehindClaim",
            String.class,
            RouteDefinition.class,
            int.class,
            NodeId.class,
            RailGraph.class,
            OccupancyRequest.class,
            Set.class,
            OccupancyClaim.class);
    method.setAccessible(true);

    boolean speculative =
        (boolean)
            method.invoke(
                service,
                "train-front",
                route,
                1,
                b,
                graphWithTwoEdges(a, b, c, 10, 10),
                new OccupancyRequest(
                    "train-front",
                    Optional.of(route.id()),
                    Instant.now(),
                    List.of(forwardEdge),
                    Map.of()),
                Set.of(forwardEdge),
                footprint);

    assertFalse(speculative);
  }

  @Test
  void behindRouteCleanupCannotReleaseGuardedSharedMovementClaim() throws Exception {
    NodeId a = NodeId.of("A");
    NodeId b = NodeId.of("B");
    NodeId c = NodeId.of("C");
    RouteDefinition route =
        new RouteDefinition(RouteId.of("r"), List.of(a, b, c), Optional.empty());
    OccupancyResource sharedCrossing =
        OccupancyResource.forConflict("switcher:shared-turnback-crossing");
    OccupancyClaim movement =
        new OccupancyClaim(
            sharedCrossing,
            "train-behind",
            Optional.of(route.id()),
            Instant.now(),
            Duration.ZERO,
            Optional.empty(),
            ClaimRole.MOVEMENT_REQUIRED);
    RouteProgressRegistry registry = new RouteProgressRegistry();
    registry.initFromTags(
        "train-behind", new TagStore("train-behind", "FTA_ROUTE_INDEX=0").properties(), route);
    RuntimeDispatchService service =
        new RuntimeDispatchService(
            mockOccupancyManager(),
            mock(RailGraphService.class),
            mock(RouteDefinitionCache.class),
            registry,
            mock(SignNodeRegistry.class),
            mock(LayoverRegistry.class),
            new DwellRegistry(),
            mock(ConfigManager.class),
            null,
            new TrainConfigResolver(),
            null);
    RuntimeTrainHandle train = mock(RuntimeTrainHandle.class);
    when(train.estimatedTrainLengthBlocks()).thenReturn(OptionalDouble.of(2.0));
    NodeId clear = NodeId.of("CLEAR");
    service.registerTurnbackFootprintGuard(
        train,
        "train-behind",
        a,
        Set.of(sharedCrossing),
        List.of(
            new TurnbackFootprintGuardRegistry.ForwardPathEdge(a, b, 10.0),
            new TurnbackFootprintGuardRegistry.ForwardPathEdge(b, clear, 10.0)),
        1);
    java.lang.reflect.Method method =
        RuntimeDispatchService.class.getDeclaredMethod(
            "isSpeculativeBehindClaim",
            String.class,
            RouteDefinition.class,
            int.class,
            NodeId.class,
            RailGraph.class,
            OccupancyRequest.class,
            Set.class,
            OccupancyClaim.class);
    method.setAccessible(true);

    boolean speculative =
        (boolean)
            method.invoke(
                service,
                "train-front",
                route,
                1,
                b,
                graphWithTwoEdges(a, b, c, 10, 10),
                new OccupancyRequest(
                    "train-front",
                    Optional.of(route.id()),
                    Instant.now(),
                    List.of(sharedCrossing),
                    Map.of()),
                Set.of(sharedCrossing),
                movement);

    assertFalse(speculative);
  }

  @Test
  void singleDirectionHealCannotDeleteGuardedSharedMovementClaim() {
    OccupancyResource sharedSection =
        OccupancyResource.forConflict("single:section:terminal-shared");
    RuntimeDispatchService service = createMinimalService();
    RuntimeTrainHandle train = mock(RuntimeTrainHandle.class);
    when(train.estimatedTrainLengthBlocks()).thenReturn(OptionalDouble.of(2.0));
    NodeId terminal = NodeId.of("TERM");
    NodeId middle = NodeId.of("MID");
    NodeId clear = NodeId.of("CLEAR");
    service.registerTurnbackFootprintGuard(
        train,
        "turning-train",
        terminal,
        Set.of(sharedSection),
        List.of(
            new TurnbackFootprintGuardRegistry.ForwardPathEdge(terminal, middle, 10.0),
            new TurnbackFootprintGuardRegistry.ForwardPathEdge(middle, clear, 10.0)),
        1);
    OccupancyClaim movement =
        new OccupancyClaim(
            sharedSection,
            "turning-train",
            Optional.empty(),
            Instant.now(),
            Duration.ZERO,
            Optional.of(CorridorDirection.B_TO_A),
            ClaimRole.MOVEMENT_REQUIRED);
    OccupancyDecision mismatch =
        new OccupancyDecision(
            false,
            Instant.now(),
            SignalAspect.STOP,
            List.of(movement),
            false,
            "self-owned-single-opposite-direction");

    assertTrue(service.hasTurnbackProtectedBlocker("turning-train", mismatch));
  }

  @Test
  void turnbackSharedFootprintSurvivesRollbackAndShrinkUntilRearClear() throws Exception {
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    OccupancyResource oldApproach = OccupancyResource.forNode(NodeId.of("OLD"));
    OccupancyResource sharedCrossing = OccupancyResource.forConflict("switcher:terminal-crossing");
    OccupancyResource outboundEdge =
        OccupancyResource.forEdge(EdgeId.undirected(NodeId.of("TERM"), NodeId.of("MID")));
    OccupancyRequest inbound =
        new OccupancyRequest(
            "turning-train",
            Optional.empty(),
            Instant.now(),
            List.of(oldApproach, sharedCrossing),
            Map.of());
    assertTrue(manager.acquire(inbound).allowed());

    RuntimeDispatchService service = createMinimalService(manager, new ArrayList<>());
    Set<OccupancyResource> footprint = service.snapshotTurnbackFootprintResources("turning-train");
    assertEquals(Set.of(oldApproach, sharedCrossing), footprint);

    OccupancyRequest outbound =
        new OccupancyRequest(
            "turning-train",
            Optional.empty(),
            Instant.now(),
            List.of(sharedCrossing, outboundEdge),
            Map.of());
    assertTrue(manager.handoffAuthority(outbound).allowed());
    assertTrue(
        manager.snapshotClaims().stream()
            .anyMatch(
                claim ->
                    sharedCrossing.equals(claim.resource())
                        && claim.role() == ClaimRole.MOVEMENT_REQUIRED));
    assertEquals(
        Set.of(sharedCrossing, outboundEdge),
        service.snapshotTurnbackFootprintResources("turning-train"),
        "后续 epoch 不得重新收纳已由旧 guard 负责的 physical footprint");

    RuntimeTrainHandle train = mock(RuntimeTrainHandle.class);
    when(train.estimatedTrainLengthBlocks()).thenReturn(OptionalDouble.of(2.0));
    NodeId terminal = NodeId.of("TERM");
    NodeId middle = NodeId.of("MID");
    NodeId clear = NodeId.of("CLEAR");
    service.registerTurnbackFootprintGuard(
        train,
        "turning-train",
        terminal,
        footprint,
        List.of(
            new TurnbackFootprintGuardRegistry.ForwardPathEdge(terminal, middle, 10.0),
            new TurnbackFootprintGuardRegistry.ForwardPathEdge(middle, clear, 10.0)),
        1);

    java.lang.reflect.Method rollback =
        RuntimeDispatchService.class.getDeclaredMethod(
            "releaseMovementAuthorityResources", String.class, OccupancyRequest.class);
    rollback.setAccessible(true);
    rollback.invoke(service, "turning-train", outbound);
    assertTrue(
        manager.snapshotClaims().stream()
            .anyMatch(claim -> sharedCrossing.equals(claim.resource())));
    assertFalse(
        manager.snapshotClaims().stream().anyMatch(claim -> outboundEdge.equals(claim.resource())));

    service.releaseResourcesNotInRequest("turning-train", List.of(), Set.of());
    assertEquals(2, manager.snapshotClaims().size());

    service.observeTurnbackFootprintProgress("turning-train", middle);
    service.observeTurnbackFootprintProgress("turning-train", clear);
    assertFalse(
        manager.snapshotClaims().stream().anyMatch(claim -> oldApproach.equals(claim.resource())));
    assertTrue(
        manager.snapshotClaims().stream()
            .anyMatch(claim -> sharedCrossing.equals(claim.resource())));

    service.releaseResourcesNotInRequest("turning-train", List.of(), Set.of());
    assertTrue(manager.snapshotClaims().isEmpty());
  }

  @Test
  void layoverReadinessUsesActualDwellAfterEstimatedReadyAt() {
    DwellRegistry dwellRegistry = new DwellRegistry();
    dwellRegistry.start("turning-train", 30);
    RuntimeDispatchService service =
        new RuntimeDispatchService(
            mockOccupancyManager(),
            mock(RailGraphService.class),
            mock(RouteDefinitionCache.class),
            new RouteProgressRegistry(),
            mock(SignNodeRegistry.class),
            mock(LayoverRegistry.class),
            dwellRegistry,
            mock(ConfigManager.class),
            null,
            new TrainConfigResolver(),
            null);
    LayoverRegistry.LayoverCandidate candidate =
        new LayoverRegistry.LayoverCandidate(
            "turning-train",
            "SURC:S:TERM",
            NodeId.of("SURC:S:TERM:1"),
            Instant.now().minusSeconds(5),
            Map.of());
    RuntimeTrainHandle stoppedTrain = mock(RuntimeTrainHandle.class);
    when(stoppedTrain.isMoving()).thenReturn(false);

    assertEquals(
        Optional.of("dwell-active"),
        service.layoverReadinessBlocker(candidate, stoppedTrain, Instant.now()));
  }

  @Test
  void handleSignalTickPreservesFullAuthorityForClaimedLayoverAttempt() {
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    NodeId location = NodeId.of("SURC:S:TERM:1");
    NodeId throat = NodeId.of("SURC:S:TERM:1:001");
    OccupancyResource locationResource = OccupancyResource.forNode(location);
    OccupancyResource throatResource =
        OccupancyResource.forEdge(EdgeId.undirected(location, throat));
    OccupancyRequest authority =
        new OccupancyRequest(
            "train-1",
            Optional.empty(),
            Instant.now(),
            List.of(locationResource, throatResource),
            Map.of());
    assertTrue(manager.acquire(authority).allowed());

    LayoverRegistry layoverRegistry = new LayoverRegistry();
    layoverRegistry.register(
        "train-1", "SURC:S:TERM", location, Instant.now().minusSeconds(1), Map.of());
    assertTrue(layoverRegistry.claimDispatch("train-1", "ticket-1", "train-1-next").isPresent());

    ConfigManager configManager = mock(ConfigManager.class);
    when(configManager.current()).thenReturn(testConfigView(20, 20.0));
    RuntimeDispatchService service =
        new RuntimeDispatchService(
            manager,
            mock(RailGraphService.class),
            mock(RouteDefinitionCache.class),
            new RouteProgressRegistry(),
            mock(SignNodeRegistry.class),
            layoverRegistry,
            new DwellRegistry(),
            configManager,
            null,
            new TrainConfigResolver(),
            null);
    TagStore tags = new TagStore("train-1", "FTA_TRAIN_NAME=train-1", "FTA_OPERATOR_CODE=SURC");

    service.handleSignalTick(new FakeTrain(UUID.randomUUID(), tags.properties(), false), false);

    Set<OccupancyResource> retained =
        manager.snapshotClaims().stream()
            .map(OccupancyClaim::resource)
            .collect(java.util.stream.Collectors.toSet());
    assertEquals(Set.of(locationResource, throatResource), retained);
  }

  @Test
  void handleSignalTickFailRetainsFullAuthorityForReadyLayoverWithoutGraphEvidence() {
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    NodeId location = NodeId.of("SURC:S:TERM:1");
    NodeId throat = NodeId.of("SURC:S:TERM:1:001");
    OccupancyResource locationResource = OccupancyResource.forNode(location);
    OccupancyResource throatResource =
        OccupancyResource.forEdge(EdgeId.undirected(location, throat));
    OccupancyResource crossing = OccupancyResource.forConflict("switcher:SURC:S:TERM:1:001");
    assertTrue(
        manager
            .acquire(
                new OccupancyRequest(
                    "train-1",
                    Optional.empty(),
                    Instant.now(),
                    List.of(locationResource, throatResource, crossing),
                    Map.of()))
            .allowed());

    LayoverRegistry layoverRegistry = new LayoverRegistry();
    layoverRegistry.register(
        "train-1", "SURC:S:TERM", location, Instant.now().minusSeconds(1), Map.of());
    ConfigManager configManager = mock(ConfigManager.class);
    when(configManager.current()).thenReturn(testConfigView(20, 20.0));
    RuntimeDispatchService service =
        new RuntimeDispatchService(
            manager,
            mock(RailGraphService.class),
            mock(RouteDefinitionCache.class),
            new RouteProgressRegistry(),
            mock(SignNodeRegistry.class),
            layoverRegistry,
            new DwellRegistry(),
            configManager,
            null,
            new TrainConfigResolver(),
            null);
    TagStore tags = new TagStore("train-1", "FTA_TRAIN_NAME=train-1", "FTA_OPERATOR_CODE=SURC");

    service.handleSignalTick(new FakeTrain(UUID.randomUUID(), tags.properties(), false), false);

    Set<OccupancyResource> retained =
        manager.snapshotClaims().stream()
            .map(OccupancyClaim::resource)
            .collect(java.util.stream.Collectors.toSet());
    assertEquals(Set.of(locationResource, throatResource, crossing), retained);
  }

  @Test
  void dispatchLayoverHandsOffAuthorityAndKeepsOldFootprintUntilRearClear() {
    String previousTrainName = "turning-train";
    NodeId approach = NodeId.of("SURC:TERM:APPROACH:1");
    NodeId terminal = NodeId.of("SURC:S:TERM:1");
    NodeId throat = NodeId.of("SURC:S:TERM:1:001");
    NodeId clear = NodeId.of("SURC:TERM:NEXT:1:001");
    RailEdge oldApproach =
        new RailEdge(
            EdgeId.undirected(approach, terminal),
            approach,
            terminal,
            10,
            -1.0,
            true,
            Optional.empty());
    RailEdge terminalThroat =
        new RailEdge(
            EdgeId.undirected(terminal, throat),
            terminal,
            throat,
            10,
            -1.0,
            true,
            Optional.empty());
    RailEdge throatClear =
        new RailEdge(
            EdgeId.undirected(throat, clear), throat, clear, 10, -1.0, true, Optional.empty());
    RailGraph graph =
        new SimpleRailGraph(
            Map.of(
                approach, new RailNodeTest(approach),
                terminal, new RailNodeTest(terminal),
                throat, new RailNodeTest(throat),
                clear, new RailNodeTest(clear)),
            Map.of(
                oldApproach.id(), oldApproach,
                terminalThroat.id(), terminalThroat,
                throatClear.id(), throatClear),
            Set.of());
    RouteDefinition route =
        new RouteDefinition(
            RouteId.of("turnback"), List.of(terminal, throat, clear), Optional.empty());
    UUID ticketRouteId = UUID.randomUUID();
    UUID worldId = UUID.randomUUID();
    OccupancyResource oldApproachResource = OccupancyResource.forEdge(oldApproach.id());
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    assertTrue(
        manager
            .acquire(
                new OccupancyRequest(
                    previousTrainName,
                    Optional.empty(),
                    Instant.now(),
                    List.of(oldApproachResource, OccupancyResource.forNode(terminal)),
                    Map.of()))
            .allowed());

    ConfigManager configManager = mock(ConfigManager.class);
    when(configManager.current()).thenReturn(testConfigView(1, 20.0));
    RailGraphService railGraphService = mock(RailGraphService.class);
    when(railGraphService.getSnapshot(worldId))
        .thenReturn(Optional.of(new RailGraphService.RailGraphSnapshot(graph, Instant.now())));
    when(railGraphService.effectiveSpeedLimitBlocksPerSecond(any(), any(), any(), anyDouble()))
        .thenReturn(20.0);
    RouteDefinitionCache routeDefinitions = mock(RouteDefinitionCache.class);
    when(routeDefinitions.findById(ticketRouteId)).thenReturn(Optional.of(route));
    when(routeDefinitions.listStops(route.id())).thenReturn(List.of());
    LayoverRegistry layoverRegistry = new LayoverRegistry();
    layoverRegistry.register(
        previousTrainName,
        TerminalKeyResolver.toTerminalKey(terminal),
        terminal,
        Instant.now().minusSeconds(1),
        Map.of());
    RuntimeDispatchService service =
        new RuntimeDispatchService(
            manager,
            railGraphService,
            routeDefinitions,
            new RouteProgressRegistry(),
            mock(SignNodeRegistry.class),
            layoverRegistry,
            new DwellRegistry(),
            configManager,
            null,
            new TrainConfigResolver(),
            null);
    TagStore tags = new TagStore(previousTrainName);
    FakeTrain train = new FakeTrain(worldId, tags.properties(), false);
    LayoverRegistry.LayoverCandidate candidate =
        layoverRegistry.get(previousTrainName).orElseThrow();
    ServiceTicket ticket =
        new ServiceTicket(
            "ticket-turnback",
            Instant.now(),
            ticketRouteId,
            candidate.terminalKey(),
            10,
            ServiceTicket.TicketMode.OPERATION);

    LayoverDispatchResult result =
        service.dispatchLayover(candidate, ticket, tags.properties(), train);

    assertTrue(result.dispatched(), result.reason());
    String committedTrainName = result.trainName().orElseThrow();
    assertEquals(1, train.launchCalls);
    assertTrue(
        manager.snapshotClaims().stream()
            .anyMatch(
                claim ->
                    oldApproachResource.equals(claim.resource())
                        && TrainNameNormalizer.sameLogicalTrain(
                            claim.trainName(), committedTrainName)
                        && claim.role() == ClaimRole.PHYSICAL_FOOTPRINT));

    service.observeTurnbackFootprintProgress(committedTrainName, throat);

    assertTrue(
        manager.snapshotClaims().stream()
            .anyMatch(
                claim ->
                    oldApproachResource.equals(claim.resource())
                        && claim.role() == ClaimRole.PHYSICAL_FOOTPRINT));

    service.observeTurnbackFootprintProgress(committedTrainName, clear);

    assertFalse(
        manager.snapshotClaims().stream()
            .anyMatch(claim -> oldApproachResource.equals(claim.resource())));
  }

  @Test
  void resolveTurnbackForwardPathExtendsBeyondShortAuthorityWindow() {
    NodeId station = NodeId.of("SURC:S:TERM:1");
    NodeId throat = NodeId.of("SURC:S:TERM:1:001");
    NodeId clearance = NodeId.of("SURC:TERM:NEXT:1:001");
    NodeId nextStation = NodeId.of("SURC:S:NEXT:1");
    RailEdge stationThroat =
        new RailEdge(
            EdgeId.undirected(station, throat), station, throat, 3, -1.0, true, Optional.empty());
    RailEdge throatClearance =
        new RailEdge(
            EdgeId.undirected(throat, clearance),
            throat,
            clearance,
            3,
            -1.0,
            true,
            Optional.empty());
    RailEdge clearanceStation =
        new RailEdge(
            EdgeId.undirected(clearance, nextStation),
            clearance,
            nextStation,
            20,
            -1.0,
            true,
            Optional.empty());
    RailGraph graph =
        new SimpleRailGraph(
            Map.of(
                station, new RailNodeTest(station),
                throat, new RailNodeTest(throat),
                clearance, new RailNodeTest(clearance),
                nextStation, new RailNodeTest(nextStation)),
            Map.of(
                stationThroat.id(), stationThroat,
                throatClearance.id(), throatClearance,
                clearanceStation.id(), clearanceStation),
            Set.of());
    OccupancyRequest request =
        new OccupancyRequest(
            "train-1",
            Optional.empty(),
            Instant.now(),
            List.of(OccupancyResource.forEdge(stationThroat.id())),
            Map.of());
    OccupancyRequestContext context =
        new OccupancyRequestContext(
            request, List.of(station, throat, clearance), List.of(stationThroat, throatClearance));

    List<TurnbackFootprintGuardRegistry.ForwardPathEdge> path =
        createMinimalService()
            .resolveTurnbackForwardPath(graph, context, List.of(station, nextStation), 0);

    assertEquals(3, path.size());
    assertEquals(clearance, path.get(2).fromNode());
    assertEquals(nextStation, path.get(2).toNode());
    assertEquals(20.0, path.get(2).lengthBlocks());
  }

  @Test
  void resolveTurnbackForwardPathDoesNotBacktrackAcrossCoveredRouteWaypoint() {
    NodeId terminal = NodeId.of("TERM");
    NodeId throat = NodeId.of("THROAT");
    NodeId next = NodeId.of("NEXT");
    NodeId clearance = NodeId.of("CLEARANCE");
    NodeId end = NodeId.of("END");
    RailEdge terminalThroat =
        new RailEdge(
            EdgeId.undirected(terminal, throat),
            terminal,
            throat,
            10,
            -1.0,
            true,
            Optional.empty());
    RailEdge throatNext =
        new RailEdge(
            EdgeId.undirected(throat, next), throat, next, 10, -1.0, true, Optional.empty());
    RailEdge nextClearance =
        new RailEdge(
            EdgeId.undirected(next, clearance), next, clearance, 10, -1.0, true, Optional.empty());
    RailEdge clearanceEnd =
        new RailEdge(
            EdgeId.undirected(clearance, end), clearance, end, 10, -1.0, true, Optional.empty());
    RailGraph graph =
        new SimpleRailGraph(
            Map.of(
                terminal, new RailNodeTest(terminal),
                throat, new RailNodeTest(throat),
                next, new RailNodeTest(next),
                clearance, new RailNodeTest(clearance),
                end, new RailNodeTest(end)),
            Map.of(
                terminalThroat.id(), terminalThroat,
                throatNext.id(), throatNext,
                nextClearance.id(), nextClearance,
                clearanceEnd.id(), clearanceEnd),
            Set.of());
    List<NodeId> authorityNodes = List.of(terminal, throat, next, clearance);
    List<RailEdge> authorityEdges = List.of(terminalThroat, throatNext, nextClearance);
    OccupancyRequestContext context =
        new OccupancyRequestContext(
            new OccupancyRequest(
                "turning-train", Optional.empty(), Instant.now(), List.of(), Map.of()),
            authorityNodes,
            authorityEdges);

    List<TurnbackFootprintGuardRegistry.ForwardPathEdge> path =
        createMinimalService()
            .resolveTurnbackForwardPath(graph, context, List.of(terminal, next, end), 0);

    assertEquals(4, path.size());
    assertEquals(clearance, path.get(3).fromNode());
    assertEquals(end, path.get(3).toNode());
  }

  private static Answer<OccupancyDecision> allowProceed() {
    return inv -> {
      OccupancyRequest request = inv.getArgument(0);
      return new OccupancyDecision(true, request.now(), SignalAspect.PROCEED, List.of());
    };
  }

  private static RiskSource invokeRiskSourceForBlocker(
      RuntimeDispatchService service, OccupancyRequest request, OccupancyClaim blocker)
      throws Exception {
    java.lang.reflect.Method method =
        RuntimeDispatchService.class.getDeclaredMethod(
            "riskSourceForBlocker", OccupancyRequest.class, OccupancyClaim.class);
    method.setAccessible(true);
    return (RiskSource) method.invoke(service, request, blocker);
  }

  private static boolean invokeProvenSameRouteLeader(
      RuntimeDispatchService service, String requesterTrain, String blockerTrain) throws Exception {
    java.lang.reflect.Method method =
        RuntimeDispatchService.class.getDeclaredMethod(
            "provenSameRouteLeader", String.class, String.class);
    method.setAccessible(true);
    return (boolean) method.invoke(service, requesterTrain, blockerTrain);
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

  private static RailGraph graphWithLinearPath(List<NodeId> nodes, int lengthBlocks) {
    java.util.Map<NodeId, org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode> railNodes =
        new java.util.LinkedHashMap<>();
    java.util.Map<EdgeId, RailEdge> edges = new java.util.LinkedHashMap<>();
    for (NodeId node : nodes) {
      railNodes.put(node, new RailNodeTest(node));
    }
    for (int i = 0; i + 1 < nodes.size(); i++) {
      NodeId from = nodes.get(i);
      NodeId to = nodes.get(i + 1);
      EdgeId edgeId = EdgeId.undirected(from, to);
      edges.put(edgeId, new RailEdge(edgeId, from, to, lengthBlocks, -1.0, true, Optional.empty()));
    }
    return new SimpleRailGraph(railNodes, edges, Set.of());
  }

  /** 为逻辑图的每条边配置唯一完整 rail cell，供运行时释放测试提供可验证的现场证据。 */
  private static PhysicalGraphFixture withSyntheticPhysicalFootprints(
      RailGraph source, UUID worldId) {
    Map<NodeId, RailNode> nodes = new LinkedHashMap<>();
    source.nodes().forEach(node -> nodes.put(node.id(), node));
    List<RailEdge> sortedEdges =
        source.edges().stream()
            .sorted(java.util.Comparator.comparing(edge -> edge.id().toString()))
            .toList();
    Map<EdgeId, RailEdge> edges = new LinkedHashMap<>();
    Map<EdgeId, RailFootprintCell> cellsByEdge = new LinkedHashMap<>();
    for (int index = 0; index < sortedEdges.size(); index++) {
      RailEdge edge = sortedEdges.get(index);
      RailFootprintCell cell = new RailFootprintCell(10_000 + index, 64, 20_000 + index);
      RailEdge physicalEdge =
          new RailEdge(
              edge.id(),
              edge.from(),
              edge.to(),
              edge.lengthBlocks(),
              edge.baseSpeedLimit(),
              edge.bidirectional(),
              edge.metadata());
      edges.put(physicalEdge.id(), physicalEdge);
      cellsByEdge.put(physicalEdge.id(), cell);
    }
    RailInterlockingState interlockingState =
        RailInterlockingState.fromSnapshot(
            worldId,
            edges.keySet(),
            new org.fetarute.fetaruteTCAddon.dispatcher.graph.interlocking.RailInterlockingCoverage(
                edges.size(), edges.size(), true),
            Map.of());
    return new PhysicalGraphFixture(
        new SimpleRailGraph(nodes, edges, Set.of(), interlockingState), Map.copyOf(cellsByEdge));
  }

  /** 携带 edge 到唯一现场 cell 的物理图测试夹具。 */
  private record PhysicalGraphFixture(
      RailGraph graph, Map<EdgeId, RailFootprintCell> cellsByEdge) {}

  /**
   * 构造不产生桥链单线 section 的环形图夹具。
   *
   * <p>回边只用于证明各 route edge 存在替代路径，并使用足够大的长度避免改变逐段最短路；测试仍沿 {@code nodes} 给出的规范路径运行。
   */
  private static RailGraph graphWithConflictFreeCyclePath(List<NodeId> nodes, int lengthBlocks) {
    if (nodes.size() < 3) {
      throw new IllegalArgumentException("环形图至少需要三个节点");
    }
    java.util.Map<NodeId, org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode> railNodes =
        new java.util.LinkedHashMap<>();
    java.util.List<RailEdge> edges = new java.util.ArrayList<>();
    for (NodeId node : nodes) {
      railNodes.put(node, new RailNodeTest(node));
    }
    for (int index = 0; index + 1 < nodes.size(); index++) {
      NodeId from = nodes.get(index);
      NodeId to = nodes.get(index + 1);
      EdgeId edgeId = EdgeId.undirected(from, to);
      edges.add(new RailEdge(edgeId, from, to, lengthBlocks, -1.0, true, Optional.empty()));
    }
    NodeId first = nodes.get(0);
    NodeId last = nodes.get(nodes.size() - 1);
    EdgeId returnEdgeId = EdgeId.undirected(last, first);
    edges.add(
        new RailEdge(
            returnEdgeId,
            last,
            first,
            Math.max(lengthBlocks + 1, Math.multiplyExact(lengthBlocks, nodes.size())),
            -1.0,
            true,
            Optional.empty()));
    return new RailGraph() {
      @Override
      public java.util.Collection<org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode> nodes() {
        return railNodes.values();
      }

      @Override
      public java.util.Collection<RailEdge> edges() {
        return edges;
      }

      @Override
      public Optional<org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode> findNode(NodeId id) {
        return Optional.ofNullable(railNodes.get(id));
      }

      @Override
      public java.util.Set<RailEdge> edgesFrom(NodeId id) {
        if (id == null) {
          return Set.of();
        }
        return edges.stream()
            .filter(edge -> edge.from().equals(id) || edge.to().equals(id))
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
      }

      @Override
      public boolean isBlocked(EdgeId id) {
        return false;
      }
    };
  }

  private static final class ConflictExitGraph implements RailGraph, RailGraphConflictSupport {
    private final NodeId a;
    private final NodeId b;
    private final NodeId c;
    private final RailEdge edgeAb;
    private final RailEdge edgeBc;
    private final String firstEdgeConflictKey;

    private ConflictExitGraph(
        NodeId a,
        NodeId b,
        NodeId c,
        RailEdge edgeAb,
        RailEdge edgeBc,
        String firstEdgeConflictKey) {
      this.a = a;
      this.b = b;
      this.c = c;
      this.edgeAb = edgeAb;
      this.edgeBc = edgeBc;
      this.firstEdgeConflictKey = firstEdgeConflictKey;
    }

    @Override
    public java.util.Collection<org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode> nodes() {
      return List.of(new RailNodeTest(a), new RailNodeTest(b), new RailNodeTest(c));
    }

    @Override
    public java.util.Collection<RailEdge> edges() {
      return List.of(edgeAb, edgeBc);
    }

    @Override
    public Optional<org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode> findNode(NodeId id) {
      if (a.equals(id)) {
        return Optional.of(new RailNodeTest(a));
      }
      if (b.equals(id)) {
        return Optional.of(new RailNodeTest(b));
      }
      if (c.equals(id)) {
        return Optional.of(new RailNodeTest(c));
      }
      return Optional.empty();
    }

    @Override
    public Set<RailEdge> edgesFrom(NodeId id) {
      if (a.equals(id)) {
        return Set.of(edgeAb);
      }
      if (b.equals(id)) {
        return Set.of(edgeAb, edgeBc);
      }
      if (c.equals(id)) {
        return Set.of(edgeBc);
      }
      return Set.of();
    }

    @Override
    public boolean isBlocked(EdgeId id) {
      return false;
    }

    @Override
    public Optional<String> conflictKeyForEdge(EdgeId edgeId) {
      if (edgeAb.id().equals(edgeId)) {
        return Optional.of(firstEdgeConflictKey);
      }
      return Optional.empty();
    }
  }

  private static final class ThroatConflictGraph implements RailGraph, RailGraphConflictSupport {
    private final Map<NodeId, RailNode> nodes;
    private final Map<EdgeId, RailEdge> edges;
    private final Map<EdgeId, String> conflicts;

    private ThroatConflictGraph(
        Map<NodeId, RailNode> nodes, List<RailEdge> edges, Map<EdgeId, String> conflicts) {
      this.nodes = Map.copyOf(nodes);
      Map<EdgeId, RailEdge> nextEdges = new LinkedHashMap<>();
      for (RailEdge edge : edges) {
        nextEdges.put(edge.id(), edge);
      }
      this.edges = Map.copyOf(nextEdges);
      this.conflicts = Map.copyOf(conflicts);
    }

    @Override
    public java.util.Collection<RailNode> nodes() {
      return nodes.values();
    }

    @Override
    public java.util.Collection<RailEdge> edges() {
      return edges.values();
    }

    @Override
    public Optional<RailNode> findNode(NodeId id) {
      return Optional.ofNullable(nodes.get(id));
    }

    @Override
    public Set<RailEdge> edgesFrom(NodeId id) {
      Set<RailEdge> result = new LinkedHashSet<>();
      for (RailEdge edge : edges.values()) {
        if (edge.from().equals(id) || edge.to().equals(id)) {
          result.add(edge);
        }
      }
      return result;
    }

    @Override
    public boolean isBlocked(EdgeId id) {
      return false;
    }

    @Override
    public Optional<String> conflictKeyForEdge(EdgeId edgeId) {
      return Optional.ofNullable(conflicts.get(edgeId));
    }
  }

  private static final class ParallelConflictExitGraph
      implements RailGraph, RailGraphConflictSupport {
    private final Map<NodeId, RailNode> nodes;
    private final Map<EdgeId, RailEdge> edges;
    private final Set<EdgeId> conflictEdges;
    private final String conflictKey;

    private ParallelConflictExitGraph(
        List<RailEdge> graphEdges, Set<EdgeId> conflictEdges, String conflictKey) {
      Map<NodeId, RailNode> nextNodes = new LinkedHashMap<>();
      Map<EdgeId, RailEdge> nextEdges = new LinkedHashMap<>();
      for (RailEdge edge : graphEdges) {
        nextNodes.putIfAbsent(edge.from(), new RailNodeTest(edge.from()));
        nextNodes.putIfAbsent(edge.to(), new RailNodeTest(edge.to()));
        nextEdges.put(edge.id(), edge);
      }
      this.nodes = Map.copyOf(nextNodes);
      this.edges = Map.copyOf(nextEdges);
      this.conflictEdges = Set.copyOf(conflictEdges);
      this.conflictKey = conflictKey;
    }

    @Override
    public java.util.Collection<RailNode> nodes() {
      return nodes.values();
    }

    @Override
    public java.util.Collection<RailEdge> edges() {
      return edges.values();
    }

    @Override
    public Optional<RailNode> findNode(NodeId id) {
      return Optional.ofNullable(nodes.get(id));
    }

    @Override
    public Set<RailEdge> edgesFrom(NodeId id) {
      Set<RailEdge> result = new java.util.LinkedHashSet<>();
      for (RailEdge edge : edges.values()) {
        if (edge.from().equals(id) || edge.to().equals(id)) {
          result.add(edge);
        }
      }
      return result;
    }

    @Override
    public boolean isBlocked(EdgeId id) {
      return false;
    }

    @Override
    public Optional<String> conflictKeyForEdge(EdgeId edgeId) {
      return conflictEdges.contains(edgeId) ? Optional.of(conflictKey) : Optional.empty();
    }
  }

  private static final class DeadEndStationConflictGraph
      implements RailGraph, RailGraphConflictSupport {
    private final Map<NodeId, RailNode> nodes;
    private final Map<EdgeId, RailEdge> edges;
    private final EdgeId conflictEdge;
    private final String conflictKey;

    private DeadEndStationConflictGraph(
        List<RailEdge> graphEdges, NodeId stationNode, EdgeId conflictEdge, String conflictKey) {
      Map<NodeId, RailNode> nextNodes = new LinkedHashMap<>();
      Map<EdgeId, RailEdge> nextEdges = new LinkedHashMap<>();
      for (RailEdge edge : graphEdges) {
        nextNodes.putIfAbsent(edge.from(), new RailNodeTest(edge.from()));
        nextNodes.putIfAbsent(edge.to(), new RailNodeTest(edge.to()));
        nextEdges.put(edge.id(), edge);
      }
      nextNodes.put(stationNode, new RailNodeTest(stationNode, NodeType.STATION, Optional.empty()));
      this.nodes = Map.copyOf(nextNodes);
      this.edges = Map.copyOf(nextEdges);
      this.conflictEdge = conflictEdge;
      this.conflictKey = conflictKey;
    }

    @Override
    public java.util.Collection<RailNode> nodes() {
      return nodes.values();
    }

    @Override
    public java.util.Collection<RailEdge> edges() {
      return edges.values();
    }

    @Override
    public Optional<RailNode> findNode(NodeId id) {
      return Optional.ofNullable(nodes.get(id));
    }

    @Override
    public Set<RailEdge> edgesFrom(NodeId id) {
      Set<RailEdge> result = new java.util.LinkedHashSet<>();
      for (RailEdge edge : edges.values()) {
        if (edge.from().equals(id) || edge.to().equals(id)) {
          result.add(edge);
        }
      }
      return result;
    }

    @Override
    public boolean isBlocked(EdgeId id) {
      return false;
    }

    @Override
    public Optional<String> conflictKeyForEdge(EdgeId edgeId) {
      return conflictEdge.equals(edgeId) ? Optional.of(conflictKey) : Optional.empty();
    }
  }

  private static final class ConflictBoundaryGraph implements RailGraph, RailGraphConflictSupport {
    private final NodeId from;
    private final NodeId to;
    private final RailEdge edge;
    private final String conflictKey;

    private ConflictBoundaryGraph(NodeId from, NodeId to, RailEdge edge, String conflictKey) {
      this.from = from;
      this.to = to;
      this.edge = edge;
      this.conflictKey = conflictKey;
    }

    @Override
    public java.util.Collection<org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode> nodes() {
      return List.of(new RailNodeTest(from), new RailNodeTest(to));
    }

    @Override
    public java.util.Collection<RailEdge> edges() {
      return List.of(edge);
    }

    @Override
    public Optional<org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode> findNode(NodeId id) {
      if (from.equals(id)) {
        return Optional.of(new RailNodeTest(from));
      }
      if (to.equals(id)) {
        return Optional.of(new RailNodeTest(to));
      }
      return Optional.empty();
    }

    @Override
    public Set<RailEdge> edgesFrom(NodeId id) {
      if (from.equals(id) || to.equals(id)) {
        return Set.of(edge);
      }
      return Set.of();
    }

    @Override
    public boolean isBlocked(EdgeId id) {
      return false;
    }

    @Override
    public Optional<String> conflictKeyForEdge(EdgeId edgeId) {
      return edge.id().equals(edgeId) ? Optional.of(conflictKey) : Optional.empty();
    }
  }

  private static RailGraph graphWithSwitcher(
      NodeId a, NodeId switcher, NodeId b, int length1, int length2) {
    RailEdge edge1 =
        new RailEdge(
            EdgeId.undirected(a, switcher), a, switcher, length1, -1.0, true, Optional.empty());
    RailEdge edge2 =
        new RailEdge(
            EdgeId.undirected(switcher, b), switcher, b, length2, -1.0, true, Optional.empty());
    return new SimpleRailGraph(
        Map.of(
            a, new RailNodeTest(a),
            switcher, new RailNodeTest(switcher, NodeType.SWITCHER, Optional.empty()),
            b, new RailNodeTest(b)),
        Map.of(edge1.id(), edge1, edge2.id(), edge2),
        Set.of());
  }

  private static RailGraph graphWithApproachAndSwitcher(
      NodeId a, NodeId approach, NodeId switcher, NodeId b, int length1, int length2, int length3) {
    Map<NodeId, RailNode> nodes = new LinkedHashMap<>();
    nodes.put(a, new RailNodeTest(a));
    nodes.put(approach, new RailNodeTest(approach));
    nodes.put(switcher, new RailNodeTest(switcher, NodeType.SWITCHER, Optional.empty()));
    nodes.put(b, new RailNodeTest(b));
    Map<EdgeId, RailEdge> edges = new LinkedHashMap<>();
    addEdge(edges, a, approach, length1);
    addEdge(edges, approach, switcher, length2);
    addEdge(edges, switcher, b, length3);
    return new SimpleRailGraph(nodes, edges, Set.of());
  }

  private static RailGraph graphWithSwitcherExit(
      NodeId a, NodeId switcher, NodeId exit, NodeId b, int length1, int length2, int length3) {
    return graphWithSwitcherExit(a, switcher, exit, b, null, length1, length2, length3, 0);
  }

  private static RailGraph graphWithSwitcherExit(
      NodeId a,
      NodeId switcher,
      NodeId exit,
      NodeId b,
      NodeId c,
      int length1,
      int length2,
      int length3,
      int length4) {
    Map<NodeId, RailNode> nodes = new LinkedHashMap<>();
    nodes.put(a, new RailNodeTest(a));
    nodes.put(switcher, new RailNodeTest(switcher, NodeType.SWITCHER, Optional.empty()));
    nodes.put(exit, new RailNodeTest(exit));
    nodes.put(b, new RailNodeTest(b));
    if (c != null) {
      nodes.put(c, new RailNodeTest(c));
    }
    Map<EdgeId, RailEdge> edges = new LinkedHashMap<>();
    addEdge(edges, a, switcher, length1);
    addEdge(edges, switcher, exit, length2);
    addEdge(edges, exit, b, length3);
    if (c != null) {
      addEdge(edges, b, c, length4);
    }
    return new SimpleRailGraph(nodes, edges, Set.of());
  }

  private static void addEdge(Map<EdgeId, RailEdge> edges, NodeId from, NodeId to, int length) {
    EdgeId edgeId = EdgeId.undirected(from, to);
    edges.put(edgeId, new RailEdge(edgeId, from, to, length, -1.0, true, Optional.empty()));
  }

  private static RailGraph graphWithTypedSingleEdge(NodeId from, RailNode to, int lengthBlocks) {
    RailEdge edge =
        new RailEdge(
            EdgeId.undirected(from, to.id()),
            from,
            to.id(),
            lengthBlocks,
            -1.0,
            true,
            Optional.empty());
    return new SimpleRailGraph(
        Map.of(from, new RailNodeTest(from), to.id(), to), Map.of(edge.id(), edge), Set.of());
  }

  private static RailGraph graphWithStationSwitcherPath(
      NodeId start,
      NodeId waypoint,
      NodeId switcher,
      NodeId station,
      int length1,
      int length2,
      int length3) {
    RailEdge edge1 =
        new RailEdge(
            EdgeId.undirected(start, waypoint),
            start,
            waypoint,
            length1,
            -1.0,
            true,
            Optional.empty());
    RailEdge edge2 =
        new RailEdge(
            EdgeId.undirected(waypoint, switcher),
            waypoint,
            switcher,
            length2,
            -1.0,
            true,
            Optional.empty());
    RailEdge edge3 =
        new RailEdge(
            EdgeId.undirected(switcher, station),
            switcher,
            station,
            length3,
            -1.0,
            true,
            Optional.empty());
    return new SimpleRailGraph(
        Map.of(
            start, new RailNodeTest(start),
            waypoint, new RailNodeTest(waypoint),
            switcher, new RailNodeTest(switcher, NodeType.SWITCHER, Optional.empty()),
            station,
                new RailNodeTest(
                    station,
                    NodeType.STATION,
                    Optional.of(WaypointMetadata.station("OP", "CENTRAL", 1)))),
        Map.of(edge1.id(), edge1, edge2.id(), edge2, edge3.id(), edge3),
        Set.of());
  }

  private static SignNodeRegistry registryWithStation(UUID worldId, NodeId station) {
    SignNodeRegistry registry = new SignNodeRegistry();
    registry.put(
        worldId,
        "world",
        0,
        64,
        0,
        new SignNodeDefinition(
            station,
            NodeType.STATION,
            Optional.of(station.value()),
            Optional.of(WaypointMetadata.station("OP", "CENTRAL", 1))));
    return registry;
  }

  private static ConfigManager.ConfigView withRuntimeSettings(
      ConfigManager.ConfigView base, ConfigManager.RuntimeSettings runtime) {
    return new ConfigManager.ConfigView(
        base.configVersion(),
        base.debugEnabled(),
        base.locale(),
        base.storageSettings(),
        base.graphSettings(),
        base.autoStationSettings(),
        runtime,
        base.spawnSettings(),
        base.trainConfigSettings(),
        base.reclaimSettings(),
        base.smartDispatcherSettings(),
        base.healthSettings());
  }

  private static ConfigManager.RuntimeSettings runtimeWithLookaheadEdges(
      ConfigManager.ConfigView base, int lookaheadEdges) {
    ConfigManager.RuntimeSettings runtime = base.runtimeSettings();
    return new ConfigManager.RuntimeSettings(
        runtime.dispatchTickIntervalTicks(),
        runtime.launchCooldownTicks(),
        lookaheadEdges,
        runtime.minClearEdges(),
        runtime.rearGuardEdges(),
        runtime.switcherZoneEdges(),
        runtime.approachSpeedBps(),
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
  }

  private static ConfigManager.RuntimeSettings runtimeWithRearGuardEdges(
      ConfigManager.ConfigView base, int rearGuardEdges) {
    ConfigManager.RuntimeSettings runtime = base.runtimeSettings();
    return new ConfigManager.RuntimeSettings(
        runtime.dispatchTickIntervalTicks(),
        runtime.launchCooldownTicks(),
        runtime.lookaheadEdges(),
        runtime.minClearEdges(),
        rearGuardEdges,
        runtime.switcherZoneEdges(),
        runtime.approachSpeedBps(),
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
  }

  private static ConfigManager.RuntimeSettings runtimeWithApproachSpeed(
      ConfigManager.ConfigView base, double approachSpeedBps) {
    ConfigManager.RuntimeSettings runtime = base.runtimeSettings();
    return new ConfigManager.RuntimeSettings(
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

    OccupancyManager occupancyManager = mockOccupancyManager();
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

    OccupancyManager occupancyManager = mockOccupancyManager();
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
  void shouldEnterLayoverAtTerminateStopOnlyWhenAtRouteTail() throws Exception {
    RouteDefinition reuseRoute =
        new RouteDefinition(
            RouteId.of("r"),
            List.of(NodeId.of("TERM"), NodeId.of("LAYOVER")),
            Optional.empty(),
            RouteLifecycleMode.REUSE_AT_TERM);
    RouteDefinition destroyRoute =
        new RouteDefinition(
            RouteId.of("r2"),
            List.of(NodeId.of("TERM")),
            Optional.empty(),
            RouteLifecycleMode.DESTROY_AFTER_TERM);
    RouteStop terminateStop =
        new RouteStop(
            UUID.randomUUID(),
            0,
            Optional.empty(),
            Optional.of("TERM"),
            Optional.empty(),
            RouteStopPassType.TERMINATE,
            Optional.empty());
    RouteStop stopStop =
        new RouteStop(
            UUID.randomUUID(),
            0,
            Optional.empty(),
            Optional.of("TERM"),
            Optional.empty(),
            RouteStopPassType.STOP,
            Optional.empty());

    RuntimeDispatchService service = createMinimalService();
    java.lang.reflect.Method method =
        RuntimeDispatchService.class.getDeclaredMethod(
            "shouldEnterLayoverAtTerminateStop", RouteDefinition.class, int.class, RouteStop.class);
    method.setAccessible(true);

    assertFalse((boolean) method.invoke(service, reuseRoute, 0, terminateStop));
    assertTrue((boolean) method.invoke(service, reuseRoute, 1, terminateStop));
    assertFalse((boolean) method.invoke(service, destroyRoute, 0, terminateStop));
    assertFalse((boolean) method.invoke(service, reuseRoute, 1, stopStop));
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

    OccupancyManager occupancyManager = mockOccupancyManager();
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
    // 占用释放已移至 handleTrainRemoved（由 GroupRemoveEvent 触发），
    // 避免 destroy 延迟 1 tick 期间 SpawnMonitor 误判可用而撞车。
    verify(occupancyManager, never()).releaseByTrain("train-1");

    // 模拟实体销毁后的清理
    service.handleTrainRemoved("train-1");
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

    OccupancyManager occupancyManager = mockOccupancyManager();
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
            base.runtimeSettings().movementAuthorityEnabled(),
            base.runtimeSettings().movementAuthorityStopMarginBlocks(),
            base.runtimeSettings().movementAuthorityCautionMarginBlocks(),
            base.runtimeSettings().speedCommandHysteresisBps(),
            base.runtimeSettings().speedCommandAccelFactor(),
            base.runtimeSettings().speedCommandDecelFactor(),
            base.runtimeSettings().distanceCacheRefreshSeconds(),
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
                base.reclaimSettings(),
                base.healthSettings()));

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

    OccupancyManager occupancyManager = mockOccupancyManager();
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

  @Test
  void proceedKeepsEdgeLimitWithoutCautionOrNodeDistanceCurve() {
    NodeId a = NodeId.of("A");
    NodeId b = NodeId.of("B");
    RouteDefinition route = new RouteDefinition(RouteId.of("r"), List.of(a, b), Optional.empty());
    TagStore tags =
        new TagStore(
            "train-1",
            "FTA_OPERATOR_CODE=op",
            "FTA_LINE_CODE=l1",
            "FTA_ROUTE_CODE=r1",
            "FTA_ROUTE_INDEX=0");
    UUID worldId = UUID.randomUUID();

    ConfigManager configManager = mock(ConfigManager.class);
    ConfigManager.ConfigView base = testConfigView(1, 40.0);
    when(configManager.current())
        .thenReturn(withRuntimeSettings(base, runtimeWithApproachSpeed(base, 6.0)));

    RailGraphService railGraphService = mock(RailGraphService.class);
    when(railGraphService.getSnapshot(worldId))
        .thenReturn(
            Optional.of(
                new RailGraphService.RailGraphSnapshot(
                    graphWithSingleEdge(a, b, 50), Instant.now())));
    when(railGraphService.effectiveSpeedLimitBlocksPerSecond(any(), any(), any(), anyDouble()))
        .thenReturn(28.8);

    RouteDefinitionCache routeDefinitions = mock(RouteDefinitionCache.class);
    when(routeDefinitions.findByCodes("op", "l1", "r1")).thenReturn(Optional.of(route));

    OccupancyManager occupancyManager = mockOccupancyManager();
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

    ControlDiagnostics diagnostics = service.getDiagnostics("train-1").orElseThrow();
    assertEquals(SignalAspect.PROCEED, diagnostics.currentSignal());
    assertEquals(28.8, diagnostics.edgeLimitBps(), 1.0e-6);
    assertTrue(diagnostics.finalTargetBps() < 28.8);
    assertEquals("movement_authority", diagnostics.finalLimiterSource());
    assertTrue(diagnostics.movementAuthorityLimitBps().isPresent());
    assertEquals(diagnostics.finalTargetBps() / 20.0, speedCaptor.getValue(), 1.0e-6);
    assertTrue(diagnostics.speedCurveLimitBps().isEmpty());
  }

  @Test
  void passStationDoesNotTriggerApproachLimiter() {
    NodeId a = NodeId.of("A");
    NodeId station = NodeId.of("OP:S:CENTRAL:1");
    RouteDefinition route =
        new RouteDefinition(RouteId.of("r"), List.of(a, station), Optional.empty());
    TagStore tags =
        new TagStore(
            "train-1",
            "FTA_OPERATOR_CODE=op",
            "FTA_LINE_CODE=l1",
            "FTA_ROUTE_CODE=r1",
            "FTA_ROUTE_INDEX=0");
    UUID worldId = UUID.randomUUID();

    ConfigManager configManager = mock(ConfigManager.class);
    ConfigManager.ConfigView base = testConfigView(1, 40.0);
    when(configManager.current())
        .thenReturn(withRuntimeSettings(base, runtimeWithApproachSpeed(base, 6.0)));

    RailGraphService railGraphService = mock(RailGraphService.class);
    when(railGraphService.getSnapshot(worldId))
        .thenReturn(
            Optional.of(
                new RailGraphService.RailGraphSnapshot(
                    graphWithSingleEdge(a, station, 50), Instant.now())));
    when(railGraphService.effectiveSpeedLimitBlocksPerSecond(any(), any(), any(), anyDouble()))
        .thenReturn(28.8);

    RouteDefinitionCache routeDefinitions = mock(RouteDefinitionCache.class);
    when(routeDefinitions.findByCodes("op", "l1", "r1")).thenReturn(Optional.of(route));
    when(routeDefinitions.findStop(any(), eq(1)))
        .thenReturn(Optional.of(routeStop(1, station, RouteStopPassType.PASS)));

    OccupancyManager occupancyManager = mockOccupancyManager();
    when(occupancyManager.canEnter(any())).thenAnswer(allowProceed());
    when(occupancyManager.acquire(any())).thenAnswer(allowProceed());

    RuntimeDispatchService service =
        new RuntimeDispatchService(
            occupancyManager,
            railGraphService,
            routeDefinitions,
            new RouteProgressRegistry(),
            registryWithStation(worldId, station),
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

    ControlDiagnostics diagnostics = service.getDiagnostics("train-1").orElseThrow();
    assertEquals("none", diagnostics.approachKind());
    assertEquals("movement_authority", diagnostics.finalLimiterSource());
    assertEquals(diagnostics.finalTargetBps() / 20.0, speedCaptor.getValue(), 1.0e-6);
  }

  @Test
  void stopStationTriggersApproachLimiter() {
    NodeId a = NodeId.of("A");
    NodeId station = NodeId.of("OP:S:CENTRAL:1");
    RouteDefinition route =
        new RouteDefinition(RouteId.of("r"), List.of(a, station), Optional.empty());
    TagStore tags =
        new TagStore(
            "train-1",
            "FTA_OPERATOR_CODE=op",
            "FTA_LINE_CODE=l1",
            "FTA_ROUTE_CODE=r1",
            "FTA_ROUTE_INDEX=0");
    UUID worldId = UUID.randomUUID();

    ConfigManager configManager = mock(ConfigManager.class);
    ConfigManager.ConfigView base = testConfigView(1, 40.0);
    when(configManager.current())
        .thenReturn(withRuntimeSettings(base, runtimeWithApproachSpeed(base, 6.0)));

    RailGraphService railGraphService = mock(RailGraphService.class);
    when(railGraphService.getSnapshot(worldId))
        .thenReturn(
            Optional.of(
                new RailGraphService.RailGraphSnapshot(
                    graphWithSingleEdge(a, station, 50), Instant.now())));
    when(railGraphService.effectiveSpeedLimitBlocksPerSecond(any(), any(), any(), anyDouble()))
        .thenReturn(28.8);

    RouteDefinitionCache routeDefinitions = mock(RouteDefinitionCache.class);
    when(routeDefinitions.findByCodes("op", "l1", "r1")).thenReturn(Optional.of(route));
    when(routeDefinitions.findStop(any(), eq(1)))
        .thenReturn(Optional.of(routeStop(1, station, RouteStopPassType.STOP)));

    OccupancyManager occupancyManager = mockOccupancyManager();
    when(occupancyManager.canEnter(any())).thenAnswer(allowProceed());
    when(occupancyManager.acquire(any())).thenAnswer(allowProceed());

    RuntimeDispatchService service =
        new RuntimeDispatchService(
            occupancyManager,
            railGraphService,
            routeDefinitions,
            new RouteProgressRegistry(),
            registryWithStation(worldId, station),
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

    assertEquals(6.0 / 20.0, speedCaptor.getValue(), 1.0e-6);
    ControlDiagnostics diagnostics = service.getDiagnostics("train-1").orElseThrow();
    assertEquals("station", diagnostics.approachKind());
    assertEquals(station, diagnostics.approachNode());
    assertEquals("approach", diagnostics.finalLimiterSource());
  }

  @Test
  void farStopStationDoesNotTriggerApproachBeforeWindow() {
    NodeId a = NodeId.of("A");
    NodeId station = NodeId.of("OP:S:CENTRAL:1");
    RouteDefinition route =
        new RouteDefinition(RouteId.of("r"), List.of(a, station), Optional.empty());
    TagStore tags =
        new TagStore(
            "train-1",
            "FTA_OPERATOR_CODE=op",
            "FTA_LINE_CODE=l1",
            "FTA_ROUTE_CODE=r1",
            "FTA_ROUTE_INDEX=0");
    UUID worldId = UUID.randomUUID();

    ConfigManager configManager = mock(ConfigManager.class);
    ConfigManager.ConfigView base = testConfigView(1, 40.0);
    when(configManager.current())
        .thenReturn(withRuntimeSettings(base, runtimeWithApproachSpeed(base, 6.0)));

    RailGraphService railGraphService = mock(RailGraphService.class);
    when(railGraphService.getSnapshot(worldId))
        .thenReturn(
            Optional.of(
                new RailGraphService.RailGraphSnapshot(
                    graphWithSingleEdge(a, station, 160), Instant.now())));
    when(railGraphService.effectiveSpeedLimitBlocksPerSecond(any(), any(), any(), anyDouble()))
        .thenReturn(28.8);

    RouteDefinitionCache routeDefinitions = mock(RouteDefinitionCache.class);
    when(routeDefinitions.findByCodes("op", "l1", "r1")).thenReturn(Optional.of(route));
    when(routeDefinitions.findStop(any(), eq(1)))
        .thenReturn(Optional.of(routeStop(1, station, RouteStopPassType.STOP)));

    OccupancyManager occupancyManager = mockOccupancyManager();
    when(occupancyManager.canEnter(any())).thenAnswer(allowProceed());
    when(occupancyManager.acquire(any())).thenAnswer(allowProceed());

    RuntimeDispatchService service =
        new RuntimeDispatchService(
            occupancyManager,
            railGraphService,
            routeDefinitions,
            new RouteProgressRegistry(),
            registryWithStation(worldId, station),
            mock(LayoverRegistry.class),
            new DwellRegistry(),
            configManager,
            null,
            new TrainConfigResolver(),
            null);

    FakeTrain train = new FakeTrain(worldId, tags.properties(), false, 0.0);
    service.handleSignalTick(train, true);

    ControlDiagnostics diagnostics = service.getDiagnostics("train-1").orElseThrow();
    assertEquals("none", diagnostics.approachKind());
    assertEquals("movement_authority", diagnostics.finalLimiterSource());
  }

  @Test
  void expandedRouteApproachTreatsStationAdjacentSwitcherAsStation() {
    NodeId a = NodeId.of("A");
    NodeId waypoint = NodeId.of("W");
    NodeId switcher = NodeId.of("SW");
    NodeId station = NodeId.of("OP:S:CENTRAL:1");
    RouteDefinition route =
        new RouteDefinition(RouteId.of("r"), List.of(a, station), Optional.empty());
    TagStore tags =
        new TagStore(
            "train-1",
            "FTA_OPERATOR_CODE=op",
            "FTA_LINE_CODE=l1",
            "FTA_ROUTE_CODE=r1",
            "FTA_ROUTE_INDEX=0");
    UUID worldId = UUID.randomUUID();

    ConfigManager configManager = mock(ConfigManager.class);
    ConfigManager.ConfigView base = testConfigView(1, 40.0);
    ConfigManager.ConfigView approachConfig =
        withRuntimeSettings(base, runtimeWithApproachSpeed(base, 6.0));
    when(configManager.current())
        .thenReturn(withSmartDispatcherMode(approachConfig, SmartDispatcherMode.OFF));

    RailGraph graph = graphWithStationSwitcherPath(a, waypoint, switcher, station, 80, 10, 20);
    RailGraphService railGraphService = mock(RailGraphService.class);
    when(railGraphService.getSnapshot(worldId))
        .thenReturn(Optional.of(new RailGraphService.RailGraphSnapshot(graph, Instant.now())));
    when(railGraphService.effectiveSpeedLimitBlocksPerSecond(any(), any(), any(), anyDouble()))
        .thenReturn(28.8);

    RouteDefinitionCache routeDefinitions = mock(RouteDefinitionCache.class);
    when(routeDefinitions.findByCodes("op", "l1", "r1")).thenReturn(Optional.of(route));
    when(routeDefinitions.findStop(any(), eq(1)))
        .thenReturn(Optional.of(routeStop(1, station, RouteStopPassType.STOP)));

    OccupancyManager occupancyManager = mockOccupancyManager();
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

    ControlDiagnostics diagnostics = service.getDiagnostics("train-1").orElseThrow();
    assertEquals("station", diagnostics.approachKind());
    assertEquals(switcher, diagnostics.approachNode());
    assertEquals(90L, diagnostics.distanceToApproach().orElseThrow());
    assertEquals("approach", diagnostics.finalLimiterSource());
  }

  @Test
  void stationApproachEnvelopeUsesTrainDecelConfig() {
    NodeId a = NodeId.of("A");
    NodeId waypoint = NodeId.of("W");
    NodeId switcher = NodeId.of("SW");
    NodeId station = NodeId.of("OP:S:CENTRAL:1");
    RouteDefinition route =
        new RouteDefinition(RouteId.of("r"), List.of(a, station), Optional.empty());
    TagStore tags =
        new TagStore(
            "train-1",
            "FTA_OPERATOR_CODE=op",
            "FTA_LINE_CODE=l1",
            "FTA_ROUTE_CODE=r1",
            "FTA_ROUTE_INDEX=0",
            "FTA_TRAIN_DECEL_BPS2=0.25");
    UUID worldId = UUID.randomUUID();

    ConfigManager configManager = mock(ConfigManager.class);
    ConfigManager.ConfigView base = testConfigView(1, 40.0);
    ConfigManager.ConfigView approachConfig =
        withRuntimeSettings(base, runtimeWithApproachSpeed(base, 6.0));
    when(configManager.current())
        .thenReturn(withSmartDispatcherMode(approachConfig, SmartDispatcherMode.OFF));

    RailGraph graph = graphWithStationSwitcherPath(a, waypoint, switcher, station, 110, 10, 20);
    RailGraphService railGraphService = mock(RailGraphService.class);
    when(railGraphService.getSnapshot(worldId))
        .thenReturn(Optional.of(new RailGraphService.RailGraphSnapshot(graph, Instant.now())));
    when(railGraphService.effectiveSpeedLimitBlocksPerSecond(any(), any(), any(), anyDouble()))
        .thenReturn(28.8);

    RouteDefinitionCache routeDefinitions = mock(RouteDefinitionCache.class);
    when(routeDefinitions.findByCodes("op", "l1", "r1")).thenReturn(Optional.of(route));
    when(routeDefinitions.findStop(any(), eq(1)))
        .thenReturn(Optional.of(routeStop(1, station, RouteStopPassType.STOP)));

    OccupancyManager occupancyManager = mockOccupancyManager();
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

    ControlDiagnostics diagnostics = service.getDiagnostics("train-1").orElseThrow();
    double expectedApproachEnvelope = Math.sqrt(6.0 * 6.0 + 2.0 * 0.25 * (120.0 - 10.0));
    assertEquals("station", diagnostics.approachKind());
    assertEquals("approach_curve", diagnostics.finalLimiterSource(), diagnostics.toString());
    assertTrue(diagnostics.approachReason().contains("target_edge_distance=10"));
    assertTrue(diagnostics.approachReason().contains("preview=true"));
    assertTrue(diagnostics.movementAuthorityLimitBps().isEmpty(), diagnostics.toString());
    assertEquals(expectedApproachEnvelope, diagnostics.finalTargetBps(), 1.0e-6);
  }

  @Test
  void depotStopUsesDepotApproachLimiter() {
    NodeId a = NodeId.of("A");
    NodeId depot = NodeId.of("OP:D:YARD:1");
    RouteDefinition route =
        new RouteDefinition(RouteId.of("r"), List.of(a, depot), Optional.empty());
    TagStore tags =
        new TagStore(
            "train-1",
            "FTA_OPERATOR_CODE=op",
            "FTA_LINE_CODE=l1",
            "FTA_ROUTE_CODE=r1",
            "FTA_ROUTE_INDEX=0");
    UUID worldId = UUID.randomUUID();

    ConfigManager configManager = mock(ConfigManager.class);
    when(configManager.current()).thenReturn(testConfigView(1, 40.0));

    RailGraph graph =
        graphWithTypedSingleEdge(
            a,
            new RailNodeTest(
                depot, NodeType.DEPOT, Optional.of(WaypointMetadata.depot("OP", "YARD", 1))),
            50);
    RailGraphService railGraphService = mock(RailGraphService.class);
    when(railGraphService.getSnapshot(worldId))
        .thenReturn(Optional.of(new RailGraphService.RailGraphSnapshot(graph, Instant.now())));
    when(railGraphService.effectiveSpeedLimitBlocksPerSecond(any(), any(), any(), anyDouble()))
        .thenReturn(28.8);

    RouteDefinitionCache routeDefinitions = mock(RouteDefinitionCache.class);
    when(routeDefinitions.findByCodes("op", "l1", "r1")).thenReturn(Optional.of(route));
    when(routeDefinitions.findStop(any(), eq(1)))
        .thenReturn(Optional.of(routeStop(1, depot, RouteStopPassType.TERMINATE)));

    OccupancyManager occupancyManager = mockOccupancyManager();
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

    ControlDiagnostics diagnostics = service.getDiagnostics("train-1").orElseThrow();
    assertEquals("depot", diagnostics.approachKind());
    assertEquals(depot, diagnostics.approachNode());
    assertEquals("depot_approach", diagnostics.finalLimiterSource());
    assertEquals(3.5, diagnostics.finalTargetBps(), 1.0e-6);
  }

  @Test
  void cautionDiagnosticsExposeComponentCautionLimiter() {
    NodeId a = NodeId.of("A");
    NodeId b = NodeId.of("B");
    RouteDefinition route = new RouteDefinition(RouteId.of("r"), List.of(a, b), Optional.empty());
    TagStore tags =
        new TagStore(
            "train-1",
            "FTA_OPERATOR_CODE=op",
            "FTA_LINE_CODE=l1",
            "FTA_ROUTE_CODE=r1",
            "FTA_ROUTE_INDEX=0");
    UUID worldId = UUID.randomUUID();

    ConfigManager configManager = mock(ConfigManager.class);
    when(configManager.current()).thenReturn(testConfigView(1, 40.0));

    RailGraphService railGraphService = mock(RailGraphService.class);
    when(railGraphService.getSnapshot(worldId))
        .thenReturn(
            Optional.of(
                new RailGraphService.RailGraphSnapshot(
                    graphWithSingleEdge(a, b, 50), Instant.now())));
    when(railGraphService.effectiveSpeedLimitBlocksPerSecond(any(), any(), any(), anyDouble()))
        .thenReturn(28.8);
    when(railGraphService.componentKey(worldId, b)).thenReturn(Optional.of("component-a"));
    when(railGraphService.componentCautionSpeedBlocksPerSecond(worldId, "component-a"))
        .thenReturn(OptionalDouble.of(9.0));

    RouteDefinitionCache routeDefinitions = mock(RouteDefinitionCache.class);
    when(routeDefinitions.findByCodes("op", "l1", "r1")).thenReturn(Optional.of(route));

    OccupancyManager occupancyManager = mockOccupancyManager();
    when(occupancyManager.canEnter(any()))
        .thenAnswer(
            inv -> {
              OccupancyRequest request = inv.getArgument(0);
              return new OccupancyDecision(true, request.now(), SignalAspect.CAUTION, List.of());
            });
    when(occupancyManager.acquire(any()))
        .thenAnswer(
            inv -> {
              OccupancyRequest request = inv.getArgument(0);
              return new OccupancyDecision(true, request.now(), SignalAspect.CAUTION, List.of());
            });

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

    ControlDiagnostics diagnostics = service.getDiagnostics("train-1").orElseThrow();
    assertEquals(SignalAspect.CAUTION, diagnostics.currentSignal());
    assertEquals("component", diagnostics.cautionSource());
    assertEquals(9.0, diagnostics.finalTargetBps(), 1.0e-6);
    assertEquals("component_caution", diagnostics.finalLimiterSource());
  }

  @Test
  void movementAuthorityHardBlockerAppliesStopInsteadOfSpeedCurve() {
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
    ConfigManager.ConfigView base = testConfigView(1, 40.0);
    when(configManager.current())
        .thenReturn(withRuntimeSettings(base, runtimeWithLookaheadEdges(base, 2)));

    RailGraphService railGraphService = mock(RailGraphService.class);
    when(railGraphService.getSnapshot(worldId))
        .thenReturn(
            Optional.of(
                new RailGraphService.RailGraphSnapshot(
                    graphWithTwoEdges(a, b, c, 10, 10), Instant.now())));
    when(railGraphService.effectiveSpeedLimitBlocksPerSecond(any(), any(), any(), anyDouble()))
        .thenReturn(28.8);

    RouteDefinitionCache routeDefinitions = mock(RouteDefinitionCache.class);
    when(routeDefinitions.findByCodes("op", "l1", "r1")).thenReturn(Optional.of(route));

    OccupancyResource blocker = OccupancyResource.forEdge(EdgeId.undirected(b, c));
    OccupancyManager occupancyManager = mockOccupancyManager();
    when(occupancyManager.canEnter(any()))
        .thenAnswer(
            inv -> {
              OccupancyRequest request = inv.getArgument(0);
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

    ControlDiagnostics diagnostics = service.getDiagnostics("train-1").orElseThrow();
    assertEquals(SignalAspect.STOP, diagnostics.currentSignal());
    assertEquals(0.0, diagnostics.finalTargetBps(), 1.0e-6);
    assertEquals("stop", diagnostics.finalLimiterSource());
    assertEquals(0, train.hardStopCalls);
  }

  @Test
  void movementAuthorityIgnoresArtificialWindowWhenAuthorityEndIsTooClose() {
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
    when(configManager.current()).thenReturn(testConfigView(1, 40.0));

    RailGraphService railGraphService = mock(RailGraphService.class);
    when(railGraphService.getSnapshot(worldId))
        .thenReturn(
            Optional.of(
                new RailGraphService.RailGraphSnapshot(
                    graphWithTwoEdges(a, b, c, 10, 50), Instant.now())));
    when(railGraphService.effectiveSpeedLimitBlocksPerSecond(any(), any(), any(), anyDouble()))
        .thenReturn(40.0);

    RouteDefinitionCache routeDefinitions = mock(RouteDefinitionCache.class);
    when(routeDefinitions.findByCodes("op", "l1", "r1")).thenReturn(Optional.of(route));

    OccupancyManager occupancyManager = mockOccupancyManager();
    when(occupancyManager.snapshotClaims()).thenReturn(List.of());
    when(occupancyManager.canEnter(any()))
        .thenAnswer(
            inv -> {
              OccupancyRequest request = inv.getArgument(0);
              return new OccupancyDecision(true, request.now(), SignalAspect.PROCEED, List.of());
            });
    when(occupancyManager.acquire(any()))
        .thenAnswer(
            inv -> {
              OccupancyRequest request = inv.getArgument(0);
              return new OccupancyDecision(true, request.now(), SignalAspect.PROCEED, List.of());
            });

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

    ControlDiagnostics diagnostics = service.getDiagnostics("train-1").orElseThrow();
    assertEquals(SignalAspect.PROCEED, diagnostics.currentSignal());
    assertEquals(10L, diagnostics.distanceToAuthorityEnd().orElse(-1));
    assertEquals("EDGE:B~C", diagnostics.authorityEndResource());
    assertEquals(1, diagnostics.authorizedEdgeCount());
  }

  @Test
  void movementAuthorityKeepsProceedWhenAuthorityEndIsFarEnough() {
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
    when(configManager.current()).thenReturn(testConfigView(1, 40.0));

    RailGraphService railGraphService = mock(RailGraphService.class);
    when(railGraphService.getSnapshot(worldId))
        .thenReturn(
            Optional.of(
                new RailGraphService.RailGraphSnapshot(
                    graphWithTwoEdges(a, b, c, 60, 50), Instant.now())));
    when(railGraphService.effectiveSpeedLimitBlocksPerSecond(any(), any(), any(), anyDouble()))
        .thenReturn(40.0);

    RouteDefinitionCache routeDefinitions = mock(RouteDefinitionCache.class);
    when(routeDefinitions.findByCodes("op", "l1", "r1")).thenReturn(Optional.of(route));

    OccupancyManager occupancyManager = mockOccupancyManager();
    when(occupancyManager.snapshotClaims()).thenReturn(List.of());
    when(occupancyManager.canEnter(any()))
        .thenAnswer(
            inv -> {
              OccupancyRequest request = inv.getArgument(0);
              return new OccupancyDecision(true, request.now(), SignalAspect.PROCEED, List.of());
            });
    when(occupancyManager.acquire(any()))
        .thenAnswer(
            inv -> {
              OccupancyRequest request = inv.getArgument(0);
              return new OccupancyDecision(true, request.now(), SignalAspect.PROCEED, List.of());
            });

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

    FakeTrain train = new FakeTrain(worldId, tags.properties(), false, 0.25);
    service.handleSignalTick(train, true);

    ControlDiagnostics diagnostics = service.getDiagnostics("train-1").orElseThrow();
    assertEquals(SignalAspect.PROCEED, diagnostics.currentSignal());
    assertEquals(60L, diagnostics.distanceToAuthorityEnd().orElse(-1));
    assertEquals(1, diagnostics.authorizedEdgeCount());
  }

  @Test
  void diagnosticsRecordRetainedDestinationWhenAuthorizationBlocks() {
    NodeId a = NodeId.of("A");
    NodeId b = NodeId.of("B");
    RouteDefinition route = new RouteDefinition(RouteId.of("r"), List.of(a, b), Optional.empty());
    TagStore tags =
        new TagStore(
            "train-1",
            "FTA_OPERATOR_CODE=op",
            "FTA_LINE_CODE=l1",
            "FTA_ROUTE_CODE=r1",
            "FTA_ROUTE_INDEX=0");
    java.util.concurrent.atomic.AtomicReference<String> destination =
        new java.util.concurrent.atomic.AtomicReference<>("NEXT");
    when(tags.properties().getDestination()).thenAnswer(inv -> destination.get());
    org.mockito.Mockito.doAnswer(
            inv -> {
              destination.set("");
              return null;
            })
        .when(tags.properties())
        .clearDestination();
    UUID worldId = UUID.randomUUID();

    ConfigManager configManager = mock(ConfigManager.class);
    when(configManager.current()).thenReturn(testConfigView(1, 40.0));

    RailGraphService railGraphService = mock(RailGraphService.class);
    when(railGraphService.getSnapshot(worldId))
        .thenReturn(
            Optional.of(
                new RailGraphService.RailGraphSnapshot(
                    graphWithSingleEdge(a, b, 20), Instant.now())));
    when(railGraphService.effectiveSpeedLimitBlocksPerSecond(any(), any(), any(), anyDouble()))
        .thenReturn(40.0);

    RouteDefinitionCache routeDefinitions = mock(RouteDefinitionCache.class);
    when(routeDefinitions.findByCodes("op", "l1", "r1")).thenReturn(Optional.of(route));

    OccupancyResource blocker = OccupancyResource.forNode(b);
    OccupancyManager occupancyManager = mockOccupancyManager();
    when(occupancyManager.snapshotClaims()).thenReturn(List.of());
    when(occupancyManager.canEnter(any()))
        .thenAnswer(
            inv -> {
              OccupancyRequest request = inv.getArgument(0);
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

    ControlDiagnostics diagnostics = service.getDiagnostics("train-1").orElseThrow();
    assertTrue(diagnostics.destinationPresentWhileBlocked());
    assertEquals("NEXT", diagnostics.retainedDestination());
    assertEquals("BLOCKED_BY_OCCUPANCY", diagnostics.blockedReason());
    RuntimeStopState stopState = service.getActiveStopState("train-1").orElseThrow();
    assertEquals("BLOCKED_BY_OCCUPANCY", stopState.reasonCode());
    assertEquals(
        RuntimeStopState.ReleaseCondition.BLOCKING_RESOURCES_RELEASED_OR_TRANSFERRED,
        stopState.releaseCondition());
    assertEquals(
        RuntimeStopState.RetryTrigger.OCCUPANCY_CHANGE_OR_PERIODIC_RECHECK,
        stopState.retryTrigger());
    assertEquals(
        List.of(
            new RuntimeStopState.Blocker(
                blocker.toString(), "other", ClaimRole.MOVEMENT_REQUIRED.name())),
        stopState.blockers());
    assertFalse(stopState.invalidatesAuthority());
    verify(tags.properties(), never()).clearDestinationRoute();
    verify(tags.properties(), never()).clearDestination();
    verify(tags.properties(), never()).setDestination(any());
    assertEquals(0, train.hardStopCalls);
  }

  @Test
  void stopFromProtectiveRetainDoesNotInvalidateToken() throws Exception {
    List<String> debugMessages = new ArrayList<>();
    RuntimeDispatchService service = createMinimalService(mockOccupancyManager(), debugMessages);
    NodeId a = NodeId.of("A");
    NodeId b = NodeId.of("B");
    OccupancyResource resource = OccupancyResource.forNode(b);
    OccupancyRequest request = completeTwoNodeAuthorityRequest("train-1", Instant.now(), a, b);
    RouteDefinition route = new RouteDefinition(RouteId.of("r"), List.of(a, b), Optional.empty());
    TagStore tags = new TagStore("train-1", "FTA_ROUTE_INDEX=0");
    when(tags.properties().getDestination()).thenReturn("NEXT");
    FakeTrain train = new FakeTrain(UUID.randomUUID(), tags.properties(), false, 0.0);

    java.lang.reflect.Method issue =
        RuntimeDispatchService.class.getDeclaredMethod(
            "issueMovementAuthorizationToken",
            String.class,
            NodeId.class,
            NodeId.class,
            OccupancyRequest.class,
            SignalAspect.class,
            Instant.class);
    java.lang.reflect.Method activate =
        RuntimeDispatchService.class.getDeclaredMethod(
            "activateMovementAuthorizationTokenRetainingInhibitor",
            String.class,
            MovementAuthorizationToken.class,
            String.class);
    java.lang.reflect.Method valid =
        RuntimeDispatchService.class.getDeclaredMethod(
            "hasValidMovementAuthorization", String.class, NodeId.class, NodeId.class, List.class);
    java.lang.reflect.Method protectiveStop =
        RuntimeDispatchService.class.getDeclaredMethod(
            "applyProtectiveOnlyStop",
            RuntimeTrainHandle.class,
            TrainProperties.class,
            String.class,
            RouteDefinition.class,
            NodeId.class,
            NodeId.class,
            OccupancyDecision.class);
    issue.setAccessible(true);
    activate.setAccessible(true);
    valid.setAccessible(true);
    protectiveStop.setAccessible(true);

    MovementAuthorizationToken token =
        (MovementAuthorizationToken)
            issue.invoke(service, "train-1", a, b, request, SignalAspect.PROCEED, Instant.now());
    assertTrue((boolean) activate.invoke(service, "train-1", token, "NEXT"));
    OccupancyDecision decision =
        new OccupancyDecision(
            false,
            Instant.now(),
            SignalAspect.STOP,
            List.of(
                new OccupancyClaim(
                    resource,
                    "other",
                    Optional.empty(),
                    Instant.now(),
                    Duration.ZERO,
                    Optional.empty(),
                    ClaimRole.PROTECTIVE_RETAIN)));

    protectiveStop.invoke(service, train, tags.properties(), "train-1", route, a, b, decision);

    assertFalse(service.isMovementInhibited("train-1"));
    assertTrue((boolean) valid.invoke(service, "train-1", a, b, List.of(resource)));
    assertTrue(
        debugMessages.stream()
            .anyMatch(
                message ->
                    message.contains("STALE_PROTECTIVE_RETAIN_CANDIDATE")
                        && message.contains("releaseCandidate=false")
                        && message.contains("releaseBlockedReason=no-self-retain-candidate")));
    verify(tags.properties(), never()).clearDestinationRoute();
    verify(tags.properties(), never()).clearDestination();
    verify(tags.properties(), never()).setDestination(any());
  }

  @Test
  void identicalPhysicalAuthorityReusesActiveMovementToken() throws Exception {
    RuntimeDispatchService service =
        createMinimalService(mockOccupancyManager(), new ArrayList<>());
    NodeId from = NodeId.of("A");
    NodeId destination = NodeId.of("B");
    OccupancyRequest request =
        completeTwoNodeAuthorityRequest(
            "train-1", Instant.parse("2026-08-17T12:00:00Z"), from, destination);
    java.lang.reflect.Method issue =
        RuntimeDispatchService.class.getDeclaredMethod(
            "issueMovementAuthorizationToken",
            String.class,
            NodeId.class,
            NodeId.class,
            OccupancyRequest.class,
            SignalAspect.class,
            Instant.class);
    java.lang.reflect.Method activate =
        RuntimeDispatchService.class.getDeclaredMethod(
            "activateMovementAuthorizationTokenRetainingInhibitor",
            String.class,
            MovementAuthorizationToken.class,
            String.class);
    issue.setAccessible(true);
    activate.setAccessible(true);

    MovementAuthorizationToken initial =
        (MovementAuthorizationToken)
            issue.invoke(
                service,
                "train-1",
                from,
                destination,
                request,
                SignalAspect.PROCEED,
                Instant.parse("2026-08-17T12:00:00Z"));
    assertTrue((boolean) activate.invoke(service, "train-1", initial, "B"));

    MovementAuthorizationToken repeated =
        (MovementAuthorizationToken)
            issue.invoke(
                service,
                "train-1",
                from,
                destination,
                request,
                SignalAspect.PROCEED,
                Instant.parse("2026-08-17T12:01:00Z"));

    assertEquals(initial.claimVersion(), repeated.claimVersion());
    assertTrue(repeated.active());
    assertEquals(Optional.of("B"), repeated.committedDestination());
  }

  @Test
  void activeTokenWithCurrentDestinationDoesNotRewriteTrainCartsDestination() throws Exception {
    RuntimeDispatchService service =
        createMinimalService(mockOccupancyManager(), new ArrayList<>());
    NodeId from = NodeId.of("A");
    NodeId destination = NodeId.of("B");
    TagStore tags = new TagStore("train-1");
    when(tags.properties().getDestination()).thenReturn("B");
    installMovementToken(
        service,
        new MovementAuthorizationToken(
                "train-1",
                1L,
                Instant.parse("2026-08-17T12:00:00Z"),
                from,
                destination,
                Optional.of(destination),
                1,
                List.of(OccupancyResource.forNode(from), OccupancyResource.forNode(destination)),
                SignalAspect.PROCEED)
            .activate("B"));
    java.lang.reflect.Method commit =
        RuntimeDispatchService.class.getDeclaredMethod(
            "commitAuthorizedDestination",
            TrainProperties.class,
            String.class,
            RouteDefinition.class,
            int.class,
            NodeId.class);
    commit.setAccessible(true);

    @SuppressWarnings("unchecked")
    Optional<String> committed =
        (Optional<String>)
            commit.invoke(service, tags.properties(), "train-1", null, 1, destination);

    assertEquals(Optional.of("B"), committed);
    verify(tags.properties(), never()).clearDestinationRoute();
    verify(tags.properties(), never()).setDestination(any());
  }

  @Test
  void edgeSpeedLookaheadOnlyLowersForForwardLowerSpeedConstraint() {
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
    EdgeId slowEdge = EdgeId.undirected(b, c);

    ConfigManager configManager = mock(ConfigManager.class);
    ConfigManager.ConfigView base = testConfigView(1, 40.0);
    when(configManager.current())
        .thenReturn(withRuntimeSettings(base, runtimeWithLookaheadEdges(base, 2)));

    RailGraphService railGraphService = mock(RailGraphService.class);
    when(railGraphService.getSnapshot(worldId))
        .thenReturn(
            Optional.of(
                new RailGraphService.RailGraphSnapshot(
                    graphWithTwoEdges(a, b, c, 50, 50), Instant.now())));
    when(railGraphService.effectiveSpeedLimitBlocksPerSecond(any(), any(), any(), anyDouble()))
        .thenAnswer(
            inv -> {
              RailEdge edge = inv.getArgument(1);
              return slowEdge.equals(edge.id()) ? 8.0 : 28.8;
            });

    RouteDefinitionCache routeDefinitions = mock(RouteDefinitionCache.class);
    when(routeDefinitions.findByCodes("op", "l1", "r1")).thenReturn(Optional.of(route));

    OccupancyManager occupancyManager = mockOccupancyManager();
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

    ControlDiagnostics diagnostics = service.getDiagnostics("train-1").orElseThrow();
    assertEquals(SignalAspect.PROCEED, diagnostics.currentSignal());
    assertTrue(diagnostics.edgeSpeedLookaheadMinBps().isPresent());
    assertEquals(
        Math.sqrt(8.0 * 8.0 + 2.0 * 1.0 * 50.0),
        diagnostics.edgeSpeedLookaheadMinBps().getAsDouble(),
        1.0e-6);
    assertEquals("edge_speed_lookahead", diagnostics.finalLimiterSource());
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

  @Test
  @SuppressWarnings("unchecked")
  void requestSignalReevaluationForResourcesIncludesClaimsAndQueues() throws Exception {
    OccupancyManager occupancyManager =
        mock(
            OccupancyManager.class,
            org.mockito.Mockito.withSettings().extraInterfaces(OccupancyQueueSupport.class));
    OccupancyQueueSupport queueSupport = (OccupancyQueueSupport) occupancyManager;
    OccupancyResource target = OccupancyResource.forConflict("switcher:test");
    OccupancyResource other = OccupancyResource.forConflict("switcher:other");
    Instant now = Instant.now();

    when(occupancyManager.snapshotClaims())
        .thenReturn(
            List.of(
                new OccupancyClaim(
                    target, "Train-A", Optional.empty(), now, Duration.ZERO, Optional.empty()),
                new OccupancyClaim(
                    target, "spawn-train", Optional.empty(), now, Duration.ZERO, Optional.empty()),
                new OccupancyClaim(
                    other, "Train-B", Optional.empty(), now, Duration.ZERO, Optional.empty())));
    when(queueSupport.snapshotQueues())
        .thenReturn(
            List.of(
                new OccupancyQueueSnapshot(
                    target,
                    Optional.empty(),
                    0,
                    List.of(
                        new OccupancyQueueEntry(
                            "Train-C", CorridorDirection.UNKNOWN, now, now, 0, 0),
                        new OccupancyQueueEntry(
                            "spawn-train", CorridorDirection.UNKNOWN, now, now, 0, 1))),
                new OccupancyQueueSnapshot(other, Optional.empty(), 0, List.of())));

    ConfigManager configManager = mock(ConfigManager.class);
    when(configManager.current()).thenReturn(testConfigView(20, 20.0));
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

    java.lang.reflect.Method method =
        RuntimeDispatchService.class.getDeclaredMethod(
            "collectImpactedTrainsForResourceReevaluation", Set.class, String.class);
    method.setAccessible(true);
    Map<String, String> impacted =
        (Map<String, String>) method.invoke(service, Set.of(target), "spawn-train");

    assertEquals(2, impacted.size());
    assertTrue(impacted.containsKey("train-a"));
    assertTrue(impacted.containsKey("train-c"));
    assertFalse(impacted.containsKey("spawn-train"));

    List<String> reevaluationRequests = new ArrayList<>();
    service.setSignalReevaluationRequester(reevaluationRequests::add);

    service.requestSignalReevaluationForResources(List.of(target), "spawn-train");

    assertEquals(List.of("Train-A", "Train-C"), reevaluationRequests);

    when(occupancyManager.snapshotClaims()).thenReturn(List.of());
    reevaluationRequests.clear();

    Map<String, String> queueOnlyImpacted =
        (Map<String, String>) method.invoke(service, Set.of(target), "spawn-train");

    assertEquals(Map.of("train-c", "Train-C"), queueOnlyImpacted);

    service.requestSignalReevaluationForResources(List.of(target), "spawn-train");

    assertEquals(List.of("Train-C"), reevaluationRequests);
  }

  @Test
  void shouldAdvancePassedStationReturnsTrueForPassStation() {
    NodeId start = NodeId.of("SURN:S:START:1");
    NodeId passStation = NodeId.of("SURN:S:MID:1");
    NodeId next = NodeId.of("SURN:S:NEXT:1");
    RouteDefinition route =
        new RouteDefinition(RouteId.of("r"), List.of(start, passStation, next), Optional.empty());
    RouteDefinitionCache routeDefinitions = mock(RouteDefinitionCache.class);
    when(routeDefinitions.findByCodes("op", "l1", "r1")).thenReturn(Optional.of(route));
    when(routeDefinitions.findStop(route.id(), 1))
        .thenReturn(Optional.of(routeStop(1, passStation, RouteStopPassType.PASS)));
    RuntimeDispatchService service = createMinimalService(routeDefinitions);
    TagStore tags =
        new TagStore(
            "train-1",
            "FTA_OPERATOR_CODE=op",
            "FTA_LINE_CODE=l1",
            "FTA_ROUTE_CODE=r1",
            "FTA_ROUTE_INDEX=0");
    SignNodeDefinition definition =
        new SignNodeDefinition(passStation, NodeType.STATION, Optional.empty(), Optional.empty());

    assertTrue(service.shouldAdvancePassedStation(tags.properties(), definition));
  }

  @Test
  void shouldAdvancePassedStationReturnsFalseForStopStation() {
    NodeId start = NodeId.of("SURN:S:START:1");
    NodeId stopStation = NodeId.of("SURN:S:MID:1");
    NodeId next = NodeId.of("SURN:S:NEXT:1");
    RouteDefinition route =
        new RouteDefinition(RouteId.of("r"), List.of(start, stopStation, next), Optional.empty());
    RouteDefinitionCache routeDefinitions = mock(RouteDefinitionCache.class);
    when(routeDefinitions.findByCodes("op", "l1", "r1")).thenReturn(Optional.of(route));
    when(routeDefinitions.findStop(route.id(), 1))
        .thenReturn(Optional.of(routeStop(1, stopStation, RouteStopPassType.STOP)));
    RuntimeDispatchService service = createMinimalService(routeDefinitions);
    TagStore tags =
        new TagStore(
            "train-1",
            "FTA_OPERATOR_CODE=op",
            "FTA_LINE_CODE=l1",
            "FTA_ROUTE_CODE=r1",
            "FTA_ROUTE_INDEX=0");
    SignNodeDefinition definition =
        new SignNodeDefinition(stopStation, NodeType.STATION, Optional.empty(), Optional.empty());

    assertFalse(service.shouldAdvancePassedStation(tags.properties(), definition));
  }

  // 注意：handleStationArrival 测试需要 TrainCarts 依赖，改用功能文档验证
  // handleStationArrival 方法已在 AutoStationSignAction 中调用，功能集成测试由手动验证覆盖

  // ====== 互锁检查（hasHardBlockersForTrain）测试 ======

  @Test
  void hasHardBlockersReturnsFalseForEmptyBlockers() throws Exception {
    java.lang.reflect.Method method =
        RuntimeDispatchService.class.getDeclaredMethod(
            "hasHardBlockersForTrain", String.class, List.class);
    method.setAccessible(true);

    assertFalse((boolean) method.invoke(null, "train-1", List.of()));
    assertFalse((boolean) method.invoke(null, "train-1", null));
  }

  @Test
  void hasHardBlockersReturnsTrueForNullBlockerElement() throws Exception {
    java.lang.reflect.Method method =
        RuntimeDispatchService.class.getDeclaredMethod(
            "hasHardBlockersForTrain", String.class, List.class);
    method.setAccessible(true);

    List<OccupancyClaim> blockers = new ArrayList<>();
    blockers.add(null);
    assertTrue((boolean) method.invoke(null, "train-1", blockers));
  }

  @Test
  void hasHardBlockersReturnsTrueForNullResource() throws Exception {
    java.lang.reflect.Method method =
        RuntimeDispatchService.class.getDeclaredMethod(
            "hasHardBlockersForTrain", String.class, List.class);
    method.setAccessible(true);

    // OccupancyClaim 构造器不允许 null resource，使用 mock 模拟损坏状态
    OccupancyClaim blocker = mock(OccupancyClaim.class);
    when(blocker.resource()).thenReturn(null);
    when(blocker.trainName()).thenReturn("other");
    assertTrue((boolean) method.invoke(null, "train-1", List.of(blocker)));
  }

  @Test
  void hasHardBlockersSkipsSelfOccupancy() throws Exception {
    java.lang.reflect.Method method =
        RuntimeDispatchService.class.getDeclaredMethod(
            "hasHardBlockersForTrain", String.class, List.class);
    method.setAccessible(true);

    OccupancyClaim selfBlocker =
        new OccupancyClaim(
            OccupancyResource.forNode(NodeId.of("A")),
            "train-1",
            Optional.empty(),
            Instant.now(),
            Duration.ZERO,
            Optional.empty());
    assertFalse((boolean) method.invoke(null, "train-1", List.of(selfBlocker)));
  }

  @Test
  void hasHardBlockersReturnsFalseForConflictOnly() throws Exception {
    java.lang.reflect.Method method =
        RuntimeDispatchService.class.getDeclaredMethod(
            "hasHardBlockersForTrain", String.class, List.class);
    method.setAccessible(true);

    OccupancyClaim conflictBlocker =
        new OccupancyClaim(
            OccupancyResource.forConflict("switcher:test"),
            "other-train",
            Optional.empty(),
            Instant.now(),
            Duration.ZERO,
            Optional.empty());
    assertFalse((boolean) method.invoke(null, "train-1", List.of(conflictBlocker)));
  }

  @Test
  void hasHardBlockersReturnsTrueForOtherTrainNodeOccupancy() throws Exception {
    java.lang.reflect.Method method =
        RuntimeDispatchService.class.getDeclaredMethod(
            "hasHardBlockersForTrain", String.class, List.class);
    method.setAccessible(true);

    OccupancyClaim nodeBlocker =
        new OccupancyClaim(
            OccupancyResource.forNode(NodeId.of("B")),
            "other-train",
            Optional.empty(),
            Instant.now(),
            Duration.ZERO,
            Optional.empty());
    assertTrue((boolean) method.invoke(null, "train-1", List.of(nodeBlocker)));
  }

  @Test
  void hasHardBlockersReturnsTrueForOtherTrainEdgeOccupancy() throws Exception {
    java.lang.reflect.Method method =
        RuntimeDispatchService.class.getDeclaredMethod(
            "hasHardBlockersForTrain", String.class, List.class);
    method.setAccessible(true);

    OccupancyClaim edgeBlocker =
        new OccupancyClaim(
            new OccupancyResource(ResourceKind.EDGE, "A~B"),
            "other-train",
            Optional.empty(),
            Instant.now(),
            Duration.ZERO,
            Optional.empty());
    assertTrue((boolean) method.invoke(null, "train-1", List.of(edgeBlocker)));
  }

  @Test
  void movementInhibitedOnlyClearsAfterTokenCommit() throws Exception {
    RuntimeDispatchService service = createMinimalService();
    NodeId a = NodeId.of("A");
    NodeId b = NodeId.of("B");
    OccupancyResource resource = OccupancyResource.forNode(b);
    OccupancyRequest request = completeTwoNodeAuthorityRequest("train-1", Instant.now(), a, b);

    java.lang.reflect.Method invalidate =
        RuntimeDispatchService.class.getDeclaredMethod(
            "invalidateMovementAuthorization", String.class, HardStopReason.class);
    java.lang.reflect.Method issue =
        RuntimeDispatchService.class.getDeclaredMethod(
            "issueMovementAuthorizationToken",
            String.class,
            NodeId.class,
            NodeId.class,
            OccupancyRequest.class,
            SignalAspect.class,
            Instant.class);
    java.lang.reflect.Method activate =
        RuntimeDispatchService.class.getDeclaredMethod(
            "activateMovementAuthorizationTokenRetainingInhibitor",
            String.class,
            MovementAuthorizationToken.class,
            String.class);
    invalidate.setAccessible(true);
    issue.setAccessible(true);
    activate.setAccessible(true);

    invalidate.invoke(service, "train-1", HardStopReason.AUTHORIZATION_FAILURE);
    MovementAuthorizationToken token =
        (MovementAuthorizationToken)
            issue.invoke(service, "train-1", a, b, request, SignalAspect.PROCEED, Instant.now());

    assertTrue(service.isMovementInhibited("train-1"));
    assertFalse(token.active());

    assertTrue((boolean) activate.invoke(service, "train-1", token, "B"));
    assertTrue(service.isMovementInhibited("train-1"));
    assertFalse((boolean) validMovementAuthorization(service, "train-1", a, b, List.of(resource)));
  }

  @Test
  void destinationCommitRequiresMatchingClaimVersion() throws Exception {
    RuntimeDispatchService service = createMinimalService();
    OccupancyRequest request =
        new OccupancyRequest(
            "train-1",
            Optional.empty(),
            Instant.now(),
            List.of(OccupancyResource.forNode(NodeId.of("B"))),
            Map.of());

    java.lang.reflect.Method issue =
        RuntimeDispatchService.class.getDeclaredMethod(
            "issueMovementAuthorizationToken",
            String.class,
            NodeId.class,
            NodeId.class,
            OccupancyRequest.class,
            SignalAspect.class,
            Instant.class);
    java.lang.reflect.Method activate =
        RuntimeDispatchService.class.getDeclaredMethod(
            "activateMovementAuthorizationTokenRetainingInhibitor",
            String.class,
            MovementAuthorizationToken.class,
            String.class);
    issue.setAccessible(true);
    activate.setAccessible(true);

    MovementAuthorizationToken stale =
        (MovementAuthorizationToken)
            issue.invoke(
                service,
                "train-1",
                NodeId.of("A"),
                NodeId.of("B"),
                request,
                SignalAspect.PROCEED,
                Instant.now());
    issue.invoke(
        service,
        "train-1",
        NodeId.of("A"),
        NodeId.of("B"),
        request,
        SignalAspect.PROCEED,
        Instant.now());

    assertFalse((boolean) activate.invoke(service, "train-1", stale, "B"));
  }

  @Test
  void proceedEventIgnoredUntilPeriodicCommit() {
    List<String> debugMessages = new ArrayList<>();
    RuntimeDispatchService service = createMinimalService(mockOccupancyManager(), debugMessages);

    service.applySignalFromEvent("train-1", SignalAspect.PROCEED);

    RuntimeDispatchService.SignalRuntimeStats stats = service.signalRuntimeStats();
    assertEquals(1, stats.dirtyTrainCount());
    assertEquals(1, stats.coalescedEventCount());
  }

  @Test
  void authorizationStopDoesNotPhysicallyPublishBeforeCycleFinalDecision() {
    List<String> debugMessages = new ArrayList<>();
    RuntimeDispatchService service = createMinimalService(mockOccupancyManager(), debugMessages);

    service.applySignalFromEvent("train-1", SignalAspect.STOP);

    RuntimeDispatchService.SignalRuntimeStats stats = service.signalRuntimeStats();
    assertEquals(1, stats.dirtyTrainCount());
    assertEquals(1, stats.coalescedEventCount());
    assertFalse(
        debugMessages.stream()
            .anyMatch(
                message ->
                    message.contains("hard-stop:") || message.contains("physicalPublished=true")));
  }

  @Test
  void eventCoalescedDirtyDoesNotRecordPublishedProceed() {
    List<String> debugMessages = new ArrayList<>();
    RuntimeDispatchService service = createMinimalService(mockOccupancyManager(), debugMessages);

    service.applySignalFromEvent("train-1", SignalAspect.PROCEED);

    assertTrue(
        debugMessages.stream()
            .anyMatch(
                message ->
                    message.contains("事件信号合并:")
                        && message.contains("computedAspect=PROCEED")
                        && message.contains("publishedAspect=DIRTY_ONLY")
                        && message.contains("physicalPublished=false")));
    assertFalse(
        debugMessages.stream()
            .anyMatch(
                message ->
                    message.contains("事件信号合并:") && message.contains("publishedAspect=PROCEED")));
  }

  @Test
  void movementInhibitedEventProceedDoesNotDirtyOrPublish() throws Exception {
    List<String> debugMessages = new ArrayList<>();
    RuntimeDispatchService service = createMinimalService(mockOccupancyManager(), debugMessages);
    java.lang.reflect.Method invalidate =
        RuntimeDispatchService.class.getDeclaredMethod(
            "invalidateMovementAuthorization", String.class, HardStopReason.class);
    invalidate.setAccessible(true);

    invalidate.invoke(service, "train-1", HardStopReason.AUTHORIZATION_FAILURE);
    service.applySignalFromEvent("train-1", SignalAspect.PROCEED);

    RuntimeDispatchService.SignalRuntimeStats stats = service.signalRuntimeStats();
    assertEquals(0, stats.dirtyTrainCount());
    assertEquals(1, stats.coalescedEventCount());
    assertTrue(
        debugMessages.stream()
            .anyMatch(
                message ->
                    message.contains("事件信号合并抑制:")
                        && message.contains("computedAspect=PROCEED")
                        && message.contains("publishedAspect=PRESERVE")
                        && message.contains("reason=movement-inhibited")));
  }

  @Test
  void smartDepotExitIntoLongSingleBlockedWhenOccupiedOpposite() {
    List<String> debugMessages = new ArrayList<>();
    NodeId a = NodeId.of("DEPOT");
    NodeId b = NodeId.of("SINGLE");
    NodeId c = NodeId.of("EXIT");
    String conflictKey = "single:test:DEPOT~EXIT";
    OccupancyResource conflict = OccupancyResource.forConflict(conflictKey);
    RailEdge edgeAb = new RailEdge(EdgeId.undirected(a, b), a, b, 10, -1.0, true, Optional.empty());
    RailEdge edgeBc = new RailEdge(EdgeId.undirected(b, c), b, c, 10, -1.0, true, Optional.empty());
    OccupancyManager occupancyManager = mockOccupancyManager();
    when(occupancyManager.snapshotClaims())
        .thenReturn(
            List.of(
                singleConflictClaim(
                    conflict,
                    "opposite",
                    CorridorDirection.B_TO_A,
                    Instant.parse("2026-01-01T00:00:00Z"))));
    RuntimeDispatchService service =
        createMinimalService(occupancyManager, debugMessages, SmartDispatcherMode.ENFORCE);

    boolean allowed =
        service.smartDepotAdmissionAllowsSpawn(
            "follower",
            new ConflictExitGraph(a, b, c, edgeAb, edgeBc, conflictKey),
            singleConflictContext(
                "follower", conflict, CorridorDirection.A_TO_B, edgeAb, edgeBc, a, b, c));

    assertFalse(allowed);
    assertTrue(
        debugMessages.stream()
            .anyMatch(message -> message.contains("SMART_DEPOT_LONG_SINGLE_HELD")));
    assertTrue(
        debugMessages.stream()
            .anyMatch(
                message ->
                    message.contains("SMART_ACTION_ALLOWED_BY_EFFECT_GATE")
                        && message.contains("action=SMART_ADMISSION_HOLD")));
  }

  @Test
  void smartAdmissionAllowsQueueHeadWhenOnlyOppositeQueueExists() {
    List<String> debugMessages = new ArrayList<>();
    NodeId a = NodeId.of("DEPOT");
    NodeId b = NodeId.of("SINGLE");
    NodeId c = NodeId.of("EXIT");
    String conflictKey = "single:test:DEPOT~EXIT";
    OccupancyResource conflict = OccupancyResource.forConflict(conflictKey);
    RailEdge edgeAb = new RailEdge(EdgeId.undirected(a, b), a, b, 10, -1.0, true, Optional.empty());
    RailEdge edgeBc = new RailEdge(EdgeId.undirected(b, c), b, c, 10, -1.0, true, Optional.empty());
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    assertTrue(
        manager
            .canEnter(singleConflictRequest("winner", conflict, CorridorDirection.A_TO_B))
            .allowed());
    assertFalse(
        manager
            .canEnter(singleConflictRequest("loser", conflict, CorridorDirection.B_TO_A))
            .allowed());
    RuntimeDispatchService service =
        createMinimalService(manager, debugMessages, SmartDispatcherMode.ENFORCE);
    long versionBefore = manager.version();
    int queuesBefore = manager.snapshotQueues().size();

    boolean allowed =
        service.smartDepotAdmissionAllowsSpawn(
            "winner",
            new ConflictExitGraph(a, b, c, edgeAb, edgeBc, conflictKey),
            singleConflictContext(
                "winner", conflict, CorridorDirection.A_TO_B, edgeAb, edgeBc, a, b, c));

    assertTrue(allowed, debugMessages.toString());
    assertEquals(versionBefore, manager.version());
    assertEquals(queuesBefore, manager.snapshotQueues().size());
    assertTrue(
        debugMessages.stream()
            .anyMatch(message -> message.contains("reason=queue-head-arbitration")));
  }

  @Test
  void smartAdmissionHoldsQueueLoserWhenOnlyOppositeQueueExists() {
    List<String> debugMessages = new ArrayList<>();
    NodeId a = NodeId.of("DEPOT");
    NodeId b = NodeId.of("SINGLE");
    NodeId c = NodeId.of("EXIT");
    String conflictKey = "single:test:DEPOT~EXIT";
    OccupancyResource conflict = OccupancyResource.forConflict(conflictKey);
    RailEdge edgeAb = new RailEdge(EdgeId.undirected(a, b), a, b, 10, -1.0, true, Optional.empty());
    RailEdge edgeBc = new RailEdge(EdgeId.undirected(b, c), b, c, 10, -1.0, true, Optional.empty());
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    assertTrue(
        manager
            .canEnter(singleConflictRequest("winner", conflict, CorridorDirection.A_TO_B))
            .allowed());
    assertFalse(
        manager
            .canEnter(singleConflictRequest("loser", conflict, CorridorDirection.B_TO_A))
            .allowed());
    RuntimeDispatchService service =
        createMinimalService(manager, debugMessages, SmartDispatcherMode.ENFORCE);
    long versionBefore = manager.version();
    int queuesBefore = manager.snapshotQueues().size();

    boolean allowed =
        service.smartDepotAdmissionAllowsSpawn(
            "loser",
            new ConflictExitGraph(a, b, c, edgeAb, edgeBc, conflictKey),
            singleConflictContext(
                "loser", conflict, CorridorDirection.B_TO_A, edgeAb, edgeBc, a, b, c));

    assertFalse(allowed);
    assertEquals(versionBefore, manager.version());
    assertEquals(queuesBefore, manager.snapshotQueues().size());
    assertTrue(
        debugMessages.stream()
            .anyMatch(message -> message.contains("reason=queue-arbitration-wait")));
  }

  @Test
  void smartAdmissionDoesNotRecreateFilteredSingleConflictOnPassingLoopBranch() {
    NodeId switcherA = NodeId.of("LOOP-SW-A");
    NodeId upperA = NodeId.of("UPPER-A");
    NodeId upperB = NodeId.of("UPPER-B");
    NodeId lowerA = NodeId.of("LOWER-A");
    NodeId lowerB = NodeId.of("LOWER-B");
    NodeId switcherB = NodeId.of("LOOP-SW-B");
    Map<NodeId, RailNode> nodes =
        Map.of(
            switcherA, new RailNodeTest(switcherA, NodeType.SWITCHER, Optional.empty()),
            upperA, new RailNodeTest(upperA, NodeType.WAYPOINT, Optional.empty()),
            upperB, new RailNodeTest(upperB, NodeType.WAYPOINT, Optional.empty()),
            lowerA, new RailNodeTest(lowerA, NodeType.WAYPOINT, Optional.empty()),
            lowerB, new RailNodeTest(lowerB, NodeType.WAYPOINT, Optional.empty()),
            switcherB, new RailNodeTest(switcherB, NodeType.SWITCHER, Optional.empty()));
    List<RailEdge> edgeList =
        List.of(
            testEdge(switcherA, upperA),
            testEdge(upperA, upperB),
            testEdge(upperB, switcherB),
            testEdge(switcherA, lowerA),
            testEdge(lowerA, lowerB),
            testEdge(lowerB, switcherB));
    Map<EdgeId, RailEdge> edges = new LinkedHashMap<>();
    edgeList.forEach(edge -> edges.put(edge.id(), edge));
    SimpleRailGraph graph = new SimpleRailGraph(nodes, edges, Set.of());
    OccupancyRequestContext context =
        new OccupancyRequestBuilder(graph, 2, 0, 0, 0)
            .buildContextFromNodes(
                "passing-loop-train",
                Optional.of(RouteId.of("passing-loop")),
                List.of(upperA, upperB),
                0,
                Instant.parse("2026-01-01T00:00:00Z"),
                0)
            .orElseThrow();
    assertTrue(
        context.request().resourceList().stream()
            .noneMatch(
                resource ->
                    resource.kind() == ResourceKind.CONFLICT
                        && resource.key().startsWith("single:")));
    List<String> debugMessages = new ArrayList<>();
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    RuntimeDispatchService service =
        createMinimalService(manager, debugMessages, SmartDispatcherMode.ENFORCE);

    boolean allowed = service.smartDepotAdmissionAllowsSpawn("passing-loop-train", graph, context);

    assertTrue(allowed, debugMessages.toString());
    assertFalse(
        debugMessages.stream().anyMatch(message -> message.contains("single-conflict-missing")),
        debugMessages.toString());
  }

  @Test
  void throatSectionFullyClearAllowsAtomicEntry() {
    List<String> debugMessages = new ArrayList<>();
    ThroatFixture throat = ppkThroatFixture();
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    RuntimeDispatchService service =
        createMinimalService(manager, debugMessages, SmartDispatcherMode.ENFORCE);

    boolean allowed =
        service.smartDepotAdmissionAllowsSpawn(
            "SURC-MT-LP-2387",
            throat.graph(),
            throat.context("SURC-MT-LP-2387", CorridorDirection.A_TO_B, false));

    assertTrue(allowed, debugMessages.toString());
    assertTrue(
        debugMessages.stream()
            .anyMatch(
                message ->
                    message.contains("SMART_THROAT_SECTION_ATOMIC")
                        && message.contains("clear=true")
                        && message.contains("throatEntry=OP:S:PPK:1")
                        && message.contains("throatExitSafePoint=OP:S:PPK:2")),
        debugMessages.toString());
  }

  @Test
  void throatAdvisoryPathCannotReplaceIncompleteHardAuthority() {
    List<String> debugMessages = new ArrayList<>();
    ThroatFixture throat = ppkThroatFixture();
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    RuntimeDispatchService service =
        createMinimalService(manager, debugMessages, SmartDispatcherMode.ENFORCE);

    boolean allowed =
        service.smartDepotAdmissionAllowsSpawn(
            "SURC-MT-LP-2387",
            throat.graph(),
            throat.advisoryOnlyContext("SURC-MT-LP-2387", CorridorDirection.A_TO_B));

    assertFalse(allowed, debugMessages.toString());
    assertTrue(
        debugMessages.stream()
            .anyMatch(
                message ->
                    message.contains("SMART_THROAT_SECTION_ATOMIC")
                        && message.contains("reason=throat-hard-authority-incomplete")),
        debugMessages.toString());
  }

  @Test
  void throatQueueWinnerIsNotBlockedByLoserQueuedOnInternalConflict() {
    List<String> debugMessages = new ArrayList<>();
    ThroatFixture throat = ppkThroatFixture();
    OccupancyResource internalConflict =
        OccupancyResource.forConflict("single:test:ppk-throat-internal");
    ThroatConflictGraph graph =
        new ThroatConflictGraph(
            Map.of(
                throat.entry(),
                    new RailNodeTest(
                        throat.entry(),
                        NodeType.STATION,
                        Optional.of(WaypointMetadata.station("OP", "PPK", 1))),
                throat.throat(),
                    new RailNodeTest(
                        throat.throat(),
                        NodeType.WAYPOINT,
                        Optional.of(WaypointMetadata.stationThroat("OP", "PPK", 1, "001"))),
                throat.switcher(),
                    new RailNodeTest(throat.switcher(), NodeType.SWITCHER, Optional.empty()),
                throat.exit(),
                    new RailNodeTest(
                        throat.exit(),
                        NodeType.STATION,
                        Optional.of(WaypointMetadata.station("OP", "PPK", 2)))),
            List.of(throat.entryEdge(), throat.switcherEdge(), throat.exitEdge()),
            Map.of(
                throat.entryEdge().id(), throat.conflict().key(),
                throat.switcherEdge().id(), internalConflict.key(),
                throat.exitEdge().id(), internalConflict.key()));
    OccupancyRequestContext winner =
        splitConflictThroatContext(
            "winner", throat, throat.conflict(), internalConflict, CorridorDirection.A_TO_B);
    OccupancyRequestContext loser =
        splitConflictThroatContext(
            "loser", throat, throat.conflict(), internalConflict, CorridorDirection.B_TO_A);
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    assertTrue(manager.canEnter(winner.request()).allowed());
    assertFalse(manager.canEnter(loser.request()).allowed());
    assertTrue(manager.canEnterPreview(winner.request()).allowed());
    RuntimeDispatchService service =
        createMinimalService(manager, debugMessages, SmartDispatcherMode.ENFORCE);

    boolean allowed = service.smartDepotAdmissionAllowsSpawn("winner", graph, winner);

    assertTrue(allowed, debugMessages.toString());
    assertFalse(
        debugMessages.stream()
            .anyMatch(
                message ->
                    message.contains("SMART_THROAT_SECTION_ATOMIC")
                        && message.contains("occupiedResource=")
                        && message.contains("@queue:loser")),
        debugMessages.toString());
  }

  @Test
  void throatSectionIntervalExitAllowsStationDeparture() {
    List<String> debugMessages = new ArrayList<>();
    ThroatFixture throat = ppkOutboundIntervalThroatFixture();
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    RuntimeDispatchService service =
        createMinimalService(manager, debugMessages, SmartDispatcherMode.ENFORCE);

    boolean allowed =
        service.smartDepotAdmissionAllowsSpawn(
            "SURC-MT-LH-7938",
            throat.graph(),
            throat.context("SURC-MT-LH-7938", CorridorDirection.A_TO_B, false));

    assertTrue(allowed, debugMessages.toString());
    assertTrue(
        debugMessages.stream()
            .anyMatch(
                message ->
                    message.contains("SMART_THROAT_SECTION_ATOMIC")
                        && message.contains("clear=true")
                        && message.contains("throatEntry=OP:S:PPK:2")
                        && message.contains("throatExitSafePoint=OP:PPK:RVS:2:001")),
        debugMessages.toString());
  }

  @Test
  void throatSectionExitOccupiedHoldsAtEntrySafePoint() {
    List<String> debugMessages = new ArrayList<>();
    ThroatFixture throat = ppkThroatFixture();
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    assertTrue(
        manager
            .acquire(
                new OccupancyRequest(
                    "SURC-MT-LP-0888",
                    Optional.empty(),
                    Instant.parse("2026-01-01T00:00:00Z"),
                    List.of(OccupancyResource.forNode(throat.exit())),
                    Map.of()))
            .allowed());
    RuntimeDispatchService service =
        createMinimalService(manager, debugMessages, SmartDispatcherMode.ENFORCE);

    boolean allowed =
        service.smartDepotAdmissionAllowsSpawn(
            "SURC-MT-LP-2387",
            throat.graph(),
            throat.context("SURC-MT-LP-2387", CorridorDirection.A_TO_B, false));

    assertFalse(allowed);
    assertTrue(
        debugMessages.stream()
            .anyMatch(
                message ->
                    message.contains("SMART_THROAT_SECTION_ATOMIC")
                        && message.contains("clear=false")
                        && message.contains(
                            "occupiedResource=NODE:OP:S:PPK:2@claim:SURC-MT-LP-0888")),
        debugMessages.toString());
    assertTrue(
        debugMessages.stream()
            .anyMatch(
                message ->
                    message.contains("SMART_ADMISSION_REASON")
                        && message.contains("reason=throat-section-not-atomically-clear")),
        debugMessages.toString());
    RuntimeDispatchService.DeadlockBlockerSnapshot snapshot =
        service.recentDeadlockBlockers("SURC-MT-LP-2387", Duration.ofSeconds(30));
    assertTrue(
        snapshot.blockers().stream()
            .anyMatch(
                blocker ->
                    blocker.trainName().equals("SURC-MT-LP-0888")
                        && blocker.resourceKey().equals("NODE:OP:S:PPK:2")),
        () -> "咽喉原子准入拒绝必须发布 typed blocker: " + snapshot.blockers());
  }

  @Test
  void throatSectionIntervalExitOccupiedHoldsAtEntrySafePoint() {
    List<String> debugMessages = new ArrayList<>();
    ThroatFixture throat = ppkOutboundIntervalThroatFixture();
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    assertTrue(
        manager
            .acquire(
                new OccupancyRequest(
                    "SURC-MT-LP-0888",
                    Optional.empty(),
                    Instant.parse("2026-01-01T00:00:00Z"),
                    List.of(OccupancyResource.forNode(throat.exit())),
                    Map.of()))
            .allowed());
    RuntimeDispatchService service =
        createMinimalService(manager, debugMessages, SmartDispatcherMode.ENFORCE);

    boolean allowed =
        service.smartDepotAdmissionAllowsSpawn(
            "SURC-MT-LH-7938",
            throat.graph(),
            throat.context("SURC-MT-LH-7938", CorridorDirection.A_TO_B, false));

    assertFalse(allowed);
    assertTrue(
        debugMessages.stream()
            .anyMatch(
                message ->
                    message.contains("SMART_THROAT_SECTION_ATOMIC")
                        && message.contains("clear=false")
                        && message.contains(
                            "occupiedResource=NODE:OP:PPK:RVS:2:001@claim:SURC-MT-LP-0888")),
        debugMessages.toString());
    assertTrue(
        debugMessages.stream()
            .anyMatch(
                message ->
                    message.contains("SMART_ADMISSION_REASON")
                        && message.contains("reason=throat-section-not-atomically-clear")),
        debugMessages.toString());
  }

  @Test
  void twoTrainsCannotEachHoldHalfThroat() {
    List<String> debugMessages = new ArrayList<>();
    ThroatFixture throat = ppkThroatFixture();
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    RuntimeDispatchService service =
        createMinimalService(manager, debugMessages, SmartDispatcherMode.ENFORCE);

    boolean outboundAllowed =
        service.smartDepotAdmissionAllowsSpawn(
            "SURC-MT-LP-2387",
            throat.graph(),
            throat.context("SURC-MT-LP-2387", CorridorDirection.A_TO_B, false));
    assertTrue(outboundAllowed, debugMessages.toString());
    assertTrue(
        manager
            .acquire(throat.atomicRequest("SURC-MT-LP-2387", CorridorDirection.A_TO_B))
            .allowed());

    boolean turnbackAllowed =
        service.smartDepotAdmissionAllowsSpawn(
            "SURC-MT-LP-0888",
            throat.graph(),
            throat.context("SURC-MT-LP-0888", CorridorDirection.B_TO_A, true));

    assertFalse(turnbackAllowed, debugMessages.toString());
    assertTrue(
        manager.snapshotClaims().stream()
            .allMatch(claim -> !"SURC-MT-LP-0888".equals(claim.trainName())));
    assertTrue(
        debugMessages.stream()
            .anyMatch(
                message ->
                    message.contains("SMART_THROAT_SECTION_ATOMIC")
                        && message.contains("train=SURC-MT-LP-0888")
                        && message.contains("clear=false")
                        && message.contains("throat-section-resource-occupied")),
        debugMessages.toString());
  }

  @Test
  void plainSingleCorridorFollowUnaffected() throws Exception {
    List<String> debugMessages = new ArrayList<>();
    NodeId a = NodeId.of("A");
    NodeId b = NodeId.of("B");
    NodeId c = NodeId.of("C");
    String conflictKey = "single:test:plain-corridor";
    OccupancyResource conflict = OccupancyResource.forConflict(conflictKey);
    RailEdge edgeAb = new RailEdge(EdgeId.undirected(a, b), a, b, 10, -1.0, true, Optional.empty());
    RailEdge edgeBc = new RailEdge(EdgeId.undirected(b, c), b, c, 10, -1.0, true, Optional.empty());
    OccupancyRequest leaderAuthority =
        singleConflictContextWithResources(
                "leader",
                conflict,
                CorridorDirection.A_TO_B,
                edgeAb,
                edgeBc,
                a,
                b,
                c,
                List.of(
                    conflict,
                    OccupancyResource.forNode(a),
                    OccupancyResource.forEdge(edgeAb.id()),
                    OccupancyResource.forNode(b),
                    OccupancyResource.forEdge(edgeBc.id()),
                    OccupancyResource.forNode(c)))
            .request();
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    assertTrue(manager.acquire(leaderAuthority).allowed());
    RouteDefinition route =
        new RouteDefinition(RouteId.of("plain-follow"), List.of(a, b, c), Optional.empty());
    UUID routeUuid = UUID.randomUUID();
    RouteProgressRegistry registry = new RouteProgressRegistry();
    registry.initFromTags(
        "leader",
        new TagStore("leader", "FTA_ROUTE_ID=" + routeUuid, "FTA_ROUTE_INDEX=0").properties(),
        route);
    registry.updateLastPassedGraphNode("leader", a, Instant.now());
    registry.updateSignal("leader", SignalAspect.PROCEED, Instant.now());
    RuntimeDispatchService service =
        createMinimalService(
            manager,
            routeDefinitionCacheWith(route, routeUuid),
            registry,
            debugMessages,
            SmartDispatcherMode.ENFORCE);
    issueAndActivateMovementToken(service, "leader", a, c, leaderAuthority, "C");

    boolean allowed =
        service.smartDepotAdmissionAllowsSpawn(
            "follower",
            new ConflictExitGraph(a, b, c, edgeAb, edgeBc, conflictKey),
            singleConflictContext(
                "follower", conflict, CorridorDirection.A_TO_B, edgeAb, edgeBc, a, b, c));

    assertTrue(allowed, debugMessages.toString());
    assertFalse(
        debugMessages.stream().anyMatch(message -> message.contains("SMART_THROAT_SECTION_ATOMIC")),
        debugMessages.toString());
  }

  @Test
  void throatTopologyUnresolvableFailsClosed() {
    List<String> debugMessages = new ArrayList<>();
    NodeId station = NodeId.of("OP:S:PPK:1");
    NodeId throat = NodeId.of("OP:S:PPK:1:001");
    NodeId plain = NodeId.of("PLAIN-NON-STOP");
    String conflictKey = "single:test:ppk-broken-throat";
    OccupancyResource conflict = OccupancyResource.forConflict(conflictKey);
    RailEdge entryEdge =
        new RailEdge(
            EdgeId.undirected(station, throat), station, throat, 10, -1.0, true, Optional.empty());
    RailEdge brokenEdge =
        new RailEdge(
            EdgeId.undirected(throat, plain), throat, plain, 10, -1.0, true, Optional.empty());
    ThroatConflictGraph graph =
        new ThroatConflictGraph(
            Map.of(
                station,
                    new RailNodeTest(
                        station,
                        NodeType.STATION,
                        Optional.of(WaypointMetadata.station("OP", "PPK", 1))),
                throat,
                    new RailNodeTest(
                        throat,
                        NodeType.WAYPOINT,
                        Optional.of(WaypointMetadata.stationThroat("OP", "PPK", 1, "001"))),
                plain, new RailNodeTest(plain)),
            List.of(entryEdge, brokenEdge),
            Map.of(entryEdge.id(), conflictKey, brokenEdge.id(), conflictKey));
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    RuntimeDispatchService service =
        createMinimalService(manager, debugMessages, SmartDispatcherMode.ENFORCE);

    boolean allowed =
        service.smartDepotAdmissionAllowsSpawn(
            "SURC-MT-LP-2387",
            graph,
            singleConflictPathContext(
                "SURC-MT-LP-2387",
                conflict,
                CorridorDirection.A_TO_B,
                List.of(station, throat, plain),
                List.of(entryEdge, brokenEdge),
                List.of(conflict)));

    assertFalse(allowed);
    assertTrue(
        debugMessages.stream()
            .anyMatch(
                message ->
                    message.contains("SMART_THROAT_SECTION_ATOMIC")
                        && message.contains("clear=false")
                        && message.contains("reason=throat-topology-unresolvable")),
        debugMessages.toString());
  }

  @Test
  void smartLongSingleObserveOnlyDoesNotBlockButTraces() {
    List<String> debugMessages = new ArrayList<>();
    NodeId a = NodeId.of("DEPOT");
    NodeId b = NodeId.of("SINGLE");
    NodeId c = NodeId.of("EXIT");
    String conflictKey = "single:test:DEPOT~EXIT";
    OccupancyResource conflict = OccupancyResource.forConflict(conflictKey);
    RailEdge edgeAb = new RailEdge(EdgeId.undirected(a, b), a, b, 10, -1.0, true, Optional.empty());
    RailEdge edgeBc = new RailEdge(EdgeId.undirected(b, c), b, c, 10, -1.0, true, Optional.empty());
    OccupancyManager occupancyManager = mockOccupancyManager();
    when(occupancyManager.snapshotClaims())
        .thenReturn(
            List.of(
                singleConflictClaim(
                    conflict,
                    "opposite",
                    CorridorDirection.B_TO_A,
                    Instant.parse("2026-01-01T00:00:00Z"))));
    RuntimeDispatchService service =
        createMinimalService(occupancyManager, debugMessages, SmartDispatcherMode.OBSERVE_ONLY);

    boolean allowed =
        service.smartDepotAdmissionAllowsSpawn(
            "follower",
            new ConflictExitGraph(a, b, c, edgeAb, edgeBc, conflictKey),
            singleConflictContext(
                "follower", conflict, CorridorDirection.A_TO_B, edgeAb, edgeBc, a, b, c));

    assertTrue(allowed, debugMessages.toString());
    assertTrue(
        debugMessages.stream()
            .anyMatch(message -> message.contains("SMART_ACTION_SUPPRESSED_BY_MODE")));
  }

  @Test
  void smartSameDirectionFollowerBlockedWhenLeaderStalled() {
    List<String> debugMessages = new ArrayList<>();
    NodeId a = NodeId.of("A");
    NodeId b = NodeId.of("B");
    NodeId c = NodeId.of("C");
    String conflictKey = "single:test:A~C";
    OccupancyResource conflict = OccupancyResource.forConflict(conflictKey);
    RailEdge edgeAb = new RailEdge(EdgeId.undirected(a, b), a, b, 10, -1.0, true, Optional.empty());
    RailEdge edgeBc = new RailEdge(EdgeId.undirected(b, c), b, c, 10, -1.0, true, Optional.empty());
    OccupancyManager occupancyManager = mockOccupancyManager();
    when(occupancyManager.snapshotClaims())
        .thenReturn(
            List.of(
                singleConflictClaim(
                    conflict,
                    "leader",
                    CorridorDirection.A_TO_B,
                    Instant.parse("2026-01-01T00:00:00Z"))));
    RuntimeDispatchService service =
        createMinimalService(occupancyManager, debugMessages, SmartDispatcherMode.ENFORCE);

    boolean allowed =
        service.smartDepotAdmissionAllowsSpawn(
            "follower",
            new ConflictExitGraph(a, b, c, edgeAb, edgeBc, conflictKey),
            singleConflictContext(
                "follower", conflict, CorridorDirection.A_TO_B, edgeAb, edgeBc, a, b, c));

    assertFalse(allowed);
    assertTrue(
        debugMessages.stream()
            .anyMatch(message -> message.contains("SMART_DOWNSTREAM_CONGESTION_DETECTED")));
    assertTrue(
        debugMessages.stream()
            .anyMatch(message -> message.contains("SMART_FOLLOW_THROUGH_PREVIEW")));
  }

  @Test
  void followThroughPreviewDoesNotChangeAdmissionOutcome() {
    List<String> debugMessages = new ArrayList<>();
    NodeId a = NodeId.of("A");
    NodeId b = NodeId.of("B");
    NodeId c = NodeId.of("C");
    String conflictKey = "single:test:A~C";
    OccupancyResource conflict = OccupancyResource.forConflict(conflictKey);
    RailEdge edgeAb = new RailEdge(EdgeId.undirected(a, b), a, b, 10, -1.0, true, Optional.empty());
    RailEdge edgeBc = new RailEdge(EdgeId.undirected(b, c), b, c, 10, -1.0, true, Optional.empty());
    OccupancyManager occupancyManager = mockOccupancyManager();
    when(occupancyManager.snapshotClaims())
        .thenReturn(
            List.of(
                singleConflictClaim(
                    conflict,
                    "leader",
                    CorridorDirection.A_TO_B,
                    Instant.parse("2026-01-01T00:00:00Z"))));
    RuntimeDispatchService service =
        createMinimalService(occupancyManager, debugMessages, SmartDispatcherMode.ENFORCE);

    boolean allowed =
        service.smartDepotAdmissionAllowsSpawn(
            "follower",
            new ConflictExitGraph(a, b, c, edgeAb, edgeBc, conflictKey),
            singleConflictContext(
                "follower", conflict, CorridorDirection.A_TO_B, edgeAb, edgeBc, a, b, c));

    assertFalse(allowed);
    assertTrue(
        debugMessages.stream()
            .anyMatch(
                message ->
                    message.contains("SMART_FOLLOW_THROUGH_PREVIEW")
                        && message.contains("wouldMutate=false")
                        && message.contains("didMutate=false")));
  }

  @Test
  void followThroughPreviewLogsWouldAllowButDoesNotMutate() throws Exception {
    List<String> debugMessages = new ArrayList<>();
    NodeId a = NodeId.of("A");
    NodeId b = NodeId.of("B");
    NodeId c = NodeId.of("C");
    String conflictKey = "single:test:A~C";
    OccupancyResource conflict = OccupancyResource.forConflict(conflictKey);
    RailEdge edgeAb = new RailEdge(EdgeId.undirected(a, b), a, b, 10, -1.0, true, Optional.empty());
    RailEdge edgeBc = new RailEdge(EdgeId.undirected(b, c), b, c, 10, -1.0, true, Optional.empty());
    OccupancyRequest leaderAuthority =
        singleConflictContextWithResources(
                "leader",
                conflict,
                CorridorDirection.A_TO_B,
                edgeAb,
                edgeBc,
                a,
                b,
                c,
                List.of(
                    conflict,
                    OccupancyResource.forNode(a),
                    OccupancyResource.forEdge(edgeAb.id()),
                    OccupancyResource.forNode(b),
                    OccupancyResource.forEdge(edgeBc.id()),
                    OccupancyResource.forNode(c)))
            .request();
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    assertTrue(manager.acquire(leaderAuthority).allowed());
    RouteProgressRegistry registry = new RouteProgressRegistry();
    RouteDefinition route =
        new RouteDefinition(RouteId.of("follow"), List.of(a, b, c), Optional.empty());
    UUID routeUuid = UUID.randomUUID();
    registry.initFromTags(
        "leader",
        new TagStore(
                "leader",
                "FTA_ROUTE_ID=" + routeUuid,
                "FTA_TRAIN_NAME=leader",
                "FTA_OPERATOR_CODE=op",
                "FTA_LINE_CODE=l1",
                "FTA_ROUTE_CODE=follow",
                "FTA_ROUTE_INDEX=0")
            .properties(),
        route);
    registry.updateLastPassedGraphNode("leader", a, Instant.now());
    registry.updateSignal("leader", SignalAspect.PROCEED, Instant.now());
    RouteDefinitionCache routeDefinitions = routeDefinitionCacheWith(route, routeUuid);
    RuntimeDispatchService service =
        createMinimalService(
            manager, routeDefinitions, registry, debugMessages, SmartDispatcherMode.ENFORCE);
    issueAndActivateMovementToken(service, "leader", a, c, leaderAuthority, "C");
    long versionBefore = manager.version();
    int claimsBefore = manager.snapshotClaims().size();

    boolean allowed =
        service.smartDepotAdmissionAllowsSpawn(
            "follower",
            new ConflictExitGraph(a, b, c, edgeAb, edgeBc, conflictKey),
            singleConflictContext(
                "follower", conflict, CorridorDirection.A_TO_B, edgeAb, edgeBc, a, b, c));

    assertTrue(allowed, debugMessages.toString());
    assertEquals(versionBefore, manager.version());
    assertEquals(claimsBefore, manager.snapshotClaims().size());
    assertTrue(
        debugMessages.stream()
            .anyMatch(
                message ->
                    message.contains("SMART_FOLLOW_THROUGH_PREVIEW")
                        && message.contains("decision=WOULD_ALLOW_FOLLOW_THROUGH")
                        && message.contains("wouldMutate=false")
                        && message.contains("didMutate=false")));
  }

  @Test
  void sameDirectionLeaderWithVisibleSharedExitAllowsFollower() throws Exception {
    List<String> debugMessages = new ArrayList<>();
    NodeId a = NodeId.of("A");
    NodeId b = NodeId.of("B");
    NodeId c = NodeId.of("C");
    String conflictKey = "single:test:A~B";
    OccupancyResource conflict = OccupancyResource.forConflict(conflictKey);
    RailEdge edgeAb = new RailEdge(EdgeId.undirected(a, b), a, b, 10, -1.0, true, Optional.empty());
    RailEdge edgeBc = new RailEdge(EdgeId.undirected(b, c), b, c, 10, -1.0, true, Optional.empty());
    OccupancyRequest leaderAuthority =
        singleConflictContextWithResources(
                "leader",
                conflict,
                CorridorDirection.A_TO_B,
                edgeAb,
                edgeBc,
                a,
                b,
                c,
                List.of(
                    conflict,
                    OccupancyResource.forNode(a),
                    OccupancyResource.forEdge(edgeAb.id()),
                    OccupancyResource.forNode(b),
                    OccupancyResource.forEdge(edgeBc.id()),
                    OccupancyResource.forNode(c)))
            .request();
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    assertTrue(manager.acquire(leaderAuthority).allowed());
    RouteDefinition route =
        new RouteDefinition(RouteId.of("follow"), List.of(a, b, c), Optional.empty());
    UUID routeUuid = UUID.randomUUID();
    RouteProgressRegistry registry = new RouteProgressRegistry();
    registry.initFromTags(
        "leader",
        new TagStore("leader", "FTA_ROUTE_ID=" + routeUuid, "FTA_ROUTE_INDEX=0").properties(),
        route);
    registry.updateLastPassedGraphNode("leader", a, Instant.now());
    registry.updateSignal("leader", SignalAspect.PROCEED, Instant.now());
    RuntimeDispatchService service =
        createMinimalService(
            manager,
            routeDefinitionCacheWith(route, routeUuid),
            registry,
            debugMessages,
            SmartDispatcherMode.ENFORCE);
    issueAndActivateMovementToken(service, "leader", a, c, leaderAuthority, "C");

    boolean allowed =
        service.smartDepotAdmissionAllowsSpawn(
            "follower",
            new ConflictExitGraph(a, b, c, edgeAb, edgeBc, conflictKey),
            singleConflictContext(
                "follower", conflict, CorridorDirection.A_TO_B, edgeAb, edgeBc, a, b, c));

    assertTrue(allowed, debugMessages.toString());
    assertTrue(
        debugMessages.stream()
            .anyMatch(
                message ->
                    message.contains("SMART_SAME_DIRECTION_LEADER_DRAIN_PREDICTION")
                        && message.contains("drainProven=true")
                        && message.contains("same-direction-leader-drain-predicted")));
  }

  @Test
  void sameDirectionLeaderDestinationBeyondHardAuthorityBlocksFollower() throws Exception {
    List<String> debugMessages = new ArrayList<>();
    NodeId a = NodeId.of("A");
    NodeId b = NodeId.of("B");
    NodeId c = NodeId.of("C");
    String conflictKey = "single:test:A~B";
    OccupancyResource conflict = OccupancyResource.forConflict(conflictKey);
    RailEdge edgeAb = new RailEdge(EdgeId.undirected(a, b), a, b, 10, -1.0, true, Optional.empty());
    RailEdge edgeBc = new RailEdge(EdgeId.undirected(b, c), b, c, 10, -1.0, true, Optional.empty());
    OccupancyRequest leaderAuthority =
        singleConflictContextWithResources(
                "leader",
                conflict,
                CorridorDirection.A_TO_B,
                edgeAb,
                edgeBc,
                a,
                b,
                c,
                List.of(
                    conflict,
                    OccupancyResource.forNode(a),
                    OccupancyResource.forEdge(edgeAb.id()),
                    OccupancyResource.forNode(b)))
            .request();
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    assertTrue(manager.acquire(leaderAuthority).allowed());
    RouteDefinition route =
        new RouteDefinition(RouteId.of("follow"), List.of(a, b, c), Optional.empty());
    UUID routeUuid = UUID.randomUUID();
    RouteProgressRegistry registry = new RouteProgressRegistry();
    registry.initFromTags(
        "leader",
        new TagStore("leader", "FTA_ROUTE_ID=" + routeUuid + ",FTA_ROUTE_INDEX=0").properties(),
        route);
    registry.updateLastPassedGraphNode("leader", a, Instant.now());
    registry.updateSignal("leader", SignalAspect.PROCEED, Instant.now());
    RuntimeDispatchService service =
        createMinimalService(
            manager,
            routeDefinitionCacheWith(route, routeUuid),
            registry,
            debugMessages,
            SmartDispatcherMode.ENFORCE);
    issueAndActivateMovementToken(service, "leader", a, c, leaderAuthority, "C");

    boolean allowed =
        service.smartDepotAdmissionAllowsSpawn(
            "follower",
            new ConflictExitGraph(a, b, c, edgeAb, edgeBc, conflictKey),
            singleConflictContext(
                "follower", conflict, CorridorDirection.A_TO_B, edgeAb, edgeBc, a, b, c));

    assertFalse(allowed, debugMessages.toString());
    assertTrue(
        debugMessages.stream()
            .anyMatch(
                message ->
                    message.contains("SMART_SAME_DIRECTION_LEADER_DRAIN_PREDICTION")
                        && message.contains("same-direction-leader-authority-boundary-incomplete")),
        debugMessages.toString());
  }

  @Test
  void sameDirectionLeaderTokenWithLostHardResourceBlocksFollower() throws Exception {
    List<String> debugMessages = new ArrayList<>();
    NodeId a = NodeId.of("A");
    NodeId b = NodeId.of("B");
    NodeId c = NodeId.of("C");
    String conflictKey = "single:test:A~B";
    OccupancyResource conflict = OccupancyResource.forConflict(conflictKey);
    RailEdge edgeAb = new RailEdge(EdgeId.undirected(a, b), a, b, 10, -1.0, true, Optional.empty());
    RailEdge edgeBc = new RailEdge(EdgeId.undirected(b, c), b, c, 10, -1.0, true, Optional.empty());
    OccupancyResource lostEdge = OccupancyResource.forEdge(edgeAb.id());
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    assertTrue(
        manager
            .acquire(singleConflictRequest("leader", conflict, CorridorDirection.A_TO_B))
            .allowed());
    RouteDefinition route =
        new RouteDefinition(RouteId.of("follow"), List.of(a, b, c), Optional.empty());
    UUID routeUuid = UUID.randomUUID();
    RouteProgressRegistry registry = new RouteProgressRegistry();
    registry.initFromTags(
        "leader",
        new TagStore("leader", "FTA_ROUTE_ID=" + routeUuid, "FTA_ROUTE_INDEX=0").properties(),
        route);
    registry.updateLastPassedGraphNode("leader", a, Instant.now());
    registry.updateSignal("leader", SignalAspect.PROCEED, Instant.now());
    RuntimeDispatchService service =
        createMinimalService(
            manager,
            routeDefinitionCacheWith(route, routeUuid),
            registry,
            debugMessages,
            SmartDispatcherMode.ENFORCE);
    installMovementToken(
        service,
        new MovementAuthorizationToken(
                "leader",
                1L,
                Instant.now(),
                a,
                c,
                List.of(conflict, lostEdge),
                SignalAspect.PROCEED)
            .activate("C"));

    boolean allowed =
        service.smartDepotAdmissionAllowsSpawn(
            "follower",
            new ConflictExitGraph(a, b, c, edgeAb, edgeBc, conflictKey),
            singleConflictContext(
                "follower", conflict, CorridorDirection.A_TO_B, edgeAb, edgeBc, a, b, c));

    assertFalse(allowed, debugMessages.toString());
    assertTrue(
        debugMessages.stream()
            .anyMatch(
                message ->
                    message.contains("SMART_SAME_DIRECTION_LEADER_DRAIN_PREDICTION")
                        && message.contains("same-direction-leader-hard-authority-missing")),
        debugMessages.toString());
  }

  @Test
  void sameDirectionLeaderOnParallelBranchDoesNotProveFollowerDrain() throws Exception {
    List<String> debugMessages = new ArrayList<>();
    NodeId followerA = NodeId.of("FOLLOWER-A");
    NodeId followerB = NodeId.of("FOLLOWER-B");
    NodeId followerC = NodeId.of("FOLLOWER-C");
    NodeId leaderA = NodeId.of("LEADER-A");
    NodeId leaderB = NodeId.of("LEADER-B");
    NodeId leaderC = NodeId.of("LEADER-C");
    String conflictKey = "single:test:shared-key";
    OccupancyResource conflict = OccupancyResource.forConflict(conflictKey);
    RailEdge followerConflictEdge =
        new RailEdge(
            EdgeId.undirected(followerA, followerB),
            followerA,
            followerB,
            10,
            -1.0,
            true,
            Optional.empty());
    RailEdge followerExitEdge =
        new RailEdge(
            EdgeId.undirected(followerB, followerC),
            followerB,
            followerC,
            10,
            -1.0,
            true,
            Optional.empty());
    RailEdge leaderConflictEdge =
        new RailEdge(
            EdgeId.undirected(leaderA, leaderB),
            leaderA,
            leaderB,
            10,
            -1.0,
            true,
            Optional.empty());
    RailEdge leaderExitEdge =
        new RailEdge(
            EdgeId.undirected(leaderB, leaderC),
            leaderB,
            leaderC,
            10,
            -1.0,
            true,
            Optional.empty());
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    assertTrue(
        manager
            .acquire(singleConflictRequest("leader", conflict, CorridorDirection.A_TO_B))
            .allowed());
    RouteDefinition route =
        new RouteDefinition(
            RouteId.of("parallel-leader"), List.of(leaderA, leaderB, leaderC), Optional.empty());
    UUID routeUuid = UUID.randomUUID();
    RouteProgressRegistry registry = new RouteProgressRegistry();
    registry.initFromTags(
        "leader",
        new TagStore("leader", "FTA_ROUTE_ID=" + routeUuid, "FTA_ROUTE_INDEX=0").properties(),
        route);
    registry.updateLastPassedGraphNode("leader", leaderA, Instant.now());
    registry.updateSignal("leader", SignalAspect.PROCEED, Instant.now());
    RuntimeDispatchService service =
        createMinimalService(
            manager,
            routeDefinitionCacheWith(route, routeUuid),
            registry,
            debugMessages,
            SmartDispatcherMode.ENFORCE);
    installMovementToken(
        service,
        new MovementAuthorizationToken(
                "leader",
                1L,
                Instant.now(),
                leaderA,
                leaderC,
                List.of(conflict),
                SignalAspect.PROCEED)
            .activate("LEADER-C"));

    boolean allowed =
        service.smartDepotAdmissionAllowsSpawn(
            "follower",
            new ParallelConflictExitGraph(
                List.of(followerConflictEdge, followerExitEdge, leaderConflictEdge, leaderExitEdge),
                Set.of(followerConflictEdge.id(), leaderConflictEdge.id()),
                conflictKey),
            singleConflictContext(
                "follower",
                conflict,
                CorridorDirection.A_TO_B,
                followerConflictEdge,
                followerExitEdge,
                followerA,
                followerB,
                followerC));

    assertFalse(allowed);
    assertTrue(
        debugMessages.stream()
            .anyMatch(
                message ->
                    message.contains("SMART_SAME_DIRECTION_LEADER_DRAIN_PREDICTION")
                        && message.contains("routeOverlap=false")
                        && message.contains("same-direction-leader-route-window-not-overlapping")),
        debugMessages.toString());
  }

  @Test
  void sameDirectionLeaderDwellWithProvenDrainAllowsFollowerAtSafeHoldPoint() throws Exception {
    List<String> debugMessages = new ArrayList<>();
    NodeId a = NodeId.of("A");
    NodeId b = NodeId.of("B");
    NodeId c = NodeId.of("C");
    NodeId station = NodeId.of("OP:S:CHT:3");
    String conflictKey = "single:test:terminal-approach";
    OccupancyResource conflict = OccupancyResource.forConflict(conflictKey);
    RailEdge edgeAb = new RailEdge(EdgeId.undirected(a, b), a, b, 10, -1.0, true, Optional.empty());
    RailEdge edgeBc = new RailEdge(EdgeId.undirected(b, c), b, c, 10, -1.0, true, Optional.empty());
    RailEdge edgeCs =
        new RailEdge(EdgeId.undirected(c, station), c, station, 10, -1.0, true, Optional.empty());
    OccupancyRequest leaderAuthority =
        singleConflictPathContext(
                "leader",
                conflict,
                CorridorDirection.A_TO_B,
                List.of(a, b, c, station),
                List.of(edgeAb, edgeBc, edgeCs),
                List.of(
                    OccupancyResource.forNode(a),
                    OccupancyResource.forEdge(edgeAb.id()),
                    OccupancyResource.forNode(b),
                    OccupancyResource.forEdge(edgeBc.id()),
                    OccupancyResource.forNode(c),
                    OccupancyResource.forEdge(edgeCs.id()),
                    OccupancyResource.forNode(station)))
            .request();
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    assertTrue(manager.acquire(leaderAuthority).allowed());
    RouteDefinition route =
        new RouteDefinition(RouteId.of("terminal"), List.of(a, b, c, station), Optional.empty());
    UUID routeUuid = UUID.randomUUID();
    RouteProgressRegistry registry = new RouteProgressRegistry();
    registry.initFromTags(
        "leader",
        new TagStore("leader", "FTA_ROUTE_ID=" + routeUuid, "FTA_ROUTE_INDEX=0").properties(),
        route);
    registry.updateLastPassedGraphNode("leader", a, Instant.now());
    registry.updateSignal("leader", SignalAspect.PROCEED, Instant.now());
    DwellRegistry dwellRegistry = new DwellRegistry();
    dwellRegistry.start("leader", 30);
    RuntimeDispatchService service =
        createMinimalService(
            manager,
            routeDefinitionCacheWith(route, routeUuid),
            registry,
            debugMessages,
            SmartDispatcherMode.ENFORCE,
            dwellRegistry);
    issueAndActivateMovementToken(service, "leader", a, station, leaderAuthority, "OP:S:CHT:3");

    boolean allowed =
        service.smartDepotAdmissionAllowsSpawn(
            "follower",
            new DeadEndStationConflictGraph(
                List.of(edgeAb, edgeBc, edgeCs), station, edgeAb.id(), conflictKey),
            singleConflictContext(
                "follower", conflict, CorridorDirection.A_TO_B, edgeAb, edgeBc, a, b, c));

    assertTrue(allowed, debugMessages.toString());
    assertTrue(
        debugMessages.stream()
            .anyMatch(
                message ->
                    message.contains("SMART_SAME_DIRECTION_LEADER_DRAIN_PREDICTION")
                        && message.contains("drainProven=true")
                        && message.contains("same-direction-leader-terminal-follow-through")),
        debugMessages.toString());
  }

  @Test
  void alreadyInsideTerminalRouteWithoutActiveAuthorityBlocksFollower() throws Exception {
    List<String> debugMessages = new ArrayList<>();
    NodeId a = NodeId.of("A");
    NodeId b = NodeId.of("B");
    NodeId c = NodeId.of("C");
    NodeId station = NodeId.of("OP:S:CHT:3");
    String conflictKey = "single:test:already-inside-terminal-follow-through";
    OccupancyResource conflict = OccupancyResource.forConflict(conflictKey);
    RailEdge edgeBc = new RailEdge(EdgeId.undirected(b, c), b, c, 10, -1.0, true, Optional.empty());
    RailEdge edgeCs =
        new RailEdge(EdgeId.undirected(c, station), c, station, 10, -1.0, true, Optional.empty());
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    assertTrue(
        manager
            .acquire(singleConflictRequest("leader", conflict, CorridorDirection.A_TO_B))
            .allowed());
    assertTrue(
        manager
            .acquire(singleConflictRequest("follower", conflict, CorridorDirection.A_TO_B))
            .allowed());
    RouteDefinition route =
        new RouteDefinition(RouteId.of("terminal"), List.of(a, b, c, station), Optional.empty());
    UUID routeUuid = UUID.randomUUID();
    RouteProgressRegistry registry = new RouteProgressRegistry();
    registry.initFromTags(
        "follower",
        new TagStore("follower", "FTA_ROUTE_ID=" + routeUuid, "FTA_ROUTE_INDEX=0").properties(),
        route);
    registry.initFromTags(
        "leader",
        new TagStore("leader", "FTA_ROUTE_ID=" + routeUuid, "FTA_ROUTE_INDEX=1").properties(),
        route);
    registry.updateSignal("leader", SignalAspect.PROCEED, Instant.now());
    RuntimeDispatchService service =
        createMinimalService(
            manager,
            routeDefinitionCacheWith(route, routeUuid),
            registry,
            debugMessages,
            SmartDispatcherMode.ENFORCE);

    boolean allowed =
        service.smartDepotAdmissionAllowsSpawn(
            "follower",
            new DeadEndStationConflictGraph(
                List.of(edgeBc, edgeCs), station, edgeBc.id(), conflictKey),
            singleConflictContext(
                "follower", conflict, CorridorDirection.A_TO_B, edgeBc, edgeCs, b, c, station));

    assertFalse(allowed, debugMessages.toString());
    assertTrue(
        debugMessages.stream()
            .anyMatch(
                message ->
                    message.contains("SMART_SAME_DIRECTION_LEADER_DRAIN_PREDICTION")
                        && message.contains("drainProven=false")
                        && message.contains("same-direction-leader-authority-token-missing")),
        debugMessages.toString());
    assertTrue(
        debugMessages.stream()
            .anyMatch(
                message ->
                    message.contains("SMART_ALREADY_INSIDE_REGION_BLOCKED_BY_SAME_DIRECTION_LEADER")
                        && message.contains("same-direction-leader-authority-token-missing")),
        debugMessages.toString());
  }

  @Test
  void alreadyInsideTerminalLeaderBlocksSameDirectionFollower() throws Exception {
    List<String> debugMessages = new ArrayList<>();
    NodeId a = NodeId.of("A");
    NodeId b = NodeId.of("B");
    NodeId c = NodeId.of("C");
    NodeId station = NodeId.of("OP:S:PPK:1");
    String conflictKey = "single:test:terminal-inside";
    OccupancyResource conflict = OccupancyResource.forConflict(conflictKey);
    RailEdge edgeAb = new RailEdge(EdgeId.undirected(a, b), a, b, 10, -1.0, true, Optional.empty());
    RailEdge edgeBc = new RailEdge(EdgeId.undirected(b, c), b, c, 10, -1.0, true, Optional.empty());
    RailEdge edgeCs =
        new RailEdge(EdgeId.undirected(c, station), c, station, 10, -1.0, true, Optional.empty());
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    assertTrue(
        manager
            .acquire(singleConflictRequest("leader", conflict, CorridorDirection.A_TO_B))
            .allowed());
    assertTrue(
        manager
            .acquire(singleConflictRequest("follower", conflict, CorridorDirection.A_TO_B))
            .allowed());
    RouteDefinition route =
        new RouteDefinition(RouteId.of("terminal"), List.of(a, b, c, station), Optional.empty());
    UUID routeUuid = UUID.randomUUID();
    RouteProgressRegistry registry = new RouteProgressRegistry();
    registry.initFromTags(
        "follower",
        new TagStore("follower", "FTA_ROUTE_ID=" + routeUuid, "FTA_ROUTE_INDEX=0").properties(),
        route);
    registry.initFromTags(
        "leader",
        new TagStore(
                "leader",
                "FTA_ROUTE_ID=" + routeUuid,
                "FTA_ROUTE_INDEX=" + (route.waypoints().size() - 1))
            .properties(),
        route);
    registry.updateLastPassedGraphNode("leader", station, Instant.now());
    registry.updateSignal("leader", SignalAspect.STOP, Instant.now());
    RuntimeDispatchService service =
        createMinimalService(
            manager,
            routeDefinitionCacheWith(route, routeUuid),
            registry,
            debugMessages,
            SmartDispatcherMode.ENFORCE);

    boolean allowed =
        service.smartDepotAdmissionAllowsSpawn(
            "follower",
            new DeadEndStationConflictGraph(
                List.of(edgeAb, edgeBc, edgeCs), station, edgeAb.id(), conflictKey),
            singleConflictContext(
                "follower", conflict, CorridorDirection.A_TO_B, edgeAb, edgeBc, a, b, c));

    assertFalse(allowed, debugMessages.toString());
    assertTrue(
        debugMessages.stream()
            .anyMatch(
                message ->
                    message.contains("SMART_ALREADY_INSIDE_REGION_BLOCKED_BY_SAME_DIRECTION_LEADER")
                        && message.contains("leader=leader")
                        && message.contains("same-direction-leader-terminal-or-dwell")
                        && message.contains("leaderOrder=LEADER_AHEAD")),
        debugMessages.toString());
    assertTrue(
        debugMessages.stream()
            .anyMatch(
                message ->
                    message.contains("SMART_DOWNSTREAM_CONGESTION_DETECTED")
                        && message.contains("decision=REJECT_LEADER_OCCUPYING")),
        debugMessages.toString());
    assertTrue(
        debugMessages.stream()
            .anyMatch(
                message ->
                    message.contains("SMART_ADMISSION_REASON")
                        && message.contains("decision=HOLD_AT_DEPOT")),
        debugMessages.toString());
  }

  @Test
  void alreadyInsideSameDirectionUnknownOrderDoesNotMutuallyHold() {
    List<String> debugMessages = new ArrayList<>();
    NodeId a = NodeId.of("A");
    NodeId b = NodeId.of("B");
    NodeId c = NodeId.of("C");
    String conflictKey = "single:test:unknown-order";
    OccupancyResource conflict = OccupancyResource.forConflict(conflictKey);
    RailEdge edgeAb = new RailEdge(EdgeId.undirected(a, b), a, b, 10, -1.0, true, Optional.empty());
    RailEdge edgeBc = new RailEdge(EdgeId.undirected(b, c), b, c, 10, -1.0, true, Optional.empty());
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    assertTrue(
        manager
            .acquire(singleConflictRequest("alpha", conflict, CorridorDirection.A_TO_B))
            .allowed());
    assertTrue(
        manager
            .acquire(singleConflictRequest("bravo", conflict, CorridorDirection.A_TO_B))
            .allowed());
    RuntimeDispatchService service =
        createMinimalService(manager, debugMessages, SmartDispatcherMode.ENFORCE);
    RailGraph graph = new ConflictExitGraph(a, b, c, edgeAb, edgeBc, conflictKey);

    boolean alphaAllowed =
        service.smartDepotAdmissionAllowsSpawn(
            "alpha",
            graph,
            singleConflictContext(
                "alpha", conflict, CorridorDirection.A_TO_B, edgeAb, edgeBc, a, b, c));
    boolean bravoAllowed =
        service.smartDepotAdmissionAllowsSpawn(
            "bravo",
            graph,
            singleConflictContext(
                "bravo", conflict, CorridorDirection.A_TO_B, edgeAb, edgeBc, a, b, c));

    assertTrue(alphaAllowed || bravoAllowed, debugMessages.toString());
    assertFalse(alphaAllowed && bravoAllowed, debugMessages.toString());
    assertTrue(
        debugMessages.stream()
            .anyMatch(
                message ->
                    message.contains("SMART_ALREADY_INSIDE_LEADER_GUARD_SKIPPED")
                        && message.contains("leader-order-unknown-deterministic-pass")),
        debugMessages.toString());
  }

  @Test
  void depotSameOriginSymmetricStallSelectsSingleDeterministicWinner() throws Exception {
    NodeId a = NodeId.of("D:HHU:3");
    NodeId b = NodeId.of("CGL:WYB:1:001");
    NodeId c = NodeId.of("OP:S:PPK:1");
    String conflictKey = "single:test:HHU~PPK";
    OccupancyResource conflict = OccupancyResource.forConflict(conflictKey);
    RailEdge edgeAb = new RailEdge(EdgeId.undirected(a, b), a, b, 10, -1.0, true, Optional.empty());
    RailEdge edgeBc = new RailEdge(EdgeId.undirected(b, c), b, c, 10, -1.0, true, Optional.empty());
    RailGraph graph = new ConflictExitGraph(a, b, c, edgeAb, edgeBc, conflictKey);
    String firstTrain = "SURC-DS-1W-6949";
    String secondTrain = "SURC-DS-1W-8862";

    AdmissionView firstView =
        sameDirectionAdmissionView(
            firstTrain,
            secondTrain,
            conflict,
            CorridorDirection.A_TO_B,
            CorridorDirection.A_TO_B,
            edgeAb,
            edgeBc,
            a,
            b,
            c);
    AdmissionView secondView =
        sameDirectionAdmissionView(
            secondTrain,
            firstTrain,
            conflict,
            CorridorDirection.A_TO_B,
            CorridorDirection.A_TO_B,
            edgeAb,
            edgeBc,
            a,
            b,
            c);
    boolean firstAllowed =
        firstView.service().smartDepotAdmissionAllowsSpawn(firstTrain, graph, firstView.context());
    boolean secondAllowed =
        secondView
            .service()
            .smartDepotAdmissionAllowsSpawn(secondTrain, graph, secondView.context());

    assertTrue(
        firstAllowed || secondAllowed,
        firstView.debugMessages() + "\n" + secondView.debugMessages());
    assertFalse(
        firstAllowed && secondAllowed,
        firstView.debugMessages() + "\n" + secondView.debugMessages());
    List<String> winnerLogs = firstAllowed ? firstView.debugMessages() : secondView.debugMessages();
    List<String> loserLogs = firstAllowed ? secondView.debugMessages() : firstView.debugMessages();
    assertTrue(
        winnerLogs.stream()
            .anyMatch(
                message ->
                    message.contains("SMART_SAME_DIRECTION_CORRIDOR_PRIORITY")
                        && message.contains("corridorOrder=UNKNOWN")
                        && message.contains("selfIsCorridorPriority=true")),
        winnerLogs.toString());
    assertTrue(
        loserLogs.stream()
            .anyMatch(
                message ->
                    message.contains("SMART_ADMISSION_REASON")
                        && message.contains("reason=same-direction-leader-stalled")),
        loserLogs.toString());
  }

  @Test
  void corridorLeaderAheadFollowerHeldNoOvertake() throws Exception {
    List<String> debugMessages = new ArrayList<>();
    NodeId a = NodeId.of("A");
    NodeId b = NodeId.of("B");
    NodeId c = NodeId.of("C");
    NodeId d = NodeId.of("D");
    String conflictKey = "single:test:A~C";
    OccupancyResource conflict = OccupancyResource.forConflict(conflictKey);
    RailEdge edgeAb = new RailEdge(EdgeId.undirected(a, b), a, b, 10, -1.0, true, Optional.empty());
    RailEdge edgeBc = new RailEdge(EdgeId.undirected(b, c), b, c, 10, -1.0, true, Optional.empty());
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    assertTrue(
        manager
            .acquire(singleConflictRequest("leader", conflict, CorridorDirection.A_TO_B))
            .allowed());
    RouteDefinition route =
        new RouteDefinition(RouteId.of("same-route"), List.of(a, b, c, d), Optional.empty());
    UUID routeUuid = UUID.randomUUID();
    RouteProgressRegistry registry = new RouteProgressRegistry();
    registry.initFromTags(
        "follower",
        new TagStore("follower", "FTA_ROUTE_ID=" + routeUuid, "FTA_ROUTE_INDEX=0").properties(),
        route);
    registry.initFromTags(
        "leader",
        new TagStore("leader", "FTA_ROUTE_ID=" + routeUuid, "FTA_ROUTE_INDEX=1").properties(),
        route);
    RuntimeDispatchService service =
        createMinimalService(
            manager,
            routeDefinitionCacheWith(route, routeUuid),
            registry,
            debugMessages,
            SmartDispatcherMode.ENFORCE);
    installMovementInhibitor(service, "leader");

    boolean allowed =
        service.smartDepotAdmissionAllowsSpawn(
            "follower",
            new ConflictExitGraph(a, b, c, edgeAb, edgeBc, conflictKey),
            singleConflictContext(
                "follower", conflict, CorridorDirection.A_TO_B, edgeAb, edgeBc, a, b, c));

    assertFalse(allowed, debugMessages.toString());
    assertTrue(
        debugMessages.stream()
            .anyMatch(
                message ->
                    message.contains("SMART_SAME_DIRECTION_CORRIDOR_PRIORITY")
                        && message.contains("corridorOrder=LEADER_AHEAD")
                        && message.contains("selfIsCorridorPriority=false")),
        debugMessages.toString());
    assertTrue(
        debugMessages.stream()
            .anyMatch(
                message ->
                    message.contains("SMART_ADMISSION_REASON")
                        && message.contains("reason=same-direction-leader-stalled")),
        debugMessages.toString());
  }

  @Test
  void corridorTrainAheadBypassesStalledRearLeader() throws Exception {
    List<String> debugMessages = new ArrayList<>();
    NodeId a = NodeId.of("A");
    NodeId b = NodeId.of("B");
    NodeId c = NodeId.of("C");
    NodeId d = NodeId.of("D");
    String conflictKey = "single:test:B~D";
    OccupancyResource conflict = OccupancyResource.forConflict(conflictKey);
    RailEdge edgeBc = new RailEdge(EdgeId.undirected(b, c), b, c, 10, -1.0, true, Optional.empty());
    RailEdge edgeCd = new RailEdge(EdgeId.undirected(c, d), c, d, 10, -1.0, true, Optional.empty());
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    assertTrue(
        manager
            .acquire(singleConflictRequest("rear", conflict, CorridorDirection.A_TO_B))
            .allowed());
    RouteDefinition route =
        new RouteDefinition(RouteId.of("same-route"), List.of(a, b, c, d), Optional.empty());
    UUID routeUuid = UUID.randomUUID();
    RouteProgressRegistry registry = new RouteProgressRegistry();
    registry.initFromTags(
        "front",
        new TagStore("front", "FTA_ROUTE_ID=" + routeUuid, "FTA_ROUTE_INDEX=1").properties(),
        route);
    registry.initFromTags(
        "rear",
        new TagStore("rear", "FTA_ROUTE_ID=" + routeUuid, "FTA_ROUTE_INDEX=0").properties(),
        route);
    RuntimeDispatchService service =
        createMinimalService(
            manager,
            routeDefinitionCacheWith(route, routeUuid),
            registry,
            debugMessages,
            SmartDispatcherMode.ENFORCE);
    installMovementInhibitor(service, "rear");

    boolean allowed =
        service.smartDepotAdmissionAllowsSpawn(
            "front",
            new ConflictExitGraph(b, c, d, edgeBc, edgeCd, conflictKey),
            singleConflictContext(
                "front", conflict, CorridorDirection.A_TO_B, edgeBc, edgeCd, b, c, d));

    assertTrue(allowed, debugMessages.toString());
    assertTrue(
        debugMessages.stream()
            .anyMatch(
                message ->
                    message.contains("SMART_SAME_DIRECTION_CORRIDOR_PRIORITY")
                        && message.contains("corridorOrder=TRAIN_AHEAD")
                        && message.contains("selfIsCorridorPriority=true")),
        debugMessages.toString());
    assertFalse(
        debugMessages.stream()
            .anyMatch(
                message ->
                    message.contains("SMART_ADMISSION_REASON")
                        && message.contains("reason=same-direction-leader-stalled")),
        debugMessages.toString());
  }

  @Test
  void corridorPriorityStillBlockedByPhysicalNode() throws Exception {
    List<String> debugMessages = new ArrayList<>();
    NodeId a = NodeId.of("A");
    NodeId b = NodeId.of("B");
    NodeId c = NodeId.of("C");
    NodeId d = NodeId.of("D");
    String conflictKey = "single:test:B~D";
    OccupancyResource conflict = OccupancyResource.forConflict(conflictKey);
    OccupancyResource physicalNode = OccupancyResource.forNode(c);
    RailEdge edgeBc = new RailEdge(EdgeId.undirected(b, c), b, c, 10, -1.0, true, Optional.empty());
    RailEdge edgeCd = new RailEdge(EdgeId.undirected(c, d), c, d, 10, -1.0, true, Optional.empty());
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    assertTrue(
        manager
            .acquire(singleConflictRequest("rear", conflict, CorridorDirection.A_TO_B))
            .allowed());
    assertTrue(
        manager
            .acquire(
                new OccupancyRequest(
                    "physical-blocker",
                    Optional.empty(),
                    Instant.parse("2026-01-01T00:00:00Z"),
                    List.of(physicalNode),
                    Map.of()))
            .allowed());
    RouteDefinition route =
        new RouteDefinition(RouteId.of("same-route"), List.of(a, b, c, d), Optional.empty());
    UUID routeUuid = UUID.randomUUID();
    RouteProgressRegistry registry = new RouteProgressRegistry();
    registry.initFromTags(
        "front",
        new TagStore("front", "FTA_ROUTE_ID=" + routeUuid, "FTA_ROUTE_INDEX=1").properties(),
        route);
    registry.initFromTags(
        "rear",
        new TagStore("rear", "FTA_ROUTE_ID=" + routeUuid, "FTA_ROUTE_INDEX=0").properties(),
        route);
    RuntimeDispatchService service =
        createMinimalService(
            manager,
            routeDefinitionCacheWith(route, routeUuid),
            registry,
            debugMessages,
            SmartDispatcherMode.ENFORCE);
    installMovementInhibitor(service, "rear");
    OccupancyRequestContext context =
        singleConflictContextWithResources(
            "front",
            conflict,
            CorridorDirection.A_TO_B,
            edgeBc,
            edgeCd,
            b,
            c,
            d,
            List.of(conflict, physicalNode));

    boolean admissionAllowed =
        service.smartDepotAdmissionAllowsSpawn(
            "front", new ConflictExitGraph(b, c, d, edgeBc, edgeCd, conflictKey), context);
    OccupancyDecision physicalDecision = manager.canEnter(context.request());

    assertTrue(admissionAllowed, debugMessages.toString());
    assertFalse(physicalDecision.allowed());
    assertTrue(
        physicalDecision.blockers().stream()
            .anyMatch(claim -> physicalNode.equals(claim.resource())));
  }

  @Test
  void oppositeDirectionUnaffectedStillBlocksBoth() throws Exception {
    NodeId a = NodeId.of("A");
    NodeId b = NodeId.of("B");
    NodeId c = NodeId.of("C");
    String conflictKey = "single:test:A~C";
    OccupancyResource conflict = OccupancyResource.forConflict(conflictKey);
    RailEdge edgeAb = new RailEdge(EdgeId.undirected(a, b), a, b, 10, -1.0, true, Optional.empty());
    RailEdge edgeBc = new RailEdge(EdgeId.undirected(b, c), b, c, 10, -1.0, true, Optional.empty());
    RailGraph eastGraph = new ConflictExitGraph(a, b, c, edgeAb, edgeBc, conflictKey);
    RailGraph westGraph = new ConflictExitGraph(c, b, a, edgeBc, edgeAb, conflictKey);

    AdmissionView aView =
        sameDirectionAdmissionView(
            "east",
            "west",
            conflict,
            CorridorDirection.A_TO_B,
            CorridorDirection.B_TO_A,
            edgeAb,
            edgeBc,
            a,
            b,
            c);
    AdmissionView bView =
        sameDirectionAdmissionView(
            "west",
            "east",
            conflict,
            CorridorDirection.B_TO_A,
            CorridorDirection.A_TO_B,
            edgeBc,
            edgeAb,
            c,
            b,
            a);
    boolean eastAllowed =
        aView.service().smartDepotAdmissionAllowsSpawn("east", eastGraph, aView.context());
    boolean westAllowed =
        bView.service().smartDepotAdmissionAllowsSpawn("west", westGraph, bView.context());

    assertFalse(eastAllowed, aView.debugMessages().toString());
    assertFalse(westAllowed, bView.debugMessages().toString());
    assertTrue(
        aView.debugMessages().stream()
            .anyMatch(
                message ->
                    message.contains("SMART_ADMISSION_REASON")
                        && message.contains("reason=opposite-direction-claim")),
        aView.debugMessages().toString());
    assertTrue(
        bView.debugMessages().stream()
            .anyMatch(
                message ->
                    message.contains("SMART_ADMISSION_REASON")
                        && message.contains("reason=opposite-direction-claim")),
        bView.debugMessages().toString());
  }

  @Test
  void crossRouteSameDirectionLeaderBehindDoesNotBlockPhysicalFrontTrain() {
    List<String> debugMessages = new ArrayList<>();
    NodeId a = NodeId.of("A");
    NodeId b = NodeId.of("B");
    NodeId c = NodeId.of("C");
    NodeId d = NodeId.of("D");
    NodeId e = NodeId.of("E");
    String conflictKey = "single:test:cross-route-order";
    OccupancyResource conflict = OccupancyResource.forConflict(conflictKey);
    RailEdge edgeAb = new RailEdge(EdgeId.undirected(a, b), a, b, 10, -1.0, true, Optional.empty());
    RailEdge edgeBc = new RailEdge(EdgeId.undirected(b, c), b, c, 10, -1.0, true, Optional.empty());
    RailEdge edgeCd = new RailEdge(EdgeId.undirected(c, d), c, d, 10, -1.0, true, Optional.empty());
    RailEdge edgeDe = new RailEdge(EdgeId.undirected(d, e), d, e, 10, -1.0, true, Optional.empty());
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    assertTrue(
        manager
            .acquire(singleConflictRequest("alpha", conflict, CorridorDirection.A_TO_B))
            .allowed());
    assertTrue(
        manager
            .acquire(singleConflictRequest("zulu", conflict, CorridorDirection.A_TO_B))
            .allowed());
    RouteDefinition rearRoute =
        new RouteDefinition(RouteId.of("rear"), List.of(a, b, c, d, e), Optional.empty());
    RouteDefinition frontRoute =
        new RouteDefinition(RouteId.of("front"), List.of(c, d, e), Optional.empty());
    UUID rearRouteUuid = UUID.randomUUID();
    UUID frontRouteUuid = UUID.randomUUID();
    RouteProgressRegistry registry = new RouteProgressRegistry();
    registry.initFromTags(
        "alpha",
        new TagStore("alpha", "FTA_ROUTE_ID=" + rearRouteUuid, "FTA_ROUTE_INDEX=0").properties(),
        rearRoute);
    registry.updateLastPassedGraphNode("alpha", a, Instant.now());
    registry.updateSignal("alpha", SignalAspect.STOP, Instant.now());
    registry.initFromTags(
        "zulu",
        new TagStore("zulu", "FTA_ROUTE_ID=" + frontRouteUuid, "FTA_ROUTE_INDEX=0").properties(),
        frontRoute);
    registry.updateLastPassedGraphNode("zulu", c, Instant.now());
    RuntimeDispatchService service =
        createMinimalService(
            manager,
            routeDefinitionCacheWith(Map.of(rearRouteUuid, rearRoute, frontRouteUuid, frontRoute)),
            registry,
            debugMessages,
            SmartDispatcherMode.ENFORCE);
    RailGraph graph =
        new ParallelConflictExitGraph(
            List.of(edgeAb, edgeBc, edgeCd, edgeDe), Set.of(edgeCd.id()), conflictKey);

    boolean allowed =
        service.smartDepotAdmissionAllowsSpawn(
            "zulu",
            graph,
            singleConflictContext(
                "zulu", conflict, CorridorDirection.A_TO_B, edgeCd, edgeDe, c, d, e));

    assertTrue(allowed, debugMessages.toString());
    assertTrue(
        debugMessages.stream()
            .anyMatch(
                message ->
                    message.contains("SMART_SAME_DIRECTION_LEADER_DISTANCE_ORDER")
                        && message.contains("train=zulu")
                        && message.contains("leader=alpha")
                        && message.contains("result=TRAIN_AHEAD")),
        debugMessages.toString());
    assertTrue(
        debugMessages.stream()
            .anyMatch(
                message ->
                    message.contains("SMART_ALREADY_INSIDE_LEADER_GUARD_SKIPPED")
                        && message.contains("train=zulu")
                        && message.contains("leader=alpha")
                        && message.contains("candidate-leader-not-ahead")),
        debugMessages.toString());
    assertFalse(
        debugMessages.stream()
            .anyMatch(
                message ->
                    message.contains("SMART_ALREADY_INSIDE_REGION_BLOCKED_BY_SAME_DIRECTION_LEADER")
                        && message.contains("train=zulu")
                        && message.contains("leader=alpha")),
        debugMessages.toString());
  }

  @Test
  void sameDirectionLeaderWithoutRouteProofBlocksFollowerInEnforce() throws Exception {
    List<String> debugMessages = new ArrayList<>();
    NodeId a = NodeId.of("A");
    NodeId b = NodeId.of("B");
    NodeId c = NodeId.of("C");
    String conflictKey = "single:test:A~B";
    OccupancyResource conflict = OccupancyResource.forConflict(conflictKey);
    RailEdge edgeAb = new RailEdge(EdgeId.undirected(a, b), a, b, 10, -1.0, true, Optional.empty());
    RailEdge edgeBc = new RailEdge(EdgeId.undirected(b, c), b, c, 10, -1.0, true, Optional.empty());
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    assertTrue(
        manager
            .acquire(singleConflictRequest("leader", conflict, CorridorDirection.A_TO_B))
            .allowed());
    RouteDefinition route =
        new RouteDefinition(RouteId.of("follow"), List.of(a, b, c), Optional.empty());
    RouteProgressRegistry registry = new RouteProgressRegistry();
    registry.initFromTags(
        "leader", new TagStore("leader", "FTA_ROUTE_INDEX=0").properties(), route);
    registry.updateLastPassedGraphNode("leader", b, Instant.now());
    registry.updateSignal("leader", SignalAspect.PROCEED, Instant.now());
    RuntimeDispatchService service =
        createMinimalService(
            manager,
            mock(RouteDefinitionCache.class),
            registry,
            debugMessages,
            SmartDispatcherMode.ENFORCE);
    installMovementToken(
        service,
        new MovementAuthorizationToken(
                "leader", 1L, Instant.now(), a, c, List.of(conflict), SignalAspect.PROCEED)
            .activate("C"));

    boolean allowed =
        service.smartDepotAdmissionAllowsSpawn(
            "follower",
            new ConflictExitGraph(a, b, c, edgeAb, edgeBc, conflictKey),
            singleConflictContext(
                "follower", conflict, CorridorDirection.A_TO_B, edgeAb, edgeBc, a, b, c));

    assertFalse(allowed);
    assertTrue(
        debugMessages.stream()
            .anyMatch(
                message ->
                    message.contains("SMART_SAME_DIRECTION_LEADER_DRAIN_PREDICTION")
                        && message.contains("drainProven=false")
                        && message.contains("same-direction-leader-route-missing")));
    assertTrue(
        debugMessages.stream()
            .anyMatch(message -> message.contains("reason=same-direction-leader-route-missing")));
  }

  @Test
  void sameDirectionLeaderBoundaryOnlyDoesNotAdmitFollower() throws Exception {
    List<String> debugMessages = new ArrayList<>();
    NodeId switcher = NodeId.of("SWITCHER:Towny:1:64:1");
    NodeId station = NodeId.of("OP:S:BOUNDARY:1");
    String conflictKey = "single:test:SW~BOUNDARY";
    OccupancyResource conflict = OccupancyResource.forConflict(conflictKey);
    RailEdge edge =
        new RailEdge(
            EdgeId.undirected(switcher, station),
            switcher,
            station,
            10,
            -1.0,
            true,
            Optional.empty());
    OccupancyRequest leaderAuthority =
        singleConflictPathContext(
                "leader",
                conflict,
                CorridorDirection.A_TO_B,
                List.of(switcher, station),
                List.of(edge),
                List.of(
                    OccupancyResource.forNode(switcher),
                    OccupancyResource.forEdge(edge.id()),
                    OccupancyResource.forNode(station)))
            .request();
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    assertTrue(manager.acquire(leaderAuthority).allowed());
    RouteDefinition route =
        new RouteDefinition(RouteId.of("boundary"), List.of(switcher, station), Optional.empty());
    UUID routeUuid = UUID.randomUUID();
    RouteProgressRegistry registry = new RouteProgressRegistry();
    registry.initFromTags(
        "leader",
        new TagStore("leader", "FTA_ROUTE_ID=" + routeUuid, "FTA_ROUTE_INDEX=0").properties(),
        route);
    registry.updateLastPassedGraphNode("leader", switcher, Instant.now());
    registry.updateSignal("leader", SignalAspect.PROCEED, Instant.now());
    RuntimeDispatchService service =
        createMinimalService(
            manager,
            routeDefinitionCacheWith(route, routeUuid),
            registry,
            debugMessages,
            SmartDispatcherMode.ENFORCE);
    issueAndActivateMovementToken(
        service, "leader", switcher, station, leaderAuthority, station.value());
    long versionBefore = manager.version();
    int claimsBefore = manager.snapshotClaims().size();
    int tokensBefore = movementTokenCount(service);

    boolean allowed =
        service.smartDepotAdmissionAllowsSpawn(
            "follower",
            new ConflictBoundaryGraph(switcher, station, edge, conflictKey),
            singleConflictBoundaryContext(
                "follower", conflict, CorridorDirection.A_TO_B, edge, switcher, station));

    assertFalse(allowed);
    assertEquals(versionBefore, manager.version());
    assertEquals(claimsBefore, manager.snapshotClaims().size());
    assertEquals(tokensBefore, movementTokenCount(service));
    assertTrue(
        debugMessages.stream()
            .anyMatch(
                message ->
                    message.contains("SMART_SAME_DIRECTION_LEADER_DRAIN_PREDICTION")
                        && message.contains("boundaryOnly=true")
                        && message.contains("same-direction-leader-boundary-only")),
        debugMessages.toString());
  }

  @Test
  void emptySingleZoneWithoutVisibleExitFailsClosedAtDepotAdmission() {
    List<String> debugMessages = new ArrayList<>();
    NodeId switcher = NodeId.of("SWITCHER:Towny:1:64:1");
    NodeId plainEnd = NodeId.of("PLAIN-END");
    String conflictKey = "single:test:SW~PLAIN";
    OccupancyResource conflict = OccupancyResource.forConflict(conflictKey);
    RailEdge edge =
        new RailEdge(
            EdgeId.undirected(switcher, plainEnd),
            switcher,
            plainEnd,
            10,
            -1.0,
            true,
            Optional.empty());
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    RuntimeDispatchService service =
        createMinimalService(manager, debugMessages, SmartDispatcherMode.ENFORCE);

    boolean allowed =
        service.smartDepotAdmissionAllowsSpawn(
            "entrant",
            new ConflictBoundaryGraph(switcher, plainEnd, edge, conflictKey),
            singleConflictBoundaryContext(
                "entrant", conflict, CorridorDirection.A_TO_B, edge, switcher, plainEnd));

    assertFalse(allowed, debugMessages.toString());
    assertTrue(
        debugMessages.stream()
            .anyMatch(
                message ->
                    message.contains("SMART_ADMISSION_REASON")
                        && message.contains("entry-lookahead-exit-not-feasible")),
        debugMessages.toString());
  }

  @Test
  void sameDirectionLeaderWithoutVisibleExitDoesNotConvergeFollower() throws Exception {
    List<String> debugMessages = new ArrayList<>();
    NodeId switcher = NodeId.of("SWITCHER:Towny:1:64:1");
    NodeId plainEnd = NodeId.of("PLAIN-END");
    String conflictKey = "single:test:SW~PLAIN";
    OccupancyResource conflict = OccupancyResource.forConflict(conflictKey);
    RailEdge edge =
        new RailEdge(
            EdgeId.undirected(switcher, plainEnd),
            switcher,
            plainEnd,
            10,
            -1.0,
            true,
            Optional.empty());
    OccupancyRequest leaderAuthority =
        singleConflictPathContext(
                "leader",
                conflict,
                CorridorDirection.A_TO_B,
                List.of(switcher, plainEnd),
                List.of(edge),
                List.of(
                    OccupancyResource.forNode(switcher),
                    OccupancyResource.forEdge(edge.id()),
                    OccupancyResource.forNode(plainEnd)))
            .request();
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    assertTrue(manager.acquire(leaderAuthority).allowed());
    RouteDefinition route =
        new RouteDefinition(RouteId.of("plain-end"), List.of(switcher, plainEnd), Optional.empty());
    UUID routeUuid = UUID.randomUUID();
    RouteProgressRegistry registry = new RouteProgressRegistry();
    registry.initFromTags(
        "leader",
        new TagStore("leader", "FTA_ROUTE_ID=" + routeUuid, "FTA_ROUTE_INDEX=0").properties(),
        route);
    registry.updateLastPassedGraphNode("leader", switcher, Instant.now());
    registry.updateSignal("leader", SignalAspect.PROCEED, Instant.now());
    RuntimeDispatchService service =
        createMinimalService(
            manager,
            routeDefinitionCacheWith(route, routeUuid),
            registry,
            debugMessages,
            SmartDispatcherMode.ENFORCE);
    issueAndActivateMovementToken(
        service, "leader", switcher, plainEnd, leaderAuthority, "PLAIN-END");

    boolean allowed =
        service.smartDepotAdmissionAllowsSpawn(
            "follower",
            new ConflictBoundaryGraph(switcher, plainEnd, edge, conflictKey),
            singleConflictBoundaryContext(
                "follower", conflict, CorridorDirection.A_TO_B, edge, switcher, plainEnd));

    assertFalse(allowed);
    assertTrue(
        debugMessages.stream()
            .anyMatch(
                message ->
                    message.contains("SMART_SAME_DIRECTION_LEADER_DRAIN_PREDICTION")
                        && message.contains("boundaryOnly=false")
                        && message.contains("same-direction-leader-exit-not-visible")),
        debugMessages.toString());
  }

  @Test
  void followThroughPreviewDoesNotChangeOccupancyVersion() {
    List<String> debugMessages = new ArrayList<>();
    NodeId a = NodeId.of("A");
    NodeId b = NodeId.of("B");
    NodeId c = NodeId.of("C");
    String conflictKey = "single:test:A~C";
    OccupancyResource conflict = OccupancyResource.forConflict(conflictKey);
    RailEdge edgeAb = new RailEdge(EdgeId.undirected(a, b), a, b, 10, -1.0, true, Optional.empty());
    RailEdge edgeBc = new RailEdge(EdgeId.undirected(b, c), b, c, 10, -1.0, true, Optional.empty());
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    assertTrue(
        manager
            .acquire(singleConflictRequest("leader", conflict, CorridorDirection.A_TO_B))
            .allowed());
    RuntimeDispatchService service =
        createMinimalService(manager, debugMessages, SmartDispatcherMode.OBSERVE_ONLY);
    long versionBefore = manager.version();
    int claimCountBefore = manager.snapshotClaims().size();

    boolean allowed =
        service.smartDepotAdmissionAllowsSpawn(
            "follower",
            new ConflictExitGraph(a, b, c, edgeAb, edgeBc, conflictKey),
            singleConflictContext(
                "follower", conflict, CorridorDirection.A_TO_B, edgeAb, edgeBc, a, b, c));

    assertTrue(allowed);
    assertEquals(versionBefore, manager.version());
    assertEquals(claimCountBefore, manager.snapshotClaims().size());
  }

  @Test
  void followThroughPreviewObserveOnlyDoesNotChangeAdmissionOutcome() throws Exception {
    FollowThroughPreviewSnapshot snapshot =
        runFollowThroughPreview(SmartDispatcherMode.OBSERVE_ONLY, false);

    assertTrue(snapshot.admissionAllowed());
    assertTrue(hasFollowThroughPreviewLog(snapshot.debugMessages()));
  }

  @Test
  void followThroughPreviewObserveOnlyDoesNotChangeSignalOutcome() throws Exception {
    FollowThroughPreviewSnapshot snapshot =
        runFollowThroughPreview(SmartDispatcherMode.OBSERVE_ONLY, false);

    assertEquals(
        snapshot.followerSignalBefore(),
        snapshot.registry().get("follower").orElseThrow().lastSignal());
    assertEquals(
        snapshot.leaderSignalBefore(),
        snapshot.registry().get("leader").orElseThrow().lastSignal());
  }

  @Test
  void followThroughPreviewObserveOnlyDoesNotChangeOccupancyVersion() throws Exception {
    FollowThroughPreviewSnapshot snapshot =
        runFollowThroughPreview(SmartDispatcherMode.OBSERVE_ONLY, false);

    assertEquals(snapshot.occupancyVersionBefore(), snapshot.manager().version());
  }

  @Test
  void followThroughPreviewGlobalEnforceStillDoesNotMutate() throws Exception {
    FollowThroughPreviewSnapshot snapshot =
        runFollowThroughPreview(SmartDispatcherMode.ENFORCE, true);

    assertEquals(snapshot.occupancyVersionBefore(), snapshot.manager().version());
    assertEquals(snapshot.claimsBefore(), snapshot.manager().snapshotClaims().size());
    assertEquals(snapshot.queuesBefore(), snapshot.manager().snapshotQueues().size());
    assertEquals(snapshot.tokensBefore(), movementTokenCount(snapshot.service()));
  }

  @Test
  void followThroughPreviewGlobalEnforceStillDoesNotChangeAdmissionOutcome() throws Exception {
    FollowThroughPreviewSnapshot snapshot =
        runFollowThroughPreview(SmartDispatcherMode.ENFORCE, true);

    assertTrue(snapshot.admissionAllowed());
    assertTrue(
        snapshot.debugMessages().stream()
            .anyMatch(
                message ->
                    message.contains("SMART_FOLLOW_THROUGH_PREVIEW")
                        && message.contains("decision=WOULD_ALLOW_FOLLOW_THROUGH")));
  }

  @Test
  void followThroughPreviewAllowDoesNotEmitStopTrace() throws Exception {
    FollowThroughPreviewSnapshot snapshot =
        runFollowThroughPreview(SmartDispatcherMode.ENFORCE, true);

    assertTrue(snapshot.admissionAllowed());
    assertFalse(
        snapshot.debugMessages().stream()
            .anyMatch(
                message ->
                    message.startsWith("SignalTrace ")
                        && message.contains("newAspect=STOP")
                        && (message.contains("primaryReason=SMART_FOLLOW_THROUGH_PREVIEW")
                            || message.contains("primaryReason=SMART_REGION_VIEW")
                            || message.contains("primaryReason=SMART_LONG_SINGLE_VIEW"))),
        snapshot.debugMessages().toString());
    assertTrue(
        snapshot.debugMessages().stream()
            .anyMatch(
                message ->
                    message.contains("SMART_FOLLOW_THROUGH_PREVIEW")
                        && message.contains("decision=WOULD_ALLOW_FOLLOW_THROUGH")));
  }

  @Test
  void followThroughPreviewGlobalEnforceStillDoesNotCreateClaims() throws Exception {
    FollowThroughPreviewSnapshot snapshot =
        runFollowThroughPreview(SmartDispatcherMode.ENFORCE, true);

    assertEquals(snapshot.claimsBefore(), snapshot.manager().snapshotClaims().size());
  }

  @Test
  void followThroughPreviewGlobalEnforceStillDoesNotIssueMovementToken() throws Exception {
    FollowThroughPreviewSnapshot snapshot =
        runFollowThroughPreview(SmartDispatcherMode.ENFORCE, true);

    assertEquals(snapshot.tokensBefore(), movementTokenCount(snapshot.service()));
  }

  @Test
  void finalSignalRemainsStopWhenOppositeSingleRegionHardBarrierExists() {
    List<String> debugMessages = new ArrayList<>();
    NodeId a = NodeId.of("A");
    NodeId b = NodeId.of("B");
    NodeId c = NodeId.of("C");
    String conflictKey = "single:test:A~C";
    OccupancyResource conflict = OccupancyResource.forConflict(conflictKey);
    RailEdge edgeAb = new RailEdge(EdgeId.undirected(a, b), a, b, 10, -1.0, true, Optional.empty());
    RailEdge edgeBc = new RailEdge(EdgeId.undirected(b, c), b, c, 10, -1.0, true, Optional.empty());
    OccupancyManager occupancyManager = mockOccupancyManager();
    when(occupancyManager.snapshotClaims())
        .thenReturn(
            List.of(
                singleConflictClaim(
                    conflict,
                    "opposite",
                    CorridorDirection.A_TO_B,
                    Instant.parse("2026-01-01T00:00:00Z"))));
    RuntimeDispatchService service =
        createMinimalService(occupancyManager, debugMessages, SmartDispatcherMode.ENFORCE);

    boolean allowed =
        service.smartDepotAdmissionAllowsSpawn(
            "follower",
            new ConflictExitGraph(a, b, c, edgeAb, edgeBc, conflictKey),
            singleConflictContext(
                "follower", conflict, CorridorDirection.B_TO_A, edgeAb, edgeBc, c, b, a));

    assertFalse(allowed);
    assertTrue(hasHardBarrierLog(debugMessages));
    assertTrue(
        debugMessages.stream()
            .anyMatch(message -> message.contains("ROUTE_UNLOCK_POTENTIAL_PREVIEW")));
    assertFalse(debugMessages.stream().anyMatch(message -> message.contains("FORCE_PROCEED")));
    assertFalse(
        debugMessages.stream()
            .anyMatch(message -> message.contains("SAME_DIRECTION_FOLLOW_THROUGH_ALLOW")));
  }

  @Test
  void alreadyInsideDrainOutAllowedOnlyForSameLogicalTrain() {
    List<String> debugMessages = new ArrayList<>();
    NodeId a = NodeId.of("A");
    NodeId b = NodeId.of("B");
    NodeId c = NodeId.of("C");
    String conflictKey = "single:test:A~C";
    OccupancyResource conflict = OccupancyResource.forConflict(conflictKey);
    RailEdge edgeAb = new RailEdge(EdgeId.undirected(a, b), a, b, 10, -1.0, true, Optional.empty());
    RailEdge edgeBc = new RailEdge(EdgeId.undirected(b, c), b, c, 10, -1.0, true, Optional.empty());
    OccupancyManager occupancyManager = mockOccupancyManager();
    when(occupancyManager.snapshotClaims())
        .thenReturn(
            List.of(
                singleConflictClaim(
                    conflict,
                    "follower",
                    CorridorDirection.A_TO_B,
                    Instant.parse("2026-01-01T00:00:00Z"))));
    RuntimeDispatchService service =
        createMinimalService(occupancyManager, debugMessages, SmartDispatcherMode.ENFORCE);

    boolean allowed =
        service.smartDepotAdmissionAllowsSpawn(
            "follower",
            new ConflictExitGraph(a, b, c, edgeAb, edgeBc, conflictKey),
            singleConflictContext(
                "follower", conflict, CorridorDirection.A_TO_B, edgeAb, edgeBc, a, b, c));

    assertTrue(allowed);
    assertFalse(hasHardBarrierLog(debugMessages));
  }

  @Test
  void alreadyInsideSameLogicalTrainMayDrainOutToBoundaryStation() {
    List<String> debugMessages = new ArrayList<>();
    NodeId switcher = NodeId.of("SWITCHER:Towny:1:64:1");
    NodeId station = NodeId.of("OP:S:BOUNDARY:1");
    String conflictKey = "single:test:SW~BOUNDARY";
    OccupancyResource conflict = OccupancyResource.forConflict(conflictKey);
    RailEdge edge =
        new RailEdge(
            EdgeId.undirected(switcher, station),
            switcher,
            station,
            10,
            -1.0,
            true,
            Optional.empty());
    OccupancyManager occupancyManager = mockOccupancyManager();
    when(occupancyManager.snapshotClaims())
        .thenReturn(
            List.of(
                singleConflictClaim(
                    conflict,
                    "follower",
                    CorridorDirection.A_TO_B,
                    Instant.parse("2026-01-01T00:00:00Z"))));
    RuntimeDispatchService service =
        createMinimalService(occupancyManager, debugMessages, SmartDispatcherMode.ENFORCE);

    boolean allowed =
        service.smartDepotAdmissionAllowsSpawn(
            "follower",
            new ConflictBoundaryGraph(switcher, station, edge, conflictKey),
            singleConflictBoundaryContext(
                "follower", conflict, CorridorDirection.A_TO_B, edge, switcher, station));

    assertTrue(allowed);
    assertFalse(hasHardBarrierLog(debugMessages));
    assertFalse(
        debugMessages.stream()
            .anyMatch(message -> message.contains("ALREADY_INSIDE_CONTINUE_MISSING_EXIT_PROOF")));
    assertTrue(
        debugMessages.stream()
            .anyMatch(
                message -> message.contains("SMART_ALREADY_INSIDE_REGION_BYPASS_ENTRY_GATE")));
  }

  @Test
  void healthDestroyMayRunButDoesNotConvertHardBarrierIntoDispatcherAllow() {
    List<String> debugMessages = new ArrayList<>();
    RuntimeDispatchService service =
        createMinimalService(mockOccupancyManager(), debugMessages, SmartDispatcherMode.ENFORCE);

    RuntimeDispatchService.SmartRecoveryActionResult result =
        service.applySmartForwardUnlock(
            singleRegionHardBarrierRecoveryInput("train-1", true, "opposite-single-conflict"));

    assertFalse(result.applied());
    assertEquals(
        SimpleOccupancyManager.OPPOSITE_OR_UNKNOWN_SINGLE_REGION_HARD_BARRIER, result.reason());
    assertFalse(
        debugMessages.stream()
            .anyMatch(message -> message.contains("SMART_ACTION_ALLOWED_BY_EFFECT_GATE")));
    assertFalse(debugMessages.stream().anyMatch(message -> message.contains("DESTROY_TRAIN")));
  }

  @Test
  void smartDrainUnlockAllowsProvenSingleRegionDrainOut() {
    List<String> debugMessages = new ArrayList<>();
    OccupancyResource conflict = OccupancyResource.forConflict("single:test:A~B");
    OccupancyManager occupancyManager = mockOccupancyManager();
    when(occupancyManager.snapshotClaims())
        .thenReturn(
            List.of(
                singleConflictClaim(
                    conflict,
                    "train-1",
                    CorridorDirection.A_TO_B,
                    Instant.parse("2026-01-01T00:00:00Z")),
                singleConflictClaim(
                    conflict,
                    "train-2",
                    CorridorDirection.B_TO_A,
                    Instant.parse("2026-01-01T00:00:01Z"))));
    RuntimeDispatchService service =
        createMinimalService(occupancyManager, debugMessages, SmartDispatcherMode.ENFORCE);

    RuntimeDispatchService.SmartRecoveryActionResult result =
        service.applySmartDrainUnlock(
            singleRegionHardBarrierRecoveryInput("train-1", true, "opposite-single-conflict"));

    assertTrue(result.candidate());
    assertTrue(result.applied());
    assertTrue(
        debugMessages.stream()
            .anyMatch(
                message ->
                    message.contains("SMART_DRAIN_UNLOCK_DRAIN_OUT_ALLOWED")
                        && message.contains("occupancyDecreasing=true")
                        && message.contains("externalTrain=train-2")));
    assertFalse(
        debugMessages.stream()
            .anyMatch(
                message ->
                    message.contains("SMART_DRAIN_UNLOCK_BLOCKED_BY_SINGLE_REGION_HARD_BARRIER")));
  }

  @Test
  void smartForwardUnlockAllowsProvenSingleRegionDrainOutToFinalRefresh() {
    List<String> debugMessages = new ArrayList<>();
    OccupancyResource conflict = OccupancyResource.forConflict("single:test:A~B");
    OccupancyManager occupancyManager = mockOccupancyManager();
    when(occupancyManager.snapshotClaims())
        .thenReturn(
            List.of(
                singleConflictClaim(
                    conflict,
                    "train-1",
                    CorridorDirection.A_TO_B,
                    Instant.parse("2026-01-01T00:00:00Z")),
                singleConflictClaim(
                    conflict, "train-2", null, Instant.parse("2026-01-01T00:00:01Z"))));
    RuntimeDispatchService service =
        createMinimalService(occupancyManager, debugMessages, SmartDispatcherMode.ENFORCE);

    RuntimeDispatchService.SmartRecoveryActionResult result =
        service.applySmartForwardUnlock(
            singleRegionHardBarrierRecoveryInput(
                "train-1",
                true,
                Set.of("CONFLICT:single:test:A~B"),
                "movement-authority-window-opposite-single-conflict"));

    assertTrue(result.candidate());
    assertFalse(result.applied());
    assertEquals("runtime-train-not-resolved", result.reason());
    assertTrue(
        debugMessages.stream()
            .anyMatch(
                message ->
                    message.contains("SMART_FORWARD_UNLOCK_DRAIN_OUT_ALLOWED")
                        && message.contains("externalDirection=UNKNOWN")));
    assertFalse(
        debugMessages.stream()
            .anyMatch(
                message ->
                    message.contains(
                        "SMART_FORWARD_UNLOCK_BLOCKED_BY_SINGLE_REGION_HARD_BARRIER")));
  }

  @Test
  void smartDrainUnlockDrainOutProofDoesNotCoverUnrelatedHardBlocker() {
    List<String> debugMessages = new ArrayList<>();
    OccupancyResource conflict = OccupancyResource.forConflict("single:test:A~B");
    OccupancyManager occupancyManager = mockOccupancyManager();
    when(occupancyManager.snapshotClaims())
        .thenReturn(
            List.of(
                singleConflictClaim(
                    conflict,
                    "train-1",
                    CorridorDirection.A_TO_B,
                    Instant.parse("2026-01-01T00:00:00Z")),
                singleConflictClaim(
                    conflict,
                    "train-2",
                    CorridorDirection.B_TO_A,
                    Instant.parse("2026-01-01T00:00:01Z"))));
    RuntimeDispatchService service =
        createMinimalService(occupancyManager, debugMessages, SmartDispatcherMode.ENFORCE);

    RuntimeDispatchService.SmartRecoveryActionResult result =
        service.applySmartDrainUnlock(
            singleRegionHardBarrierRecoveryInput(
                "train-1", true, Set.of("NODE:B"), "opposite-single-conflict"));

    assertFalse(result.candidate());
    assertFalse(result.applied());
    assertTrue(
        debugMessages.stream()
            .anyMatch(
                message ->
                    message.contains("SMART_DRAIN_UNLOCK_DRAIN_OUT_DENIED")
                        && message.contains("hard-blocker-not-covered-by-drain-out")));
    assertTrue(
        debugMessages.stream()
            .anyMatch(
                message ->
                    message.contains("SMART_DRAIN_UNLOCK_BLOCKED_BY_SINGLE_REGION_HARD_BARRIER")));
  }

  @Test
  void smartAlreadyInsideSingleDoesNotUseEntryGate() {
    List<String> debugMessages = new ArrayList<>();
    NodeId a = NodeId.of("A");
    NodeId b = NodeId.of("B");
    NodeId c = NodeId.of("C");
    String conflictKey = "single:test:A~C";
    OccupancyResource conflict = OccupancyResource.forConflict(conflictKey);
    RailEdge edgeAb = new RailEdge(EdgeId.undirected(a, b), a, b, 10, -1.0, true, Optional.empty());
    RailEdge edgeBc = new RailEdge(EdgeId.undirected(b, c), b, c, 10, -1.0, true, Optional.empty());
    OccupancyManager occupancyManager = mockOccupancyManager();
    when(occupancyManager.snapshotClaims())
        .thenReturn(
            List.of(
                singleConflictClaim(
                    conflict,
                    "follower",
                    CorridorDirection.A_TO_B,
                    Instant.parse("2026-01-01T00:00:00Z"))));
    RuntimeDispatchService service =
        createMinimalService(occupancyManager, debugMessages, SmartDispatcherMode.ENFORCE);

    boolean allowed =
        service.smartDepotAdmissionAllowsSpawn(
            "follower",
            new ConflictExitGraph(a, b, c, edgeAb, edgeBc, conflictKey),
            singleConflictContext(
                "follower", conflict, CorridorDirection.A_TO_B, edgeAb, edgeBc, a, b, c));

    assertTrue(allowed);
    assertTrue(
        debugMessages.stream()
            .anyMatch(
                message -> message.contains("SMART_ALREADY_INSIDE_REGION_BYPASS_ENTRY_GATE")));
  }

  @Test
  void smartForwardUnlockSuppressedInObserveOnly() {
    List<String> debugMessages = new ArrayList<>();
    RuntimeDispatchService service =
        createMinimalService(
            mockOccupancyManager(), debugMessages, SmartDispatcherMode.OBSERVE_ONLY);

    RuntimeDispatchService.SmartRecoveryActionResult result =
        service.applySmartForwardUnlock(smartForwardUnlockInput("train-1", Set.of(), false, false));

    assertTrue(result.candidate());
    assertFalse(result.applied());
    assertTrue(
        debugMessages.stream()
            .anyMatch(message -> message.contains("SMART_FORWARD_UNLOCK_SUPPRESSED_BY_MODE")));
    assertTrue(
        debugMessages.stream()
            .anyMatch(message -> message.contains("SMART_ACTION_SUPPRESSED_BY_MODE")));
  }

  @Test
  void smartForwardUnlockNotAppliedWhenHardBlockerExists() {
    List<String> debugMessages = new ArrayList<>();
    RuntimeDispatchService service =
        createMinimalService(mockOccupancyManager(), debugMessages, SmartDispatcherMode.ENFORCE);

    RuntimeDispatchService.SmartRecoveryActionResult result =
        service.applySmartForwardUnlock(
            smartForwardUnlockInput("train-1", Set.of("front"), false, false));

    assertFalse(result.candidate());
    assertFalse(result.applied());
    assertTrue(
        debugMessages.stream()
            .anyMatch(message -> message.contains("SMART_FORWARD_UNLOCK_BLOCKED_BY_HARD_BLOCKER")));
  }

  @Test
  void smartForwardUnlockNotAppliedWhenOppositeSingleConflictExists() {
    List<String> debugMessages = new ArrayList<>();
    RuntimeDispatchService service =
        createMinimalService(mockOccupancyManager(), debugMessages, SmartDispatcherMode.ENFORCE);

    RuntimeDispatchService.SmartRecoveryActionResult result =
        service.applySmartForwardUnlock(smartForwardUnlockInput("train-1", Set.of(), true, false));

    assertFalse(result.candidate());
    assertFalse(result.applied());
    assertTrue(
        debugMessages.stream()
            .anyMatch(
                message ->
                    message.contains(
                        "SMART_FORWARD_UNLOCK_BLOCKED_BY_SINGLE_REGION_HARD_BARRIER")));
  }

  @Test
  void smartForwardUnlockDoesNotClearValidDestinationOrInvalidateToken() {
    List<String> debugMessages = new ArrayList<>();
    RuntimeDispatchService service =
        createMinimalService(mockOccupancyManager(), debugMessages, SmartDispatcherMode.ENFORCE);

    RuntimeDispatchService.SmartRecoveryActionResult result =
        service.applySmartForwardUnlock(smartForwardUnlockInput("train-1", Set.of(), false, true));

    assertTrue(result.candidate());
    assertFalse(result.applied(), "缺少 runtime train 时不能声称已执行");
    assertFalse(
        debugMessages.stream().anyMatch(message -> message.contains("tokenInvalidated=true")));
    assertFalse(
        debugMessages.stream().anyMatch(message -> message.contains("destinationMutated=true")));
  }

  @Test
  void signalAdvisoryDoesNotOverrideFinalRed() {
    List<String> debugMessages = new ArrayList<>();
    RouteProgressRegistry registry = signalRegistryForTrain("train-1", SignalAspect.STOP);
    RuntimeDispatchService service =
        createMinimalService(
            mockOccupancyManager(),
            mock(RouteDefinitionCache.class),
            registry,
            debugMessages,
            SmartDispatcherMode.ENFORCE);

    service.applySmartForwardUnlock(smartForwardUnlockInput("train-1", Set.of(), false, true));

    assertEquals(SignalAspect.STOP, registry.get("train-1").orElseThrow().lastSignal());
    assertFalse(debugMessages.stream().anyMatch(message -> message.contains("signal=PROCEED")));
  }

  @Test
  void signalAdvisoryDoesNotForceGreen() {
    List<String> debugMessages = new ArrayList<>();
    RouteProgressRegistry registry = signalRegistryForTrain("train-1", SignalAspect.STOP);
    RuntimeDispatchService service =
        createMinimalService(
            mockOccupancyManager(),
            mock(RouteDefinitionCache.class),
            registry,
            debugMessages,
            SmartDispatcherMode.ENFORCE);

    service.applySmartDrainUnlock(smartDrainUnlockInput("train-1", Set.of(), false));

    assertEquals(SignalAspect.STOP, registry.get("train-1").orElseThrow().lastSignal());
    assertFalse(debugMessages.stream().anyMatch(message -> message.contains("signal=PROCEED")));
  }

  @Test
  void signalAdvisoryCannotClearInhibitorWhenLiveBarrierAppearsAfterSnapshot() throws Exception {
    List<String> debugMessages = new ArrayList<>();
    RuntimeDispatchService service =
        createMinimalService(mockOccupancyManager(), debugMessages, SmartDispatcherMode.ENFORCE);
    installMovementInhibitor(service, "train-1");
    installSmartUnlockBlockerSnapshot(
        service, "train-1", "external", "CONFLICT:single:test:A~B", Instant.now());

    RuntimeDispatchService.SmartRecoveryActionResult result =
        service.applySmartDrainUnlock(smartDrainUnlockInput("train-1", Set.of(), false));

    assertTrue(result.candidate());
    assertTrue(service.isMovementInhibited("train-1"));
    assertTrue(
        debugMessages.stream()
            .anyMatch(
                message ->
                    message.contains("event=SIGNAL_ADVISORY_SUPPRESSED")
                        && message.contains("SIGNAL_ADVISORY_SUPPRESSED_BY_HARD_BARRIER")
                        && message.contains("didMutate=false")));
    assertFalse(
        debugMessages.stream().anyMatch(message -> message.contains("inhibitorCleared=true")));
  }

  @Test
  void observeOnlySignalAdvisoryDoesNotClearMovementInhibitor() throws Exception {
    List<String> debugMessages = new ArrayList<>();
    RuntimeDispatchService service =
        createMinimalService(
            mockOccupancyManager(), debugMessages, SmartDispatcherMode.OBSERVE_ONLY);
    installMovementInhibitor(service, "train-1");

    RuntimeDispatchService.SmartRecoveryActionResult result =
        service.applySmartDrainUnlock(smartDrainUnlockInput("train-1", Set.of(), false));

    assertTrue(result.candidate());
    assertFalse(result.applied());
    assertTrue(service.isMovementInhibited("train-1"));
    assertFalse(
        debugMessages.stream().anyMatch(message -> message.contains("inhibitorCleared=true")));
  }

  @Test
  void observeOnlySignalAdvisoryDoesNotChangeSignalOutcome() {
    List<String> debugMessages = new ArrayList<>();
    RouteProgressRegistry registry = signalRegistryForTrain("train-1", SignalAspect.STOP);
    RuntimeDispatchService service =
        createMinimalService(
            mockOccupancyManager(),
            mock(RouteDefinitionCache.class),
            registry,
            debugMessages,
            SmartDispatcherMode.OBSERVE_ONLY);

    service.applySmartForwardUnlock(smartForwardUnlockInput("train-1", Set.of(), false, true));

    assertEquals(SignalAspect.STOP, registry.get("train-1").orElseThrow().lastSignal());
    assertTrue(
        debugMessages.stream()
            .anyMatch(message -> message.contains("SMART_FORWARD_UNLOCK_SUPPRESSED_BY_MODE")));
  }

  @Test
  void signalAdvisoryRequiresFinalReevaluateBeforeProceed() {
    List<String> debugMessages = new ArrayList<>();
    RouteProgressRegistry registry = signalRegistryForTrain("train-1", SignalAspect.STOP);
    RuntimeDispatchService service =
        createMinimalService(
            mockOccupancyManager(),
            mock(RouteDefinitionCache.class),
            registry,
            debugMessages,
            SmartDispatcherMode.ENFORCE);

    RuntimeDispatchService.SmartRecoveryActionResult result =
        service.applySmartForwardUnlock(smartForwardUnlockInput("train-1", Set.of(), false, true));

    assertTrue(result.candidate());
    assertEquals(SignalAspect.STOP, registry.get("train-1").orElseThrow().lastSignal());
    assertTrue(
        debugMessages.stream()
            .anyMatch(message -> message.contains("SMART_FORWARD_UNLOCK_VERIFY")));
    assertTrue(
        debugMessages.stream()
            .anyMatch(message -> message.contains("SMART_RECOVERY_EFFECT_VERIFY")));
  }

  @Test
  void oppositeDirectionSingleRegionBlocksSignalAdvisory() throws Exception {
    List<String> debugMessages = new ArrayList<>();
    RuntimeDispatchService service =
        createMinimalService(mockOccupancyManager(), debugMessages, SmartDispatcherMode.ENFORCE);
    installMovementInhibitor(service, "train-1");

    RuntimeDispatchService.SmartRecoveryActionResult result =
        service.applySmartForwardUnlock(
            singleRegionHardBarrierRecoveryInput("train-1", true, "opposite-single-conflict"));

    assertFalse(result.applied());
    assertTrue(service.isMovementInhibited("train-1"));
    assertEquals(
        SimpleOccupancyManager.OPPOSITE_OR_UNKNOWN_SINGLE_REGION_HARD_BARRIER, result.reason());
    assertTrue(hasHardBarrierLog(debugMessages));
    assertFalse(debugMessages.stream().anyMatch(message -> message.contains("FORCE_PROCEED")));
    assertFalse(debugMessages.stream().anyMatch(message -> message.contains("DESTROY_TRAIN")));
  }

  @Test
  void unknownDirectionSingleRegionBlocksSignalAdvisory() throws Exception {
    List<String> debugMessages = new ArrayList<>();
    RuntimeDispatchService service =
        createMinimalService(mockOccupancyManager(), debugMessages, SmartDispatcherMode.ENFORCE);
    installMovementInhibitor(service, "train-1");

    RuntimeDispatchService.SmartRecoveryActionResult result =
        service.applySmartDrainUnlock(
            singleRegionHardBarrierRecoveryInput(
                "train-1", false, "single-conflict-direction-unknown"));

    assertFalse(result.applied());
    assertTrue(service.isMovementInhibited("train-1"));
    assertEquals(
        SimpleOccupancyManager.OPPOSITE_OR_UNKNOWN_SINGLE_REGION_HARD_BARRIER, result.reason());
    assertTrue(hasHardBarrierLog(debugMessages));
  }

  @Test
  void oppositeDirectionSingleRegionBlocksMovementInhibitorClear() throws Exception {
    List<String> debugMessages = new ArrayList<>();
    RuntimeDispatchService service =
        createMinimalService(mockOccupancyManager(), debugMessages, SmartDispatcherMode.ENFORCE);
    installMovementInhibitor(service, "train-1");

    service.applySmartForwardUnlock(
        singleRegionHardBarrierRecoveryInput("train-1", true, "opposite-single-conflict"));

    assertTrue(service.isMovementInhibited("train-1"));
    assertFalse(
        debugMessages.stream().anyMatch(message -> message.contains("inhibitorCleared=true")));
  }

  @Test
  void selfOwnedProtectiveRetainReleaseSuppressedInObserveOnly() {
    List<String> debugMessages = new ArrayList<>();
    SimpleOccupancyManager manager = simpleOccupancyManagerWithSelfRetainCandidate();
    RuntimeDispatchService service =
        createMinimalService(manager, debugMessages, SmartDispatcherMode.OBSERVE_ONLY);

    RuntimeDispatchService.SmartRecoveryActionResult result =
        service.applySmartSelfOwnedStaleRetainRelease(smartSelfRetainInput("train-1"));

    assertTrue(result.candidate());
    assertFalse(result.applied());
    assertFalse(manager.snapshotClaims().isEmpty());
    assertTrue(
        debugMessages.stream()
            .anyMatch(message -> message.contains("SMART_STALE_SELF_RETAIN_WOULD_RELEASE")));
  }

  @Test
  void selfOwnedProtectiveRetainReleasedInEnforce() {
    List<String> debugMessages = new ArrayList<>();
    SimpleOccupancyManager manager = simpleOccupancyManagerWithSelfRetainCandidate();
    RuntimeDispatchService service =
        createMinimalService(manager, debugMessages, SmartDispatcherMode.ENFORCE);
    long versionBefore = manager.version();

    RuntimeDispatchService.SmartRecoveryActionResult result =
        service.applySmartSelfOwnedStaleRetainRelease(smartSelfRetainInput("train-1"));

    assertTrue(result.candidate());
    assertTrue(result.applied());
    assertEquals(versionBefore + 1, manager.version());
    assertTrue(manager.snapshotClaims().isEmpty());
    assertTrue(
        debugMessages.stream()
            .anyMatch(message -> message.contains("SMART_STALE_SELF_RETAIN_RELEASE_APPLIED")));
    assertTrue(
        debugMessages.stream()
            .anyMatch(
                message ->
                    message.contains("SMART_STALE_SELF_RETAIN_RELEASE_REEVALUATED")
                        && message.contains("result=ALLOW")));
    assertFalse(
        debugMessages.stream().anyMatch(message -> message.contains("destinationMutated=true")));
    assertFalse(
        debugMessages.stream().anyMatch(message -> message.contains("tokenInvalidated=true")));
  }

  @Test
  void healthSelfOwnedRetainReleaseRechecksCachedInterlockingScope() {
    List<String> debugMessages = new ArrayList<>();
    String trainName = "train-1";
    OccupancyResource single = OccupancyResource.forConflict("single:test:A~B");
    OccupancyResource interlocking = OccupancyResource.forConflict("interlocking:0123456789abcdef");
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    OccupancyRequest retain =
        singleConflictRequest(trainName, single, CorridorDirection.A_TO_B)
            .withResourceIntents(Map.of(single, ResourceIntent.PROTECTIVE_RETAIN));
    assertTrue(manager.acquire(retain).allowed());

    OccupancyRequest reversed = singleConflictRequest(trainName, single, CorridorDirection.B_TO_A);
    OccupancyRequest withClearInterlockingAhead =
        new OccupancyRequest(
                reversed.trainName(),
                reversed.routeId(),
                reversed.now(),
                List.of(single, interlocking),
                reversed.corridorDirections(),
                reversed.conflictEntryOrders(),
                reversed.priority(),
                reversed.purpose(),
                reversed.conflictReleaseHints(),
                reversed.resourceIntents())
            .withDirectedContext(reversed.directedContext());
    assertFalse(manager.canEnter(withClearInterlockingAhead).allowed());
    assertTrue(manager.selfOwnedStaleRetainReleaseCandidate(trainName).isPresent());
    assertTrue(
        manager
            .acquire(
                new OccupancyRequest(
                    "blocker",
                    Optional.empty(),
                    Instant.parse("2026-01-01T00:00:01Z"),
                    List.of(interlocking),
                    Map.of()))
            .allowed());
    RuntimeDispatchService service =
        createMinimalService(manager, debugMessages, SmartDispatcherMode.ENFORCE);

    RuntimeDispatchService.SmartRecoveryActionResult result =
        service.applySmartSelfOwnedStaleRetainRelease(smartSelfRetainInput(trainName));

    assertFalse(result.applied());
    assertTrue(
        manager.snapshotClaims().stream()
            .anyMatch(
                claim ->
                    claim.resource().equals(single)
                        && claim.trainName().equals(trainName)
                        && claim.role() == ClaimRole.PROTECTIVE_RETAIN));
    assertTrue(
        debugMessages.stream()
            .anyMatch(
                message ->
                    message.contains("SMART_STALE_SELF_RETAIN_RELEASE_SKIPPED")
                        && message.contains("reason=self-owned-stale-retain-not-found")));
  }

  @Test
  void selfOwnedRetainDecisionChainReleases1302InEnforce() throws Exception {
    List<String> debugMessages = new ArrayList<>();
    String trainName = "SURC-DS-LW-1302";
    String conflictKey = "single:test:JBS~WSD";
    OccupancyResource conflict = OccupancyResource.forConflict(conflictKey);
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    OccupancyRequest staleRetain =
        singleConflictRequest(trainName, conflict, CorridorDirection.B_TO_A)
            .withResourceIntents(Map.of(conflict, ResourceIntent.PROTECTIVE_RETAIN));
    assertTrue(manager.acquire(staleRetain).allowed());
    RuntimeDispatchService service =
        createMinimalService(manager, debugMessages, SmartDispatcherMode.ENFORCE);
    OccupancyRequest request = singleConflictRequest(trainName, conflict, CorridorDirection.A_TO_B);
    OccupancyDecision blocked = manager.canEnter(request);
    assertFalse(blocked.allowed());

    OccupancyDecision recovered =
        recoverSelfOwnedStaleRetainDecision(service, request, blocked, "signal-canenter");

    assertTrue(manager.selfOwnedStaleRetainReleaseCandidate(trainName).isEmpty());
    assertFalse(
        manager.snapshotClaims().stream()
            .anyMatch(
                claim ->
                    claim.role() == ClaimRole.PROTECTIVE_RETAIN
                        && claim.corridorDirection().orElse(CorridorDirection.UNKNOWN)
                            == CorridorDirection.B_TO_A));
    assertTrue(recovered.allowed());
    assertTrue(
        debugMessages.stream()
            .anyMatch(message -> message.contains("SMART_SELF_RETAIN_RELEASE_APPLIED")));
    assertTrue(
        debugMessages.stream()
            .anyMatch(
                message ->
                    message.contains("SMART_SELF_RETAIN_RELEASE_VERIFY")
                        && message.contains("result=ALLOW")));
    assertFalse(
        debugMessages.stream().anyMatch(message -> message.contains("destinationMutated=true")));
    assertFalse(
        debugMessages.stream().anyMatch(message -> message.contains("tokenInvalidated=true")));
  }

  @Test
  void decisionChainRecoveryVerifyDoesNotMutateOccupancy() throws Exception {
    List<String> debugMessages = new ArrayList<>();
    String trainName = "train-1";
    OccupancyResource conflict = OccupancyResource.forConflict("single:test:A~B");
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    OccupancyRequest retain =
        singleConflictRequest(trainName, conflict, CorridorDirection.A_TO_B)
            .withResourceIntents(Map.of(conflict, ResourceIntent.PROTECTIVE_RETAIN));
    assertTrue(manager.acquire(retain).allowed());
    OccupancyRequest request = singleConflictRequest(trainName, conflict, CorridorDirection.B_TO_A);
    OccupancyDecision blocked = manager.canEnter(request);
    assertFalse(blocked.allowed());
    RuntimeDispatchService service =
        createMinimalService(manager, debugMessages, SmartDispatcherMode.ENFORCE);
    long versionBeforeRecovery = manager.version();
    List<OccupancyQueueSnapshot> queuesBeforeRecovery = manager.snapshotQueues();

    OccupancyDecision recovered =
        recoverSelfOwnedStaleRetainDecision(service, request, blocked, "signal-canenter");

    assertTrue(recovered.allowed());
    assertEquals(versionBeforeRecovery + 1, manager.version());
    assertTrue(manager.snapshotClaims().isEmpty());
    assertTrue(manager.snapshotQueues().size() <= queuesBeforeRecovery.size());
    assertTrue(
        debugMessages.stream()
            .anyMatch(
                message ->
                    message.contains("SMART_SELF_RETAIN_RELEASE_VERIFY")
                        && message.contains("result=ALLOW")));
  }

  @Test
  void selfOwnedRetainDecisionChainObserveOnlyWouldRelease0712WithoutMutation() throws Exception {
    List<String> debugMessages = new ArrayList<>();
    String trainName = "SURC-DS-LW-0712";
    String conflictKey = "single:test:JBS~WSD";
    OccupancyResource conflict = OccupancyResource.forConflict(conflictKey);
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    OccupancyRequest staleRetain =
        singleConflictRequest(trainName, conflict, CorridorDirection.B_TO_A)
            .withResourceIntents(Map.of(conflict, ResourceIntent.PROTECTIVE_RETAIN));
    assertTrue(manager.acquire(staleRetain).allowed());
    RuntimeDispatchService service =
        createMinimalService(manager, debugMessages, SmartDispatcherMode.OBSERVE_ONLY);
    OccupancyRequest request = singleConflictRequest(trainName, conflict, CorridorDirection.A_TO_B);
    OccupancyDecision blocked = manager.canEnter(request);
    assertFalse(blocked.allowed());

    OccupancyDecision recovered =
        recoverSelfOwnedStaleRetainDecision(service, request, blocked, "signal-canenter");

    assertTrue(manager.selfOwnedStaleRetainReleaseCandidate(trainName).isPresent());
    assertTrue(
        manager.snapshotClaims().stream()
            .anyMatch(
                claim ->
                    claim.role() == ClaimRole.PROTECTIVE_RETAIN
                        && claim.corridorDirection().orElse(CorridorDirection.UNKNOWN)
                            == CorridorDirection.B_TO_A));
    assertFalse(recovered.allowed());
    assertTrue(
        debugMessages.stream()
            .anyMatch(message -> message.contains("SMART_SELF_RETAIN_RELEASE_WOULD_APPLY")));
    assertFalse(
        debugMessages.stream()
            .anyMatch(message -> message.contains("SMART_SELF_RETAIN_RELEASE_APPLIED")));
  }

  @Test
  void departureSelfOwnedRetainPreviewObserveOnlyDoesNotMutateOccupancy() throws Exception {
    assertDepartureSelfOwnedRetainPreviewDoesNotMutate(SmartDispatcherMode.OBSERVE_ONLY);
  }

  @Test
  void departureSelfOwnedRetainPreviewOffDoesNotMutateOccupancy() throws Exception {
    assertDepartureSelfOwnedRetainPreviewDoesNotMutate(SmartDispatcherMode.OFF);
  }

  @Test
  void healthSelfOwnedRetainReleaseRejectsHoldOnlyCandidateInEnforce() {
    List<String> debugMessages = new ArrayList<>();
    OccupancyResource conflict = OccupancyResource.forConflict("single:test:A~B");
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    OccupancyRequest hold =
        singleConflictRequest("train-1", conflict, CorridorDirection.A_TO_B)
            .withResourceIntents(Map.of(conflict, ResourceIntent.HOLD_ONLY));
    assertTrue(manager.acquire(hold).allowed());
    assertFalse(
        manager
            .canEnter(singleConflictRequest("train-1", conflict, CorridorDirection.B_TO_A))
            .allowed());
    assertTrue(manager.selfOwnedStaleRetainReleaseCandidate("train-1").isPresent());
    RuntimeDispatchService service =
        createMinimalService(manager, debugMessages, SmartDispatcherMode.ENFORCE);

    RuntimeDispatchService.SmartRecoveryActionResult result =
        service.applySmartSelfOwnedStaleRetainRelease(smartSelfRetainInput("train-1"));

    assertFalse(result.applied());
    assertEquals(1, manager.snapshotClaims().size());
    assertEquals(ClaimRole.HOLD_ONLY, manager.snapshotClaims().get(0).role());
    assertTrue(
        debugMessages.stream().anyMatch(message -> message.contains("reason=outside-p0-boundary")));
  }

  @Test
  void healthSelfOwnedRetainReleaseRejectsUnknownDirectionCandidateInEnforce() {
    List<String> debugMessages = new ArrayList<>();
    OccupancyResource conflict = OccupancyResource.forConflict("single:test:A~B");
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    OccupancyRequest retain =
        singleConflictRequest("train-1", conflict, CorridorDirection.A_TO_B)
            .withResourceIntents(Map.of(conflict, ResourceIntent.PROTECTIVE_RETAIN));
    assertTrue(manager.acquire(retain).allowed());
    OccupancyRequest unknownDirection =
        new OccupancyRequest(
            "train-1",
            Optional.empty(),
            Instant.parse("2026-01-01T00:00:00Z"),
            List.of(conflict),
            Map.of(conflict.key(), CorridorDirection.UNKNOWN),
            Map.of(conflict.key(), 0),
            0);
    manager.canEnter(unknownDirection);
    assertTrue(manager.selfOwnedStaleRetainReleaseCandidate("train-1").isEmpty());
    RuntimeDispatchService service =
        createMinimalService(manager, debugMessages, SmartDispatcherMode.ENFORCE);

    RuntimeDispatchService.SmartRecoveryActionResult result =
        service.applySmartSelfOwnedStaleRetainRelease(smartSelfRetainInput("train-1"));

    assertFalse(result.applied());
    assertEquals(1, manager.snapshotClaims().size());
    assertEquals(ClaimRole.PROTECTIVE_RETAIN, manager.snapshotClaims().get(0).role());
  }

  @Test
  void healthSelfOwnedRetainReleaseRejectsNonOppositeDirectionCandidateInEnforce() {
    List<String> debugMessages = new ArrayList<>();
    OccupancyResource conflict = OccupancyResource.forConflict("single:test:A~B");
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    OccupancyRequest retain =
        singleConflictRequest("train-1", conflict, CorridorDirection.A_TO_B)
            .withResourceIntents(Map.of(conflict, ResourceIntent.PROTECTIVE_RETAIN));
    assertTrue(manager.acquire(retain).allowed());
    assertTrue(
        manager
            .canEnter(singleConflictRequest("train-1", conflict, CorridorDirection.A_TO_B))
            .allowed());
    assertTrue(manager.selfOwnedStaleRetainReleaseCandidate("train-1").isEmpty());
    RuntimeDispatchService service =
        createMinimalService(manager, debugMessages, SmartDispatcherMode.ENFORCE);

    RuntimeDispatchService.SmartRecoveryActionResult result =
        service.applySmartSelfOwnedStaleRetainRelease(smartSelfRetainInput("train-1"));

    assertFalse(result.applied());
    assertEquals(1, manager.snapshotClaims().size());
    assertEquals(ClaimRole.PROTECTIVE_RETAIN, manager.snapshotClaims().get(0).role());
  }

  @Test
  void smartDrainUnlockSuppressedInObserveOnly() {
    List<String> debugMessages = new ArrayList<>();
    RuntimeDispatchService service =
        createMinimalService(
            mockOccupancyManager(), debugMessages, SmartDispatcherMode.OBSERVE_ONLY);

    RuntimeDispatchService.SmartRecoveryActionResult result =
        service.applySmartDrainUnlock(smartDrainUnlockInput("train-1", Set.of(), false));

    assertTrue(result.candidate());
    assertFalse(result.applied());
    assertTrue(
        debugMessages.stream()
            .anyMatch(message -> message.contains("SMART_DRAIN_UNLOCK_SUPPRESSED_BY_MODE")));
  }

  @Test
  void smartDrainUnlockDoesNotFakeDrainAuthority() {
    List<String> debugMessages = new ArrayList<>();
    RuntimeDispatchService service =
        createMinimalService(mockOccupancyManager(), debugMessages, SmartDispatcherMode.ENFORCE);

    RuntimeDispatchService.SmartRecoveryActionResult result =
        service.applySmartDrainUnlock(smartDrainUnlockInput("train-1", Set.of(), false));

    assertTrue(result.candidate());
    assertTrue(
        debugMessages.stream()
            .anyMatch(
                message ->
                    message.contains("SMART_DRAIN_UNLOCK_CANDIDATE")
                        && message.contains("drainThroughAuthorityCreated=false")
                        && message.contains("topologyExitHintPromoted=false")));
  }

  @Test
  void smartDrainUnlockBlockedByDifferentOwnerOppositeClaim() {
    List<String> debugMessages = new ArrayList<>();
    RuntimeDispatchService service =
        createMinimalService(mockOccupancyManager(), debugMessages, SmartDispatcherMode.ENFORCE);

    RuntimeDispatchService.SmartRecoveryActionResult result =
        service.applySmartDrainUnlock(smartDrainUnlockInput("train-1", Set.of("opposite"), true));

    assertFalse(result.candidate());
    assertFalse(result.applied());
    assertTrue(
        debugMessages.stream()
            .anyMatch(
                message ->
                    message.contains("SMART_DRAIN_UNLOCK_BLOCKED_BY_SINGLE_REGION_HARD_BARRIER")));
  }

  @Test
  void drainThroughAuthorityBlockedByOtherTrainNodeClaim() throws Exception {
    List<String> debugMessages = new ArrayList<>();
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    RuntimeDispatchService service = createMinimalService(manager, debugMessages);

    NodeId a = NodeId.of("A");
    NodeId b = NodeId.of("B");
    NodeId c = NodeId.of("C");
    RailEdge edgeAb = new RailEdge(EdgeId.undirected(a, b), a, b, 10, -1.0, true, Optional.empty());
    RailEdge edgeBc = new RailEdge(EdgeId.undirected(b, c), b, c, 10, -1.0, true, Optional.empty());
    String conflictKey = "single:test:A~B";
    OccupancyResource conflict = OccupancyResource.forConflict(conflictKey);
    OccupancyResource nodeB = OccupancyResource.forNode(b);
    OccupancyResource edgeAbResource = OccupancyResource.forEdge(edgeAb.id());
    OccupancyResource edgeBcResource = OccupancyResource.forEdge(edgeBc.id());

    manager.acquire(
        new OccupancyRequest(
            "drain",
            Optional.empty(),
            Instant.parse("2026-01-01T00:00:00Z"),
            List.of(conflict),
            Map.of(conflict.key(), CorridorDirection.A_TO_B),
            Map.of(conflict.key(), 0),
            0));
    manager.acquire(
        new OccupancyRequest(
            "blocker",
            Optional.empty(),
            Instant.parse("2026-01-01T00:00:01Z"),
            List.of(nodeB),
            Map.of(),
            0));

    OccupancyRequest request =
        new OccupancyRequest(
            "drain",
            Optional.empty(),
            Instant.parse("2026-01-01T00:00:02Z"),
            List.of(conflict, nodeB, edgeAbResource, edgeBcResource),
            Map.of(conflict.key(), CorridorDirection.A_TO_B),
            Map.of(conflict.key(), 0),
            0,
            AuthorizationPurpose.RUNTIME_MOVE);
    OccupancyRequestContext context =
        new OccupancyRequestContext(request, List.of(a, b, c), List.of(edgeAb, edgeBc));
    RailGraph graph = new ConflictExitGraph(a, b, c, edgeAb, edgeBc, conflictKey);

    java.lang.reflect.Method method =
        RuntimeDispatchService.class.getDeclaredMethod(
            "withRuntimeConflictClearingEvidence",
            OccupancyRequest.class,
            OccupancyRequestContext.class,
            RailGraph.class);
    method.setAccessible(true);
    OccupancyRequest result = (OccupancyRequest) method.invoke(service, request, context, graph);

    assertEquals(AuthorizationPurpose.RUNTIME_MOVE, result.purpose());
    assertTrue(result.conflictReleaseHints().isEmpty());
    assertTrue(
        debugMessages.stream()
            .anyMatch(
                message ->
                    message.contains("DRAIN_THROUGH_BLOCKED")
                        && message.contains("blockerResource=NODE:B")
                        && message.contains("blockerOwner=blocker")));
  }

  @Test
  void topologyExitHintDoesNotChangeRuntimeMovePurpose() throws Exception {
    List<String> debugMessages = new ArrayList<>();
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    RuntimeDispatchService service = createMinimalService(manager, debugMessages);

    NodeId a = NodeId.of("A");
    NodeId b = NodeId.of("B");
    NodeId c = NodeId.of("C");
    RailEdge edgeAb = new RailEdge(EdgeId.undirected(a, b), a, b, 10, -1.0, true, Optional.empty());
    RailEdge edgeBc = new RailEdge(EdgeId.undirected(b, c), b, c, 10, -1.0, true, Optional.empty());
    String conflictKey = "single:test:A~B";
    OccupancyResource conflict = OccupancyResource.forConflict(conflictKey);
    OccupancyResource nodeB = OccupancyResource.forNode(b);
    OccupancyResource edgeAbResource = OccupancyResource.forEdge(edgeAb.id());
    OccupancyResource edgeBcResource = OccupancyResource.forEdge(edgeBc.id());

    manager.acquire(
        new OccupancyRequest(
            "departing",
            Optional.empty(),
            Instant.parse("2026-01-01T00:00:00Z"),
            List.of(conflict),
            Map.of(conflict.key(), CorridorDirection.A_TO_B),
            Map.of(conflict.key(), 0),
            0));

    OccupancyRequest request =
        new OccupancyRequest(
                "departing",
                Optional.empty(),
                Instant.parse("2026-01-01T00:00:02Z"),
                List.of(conflict, nodeB, edgeAbResource, edgeBcResource),
                Map.of(conflict.key(), CorridorDirection.A_TO_B),
                Map.of(conflict.key(), 0),
                0,
                AuthorizationPurpose.RUNTIME_MOVE)
            .withDirectedContext(
                Optional.of(
                    new DirectedTraversalContext(
                        "departing",
                        Optional.empty(),
                        0,
                        Optional.of(a),
                        Optional.empty(),
                        Optional.of(a),
                        Optional.of(b),
                        List.of(a, b, c),
                        List.of(
                            new DirectedTraversalContext.DirectedEdge(edgeAb.id(), a, b),
                            new DirectedTraversalContext.DirectedEdge(edgeBc.id(), b, c)),
                        Map.of(conflict.key(), CorridorDirection.A_TO_B),
                        Map.of(),
                        "TEST",
                        -1L,
                        -1L,
                        "test",
                        Optional.empty())));
    OccupancyRequestContext context =
        new OccupancyRequestContext(request, List.of(a, b, c), List.of(edgeAb, edgeBc));
    RailGraph graph = new ConflictExitGraph(a, b, c, edgeAb, edgeBc, conflictKey);

    java.lang.reflect.Method method =
        RuntimeDispatchService.class.getDeclaredMethod(
            "withRuntimeConflictClearingEvidence",
            OccupancyRequest.class,
            OccupancyRequestContext.class,
            RailGraph.class);
    method.setAccessible(true);
    OccupancyRequest result = (OccupancyRequest) method.invoke(service, request, context, graph);

    assertEquals(AuthorizationPurpose.RUNTIME_MOVE, result.purpose());
    assertEquals(
        SignalDecisionInputType.FORWARD_MOVEMENT, SignalDecisionInputClassifier.classify(result));
    assertEquals(
        ConflictClearingEvidenceKind.TOPOLOGY_EXIT_HINT,
        result.conflictReleaseHints().get(conflictKey).kind());
    assertFalse(result.conflictReleaseHints().get(conflictKey).verifiedFor(conflictKey));
    OccupancyDecision decision = manager.canEnter(result);
    assertTrue(decision.allowed());
    assertFalse(decision.conflictRelease());
  }

  @Test
  void switcherOccupantClearingAuthorityLetsInsideTrainGoAndKeepsEntrantHeld() throws Exception {
    List<String> debugMessages = new ArrayList<>();
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    RuntimeDispatchService service = createMinimalService(manager, debugMessages);

    NodeId entry = NodeId.of("ENTRY");
    NodeId switcher = NodeId.of("SWITCHER:TEST:GENERAL");
    NodeId exit = NodeId.of("EXIT");
    RailEdge entryEdge =
        new RailEdge(
            EdgeId.undirected(entry, switcher), entry, switcher, 23, -1.0, true, Optional.empty());
    RailEdge exitEdge =
        new RailEdge(
            EdgeId.undirected(switcher, exit), switcher, exit, 33, -1.0, true, Optional.empty());
    OccupancyResource switcherConflict =
        OccupancyResource.forConflict("switcher:" + switcher.value());
    OccupancyResource switcherNode = OccupancyResource.forNode(switcher);
    OccupancyResource exitEdgeResource = OccupancyResource.forEdge(exitEdge.id());
    OccupancyResource exitNode = OccupancyResource.forNode(exit);

    OccupancyRequest entrantClaim =
        switcherTraversalRequest(
            "entrant",
            Instant.parse("2026-01-01T00:00:00Z"),
            5,
            List.of(entry, switcher, exit),
            List.of(entryEdge, exitEdge),
            List.of(switcherConflict));
    assertTrue(manager.acquire(entrantClaim).allowed());
    OccupancyRequest queuedOutsider =
        switcherTraversalRequest(
            "queued-outsider",
            Instant.parse("2026-01-01T00:00:00.500Z"),
            5,
            List.of(entry, switcher, exit),
            List.of(entryEdge, exitEdge),
            List.of(switcherConflict));
    manager.touchQueues(queuedOutsider);
    assertEquals(
        "queued-outsider",
        manager.snapshotQueues().stream()
            .filter(queue -> queue.resource().equals(switcherConflict))
            .findFirst()
            .orElseThrow()
            .entries()
            .get(0)
            .trainName());

    OccupancyRequest insideFootprint =
        new OccupancyRequest(
            "inside",
            Optional.empty(),
            Instant.parse("2026-01-01T00:00:01Z"),
            List.of(switcherNode, exitEdgeResource),
            Map.of(),
            Map.of(),
            0,
            AuthorizationPurpose.RUNTIME_MOVE,
            Map.of(),
            Map.of(
                switcherNode,
                ResourceIntent.HOLD_ONLY,
                exitEdgeResource,
                ResourceIntent.HOLD_ONLY));
    assertTrue(manager.acquire(insideFootprint).allowed());

    OccupancyRequest insideRequest =
        switcherTraversalRequest(
            "inside",
            Instant.parse("2026-01-01T00:00:02Z"),
            5,
            List.of(switcher, exit),
            List.of(exitEdge),
            List.of(switcherNode, exitEdgeResource, exitNode, switcherConflict));
    OccupancyDecision initiallyBlocked = manager.canEnter(insideRequest);
    assertFalse(initiallyBlocked.allowed());
    assertEquals(switcherConflict, initiallyBlocked.blockers().get(0).resource());

    OccupancyRequest clearingRequest =
        VerifiedSwitcherDrainClaims.prepare(
                insideRequest, switcherVerificationSnapshot(manager.snapshotClaims()))
            .request();

    assertEquals(AuthorizationPurpose.CONFLICT_CLEARING, clearingRequest.purpose());
    assertEquals(
        ConflictClearingEvidenceKind.VERIFIED_SWITCHER_OCCUPANT,
        clearingRequest.conflictReleaseHints().get(switcherConflict.key()).kind());
    OccupancyDecision clearingDecision = manager.canEnter(clearingRequest);
    assertTrue(clearingDecision.allowed(), clearingDecision.toString());
    assertTrue(clearingDecision.conflictRelease());
    SignalPublicationGate.Decision publication =
        invokePublicationGate(service, clearingRequest, clearingDecision);
    assertEquals(SignalDecisionInputType.DRAIN_THROUGH, publication.inputType());
    assertEquals(SignalAspect.PROCEED, publication.visibleAspect());
    assertTrue(manager.acquire(clearingRequest).allowed());

    OccupancyRequest entrantMovement =
        switcherTraversalRequest(
            "entrant",
            Instant.parse("2026-01-01T00:00:03Z"),
            5,
            List.of(entry, switcher, exit),
            List.of(entryEdge, exitEdge),
            List.of(switcherConflict, switcherNode, exitEdgeResource, exitNode));
    OccupancyDecision entrantDecision = manager.canEnter(entrantMovement);

    assertFalse(entrantDecision.allowed());
    assertTrue(
        entrantDecision.blockers().stream()
            .anyMatch(
                blocker ->
                    blocker.trainName().equals("inside")
                        && (blocker.resource().equals(switcherNode)
                            || blocker.resource().equals(exitEdgeResource))));
    assertEquals("entrant", manager.getClaim(switcherConflict).orElseThrow().trainName());
    assertEquals("inside", manager.getClaim(switcherNode).orElseThrow().trainName());
    assertEquals("inside", manager.getClaim(exitEdgeResource).orElseThrow().trainName());
  }

  @Test
  void switcherOccupantKeepsClearingAuthorityWhenEntrantClaimsAfterPlanSnapshot() throws Exception {
    List<String> debugMessages = new ArrayList<>();
    NodeId switcher = NodeId.of("SWITCHER:Towny:-581:65:643");
    NodeId exit = NodeId.of("SWITCHER:Towny:-583:65:650");
    RailEdge exitEdge =
        new RailEdge(
            EdgeId.undirected(switcher, exit), switcher, exit, 8, -1.0, true, Optional.empty());
    OccupancyResource switcherConflict =
        OccupancyResource.forConflict("switcher:" + switcher.value());
    OccupancyResource switcherNode = OccupancyResource.forNode(switcher);
    OccupancyResource exitEdgeResource = OccupancyResource.forEdge(exitEdge.id());
    OccupancyResource exitNode = OccupancyResource.forNode(exit);
    SimpleOccupancyManager manager = mock(SimpleOccupancyManager.class);
    when(manager.version()).thenReturn(1L);
    when(manager.snapshotClaims())
        .thenReturn(
            List.of(
                new OccupancyClaim(
                    switcherNode,
                    "departing",
                    Optional.empty(),
                    Instant.parse("2026-01-01T00:00:00Z"),
                    Duration.ZERO,
                    Optional.empty(),
                    ClaimRole.HOLD_ONLY),
                new OccupancyClaim(
                    exitEdgeResource,
                    "departing",
                    Optional.empty(),
                    Instant.parse("2026-01-01T00:00:00Z"),
                    Duration.ZERO,
                    Optional.empty(),
                    ClaimRole.HOLD_ONLY)));
    OccupancyAdvisoryPreviewSupport advisorySupport = (OccupancyAdvisoryPreviewSupport) manager;
    when(advisorySupport.scanAdvisoryRisks(any()))
        .thenReturn(
            List.of(
                new AdvisoryRisk(
                    switcherConflict,
                    new OccupancyClaim(
                        switcherConflict,
                        "entrant",
                        Optional.empty(),
                        Instant.parse("2026-01-01T00:00:02Z"),
                        Duration.ZERO,
                        Optional.empty(),
                        ClaimRole.MOVEMENT_REQUIRED),
                    AdvisoryRiskSource.ACTIVE_SWITCHER_CONFLICT,
                    "late-switcher-claim")));
    RuntimeDispatchService service = createMinimalService(manager, debugMessages);

    OccupancyRequest insideRequest =
        switcherTraversalRequest(
                "departing",
                Instant.parse("2026-01-01T00:00:01Z"),
                0,
                List.of(switcher, exit),
                List.of(exitEdge),
                List.of(switcherNode, exitEdgeResource, exitNode, switcherConflict))
            .withDirectedProgressVersion(0L);
    OccupancyRequest clearingRequest =
        VerifiedSwitcherDrainClaims.prepare(
                insideRequest,
                new VerifiedSwitcherDrainClaims.VerificationSnapshot(
                    manager.snapshotClaims(), 1L, 0L))
            .request();

    assertEquals(AuthorizationPurpose.CONFLICT_CLEARING, clearingRequest.purpose());
    assertEquals(
        ConflictClearingEvidenceKind.VERIFIED_SWITCHER_OCCUPANT,
        clearingRequest.conflictReleaseHints().get(switcherConflict.key()).kind());
    OccupancyDecision authorizationDecision =
        new OccupancyDecision(
            true,
            Instant.parse("2026-01-01T00:00:01Z"),
            SignalAspect.PROCEED,
            List.of(),
            false,
            "none");

    OccupancyDecision advisoryDecision =
        invokeAdvisoryPreviewDecision(
            service, insideRequest.asLookaheadPreview(), authorizationDecision, clearingRequest);
    assertEquals(SignalAspect.PROCEED, advisoryDecision.signal());
    assertTrue(
        advisoryDecision.blockers().stream()
            .noneMatch(blocker -> switcherConflict.equals(blocker.resource())));
    assertTrue(
        debugMessages.stream()
            .anyMatch(
                message ->
                    message.contains("DRAIN_THROUGH_ADVISORY_RISK_SUPPRESSED")
                        && message.contains("conflictKey=" + switcherConflict.key())));
  }

  @Test
  void periodicSignalTickLetsPpkTurnbackOccupantDrainThrough() {
    List<String> debugMessages = new ArrayList<>();
    Instant claimTime = Instant.parse("2026-01-01T00:00:00Z");
    String trainName = "SURC-MT-LH-6469";
    String entrantTrainName = "SURC-MT-LP-8712";
    NodeId entrantPlatform = NodeId.of("SURC:PPK:RVS:1:001");
    NodeId entrantSwitcher = NodeId.of("SWITCHER:Towny:-579:65:650");
    NodeId entry = NodeId.of("SWITCHER:Towny:-581:65:637");
    NodeId switcher = NodeId.of("SWITCHER:Towny:-581:65:643");
    NodeId exitSwitcher = NodeId.of("SWITCHER:Towny:-583:65:650");
    NodeId exit = NodeId.of("SURC:PPK:RVS:2:001");
    RailEdge entrantPlatformEdge =
        new RailEdge(
            EdgeId.undirected(entrantPlatform, entrantSwitcher),
            entrantPlatform,
            entrantSwitcher,
            21,
            -1.0,
            true,
            Optional.empty());
    RailEdge entrantSwitcherEdge =
        new RailEdge(
            EdgeId.undirected(entrantSwitcher, switcher),
            entrantSwitcher,
            switcher,
            7,
            -1.0,
            true,
            Optional.empty());
    RailEdge entryEdge =
        new RailEdge(
            EdgeId.undirected(entry, switcher), entry, switcher, 4, -1.0, true, Optional.empty());
    RailEdge switcherExitEdge =
        new RailEdge(
            EdgeId.undirected(switcher, exitSwitcher),
            switcher,
            exitSwitcher,
            7,
            -1.0,
            true,
            Optional.empty());
    RailEdge exitEdge =
        new RailEdge(
            EdgeId.undirected(exitSwitcher, exit),
            exitSwitcher,
            exit,
            21,
            -1.0,
            true,
            Optional.empty());
    RailGraph graph =
        new SimpleRailGraph(
            Map.of(
                entrantPlatform, new RailNodeTest(entrantPlatform),
                entrantSwitcher,
                    new RailNodeTest(entrantSwitcher, NodeType.SWITCHER, Optional.empty()),
                entry, new RailNodeTest(entry, NodeType.SWITCHER, Optional.empty()),
                switcher, new RailNodeTest(switcher, NodeType.SWITCHER, Optional.empty()),
                exitSwitcher, new RailNodeTest(exitSwitcher, NodeType.SWITCHER, Optional.empty()),
                exit, new RailNodeTest(exit)),
            Map.of(
                entrantPlatformEdge.id(), entrantPlatformEdge,
                entrantSwitcherEdge.id(), entrantSwitcherEdge,
                entryEdge.id(), entryEdge,
                switcherExitEdge.id(), switcherExitEdge,
                exitEdge.id(), exitEdge),
            Set.of());
    OccupancyResource switcherConflict =
        OccupancyResource.forConflict("switcher:" + switcher.value());
    OccupancyResource switcherNode = OccupancyResource.forNode(switcher);
    OccupancyResource exitEdgeResource = OccupancyResource.forEdge(switcherExitEdge.id());
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    OccupancyRequest entrantClaim =
        switcherTraversalRequest(
                entrantTrainName,
                claimTime,
                13,
                List.of(entrantPlatform, entrantSwitcher, switcher, entry),
                List.of(entrantPlatformEdge, entrantSwitcherEdge, entryEdge),
                List.of(switcherConflict))
            .withSchedulingMetadata(claimTime, 20);
    assertTrue(manager.acquire(entrantClaim).allowed());
    OccupancyRequest insideFootprint =
        new OccupancyRequest(
            trainName,
            Optional.empty(),
            claimTime.plusSeconds(1),
            List.of(switcherNode, exitEdgeResource),
            Map.of(),
            Map.of(),
            -10,
            AuthorizationPurpose.RUNTIME_MOVE,
            Map.of(),
            Map.of(
                switcherNode,
                ResourceIntent.HOLD_ONLY,
                exitEdgeResource,
                ResourceIntent.HOLD_ONLY));
    assertTrue(manager.acquire(insideFootprint).allowed());

    RouteDefinition route =
        new RouteDefinition(
            RouteId.of("SURC:MT:MT-2O_ShortD"), List.of(entry, exit), Optional.empty());
    TagStore tags =
        new TagStore(
            trainName,
            "FTA_OPERATOR_CODE=SURC",
            "FTA_LINE_CODE=MT",
            "FTA_ROUTE_CODE=MT-2O_ShortD",
            "FTA_ROUTE_INDEX=0",
            "FTA_PRIORITY=-10");
    UUID worldId = UUID.randomUUID();
    RouteProgressRegistry progressRegistry = new RouteProgressRegistry();
    progressRegistry.initFromTags(trainName, tags.properties(), route);
    progressRegistry.updateLastPassedGraphNode(trainName, switcher, claimTime.plusSeconds(2));
    progressRegistry.updateSignal(trainName, SignalAspect.STOP, claimTime.plusSeconds(2));

    ConfigManager configManager = mock(ConfigManager.class);
    when(configManager.current()).thenReturn(testConfigView(1, 20.0));
    RailGraphService railGraphService = mock(RailGraphService.class);
    when(railGraphService.getSnapshot(worldId))
        .thenReturn(Optional.of(new RailGraphService.RailGraphSnapshot(graph, Instant.now())));
    when(railGraphService.effectiveSpeedLimitBlocksPerSecond(any(), any(), any(), anyDouble()))
        .thenReturn(20.0);
    RouteDefinitionCache routeDefinitions = mock(RouteDefinitionCache.class);
    when(routeDefinitions.findByCodes("SURC", "MT", "MT-2O_ShortD")).thenReturn(Optional.of(route));
    RuntimeDispatchService service =
        new RuntimeDispatchService(
            manager,
            railGraphService,
            routeDefinitions,
            progressRegistry,
            mock(SignNodeRegistry.class),
            mock(LayoverRegistry.class),
            new DwellRegistry(),
            configManager,
            null,
            new TrainConfigResolver(),
            debugMessages::add);
    FakeTrain train = new FakeTrain(worldId, tags.properties(), false, 0.0);

    service.handleSignalTick(train, true);

    assertEquals(1, train.launchCalls, debugMessages.toString());
    assertEquals(
        SignalAspect.PROCEED,
        progressRegistry.get(trainName).orElseThrow().lastSignal(),
        debugMessages.toString());
    assertTrue(
        debugMessages.stream()
            .anyMatch(
                message ->
                    message.contains("SWITCHER_DRAIN_THROUGH_AUTHORITY")
                        && message.contains("train=" + trainName)),
        debugMessages.toString());
    assertTrue(
        debugMessages.stream()
            .anyMatch(
                message ->
                    message.contains("SMART_THROAT_DRAIN_CONFLICT_SUPPRESSED")
                        && message.contains("train=" + trainName)
                        && message.contains("conflictKey=" + switcherConflict.key())),
        debugMessages.toString());
    assertTrue(
        debugMessages.stream()
            .anyMatch(
                message ->
                    message.contains("DRAIN_THROUGH_ADVISORY_RISK_SUPPRESSED")
                        && message.contains("conflictKey=" + switcherConflict.key())),
        debugMessages.toString());
    assertEquals(entrantTrainName, manager.getClaim(switcherConflict).orElseThrow().trainName());
    assertEquals(trainName, manager.getClaim(switcherNode).orElseThrow().trainName());
    assertEquals(trainName, manager.getClaim(exitEdgeResource).orElseThrow().trainName());
    OccupancyDecision entrantDecision =
        manager.canEnter(
            switcherTraversalRequest(
                    entrantTrainName,
                    claimTime.plusSeconds(3),
                    13,
                    List.of(entrantPlatform, entrantSwitcher, switcher, entry),
                    List.of(entrantPlatformEdge, entrantSwitcherEdge, entryEdge),
                    List.of(switcherConflict, switcherNode, exitEdgeResource))
                .withSchedulingMetadata(claimTime.plusSeconds(3), 20));
    assertFalse(entrantDecision.allowed());
    assertTrue(
        entrantDecision.blockers().stream()
            .anyMatch(
                blocker ->
                    blocker.trainName().equals(trainName)
                        && (blocker.resource().equals(switcherNode)
                            || blocker.resource().equals(exitEdgeResource))));
  }

  @Test
  void periodicSignalTickLetsSpbCrossingOccupantDrainAtRouteIndexEight() {
    List<String> debugMessages = new ArrayList<>();
    Instant claimTime = Instant.parse("2026-01-01T00:00:00Z");
    String trainName = "SURC-MT-LP-0690";
    String entrantTrainName = "SURC-MT-LP-2983";
    NodeId entrant = NodeId.of("SURC:SPB:JBS:1:003");
    NodeId entrantSwitcher = NodeId.of("SWITCHER:Towny:-555:77:1196");
    NodeId mergeSwitcher = NodeId.of("SWITCHER:Towny:-557:77:1193");
    NodeId insideEntry = NodeId.of("SURC:SPB:WSD:2:001");
    NodeId switcher = NodeId.of("SWITCHER:Towny:-566:77:1179");
    NodeId exit = NodeId.of("SURC:SPB:JBS:1:002");
    RailEdge entrantEdge =
        new RailEdge(
            EdgeId.undirected(entrant, entrantSwitcher),
            entrant,
            entrantSwitcher,
            5,
            -1.0,
            true,
            Optional.empty());
    RailEdge entrantMergeEdge =
        new RailEdge(
            EdgeId.undirected(entrantSwitcher, mergeSwitcher),
            entrantSwitcher,
            mergeSwitcher,
            2,
            -1.0,
            true,
            Optional.empty());
    RailEdge mergeEdge =
        new RailEdge(
            EdgeId.undirected(mergeSwitcher, switcher),
            mergeSwitcher,
            switcher,
            16,
            -1.0,
            true,
            Optional.empty());
    RailEdge insideEntryEdge =
        new RailEdge(
            EdgeId.undirected(insideEntry, switcher),
            insideEntry,
            switcher,
            40,
            -1.0,
            true,
            Optional.empty());
    RailEdge exitEdge =
        new RailEdge(
            EdgeId.undirected(switcher, exit), switcher, exit, 33, -1.0, true, Optional.empty());
    RailGraph graph =
        new SimpleRailGraph(
            Map.of(
                entrant, new RailNodeTest(entrant),
                entrantSwitcher,
                    new RailNodeTest(entrantSwitcher, NodeType.SWITCHER, Optional.empty()),
                mergeSwitcher, new RailNodeTest(mergeSwitcher, NodeType.SWITCHER, Optional.empty()),
                insideEntry, new RailNodeTest(insideEntry),
                switcher, new RailNodeTest(switcher, NodeType.SWITCHER, Optional.empty()),
                exit, new RailNodeTest(exit)),
            Map.of(
                entrantEdge.id(), entrantEdge,
                entrantMergeEdge.id(), entrantMergeEdge,
                mergeEdge.id(), mergeEdge,
                insideEntryEdge.id(), insideEntryEdge,
                exitEdge.id(), exitEdge),
            Set.of());
    OccupancyResource switcherConflict =
        OccupancyResource.forConflict("switcher:" + switcher.value());
    OccupancyResource switcherNode = OccupancyResource.forNode(switcher);
    OccupancyResource exitEdgeResource = OccupancyResource.forEdge(exitEdge.id());
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    OccupancyRequest entrantClaim =
        switcherTraversalRequest(
            entrantTrainName,
            claimTime,
            8,
            List.of(entrant, entrantSwitcher, mergeSwitcher, switcher, exit),
            List.of(entrantEdge, entrantMergeEdge, mergeEdge, exitEdge),
            List.of(switcherConflict));
    assertTrue(manager.acquire(entrantClaim).allowed());
    OccupancyRequest insideFootprint =
        new OccupancyRequest(
            trainName,
            Optional.empty(),
            claimTime.plusSeconds(1),
            List.of(switcherNode, exitEdgeResource),
            Map.of(),
            Map.of(),
            0,
            AuthorizationPurpose.RUNTIME_MOVE,
            Map.of(),
            Map.of(
                switcherNode,
                ResourceIntent.HOLD_ONLY,
                exitEdgeResource,
                ResourceIntent.HOLD_ONLY));
    assertTrue(manager.acquire(insideFootprint).allowed());

    RouteDefinition route =
        new RouteDefinition(
            RouteId.of("SURC:MT:MT-2F_Short"),
            List.of(
                NodeId.of("SURC:TEST:0"),
                NodeId.of("SURC:TEST:1"),
                NodeId.of("SURC:TEST:2"),
                NodeId.of("SURC:TEST:3"),
                NodeId.of("SURC:TEST:4"),
                NodeId.of("SURC:TEST:5"),
                NodeId.of("SURC:TEST:6"),
                NodeId.of("SURC:TEST:7"),
                insideEntry,
                exit),
            Optional.empty());
    TagStore tags =
        new TagStore(
            trainName,
            "FTA_OPERATOR_CODE=op",
            "FTA_LINE_CODE=l1",
            "FTA_ROUTE_CODE=r1",
            "FTA_ROUTE_INDEX=8");
    UUID worldId = UUID.randomUUID();
    RouteProgressRegistry progressRegistry = new RouteProgressRegistry();
    progressRegistry.initFromTags(trainName, tags.properties(), route);
    progressRegistry.updateLastPassedGraphNode(trainName, switcher, claimTime.plusSeconds(2));
    progressRegistry.updateSignal(trainName, SignalAspect.STOP, claimTime.plusSeconds(2));

    ConfigManager configManager = mock(ConfigManager.class);
    when(configManager.current()).thenReturn(testConfigView(1, 20.0));
    RailGraphService railGraphService = mock(RailGraphService.class);
    when(railGraphService.getSnapshot(worldId))
        .thenReturn(Optional.of(new RailGraphService.RailGraphSnapshot(graph, Instant.now())));
    when(railGraphService.effectiveSpeedLimitBlocksPerSecond(any(), any(), any(), anyDouble()))
        .thenReturn(20.0);
    RouteDefinitionCache routeDefinitions = mock(RouteDefinitionCache.class);
    when(routeDefinitions.findByCodes("op", "l1", "r1")).thenReturn(Optional.of(route));
    RuntimeDispatchService service =
        new RuntimeDispatchService(
            manager,
            railGraphService,
            routeDefinitions,
            progressRegistry,
            mock(SignNodeRegistry.class),
            mock(LayoverRegistry.class),
            new DwellRegistry(),
            configManager,
            null,
            new TrainConfigResolver(),
            debugMessages::add);
    FakeTrain train = new FakeTrain(worldId, tags.properties(), false, 0.0);

    service.handleSignalTick(train, true);

    assertEquals(1, train.launchCalls, debugMessages.toString());
    assertEquals(
        SignalAspect.PROCEED,
        progressRegistry.get(trainName).orElseThrow().lastSignal(),
        debugMessages.toString());
    assertTrue(
        debugMessages.stream()
            .anyMatch(
                message ->
                    message.contains("SWITCHER_DRAIN_THROUGH_AUTHORITY")
                        && message.contains("train=" + trainName)),
        debugMessages.toString());
    assertTrue(
        debugMessages.stream()
            .anyMatch(
                message ->
                    message.contains("DRAIN_THROUGH_ADVISORY_RISK_SUPPRESSED")
                        && message.contains("conflictKey=" + switcherConflict.key())),
        debugMessages.toString());
    assertEquals(entrantTrainName, manager.getClaim(switcherConflict).orElseThrow().trainName());
    assertEquals(trainName, manager.getClaim(switcherNode).orElseThrow().trainName());
    assertEquals(trainName, manager.getClaim(exitEdgeResource).orElseThrow().trainName());
    OccupancyDecision entrantDecision =
        manager.canEnter(
            switcherTraversalRequest(
                entrantTrainName,
                claimTime.plusSeconds(3),
                8,
                List.of(entrant, entrantSwitcher, mergeSwitcher, switcher, exit),
                List.of(entrantEdge, entrantMergeEdge, mergeEdge, exitEdge),
                List.of(
                    switcherConflict,
                    switcherNode,
                    exitEdgeResource,
                    OccupancyResource.forNode(exit))));
    assertFalse(entrantDecision.allowed());
    assertTrue(
        entrantDecision.blockers().stream()
            .anyMatch(
                blocker ->
                    blocker.trainName().equals(trainName)
                        && (blocker.resource().equals(switcherNode)
                            || blocker.resource().equals(exitEdgeResource))));
  }

  @Test
  void drainThroughAdvisoryFilterKeepsOtherSwitcherRisk() throws Exception {
    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    NodeId releasedSwitcher = NodeId.of("SWITCHER:Towny:-566:77:1179");
    NodeId otherSwitcher = NodeId.of("SWITCHER:Towny:-557:77:1193");
    NodeId exit = NodeId.of("SURC:SPB:JBS:1:002");
    RailEdge releasedExit =
        new RailEdge(
            EdgeId.undirected(releasedSwitcher, exit),
            releasedSwitcher,
            exit,
            33,
            -1.0,
            true,
            Optional.empty());
    OccupancyResource releasedConflict =
        OccupancyResource.forConflict("switcher:" + releasedSwitcher.value());
    OccupancyResource otherConflict =
        OccupancyResource.forConflict("switcher:" + otherSwitcher.value());
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    assertTrue(
        manager
            .acquire(
                switcherTraversalRequest(
                    "released-owner",
                    now,
                    0,
                    List.of(releasedSwitcher, exit),
                    List.of(releasedExit),
                    List.of(releasedConflict)))
            .allowed());
    assertTrue(
        manager
            .acquire(
                switcherTraversalRequest(
                    "other-owner",
                    now.plusMillis(1),
                    0,
                    List.of(otherSwitcher, exit),
                    List.of(
                        new RailEdge(
                            EdgeId.undirected(otherSwitcher, exit),
                            otherSwitcher,
                            exit,
                            20,
                            -1.0,
                            true,
                            Optional.empty())),
                    List.of(otherConflict)))
            .allowed());
    OccupancyResource releasedNode = OccupancyResource.forNode(releasedSwitcher);
    OccupancyResource releasedExitEdge = OccupancyResource.forEdge(releasedExit.id());
    assertTrue(
        manager
            .acquire(
                new OccupancyRequest(
                    "inside",
                    Optional.empty(),
                    now.plusMillis(2),
                    List.of(releasedNode, releasedExitEdge),
                    Map.of(),
                    Map.of(),
                    0,
                    AuthorizationPurpose.RUNTIME_MOVE,
                    Map.of(),
                    Map.of(
                        releasedNode,
                        ResourceIntent.HOLD_ONLY,
                        releasedExitEdge,
                        ResourceIntent.HOLD_ONLY)))
            .allowed());
    RuntimeDispatchService service = createMinimalService(manager, new ArrayList<>());
    OccupancyRequest authorizationRequest =
        switcherTraversalRequest(
                "inside",
                now.plusSeconds(1),
                0,
                List.of(releasedSwitcher, exit),
                List.of(releasedExit),
                List.of(releasedConflict, releasedNode, releasedExitEdge))
            .withDirectedOccupancyVersion(manager.version())
            .withDirectedProgressVersion(0L)
            .withConflictReleaseHints(
                AuthorizationPurpose.CONFLICT_CLEARING,
                Map.of(
                    releasedConflict.key(),
                    org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.ConflictReleaseHint
                        .verifiedSwitcherOccupant(releasedConflict.key(), "test")));
    OccupancyDecision authorizationDecision =
        new OccupancyDecision(
            true,
            authorizationRequest.now(),
            SignalAspect.PROCEED,
            List.of(manager.getClaim(releasedConflict).orElseThrow()),
            true);
    OccupancyRequest advisoryRequest =
        new OccupancyRequest(
                "inside",
                Optional.empty(),
                now.plusSeconds(1),
                List.of(releasedConflict, otherConflict),
                Map.of())
            .asLookaheadPreview();

    OccupancyDecision advisoryDecision =
        invokeAdvisoryPreviewDecision(
            service, advisoryRequest, authorizationDecision, authorizationRequest);

    assertEquals(SignalAspect.PROCEED_WITH_CAUTION, advisoryDecision.signal());
    assertFalse(
        advisoryDecision.blockers().stream()
            .anyMatch(blocker -> releasedConflict.equals(blocker.resource())));
    assertTrue(
        advisoryDecision.blockers().stream()
            .anyMatch(
                blocker ->
                    otherConflict.equals(blocker.resource())
                        && blocker.trainName().equals("other-owner")));
  }

  @Test
  void approachingTrainCannotClaimSwitcherDrainAuthority() {
    NodeId entry = NodeId.of("ENTRY");
    NodeId switcher = NodeId.of("SWITCHER:TEST:APPROACH");
    NodeId exit = NodeId.of("EXIT");
    RailEdge entryEdge =
        new RailEdge(
            EdgeId.undirected(entry, switcher), entry, switcher, 23, -1.0, true, Optional.empty());
    RailEdge exitEdge =
        new RailEdge(
            EdgeId.undirected(switcher, exit), switcher, exit, 33, -1.0, true, Optional.empty());
    OccupancyResource conflict = OccupancyResource.forConflict("switcher:" + switcher.value());
    OccupancyResource entryNode = OccupancyResource.forNode(entry);
    OccupancyManager manager = mockOccupancyManager();
    when(manager.snapshotClaims())
        .thenReturn(
            List.of(
                new OccupancyClaim(
                    conflict,
                    "incumbent",
                    Optional.empty(),
                    Instant.EPOCH,
                    Duration.ZERO,
                    Optional.empty(),
                    ClaimRole.MOVEMENT_REQUIRED),
                new OccupancyClaim(
                    entryNode,
                    "approaching",
                    Optional.empty(),
                    Instant.EPOCH,
                    Duration.ZERO,
                    Optional.empty(),
                    ClaimRole.HOLD_ONLY)));
    OccupancyRequest request =
        switcherTraversalRequest(
            "approaching",
            Instant.parse("2026-01-01T00:00:02Z"),
            8,
            List.of(entry, switcher, exit),
            List.of(entryEdge, exitEdge),
            List.of(entryNode, conflict));
    OccupancyRequest result =
        VerifiedSwitcherDrainClaims.prepare(
                request, switcherVerificationSnapshot(manager.snapshotClaims()))
            .request();

    assertEquals(AuthorizationPurpose.RUNTIME_MOVE, result.purpose());
    assertTrue(result.conflictReleaseHints().isEmpty());
  }

  @Test
  void switcherOccupantCannotBypassExternalExitNode() {
    NodeId switcher = NodeId.of("SWITCHER:TEST:BLOCKED");
    NodeId exit = NodeId.of("EXIT");
    RailEdge exitEdge =
        new RailEdge(
            EdgeId.undirected(switcher, exit), switcher, exit, 33, -1.0, true, Optional.empty());
    OccupancyResource conflict = OccupancyResource.forConflict("switcher:" + switcher.value());
    OccupancyResource switcherNode = OccupancyResource.forNode(switcher);
    OccupancyResource exitNode = OccupancyResource.forNode(exit);
    OccupancyManager manager = mockOccupancyManager();
    when(manager.snapshotClaims())
        .thenReturn(
            List.of(
                new OccupancyClaim(
                    conflict,
                    "entrant",
                    Optional.empty(),
                    Instant.EPOCH,
                    Duration.ZERO,
                    Optional.empty(),
                    ClaimRole.MOVEMENT_REQUIRED),
                new OccupancyClaim(
                    switcherNode,
                    "inside",
                    Optional.empty(),
                    Instant.EPOCH,
                    Duration.ZERO,
                    Optional.empty(),
                    ClaimRole.HOLD_ONLY),
                new OccupancyClaim(
                    exitNode,
                    "external",
                    Optional.empty(),
                    Instant.EPOCH,
                    Duration.ZERO,
                    Optional.empty(),
                    ClaimRole.MOVEMENT_REQUIRED)));
    OccupancyRequest request =
        switcherTraversalRequest(
            "inside",
            Instant.parse("2026-01-01T00:00:02Z"),
            8,
            List.of(switcher, exit),
            List.of(exitEdge),
            List.of(switcherNode, OccupancyResource.forEdge(exitEdge.id()), exitNode, conflict));
    VerifiedSwitcherDrainClaims.Preparation preparation =
        VerifiedSwitcherDrainClaims.prepare(
            request, switcherVerificationSnapshot(manager.snapshotClaims()));
    OccupancyRequest result = preparation.request();

    assertEquals(AuthorizationPurpose.RUNTIME_MOVE, result.purpose());
    assertTrue(result.conflictReleaseHints().isEmpty());
    assertEquals(exitNode, preparation.hardBlocker().orElseThrow().resource());
    assertEquals("external", preparation.hardBlocker().orElseThrow().trainName());
  }

  @Test
  void hardStopToProceedRequiresAcquireCommit() throws Exception {
    RuntimeDispatchService service = createMinimalService();
    NodeId a = NodeId.of("A");
    NodeId b = NodeId.of("B");
    OccupancyResource resource = OccupancyResource.forNode(b);
    OccupancyRequest request = completeTwoNodeAuthorityRequest("train-1", Instant.now(), a, b);

    java.lang.reflect.Method issue =
        RuntimeDispatchService.class.getDeclaredMethod(
            "issueMovementAuthorizationToken",
            String.class,
            NodeId.class,
            NodeId.class,
            OccupancyRequest.class,
            SignalAspect.class,
            Instant.class);
    java.lang.reflect.Method activate =
        RuntimeDispatchService.class.getDeclaredMethod(
            "activateMovementAuthorizationTokenRetainingInhibitor",
            String.class,
            MovementAuthorizationToken.class,
            String.class);
    java.lang.reflect.Method hasValid =
        RuntimeDispatchService.class.getDeclaredMethod(
            "hasValidMovementAuthorization", String.class, NodeId.class, NodeId.class, List.class);
    issue.setAccessible(true);
    activate.setAccessible(true);
    hasValid.setAccessible(true);

    MovementAuthorizationToken pending =
        (MovementAuthorizationToken)
            issue.invoke(service, "train-1", a, b, request, SignalAspect.PROCEED, Instant.now());

    assertFalse((boolean) hasValid.invoke(service, "train-1", a, b, List.of(resource)));
    assertTrue((boolean) activate.invoke(service, "train-1", pending, "B"));
    assertTrue((boolean) hasValid.invoke(service, "train-1", a, b, List.of(resource)));
  }

  @Test
  void acquireEventDoesNotStopSameTrainReentrantly() throws Exception {
    RuntimeDispatchService service = createMinimalService();
    NodeId a = NodeId.of("A");
    NodeId b = NodeId.of("B");
    OccupancyResource resource = OccupancyResource.forNode(b);
    OccupancyRequest request = completeTwoNodeAuthorityRequest("train-1", Instant.now(), a, b);

    java.lang.reflect.Method issue =
        RuntimeDispatchService.class.getDeclaredMethod(
            "issueMovementAuthorizationToken",
            String.class,
            NodeId.class,
            NodeId.class,
            OccupancyRequest.class,
            SignalAspect.class,
            Instant.class);
    java.lang.reflect.Method activate =
        RuntimeDispatchService.class.getDeclaredMethod(
            "activateMovementAuthorizationTokenRetainingInhibitor",
            String.class,
            MovementAuthorizationToken.class,
            String.class);
    java.lang.reflect.Method hasValid =
        RuntimeDispatchService.class.getDeclaredMethod(
            "hasValidMovementAuthorization", String.class, NodeId.class, NodeId.class, List.class);
    issue.setAccessible(true);
    activate.setAccessible(true);
    hasValid.setAccessible(true);

    MovementAuthorizationToken token =
        (MovementAuthorizationToken)
            issue.invoke(service, "train-1", a, b, request, SignalAspect.PROCEED, Instant.now());
    assertTrue((boolean) activate.invoke(service, "train-1", token, "B"));

    service.applySignalFromEvent("train-1", SignalAspect.STOP);

    RuntimeDispatchService.SignalRuntimeStats stats = service.signalRuntimeStats();
    assertEquals(1, stats.dirtyTrainCount());
    assertEquals(1, stats.coalescedEventCount());
    assertTrue((boolean) hasValid.invoke(service, "train-1", a, b, List.of(resource)));
  }

  @Test
  void stopProceedPingPongRegression() {
    RuntimeDispatchService service = createMinimalService();

    service.applySignalFromEvent("train-1", SignalAspect.STOP);
    service.applySignalFromEvent("train-1", SignalAspect.PROCEED);

    RuntimeDispatchService.SignalRuntimeStats stats = service.signalRuntimeStats();
    assertEquals(1, stats.dirtyTrainCount());
    assertEquals(2, stats.coalescedEventCount());
    assertEquals(0, stats.reentrantStopSuppressed());
  }

  @Test
  void directSignalUpdateCannotPublishProceedWithoutFinalDecision() throws Exception {
    List<String> debugMessages = new ArrayList<>();
    RouteProgressRegistry registry = signalRegistryForTrain("train-1", SignalAspect.STOP);
    RuntimeDispatchService service =
        createMinimalService(
            mockOccupancyManager(), mock(RouteDefinitionCache.class), registry, debugMessages);

    assertFalse(invokeSignalUpdate(service, "train-1", SignalAspect.PROCEED));

    assertEquals(SignalAspect.STOP, registry.get("train-1").orElseThrow().lastSignal());
    assertTrue(
        debugMessages.stream()
            .anyMatch(
                message ->
                    message.contains("DIRECT_SIGNAL_UPDATE_SUPPRESSED")
                        && message.contains("final-authorization-missing")
                        && message.contains("didMutate=false")));
    assertTrue(
        debugMessages.stream()
            .anyMatch(
                message ->
                    message.contains("event=SIGNAL_FINAL_DECISION")
                        && message.contains("physicalPublished=false")
                        && message.contains("didMutate=false")));
  }

  @Test
  void duplicateDirectStopDoesNotRepublishOrInvalidateProgressSnapshot() throws Exception {
    List<String> debugMessages = new ArrayList<>();
    RouteProgressRegistry registry = signalRegistryForTrain("train-1", SignalAspect.STOP);
    RuntimeDispatchService service =
        createMinimalService(
            mockOccupancyManager(), mock(RouteDefinitionCache.class), registry, debugMessages);
    long versionBefore = registry.version();

    assertFalse(invokeSignalUpdate(service, "train-1", SignalAspect.STOP));

    assertEquals(versionBefore, registry.version());
    assertEquals(SignalAspect.STOP, registry.get("train-1").orElseThrow().lastSignal());
    assertTrue(
        debugMessages.stream()
            .noneMatch(
                message ->
                    message.contains("SMART_SIGNAL_FINAL")
                        && message.contains("physicalPublished=true")));
  }

  @Test
  void runtimeCacheCleanupDropsPublishedPhysicalSignalForReusedTrainName() throws Exception {
    RouteProgressRegistry registry = signalRegistryForTrain("train-1", SignalAspect.STOP);
    RuntimeDispatchService service =
        createMinimalService(
            mockOccupancyManager(), mock(RouteDefinitionCache.class), registry, new ArrayList<>());
    installPublishedPhysicalSignal(service, "train-1", SignalAspect.PROCEED);

    assertEquals(SignalAspect.PROCEED, currentPhysicalAspect(service, "train-1"));

    invokeClearRuntimeCachesForTrain(service, "train-1");

    assertEquals(SignalAspect.STOP, currentPhysicalAspect(service, "train-1"));
  }

  @Test
  void directSignalUpdateCannotPublishProceedAcrossHardBarrier() throws Exception {
    List<String> debugMessages = new ArrayList<>();
    RouteProgressRegistry registry = signalRegistryForTrain("train-1", SignalAspect.STOP);
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    RuntimeDispatchService service =
        createMinimalService(manager, mock(RouteDefinitionCache.class), registry, debugMessages);
    OccupancyResource conflict = OccupancyResource.forConflict("single:test:A~B");
    assertTrue(
        manager
            .acquire(singleConflictRequest("external", conflict, CorridorDirection.A_TO_B))
            .allowed());
    OccupancyRequest request = singleConflictRequest("train-1", conflict, CorridorDirection.B_TO_A);
    OccupancyDecision decision =
        new OccupancyDecision(true, Instant.now(), SignalAspect.PROCEED, List.of());
    SignalPublicationGate.Decision publication =
        new SignalPublicationGate.Decision(
            SignalAspect.PROCEED,
            SignalAspect.PROCEED,
            SignalDecisionInputType.FORWARD_MOVEMENT,
            false,
            false,
            "allowed");
    Object authorization =
        finalSignalAuthorization(
            request, decision, publication, NodeId.of("A"), NodeId.of("B"), true);

    assertFalse(invokeSignalUpdate(service, "train-1", SignalAspect.PROCEED, authorization));

    assertEquals(SignalAspect.STOP, registry.get("train-1").orElseThrow().lastSignal());
    assertTrue(hasHardBarrierLog(debugMessages));
    assertTrue(
        debugMessages.stream()
            .anyMatch(
                message ->
                    message.contains("DIRECT_SIGNAL_UPDATE_SUPPRESSED")
                        && message.contains("single-region-hard-barrier")
                        && message.contains("didMutate=false")));
  }

  @Test
  void externalOccupancyHoldBlocksNarrowAuthoritySignalUntilResourceIsFree() throws Exception {
    List<String> debugMessages = new ArrayList<>();
    RouteProgressRegistry registry = signalRegistryForTrain("train-1", SignalAspect.STOP);
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    RuntimeDispatchService service =
        createMinimalService(manager, mock(RouteDefinitionCache.class), registry, debugMessages);
    OccupancyResource externalConflict =
        OccupancyResource.forConflict("single:test:external-ahead");
    assertTrue(
        manager
            .acquire(
                singleConflictRequest("external-train", externalConflict, CorridorDirection.A_TO_B))
            .allowed());

    NodeId currentNode = NodeId.of("A");
    NodeId nextNode = NodeId.of("B");
    OccupancyRequest authorityRequest =
        completeTwoNodeAuthorityRequest("train-1", Instant.now(), currentNode, nextNode);
    assertTrue(manager.acquire(authorityRequest).allowed());
    authorityRequest =
        authorityRequest
            .withDirectedOccupancyVersion(manager.version())
            .withDirectedProgressVersion(registry.version());
    issueAndActivateMovementToken(service, "train-1", currentNode, nextNode, authorityRequest, "B");

    OccupancyClaim externalClaim = manager.getClaim(externalConflict).orElseThrow();
    OccupancyDecision blocked =
        new OccupancyDecision(
            false,
            Instant.now(),
            SignalAspect.STOP,
            List.of(externalClaim),
            false,
            "external-single-conflict");
    java.lang.reflect.Method recordStopState =
        RuntimeDispatchService.class.getDeclaredMethod("recordStopState", RuntimeStopState.class);
    recordStopState.setAccessible(true);
    recordStopState.invoke(
        service,
        RuntimeStopState.occupancyHold("train-1", "BLOCKED_BY_OCCUPANCY", blocked, Instant.now()));

    OccupancyDecision allowed =
        new OccupancyDecision(true, Instant.now(), SignalAspect.PROCEED, List.of());
    SignalPublicationGate.Decision publication =
        new SignalPublicationGate.Decision(
            SignalAspect.PROCEED,
            SignalAspect.PROCEED,
            SignalDecisionInputType.FORWARD_MOVEMENT,
            false,
            false,
            "allowed");
    Object authorization =
        finalSignalAuthorization(
            authorityRequest, allowed, publication, currentNode, nextNode, true);

    assertFalse(invokeSignalUpdate(service, "train-1", SignalAspect.PROCEED, authorization));
    assertEquals(SignalAspect.STOP, registry.get("train-1").orElseThrow().lastSignal());
    assertEquals(
        "BLOCKED_BY_OCCUPANCY", service.getActiveStopState("train-1").orElseThrow().reasonCode());

    manager.releaseByTrain("external-train");
    assertTrue(
        manager
            .acquire(
                singleConflictRequest(
                    "replacement-train", externalConflict, CorridorDirection.B_TO_A))
            .allowed());
    OccupancyRequest transferredAuthorityRequest =
        authorityRequest
            .withDirectedOccupancyVersion(manager.version())
            .withDirectedProgressVersion(registry.version());
    Object transferredAuthorization =
        finalSignalAuthorization(
            transferredAuthorityRequest, allowed, publication, currentNode, nextNode, true);

    assertFalse(
        invokeSignalUpdate(service, "train-1", SignalAspect.PROCEED, transferredAuthorization));
    assertEquals(SignalAspect.STOP, registry.get("train-1").orElseThrow().lastSignal());

    manager.releaseByTrain("replacement-train");
    OccupancyRequest releasedAuthorityRequest =
        authorityRequest
            .withDirectedOccupancyVersion(manager.version())
            .withDirectedProgressVersion(registry.version());
    Object releasedAuthorization =
        finalSignalAuthorization(
            releasedAuthorityRequest, allowed, publication, currentNode, nextNode, true);

    assertTrue(invokeSignalUpdate(service, "train-1", SignalAspect.PROCEED, releasedAuthorization));
    assertEquals(SignalAspect.PROCEED, registry.get("train-1").orElseThrow().lastSignal());
    assertTrue(service.getActiveStopState("train-1").isEmpty());
  }

  @Test
  void staleSignalDecisionFailsSafeStop() throws Exception {
    List<String> debugMessages = new ArrayList<>();
    RouteProgressRegistry registry = signalRegistryForTrain("train-1", SignalAspect.STOP);
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    RuntimeDispatchService service =
        createMinimalService(manager, mock(RouteDefinitionCache.class), registry, debugMessages);
    OccupancyResource resource = OccupancyResource.forNode(NodeId.of("B"));
    OccupancyRequest request =
        staleProgressRequest(
            "train-1", RouteId.of("signal-test"), 0, registry.version(), List.of(resource));
    OccupancyDecision decision =
        new OccupancyDecision(true, Instant.now(), SignalAspect.PROCEED, List.of());
    SignalPublicationGate.Decision publication =
        new SignalPublicationGate.Decision(
            SignalAspect.PROCEED,
            SignalAspect.PROCEED,
            SignalDecisionInputType.FORWARD_MOVEMENT,
            false,
            false,
            "allowed");
    Object authorization =
        finalSignalAuthorization(
            request,
            decision,
            publication,
            NodeId.of("A"),
            NodeId.of("B"),
            SignalComputationTrace.TokenState.NONE,
            true);

    assertFalse(invokeSignalUpdate(service, "train-1", SignalAspect.PROCEED, authorization));

    assertEquals(SignalAspect.STOP, registry.get("train-1").orElseThrow().lastSignal());
    assertTrue(
        debugMessages.stream()
            .anyMatch(
                message ->
                    message.contains("DIRECT_SIGNAL_UPDATE_SUPPRESSED")
                        && message.contains("stale-final-snapshot")
                        && message.contains("snapshotStale=true")));
  }

  @Test
  void signalProceedRequiresActiveToken() throws Exception {
    List<String> debugMessages = new ArrayList<>();
    RouteProgressRegistry registry = signalRegistryForTrain("train-1", SignalAspect.STOP);
    RuntimeDispatchService service =
        createMinimalService(
            mockOccupancyManager(), mock(RouteDefinitionCache.class), registry, debugMessages);
    OccupancyResource resource = OccupancyResource.forNode(NodeId.of("B"));
    OccupancyRequest request =
        staleProgressRequest(
            "train-1", RouteId.of("signal-test"), 0, registry.version(), List.of(resource));
    OccupancyDecision decision =
        new OccupancyDecision(true, Instant.now(), SignalAspect.PROCEED, List.of());
    SignalPublicationGate.Decision publication =
        new SignalPublicationGate.Decision(
            SignalAspect.PROCEED,
            SignalAspect.PROCEED,
            SignalDecisionInputType.FORWARD_MOVEMENT,
            false,
            false,
            "allowed");
    Object authorization =
        finalSignalAuthorization(
            request,
            decision,
            publication,
            NodeId.of("A"),
            NodeId.of("B"),
            SignalComputationTrace.TokenState.NONE,
            true);

    assertFalse(invokeSignalUpdate(service, "train-1", SignalAspect.PROCEED, authorization));

    assertEquals(SignalAspect.STOP, registry.get("train-1").orElseThrow().lastSignal());
    assertTrue(
        debugMessages.stream()
            .anyMatch(
                message ->
                    message.contains("DIRECT_SIGNAL_UPDATE_SUPPRESSED")
                        && message.contains("movement-token-not-active")));
  }

  @Test
  void signalProceedRequiresPhysicalAuthorityBoundary() throws Exception {
    List<String> debugMessages = new ArrayList<>();
    RouteProgressRegistry registry = signalRegistryForTrain("train-1", SignalAspect.STOP);
    RuntimeDispatchService service =
        createMinimalService(
            mockOccupancyManager(), mock(RouteDefinitionCache.class), registry, debugMessages);
    OccupancyResource resource = OccupancyResource.forNode(NodeId.of("B"));
    OccupancyRequest request =
        staleProgressRequest(
            "train-1", RouteId.of("signal-test"), 0, registry.version(), List.of(resource));
    installMovementToken(
        service,
        new MovementAuthorizationToken(
                "train-1",
                1L,
                Instant.now(),
                NodeId.of("A"),
                NodeId.of("B"),
                List.of(resource),
                SignalAspect.PROCEED)
            .activate("B"));
    OccupancyDecision decision =
        new OccupancyDecision(true, Instant.now(), SignalAspect.PROCEED, List.of());
    SignalPublicationGate.Decision publication =
        new SignalPublicationGate.Decision(
            SignalAspect.PROCEED,
            SignalAspect.PROCEED,
            SignalDecisionInputType.FORWARD_MOVEMENT,
            false,
            false,
            "allowed");
    Object authorization =
        finalSignalAuthorization(
            request, decision, publication, NodeId.of("A"), NodeId.of("B"), true);

    assertFalse(invokeSignalUpdate(service, "train-1", SignalAspect.PROCEED, authorization));

    assertEquals(SignalAspect.STOP, registry.get("train-1").orElseThrow().lastSignal());
    assertTrue(
        debugMessages.stream()
            .anyMatch(
                message ->
                    message.contains("DIRECT_SIGNAL_UPDATE_SUPPRESSED")
                        && message.contains("movement-token-authority-boundary-missing")),
        debugMessages.toString());
  }

  @Test
  void signalProceedRequiresLiveHardAuthorityOwnership() throws Exception {
    List<String> debugMessages = new ArrayList<>();
    RouteProgressRegistry registry = signalRegistryForTrain("train-1", SignalAspect.STOP);
    OccupancyManager manager =
        mock(OccupancyManager.class, withSettings().extraInterfaces(AuthorityHandoffSupport.class));
    when(((AuthorityHandoffSupport) manager).holdsHardAuthority(any())).thenReturn(false);
    RuntimeDispatchService service =
        createMinimalService(manager, mock(RouteDefinitionCache.class), registry, debugMessages);
    OccupancyResource resource = OccupancyResource.forNode(NodeId.of("B"));
    OccupancyRequest request =
        staleProgressRequest(
            "train-1", RouteId.of("signal-test"), 0, registry.version(), List.of(resource));
    installMovementToken(
        service,
        new MovementAuthorizationToken(
                "train-1",
                1L,
                Instant.now(),
                NodeId.of("A"),
                NodeId.of("B"),
                Optional.of(NodeId.of("B")),
                1,
                List.of(resource),
                SignalAspect.PROCEED)
            .activate("B"));
    OccupancyDecision decision =
        new OccupancyDecision(true, Instant.now(), SignalAspect.PROCEED, List.of());
    SignalPublicationGate.Decision publication =
        new SignalPublicationGate.Decision(
            SignalAspect.PROCEED,
            SignalAspect.PROCEED,
            SignalDecisionInputType.FORWARD_MOVEMENT,
            false,
            false,
            "allowed");
    Object authorization =
        finalSignalAuthorization(
            request, decision, publication, NodeId.of("A"), NodeId.of("B"), true);

    assertFalse(invokeSignalUpdate(service, "train-1", SignalAspect.PROCEED, authorization));

    assertEquals(SignalAspect.STOP, registry.get("train-1").orElseThrow().lastSignal());
    assertTrue(
        debugMessages.stream()
            .anyMatch(
                message ->
                    message.contains("DIRECT_SIGNAL_UPDATE_SUPPRESSED")
                        && message.contains("hard-authority-not-held")),
        debugMessages.toString());
  }

  @Test
  void alreadyPublishedProceedDowngradesWhenHardAuthorityIsLost() throws Exception {
    List<String> debugMessages = new ArrayList<>();
    RouteProgressRegistry registry = signalRegistryForTrain("train-1", SignalAspect.PROCEED);
    OccupancyManager manager =
        mock(OccupancyManager.class, withSettings().extraInterfaces(AuthorityHandoffSupport.class));
    when(((AuthorityHandoffSupport) manager).holdsHardAuthority(any())).thenReturn(false);
    RuntimeDispatchService service =
        createMinimalService(manager, mock(RouteDefinitionCache.class), registry, debugMessages);
    OccupancyResource resource = OccupancyResource.forNode(NodeId.of("B"));
    OccupancyRequest request =
        staleProgressRequest(
            "train-1", RouteId.of("signal-test"), 0, registry.version(), List.of(resource));
    installMovementToken(
        service,
        new MovementAuthorizationToken(
                "train-1",
                1L,
                Instant.now(),
                NodeId.of("A"),
                NodeId.of("B"),
                List.of(resource),
                SignalAspect.PROCEED)
            .activate("B"));
    installPublishedPhysicalSignal(service, "train-1", SignalAspect.PROCEED);
    OccupancyDecision decision =
        new OccupancyDecision(true, Instant.now(), SignalAspect.PROCEED, List.of());
    SignalPublicationGate.Decision publication =
        new SignalPublicationGate.Decision(
            SignalAspect.PROCEED,
            SignalAspect.PROCEED,
            SignalDecisionInputType.FORWARD_MOVEMENT,
            false,
            false,
            "allowed");
    Object authorization =
        finalSignalAuthorization(
            request, decision, publication, NodeId.of("A"), NodeId.of("B"), true);

    invokePhysicalSignalPublication(
        service, "train-1", SignalAspect.PROCEED, SignalAspect.PROCEED, authorization);

    assertEquals(SignalAspect.STOP, registry.get("train-1").orElseThrow().lastSignal());
  }

  @Test
  void directCautionUpdateCannotBypassFinalAuthorization() throws Exception {
    RouteProgressRegistry registry = new RouteProgressRegistry();
    TagStore tags =
        new TagStore(
            "train-1",
            "FTA_OPERATOR_CODE=op",
            "FTA_LINE_CODE=l1",
            "FTA_ROUTE_CODE=r1",
            "FTA_ROUTE_INDEX=0");
    RouteDefinition route =
        new RouteDefinition(
            RouteId.of("r"), List.of(NodeId.of("A"), NodeId.of("B")), Optional.empty());
    registry.initFromTags("train-1", tags.properties(), route);
    registry.updateSignal("train-1", SignalAspect.PROCEED, Instant.now());
    RuntimeDispatchService service =
        createMinimalService(
            mockOccupancyManager(), mock(RouteDefinitionCache.class), registry, new ArrayList<>());

    assertFalse(invokeSignalUpdate(service, "train-1", SignalAspect.PROCEED_WITH_CAUTION));

    assertEquals(SignalAspect.PROCEED, registry.get("train-1").orElseThrow().lastSignal());
  }

  @Test
  void directPureCautionUpdateCannotBypassFinalAuthorization() throws Exception {
    List<String> debugMessages = new ArrayList<>();
    RouteProgressRegistry registry = signalRegistryForTrain("train-1", SignalAspect.STOP);
    RuntimeDispatchService service =
        createMinimalService(
            mockOccupancyManager(), mock(RouteDefinitionCache.class), registry, debugMessages);

    assertFalse(invokeSignalUpdate(service, "train-1", SignalAspect.CAUTION));

    assertEquals(SignalAspect.STOP, registry.get("train-1").orElseThrow().lastSignal());
    assertTrue(
        debugMessages.stream()
            .anyMatch(
                message ->
                    message.contains("DIRECT_SIGNAL_UPDATE_SUPPRESSED")
                        && message.contains("final-authorization-missing")),
        debugMessages.toString());
  }

  @Test
  void eventCoalescingLimitsEnvelopeBuildsPerTick() {
    RuntimeDispatchService service = createMinimalService();

    service.applySignalFromEvent("train-1", SignalAspect.STOP);
    service.applySignalFromEvent("train-2", SignalAspect.CAUTION);

    RuntimeDispatchService.SignalRuntimeStats stats = service.signalRuntimeStats();
    assertEquals(2, stats.dirtyTrainCount());
    assertEquals(2, stats.coalescedEventCount());
    assertEquals(0, stats.envelopeBuildCount());
  }

  @Test
  void signalEnvelopeLimitsFollowingTrainSpeed() {
    ConstraintPoint point =
        new ConstraintPoint(
            ConstraintType.FOLLOWING_TRAIN,
            org.fetarute
                .fetaruteTCAddon
                .dispatcher
                .schedule
                .occupancy
                .BlockerRelation
                .SAME_DIRECTION_FRONT,
            OptionalLong.of(12L),
            OptionalDouble.of(4.0),
            Optional.of("front"),
            Optional.of("EDGE:M1~M2"));

    SignalConstraintEnvelope envelope =
        new SignalConstraintEnvelope(
            SignalAspect.CAUTION,
            StopControlMode.BRAKING_TO_PLANNED_STOP,
            List.of(point),
            OptionalLong.empty(),
            Optional.empty(),
            OptionalDouble.of(4.0),
            OptionalDouble.of(3.5),
            false,
            false,
            "following-train");

    assertEquals(SignalAspect.CAUTION, envelope.aspect());
    assertEquals(4.0, envelope.targetSpeedBps().orElseThrow());
    assertEquals(ConstraintType.FOLLOWING_TRAIN, envelope.constraints().get(0).type());
  }

  @Test
  void authorityEndStillLimitsProceed() {
    SignalConstraintEnvelope envelope =
        new SignalConstraintEnvelope(
            SignalAspect.PROCEED,
            StopControlMode.BRAKING_TO_PLANNED_STOP,
            List.of(
                new ConstraintPoint(
                    ConstraintType.AUTHORITY_END,
                    org.fetarute
                        .fetaruteTCAddon
                        .dispatcher
                        .schedule
                        .occupancy
                        .BlockerRelation
                        .HARD_OCCUPANCY,
                    OptionalLong.of(18L),
                    OptionalDouble.empty(),
                    Optional.empty(),
                    Optional.of("NODE:D"))),
            OptionalLong.of(18L),
            Optional.of("NODE:D"),
            OptionalDouble.of(8.0),
            OptionalDouble.of(6.0),
            true,
            true,
            "authority-end");

    assertEquals(SignalAspect.PROCEED, envelope.aspect());
    assertEquals("NODE:D", envelope.authorityEndResource().orElseThrow());
    assertTrue(envelope.requireFreshAcquire());
  }

  @Test
  void signalEnvelopeUsesCacheOnRepeatedTick() {
    RuntimeDispatchService service = createMinimalService();

    service.applySignalFromEvent("train-1", SignalAspect.CAUTION);
    service.applySignalFromEvent("train-1", SignalAspect.CAUTION);

    RuntimeDispatchService.SignalRuntimeStats stats = service.signalRuntimeStats();
    assertEquals(1, stats.dirtyTrainCount());
    assertEquals(2, stats.coalescedEventCount());
    assertEquals(0, stats.envelopeBuildCount());
  }

  // ====== 异常列车清理去重测试 ======

  @Test
  void shouldSkipDuplicateAbnormalCleanupCaseInsensitive() throws Exception {
    RuntimeDispatchService service = createMinimalService();
    java.lang.reflect.Method method =
        RuntimeDispatchService.class.getDeclaredMethod(
            "shouldSkipDuplicateAbnormalCleanup", String.class);
    method.setAccessible(true);

    boolean first = (boolean) method.invoke(service, "Train-1");
    boolean duplicateWithDifferentCase = (boolean) method.invoke(service, "TRAIN-1");

    assertFalse(first);
    assertTrue(duplicateWithDifferentCase);
  }

  @Test
  void shouldSkipDuplicateAbnormalCleanupReturnsFalseForBlank() throws Exception {
    RuntimeDispatchService service = createMinimalService();
    java.lang.reflect.Method method =
        RuntimeDispatchService.class.getDeclaredMethod(
            "shouldSkipDuplicateAbnormalCleanup", String.class);
    method.setAccessible(true);

    // normalizeTrainKey("") returns "" and the method should return false
    assertFalse((boolean) method.invoke(service, ""));
  }

  // ====== handleTrainRemoved 完整清理验证 ======

  @Test
  void handleTrainRemovedClearsAllCaches() {
    String trainName = "train-1";
    RouteDefinition route =
        new RouteDefinition(
            RouteId.of("r"), List.of(NodeId.of("A"), NodeId.of("B")), Optional.empty());
    TagStore tags = new TagStore(trainName, "FTA_ROUTE_INDEX=0");

    ConfigManager configManager = mock(ConfigManager.class);
    when(configManager.current()).thenReturn(testConfigView(20, 20.0));

    OccupancyManager occupancyManager = mockOccupancyManager();
    LayoverRegistry layoverRegistry = mock(LayoverRegistry.class);
    RouteProgressRegistry registry = new RouteProgressRegistry();
    registry.initFromTags(trainName, tags.properties(), route);

    RuntimeDispatchService service =
        new RuntimeDispatchService(
            occupancyManager,
            mock(RailGraphService.class),
            mock(RouteDefinitionCache.class),
            registry,
            mock(SignNodeRegistry.class),
            layoverRegistry,
            new DwellRegistry(),
            configManager,
            null,
            new TrainConfigResolver(),
            null);

    service.handleTrainRemoved(trainName);

    // 验证所有清理操作
    verify(occupancyManager).releaseByTrain(trainName);
    verify(layoverRegistry).unregister(trainName);
    assertTrue(registry.get(trainName).isEmpty(), "进度应已清理");
  }

  @Test
  void handleTrainRemovedIgnoresBlankName() {
    RuntimeDispatchService service = createMinimalService();
    // 不应抛异常
    service.handleTrainRemoved("");
    service.handleTrainRemoved((String) null);
  }

  // ====== 孤儿清理返回统计结果测试 ======

  @Test
  void cleanupOrphanOccupancyClaimsWithReportReturnsStatistics() throws Exception {
    String orphanTrain = "orphan-train";
    RouteDefinition route =
        new RouteDefinition(
            RouteId.of("r"), List.of(NodeId.of("A"), NodeId.of("B")), Optional.empty());
    TagStore tags = new TagStore(orphanTrain, "FTA_ROUTE_INDEX=0");

    ConfigManager configManager = mock(ConfigManager.class);
    when(configManager.current()).thenReturn(testConfigView(20, 20.0));

    OccupancyManager occupancyManager = mockOccupancyManager();
    Instant now = Instant.now();
    when(occupancyManager.snapshotClaims())
        .thenReturn(
            List.of(
                new OccupancyClaim(
                    OccupancyResource.forNode(NodeId.of("A")),
                    orphanTrain,
                    Optional.empty(),
                    now,
                    Duration.ZERO,
                    Optional.empty())));

    RouteProgressRegistry registry = new RouteProgressRegistry();
    registry.initFromTags(orphanTrain, tags.properties(), route);

    LayoverRegistry layoverRegistry = mock(LayoverRegistry.class);
    when(layoverRegistry.snapshot()).thenReturn(List.of());

    RuntimeDispatchService service =
        new RuntimeDispatchService(
            occupancyManager,
            mock(RailGraphService.class),
            mock(RouteDefinitionCache.class),
            registry,
            mock(SignNodeRegistry.class),
            layoverRegistry,
            new DwellRegistry(),
            configManager,
            null,
            new TrainConfigResolver(),
            null);
    service.acquireDepartureGate(orphanTrain, "session-1", "test");
    backdateBlockerSnapshot(service, orphanTrain, Instant.now());

    // 活跃列车集不含 orphanTrain
    RuntimeDispatchService.CleanupResult result =
        service.cleanupOrphanOccupancyClaimsWithReport(java.util.Set.of("active-train"));

    assertEquals(1, result.removedProgress(), "应清理 1 个进度条目");
    assertEquals(1, result.releasedTrains(), "应释放 1 个列车占用");
    assertTrue(registry.get(orphanTrain).isEmpty());
    assertFalse(service.hasDepartureGate(orphanTrain), "destroyall 兜底应清理 departure gate");
    assertTrue(
        service.recentBlockerTrains(orphanTrain, Duration.ofSeconds(30)).isEmpty(),
        "destroyall 兜底应清理 blocker snapshot");
    verify(occupancyManager).releaseByTrain(orphanTrain);
  }

  @Test
  void cleanupOrphanOccupancyClaimsIsCaseInsensitive() {
    ConfigManager configManager = mock(ConfigManager.class);
    when(configManager.current()).thenReturn(testConfigView(20, 20.0));

    OccupancyManager occupancyManager = mockOccupancyManager();
    Instant now = Instant.now();
    when(occupancyManager.snapshotClaims())
        .thenReturn(
            List.of(
                new OccupancyClaim(
                    OccupancyResource.forNode(NodeId.of("A")),
                    "Train-A",
                    Optional.empty(),
                    now,
                    Duration.ZERO,
                    Optional.empty())));

    LayoverRegistry layoverRegistry = mock(LayoverRegistry.class);
    when(layoverRegistry.snapshot()).thenReturn(List.of());

    RuntimeDispatchService service =
        new RuntimeDispatchService(
            occupancyManager,
            mock(RailGraphService.class),
            mock(RouteDefinitionCache.class),
            new RouteProgressRegistry(),
            mock(SignNodeRegistry.class),
            layoverRegistry,
            new DwellRegistry(),
            configManager,
            null,
            new TrainConfigResolver(),
            null);

    // 大小写不同但应匹配
    RuntimeDispatchService.CleanupResult result =
        service.cleanupOrphanOccupancyClaimsWithReport(java.util.Set.of("TRAIN-A"));

    assertEquals(0, result.releasedTrains(), "大小写不同但匹配，不应释放");
  }

  @Test
  void cleanupOrphanOccupancyClaimsKeepsCanonicalStateForActiveSplitAlias() {
    String canonicalName = "train-main";
    RouteDefinition route =
        new RouteDefinition(
            RouteId.of("r"), List.of(NodeId.of("A"), NodeId.of("B")), Optional.empty());
    TagStore tags = new TagStore(canonicalName, "FTA_ROUTE_INDEX=0");
    RouteProgressRegistry registry = new RouteProgressRegistry();
    registry.initFromTags(canonicalName, tags.properties(), route);

    ConfigManager configManager = mock(ConfigManager.class);
    when(configManager.current()).thenReturn(testConfigView(20, 20.0));
    OccupancyManager occupancyManager = mockOccupancyManager();
    when(occupancyManager.snapshotClaims())
        .thenReturn(
            List.of(
                new OccupancyClaim(
                    OccupancyResource.forNode(NodeId.of("A")),
                    canonicalName,
                    Optional.empty(),
                    Instant.now(),
                    Duration.ZERO,
                    Optional.empty())));
    LayoverRegistry layoverRegistry = mock(LayoverRegistry.class);
    when(layoverRegistry.snapshot()).thenReturn(List.of());
    RuntimeDispatchService service =
        new RuntimeDispatchService(
            occupancyManager,
            mock(RailGraphService.class),
            mock(RouteDefinitionCache.class),
            registry,
            mock(SignNodeRegistry.class),
            layoverRegistry,
            new DwellRegistry(),
            configManager,
            null,
            new TrainConfigResolver(),
            null);
    service.acquireDepartureGate(canonicalName, "session-1", "test");

    RuntimeDispatchService.CleanupResult result =
        service.cleanupOrphanOccupancyClaimsWithReport(Set.of("train-main~a"));

    assertEquals(0, result.removedProgress());
    assertEquals(0, result.releasedTrains());
    assertTrue(registry.get(canonicalName).isPresent());
    assertTrue(service.hasDepartureGate(canonicalName));
    verify(occupancyManager, never()).releaseByTrain(anyString());
  }

  @Test
  void relatedLogicalTrainMatchingUsesTrackedNameAndSplitAlias() {
    RuntimeDispatchService service = createMinimalService();
    TagStore taggedSplit =
        new TagStore(
            "train-main~a",
            "FTA_OPERATOR_CODE=op",
            "FTA_LINE_CODE=l1",
            "FTA_ROUTE_CODE=r1",
            "FTA_TRAIN_NAME=train-main");
    TagStore rawSplitAlias = new TagStore("train-main~b");
    TagStore unrelated =
        new TagStore(
            "train-other",
            "FTA_OPERATOR_CODE=op",
            "FTA_LINE_CODE=l1",
            "FTA_ROUTE_CODE=r2",
            "FTA_TRAIN_NAME=train-other");

    assertTrue(service.isRelatedLogicalTrain(taggedSplit.properties(), "train-main"));
    assertTrue(service.isRelatedLogicalTrain(rawSplitAlias.properties(), "train-main"));
    assertFalse(service.isRelatedLogicalTrain(unrelated.properties(), "train-main"));
  }

  // ====== resource wake-up 测试 ======

  @Test
  void requestSignalReevaluationForResourcesSkipsEmptyInput() {
    RuntimeDispatchService service = createMinimalService();
    // 不应抛异常
    service.requestSignalReevaluationForResources(List.of(), "source");
    service.requestSignalReevaluationForResources(null, "source");
  }

  // ====== Smart unlock reservation observation 测试 ======

  @Test
  void canonicalMovementPlansAllowNodeEdgeCycleToReachMinimalForwardReservation() throws Exception {
    NodeEdgePlannerFixture fixture = nodeEdgePlannerFixture(Instant.now());

    fixture.service().traceSmartDispatchGlobalSnapshot(fixture.activeTrains(), fixture.now());

    assertTrue(
        fixture.debugMessages().stream()
            .anyMatch(message -> message.contains("SMART_DISPATCH_PLAN_SELECTED")),
        fixture.debugMessages().toString());
    assertTrue(
        fixture.debugMessages().stream()
            .anyMatch(message -> message.contains("SMART_UNLOCK_RESERVATION_CREATED")),
        fixture.debugMessages().toString());
    assertTrue(
        fixture.debugMessages().stream()
            .noneMatch(message -> message.contains("reason=INSUFFICIENT_DIRECTION_EVIDENCE")),
        fixture.debugMessages().toString());
  }

  @Test
  void staleCanonicalMovementPlansKeepNodeEdgeCycleFailClosed() throws Exception {
    NodeEdgePlannerFixture fixture = nodeEdgePlannerFixture(Instant.now());
    Instant blockerRefreshAt = fixture.now().plusMillis(1_500L);
    updateBlockerSnapshotWithoutRequest(
        fixture.service(), fixture.trainA(), fixture.decisionA(), blockerRefreshAt);
    updateBlockerSnapshotWithoutRequest(
        fixture.service(), fixture.trainB(), fixture.decisionB(), blockerRefreshAt);

    fixture
        .service()
        .traceSmartDispatchGlobalSnapshot(fixture.activeTrains(), blockerRefreshAt.plusMillis(1L));

    assertTrue(
        fixture.debugMessages().stream()
            .noneMatch(message -> message.contains("SMART_DISPATCH_PLAN_SELECTED")),
        fixture.debugMessages().toString());
    assertTrue(
        fixture.debugMessages().stream()
            .anyMatch(message -> message.contains("reason=MOVEMENT_PLAN_STALE")),
        fixture.debugMessages().toString());
    assertTrue(
        fixture.debugMessages().stream()
            .noneMatch(message -> message.contains("SMART_UNLOCK_RESERVATION_CREATED")),
        fixture.debugMessages().toString());
  }

  @Test
  void blockerCanonicalPlansWithInitiallyEmptyLastPassedExpireWhenLastPassedAppears()
      throws Exception {
    Instant now = Instant.now();
    NodeEdgePlannerFixture fixture = nodeEdgePlannerFixture(now);
    NodeId a0 = NodeId.of("SURC:S:RVS:1");
    NodeId a1 = NodeId.of("SURC:RVS:LYM:2:001");
    NodeId b0 = NodeId.of("SURC:S:LYM:1");
    NodeId b1 = NodeId.of("SURC:LYM:RVS:2:001");
    replaceRouteProgressLastPassed(
        fixture.progressRegistry(), fixture.trainA(), Optional.empty(), now.minusMillis(1L));
    replaceRouteProgressLastPassed(
        fixture.progressRegistry(), fixture.trainB(), Optional.empty(), now.minusMillis(1L));
    updateBlockerSnapshot(
        fixture.service(),
        fixture.trainA(),
        fixture.decisionA(),
        completeTwoNodeAuthorityRequest(
            fixture.trainA(), now, a0, a1, Optional.of(RouteId.of("planner-node-edge-a"))),
        now,
        "test-empty-last-passed");
    updateBlockerSnapshot(
        fixture.service(),
        fixture.trainB(),
        fixture.decisionB(),
        completeTwoNodeAuthorityRequest(
            fixture.trainB(), now, b0, b1, Optional.of(RouteId.of("planner-node-edge-b"))),
        now,
        "test-empty-last-passed");
    assertTrue(
        fixture
            .progressRegistry()
            .updateLastPassedGraphNode(fixture.trainA(), a0, now.plusMillis(1L)));
    assertTrue(
        fixture
            .progressRegistry()
            .updateLastPassedGraphNode(fixture.trainB(), b0, now.plusMillis(1L)));

    fixture.service().traceSmartDispatchGlobalSnapshot(fixture.activeTrains(), now.plusMillis(1L));

    assertTrue(
        fixture.debugMessages().stream()
            .noneMatch(message -> message.contains("SMART_DISPATCH_PLAN_SELECTED")),
        fixture.debugMessages().toString());
    assertTrue(
        fixture.debugMessages().stream()
            .anyMatch(message -> message.contains("reason=PLAN_PROGRESS_WINDOW_MOVED")),
        fixture.debugMessages().toString());
    assertTrue(
        fixture.debugMessages().stream()
            .noneMatch(message -> message.contains("SMART_UNLOCK_RESERVATION_CREATED")),
        fixture.debugMessages().toString());
  }

  @Test
  void routeLessCanonicalMovementPlansKeepNodeEdgeCycleFailClosed() throws Exception {
    NodeEdgePlannerFixture fixture = nodeEdgePlannerFixture(Instant.now(), false);

    fixture.service().traceSmartDispatchGlobalSnapshot(fixture.activeTrains(), fixture.now());

    assertTrue(
        fixture.debugMessages().stream()
            .noneMatch(message -> message.contains("SMART_DISPATCH_PLAN_SELECTED")),
        fixture.debugMessages().toString());
    assertTrue(
        fixture.debugMessages().stream()
            .anyMatch(message -> message.contains("reason=PLAN_ROUTE_ID_MISSING")),
        fixture.debugMessages().toString());
    assertTrue(
        fixture.debugMessages().stream()
            .noneMatch(message -> message.contains("SMART_UNLOCK_RESERVATION_CREATED")),
        fixture.debugMessages().toString());
  }

  @Test
  void latestCanonicalSignalPlanAllowsBottleneckLeaderWithoutOwnBlockerSnapshot() throws Exception {
    Instant now = Instant.now();
    NodeEdgePlannerFixture fixture = nodeEdgePlannerFixture(now);
    String followerC = "SURC-MT-LP-9643";
    NodeId leaderFrom = NodeId.of("SURC:S:RVS:1");
    NodeId leaderTo = NodeId.of("SURC:RVS:LYM:2:001");
    OccupancyDecision clear =
        new OccupancyDecision(false, now, SignalAspect.STOP, List.of(), false, "test-clear");
    OccupancyDecision blockedByLeader =
        blockedBy(OccupancyResource.forNode(leaderTo), fixture.trainA(), now);
    updateBlockerSnapshotWithoutRequest(fixture.service(), fixture.trainA(), clear, now);
    updateBlockerSnapshotWithoutRequest(fixture.service(), fixture.trainB(), blockedByLeader, now);
    updateBlockerSnapshotWithoutRequest(fixture.service(), followerC, blockedByLeader, now);
    replaceRouteProgressLastPassed(
        fixture.progressRegistry(), fixture.trainA(), Optional.empty(), now.minusMillis(1L));
    invokeMarkDirectedRequest(
        fixture.service(),
        completeTwoNodeAuthorityRequest(
            fixture.trainA(),
            now,
            leaderFrom,
            leaderTo,
            Optional.of(RouteId.of("planner-node-edge-a"))),
        SignalComputationTrace.Source.PERIODIC_TICK);

    fixture
        .service()
        .traceSmartDispatchGlobalSnapshot(
            Set.of(fixture.trainA(), fixture.trainB(), followerC), now);

    assertTrue(
        fixture.debugMessages().stream()
            .anyMatch(
                message ->
                    message.contains("SMART_DISPATCH_PLAN_SELECTED train=" + fixture.trainA())
                        && message.contains("cycleId=bottleneck:")),
        fixture.debugMessages().toString());
    assertTrue(
        fixture.debugMessages().stream()
            .anyMatch(message -> message.contains("SMART_UNLOCK_RESERVATION_CREATED")),
        fixture.debugMessages().toString());
  }

  @Test
  void canonicalSignalPlanAllows5108RearSwitcherProtectiveRetainToDrainForward() throws Exception {
    NodeId depot = NodeId.of("SURC:D:HHU:3");
    NodeId rearSwitcher = NodeId.of("SWITCHER:Towny:502:74:996");
    NodeId currentAnchor = NodeId.of("SURC:S:HHU:1");
    NodeId nextNode = NodeId.of("SURC:ZKW:HHU:1:006");
    RearProtectionPlannerFixture fixture =
        rearProtectionPlannerFixture(
            "SURC-MT-LP-5108",
            RouteId.of("planner-log-5108"),
            List.of(depot, currentAnchor, nextNode),
            List.of(depot, currentAnchor, nextNode),
            List.of(depot, rearSwitcher, currentAnchor, nextNode),
            1,
            currentAnchor,
            OccupancyResource.forNode(rearSwitcher),
            ClaimRole.PROTECTIVE_RETAIN);

    clearInvocations(fixture.manager());
    fixture.service().traceSmartDispatchGlobalSnapshot(fixture.activeTrains(), fixture.now());
    verify(fixture.manager(), times(1)).snapshotClaims();

    assertTrue(
        fixture.debugMessages().stream()
            .anyMatch(
                message -> message.contains("SMART_DISPATCH_PLAN_SELECTED train=SURC-MT-LP-5108")),
        fixture.debugMessages().toString());
    assertTrue(
        fixture.debugMessages().stream()
            .anyMatch(
                message ->
                    message.contains("SMART_DISPATCH_CANDIDATE_DECISION")
                        && message.contains("blockerResource=NODE:SWITCHER:Towny:502:74:996")
                        && message.contains(
                            "directionEvidenceKind=CANONICAL_MOVEMENT_PLAN_WITH_REAR_RETAIN")
                        && message.contains("rejected=false")),
        fixture.debugMessages().toString());
    assertTrue(
        fixture.debugMessages().stream()
            .anyMatch(message -> message.contains("SMART_UNLOCK_RESERVATION_CREATED")),
        fixture.debugMessages().toString());
    assertTrue(
        fixture.manager().snapshotClaims().stream()
            .anyMatch(
                claim ->
                    claim.resource().equals(OccupancyResource.forNode(rearSwitcher))
                        && claim.role() == ClaimRole.PROTECTIVE_RETAIN),
        "planner 只能请求 leader 重新评估，不能直接释放 rear claim");
  }

  @Test
  void canonicalSignalPlanAllows7327RearStationProtectiveRetainToDrainForward() throws Exception {
    NodeId rearStation = NodeId.of("SURC:S:RVS:1");
    NodeId rearThroat = NodeId.of("SURC:PPK:RVS:1:002");
    NodeId currentAnchor = NodeId.of("SURC:PPK:RVS:1:001");
    NodeId nextNode = NodeId.of("SWITCHER:Towny:593:68:1140");
    RearProtectionPlannerFixture fixture =
        rearProtectionPlannerFixture(
            "SURC-MT-LP-7327",
            RouteId.of("planner-log-7327"),
            List.of(rearStation, nextNode),
            List.of(currentAnchor, nextNode),
            List.of(rearStation, rearThroat, currentAnchor, nextNode),
            0,
            currentAnchor,
            OccupancyResource.forNode(rearStation),
            ClaimRole.PROTECTIVE_RETAIN);

    fixture.service().traceSmartDispatchGlobalSnapshot(fixture.activeTrains(), fixture.now());

    assertTrue(
        fixture.debugMessages().stream()
            .anyMatch(
                message -> message.contains("SMART_DISPATCH_PLAN_SELECTED train=SURC-MT-LP-7327")),
        fixture.debugMessages().toString());
    assertTrue(
        fixture.debugMessages().stream()
            .anyMatch(
                message ->
                    message.contains("SMART_DISPATCH_CANDIDATE_DECISION")
                        && message.contains("blockerResource=NODE:SURC:S:RVS:1")
                        && message.contains(
                            "directionEvidenceKind=CANONICAL_MOVEMENT_PLAN_WITH_REAR_RETAIN")
                        && message.contains("rejected=false")),
        fixture.debugMessages().toString());
    assertTrue(
        fixture.debugMessages().stream()
            .anyMatch(message -> message.contains("SMART_UNLOCK_RESERVATION_CREATED")),
        fixture.debugMessages().toString());
    assertTrue(
        fixture.manager().snapshotClaims().stream()
            .anyMatch(
                claim ->
                    claim.resource().equals(OccupancyResource.forNode(rearStation))
                        && claim.role() == ClaimRole.PROTECTIVE_RETAIN),
        "planner 只能请求 leader 重新评估，不能直接释放 rear claim");
  }

  @Test
  void canonicalSignalPlanRejectsRearPathResourceWithoutProtectiveRetainRole() throws Exception {
    NodeId depot = NodeId.of("SURC:D:HHU:3");
    NodeId rearSwitcher = NodeId.of("SWITCHER:Towny:502:74:996");
    NodeId currentAnchor = NodeId.of("SURC:S:HHU:1");
    NodeId nextNode = NodeId.of("SURC:ZKW:HHU:1:006");
    RearProtectionPlannerFixture fixture =
        rearProtectionPlannerFixture(
            "SURC-MT-LP-5108",
            RouteId.of("planner-log-5108"),
            List.of(depot, currentAnchor, nextNode),
            List.of(depot, currentAnchor, nextNode),
            List.of(depot, rearSwitcher, currentAnchor, nextNode),
            1,
            currentAnchor,
            OccupancyResource.forNode(rearSwitcher),
            ClaimRole.MOVEMENT_REQUIRED);

    fixture.service().traceSmartDispatchGlobalSnapshot(fixture.activeTrains(), fixture.now());

    assertTrue(
        fixture.debugMessages().stream()
            .noneMatch(message -> message.contains("SMART_DISPATCH_PLAN_SELECTED")),
        fixture.debugMessages().toString());
    assertTrue(
        fixture.debugMessages().stream()
            .anyMatch(message -> message.contains("reason=INSUFFICIENT_DIRECTION_EVIDENCE")),
        fixture.debugMessages().toString());
    assertTrue(
        fixture.debugMessages().stream()
            .noneMatch(message -> message.contains("SMART_UNLOCK_RESERVATION_CREATED")),
        fixture.debugMessages().toString());
  }

  @Test
  void canonicalSignalPlanRejectsRearProtectiveRetainFromAnotherRoute() throws Exception {
    NodeId rearStation = NodeId.of("SURC:S:RVS:1");
    NodeId rearThroat = NodeId.of("SURC:PPK:RVS:1:002");
    NodeId currentAnchor = NodeId.of("SURC:PPK:RVS:1:001");
    NodeId nextNode = NodeId.of("SWITCHER:Towny:593:68:1140");
    RearProtectionPlannerFixture fixture =
        rearProtectionPlannerFixture(
            "SURC-MT-LP-7327",
            RouteId.of("planner-log-7327"),
            List.of(rearStation, nextNode),
            List.of(currentAnchor, nextNode),
            List.of(rearStation, rearThroat, currentAnchor, nextNode),
            0,
            currentAnchor,
            OccupancyResource.forNode(rearStation),
            ClaimRole.PROTECTIVE_RETAIN,
            RouteId.of("another-route"));

    fixture.service().traceSmartDispatchGlobalSnapshot(fixture.activeTrains(), fixture.now());

    assertTrue(
        fixture.debugMessages().stream()
            .noneMatch(message -> message.contains("SMART_DISPATCH_PLAN_SELECTED")),
        fixture.debugMessages().toString());
    assertTrue(
        fixture.debugMessages().stream()
            .anyMatch(message -> message.contains("reason=INSUFFICIENT_DIRECTION_EVIDENCE")),
        fixture.debugMessages().toString());
  }

  @Test
  void syntheticForwardPlanCannotDeclareRearRetainWithoutBuilderProof() throws Exception {
    NodeId rearStation = NodeId.of("SURC:S:RVS:1");
    NodeId rearThroat = NodeId.of("SURC:PPK:RVS:1:002");
    NodeId currentAnchor = NodeId.of("SURC:PPK:RVS:1:001");
    NodeId nextNode = NodeId.of("SWITCHER:Towny:593:68:1140");
    RouteId routeId = RouteId.of("planner-log-7327");
    List<NodeId> physicalPath = List.of(rearStation, rearThroat, currentAnchor, nextNode);
    RearProtectionPlannerFixture fixture =
        rearProtectionPlannerFixture(
            "SURC-MT-LP-7327",
            routeId,
            List.of(rearStation, nextNode),
            List.of(currentAnchor, nextNode),
            physicalPath,
            0,
            currentAnchor,
            OccupancyResource.forNode(rearStation),
            ClaimRole.PROTECTIVE_RETAIN);
    invokeMarkDirectedRequest(
        fixture.service(),
        completeExpandedAuthorityRequest("SURC-MT-LP-7327", fixture.now(), physicalPath, routeId),
        SignalComputationTrace.Source.PERIODIC_TICK);

    fixture.service().traceSmartDispatchGlobalSnapshot(fixture.activeTrains(), fixture.now());

    assertTrue(
        fixture.debugMessages().stream()
            .noneMatch(message -> message.contains("SMART_DISPATCH_PLAN_SELECTED")),
        fixture.debugMessages().toString());
    assertTrue(
        fixture.debugMessages().stream()
            .anyMatch(message -> message.contains("reason=INSUFFICIENT_DIRECTION_EVIDENCE")),
        fixture.debugMessages().toString());
  }

  @Test
  void canonicalSignalPlanWithInitiallyEmptyLastPassedExpiresWhenLastPassedAppears()
      throws Exception {
    Instant now = Instant.now();
    NodeEdgePlannerFixture fixture = nodeEdgePlannerFixture(now);
    String followerC = "SURC-MT-LP-9643";
    NodeId leaderFrom = NodeId.of("SURC:S:RVS:1");
    NodeId leaderTo = NodeId.of("SURC:RVS:LYM:2:001");
    OccupancyDecision clear =
        new OccupancyDecision(false, now, SignalAspect.STOP, List.of(), false, "test-clear");
    OccupancyDecision blockedByLeader =
        blockedBy(OccupancyResource.forNode(leaderTo), fixture.trainA(), now);
    updateBlockerSnapshotWithoutRequest(fixture.service(), fixture.trainA(), clear, now);
    updateBlockerSnapshotWithoutRequest(fixture.service(), fixture.trainB(), blockedByLeader, now);
    updateBlockerSnapshotWithoutRequest(fixture.service(), followerC, blockedByLeader, now);
    replaceRouteProgressLastPassed(
        fixture.progressRegistry(), fixture.trainA(), Optional.empty(), now.minusMillis(1L));
    invokeMarkDirectedRequest(
        fixture.service(),
        completeTwoNodeAuthorityRequest(
            fixture.trainA(),
            now,
            leaderFrom,
            leaderTo,
            Optional.of(RouteId.of("planner-node-edge-a"))),
        SignalComputationTrace.Source.PERIODIC_TICK);
    assertTrue(
        fixture
            .progressRegistry()
            .updateLastPassedGraphNode(fixture.trainA(), leaderFrom, now.plusMillis(1L)));

    fixture
        .service()
        .traceSmartDispatchGlobalSnapshot(
            Set.of(fixture.trainA(), fixture.trainB(), followerC), now.plusMillis(1L));

    assertTrue(
        fixture.debugMessages().stream()
            .noneMatch(message -> message.contains("SMART_DISPATCH_PLAN_SELECTED")),
        fixture.debugMessages().toString());
    assertTrue(
        fixture.debugMessages().stream()
            .anyMatch(message -> message.contains("reason=PLAN_PROGRESS_WINDOW_MOVED")),
        fixture.debugMessages().toString());
    assertTrue(
        fixture.debugMessages().stream()
            .noneMatch(message -> message.contains("SMART_UNLOCK_RESERVATION_CREATED")),
        fixture.debugMessages().toString());
  }

  @Test
  void staleCanonicalSignalPlanCannotAuthorizeBottleneckLeaderWithoutOwnBlockerSnapshot()
      throws Exception {
    Instant now = Instant.now();
    NodeEdgePlannerFixture fixture = nodeEdgePlannerFixture(now);
    String followerC = "SURC-MT-LP-9643";
    NodeId leaderFrom = NodeId.of("SURC:S:RVS:1");
    NodeId leaderTo = NodeId.of("SURC:RVS:LYM:2:001");
    OccupancyDecision clear =
        new OccupancyDecision(false, now, SignalAspect.STOP, List.of(), false, "test-clear");
    OccupancyDecision blockedByLeader =
        blockedBy(OccupancyResource.forNode(leaderTo), fixture.trainA(), now);
    updateBlockerSnapshotWithoutRequest(fixture.service(), fixture.trainA(), clear, now);
    updateBlockerSnapshotWithoutRequest(fixture.service(), fixture.trainB(), blockedByLeader, now);
    updateBlockerSnapshotWithoutRequest(fixture.service(), followerC, blockedByLeader, now);
    invokeMarkDirectedRequest(
        fixture.service(),
        completeTwoNodeAuthorityRequest(
            fixture.trainA(),
            now.minusSeconds(2),
            leaderFrom,
            leaderTo,
            Optional.of(RouteId.of("planner-node-edge-a"))),
        SignalComputationTrace.Source.PERIODIC_TICK);

    fixture
        .service()
        .traceSmartDispatchGlobalSnapshot(
            Set.of(fixture.trainA(), fixture.trainB(), followerC), now);

    assertTrue(
        fixture.debugMessages().stream()
            .noneMatch(message -> message.contains("SMART_DISPATCH_PLAN_SELECTED")),
        fixture.debugMessages().toString());
    assertTrue(
        fixture.debugMessages().stream()
            .anyMatch(message -> message.contains("reason=MOVEMENT_PLAN_STALE")),
        fixture.debugMessages().toString());
    assertTrue(
        fixture.debugMessages().stream()
            .noneMatch(message -> message.contains("SMART_UNLOCK_RESERVATION_CREATED")),
        fixture.debugMessages().toString());
  }

  @Test
  void canonicalSignalPlanTrimsResourcesBehindLastPassedProgress() throws Exception {
    Instant now = Instant.now();
    String trainName = "SURC-MT-LP-5108";
    RouteId routeId = RouteId.of("planner-node-edge-a");
    NodeId nodeA = NodeId.of("SURC:S:RVS:1");
    NodeId nodeB = NodeId.of("SURC:RVS:LYM:2:001");
    NodeId nodeC = NodeId.of("SURC:S:LYM:1");
    EdgeId edgeAB = EdgeId.undirected(nodeA, nodeB);
    EdgeId edgeBC = EdgeId.undirected(nodeB, nodeC);
    MovementPlanSnapshot original =
        new MovementPlanSnapshot(
            trainName,
            Optional.of(routeId),
            0,
            Optional.of(nodeA),
            Optional.of(nodeA),
            Optional.of(nodeA),
            Optional.of(nodeB),
            new ExpandedPathPlan(
                List.of(nodeA, nodeB, nodeC),
                List.of(
                    new DirectedTraversalContext.DirectedEdge(edgeAB, nodeA, nodeB),
                    new DirectedTraversalContext.DirectedEdge(edgeBC, nodeB, nodeC)),
                Map.of(),
                Map.of()),
            List.of(
                OccupancyResource.forNode(nodeA),
                OccupancyResource.forEdge(edgeAB),
                OccupancyResource.forNode(nodeB),
                OccupancyResource.forEdge(edgeBC),
                OccupancyResource.forNode(nodeC)),
            1L,
            1L,
            "trim-test");
    RouteProgressRegistry.RouteProgressEntry progress =
        new RouteProgressRegistry.RouteProgressEntry(
            trainName,
            UUID.randomUUID(),
            routeId,
            0,
            Optional.of(nodeC),
            Optional.of(nodeB),
            SignalAspect.STOP,
            now);
    java.lang.reflect.Method align =
        RuntimeDispatchService.class.getDeclaredMethod(
            "alignMovementPlanToCurrentProgress",
            MovementPlanSnapshot.class,
            RouteProgressRegistry.RouteProgressEntry.class);
    align.setAccessible(true);

    @SuppressWarnings("unchecked")
    Optional<MovementPlanSnapshot> aligned =
        (Optional<MovementPlanSnapshot>) align.invoke(null, original, progress);

    MovementPlanSnapshot remaining = aligned.orElseThrow();
    assertEquals(List.of(nodeB, nodeC), remaining.expandedPathNodes());
    assertEquals(Optional.of(nodeB), remaining.effectiveFromNode());
    assertEquals(Optional.of(nodeC), remaining.effectiveToNode());
    assertFalse(remaining.movementRequiredResources().contains(OccupancyResource.forNode(nodeA)));
    assertFalse(remaining.movementRequiredResources().contains(OccupancyResource.forEdge(edgeAB)));
    assertTrue(remaining.movementRequiredResources().contains(OccupancyResource.forEdge(edgeBC)));
    assertTrue(CanonicalForwardPathEvidence.derive(trainName, remaining).evidence().isPresent());
  }

  @Test
  void globalObserveOnlyPlannerEnforceDoesNotMutateOccupancy() throws Exception {
    PlannerExecutionSnapshot snapshot =
        executePlannerReservationForMode(SmartDispatcherMode.OBSERVE_ONLY);

    assertEquals(snapshot.occupancyVersionBefore(), snapshot.manager().version());
    assertEquals(snapshot.claimsBefore(), snapshot.manager().snapshotClaims().size());
    assertEquals(snapshot.queuesBefore(), snapshot.manager().snapshotQueues().size());
    assertFalse(snapshot.service().hasActiveSmartUnlockReservation("train-1"));
    assertTrue(
        snapshot.debugMessages().stream()
            .anyMatch(message -> message.contains("SMART_UNLOCK_RESERVATION_WOULD_CREATE")));
    assertFalse(
        snapshot.debugMessages().stream()
            .anyMatch(message -> message.contains("SMART_UNLOCK_RESERVATION_COMMITTED")));
  }

  @Test
  void globalObserveOnlyPlannerEnforceDoesNotCreateUnlockReservation() throws Exception {
    PlannerExecutionSnapshot snapshot =
        executePlannerReservationForMode(SmartDispatcherMode.OBSERVE_ONLY);

    assertFalse(snapshot.service().hasActiveSmartUnlockReservation("train-1"));
    assertFalse(hasUnlockReservationClaim(snapshot.manager()));
  }

  @Test
  void globalObserveOnlyPlannerEnforceDoesNotIssueToken() throws Exception {
    PlannerExecutionSnapshot snapshot =
        executePlannerReservationForMode(SmartDispatcherMode.OBSERVE_ONLY);

    assertEquals(0, movementTokenCount(snapshot.service()));
    assertTrue(
        snapshot.debugMessages().stream()
            .noneMatch(message -> message.contains("SMART_UNLOCK_AUTHORITY_ISSUED")));
  }

  @Test
  void observeOnlyDoesNotIssueUnlockAuthority() throws Exception {
    PlannerExecutionSnapshot snapshot =
        executePlannerReservationForMode(SmartDispatcherMode.OBSERVE_ONLY);

    assertEquals(0, movementTokenCount(snapshot.service()));
    assertTrue(
        snapshot.debugMessages().stream()
            .anyMatch(message -> message.contains("SMART_UNLOCK_RESERVATION_WOULD_CREATE")));
    assertFalse(
        snapshot.debugMessages().stream()
            .anyMatch(message -> message.contains("SMART_UNLOCK_AUTHORITY_ISSUED")));
  }

  @Test
  void globalOffPlannerDoesNotMutateOccupancy() throws Exception {
    PlannerExecutionSnapshot snapshot = executePlannerReservationForMode(SmartDispatcherMode.OFF);

    assertEquals(snapshot.occupancyVersionBefore(), snapshot.manager().version());
    assertEquals(snapshot.claimsBefore(), snapshot.manager().snapshotClaims().size());
    assertEquals(snapshot.queuesBefore(), snapshot.manager().snapshotQueues().size());
    assertFalse(snapshot.service().hasActiveSmartUnlockReservation("train-1"));
  }

  @Test
  void globalEnforcePlannerMutationRequiresEffectGate() throws Exception {
    List<String> debugMessages = new ArrayList<>();
    RuntimeDispatchService observeOnly =
        createMinimalService(
            new SimpleOccupancyManager(
                (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy()),
            debugMessages,
            SmartDispatcherMode.OBSERVE_ONLY);
    ConfigManager.SmartDispatcherPlannerSettings settings = enforcePlannerSettings();

    assertEquals(
        "GLOBAL_MODE_NOT_ENFORCE",
        smartDispatchExecutorSkipReason(observeOnly, smartUnlockCandidate(), settings));

    RuntimeDispatchService enforce =
        createMinimalService(
            new SimpleOccupancyManager(
                (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy()),
            debugMessages,
            SmartDispatcherMode.ENFORCE);
    assertEquals("-", smartDispatchExecutorSkipReason(enforce, smartUnlockCandidate(), settings));
  }

  @Test
  void unchangedPlannerTraceCannotSuppressExecutorRecheck() throws Exception {
    List<String> debugMessages = new ArrayList<>();
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    ConfigManager.ConfigView base = testConfigView(20, 20.0);
    ConfigManager.ConfigView plannerView =
        new ConfigManager.ConfigView(
            base.configVersion(),
            base.debugEnabled(),
            base.locale(),
            base.storageSettings(),
            base.graphSettings(),
            base.autoStationSettings(),
            base.runtimeSettings(),
            base.spawnSettings(),
            base.trainConfigSettings(),
            base.reclaimSettings(),
            new ConfigManager.SmartDispatcherSettings(
                SmartDispatcherMode.ENFORCE, enforcePlannerSettings()),
            base.healthSettings());
    ConfigManager configManager = mock(ConfigManager.class);
    when(configManager.current()).thenReturn(plannerView);
    RuntimeDispatchService service =
        new RuntimeDispatchService(
            manager,
            mock(RailGraphService.class),
            mock(RouteDefinitionCache.class),
            new RouteProgressRegistry(),
            mock(SignNodeRegistry.class),
            mock(LayoverRegistry.class),
            new DwellRegistry(),
            configManager,
            null,
            new TrainConfigResolver(),
            debugMessages::add);
    SmartWaitForPlanner.UnlockCandidate candidate = smartUnlockCandidate();
    SmartWaitForPlanner.PlanResult unchangedResult =
        new SmartWaitForPlanner.PlanResult(
            "same-graph",
            candidate.planHash(),
            "same-throttle-key",
            List.of("trace"),
            List.of(candidate),
            Optional.of(candidate),
            false,
            true);
    SmartDispatcherController controller = mock(SmartDispatcherController.class);
    when(controller.planMinimalForwardUnlock(any())).thenReturn(unchangedResult);
    java.lang.reflect.Field controllerField =
        RuntimeDispatchService.class.getDeclaredField("smartDispatcherController");
    controllerField.setAccessible(true);
    controllerField.set(service, controller);
    java.lang.reflect.Field cooldownField =
        RuntimeDispatchService.class.getDeclaredField("smartUnlockNoReleaseCooldowns");
    cooldownField.setAccessible(true);
    @SuppressWarnings("unchecked")
    java.util.concurrent.ConcurrentMap<String, Instant> cooldowns =
        (java.util.concurrent.ConcurrentMap<String, Instant>) cooldownField.get(service);
    cooldowns.put(
        "plan:" + candidate.cycleId() + ":" + candidate.planHash(), Instant.now().plusSeconds(60));
    java.lang.reflect.Method tracePlanner =
        RuntimeDispatchService.class.getDeclaredMethod(
            "traceSmartMinimalForwardPlanner", Set.class, Map.class, Instant.class, List.class);
    tracePlanner.setAccessible(true);

    tracePlanner.invoke(service, Set.of(), Map.of(), Instant.now(), List.of());
    tracePlanner.invoke(service, Set.of(), Map.of(), Instant.now(), List.of());

    assertEquals(
        2,
        debugMessages.stream()
            .filter(
                message ->
                    message.contains("SMART_DISPATCH_EXECUTOR_SKIPPED")
                        && message.contains("NO_RELEASE_COOLDOWN"))
            .count(),
        debugMessages.toString());
  }

  @Test
  void executorCooldownUsesPlannerSnapshotTime() throws Exception {
    RuntimeDispatchService service =
        createMinimalService(
            new SimpleOccupancyManager(
                (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy()),
            new ArrayList<>(),
            SmartDispatcherMode.ENFORCE);
    SmartWaitForPlanner.UnlockCandidate candidate = smartUnlockCandidate();
    Instant capturedAt = Instant.parse("2026-01-01T00:00:00Z");
    java.lang.reflect.Field cooldownField =
        RuntimeDispatchService.class.getDeclaredField("smartUnlockNoReleaseCooldowns");
    cooldownField.setAccessible(true);
    @SuppressWarnings("unchecked")
    java.util.concurrent.ConcurrentMap<String, Instant> cooldowns =
        (java.util.concurrent.ConcurrentMap<String, Instant>) cooldownField.get(service);
    cooldowns.put(
        "plan:" + candidate.cycleId() + ":" + candidate.planHash(), capturedAt.plusSeconds(30));

    assertEquals(
        "NO_RELEASE_COOLDOWN",
        smartDispatchExecutorSkipReason(
            service, candidate, enforcePlannerSettings(), capturedAt.plusSeconds(29)));
    assertEquals(
        "-",
        smartDispatchExecutorSkipReason(
            service, candidate, enforcePlannerSettings(), capturedAt.plusSeconds(31)));
  }

  @Test
  void activeReservationIsIdempotentEvenWhenLegacyGuardSettingIsFalse() throws Exception {
    RuntimeDispatchService service =
        createMinimalService(
            new SimpleOccupancyManager(
                (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy()),
            new ArrayList<>(),
            SmartDispatcherMode.ENFORCE);
    SmartWaitForPlanner.UnlockCandidate candidate = smartUnlockCandidate();
    installSmartUnlockReservation(
        service, candidate, List.of(OccupancyResource.forNode(NodeId.of("B"))), 1L, 100);
    ConfigManager.SmartDispatcherPlannerSettings legacyDisabledGuard =
        new ConfigManager.SmartDispatcherPlannerSettings(
            true,
            SmartDispatcherPlannerMode.ENFORCE_MINIMAL_FORWARD,
            4,
            100,
            1000L,
            true,
            false,
            false,
            false);

    assertEquals(
        "ACTIVE_RESERVATION_EXISTS",
        smartDispatchExecutorSkipReason(
            service, candidate, legacyDisabledGuard, Instant.parse("2026-01-01T00:00:00Z")));
    assertEquals(
        "ACTIVE_RESERVATION_EXISTS",
        smartDispatchExecutorSkipReason(
            service,
            smartUnlockCandidate("train-1", "cycle-2"),
            legacyDisabledGuard,
            Instant.parse("2026-01-01T00:00:00Z")));
  }

  @Test
  void mutualOwnerTraceCapturesPriorityPhysicalFootprintAndAuthorityEvidence() throws Exception {
    List<String> debugMessages = new ArrayList<>();
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    RuntimeDispatchService service =
        createMinimalService(manager, debugMessages, SmartDispatcherMode.ENFORCE);
    OccupancyResource conflict = OccupancyResource.forConflict("switcher:SW");
    OccupancyResource physicalNode = OccupancyResource.forNode(NodeId.of("SW"));
    assertTrue(
        manager
            .acquire(
                new OccupancyRequest(
                    "train-1",
                    Optional.empty(),
                    Instant.now(),
                    List.of(physicalNode),
                    Map.of(),
                    Map.of(),
                    0,
                    AuthorizationPurpose.RUNTIME_MOVE,
                    Map.of(),
                    Map.of(physicalNode, ResourceIntent.HOLD_ONLY)))
            .allowed());
    assertTrue(
        manager
            .acquire(
                new OccupancyRequest(
                    "train-2", Optional.empty(), Instant.now(), List.of(conflict), Map.of(), 3))
            .allowed());
    OccupancyRequest waitingRequest =
        new OccupancyRequest(
            "train-1", Optional.empty(), Instant.now(), List.of(conflict), Map.of(), 23);
    assertFalse(manager.canEnter(waitingRequest).allowed());

    SmartWaitForPlanner.TrainState trainOne =
        new SmartWaitForPlanner.TrainState(
            "train-1",
            "route-1",
            4,
            "SW",
            "OUT",
            "SW",
            CorridorDirection.A_TO_B,
            "test",
            "-",
            60,
            true,
            false,
            false,
            false,
            false,
            false,
            "NONE");
    SmartWaitForPlanner.TrainState trainTwo =
        new SmartWaitForPlanner.TrainState(
            "train-2",
            "route-2",
            7,
            "ENTRY",
            "SW",
            "ENTRY",
            CorridorDirection.B_TO_A,
            "test",
            "-",
            60,
            true,
            false,
            false,
            false,
            false,
            false,
            "NONE");
    SmartWaitForPlanner.PlannerInput plannerInput =
        new SmartWaitForPlanner.PlannerInput(
            Instant.now(),
            new SmartWaitForPlanner.PlannerSettings(
                true,
                SmartDispatcherPlannerMode.ENFORCE_MINIMAL_FORWARD,
                4,
                20,
                20_000L,
                true,
                false,
                false,
                true),
            List.of(
                new SmartWaitForPlanner.InputEdge(
                    "train-1",
                    "train-2",
                    conflict.toString(),
                    "CONFLICT",
                    "forward",
                    "MOVEMENT_REQUIRED",
                    "MOVEMENT_REQUIRED",
                    "test",
                    CorridorDirection.A_TO_B,
                    0L,
                    true)),
            Map.of("train-1", trainOne, "train-2", trainTwo),
            Set.of(),
            Set.of());
    java.lang.reflect.Method trace =
        RuntimeDispatchService.class.getDeclaredMethod(
            "traceMutualConflictOwnerSet",
            SmartWaitForPlanner.PlannerInput.class,
            SmartWaitForPlanner.UnlockCandidate.class,
            boolean.class,
            java.util.Collection.class);
    trace.setAccessible(true);
    trace.invoke(
        service,
        plannerInput,
        smartUnlockCandidate(conflict.toString(), CorridorDirection.A_TO_B),
        false,
        manager.snapshotClaims());

    String ownerTrace =
        debugMessages.stream()
            .filter(message -> message.contains("SMART_MUTUAL_CONFLICT_OWNER_SET"))
            .findFirst()
            .orElseThrow();
    assertTrue(ownerTrace.contains("train-1=23@RUNTIME_MOVE"), ownerTrace);
    assertTrue(ownerTrace.contains("NODE:SW@HOLD_ONLY"), ownerTrace);
    assertTrue(ownerTrace.contains("reason=no-token"), ownerTrace);
    assertFalse(ownerTrace.contains("ownerPriorities={train-1=unknown"), ownerTrace);
    assertFalse(ownerTrace.contains("ownerPhysicalFootprints={train-1=unknown"), ownerTrace);
    assertFalse(ownerTrace.contains("ownerReservedAuthorities={train-1=unknown"), ownerTrace);
  }

  @Test
  void unlockAuthorityRequiresGlobalEnforceAndEffectGate() throws Exception {
    RuntimeDispatchService observeOnly =
        createMinimalService(
            new SimpleOccupancyManager(
                (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy()),
            new ArrayList<>(),
            SmartDispatcherMode.OBSERVE_ONLY);
    RuntimeDispatchService enforce =
        createMinimalService(
            new SimpleOccupancyManager(
                (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy()),
            new ArrayList<>(),
            SmartDispatcherMode.ENFORCE);

    assertEquals(
        "GLOBAL_MODE_NOT_ENFORCE",
        smartDispatchExecutorSkipReason(
            observeOnly, smartUnlockCandidate(), enforcePlannerSettings()));
    assertEquals(
        "-",
        smartDispatchExecutorSkipReason(enforce, smartUnlockCandidate(), enforcePlannerSettings()));
  }

  @Test
  void smartUnlockPlannerNeverIssuesAuthorityFromBlockerResources() throws Exception {
    PlannerExecutionSnapshot snapshot =
        executePlannerReservationForMode(SmartDispatcherMode.ENFORCE);

    assertTrue(
        movementTokens(snapshot.service()).isEmpty(),
        "wait-for blocker resources are not a complete movement-authority window");
    assertTrue(
        snapshot.manager().snapshotClaims().isEmpty(),
        "planner selection must not acquire blocker resources outside canonical admission");
    assertTrue(
        snapshot.debugMessages().stream()
            .anyMatch(message -> message.contains("TEST_SIGNAL_REEVALUATION_REQUEST train-1")),
        snapshot.debugMessages().toString());
  }

  @Test
  void smartUnlockSelectionAddsBoundedCanonicalQueuePriorityIntent() throws Exception {
    List<String> debugMessages = new ArrayList<>();
    RuntimeDispatchService service =
        createMinimalService(
            new SimpleOccupancyManager(
                (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy()),
            mock(RouteDefinitionCache.class),
            smartUnlockProgressRegistry(),
            debugMessages,
            SmartDispatcherMode.ENFORCE);
    SmartWaitForPlanner.UnlockCandidate candidate = smartUnlockCandidate();
    installSmartUnlockReservation(
        service,
        candidate,
        List.of(OccupancyResource.forNode(NodeId.of("B"))),
        currentTraceTick(),
        1000);
    DispatchPriorityResolution base =
        new DispatchPriorityResolution(
            0,
            DispatchPrioritySource.ROUTE_CODE_TAGS,
            Optional.empty(),
            Optional.of("op:l1:r1"),
            Optional.empty(),
            "operation_type_missing");

    DispatchPriorityResolution selected =
        service.applySmartUnlockPriorityIntent("train-1", NodeId.of("A"), NodeId.of("B"), base);

    assertTrue(selected.priority() > base.priority());
    assertTrue(selected.priority() - base.priority() <= 240);
  }

  @Test
  void canonicalSmartUnlockPriorityRollsBackAfterRouteIdentityChanges() throws Exception {
    List<String> debugMessages = new ArrayList<>();
    RouteProgressRegistry registry = smartUnlockProgressRegistry();
    RuntimeDispatchService service =
        createMinimalService(
            new SimpleOccupancyManager(
                (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy()),
            mock(RouteDefinitionCache.class),
            registry,
            debugMessages,
            SmartDispatcherMode.ENFORCE);
    SmartWaitForPlanner.UnlockCandidate candidate = smartUnlockCandidate();
    installSmartUnlockReservation(
        service,
        candidate,
        List.of(OccupancyResource.forNode(NodeId.of("B"))),
        currentTraceTick(),
        1000);
    RouteDefinition replacementRoute =
        new RouteDefinition(
            RouteId.of("other-route"),
            List.of(NodeId.of("A"), NodeId.of("B"), NodeId.of("C")),
            Optional.empty());
    registry.initFromTags(
        "train-1", new TagStore("train-1", "FTA_ROUTE_INDEX=0").properties(), replacementRoute);
    DispatchPriorityResolution base =
        new DispatchPriorityResolution(
            0,
            DispatchPrioritySource.ROUTE_CODE_TAGS,
            Optional.empty(),
            Optional.of("op:l1:r1"),
            Optional.empty(),
            "operation_type_missing");

    DispatchPriorityResolution selected =
        service.applySmartUnlockPriorityIntent("train-1", NodeId.of("A"), NodeId.of("B"), base);

    assertEquals(base, selected);
    assertFalse(service.hasActiveSmartUnlockReservation("train-1"));
    assertTrue(
        debugMessages.stream()
            .anyMatch(
                message ->
                    message.contains("SMART_UNLOCK_ROLLBACK_STARTED")
                        && message.contains("reason=canonical-progress-window-moved")),
        debugMessages.toString());
  }

  @Test
  void canonicalSmartUnlockPriorityRollsBackAfterSameIndexLastPassedChanges() throws Exception {
    List<String> debugMessages = new ArrayList<>();
    RouteProgressRegistry registry = smartUnlockProgressRegistry();
    RuntimeDispatchService service =
        createMinimalService(
            new SimpleOccupancyManager(
                (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy()),
            mock(RouteDefinitionCache.class),
            registry,
            debugMessages,
            SmartDispatcherMode.ENFORCE);
    SmartWaitForPlanner.UnlockCandidate candidate = smartUnlockCandidate();
    installSmartUnlockReservation(
        service,
        candidate,
        List.of(OccupancyResource.forNode(NodeId.of("B"))),
        currentTraceTick(),
        1000);
    NodeId intermediateNode = NodeId.of("A-MID");
    assertTrue(
        registry.updateLastPassedGraphNode("train-1", intermediateNode, Instant.now()),
        "测试夹具必须保持 route index 不变并只推进真实 last-passed");
    DispatchPriorityResolution base =
        new DispatchPriorityResolution(
            0,
            DispatchPrioritySource.ROUTE_CODE_TAGS,
            Optional.empty(),
            Optional.of("op:l1:r1"),
            Optional.empty(),
            "operation_type_missing");

    DispatchPriorityResolution selected =
        service.applySmartUnlockPriorityIntent("train-1", intermediateNode, NodeId.of("B"), base);

    assertEquals(base, selected);
    assertFalse(service.hasActiveSmartUnlockReservation("train-1"));
    assertTrue(
        debugMessages.stream()
            .anyMatch(
                message ->
                    message.contains("SMART_UNLOCK_ROLLBACK_STARTED")
                        && message.contains("reason=canonical-progress-window-moved")),
        debugMessages.toString());
  }

  @Test
  void smartUnlockSelectionDoesNotOverrideManualPriority() throws Exception {
    RuntimeDispatchService service =
        createMinimalService(
            new SimpleOccupancyManager(
                (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy()),
            new ArrayList<>(),
            SmartDispatcherMode.ENFORCE);
    SmartWaitForPlanner.UnlockCandidate candidate = smartUnlockCandidate();
    installSmartUnlockReservation(
        service,
        candidate,
        List.of(OccupancyResource.forNode(NodeId.of("B"))),
        currentTraceTick(),
        1000);
    DispatchPriorityResolution manual =
        new DispatchPriorityResolution(
            50,
            DispatchPrioritySource.MANUAL_FTA_PRIORITY,
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            "manual",
            false,
            true,
            50,
            0);

    assertEquals(
        manual,
        service.applySmartUnlockPriorityIntent("train-1", NodeId.of("A"), NodeId.of("B"), manual));
  }

  @Test
  void smartUnlockPriorityIntentSaturatesWithoutIntegerOverflow() throws Exception {
    List<String> debugMessages = new ArrayList<>();
    RuntimeDispatchService service =
        createMinimalService(
            new SimpleOccupancyManager(
                (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy()),
            mock(RouteDefinitionCache.class),
            smartUnlockProgressRegistry(),
            debugMessages,
            SmartDispatcherMode.ENFORCE);
    SmartWaitForPlanner.UnlockCandidate candidate = smartUnlockCandidate();
    installSmartUnlockReservation(
        service,
        candidate,
        List.of(OccupancyResource.forNode(NodeId.of("B"))),
        currentTraceTick(),
        1000);
    DispatchPriorityResolution nearMaximum =
        new DispatchPriorityResolution(
            Integer.MAX_VALUE - 10,
            DispatchPrioritySource.ROUTE_CODE_TAGS,
            Optional.empty(),
            Optional.of("op:l1:r1"),
            Optional.empty(),
            "operation_type_missing",
            false,
            false,
            0,
            Integer.MAX_VALUE - 10);

    DispatchPriorityResolution selected =
        service.applySmartUnlockPriorityIntent(
            "train-1", NodeId.of("A"), NodeId.of("B"), nearMaximum);

    assertEquals(Integer.MAX_VALUE, selected.priority());
    assertEquals(Integer.MAX_VALUE, selected.policyAdjustment());
  }

  @Test
  void stopRetainPreservesCanonicalSmartUnlockQueuePriority() throws Exception {
    String trainName = "winner";
    NodeId a = NodeId.of("A");
    NodeId b = NodeId.of("B");
    OccupancyResource conflict = OccupancyResource.forConflict("single:test:A~B");
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    assertTrue(
        manager
            .acquire(singleConflictRequest("blocker", conflict, CorridorDirection.A_TO_B))
            .allowed());
    RuntimeDispatchService service = createMinimalService(manager, new ArrayList<>());
    RouteDefinition route =
        new RouteDefinition(RouteId.of("queue-priority"), List.of(a, b), Optional.empty());
    Instant now = Instant.now();
    OccupancyRequest canonicalRequest =
        singleConflictRequest(trainName, conflict, CorridorDirection.A_TO_B)
            .withSchedulingMetadata(now, 240);

    invokeRetainStopOccupancy(
        service,
        trainName,
        route,
        0,
        a,
        Optional.of(canonicalRequest),
        graphWithSingleEdge(a, b, 10),
        now);

    OccupancyQueueEntry queuedWinner =
        manager.snapshotQueues().stream()
            .filter(snapshot -> snapshot.resource().equals(conflict))
            .flatMap(snapshot -> snapshot.entries().stream())
            .filter(entry -> entry.trainName().equals(trainName))
            .findFirst()
            .orElseThrow();
    assertEquals(240, queuedWinner.priority());
  }

  @Test
  void unlockReevaluationDoesNotClearDestination() throws Exception {
    PlannerExecutionSnapshot snapshot =
        executePlannerReservationForMode(SmartDispatcherMode.ENFORCE);

    assertTrue(movementTokens(snapshot.service()).isEmpty());
    assertFalse(
        snapshot.debugMessages().stream()
            .anyMatch(
                message ->
                    message.contains("destinationMutated=true")
                        || message.contains("CLEAR_DESTINATION")));
  }

  @Test
  void unlockAuthorityDoesNotInvalidateExistingToken() throws Exception {
    List<String> debugMessages = new ArrayList<>();
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    RuntimeDispatchService service =
        createMinimalService(manager, debugMessages, SmartDispatcherMode.ENFORCE);
    OccupancyResource resource = OccupancyResource.forNode(NodeId.of("B"));
    MovementAuthorizationToken existing =
        new MovementAuthorizationToken(
                "train-1",
                99L,
                Instant.now(),
                NodeId.of("A"),
                NodeId.of("B"),
                List.of(resource),
                SignalAspect.PROCEED)
            .activate("B");
    installMovementToken(service, existing);

    executeSmartUnlockReservation(service, smartUnlockCandidate(), enforcePlannerSettings());

    assertEquals(1, movementTokenCount(service));
    assertTrue(
        movementTokens(service).stream()
            .allMatch(token -> token.committedDestination().isPresent()));
    assertFalse(
        debugMessages.stream().anyMatch(message -> message.contains("tokenInvalidated=true")));
  }

  @Test
  void unlockAuthorityDoesNotForceGreen() throws Exception {
    RouteProgressRegistry registry = signalRegistryForTrain("train-1", SignalAspect.STOP);
    PlannerExecutionSnapshot snapshot =
        executePlannerReservationForMode(SmartDispatcherMode.ENFORCE, registry);

    assertTrue(
        snapshot.debugMessages().stream()
            .anyMatch(
                message -> message.contains("SMART_UNLOCK_AUTHORITY_REEVALUATION_REQUESTED")));
    assertTrue(
        snapshot.debugMessages().stream()
            .anyMatch(
                message ->
                    message.contains("SMART_UNLOCK_PLAN_APPLY")
                        && message.contains("applyPhase=reevaluation-requested")
                        && message.contains("applyResult=canonical-reevaluation-requested")));
    assertEquals(SignalAspect.STOP, registry.get("train-1").orElseThrow().lastSignal());
  }

  @Test
  void headOnYieldReleasesOnlyNonPhysicalYieldState() throws Exception {
    List<String> debugMessages = new ArrayList<>();
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    RuntimeDispatchService service =
        createMinimalService(manager, debugMessages, SmartDispatcherMode.ENFORCE);
    OccupancyResource conflict = OccupancyResource.forConflict("single:test:A~B");
    OccupancyResource physicalNode = OccupancyResource.forNode(NodeId.of("A"));
    OccupancyRequest lookahead =
        singleConflictRequest("yield-train", conflict, CorridorDirection.B_TO_A)
            .withResourceIntents(Map.of(conflict, ResourceIntent.LOOKAHEAD_PREVIEW));
    OccupancyRequest physical =
        new OccupancyRequest(
                "yield-train",
                Optional.empty(),
                Instant.now(),
                List.of(physicalNode),
                Map.of(),
                Map.of(),
                0)
            .withResourceIntents(Map.of(physicalNode, ResourceIntent.MOVEMENT_REQUIRED));
    assertTrue(manager.acquire(lookahead).allowed());
    assertTrue(manager.acquire(physical).allowed());
    manager.touchQueues(
        singleConflictRequest("yield-train", conflict, CorridorDirection.B_TO_A)
            .withResourceIntents(Map.of(conflict, ResourceIntent.QUEUE_POSITION)));

    executeSmartUnlockReservation(
        service, headOnYieldCandidate(conflict, physicalNode), enforcePlannerSettings());

    assertTrue(manager.getClaim(conflict).isEmpty());
    assertTrue(manager.getClaim(physicalNode).isPresent());
    assertEquals(0, movementTokenCount(service));
    assertTrue(
        manager.snapshotQueues().stream()
            .flatMap(snapshot -> snapshot.entries().stream())
            .noneMatch(entry -> entry.trainName().equals("yield-train")));
    assertTrue(
        debugMessages.stream()
            .anyMatch(
                message ->
                    message.contains("SMART_HEAD_ON_YIELD_APPLIED")
                        && message.contains("physicalClaimsReleased=0")
                        && message.contains("movementAuthorityIssued=false")));
  }

  @Test
  void stopRetainBehindSingleClaimBecomesReleaseEligibleOnlyWhenRouteProvesBehind()
      throws Exception {
    RuntimeDispatchService service = createMinimalService();
    RouteId routeId = RouteId.of("retain");
    NodeId a = NodeId.of("A");
    NodeId b = NodeId.of("B");
    NodeId c = NodeId.of("C");
    OccupancyResource behind = OccupancyResource.forConflict("single:line:A~B");
    OccupancyResource currentOrAhead = OccupancyResource.forConflict("single:line:B~C");
    OccupancyRequest holdAtC =
        new OccupancyRequest(
            "train-1",
            Optional.of(routeId),
            Instant.now(),
            List.of(OccupancyResource.forNode(c)),
            Map.of(),
            0,
            AuthorizationPurpose.RUNTIME_MOVE);
    List<OccupancyClaim> oldSelfClaims =
        List.of(
            new OccupancyClaim(
                behind,
                "train-1",
                Optional.of(routeId),
                Instant.now(),
                Duration.ZERO,
                Optional.of(CorridorDirection.A_TO_B),
                ClaimRole.MOVEMENT_REQUIRED),
            new OccupancyClaim(
                currentOrAhead,
                "train-1",
                Optional.of(routeId),
                Instant.now(),
                Duration.ZERO,
                Optional.of(CorridorDirection.A_TO_B),
                ClaimRole.MOVEMENT_REQUIRED));

    Set<OccupancyResource> releaseResources =
        invokeStopRetainBehindReleaseResources(
            service, List.of(a, b, c), 2, oldSelfClaims, holdAtC);

    assertTrue(releaseResources.contains(behind));
    assertFalse(releaseResources.contains(currentOrAhead));
  }

  @Test
  void stopRetainWithUnknownTrainLengthKeepsAllProvenRearEdges() throws Exception {
    String trainName = "long-train";
    NodeId a = NodeId.of("A");
    NodeId b = NodeId.of("B");
    NodeId c = NodeId.of("C");
    UUID worldId = UUID.randomUUID();
    PhysicalGraphFixture physicalGraph =
        withSyntheticPhysicalFootprints(graphWithTwoEdges(a, b, c, 10, 10), worldId);
    RailGraph graph = physicalGraph.graph();
    RouteDefinition route =
        new RouteDefinition(RouteId.of("rear-retain"), List.of(a, b, c), Optional.empty());
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    OccupancyResource rearEdge = OccupancyResource.forEdge(EdgeId.undirected(a, b));
    OccupancyRequest existingRearFootprint =
        new OccupancyRequest(
                trainName,
                Optional.of(route.id()),
                Instant.now(),
                List.of(rearEdge),
                Map.of(),
                Map.of(),
                0,
                AuthorizationPurpose.RUNTIME_MOVE)
            .withResourceIntents(Map.of(rearEdge, ResourceIntent.PROTECTIVE_RETAIN));
    assertTrue(manager.acquire(existingRearFootprint).allowed());
    RuntimeDispatchService service = createMinimalService(manager, new ArrayList<>());
    FakeTrain train = new FakeTrain(worldId, new TagStore(trainName).properties(), false);
    train.estimatedTrainLengthBlocks = OptionalDouble.empty();
    train.liveRailFootprintCells =
        Optional.of(Set.of(physicalGraph.cellsByEdge().get(EdgeId.undirected(a, b))));

    invokeRetainStopOccupancy(
        service, trainName, route, 2, c, Optional.empty(), graph, Instant.now(), train);

    assertTrue(manager.getClaim(rearEdge).isPresent(), "未知车长不能成为释放已证明列尾占用的依据");
  }

  @Test
  void stopRetainUsesTrainLengthAcrossTccCurveWithoutPersistedTrackCells() throws Exception {
    String trainName = "curve-train";
    NodeId a = NodeId.of("A");
    NodeId b = NodeId.of("B");
    NodeId c = NodeId.of("C");
    RailFootprintCell rearCell = new RailFootprintCell(10, 64, 10);
    RailEdge rear = new RailEdge(EdgeId.undirected(a, b), a, b, 40, -1.0, true, Optional.empty());
    RailEdge current =
        new RailEdge(EdgeId.undirected(b, c), b, c, 40, -1.0, true, Optional.empty());
    Map<EdgeId, RailEdge> edges = Map.of(rear.id(), rear, current.id(), current);
    UUID worldId = UUID.randomUUID();
    RailGraph graph =
        new SimpleRailGraph(
            Map.of(a, new RailNodeTest(a), b, new RailNodeTest(b), c, new RailNodeTest(c)),
            edges,
            Set.of(),
            RailInterlockingState.fromSnapshot(
                worldId,
                edges.keySet(),
                new org.fetarute
                    .fetaruteTCAddon
                    .dispatcher
                    .graph
                    .interlocking
                    .RailInterlockingCoverage(2, 2, true),
                Map.of()));
    RouteDefinition route =
        new RouteDefinition(RouteId.of("tcc-rear-retain"), List.of(a, b, c), Optional.empty());
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    OccupancyResource rearEdge = OccupancyResource.forEdge(rear.id());
    assertTrue(
        manager
            .acquire(
                new OccupancyRequest(
                    trainName, Optional.of(route.id()), Instant.now(), List.of(rearEdge), Map.of()))
            .allowed());
    RuntimeDispatchService service = createMinimalService(manager, new ArrayList<>());
    FakeTrain train = new FakeTrain(worldId, new TagStore(trainName).properties(), false, 0.0);
    train.estimatedTrainLengthBlocks = OptionalDouble.of(80.0);
    train.liveRailFootprintCells = Optional.of(Set.of(rearCell));

    invokeRetainStopOccupancy(
        service, trainName, route, 2, c, Optional.empty(), graph, Instant.now(), train);

    assertTrue(
        manager.getClaim(rearEdge).isPresent(), "TCC 长曲线不持久化全轨迹时，Node-first 车长保护仍不得提前释放后方进路");
  }

  @Test
  void stopRetainUsesNextLegOriginLastPassedAndReleasesStaleThroatFootprint() throws Exception {
    List<String> debugMessages = new ArrayList<>();
    String trainName = "SURC-MT-LH-8023";
    NodeId hhuDepot = NodeId.of("SURC:D:HHU:3");
    NodeId hhuSwitcher = NodeId.of("SWITCHER:Towny:502:74:996");
    NodeId ppkStation = NodeId.of("SURC:S:PPK:1");
    NodeId ppkToRvs = NodeId.of("SURC:PPK:RVS:2:001");
    RailEdge hhuThroatEdge =
        new RailEdge(
            EdgeId.undirected(hhuDepot, hhuSwitcher),
            hhuDepot,
            hhuSwitcher,
            10,
            -1.0,
            true,
            Optional.empty());
    RailEdge switcherToRvs =
        new RailEdge(
            EdgeId.undirected(hhuSwitcher, ppkToRvs),
            hhuSwitcher,
            ppkToRvs,
            10,
            -1.0,
            true,
            Optional.empty());
    RailEdge ppkToRvsEdge =
        new RailEdge(
            EdgeId.undirected(ppkStation, ppkToRvs),
            ppkStation,
            ppkToRvs,
            10,
            -1.0,
            true,
            Optional.empty());
    RailGraph logicalGraph =
        new SimpleRailGraph(
            Map.of(
                hhuDepot,
                    new RailNodeTest(
                        hhuDepot,
                        NodeType.DEPOT,
                        Optional.of(WaypointMetadata.depot("SURC", "HHU", 3))),
                hhuSwitcher, new RailNodeTest(hhuSwitcher, NodeType.SWITCHER, Optional.empty()),
                ppkStation,
                    new RailNodeTest(
                        ppkStation,
                        NodeType.STATION,
                        Optional.of(WaypointMetadata.station("SURC", "PPK", 1))),
                ppkToRvs,
                    new RailNodeTest(
                        ppkToRvs,
                        NodeType.WAYPOINT,
                        Optional.of(WaypointMetadata.interval("SURC", "PPK", "RVS", 2, "001")))),
            Map.of(
                hhuThroatEdge.id(),
                hhuThroatEdge,
                switcherToRvs.id(),
                switcherToRvs,
                ppkToRvsEdge.id(),
                ppkToRvsEdge),
            Set.of());
    UUID worldId = UUID.randomUUID();
    PhysicalGraphFixture physicalGraph = withSyntheticPhysicalFootprints(logicalGraph, worldId);
    RailGraph graph = physicalGraph.graph();
    RouteDefinition route =
        new RouteDefinition(
            RouteId.of("MT-2O_ShortD"), List.of(hhuDepot, ppkToRvs), Optional.empty());
    UUID routeUuid = UUID.randomUUID();
    RouteProgressRegistry registry = new RouteProgressRegistry();
    registry.initFromTags(
        trainName,
        new TagStore(trainName, "FTA_ROUTE_ID=" + routeUuid, "FTA_ROUTE_INDEX=0").properties(),
        route);
    registry.updateLastPassedGraphNode(trainName, ppkStation, Instant.now());
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    OccupancyResource staleEdge = OccupancyResource.forEdge(hhuThroatEdge.id());
    OccupancyResource staleSingle =
        OccupancyResource.forConflict(
            "single:SURC:CGL:WYB:1:001:SURC:D:HHU:3~SWITCHER:Towny:502:74:996");
    OccupancyRequest staleFootprint =
        new OccupancyRequest(
                trainName,
                Optional.of(route.id()),
                Instant.now(),
                List.of(staleEdge, staleSingle),
                Map.of(staleSingle.key(), CorridorDirection.A_TO_B),
                Map.of(staleSingle.key(), 0),
                0,
                AuthorizationPurpose.RUNTIME_MOVE)
            .withResourceIntents(
                Map.of(
                    staleEdge,
                    ResourceIntent.PROTECTIVE_RETAIN,
                    staleSingle,
                    ResourceIntent.PROTECTIVE_RETAIN));
    assertTrue(manager.acquire(staleFootprint).allowed());
    RuntimeDispatchService service =
        createMinimalService(
            manager,
            routeDefinitionCacheWith(route, routeUuid),
            registry,
            debugMessages,
            SmartDispatcherMode.ENFORCE);
    FakeTrain train = new FakeTrain(worldId, new TagStore(trainName).properties(), false);
    train.liveRailFootprintCells =
        Optional.of(
            Set.of(physicalGraph.cellsByEdge().get(EdgeId.undirected(ppkStation, ppkToRvs))));

    invokeRetainStopOccupancy(
        service, trainName, route, 0, hhuDepot, Optional.empty(), graph, Instant.now(), train);

    assertTrue(manager.getClaim(staleEdge).isEmpty(), debugMessages.toString());
    assertTrue(manager.getClaim(staleSingle).isEmpty(), debugMessages.toString());
    assertTrue(
        manager.getClaim(OccupancyResource.forNode(ppkStation)).isPresent(),
        debugMessages.toString());
    assertTrue(
        debugMessages.stream()
            .anyMatch(
                message ->
                    message.contains("SMART_STOP_RETAIN_CURRENT_NODE_OVERRIDDEN")
                        && message.contains("effectiveNode=SURC:S:PPK:1")
                        && message.contains("reason=last-passed-next-leg-origin")),
        debugMessages.toString());
  }

  @Test
  void stopRetainDoesNotUseLastPassedWhenNextLegOriginDiffers() throws Exception {
    List<String> debugMessages = new ArrayList<>();
    String trainName = "SURC-MT-LH-8023";
    NodeId hhuDepot = NodeId.of("SURC:D:HHU:3");
    NodeId hhuSwitcher = NodeId.of("SWITCHER:Towny:502:74:996");
    NodeId ppkStation = NodeId.of("SURC:S:PPK:1");
    NodeId lymToRvs = NodeId.of("SURC:LYM:RVS:2:001");
    RailEdge hhuThroatEdge =
        new RailEdge(
            EdgeId.undirected(hhuDepot, hhuSwitcher),
            hhuDepot,
            hhuSwitcher,
            10,
            -1.0,
            true,
            Optional.empty());
    RailEdge switcherToRvs =
        new RailEdge(
            EdgeId.undirected(hhuSwitcher, lymToRvs),
            hhuSwitcher,
            lymToRvs,
            10,
            -1.0,
            true,
            Optional.empty());
    RailEdge ppkToRvsEdge =
        new RailEdge(
            EdgeId.undirected(ppkStation, lymToRvs),
            ppkStation,
            lymToRvs,
            10,
            -1.0,
            true,
            Optional.empty());
    RailGraph graph =
        new SimpleRailGraph(
            Map.of(
                hhuDepot,
                    new RailNodeTest(
                        hhuDepot,
                        NodeType.DEPOT,
                        Optional.of(WaypointMetadata.depot("SURC", "HHU", 3))),
                hhuSwitcher, new RailNodeTest(hhuSwitcher, NodeType.SWITCHER, Optional.empty()),
                ppkStation,
                    new RailNodeTest(
                        ppkStation,
                        NodeType.STATION,
                        Optional.of(WaypointMetadata.station("SURC", "PPK", 1))),
                lymToRvs,
                    new RailNodeTest(
                        lymToRvs,
                        NodeType.WAYPOINT,
                        Optional.of(WaypointMetadata.interval("SURC", "LYM", "RVS", 2, "001")))),
            Map.of(
                hhuThroatEdge.id(),
                hhuThroatEdge,
                switcherToRvs.id(),
                switcherToRvs,
                ppkToRvsEdge.id(),
                ppkToRvsEdge),
            Set.of());
    RouteDefinition route =
        new RouteDefinition(
            RouteId.of("MT-2O_ShortD"), List.of(hhuDepot, lymToRvs), Optional.empty());
    UUID routeUuid = UUID.randomUUID();
    RouteProgressRegistry registry = new RouteProgressRegistry();
    registry.initFromTags(
        trainName,
        new TagStore(trainName, "FTA_ROUTE_ID=" + routeUuid, "FTA_ROUTE_INDEX=0").properties(),
        route);
    registry.updateLastPassedGraphNode(trainName, ppkStation, Instant.now());
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    OccupancyResource staleEdge = OccupancyResource.forEdge(hhuThroatEdge.id());
    OccupancyRequest staleFootprint =
        new OccupancyRequest(
                trainName,
                Optional.of(route.id()),
                Instant.now(),
                List.of(staleEdge),
                Map.of(),
                Map.of(),
                0,
                AuthorizationPurpose.RUNTIME_MOVE)
            .withResourceIntents(Map.of(staleEdge, ResourceIntent.PROTECTIVE_RETAIN));
    assertTrue(manager.acquire(staleFootprint).allowed());
    RuntimeDispatchService service =
        createMinimalService(
            manager,
            routeDefinitionCacheWith(route, routeUuid),
            registry,
            debugMessages,
            SmartDispatcherMode.ENFORCE);

    invokeRetainStopOccupancy(service, trainName, route, 0, hhuDepot, graph, Instant.now());

    assertTrue(manager.getClaim(staleEdge).isPresent(), debugMessages.toString());
    assertFalse(
        debugMessages.stream()
            .anyMatch(message -> message.contains("SMART_STOP_RETAIN_CURRENT_NODE_OVERRIDDEN")),
        debugMessages.toString());
  }

  @Test
  void queuePositionBlockerIsInactiveForSmartPlannerNormalAdmission() throws Exception {
    RuntimeDispatchService service = createMinimalService();
    RuntimeDispatchService.DeadlockBlockerInfo queueBlocker =
        new RuntimeDispatchService.DeadlockBlockerInfo(
            "waiting",
            "switcher:TEST",
            Optional.empty(),
            "waiting",
            "CONFLICT:switcher:TEST",
            "SWITCHER_CONFLICT",
            "MOVEMENT_REQUIRED",
            "QUEUE_POSITION",
            "canEnterPreview:queue-blocked",
            currentTraceTick(),
            1L);
    RuntimeDispatchService.DeadlockBlockerInfo liveBlocker =
        new RuntimeDispatchService.DeadlockBlockerInfo(
            "owner",
            "",
            Optional.empty(),
            "owner",
            "NODE:SWITCHER:TEST",
            "HARD_OCCUPANCY",
            "MOVEMENT_REQUIRED",
            "MOVEMENT_REQUIRED",
            "canEnterPreview:blockers",
            currentTraceTick(),
            1L);
    java.lang.reflect.Method method =
        RuntimeDispatchService.class.getDeclaredMethod(
            "smartPlannerEdgeActiveForNormalAdmission",
            RuntimeDispatchService.DeadlockBlockerInfo.class);
    method.setAccessible(true);

    assertFalse((boolean) method.invoke(service, queueBlocker));
    assertTrue((boolean) method.invoke(service, liveBlocker));
  }

  @Test
  void switcherMergeTopologySurvivesRuntimeSnapshotRefreshAndReachesPlanner() throws Exception {
    RuntimeDispatchService service = createMinimalService();
    Instant now = Instant.parse("2026-07-18T14:24:57Z");
    Instant mtSampledAt = now.plusSeconds(5);
    NodeId dsIngress = NodeId.of("SURC:S:JBS:1:001");
    NodeId mtIngress = NodeId.of("SURC:S:JBS:3:001");
    NodeId switcher = NodeId.of("SWITCHER:Towny:-520:77:1390");
    NodeId exit = NodeId.of("SURC:JBS:CSB:2:001");
    RailEdge dsEntry =
        new RailEdge(
            EdgeId.undirected(dsIngress, switcher),
            dsIngress,
            switcher,
            4,
            -1.0,
            true,
            Optional.empty());
    RailEdge mtEntry =
        new RailEdge(
            EdgeId.undirected(mtIngress, switcher),
            mtIngress,
            switcher,
            4,
            -1.0,
            true,
            Optional.empty());
    RailEdge egress =
        new RailEdge(
            EdgeId.undirected(switcher, exit), switcher, exit, 4, -1.0, true, Optional.empty());
    OccupancyResource switcherConflict =
        OccupancyResource.forConflict("switcher:" + switcher.value());
    OccupancyResource switcherNode = OccupancyResource.forNode(switcher);
    OccupancyRequest dsRequest =
        switcherTraversalRequest(
            "SURC-DS-LW-5912",
            now,
            0,
            List.of(dsIngress, switcher, exit),
            List.of(dsEntry, egress),
            List.of(switcherConflict, switcherNode));
    OccupancyRequest mtRequest =
        switcherTraversalRequest(
            "SURC-MT-LO-3495",
            now,
            0,
            List.of(mtIngress, switcher, exit),
            List.of(mtEntry, egress),
            List.of(switcherConflict, switcherNode));
    OccupancyDecision dsBlocked = blockedBy(switcherNode, "SURC-MT-LO-3495", now);
    OccupancyDecision mtBlocked = blockedBy(switcherConflict, "SURC-DS-LW-5912", mtSampledAt);

    updateBlockerSnapshot(service, "SURC-DS-LW-5912", dsBlocked, dsRequest, now, "test-rich");
    updateBlockerSnapshot(
        service, "SURC-MT-LO-3495", mtBlocked, mtRequest, mtSampledAt, "test-rich");
    updateBlockerSnapshotWithoutRequest(
        service, "SURC-DS-LW-5912", dsBlocked, mtSampledAt.plusMillis(1));

    List<SmartWaitForPlanner.InputEdge> edges =
        smartPlannerInputEdges(
            service,
            Map.of(),
            Set.of("SURC-DS-LW-5912", "SURC-MT-LO-3495"),
            mtSampledAt.plusMillis(2),
            10_000L);

    assertEquals(2, edges.size());
    assertTrue(
        edges.stream()
            .allMatch(
                edge ->
                    edge.switcherMovement()
                        .filter(
                            movement ->
                                movement.relation() == SwitcherMovementTopology.Relation.MERGE)
                        .isPresent()),
        () -> "edges=" + edges);

    Instant oneSidedStaleAt = now.plusSeconds(11);
    updateBlockerSnapshotWithoutRequest(service, "SURC-DS-LW-5912", dsBlocked, oneSidedStaleAt);
    updateBlockerSnapshotWithoutRequest(service, "SURC-MT-LO-3495", mtBlocked, oneSidedStaleAt);
    List<SmartWaitForPlanner.InputEdge> oneSidedStaleEdges =
        smartPlannerInputEdges(
            service,
            Map.of(),
            Set.of("SURC-DS-LW-5912", "SURC-MT-LO-3495"),
            oneSidedStaleAt.plusMillis(1),
            10_000L);

    assertTrue(
        oneSidedStaleEdges.stream().noneMatch(edge -> edge.switcherMovement().isPresent()),
        () -> "oneSidedStaleEdges=" + oneSidedStaleEdges);

    updateBlockerSnapshotWithoutRequest(service, "SURC-DS-LW-5912", dsBlocked, now.plusSeconds(20));
    updateBlockerSnapshotWithoutRequest(service, "SURC-MT-LO-3495", mtBlocked, now.plusSeconds(20));
    List<SmartWaitForPlanner.InputEdge> refreshedEdges =
        smartPlannerInputEdges(
            service,
            Map.of(),
            Set.of("SURC-DS-LW-5912", "SURC-MT-LO-3495"),
            now.plusSeconds(20).plusMillis(1),
            10_000L);

    assertTrue(
        refreshedEdges.stream().noneMatch(edge -> edge.switcherMovement().isPresent()),
        () -> "refreshedEdges=" + refreshedEdges);
  }

  @Test
  void oppositeDirectionSingleRegionBlocksUnlockAuthority() throws Exception {
    PlannerExecutionSnapshot snapshot =
        executeSingleRegionPlannerReservationWithExternalOccupant(CorridorDirection.B_TO_A);

    assertEquals(snapshot.occupancyVersionBefore(), snapshot.manager().version());
    assertFalse(hasUnlockReservationClaim(snapshot.manager()));
    assertEquals(0, movementTokenCount(snapshot.service()));
    assertTrue(hasHardBarrierLog(snapshot.debugMessages()));
    assertFalse(
        snapshot.debugMessages().stream()
            .anyMatch(message -> message.contains("SMART_UNLOCK_AUTHORITY_ISSUED")));
    assertEquals(1, snapshot.manager().snapshotClaims().size());
    assertEquals("external", snapshot.manager().snapshotClaims().get(0).trainName());
  }

  @Test
  void unknownDirectionSingleRegionBlocksUnlockAuthority() throws Exception {
    PlannerExecutionSnapshot snapshot =
        executeSingleRegionPlannerReservationWithExternalOccupant(CorridorDirection.UNKNOWN);

    assertFalse(hasUnlockReservationClaim(snapshot.manager()));
    assertEquals(0, movementTokenCount(snapshot.service()));
    assertTrue(hasHardBarrierLog(snapshot.debugMessages()));
    assertFalse(
        snapshot.debugMessages().stream()
            .anyMatch(message -> message.contains("SMART_UNLOCK_AUTHORITY_ISSUED")));
  }

  @Test
  void smartUnlockProgressWindowDriftRollsBackWithoutSuccess() throws Exception {
    List<String> debugMessages = new ArrayList<>();
    RouteProgressRegistry registry = smartUnlockProgressRegistry();
    RuntimeDispatchService service =
        createMinimalService(
            mockOccupancyManager(), mock(RouteDefinitionCache.class), registry, debugMessages);
    OccupancyResource resource = OccupancyResource.forNode(NodeId.of("B"));
    SmartWaitForPlanner.UnlockCandidate candidate = smartUnlockCandidate();
    installSmartUnlockReservation(service, candidate, List.of(resource), currentTraceTick(), 1000);
    installSmartUnlockBlockerSnapshot(service, "follower", "train-1", "NODE:B", Instant.now());

    registry.updateLastPassedGraphNode("train-1", NodeId.of("B"), Instant.now());
    observeSmartUnlockReservations(service, Instant.now());

    assertFalse(
        debugMessages.stream()
            .anyMatch(message -> message.contains("SMART_UNLOCK_RESERVATION_SUCCESS")));
    assertTrue(
        debugMessages.stream()
            .anyMatch(
                message ->
                    message.contains("SMART_UNLOCK_RESERVATION_ROLLBACK")
                        && message.contains("reason=canonical-progress-window-moved")));
    assertFalse(service.hasActiveSmartUnlockReservation("train-1"));
  }

  @Test
  void smartUnlockSuccessRequiresReleasedBlocker() throws Exception {
    List<String> debugMessages = new ArrayList<>();
    RouteProgressRegistry registry = smartUnlockProgressRegistry();
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    RuntimeDispatchService service =
        createMinimalService(manager, mock(RouteDefinitionCache.class), registry, debugMessages);
    SmartWaitForPlanner.UnlockCandidate candidate = smartUnlockCandidate();
    NodeId a = NodeId.of("A");
    NodeId b = NodeId.of("B");
    NodeId authorityBoundary = NodeId.of("A-MID");
    OccupancyResource resource = OccupancyResource.forNode(NodeId.of("B"));
    List<OccupancyResource> authorityResources =
        List.of(
            OccupancyResource.forNode(a),
            OccupancyResource.forEdge(EdgeId.undirected(a, authorityBoundary)),
            OccupancyResource.forNode(authorityBoundary));
    assertTrue(
        manager
            .acquire(
                new OccupancyRequest(
                    "train-1", Optional.empty(), Instant.now(), authorityResources, Map.of()))
            .allowed());
    installSmartUnlockReservation(service, candidate, List.of(resource), currentTraceTick(), 1000);
    installMovementToken(
        service,
        new MovementAuthorizationToken(
                "train-1",
                1L,
                Instant.now(),
                a,
                b,
                Optional.of(authorityBoundary),
                1,
                authorityResources,
                SignalAspect.PROCEED)
            .activate("B"));
    installSmartUnlockBlockerSnapshot(service, "follower", "other-train", "NODE:C", Instant.now());

    observeSmartUnlockReservations(service, Instant.now());

    assertTrue(
        debugMessages.stream()
            .anyMatch(message -> message.contains("SMART_UNLOCK_RESERVATION_SUCCESS")));
    assertFalse(service.hasActiveSmartUnlockReservation("train-1"));
  }

  @Test
  void smartUnlockMissingBlockerSnapshotDoesNotLogSuccessOrRememberRelease() throws Exception {
    List<String> debugMessages = new ArrayList<>();
    RouteProgressRegistry registry = smartUnlockProgressRegistry();
    RuntimeDispatchService service =
        createMinimalService(
            mockOccupancyManager(), mock(RouteDefinitionCache.class), registry, debugMessages);
    SmartWaitForPlanner.UnlockCandidate candidate = smartUnlockCandidate();
    installSmartUnlockReservation(
        service,
        candidate,
        List.of(OccupancyResource.forNode(NodeId.of("B"))),
        currentTraceTick(),
        1000);

    observeSmartUnlockReservations(service, Instant.now());

    assertTrue(
        debugMessages.stream()
            .anyMatch(
                message ->
                    message.contains("SMART_UNLOCK_RELEASE_EVIDENCE_UNKNOWN")
                        && message.contains("reason=BLOCKER_SNAPSHOT_MISSING")));
    assertFalse(
        debugMessages.stream()
            .anyMatch(message -> message.contains("SMART_UNLOCK_RESERVATION_SUCCESS")));
    assertFalse(service.recentSmartUnlockBlockerRelease("train-1", Duration.ofSeconds(30)));
    assertFalse(service.recentSmartUnlockBlockerRelease("follower", Duration.ofSeconds(30)));
    assertTrue(service.hasActiveSmartUnlockReservation("train-1"));
  }

  @Test
  void smartUnlockInvalidTokenNeverLogsSuccess() throws Exception {
    List<String> debugMessages = new ArrayList<>();
    RouteProgressRegistry registry = smartUnlockProgressRegistry();
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    RuntimeDispatchService service =
        createMinimalService(manager, mock(RouteDefinitionCache.class), registry, debugMessages);
    SmartWaitForPlanner.UnlockCandidate candidate = smartUnlockCandidate();
    OccupancyResource resource = OccupancyResource.forNode(NodeId.of("B"));
    assertTrue(
        manager
            .acquire(
                new OccupancyRequest(
                    "train-1", Optional.empty(), Instant.now(), List.of(resource), Map.of()))
            .allowed());
    installSmartUnlockReservation(service, candidate, List.of(resource), currentTraceTick(), 1000);
    installMovementToken(
        service,
        new MovementAuthorizationToken(
                "train-1",
                1L,
                Instant.now(),
                NodeId.of("A"),
                NodeId.of("B"),
                List.of(resource),
                SignalAspect.PROCEED)
            .activate("B"));
    installMovementInhibitor(service, "train-1");

    observeSmartUnlockReservations(service, Instant.now());

    assertTrue(
        debugMessages.stream()
            .anyMatch(message -> message.contains("SMART_UNLOCK_AUTHORITY_INVALID_NO_RELEASE")));
    assertFalse(
        debugMessages.stream()
            .anyMatch(message -> message.contains("SMART_UNLOCK_RESERVATION_SUCCESS")));
    assertFalse(service.hasActiveSmartUnlockReservation("train-1"));
  }

  @Test
  void smartUnlockNoReleaseTimeoutRollsBackAndStartsPlanCooldown() throws Exception {
    List<String> debugMessages = new ArrayList<>();
    RouteProgressRegistry registry = smartUnlockProgressRegistry();
    RuntimeDispatchService service =
        createMinimalService(
            mockOccupancyManager(), mock(RouteDefinitionCache.class), registry, debugMessages);
    OccupancyResource resource = OccupancyResource.forNode(NodeId.of("B"));
    SmartWaitForPlanner.UnlockCandidate candidate = smartUnlockCandidate();
    installSmartUnlockReservation(
        service, candidate, List.of(resource), currentTraceTick() - 10L, 1);
    installSmartUnlockBlockerSnapshot(service, "follower", "train-1", "NODE:B", Instant.now());

    observeSmartUnlockReservations(service, Instant.now());

    assertTrue(
        debugMessages.stream()
            .anyMatch(message -> message.contains("SMART_UNLOCK_RESERVATION_NO_RELEASE_TIMEOUT")));
    assertTrue(
        debugMessages.stream()
            .anyMatch(message -> message.contains("SMART_UNLOCK_RESERVATION_ROLLBACK")));
    assertTrue(
        debugMessages.stream()
            .anyMatch(
                message ->
                    message.contains("SMART_UNLOCK_PLAN_APPLY")
                        && message.contains("applyPhase=rollback")
                        && message.contains("applyResult=rolled-back")
                        && message.contains("failureReason=no-release-timeout")));
    assertFalse(
        debugMessages.stream()
            .anyMatch(message -> message.contains("SMART_UNLOCK_RESERVATION_SUCCESS")));
    assertFalse(service.hasActiveSmartUnlockReservation("train-1"));
    assertTrue(smartUnlockNoReleaseCooldownActive(service, candidate));
    SmartWaitForPlanner.UnlockCandidate adjacentWindowCandidate = smartUnlockCandidate(101);
    assertFalse(candidate.planHash().equals(adjacentWindowCandidate.planHash()));
    assertTrue(smartUnlockNoReleaseCooldownActive(service, adjacentWindowCandidate));
    ConfigManager.SmartDispatcherPlannerSettings settings =
        new ConfigManager.SmartDispatcherPlannerSettings(
            true,
            SmartDispatcherPlannerMode.ENFORCE_MINIMAL_FORWARD,
            4,
            100,
            1000L,
            true,
            false,
            false,
            true);
    assertEquals(
        "NO_RELEASE_COOLDOWN",
        smartDispatchExecutorSkipReason(service, adjacentWindowCandidate, settings));
  }

  @Test
  void missingLiveBlockerSnapshotReturnsEmptyWithoutFailureNoise() {
    List<String> debugMessages = new ArrayList<>();
    RuntimeDispatchService service =
        createMinimalService(
            mockOccupancyManager(),
            mock(RouteDefinitionCache.class),
            new RouteProgressRegistry(),
            debugMessages);

    assertTrue(
        service
            .recentDeadlockBlockers("never-blocked", Duration.ofSeconds(30))
            .blockers()
            .isEmpty());
    assertTrue(
        service
            .recentDeadlockBlockers("never-blocked", Duration.ofSeconds(30))
            .blockers()
            .isEmpty());
    assertFalse(
        debugMessages.stream()
            .anyMatch(
                message ->
                    message.contains("SMART_LIVE_BLOCKER_SNAPSHOT_REJECTED")
                        && message.contains("reason=missing")),
        debugMessages.toString());
  }

  @Test
  void runtimeMoveWithoutProgressStillRejectsLiveBlockerSnapshot() throws Exception {
    List<String> debugMessages = new ArrayList<>();
    RouteProgressRegistry registry = new RouteProgressRegistry();
    RuntimeDispatchService service =
        createMinimalService(
            mockOccupancyManager(), mock(RouteDefinitionCache.class), registry, debugMessages);
    RouteId routeId = RouteId.of("runtime-progress-required");
    OccupancyResource resource = OccupancyResource.forNode(NodeId.of("B"));
    OccupancyRequest request =
        progressRequest(
            "runtime-train", routeId, 0, NodeId.of("A"), NodeId.of("A"), 0L, List.of(resource));
    OccupancyClaim blocker =
        new OccupancyClaim(
            resource,
            "front-train",
            Optional.of(routeId),
            Instant.now(),
            Duration.ZERO,
            Optional.empty(),
            ClaimRole.MOVEMENT_REQUIRED);
    OccupancyDecision decision =
        new OccupancyDecision(false, Instant.now(), SignalAspect.STOP, List.of(blocker));

    updateLiveBlockerSnapshot(service, "runtime-train", decision, request, Instant.now());

    assertTrue(
        service
            .recentDeadlockBlockers("runtime-train", Duration.ofSeconds(30))
            .blockers()
            .isEmpty());
    assertTrue(
        debugMessages.stream()
            .anyMatch(
                message ->
                    message.contains("SMART_LIVE_BLOCKER_SNAPSHOT_REJECTED")
                        && message.contains("reason=PROGRESS_CONTEXT_MISSING")
                        && message.contains("train=runtime-train")),
        debugMessages.toString());
  }

  @Test
  void staleProgressContextDoesNotUpdateLiveBlockerSnapshot() throws Exception {
    List<String> debugMessages = new ArrayList<>();
    RouteDefinition route =
        new RouteDefinition(
            RouteId.of("stale-progress"),
            List.of(NodeId.of("A"), NodeId.of("B"), NodeId.of("C"), NodeId.of("D")),
            Optional.empty());
    TagStore tags =
        new TagStore(
            "train-1",
            "FTA_OPERATOR_CODE=op",
            "FTA_LINE_CODE=l1",
            "FTA_ROUTE_CODE=r1",
            "FTA_ROUTE_INDEX=3");
    RouteProgressRegistry registry = new RouteProgressRegistry();
    registry.initFromTags("train-1", tags.properties(), route);
    RuntimeDispatchService service =
        createMinimalService(
            mockOccupancyManager(), mock(RouteDefinitionCache.class), registry, debugMessages);
    OccupancyResource resource = OccupancyResource.forNode(NodeId.of("C"));
    OccupancyRequest request =
        staleProgressRequest("train-1", route.id(), 2, registry.version() - 1L, List.of(resource));
    OccupancyClaim blocker =
        new OccupancyClaim(
            resource,
            "blocker",
            Optional.of(route.id()),
            Instant.now(),
            Duration.ZERO,
            Optional.empty(),
            ClaimRole.MOVEMENT_REQUIRED);
    OccupancyDecision decision =
        new OccupancyDecision(false, Instant.now(), SignalAspect.STOP, List.of(blocker));

    updateLiveBlockerSnapshot(service, "train-1", decision, request, Instant.now());

    assertTrue(
        service.recentDeadlockBlockers("train-1", Duration.ofSeconds(30)).blockers().isEmpty());
    assertTrue(
        debugMessages.stream()
            .anyMatch(
                message ->
                    message.contains("SMART_LIVE_BLOCKER_SNAPSHOT_REJECTED")
                        && message.contains("reason=STALE_PROGRESS_CONTEXT")
                        && message.contains("requestCurrentIndex=2")
                        && message.contains("currentIndex=3")));
  }

  @Test
  void liveBlockerSnapshotExpiresWhenBlockedTrainAdvancesPastRequestWindow() throws Exception {
    List<String> debugMessages = new ArrayList<>();
    RouteDefinition route =
        new RouteDefinition(
            RouteId.of("moving-blocker-window"),
            List.of(NodeId.of("A"), NodeId.of("B"), NodeId.of("C")),
            Optional.empty());
    TagStore tags = new TagStore("train-1", "FTA_ROUTE_INDEX=0");
    RouteProgressRegistry registry = new RouteProgressRegistry();
    registry.initFromTags("train-1", tags.properties(), route);
    RuntimeDispatchService service =
        createMinimalService(
            mockOccupancyManager(), mock(RouteDefinitionCache.class), registry, debugMessages);
    OccupancyResource resource = OccupancyResource.forNode(NodeId.of("B"));
    OccupancyRequest request =
        progressRequest(
            "train-1",
            route.id(),
            0,
            NodeId.of("A"),
            NodeId.of("A"),
            registry.version(),
            List.of(resource));
    OccupancyClaim blocker =
        new OccupancyClaim(
            resource,
            "front-train",
            Optional.of(route.id()),
            Instant.now(),
            Duration.ZERO,
            Optional.empty(),
            ClaimRole.MOVEMENT_REQUIRED);
    OccupancyDecision decision =
        new OccupancyDecision(false, Instant.now(), SignalAspect.STOP, List.of(blocker));

    updateLiveBlockerSnapshot(service, "train-1", decision, request, Instant.now());
    assertFalse(
        service.recentDeadlockBlockers("train-1", Duration.ofSeconds(30)).blockers().isEmpty());

    registry.advance("train-1", null, route, 1, tags.properties(), Instant.now());

    assertTrue(
        service.recentDeadlockBlockers("train-1", Duration.ofSeconds(30)).blockers().isEmpty());
    assertTrue(
        debugMessages.stream()
            .anyMatch(
                message ->
                    message.contains("SMART_LIVE_BLOCKER_SNAPSHOT_REJECTED")
                        && message.contains("reason=PROGRESS_WINDOW_MOVED")
                        && message.contains("snapshotCurrentIndex=0")
                        && message.contains("currentIndex=1")),
        debugMessages.toString());
  }

  @Test
  void staleProgressContextDoesNotOverwriteSnapshotAfterIntermediateNodeAdvance() throws Exception {
    List<String> debugMessages = new ArrayList<>();
    RouteDefinition route =
        new RouteDefinition(
            RouteId.of("moving-intermediate-window"),
            List.of(NodeId.of("A"), NodeId.of("B")),
            Optional.empty());
    TagStore tags = new TagStore("train-1", "FTA_ROUTE_INDEX=0");
    RouteProgressRegistry registry = new RouteProgressRegistry();
    registry.initFromTags("train-1", tags.properties(), route);
    RuntimeDispatchService service =
        createMinimalService(
            mockOccupancyManager(), mock(RouteDefinitionCache.class), registry, debugMessages);
    OccupancyResource resource = OccupancyResource.forNode(NodeId.of("MID-1"));
    OccupancyRequest staleRequest =
        progressRequest(
            "train-1",
            route.id(),
            0,
            NodeId.of("A"),
            NodeId.of("A"),
            registry.version(),
            List.of(resource));
    OccupancyClaim blocker =
        new OccupancyClaim(
            resource,
            "front-train",
            Optional.of(route.id()),
            Instant.now(),
            Duration.ZERO,
            Optional.empty(),
            ClaimRole.MOVEMENT_REQUIRED);
    OccupancyDecision decision =
        new OccupancyDecision(false, Instant.now(), SignalAspect.STOP, List.of(blocker));

    registry.updateLastPassedGraphNode("train-1", NodeId.of("MID-1"), Instant.now());
    updateLiveBlockerSnapshot(service, "train-1", decision, staleRequest, Instant.now());

    assertTrue(
        service.recentDeadlockBlockers("train-1", Duration.ofSeconds(30)).blockers().isEmpty());
    assertTrue(
        debugMessages.stream()
            .anyMatch(
                message ->
                    message.contains("SMART_LIVE_BLOCKER_SNAPSHOT_REJECTED")
                        && message.contains("reason=STALE_PROGRESS_CONTEXT")
                        && message.contains("requestLastPassed=A")
                        && message.contains("currentLastPassed=MID-1")),
        debugMessages.toString());
  }

  @Test
  void selfOwnedContinuationBlockerSnapshotKeepsExternalSingleOwner() throws Exception {
    List<String> debugMessages = new ArrayList<>();
    RouteDefinition route =
        new RouteDefinition(
            RouteId.of("continuation-live-blocker"),
            List.of(NodeId.of("A"), NodeId.of("B")),
            Optional.empty());
    RouteProgressRegistry registry = new RouteProgressRegistry();
    registry.initFromTags(
        "train-1", new TagStore("train-1", "FTA_ROUTE_INDEX=0").properties(), route);
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    RuntimeDispatchService service =
        createMinimalService(
            manager,
            mock(RouteDefinitionCache.class),
            registry,
            debugMessages,
            SmartDispatcherMode.ENFORCE);
    OccupancyResource conflict = OccupancyResource.forConflict("single:test:A~B");
    OccupancyRequest retain =
        singleConflictRequest("train-1", conflict, CorridorDirection.A_TO_B)
            .withResourceIntents(Map.of(conflict, ResourceIntent.PROTECTIVE_RETAIN));
    assertTrue(manager.acquire(retain).allowed());
    assertTrue(
        manager
            .acquire(singleConflictRequest("leader", conflict, CorridorDirection.A_TO_B))
            .allowed());
    OccupancyRequest request = singleConflictRequestWithoutPlanDirection("train-1", conflict);
    OccupancyDecision decision = manager.canEnter(request);
    assertFalse(decision.allowed());
    assertTrue(
        decision.blockers().stream().anyMatch(claim -> claim.trainName().equals("leader")),
        () -> "blockers=" + decision.blockers());

    updateLiveBlockerSnapshot(service, "train-1", decision, request, Instant.now());

    RuntimeDispatchService.DeadlockBlockerSnapshot snapshot =
        service.recentDeadlockBlockers("train-1", Duration.ofSeconds(30));
    assertTrue(
        snapshot.blockers().stream()
            .anyMatch(
                blocker ->
                    blocker.trainName().equals("leader")
                        && blocker.conflictKey().equals("single:test:A~B")
                        && blocker.direction().orElse(CorridorDirection.UNKNOWN)
                            == CorridorDirection.A_TO_B),
        () -> "snapshot=" + snapshot.blockers());
    assertTrue(
        debugMessages.stream()
            .anyMatch(
                message ->
                    message.contains("SMART_LIVE_BLOCKER_SNAPSHOT_UPDATED")
                        && message.contains("blockerTrain=leader")),
        debugMessages.toString());
    assertFalse(
        debugMessages.stream().anyMatch(message -> message.contains("SNAPSHOT_USED")),
        "读取仍然有效的 blocker 快照不得伪造新的诊断状态迁移: " + debugMessages);
  }

  // ====== recentBlockerTrains 测试 ======

  @Test
  void recentBlockerTrainsReturnsEmptyForUnknownTrain() {
    RuntimeDispatchService service = createMinimalService();
    Set<String> blockers = service.recentBlockerTrains("unknown-train", Duration.ofSeconds(30));
    assertTrue(blockers.isEmpty());
  }

  @Test
  void recentBlockerTrainsExpiresStaleSnapshot() throws Exception {
    RuntimeDispatchService service = createMinimalService();
    java.lang.reflect.Field field =
        RuntimeDispatchService.class.getDeclaredField("blockerSnapshots");
    field.setAccessible(true);
    @SuppressWarnings("unchecked")
    java.util.concurrent.ConcurrentMap<String, Object> blockerSnapshots =
        (java.util.concurrent.ConcurrentMap<String, Object>) field.get(service);
    Class<?> snapshotClass =
        Class.forName(
            "org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchService$BlockerSnapshot");
    java.lang.reflect.Constructor<?> constructor =
        snapshotClass.getDeclaredConstructor(Set.class, Instant.class);
    constructor.setAccessible(true);
    Object snapshot =
        constructor.newInstance(Set.of("front-train"), Instant.now().minusSeconds(120));
    blockerSnapshots.put("train-1", snapshot);

    assertTrue(service.recentBlockerTrains("train-1", Duration.ofSeconds(30)).isEmpty());
  }

  // ====== getTrainState 测试 ======

  @Test
  void getTrainStateReturnsEmptyForNullOrBlankName() {
    RuntimeDispatchService service = createMinimalService();
    assertTrue(service.getTrainState(null).isEmpty());
    assertTrue(service.getTrainState("").isEmpty());
    assertTrue(service.getTrainState("  ").isEmpty());
  }

  @Test
  void getTrainStateReturnsEmptyForUnknownTrain() {
    RuntimeDispatchService service = createMinimalService();
    assertTrue(service.getTrainState("nonexistent").isEmpty());
  }

  @Test
  void getTrainStateUsesAliasAwareResolution() {
    RuntimeDispatchService service;
    RouteDefinition route =
        new RouteDefinition(
            RouteId.of("r"), List.of(NodeId.of("A"), NodeId.of("B")), Optional.empty());
    TagStore tags =
        new TagStore(
            "tc-current",
            "FTA_TRAIN_NAME=logic-main",
            "FTA_OPERATOR_CODE=op",
            "FTA_LINE_CODE=l1",
            "FTA_ROUTE_CODE=r1",
            "FTA_ROUTE_INDEX=0");
    RouteProgressRegistry registry = new RouteProgressRegistry();
    registry.initFromTags("logic-main", tags.properties(), route);
    registry.updateSignal("logic-main", SignalAspect.STOP, Instant.now());
    service =
        createMinimalService(
            mockOccupancyManager(), mock(RouteDefinitionCache.class), registry, new ArrayList<>());

    try (MockedStatic<TrainPropertiesStore> store = mockStatic(TrainPropertiesStore.class)) {
      store.when(() -> TrainPropertiesStore.get(anyString())).thenReturn(null);
      store.when(TrainPropertiesStore::getAll).thenReturn(List.of(tags.properties()));

      Optional<RuntimeDispatchService.TrainRuntimeState> state =
          service.getTrainState("logic-main~a");

      assertTrue(state.isPresent(), "split alias 应解析到 active runtime state");
      assertEquals("logic-main", state.orElseThrow().trainName());
      assertEquals(SignalAspect.STOP, state.orElseThrow().signalAspect());
    }
  }

  @Test
  void deadlockTrainContextUsesAliasAwareResolution() {
    RouteDefinition route =
        new RouteDefinition(
            RouteId.of("r"), List.of(NodeId.of("A"), NodeId.of("B")), Optional.empty());
    TagStore tags =
        new TagStore(
            "tc-current",
            "FTA_TRAIN_NAME=logic-main",
            "FTA_OPERATOR_CODE=op",
            "FTA_LINE_CODE=l1",
            "FTA_ROUTE_CODE=r1",
            "FTA_ROUTE_INDEX=0");
    RouteProgressRegistry registry = new RouteProgressRegistry();
    registry.initFromTags("logic-main", tags.properties(), route);
    registry.updateSignal("logic-main", SignalAspect.STOP, Instant.now());
    RouteDefinitionCache routes = mock(RouteDefinitionCache.class);
    when(routes.findByCodes("op", "l1", "r1")).thenReturn(Optional.of(route));
    RuntimeDispatchService service =
        createMinimalService(mockOccupancyManager(), routes, registry, new ArrayList<>());

    try (MockedStatic<TrainPropertiesStore> store = mockStatic(TrainPropertiesStore.class)) {
      store.when(() -> TrainPropertiesStore.get(anyString())).thenReturn(null);
      store.when(TrainPropertiesStore::getAll).thenReturn(List.of(tags.properties()));

      Optional<RuntimeDispatchService.DeadlockTrainContext> context =
          service.deadlockTrainContext("logic-main~a");

      assertTrue(context.isPresent(), "deadlock context lookup 应与 destroy 共用 alias 解析");
      assertEquals("logic-main", context.orElseThrow().trainName());
      assertEquals(2, context.orElseThrow().routeSize());
    }
  }

  @Test
  void forceRelaunchCannotBypassOppositeSingleRegionHardBarrier() throws Exception {
    List<String> debugMessages = new ArrayList<>();
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
    RouteProgressRegistry registry = new RouteProgressRegistry();
    registry.initFromTags("train-1", tags.properties(), route);
    registry.updateSignal("train-1", SignalAspect.PROCEED, Instant.now());
    RouteDefinitionCache routes = mock(RouteDefinitionCache.class);
    when(routes.findByCodes("op", "l1", "r1")).thenReturn(Optional.of(route));
    RuntimeDispatchService service =
        createMinimalService(mockOccupancyManager(), routes, registry, debugMessages);
    installSmartUnlockBlockerSnapshot(
        service, "train-1", "external", "CONFLICT:single:test:A~B", Instant.now());

    try (MockedStatic<TrainPropertiesStore> store = mockStatic(TrainPropertiesStore.class)) {
      store.when(() -> TrainPropertiesStore.get("train-1")).thenReturn(tags.properties());
      store.when(TrainPropertiesStore::getAll).thenReturn(List.of(tags.properties()));

      assertFalse(service.forceRelaunchByName("train-1"));
    }

    assertEquals(SignalAspect.PROCEED, registry.get("train-1").orElseThrow().lastSignal());
    assertTrue(debugMessages.stream().noneMatch(message -> message.contains("relaunch delegated")));
    verify(tags.properties(), never()).setDestination(any());
  }

  @Test
  void forceRelaunchRequiresActiveTokenOrFreshAuthority() {
    List<String> debugMessages = new ArrayList<>();
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
    RouteProgressRegistry registry = new RouteProgressRegistry();
    registry.initFromTags("train-1", tags.properties(), route);
    registry.updateSignal("train-1", SignalAspect.PROCEED, Instant.now());
    RouteDefinitionCache routes = mock(RouteDefinitionCache.class);
    when(routes.findByCodes("op", "l1", "r1")).thenReturn(Optional.of(route));
    RuntimeDispatchService service =
        createMinimalService(mockOccupancyManager(), routes, registry, debugMessages);

    try (MockedStatic<TrainPropertiesStore> store = mockStatic(TrainPropertiesStore.class)) {
      store.when(() -> TrainPropertiesStore.get("train-1")).thenReturn(tags.properties());
      store.when(TrainPropertiesStore::getAll).thenReturn(List.of(tags.properties()));

      assertFalse(service.forceRelaunchByName("train-1"));
    }

    assertTrue(debugMessages.stream().noneMatch(message -> message.contains("relaunch delegated")));
    verify(tags.properties(), never()).setDestination(any());
  }

  @Test
  void reapplyHardStopByNameUsesAliasAwareResolution() {
    List<String> debugMessages = new ArrayList<>();
    RouteDefinition route =
        new RouteDefinition(
            RouteId.of("r"), List.of(NodeId.of("A"), NodeId.of("B")), Optional.empty());
    TagStore tags =
        new TagStore(
            "tc-current",
            "FTA_TRAIN_NAME=logic-main",
            "FTA_OPERATOR_CODE=op",
            "FTA_LINE_CODE=l1",
            "FTA_ROUTE_CODE=r1",
            "FTA_ROUTE_INDEX=0");
    RouteProgressRegistry registry = new RouteProgressRegistry();
    registry.initFromTags("logic-main", tags.properties(), route);
    registry.updateSignal("logic-main", SignalAspect.STOP, Instant.now());
    RouteDefinitionCache routes = mock(RouteDefinitionCache.class);
    when(routes.findByCodes("op", "l1", "r1")).thenReturn(Optional.of(route));
    RuntimeDispatchService service =
        createMinimalService(mockOccupancyManager(), routes, registry, debugMessages);

    try (MockedStatic<TrainPropertiesStore> store = mockStatic(TrainPropertiesStore.class)) {
      store.when(() -> TrainPropertiesStore.get(anyString())).thenReturn(null);
      store.when(TrainPropertiesStore::getAll).thenReturn(List.of(tags.properties()));

      assertTrue(
          service.reapplyHardStopByName("logic-main~a", "health-deadlock-confirmed"),
          "hard-stop bridge 应接受 split alias");
    }

    assertTrue(
        debugMessages.stream()
            .anyMatch(
                message -> message.contains("HealthMonitor reapplyHardStop: train=logic-main")));
    verify(tags.properties(), atLeastOnce()).setSpeedLimit(0.0);
  }

  @Test
  void refreshSignalByNameUsesAliasAwareResolution() {
    List<String> debugMessages = new ArrayList<>();
    RouteDefinition route =
        new RouteDefinition(
            RouteId.of("r"), List.of(NodeId.of("A"), NodeId.of("B")), Optional.empty());
    TagStore tags =
        new TagStore(
            "tc-current",
            "FTA_TRAIN_NAME=logic-main",
            "FTA_OPERATOR_CODE=op",
            "FTA_LINE_CODE=l1",
            "FTA_ROUTE_CODE=r1",
            "FTA_ROUTE_INDEX=0");
    RouteProgressRegistry registry = new RouteProgressRegistry();
    registry.initFromTags("logic-main", tags.properties(), route);
    RuntimeDispatchService service =
        createMinimalService(
            mockOccupancyManager(), mock(RouteDefinitionCache.class), registry, debugMessages);

    try (MockedStatic<TrainPropertiesStore> store = mockStatic(TrainPropertiesStore.class)) {
      store.when(() -> TrainPropertiesStore.get(anyString())).thenReturn(null);
      store.when(TrainPropertiesStore::getAll).thenReturn(List.of(tags.properties()));

      service.refreshSignalByName("logic-main~a");
    }

    assertTrue(
        debugMessages.stream()
            .anyMatch(
                message ->
                    message.contains("DEADLOCK_RESOLVE_FAILED")
                        && message.contains("purpose=REFRESH_SIGNAL")
                        && message.contains("resolvedName=logic-main")
                        && message.contains("failureReason=ENTITY_NOT_FOUND")),
        "refresh 已解析到 alias 后才因实体缺失失败");
  }

  @Test
  void signalReevaluationFailureClosesAuthorityGateAndRequestsRecovery() {
    List<String> debugMessages = new ArrayList<>();
    RuntimeDispatchService service = createMinimalService(mockOccupancyManager(), debugMessages);
    int[] recoveryRequests = {0};
    service.setStartupRecoveryRequestedListener(() -> recoveryRequests[0]++);

    try (MockedStatic<TrainPropertiesStore> store = mockStatic(TrainPropertiesStore.class)) {
      store.when(() -> TrainPropertiesStore.get(anyString())).thenReturn(null);
      store.when(TrainPropertiesStore::getAll).thenReturn(List.of());

      service.failClosedAfterSignalReevaluationFailure("train-abi", new LinkageError("test-abi"));
    }

    assertTrue(service.captureReadyStartupRecoveryEpoch().isEmpty());
    assertEquals(1, recoveryRequests[0]);
    assertTrue(
        debugMessages.stream()
            .anyMatch(
                message ->
                    message.contains("SMART_SIGNAL_REEVALUATION_FAIL_CLOSED")
                        && message.contains("action=STOP_FIRST_RECOVERY")));
  }

  @Test
  void destroyTrainByNameUsesAliasAwareResolution() {
    List<String> debugMessages = new ArrayList<>();
    RouteDefinition route =
        new RouteDefinition(
            RouteId.of("r"), List.of(NodeId.of("A"), NodeId.of("B")), Optional.empty());
    TagStore tags =
        new TagStore(
            "tc-current",
            "FTA_TRAIN_NAME=logic-main",
            "FTA_OPERATOR_CODE=op",
            "FTA_LINE_CODE=l1",
            "FTA_ROUTE_CODE=r1",
            "FTA_ROUTE_INDEX=0");
    RouteProgressRegistry registry = new RouteProgressRegistry();
    registry.initFromTags("logic-main", tags.properties(), route);
    RuntimeDispatchService service =
        createMinimalService(
            mockOccupancyManager(), mock(RouteDefinitionCache.class), registry, debugMessages);

    try (MockedStatic<TrainPropertiesStore> store = mockStatic(TrainPropertiesStore.class)) {
      store.when(() -> TrainPropertiesStore.get(anyString())).thenReturn(null);
      store.when(TrainPropertiesStore::getAll).thenReturn(List.of(tags.properties()));

      assertFalse(
          service.destroyTrainByName("logic-main~a", "health-deadlock-timeout"),
          "alias 解析成功但实体缺失时应明确失败，不能 silent/no-op 成功");
    }

    assertTrue(
        debugMessages.stream()
            .anyMatch(
                message ->
                    message.contains("DEADLOCK_DESTROY_ATTEMPTED")
                        && message.contains("requestedName=logic-main~a")
                        && message.contains("resolvedName=logic-main")
                        && message.contains("propertiesFound=true")));
    assertTrue(
        debugMessages.stream()
            .anyMatch(
                message ->
                    message.contains("DEADLOCK_DESTROY_RESULT")
                        && message.contains("success=false")
                        && message.contains("failureReason=ENTITY_NOT_FOUND")));
  }

  @Test
  void destroyTrainByNameDoesNotReportSuccessWhenEntityMissing() {
    List<String> debugMessages = new ArrayList<>();
    RouteDefinition route =
        new RouteDefinition(
            RouteId.of("r"), List.of(NodeId.of("A"), NodeId.of("B")), Optional.empty());
    TagStore tags =
        new TagStore(
            "tc-current",
            "FTA_TRAIN_NAME=logic-main",
            "FTA_OPERATOR_CODE=op",
            "FTA_LINE_CODE=l1",
            "FTA_ROUTE_CODE=r1",
            "FTA_ROUTE_INDEX=0");
    RouteProgressRegistry registry = new RouteProgressRegistry();
    registry.initFromTags("logic-main", tags.properties(), route);
    RuntimeDispatchService service =
        createMinimalService(
            mockOccupancyManager(), mock(RouteDefinitionCache.class), registry, debugMessages);

    try (MockedStatic<TrainPropertiesStore> store = mockStatic(TrainPropertiesStore.class)) {
      store.when(() -> TrainPropertiesStore.get(anyString())).thenReturn(null);
      store.when(TrainPropertiesStore::getAll).thenReturn(List.of(tags.properties()));

      assertFalse(
          service.destroyTrainByName("logic-main~a", "health-deadlock-timeout"),
          "实体不存在时不能把 destroy 伪报成成功");
    }

    assertTrue(
        debugMessages.stream()
            .anyMatch(
                message ->
                    message.contains("DEADLOCK_DESTROY_RESULT")
                        && message.contains("success=false")
                        && message.contains("failureReason=ENTITY_NOT_FOUND")));
  }

  @Test
  void deadlockDestroyLogsResolveFailure() {
    List<String> debugMessages = new ArrayList<>();
    RuntimeDispatchService service = createMinimalService(mockOccupancyManager(), debugMessages);

    try (MockedStatic<TrainPropertiesStore> store = mockStatic(TrainPropertiesStore.class)) {
      store.when(() -> TrainPropertiesStore.get(anyString())).thenReturn(null);
      store.when(TrainPropertiesStore::getAll).thenReturn(List.of());

      assertFalse(service.destroyTrainByName("missing-train", "health-deadlock-timeout"));
    }

    assertTrue(
        debugMessages.stream()
            .anyMatch(
                message ->
                    message.contains("DEADLOCK_RESOLVE_FAILED")
                        && message.contains("requestedName=missing-train")
                        && message.contains("purpose=DESTROY")
                        && message.contains("matchedBy=FAILED")
                        && message.contains("failureReason=RESOLVE_FAILED")));
    assertTrue(
        debugMessages.stream()
            .anyMatch(
                message ->
                    message.contains("DEADLOCK_DESTROY_RESULT")
                        && message.contains("success=false")
                        && message.contains("failureReason=RESOLVE_FAILED")));
  }

  private RuntimeDispatchService createMinimalService() {
    return createMinimalService(mockOccupancyManager(), new ArrayList<>());
  }

  private NodeEdgePlannerFixture nodeEdgePlannerFixture(Instant now) throws Exception {
    return nodeEdgePlannerFixture(now, true);
  }

  private NodeEdgePlannerFixture nodeEdgePlannerFixture(Instant now, boolean includeRouteId)
      throws Exception {
    String trainA = "SURC-MT-LP-5108";
    String trainB = "SURC-MT-LP-7327";
    NodeId a0 = NodeId.of("SURC:S:RVS:1");
    NodeId a1 = NodeId.of("SURC:RVS:LYM:2:001");
    NodeId b0 = NodeId.of("SURC:S:LYM:1");
    NodeId b1 = NodeId.of("SURC:LYM:RVS:2:001");
    RouteDefinition routeA =
        new RouteDefinition(RouteId.of("planner-node-edge-a"), List.of(a0, a1), Optional.empty());
    RouteDefinition routeB =
        new RouteDefinition(RouteId.of("planner-node-edge-b"), List.of(b0, b1), Optional.empty());
    RouteProgressRegistry progressRegistry = new RouteProgressRegistry();
    progressRegistry.initFromTags(
        trainA, new TagStore(trainA, "FTA_ROUTE_INDEX=0").properties(), routeA);
    progressRegistry.initFromTags(
        trainB, new TagStore(trainB, "FTA_ROUTE_INDEX=0").properties(), routeB);
    List<String> debugMessages = new ArrayList<>();
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    ConfigManager.ConfigView base = testConfigView(20, 20.0);
    ConfigManager.ConfigView plannerView =
        new ConfigManager.ConfigView(
            base.configVersion(),
            base.debugEnabled(),
            base.locale(),
            base.storageSettings(),
            base.graphSettings(),
            base.autoStationSettings(),
            base.runtimeSettings(),
            base.spawnSettings(),
            base.trainConfigSettings(),
            base.reclaimSettings(),
            new ConfigManager.SmartDispatcherSettings(
                SmartDispatcherMode.ENFORCE, enforcePlannerSettings()),
            base.healthSettings());
    ConfigManager configManager = mock(ConfigManager.class);
    when(configManager.current()).thenReturn(plannerView);
    RuntimeDispatchService service =
        new RuntimeDispatchService(
            manager,
            mock(RailGraphService.class),
            mock(RouteDefinitionCache.class),
            progressRegistry,
            mock(SignNodeRegistry.class),
            mock(LayoverRegistry.class),
            new DwellRegistry(),
            configManager,
            null,
            new TrainConfigResolver(),
            debugMessages::add);
    service.setSignalReevaluationRequester(
        trainName -> debugMessages.add("TEST_SIGNAL_REEVALUATION_REQUEST " + trainName));
    OccupancyRequest requestA =
        completeTwoNodeAuthorityRequest(
            trainA, now, a0, a1, includeRouteId ? Optional.of(routeA.id()) : Optional.empty());
    OccupancyRequest requestB =
        completeTwoNodeAuthorityRequest(
            trainB, now, b0, b1, includeRouteId ? Optional.of(routeB.id()) : Optional.empty());
    OccupancyDecision decisionA =
        blockedBy(OccupancyResource.forEdge(EdgeId.undirected(b0, b1)), trainB, now);
    OccupancyDecision decisionB = blockedBy(OccupancyResource.forNode(a1), trainA, now);
    updateBlockerSnapshot(service, trainA, decisionA, requestA, now, "test-canonical-plan");
    updateBlockerSnapshot(service, trainB, decisionB, requestB, now, "test-canonical-plan");
    return new NodeEdgePlannerFixture(
        service,
        progressRegistry,
        debugMessages,
        Set.of(trainA, trainB),
        trainA,
        trainB,
        decisionA,
        decisionB,
        now);
  }

  /**
   * 构造日志中“leader 已越过 rear claim、两个 follower 仍被该 claim 阻塞”的 planner 现场。
   *
   * <p>leader 的规范计划保留完整展开路径，但实时进度已经推进到 {@code currentAnchor}；占用层同时保存一条指定角色的 rear NODE/EDGE
   * claim。这样测试不会用前方资源替代真实事故中的保护性尾部窗口。
   */
  private RearProtectionPlannerFixture rearProtectionPlannerFixture(
      String leaderTrain,
      RouteId routeId,
      List<NodeId> routeNodes,
      List<NodeId> movementNodes,
      List<NodeId> physicalPath,
      int currentIndex,
      NodeId currentAnchor,
      OccupancyResource rearResource,
      ClaimRole rearClaimRole)
      throws Exception {
    return rearProtectionPlannerFixture(
        leaderTrain,
        routeId,
        routeNodes,
        movementNodes,
        physicalPath,
        currentIndex,
        currentAnchor,
        rearResource,
        rearClaimRole,
        routeId);
  }

  private RearProtectionPlannerFixture rearProtectionPlannerFixture(
      String leaderTrain,
      RouteId routeId,
      List<NodeId> routeNodes,
      List<NodeId> movementNodes,
      List<NodeId> physicalPath,
      int currentIndex,
      NodeId currentAnchor,
      OccupancyResource rearResource,
      ClaimRole rearClaimRole,
      RouteId rearClaimRouteId)
      throws Exception {
    Instant now = Instant.now();
    RouteDefinition route = new RouteDefinition(routeId, routeNodes, Optional.empty());
    RouteProgressRegistry progressRegistry = new RouteProgressRegistry();
    progressRegistry.initFromTags(
        leaderTrain,
        new TagStore(leaderTrain, "FTA_ROUTE_INDEX=" + currentIndex).properties(),
        route);
    assertTrue(
        progressRegistry.updateLastPassedGraphNode(
            leaderTrain, currentAnchor, now.minusMillis(1L)));

    List<String> debugMessages = new ArrayList<>();
    SimpleOccupancyManager manager =
        spy(
            new SimpleOccupancyManager(
                (unusedRoute, unusedResource) -> Duration.ZERO,
                SignalAspectPolicy.defaultPolicy()));
    ConfigManager.ConfigView base = testConfigView(20, 20.0);
    ConfigManager.ConfigView plannerView =
        new ConfigManager.ConfigView(
            base.configVersion(),
            base.debugEnabled(),
            base.locale(),
            base.storageSettings(),
            base.graphSettings(),
            base.autoStationSettings(),
            base.runtimeSettings(),
            base.spawnSettings(),
            base.trainConfigSettings(),
            base.reclaimSettings(),
            new ConfigManager.SmartDispatcherSettings(
                SmartDispatcherMode.ENFORCE, enforcePlannerSettings()),
            base.healthSettings());
    ConfigManager configManager = mock(ConfigManager.class);
    when(configManager.current()).thenReturn(plannerView);
    RuntimeDispatchService service =
        new RuntimeDispatchService(
            manager,
            mock(RailGraphService.class),
            mock(RouteDefinitionCache.class),
            progressRegistry,
            mock(SignNodeRegistry.class),
            mock(LayoverRegistry.class),
            new DwellRegistry(),
            configManager,
            null,
            new TrainConfigResolver(),
            debugMessages::add);
    service.setSignalReevaluationRequester(
        trainName -> debugMessages.add("TEST_SIGNAL_REEVALUATION_REQUEST " + trainName));

    OccupancyRequest rearClaimRequest =
        new OccupancyRequest(
            leaderTrain,
            Optional.of(rearClaimRouteId),
            now,
            List.of(rearResource),
            Map.of(),
            Map.of(),
            0,
            AuthorizationPurpose.RUNTIME_MOVE,
            Map.of(),
            Map.of(
                rearResource,
                rearClaimRole == ClaimRole.PROTECTIVE_RETAIN
                    ? ResourceIntent.PROTECTIVE_RETAIN
                    : ResourceIntent.MOVEMENT_REQUIRED));
    assertTrue(manager.acquire(rearClaimRequest).allowed());
    assertTrue(
        manager.snapshotClaims().stream()
            .anyMatch(
                claim ->
                    claim.resource().equals(rearResource)
                        && claim.role() == rearClaimRole
                        && claim.routeId().equals(Optional.of(rearClaimRouteId))));

    OccupancyRequest canonicalRequest =
        new OccupancyRequestBuilder(graphWithConflictFreeLinearPath(physicalPath, 1), 4, 0, 0, 0)
            .buildContextFromNodesWithDirectionContext(
                leaderTrain,
                Optional.of(routeId),
                movementNodes,
                routeNodes,
                currentIndex,
                now,
                0,
                AuthorizationPurpose.RUNTIME_MOVE)
            .orElseThrow()
            .request();
    invokeMarkDirectedRequest(
        service, canonicalRequest, SignalComputationTrace.Source.PERIODIC_TICK);
    OccupancyDecision clear =
        new OccupancyDecision(false, now, SignalAspect.STOP, List.of(), false, "test-clear");
    OccupancyDecision blockedByRear = blockedBy(rearResource, leaderTrain, now, rearClaimRole);
    String followerA = leaderTrain + "-FOLLOWER-A";
    String followerB = leaderTrain + "-FOLLOWER-B";
    updateBlockerSnapshotWithoutRequest(service, leaderTrain, clear, now);
    updateBlockerSnapshotWithoutRequest(service, followerA, blockedByRear, now);
    updateBlockerSnapshotWithoutRequest(service, followerB, blockedByRear, now);
    return new RearProtectionPlannerFixture(
        service, manager, debugMessages, Set.of(leaderTrain, followerA, followerB), now);
  }

  /**
   * 构造把测试中的 successful acquire 视为已提交硬授权的 OccupancyManager 替身。
   *
   * <p>大量运行时测试只关心控车与信号计算，并用 Mockito 直接返回 allowed decision；该替身显式补上生产实现提供的最终 owner 校验能力。需要验证 owner
   * 丢失的用例应自行构造返回 {@code false} 的接口实现。
   */
  private static OccupancyManager mockOccupancyManager() {
    OccupancyManager manager =
        mock(OccupancyManager.class, withSettings().extraInterfaces(AuthorityHandoffSupport.class));
    when(((AuthorityHandoffSupport) manager).holdsHardAuthority(any())).thenReturn(true);
    when(((AuthorityHandoffSupport) manager).migrateAuthorityOwner(anyString(), anyString()))
        .thenReturn(true);
    return manager;
  }

  private RouteProgressRegistry smartUnlockProgressRegistry() {
    RouteDefinition route =
        new RouteDefinition(
            RouteId.of("smart-unlock"),
            List.of(NodeId.of("A"), NodeId.of("B"), NodeId.of("C")),
            Optional.empty());
    TagStore tags =
        new TagStore(
            "train-1",
            "FTA_OPERATOR_CODE=op",
            "FTA_LINE_CODE=l1",
            "FTA_ROUTE_CODE=r1",
            "FTA_ROUTE_INDEX=0");
    RouteProgressRegistry registry = new RouteProgressRegistry();
    registry.initFromTags("train-1", tags.properties(), route);
    registry.updateSignal("train-1", SignalAspect.STOP, Instant.now());
    return registry;
  }

  private SmartWaitForPlanner.UnlockCandidate smartUnlockCandidate() {
    return smartUnlockCandidate(100);
  }

  private SmartWaitForPlanner.UnlockCandidate smartUnlockCandidate(int score) {
    return smartUnlockCandidate("NODE:B", CorridorDirection.A_TO_B, score);
  }

  private SmartWaitForPlanner.UnlockCandidate smartUnlockCandidate(
      String resource, CorridorDirection direction) {
    return smartUnlockCandidate(resource, direction, 100);
  }

  private SmartWaitForPlanner.UnlockCandidate smartUnlockCandidate(
      String resource, CorridorDirection direction, int score) {
    SmartWaitForPlanner.GraphOnlySimulation simulation =
        new SmartWaitForPlanner.GraphOnlySimulation(
            1, 0, true, 1, 0, 1, SmartWaitForPlanner.Confidence.HIGH, "test");
    String safeResource = resource == null || resource.isBlank() ? "NODE:B" : resource;
    CorridorDirection safeDirection = direction == null ? CorridorDirection.UNKNOWN : direction;
    NodeId fromNode = NodeId.of("A");
    NodeId toNode = NodeId.of("B");
    Optional<CanonicalForwardPathEvidence> forwardPathEvidence =
        safeResource.startsWith("NODE:") || safeResource.startsWith("EDGE:")
            ? Optional.of(
                new CanonicalForwardPathEvidence(
                    "train-1",
                    "smart-unlock",
                    0,
                    fromNode.value(),
                    toNode.value(),
                    "-",
                    List.of(
                        new CanonicalForwardPathEvidence.DirectedStep(
                            EdgeId.undirected(fromNode, toNode), fromNode, toNode)),
                    List.of(safeResource),
                    1L,
                    1L,
                    "test-smart-unlock"))
            : Optional.empty();
    return new SmartWaitForPlanner.UnlockCandidate(
        "train-1",
        "cycle-1",
        SmartWaitForPlanner.CandidateKind.FORWARD_TO_RELEASE_BLOCKER,
        List.of(safeResource),
        List.of(safeResource),
        "B",
        "A",
        "B",
        safeDirection,
        1,
        1,
        4,
        score,
        true,
        "-",
        "-",
        simulation,
        true,
        true,
        60,
        forwardPathEvidence);
  }

  private SmartWaitForPlanner.UnlockCandidate smartUnlockCandidate(
      String trainName, String cycleId) {
    SmartWaitForPlanner.UnlockCandidate base = smartUnlockCandidate();
    return new SmartWaitForPlanner.UnlockCandidate(
        trainName,
        cycleId,
        base.kind(),
        base.releaseResources(),
        base.resources(),
        base.authorityEnd(),
        base.currentNode(),
        base.nextNode(),
        base.direction(),
        base.releaseResourceCount(),
        base.fullRouteResourceCount(),
        base.reservationResourceLimit(),
        base.score(),
        base.accepted(),
        base.rejectReason(),
        base.recommendation(),
        base.simulation(),
        base.releasesBottleneck(),
        base.improvesSameLineCascade(),
        base.stuckDurationSeconds(),
        base.forwardPathEvidence());
  }

  private SmartWaitForPlanner.UnlockCandidate headOnYieldCandidate(
      OccupancyResource conflict, OccupancyResource physicalNode) {
    SmartWaitForPlanner.GraphOnlySimulation simulation =
        new SmartWaitForPlanner.GraphOnlySimulation(
            3, 1, true, 2, 0, 2, SmartWaitForPlanner.Confidence.HIGH, "test");
    return new SmartWaitForPlanner.UnlockCandidate(
        "yield-train",
        "cycle-yield",
        SmartWaitForPlanner.CandidateKind.YIELD_TO_HEAD_ON,
        List.of(conflict.toString(), physicalNode.toString()),
        List.of(conflict.toString(), physicalNode.toString()),
        "A",
        "A",
        "B",
        CorridorDirection.B_TO_A,
        2,
        0,
        4,
        1200,
        true,
        "-",
        "HEAD_ON_YIELD",
        simulation,
        false,
        true,
        60);
  }

  private PlannerExecutionSnapshot executePlannerReservationForMode(SmartDispatcherMode mode)
      throws Exception {
    return executePlannerReservationForMode(mode, smartUnlockProgressRegistry());
  }

  private PlannerExecutionSnapshot executePlannerReservationForMode(
      SmartDispatcherMode mode, RouteProgressRegistry registry) throws Exception {
    List<String> debugMessages = new ArrayList<>();
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    RuntimeDispatchService service =
        createMinimalService(
            manager, mock(RouteDefinitionCache.class), registry, debugMessages, mode);
    service.setSignalReevaluationRequester(
        trainName -> debugMessages.add("TEST_SIGNAL_REEVALUATION_REQUEST " + trainName));
    long versionBefore = manager.version();
    int claimsBefore = manager.snapshotClaims().size();
    int queuesBefore = manager.snapshotQueues().size();

    executeSmartUnlockReservation(service, smartUnlockCandidate(), enforcePlannerSettings());

    return new PlannerExecutionSnapshot(
        service, manager, debugMessages, versionBefore, claimsBefore, queuesBefore);
  }

  private PlannerExecutionSnapshot executeSingleRegionPlannerReservationWithExternalOccupant(
      CorridorDirection requestedDirection) throws Exception {
    List<String> debugMessages = new ArrayList<>();
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    OccupancyResource conflict = OccupancyResource.forConflict("single:test:A~B");
    assertTrue(
        manager
            .acquire(singleConflictRequest("external", conflict, CorridorDirection.A_TO_B))
            .allowed());
    RuntimeDispatchService service =
        createMinimalService(manager, debugMessages, SmartDispatcherMode.ENFORCE);
    long versionBefore = manager.version();
    int claimsBefore = manager.snapshotClaims().size();
    int queuesBefore = manager.snapshotQueues().size();

    executeSmartUnlockReservation(
        service,
        smartUnlockCandidate("CONFLICT:single:test:A~B", requestedDirection),
        enforcePlannerSettings());

    return new PlannerExecutionSnapshot(
        service, manager, debugMessages, versionBefore, claimsBefore, queuesBefore);
  }

  private FollowThroughPreviewSnapshot runFollowThroughPreview(
      SmartDispatcherMode mode, boolean installLeaderToken) throws Exception {
    List<String> debugMessages = new ArrayList<>();
    NodeId a = NodeId.of("A");
    NodeId b = NodeId.of("B");
    NodeId c = NodeId.of("C");
    String conflictKey = "single:test:A~C";
    OccupancyResource conflict = OccupancyResource.forConflict(conflictKey);
    RailEdge edgeAb = new RailEdge(EdgeId.undirected(a, b), a, b, 10, -1.0, true, Optional.empty());
    RailEdge edgeBc = new RailEdge(EdgeId.undirected(b, c), b, c, 10, -1.0, true, Optional.empty());
    OccupancyRequest leaderAuthority =
        singleConflictContextWithResources(
                "leader",
                conflict,
                CorridorDirection.A_TO_B,
                edgeAb,
                edgeBc,
                a,
                b,
                c,
                List.of(
                    conflict,
                    OccupancyResource.forNode(a),
                    OccupancyResource.forEdge(edgeAb.id()),
                    OccupancyResource.forNode(b),
                    OccupancyResource.forEdge(edgeBc.id()),
                    OccupancyResource.forNode(c)))
            .request();
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    assertTrue(manager.acquire(leaderAuthority).allowed());
    RouteProgressRegistry registry = new RouteProgressRegistry();
    RouteDefinition route =
        new RouteDefinition(RouteId.of("follow"), List.of(a, b, c), Optional.empty());
    UUID routeUuid = UUID.randomUUID();
    registry.initFromTags(
        "leader",
        new TagStore("leader", "FTA_ROUTE_ID=" + routeUuid, "FTA_ROUTE_INDEX=0").properties(),
        route);
    registry.initFromTags(
        "follower", new TagStore("follower", "FTA_ROUTE_INDEX=0").properties(), route);
    registry.updateSignal("leader", SignalAspect.PROCEED, Instant.now());
    registry.updateSignal("follower", SignalAspect.STOP, Instant.now());
    registry.updateLastPassedGraphNode("leader", a, Instant.now());
    RouteDefinitionCache routeDefinitions = routeDefinitionCacheWith(route, routeUuid);
    RuntimeDispatchService service =
        createMinimalService(manager, routeDefinitions, registry, debugMessages, mode);
    if (installLeaderToken) {
      issueAndActivateMovementToken(service, "leader", a, c, leaderAuthority, "C");
    }
    long versionBefore = manager.version();
    int claimsBefore = manager.snapshotClaims().size();
    int queuesBefore = manager.snapshotQueues().size();
    int tokensBefore = movementTokenCount(service);
    SignalAspect followerSignalBefore = registry.get("follower").orElseThrow().lastSignal();
    SignalAspect leaderSignalBefore = registry.get("leader").orElseThrow().lastSignal();

    boolean admissionAllowed =
        service.smartDepotAdmissionAllowsSpawn(
            "follower",
            new ConflictExitGraph(a, b, c, edgeAb, edgeBc, conflictKey),
            singleConflictContext(
                "follower", conflict, CorridorDirection.A_TO_B, edgeAb, edgeBc, a, b, c));

    return new FollowThroughPreviewSnapshot(
        service,
        manager,
        registry,
        debugMessages,
        versionBefore,
        claimsBefore,
        queuesBefore,
        tokensBefore,
        followerSignalBefore,
        leaderSignalBefore,
        admissionAllowed);
  }

  private AdmissionView sameDirectionAdmissionView(
      String trainName,
      String externalTrain,
      OccupancyResource conflict,
      CorridorDirection requestDirection,
      CorridorDirection externalDirection,
      RailEdge first,
      RailEdge second,
      NodeId firstNode,
      NodeId middleNode,
      NodeId lastNode)
      throws Exception {
    List<String> debugMessages = new ArrayList<>();
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    assertTrue(
        manager
            .acquire(singleConflictRequest(externalTrain, conflict, externalDirection))
            .allowed());
    RuntimeDispatchService service =
        createMinimalService(manager, debugMessages, SmartDispatcherMode.ENFORCE);
    installMovementInhibitor(service, externalTrain);
    return new AdmissionView(
        service,
        singleConflictContext(
            trainName, conflict, requestDirection, first, second, firstNode, middleNode, lastNode),
        debugMessages);
  }

  private static boolean hasFollowThroughPreviewLog(List<String> debugMessages) {
    return debugMessages.stream()
        .anyMatch(
            message ->
                message.contains("SMART_FOLLOW_THROUGH_PREVIEW")
                    && message.contains("wouldMutate=false")
                    && message.contains("didMutate=false"));
  }

  private static boolean hasHardBarrierLog(List<String> debugMessages) {
    return debugMessages.stream()
        .anyMatch(
            message ->
                message.contains(
                    SimpleOccupancyManager.OPPOSITE_OR_UNKNOWN_SINGLE_REGION_HARD_BARRIER));
  }

  private static RouteProgressRegistry signalRegistryForTrain(
      String trainName, SignalAspect initialSignal) {
    RouteProgressRegistry registry = new RouteProgressRegistry();
    RouteDefinition route =
        new RouteDefinition(
            RouteId.of("signal-test"), List.of(NodeId.of("A"), NodeId.of("B")), Optional.empty());
    registry.initFromTags(
        trainName, new TagStore(trainName, "FTA_ROUTE_INDEX=0").properties(), route);
    registry.updateSignal(trainName, initialSignal, Instant.now());
    return registry;
  }

  private static ConfigManager.SmartDispatcherPlannerSettings enforcePlannerSettings() {
    return new ConfigManager.SmartDispatcherPlannerSettings(
        true,
        SmartDispatcherPlannerMode.ENFORCE_MINIMAL_FORWARD,
        4,
        100,
        1000L,
        true,
        false,
        false,
        true);
  }

  private static boolean hasUnlockReservationClaim(SimpleOccupancyManager manager) {
    return manager.snapshotClaims().stream()
        .anyMatch(claim -> claim.role() == ClaimRole.UNLOCK_RESERVATION);
  }

  private static int movementTokenCount(RuntimeDispatchService service) throws Exception {
    return movementTokens(service).size();
  }

  private static List<MovementAuthorizationToken> movementTokens(RuntimeDispatchService service)
      throws Exception {
    java.lang.reflect.Field field =
        RuntimeDispatchService.class.getDeclaredField("movementAuthorizationTokens");
    field.setAccessible(true);
    @SuppressWarnings("unchecked")
    java.util.concurrent.ConcurrentMap<String, MovementAuthorizationToken> tokens =
        (java.util.concurrent.ConcurrentMap<String, MovementAuthorizationToken>) field.get(service);
    return List.copyOf(tokens.values());
  }

  private static void installMovementToken(
      RuntimeDispatchService service, MovementAuthorizationToken token) throws Exception {
    java.lang.reflect.Field field =
        RuntimeDispatchService.class.getDeclaredField("movementAuthorizationTokens");
    field.setAccessible(true);
    @SuppressWarnings("unchecked")
    java.util.concurrent.ConcurrentMap<String, MovementAuthorizationToken> tokens =
        (java.util.concurrent.ConcurrentMap<String, MovementAuthorizationToken>) field.get(service);
    tokens.put(token.trainName().toLowerCase(java.util.Locale.ROOT), token);
  }

  private static MovementAuthorizationToken issueAndActivateMovementToken(
      RuntimeDispatchService service,
      String trainName,
      NodeId currentNode,
      NodeId destinationNode,
      OccupancyRequest authorityRequest,
      String destinationName)
      throws Exception {
    java.lang.reflect.Method issue =
        RuntimeDispatchService.class.getDeclaredMethod(
            "issueMovementAuthorizationToken",
            String.class,
            NodeId.class,
            NodeId.class,
            OccupancyRequest.class,
            SignalAspect.class,
            Instant.class);
    java.lang.reflect.Method activate =
        RuntimeDispatchService.class.getDeclaredMethod(
            "activateMovementAuthorizationTokenRetainingInhibitor",
            String.class,
            MovementAuthorizationToken.class,
            String.class);
    issue.setAccessible(true);
    activate.setAccessible(true);
    MovementAuthorizationToken token =
        (MovementAuthorizationToken)
            issue.invoke(
                service,
                trainName,
                currentNode,
                destinationNode,
                authorityRequest,
                SignalAspect.PROCEED,
                Instant.now());
    assertTrue((boolean) activate.invoke(service, trainName, token, destinationName));
    return movementTokens(service).stream()
        .filter(candidate -> candidate.trainName().equals(trainName))
        .findFirst()
        .orElseThrow();
  }

  private static boolean validMovementAuthorization(
      RuntimeDispatchService service,
      String trainName,
      NodeId currentNode,
      NodeId nextNode,
      List<OccupancyResource> resources)
      throws Exception {
    java.lang.reflect.Method method =
        RuntimeDispatchService.class.getDeclaredMethod(
            "hasValidMovementAuthorization", String.class, NodeId.class, NodeId.class, List.class);
    method.setAccessible(true);
    return (boolean) method.invoke(service, trainName, currentNode, nextNode, resources);
  }

  private static boolean invokeSignalUpdate(
      RuntimeDispatchService service, String trainName, SignalAspect aspect) throws Exception {
    java.lang.reflect.Method method =
        RuntimeDispatchService.class.getDeclaredMethod(
            "updateSignalOrWarn", String.class, SignalAspect.class, Instant.class);
    method.setAccessible(true);
    return (boolean) method.invoke(service, trainName, aspect, Instant.now());
  }

  private static boolean invokeSignalUpdate(
      RuntimeDispatchService service, String trainName, SignalAspect aspect, Object authorization)
      throws Exception {
    Class<?> authorizationClass =
        Class.forName(
            "org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchService$FinalSignalAuthorization");
    java.lang.reflect.Method method =
        RuntimeDispatchService.class.getDeclaredMethod(
            "updateSignalOrWarn",
            String.class,
            SignalAspect.class,
            Instant.class,
            authorizationClass);
    method.setAccessible(true);
    return (boolean) method.invoke(service, trainName, aspect, Instant.now(), authorization);
  }

  private static Object invokePhysicalSignalPublication(
      RuntimeDispatchService service,
      String trainName,
      SignalAspect aspect,
      SignalAspect fallback,
      Object authorization)
      throws Exception {
    Class<?> authorizationClass =
        Class.forName(
            "org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchService$FinalSignalAuthorization");
    java.lang.reflect.Method method =
        RuntimeDispatchService.class.getDeclaredMethod(
            "publishPhysicalSignalIfRequired",
            String.class,
            SignalAspect.class,
            SignalAspect.class,
            Instant.class,
            authorizationClass);
    method.setAccessible(true);
    return method.invoke(service, trainName, aspect, fallback, Instant.now(), authorization);
  }

  private static Object finalSignalAuthorization(
      OccupancyRequest request,
      OccupancyDecision decision,
      SignalPublicationGate.Decision publication,
      NodeId currentNode,
      NodeId nextNode,
      boolean destinationPresent)
      throws Exception {
    return finalSignalAuthorization(
        request,
        decision,
        publication,
        currentNode,
        nextNode,
        SignalComputationTrace.TokenState.ACTIVE,
        destinationPresent);
  }

  private static Object finalSignalAuthorization(
      OccupancyRequest request,
      OccupancyDecision decision,
      SignalPublicationGate.Decision publication,
      NodeId currentNode,
      NodeId nextNode,
      SignalComputationTrace.TokenState tokenState,
      boolean destinationPresent)
      throws Exception {
    Class<?> authorizationClass =
        Class.forName(
            "org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchService$FinalSignalAuthorization");
    java.lang.reflect.Constructor<?> constructor =
        authorizationClass.getDeclaredConstructor(
            String.class,
            OccupancyRequest.class,
            OccupancyDecision.class,
            SignalPublicationGate.Decision.class,
            NodeId.class,
            NodeId.class,
            SignalComputationTrace.TokenState.class,
            boolean.class,
            boolean.class);
    constructor.setAccessible(true);
    return constructor.newInstance(
        "TEST",
        request,
        decision,
        publication,
        currentNode,
        nextNode,
        tokenState,
        destinationPresent,
        false);
  }

  private static void installPublishedPhysicalSignal(
      RuntimeDispatchService service, String trainName, SignalAspect aspect) throws Exception {
    java.lang.reflect.Field field =
        RuntimeDispatchService.class.getDeclaredField("publishedPhysicalSignals");
    field.setAccessible(true);
    @SuppressWarnings("unchecked")
    java.util.concurrent.ConcurrentMap<String, SignalAspect> published =
        (java.util.concurrent.ConcurrentMap<String, SignalAspect>) field.get(service);
    published.put(trainName.toLowerCase(java.util.Locale.ROOT), aspect);
  }

  private static SignalAspect currentPhysicalAspect(
      RuntimeDispatchService service, String trainName) throws Exception {
    java.lang.reflect.Method method =
        RuntimeDispatchService.class.getDeclaredMethod(
            "currentPhysicalAspect", String.class, SignalAspect.class);
    method.setAccessible(true);
    return (SignalAspect) method.invoke(service, trainName, SignalAspect.STOP);
  }

  private static void invokeClearRuntimeCachesForTrain(
      RuntimeDispatchService service, String trainName) throws Exception {
    java.lang.reflect.Method method =
        RuntimeDispatchService.class.getDeclaredMethod("clearRuntimeCachesForTrain", String.class);
    method.setAccessible(true);
    method.invoke(service, trainName);
  }

  private void executeSmartUnlockReservation(
      RuntimeDispatchService service,
      SmartWaitForPlanner.UnlockCandidate candidate,
      ConfigManager.SmartDispatcherPlannerSettings settings)
      throws Exception {
    java.lang.reflect.Field occupancyManagerField =
        RuntimeDispatchService.class.getDeclaredField("occupancyManager");
    occupancyManagerField.setAccessible(true);
    OccupancyManager occupancyManager = (OccupancyManager) occupancyManagerField.get(service);
    List<OccupancyClaim> liveClaims =
        occupancyManager == null ? List.of() : occupancyManager.snapshotClaims();
    java.lang.reflect.Method method =
        RuntimeDispatchService.class.getDeclaredMethod(
            "executeSmartUnlockReservation",
            SmartWaitForPlanner.UnlockCandidate.class,
            ConfigManager.SmartDispatcherPlannerSettings.class,
            Instant.class,
            java.util.Collection.class);
    method.setAccessible(true);
    method.invoke(service, candidate, settings, Instant.now(), liveClaims);
  }

  private record NodeEdgePlannerFixture(
      RuntimeDispatchService service,
      RouteProgressRegistry progressRegistry,
      List<String> debugMessages,
      Set<String> activeTrains,
      String trainA,
      String trainB,
      OccupancyDecision decisionA,
      OccupancyDecision decisionB,
      Instant now) {}

  private record RearProtectionPlannerFixture(
      RuntimeDispatchService service,
      SimpleOccupancyManager manager,
      List<String> debugMessages,
      Set<String> activeTrains,
      Instant now) {}

  private record PlannerExecutionSnapshot(
      RuntimeDispatchService service,
      SimpleOccupancyManager manager,
      List<String> debugMessages,
      long occupancyVersionBefore,
      int claimsBefore,
      int queuesBefore) {}

  private record FollowThroughPreviewSnapshot(
      RuntimeDispatchService service,
      SimpleOccupancyManager manager,
      RouteProgressRegistry registry,
      List<String> debugMessages,
      long occupancyVersionBefore,
      int claimsBefore,
      int queuesBefore,
      int tokensBefore,
      SignalAspect followerSignalBefore,
      SignalAspect leaderSignalBefore,
      boolean admissionAllowed) {}

  private record AdmissionView(
      RuntimeDispatchService service,
      OccupancyRequestContext context,
      List<String> debugMessages) {}

  private record ThroatFixture(
      NodeId entry,
      NodeId throat,
      NodeId switcher,
      NodeId exit,
      RailEdge entryEdge,
      RailEdge switcherEdge,
      RailEdge exitEdge,
      OccupancyResource conflict,
      ThroatConflictGraph graph) {

    private OccupancyRequestContext context(
        String trainName, CorridorDirection direction, boolean reverse) {
      if (reverse) {
        return singleConflictPathContext(
            trainName,
            conflict,
            direction,
            List.of(exit, switcher, throat, entry),
            List.of(exitEdge, switcherEdge, entryEdge),
            atomicResources(true));
      }
      return singleConflictPathContext(
          trainName,
          conflict,
          direction,
          List.of(entry, throat, switcher, exit),
          List.of(entryEdge, switcherEdge, exitEdge),
          atomicResources(false));
    }

    private OccupancyRequestContext advisoryOnlyContext(
        String trainName, CorridorDirection direction) {
      return singleConflictPathContext(
          trainName,
          conflict,
          direction,
          List.of(entry, throat, switcher, exit),
          List.of(entryEdge, switcherEdge, exitEdge),
          List.of(conflict));
    }

    private OccupancyRequest atomicRequest(String trainName, CorridorDirection direction) {
      return singleConflictPathContext(
              trainName,
              conflict,
              direction,
              List.of(entry, throat, switcher, exit),
              List.of(entryEdge, switcherEdge, exitEdge),
              atomicResources())
          .request();
    }

    private List<OccupancyResource> atomicResources() {
      return atomicResources(false);
    }

    private List<OccupancyResource> atomicResources(boolean reverse) {
      LinkedHashSet<OccupancyResource> resources = new LinkedHashSet<>();
      resources.add(conflict);
      resources.add(OccupancyResource.forConflict("switcher:" + switcher.value()));
      resources.add(OccupancyResource.forEdge(entryEdge.id()));
      resources.add(OccupancyResource.forEdge(switcherEdge.id()));
      resources.add(OccupancyResource.forEdge(exitEdge.id()));
      resources.add(OccupancyResource.forNode(reverse ? entry : throat));
      resources.add(OccupancyResource.forNode(switcher));
      resources.add(OccupancyResource.forNode(reverse ? throat : exit));
      return List.copyOf(resources);
    }
  }

  private static OccupancyRequestContext splitConflictThroatContext(
      String trainName,
      ThroatFixture throat,
      OccupancyResource entryConflict,
      OccupancyResource internalConflict,
      CorridorDirection direction) {
    List<NodeId> nodes = List.of(throat.entry(), throat.throat(), throat.switcher(), throat.exit());
    List<RailEdge> edges = List.of(throat.entryEdge(), throat.switcherEdge(), throat.exitEdge());
    Map<String, CorridorDirection> directions =
        Map.of(entryConflict.key(), direction, internalConflict.key(), direction);
    Map<String, Integer> entryOrders = Map.of(entryConflict.key(), 0, internalConflict.key(), 1);
    LinkedHashSet<OccupancyResource> hardResources = new LinkedHashSet<>(throat.atomicResources());
    hardResources.add(entryConflict);
    hardResources.add(internalConflict);
    DirectedTraversalContext directedContext =
        new DirectedTraversalContext(
            trainName,
            Optional.empty(),
            0,
            Optional.of(throat.entry()),
            Optional.of(throat.entry()),
            Optional.of(throat.entry()),
            Optional.of(throat.throat()),
            nodes,
            List.of(
                new DirectedTraversalContext.DirectedEdge(
                    throat.entryEdge().id(), throat.entry(), throat.throat()),
                new DirectedTraversalContext.DirectedEdge(
                    throat.switcherEdge().id(), throat.throat(), throat.switcher()),
                new DirectedTraversalContext.DirectedEdge(
                    throat.exitEdge().id(), throat.switcher(), throat.exit())),
            directions,
            Map.of(),
            AuthorizationPurpose.DEPOT_SPAWN.name(),
            0L,
            0L,
            "split-conflict-throat",
            Optional.empty());
    OccupancyRequest request =
        new OccupancyRequest(
            trainName,
            Optional.empty(),
            Instant.parse("2026-01-01T00:00:01Z"),
            List.copyOf(hardResources),
            directions,
            entryOrders,
            0,
            AuthorizationPurpose.DEPOT_SPAWN,
            Map.of(),
            Map.of(),
            Optional.of(directedContext));
    return new OccupancyRequestContext(request, nodes, edges);
  }

  private static RailEdge testEdge(NodeId from, NodeId to) {
    EdgeId id = EdgeId.undirected(from, to);
    return new RailEdge(id, from, to, 10, 8.0, true, Optional.empty());
  }

  private static ThroatFixture ppkThroatFixture() {
    NodeId entry = NodeId.of("OP:S:PPK:1");
    NodeId throat = NodeId.of("OP:S:PPK:1:001");
    NodeId switcher = NodeId.of("SWITCHER:-583:65:650");
    NodeId exit = NodeId.of("OP:S:PPK:2");
    RailEdge entryEdge =
        new RailEdge(
            EdgeId.undirected(entry, throat), entry, throat, 8, -1.0, true, Optional.empty());
    RailEdge switcherEdge =
        new RailEdge(
            EdgeId.undirected(throat, switcher), throat, switcher, 6, -1.0, true, Optional.empty());
    RailEdge exitEdge =
        new RailEdge(
            EdgeId.undirected(switcher, exit), switcher, exit, 8, -1.0, true, Optional.empty());
    String conflictKey = "single:test:ppk-throat";
    OccupancyResource conflict = OccupancyResource.forConflict(conflictKey);
    ThroatConflictGraph graph =
        new ThroatConflictGraph(
            Map.of(
                entry,
                    new RailNodeTest(
                        entry,
                        NodeType.STATION,
                        Optional.of(WaypointMetadata.station("OP", "PPK", 1))),
                throat,
                    new RailNodeTest(
                        throat,
                        NodeType.WAYPOINT,
                        Optional.of(WaypointMetadata.stationThroat("OP", "PPK", 1, "001"))),
                switcher, new RailNodeTest(switcher, NodeType.SWITCHER, Optional.empty()),
                exit,
                    new RailNodeTest(
                        exit,
                        NodeType.STATION,
                        Optional.of(WaypointMetadata.station("OP", "PPK", 2)))),
            List.of(entryEdge, switcherEdge, exitEdge),
            Map.of(
                entryEdge.id(),
                conflictKey,
                switcherEdge.id(),
                conflictKey,
                exitEdge.id(),
                conflictKey));
    return new ThroatFixture(
        entry, throat, switcher, exit, entryEdge, switcherEdge, exitEdge, conflict, graph);
  }

  private static ThroatFixture ppkOutboundIntervalThroatFixture() {
    NodeId entry = NodeId.of("OP:S:PPK:2");
    NodeId throat = NodeId.of("OP:S:PPK:2:001");
    NodeId switcher = NodeId.of("SWITCHER:-583:65:650");
    NodeId exit = NodeId.of("OP:PPK:RVS:2:001");
    RailEdge entryEdge =
        new RailEdge(
            EdgeId.undirected(entry, throat), entry, throat, 8, -1.0, true, Optional.empty());
    RailEdge switcherEdge =
        new RailEdge(
            EdgeId.undirected(throat, switcher), throat, switcher, 6, -1.0, true, Optional.empty());
    RailEdge exitEdge =
        new RailEdge(
            EdgeId.undirected(switcher, exit), switcher, exit, 8, -1.0, true, Optional.empty());
    String conflictKey = "single:test:ppk-outbound-throat";
    OccupancyResource conflict = OccupancyResource.forConflict(conflictKey);
    ThroatConflictGraph graph =
        new ThroatConflictGraph(
            Map.of(
                entry,
                    new RailNodeTest(
                        entry,
                        NodeType.STATION,
                        Optional.of(WaypointMetadata.station("OP", "PPK", 2))),
                throat,
                    new RailNodeTest(
                        throat,
                        NodeType.WAYPOINT,
                        Optional.of(WaypointMetadata.stationThroat("OP", "PPK", 2, "001"))),
                switcher, new RailNodeTest(switcher, NodeType.SWITCHER, Optional.empty()),
                exit,
                    new RailNodeTest(
                        exit,
                        NodeType.WAYPOINT,
                        Optional.of(WaypointMetadata.interval("OP", "PPK", "RVS", 2, "001")))),
            List.of(entryEdge, switcherEdge, exitEdge),
            Map.of(
                entryEdge.id(),
                conflictKey,
                switcherEdge.id(),
                conflictKey,
                exitEdge.id(),
                conflictKey));
    return new ThroatFixture(
        entry, throat, switcher, exit, entryEdge, switcherEdge, exitEdge, conflict, graph);
  }

  private OccupancyRequest staleProgressRequest(
      String trainName,
      RouteId routeId,
      int currentIndex,
      long progressVersion,
      List<OccupancyResource> resources) {
    return progressRequest(
        trainName,
        routeId,
        currentIndex,
        NodeId.of("C"),
        NodeId.of("B"),
        progressVersion,
        resources);
  }

  private OccupancyRequest progressRequest(
      String trainName,
      RouteId routeId,
      int currentIndex,
      NodeId currentNode,
      NodeId lastPassedGraphNode,
      long progressVersion,
      List<OccupancyResource> resources) {
    DirectedTraversalContext context =
        new DirectedTraversalContext(
            trainName,
            Optional.of(routeId),
            currentIndex,
            Optional.of(currentNode),
            Optional.of(lastPassedGraphNode),
            Optional.of(currentNode),
            Optional.of(NodeId.of("D")),
            List.of(currentNode, NodeId.of("D")),
            List.of(),
            Map.of(),
            Map.of(),
            "EVENT",
            1L,
            progressVersion,
            "stale-request",
            Optional.empty());
    Map<OccupancyResource, ResourceIntent> intents = new LinkedHashMap<>();
    for (OccupancyResource resource : resources) {
      intents.put(resource, ResourceIntent.MOVEMENT_REQUIRED);
    }
    return new OccupancyRequest(
        trainName,
        Optional.of(routeId),
        Instant.now(),
        resources,
        Map.of(),
        Map.of(),
        0,
        AuthorizationPurpose.RUNTIME_MOVE,
        Map.of(),
        intents,
        Optional.of(context));
  }

  private void installSmartUnlockReservation(
      RuntimeDispatchService service,
      SmartWaitForPlanner.UnlockCandidate candidate,
      List<OccupancyResource> resources,
      long createdTick,
      int ttlTicks)
      throws Exception {
    Class<?> reservationClass =
        Class.forName(
            "org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchService$SmartUnlockReservation");
    java.lang.reflect.Constructor<?> constructor =
        reservationClass.getDeclaredConstructor(
            String.class,
            String.class,
            String.class,
            String.class,
            List.class,
            NodeId.class,
            String.class,
            String.class,
            int.class,
            long.class,
            int.class,
            List.class,
            List.class,
            String.class,
            String.class,
            long.class,
            boolean.class);
    constructor.setAccessible(true);
    Object reservation =
        constructor.newInstance(
            "unlock-test",
            candidate.train(),
            candidate.cycleId(),
            candidate.kind().name(),
            resources,
            NodeId.of(candidate.authorityEnd()),
            candidate.planHash(),
            candidate.forwardPathEvidence().map(CanonicalForwardPathEvidence::routeId).orElse("-"),
            candidate
                .forwardPathEvidence()
                .map(CanonicalForwardPathEvidence::routeIndex)
                .orElse(-1),
            createdTick,
            ttlTicks,
            candidate.resources(),
            List.of("follower"),
            candidate.currentNode(),
            candidate.currentNode(),
            -1L,
            true);
    java.lang.reflect.Field byCycleField =
        RuntimeDispatchService.class.getDeclaredField("smartUnlockReservationsByCycle");
    java.lang.reflect.Field byTrainField =
        RuntimeDispatchService.class.getDeclaredField("smartUnlockReservationsByTrain");
    byCycleField.setAccessible(true);
    byTrainField.setAccessible(true);
    @SuppressWarnings("unchecked")
    java.util.concurrent.ConcurrentMap<String, Object> byCycle =
        (java.util.concurrent.ConcurrentMap<String, Object>) byCycleField.get(service);
    @SuppressWarnings("unchecked")
    java.util.concurrent.ConcurrentMap<String, Object> byTrain =
        (java.util.concurrent.ConcurrentMap<String, Object>) byTrainField.get(service);
    byCycle.put(candidate.cycleId(), reservation);
    byTrain.put(candidate.train(), reservation);
  }

  private void installSmartUnlockBlockerSnapshot(
      RuntimeDispatchService service,
      String blockedTrain,
      String blockerTrain,
      String resource,
      Instant sampledAt)
      throws Exception {
    RuntimeDispatchService.DeadlockBlockerInfo blocker =
        new RuntimeDispatchService.DeadlockBlockerInfo(
            blockerTrain,
            "",
            Optional.empty(),
            blockerTrain,
            resource,
            "WAIT_FOR",
            "TEST",
            "NORMAL",
            "test",
            currentTraceTick(),
            1L);
    java.lang.reflect.Field field =
        RuntimeDispatchService.class.getDeclaredField("blockerSnapshots");
    field.setAccessible(true);
    @SuppressWarnings("unchecked")
    java.util.concurrent.ConcurrentMap<String, Object> blockerSnapshots =
        (java.util.concurrent.ConcurrentMap<String, Object>) field.get(service);
    Class<?> snapshotClass =
        Class.forName(
            "org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchService$BlockerSnapshot");
    java.lang.reflect.Constructor<?> constructor =
        snapshotClass.getDeclaredConstructor(Set.class, Instant.class);
    constructor.setAccessible(true);
    blockerSnapshots.put(blockedTrain, constructor.newInstance(Set.of(blocker), sampledAt));
  }

  private void installMovementInhibitor(RuntimeDispatchService service, String trainName)
      throws Exception {
    java.lang.reflect.Field field =
        RuntimeDispatchService.class.getDeclaredField("movementInhibitors");
    field.setAccessible(true);
    @SuppressWarnings("unchecked")
    java.util.concurrent.ConcurrentMap<String, HardStopReason> inhibitors =
        (java.util.concurrent.ConcurrentMap<String, HardStopReason>) field.get(service);
    inhibitors.put(
        TrainNameNormalizer.normalizeKey(trainName), HardStopReason.AUTHORIZATION_FAILURE);
  }

  private void observeSmartUnlockReservations(RuntimeDispatchService service, Instant now)
      throws Exception {
    java.lang.reflect.Method method =
        RuntimeDispatchService.class.getDeclaredMethod(
            "observeSmartUnlockReservations", Instant.class);
    method.setAccessible(true);
    method.invoke(service, now);
  }

  @SuppressWarnings("unchecked")
  private Set<OccupancyResource> invokeStopRetainBehindReleaseResources(
      RuntimeDispatchService service,
      List<NodeId> effectiveNodes,
      int currentIndex,
      List<OccupancyClaim> oldSelfClaims,
      OccupancyRequest request)
      throws Exception {
    java.lang.reflect.Method method =
        RuntimeDispatchService.class.getDeclaredMethod(
            "stopRetainBehindReleaseResources",
            List.class,
            int.class,
            List.class,
            OccupancyRequest.class);
    method.setAccessible(true);
    return (Set<OccupancyResource>)
        method.invoke(service, effectiveNodes, currentIndex, oldSelfClaims, request);
  }

  private void invokeRetainStopOccupancy(
      RuntimeDispatchService service,
      String trainName,
      RouteDefinition route,
      int currentIndex,
      NodeId currentNode,
      RailGraph graph,
      Instant now)
      throws Exception {
    invokeRetainStopOccupancy(
        service, trainName, route, currentIndex, currentNode, Optional.empty(), graph, now);
  }

  private void invokeRetainStopOccupancy(
      RuntimeDispatchService service,
      String trainName,
      RouteDefinition route,
      int currentIndex,
      NodeId currentNode,
      Optional<OccupancyRequest> movementRequest,
      RailGraph graph,
      Instant now,
      RuntimeTrainHandle train)
      throws Exception {
    java.lang.reflect.Method method =
        RuntimeDispatchService.class.getDeclaredMethod(
            "retainStopOccupancy",
            String.class,
            RouteDefinition.class,
            int.class,
            NodeId.class,
            Optional.class,
            RailGraph.class,
            Instant.class,
            RuntimeTrainHandle.class);
    method.setAccessible(true);
    method.invoke(
        service, trainName, route, currentIndex, currentNode, movementRequest, graph, now, train);
  }

  private void invokeRetainStopOccupancy(
      RuntimeDispatchService service,
      String trainName,
      RouteDefinition route,
      int currentIndex,
      NodeId currentNode,
      Optional<OccupancyRequest> movementRequest,
      RailGraph graph,
      Instant now)
      throws Exception {
    FakeTrain train = new FakeTrain(UUID.randomUUID(), new TagStore(trainName).properties(), false);
    train.estimatedTrainLengthBlocks = OptionalDouble.empty();
    train.liveRailFootprintCells = Optional.empty();
    invokeRetainStopOccupancy(
        service, trainName, route, currentIndex, currentNode, movementRequest, graph, now, train);
  }

  private boolean smartUnlockNoReleaseCooldownActive(
      RuntimeDispatchService service, SmartWaitForPlanner.UnlockCandidate candidate)
      throws Exception {
    java.lang.reflect.Method method =
        RuntimeDispatchService.class.getDeclaredMethod(
            "smartUnlockNoReleaseCooldownActive",
            SmartWaitForPlanner.UnlockCandidate.class,
            Instant.class);
    method.setAccessible(true);
    return (boolean) method.invoke(service, candidate, Instant.now());
  }

  private String smartDispatchExecutorSkipReason(
      RuntimeDispatchService service,
      SmartWaitForPlanner.UnlockCandidate candidate,
      ConfigManager.SmartDispatcherPlannerSettings settings)
      throws Exception {
    java.lang.reflect.Method method =
        RuntimeDispatchService.class.getDeclaredMethod(
            "smartDispatchExecutorSkipReason",
            SmartWaitForPlanner.UnlockCandidate.class,
            ConfigManager.SmartDispatcherPlannerSettings.class);
    method.setAccessible(true);
    return (String) method.invoke(service, candidate, settings);
  }

  private String smartDispatchExecutorSkipReason(
      RuntimeDispatchService service,
      SmartWaitForPlanner.UnlockCandidate candidate,
      ConfigManager.SmartDispatcherPlannerSettings settings,
      Instant now)
      throws Exception {
    java.lang.reflect.Method method =
        RuntimeDispatchService.class.getDeclaredMethod(
            "smartDispatchExecutorSkipReason",
            SmartWaitForPlanner.UnlockCandidate.class,
            ConfigManager.SmartDispatcherPlannerSettings.class,
            Instant.class);
    method.setAccessible(true);
    return (String) method.invoke(service, candidate, settings, now);
  }

  private void updateLiveBlockerSnapshot(
      RuntimeDispatchService service,
      String trainName,
      OccupancyDecision decision,
      OccupancyRequest request,
      Instant now)
      throws Exception {
    java.lang.reflect.Method method =
        RuntimeDispatchService.class.getDeclaredMethod(
            "updateLiveBlockerSnapshot",
            String.class,
            OccupancyDecision.class,
            OccupancyRequest.class,
            Instant.class,
            String.class);
    method.setAccessible(true);
    method.invoke(service, trainName, decision, request, now, "canEnterPreview:blockers");
  }

  private void updateBlockerSnapshot(
      RuntimeDispatchService service,
      String trainName,
      OccupancyDecision decision,
      OccupancyRequest request,
      Instant now,
      String source)
      throws Exception {
    java.lang.reflect.Method method =
        RuntimeDispatchService.class.getDeclaredMethod(
            "updateBlockerSnapshot",
            String.class,
            OccupancyDecision.class,
            OccupancyRequest.class,
            Instant.class,
            String.class);
    method.setAccessible(true);
    method.invoke(service, trainName, decision, request, now, source);
  }

  private void updateBlockerSnapshotWithoutRequest(
      RuntimeDispatchService service, String trainName, OccupancyDecision decision, Instant now)
      throws Exception {
    java.lang.reflect.Method method =
        RuntimeDispatchService.class.getDeclaredMethod(
            "updateBlockerSnapshot", String.class, OccupancyDecision.class, Instant.class);
    method.setAccessible(true);
    method.invoke(service, trainName, decision, now);
  }

  private void replaceRouteProgressLastPassed(
      RouteProgressRegistry registry,
      String trainName,
      Optional<NodeId> lastPassed,
      Instant updatedAt)
      throws Exception {
    RouteProgressRegistry.RouteProgressEntry current = registry.get(trainName).orElseThrow();
    RouteProgressRegistry.RouteProgressEntry replacement =
        new RouteProgressRegistry.RouteProgressEntry(
            current.trainName(),
            current.routeUuid(),
            current.routeId(),
            current.currentIndex(),
            current.nextTarget(),
            lastPassed,
            current.lastSignal(),
            updatedAt);
    java.lang.reflect.Field entriesField = RouteProgressRegistry.class.getDeclaredField("entries");
    entriesField.setAccessible(true);
    @SuppressWarnings("unchecked")
    java.util.concurrent.ConcurrentMap<String, RouteProgressRegistry.RouteProgressEntry> entries =
        (java.util.concurrent.ConcurrentMap<String, RouteProgressRegistry.RouteProgressEntry>)
            entriesField.get(registry);
    entries.put(TrainNameNormalizer.normalizeKey(trainName), replacement);
    registry.updateSignal(trainName, replacement.lastSignal(), updatedAt);
  }

  @SuppressWarnings("unchecked")
  private List<SmartWaitForPlanner.InputEdge> smartPlannerInputEdges(
      RuntimeDispatchService service,
      Map<String, RouteProgressRegistry.RouteProgressEntry> progress,
      Set<String> activeTrainNames,
      Instant now,
      long topologyTtlMs)
      throws Exception {
    // liveClaims 由调用方传入：本 tick 的同一份账本快照。这里给空列表，
    // 等价于"没有任何 claim 能被现场复核"，于是边照旧按 TTL 年龄判定——
    // 正是这些用例原本要钉的语义。
    java.lang.reflect.Method method =
        RuntimeDispatchService.class.getDeclaredMethod(
            "smartPlannerInputEdges", Map.class, Set.class, Instant.class, long.class, List.class);
    method.setAccessible(true);
    return (List<SmartWaitForPlanner.InputEdge>)
        method.invoke(service, progress, activeTrainNames, now, topologyTtlMs, List.of());
  }

  private static OccupancyDecision blockedBy(
      OccupancyResource resource, String blockerTrain, Instant now) {
    return blockedBy(resource, blockerTrain, now, ClaimRole.MOVEMENT_REQUIRED);
  }

  private static OccupancyDecision blockedBy(
      OccupancyResource resource, String blockerTrain, Instant now, ClaimRole role) {
    OccupancyClaim blocker =
        new OccupancyClaim(
            resource, blockerTrain, Optional.empty(), now, Duration.ZERO, Optional.empty(), role);
    return new OccupancyDecision(false, now, SignalAspect.STOP, List.of(blocker));
  }

  private OccupancyDecision recoverSelfOwnedStaleRetainDecision(
      RuntimeDispatchService service,
      OccupancyRequest request,
      OccupancyDecision decision,
      String source)
      throws Exception {
    java.lang.reflect.Method method =
        RuntimeDispatchService.class.getDeclaredMethod(
            "maybeRecoverSelfOwnedStaleRetain",
            OccupancyRequest.class,
            OccupancyDecision.class,
            String.class);
    method.setAccessible(true);
    Object result = method.invoke(service, request, decision, source);
    java.lang.reflect.Method decisionAccessor = result.getClass().getDeclaredMethod("decision");
    decisionAccessor.setAccessible(true);
    return (OccupancyDecision) decisionAccessor.invoke(result);
  }

  private OccupancyDecision recoverSelfOwnedStaleRetainPreview(
      RuntimeDispatchService service, OccupancyRequest request, String source) throws Exception {
    java.lang.reflect.Method method =
        RuntimeDispatchService.class.getDeclaredMethod(
            "maybeRecoverSelfOwnedStaleRetainPreview", OccupancyRequest.class, String.class);
    method.setAccessible(true);
    Object result = method.invoke(service, request, source);
    java.lang.reflect.Method decisionAccessor = result.getClass().getDeclaredMethod("decision");
    decisionAccessor.setAccessible(true);
    return (OccupancyDecision) decisionAccessor.invoke(result);
  }

  private void assertDepartureSelfOwnedRetainPreviewDoesNotMutate(SmartDispatcherMode mode)
      throws Exception {
    List<String> debugMessages = new ArrayList<>();
    String trainName = "SURC-DS-LW-1302";
    OccupancyResource conflict = OccupancyResource.forConflict("single:test:JBS~WSD");
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    OccupancyRequest staleRetain =
        singleConflictRequest(trainName, conflict, CorridorDirection.B_TO_A)
            .withResourceIntents(Map.of(conflict, ResourceIntent.PROTECTIVE_RETAIN));
    assertTrue(manager.acquire(staleRetain).allowed());
    assertTrue(manager.selfOwnedStaleRetainReleaseCandidate(trainName).isEmpty());
    RouteProgressRegistry progressRegistry = new RouteProgressRegistry();
    RuntimeDispatchService service =
        createMinimalService(
            manager, mock(RouteDefinitionCache.class), progressRegistry, debugMessages, mode);
    OccupancyRequest request = singleConflictRequest(trainName, conflict, CorridorDirection.A_TO_B);
    long versionBefore = manager.version();
    long progressVersionBefore = progressRegistry.version();
    List<OccupancyClaim> claimsBefore = manager.snapshotClaims();
    List<OccupancyQueueSnapshot> queuesBefore = manager.snapshotQueues();

    OccupancyDecision recovered =
        recoverSelfOwnedStaleRetainPreview(service, request, "departure-self-owned-retain");

    assertFalse(recovered.allowed());
    assertEquals(versionBefore, manager.version());
    assertEquals(progressVersionBefore, progressRegistry.version());
    assertEquals(claimsBefore, manager.snapshotClaims());
    assertEquals(queuesBefore, manager.snapshotQueues());
    assertTrue(manager.selfOwnedStaleRetainReleaseCandidate(trainName).isEmpty());
    assertFalse(
        debugMessages.stream().anyMatch(message -> message.contains("occupancyMutated=true")));
  }

  private static long currentTraceTick() {
    return System.currentTimeMillis() / 50L;
  }

  private RuntimeDispatchService createMinimalService(RouteDefinitionCache routeDefinitions) {
    return createMinimalService(
        mockOccupancyManager(), routeDefinitions, new RouteProgressRegistry(), new ArrayList<>());
  }

  private RuntimeDispatchService createMinimalService(
      OccupancyManager occupancyManager, List<String> debugMessages) {
    return createMinimalService(
        occupancyManager,
        mock(RouteDefinitionCache.class),
        new RouteProgressRegistry(),
        debugMessages);
  }

  private RuntimeDispatchService createMinimalService(
      OccupancyManager occupancyManager, List<String> debugMessages, SmartDispatcherMode mode) {
    return createMinimalService(
        occupancyManager,
        mock(RouteDefinitionCache.class),
        new RouteProgressRegistry(),
        debugMessages,
        mode);
  }

  private RuntimeDispatchService createMinimalService(
      OccupancyManager occupancyManager,
      RouteDefinitionCache routeDefinitions,
      RouteProgressRegistry progressRegistry,
      List<String> debugMessages) {
    return createMinimalService(
        occupancyManager,
        routeDefinitions,
        progressRegistry,
        debugMessages,
        SmartDispatcherMode.ENFORCE);
  }

  private RuntimeDispatchService createMinimalService(
      OccupancyManager occupancyManager,
      RouteDefinitionCache routeDefinitions,
      RouteProgressRegistry progressRegistry,
      List<String> debugMessages,
      SmartDispatcherMode mode) {
    return createMinimalService(
        occupancyManager,
        routeDefinitions,
        progressRegistry,
        debugMessages,
        mode,
        new DwellRegistry());
  }

  private RuntimeDispatchService createMinimalService(
      OccupancyManager occupancyManager,
      RouteDefinitionCache routeDefinitions,
      RouteProgressRegistry progressRegistry,
      List<String> debugMessages,
      SmartDispatcherMode mode,
      DwellRegistry dwellRegistry) {
    ConfigManager configManager = mock(ConfigManager.class);
    when(configManager.current())
        .thenReturn(withSmartDispatcherMode(testConfigView(20, 20.0), mode));

    return new RuntimeDispatchService(
        occupancyManager,
        mock(RailGraphService.class),
        routeDefinitions,
        progressRegistry,
        mock(SignNodeRegistry.class),
        mock(LayoverRegistry.class),
        dwellRegistry,
        configManager,
        null,
        new TrainConfigResolver(),
        debugMessages::add);
  }

  private static RouteDefinitionCache routeDefinitionCacheWith(
      RouteDefinition route, UUID routeUuid) {
    return routeDefinitionCacheWith(Map.of(routeUuid, route));
  }

  private static RouteDefinitionCache routeDefinitionCacheWith(Map<UUID, RouteDefinition> routes) {
    RouteDefinitionCache routeDefinitions = mock(RouteDefinitionCache.class);
    Map<UUID, RouteDefinition> safeRoutes = Map.copyOf(routes);
    for (Map.Entry<UUID, RouteDefinition> entry : safeRoutes.entrySet()) {
      when(routeDefinitions.findById(entry.getKey())).thenReturn(Optional.of(entry.getValue()));
    }
    when(routeDefinitions.snapshot()).thenReturn(safeRoutes);
    return routeDefinitions;
  }

  private static ConfigManager.ConfigView withSmartDispatcherMode(
      ConfigManager.ConfigView base, SmartDispatcherMode mode) {
    return new ConfigManager.ConfigView(
        base.configVersion(),
        base.debugEnabled(),
        base.locale(),
        base.storageSettings(),
        base.graphSettings(),
        base.autoStationSettings(),
        base.runtimeSettings(),
        base.spawnSettings(),
        base.trainConfigSettings(),
        base.reclaimSettings(),
        new ConfigManager.SmartDispatcherSettings(mode),
        base.healthSettings());
  }

  private static OccupancyClaim singleConflictClaim(
      OccupancyResource conflict, String trainName, CorridorDirection direction, Instant now) {
    return new OccupancyClaim(
        conflict,
        trainName,
        Optional.empty(),
        now,
        Duration.ZERO,
        Optional.ofNullable(direction),
        ClaimRole.MOVEMENT_REQUIRED);
  }

  private static RuntimeDispatchService.SmartRecoveryInput smartForwardUnlockInput(
      String trainName,
      Set<String> hardBlockers,
      boolean oppositeSingleConflict,
      boolean destinationPresent) {
    return new RuntimeDispatchService.SmartRecoveryInput(
        trainName,
        4000,
        SignalAspect.STOP,
        true,
        SignalComputationTrace.TokenState.PENDING,
        destinationPresent,
        hardBlockers == null ? 0 : hardBlockers.size(),
        hardBlockers == null ? Set.of() : hardBlockers,
        NodeId.of("A"),
        NodeId.of("B"),
        "route:test",
        0,
        "A",
        false,
        false,
        oppositeSingleConflict,
        hardBlockers != null && !hardBlockers.isEmpty(),
        "signal-authority-window-exceeded");
  }

  private static RuntimeDispatchService.SmartRecoveryInput singleRegionHardBarrierRecoveryInput(
      String trainName, boolean oppositeSingleConflict, String primaryReason) {
    return singleRegionHardBarrierRecoveryInput(
        trainName, oppositeSingleConflict, Set.of(), primaryReason);
  }

  private static RuntimeDispatchService.SmartRecoveryInput singleRegionHardBarrierRecoveryInput(
      String trainName,
      boolean oppositeSingleConflict,
      Set<String> hardBlockers,
      String primaryReason) {
    return new RuntimeDispatchService.SmartRecoveryInput(
        trainName,
        4000,
        SignalAspect.STOP,
        true,
        SignalComputationTrace.TokenState.PENDING,
        true,
        hardBlockers == null ? 0 : hardBlockers.size(),
        hardBlockers == null ? Set.of() : hardBlockers,
        NodeId.of("A"),
        NodeId.of("B"),
        "route:test",
        0,
        "A",
        true,
        false,
        oppositeSingleConflict,
        false,
        primaryReason);
  }

  private static RuntimeDispatchService.SmartRecoveryInput smartSelfRetainInput(String trainName) {
    return new RuntimeDispatchService.SmartRecoveryInput(
        trainName,
        4000,
        SignalAspect.STOP,
        true,
        SignalComputationTrace.TokenState.PENDING,
        false,
        0,
        Set.of(),
        NodeId.of("A"),
        NodeId.of("B"),
        "route:test",
        0,
        "A",
        true,
        false,
        true,
        false,
        "self-owned-single-opposite-direction");
  }

  private static RuntimeDispatchService.SmartRecoveryInput smartDrainUnlockInput(
      String trainName, Set<String> hardBlockers, boolean oppositeSingleConflict) {
    return new RuntimeDispatchService.SmartRecoveryInput(
        trainName,
        4000,
        SignalAspect.STOP,
        true,
        SignalComputationTrace.TokenState.PENDING,
        false,
        hardBlockers == null ? 0 : hardBlockers.size(),
        hardBlockers == null ? Set.of() : hardBlockers,
        NodeId.of("A"),
        NodeId.of("B"),
        "route:test",
        0,
        "A",
        true,
        false,
        oppositeSingleConflict,
        hardBlockers != null && !hardBlockers.isEmpty(),
        "self-owned-single-continuation-rejected");
  }

  private static SimpleOccupancyManager simpleOccupancyManagerWithSelfRetainCandidate() {
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    OccupancyResource conflict = OccupancyResource.forConflict("single:test:A~B");
    OccupancyRequest retain =
        singleConflictRequest("train-1", conflict, CorridorDirection.A_TO_B)
            .withResourceIntents(Map.of(conflict, ResourceIntent.PROTECTIVE_RETAIN));
    assertTrue(manager.acquire(retain).allowed());
    assertFalse(
        manager
            .canEnter(singleConflictRequest("train-1", conflict, CorridorDirection.B_TO_A))
            .allowed());
    assertTrue(manager.selfOwnedStaleRetainReleaseCandidate("train-1").isPresent());
    return manager;
  }

  private static OccupancyRequest switcherTraversalRequest(
      String trainName,
      Instant now,
      int currentIndex,
      List<NodeId> pathNodes,
      List<RailEdge> pathEdges,
      List<OccupancyResource> resources) {
    if (pathNodes.size() != pathEdges.size() + 1) {
      throw new IllegalArgumentException("pathNodes 与 pathEdges 数量不匹配");
    }
    List<DirectedTraversalContext.DirectedEdge> directedEdges = new ArrayList<>();
    for (int index = 0; index < pathEdges.size(); index++) {
      directedEdges.add(
          new DirectedTraversalContext.DirectedEdge(
              pathEdges.get(index).id(), pathNodes.get(index), pathNodes.get(index + 1)));
    }
    Map<String, Integer> entryOrders = new LinkedHashMap<>();
    Map<String, DirectedTraversalContext.SwitcherPathSignature> switcherSignatures =
        new LinkedHashMap<>();
    for (OccupancyResource resource : resources) {
      if (resource.kind() != ResourceKind.CONFLICT) {
        continue;
      }
      entryOrders.put(resource.key(), entryOrders.size());
      if (resource.key().startsWith("switcher:")) {
        switcherSignatures.put(
            resource.key(),
            new DirectedTraversalContext.SwitcherPathSignature(resource.key(), pathNodes));
      }
    }
    OccupancyRequest request =
        new OccupancyRequest(
            trainName,
            Optional.empty(),
            now,
            resources,
            Map.of(),
            entryOrders,
            0,
            AuthorizationPurpose.RUNTIME_MOVE);
    return request.withDirectedContext(
        Optional.of(
            new DirectedTraversalContext(
                trainName,
                Optional.empty(),
                currentIndex,
                Optional.of(pathNodes.get(0)),
                Optional.empty(),
                Optional.of(pathNodes.get(0)),
                pathNodes.size() < 2 ? Optional.empty() : Optional.of(pathNodes.get(1)),
                pathNodes,
                directedEdges,
                Map.of(),
                switcherSignatures,
                "TEST",
                1L,
                1L,
                "switcher-" + trainName,
                Optional.empty())));
  }

  /** 构造一条完整覆盖 from-node、edge 与 to-node 的单区间运行授权请求。 */
  private static OccupancyRequest completeTwoNodeAuthorityRequest(
      String trainName, Instant now, NodeId fromNode, NodeId toNode) {
    return completeTwoNodeAuthorityRequest(trainName, now, fromNode, toNode, Optional.empty());
  }

  /** 构造携带完整展开路径、route identity 与全部 NODE/EDGE movement window 的请求。 */
  private static OccupancyRequest completeExpandedAuthorityRequest(
      String trainName, Instant now, List<NodeId> pathNodes, RouteId routeId) {
    List<RailEdge> pathEdges = new ArrayList<>();
    List<OccupancyResource> resources = new ArrayList<>();
    for (int index = 0; index < pathNodes.size(); index++) {
      NodeId node = pathNodes.get(index);
      resources.add(OccupancyResource.forNode(node));
      if (index >= pathNodes.size() - 1) {
        continue;
      }
      NodeId next = pathNodes.get(index + 1);
      RailEdge edge =
          new RailEdge(EdgeId.undirected(node, next), node, next, 1, -1.0, true, Optional.empty());
      pathEdges.add(edge);
      resources.add(OccupancyResource.forEdge(edge.id()));
    }
    OccupancyRequest request =
        switcherTraversalRequest(trainName, now, 0, pathNodes, pathEdges, resources);
    DirectedTraversalContext context = request.directedContext().orElseThrow();
    return new OccupancyRequest(
            request.trainName(),
            Optional.of(routeId),
            request.now(),
            request.resources(),
            request.corridorDirections(),
            request.conflictEntryOrders(),
            request.priority(),
            request.purpose(),
            request.conflictReleaseHints(),
            request.resourceIntents())
        .withDirectedContext(
            Optional.of(
                new DirectedTraversalContext(
                    context.trainKey(),
                    Optional.of(routeId),
                    context.currentIndex(),
                    context.currentNode(),
                    context.lastPassedGraphNode(),
                    context.effectiveFromNode(),
                    context.effectiveToNode(),
                    context.expandedPathNodes(),
                    context.directedEdges(),
                    context.singleConflictDirections(),
                    context.switcherPathSignatures(),
                    context.source(),
                    context.occupancyVersion(),
                    context.progressVersion(),
                    context.requestId(),
                    context.authorityTokenId())));
  }

  private static OccupancyRequest completeTwoNodeAuthorityRequest(
      String trainName, Instant now, NodeId fromNode, NodeId toNode, Optional<RouteId> routeId) {
    RailEdge edge =
        new RailEdge(
            EdgeId.undirected(fromNode, toNode), fromNode, toNode, 1, -1.0, true, Optional.empty());
    OccupancyRequest request =
        switcherTraversalRequest(
            trainName,
            now,
            0,
            List.of(fromNode, toNode),
            List.of(edge),
            List.of(
                OccupancyResource.forNode(fromNode),
                OccupancyResource.forEdge(edge.id()),
                OccupancyResource.forNode(toNode)));
    DirectedTraversalContext context = request.directedContext().orElseThrow();
    return new OccupancyRequest(
            request.trainName(),
            routeId,
            request.now(),
            request.resources(),
            request.corridorDirections(),
            request.conflictEntryOrders(),
            request.priority(),
            request.purpose(),
            request.conflictReleaseHints(),
            request.resourceIntents())
        .withDirectedContext(
            Optional.of(
                new DirectedTraversalContext(
                    context.trainKey(),
                    routeId,
                    context.currentIndex(),
                    context.currentNode(),
                    context.lastPassedGraphNode(),
                    context.effectiveFromNode(),
                    context.effectiveToNode(),
                    context.expandedPathNodes(),
                    context.directedEdges(),
                    context.singleConflictDirections(),
                    context.switcherPathSignatures(),
                    context.source(),
                    context.occupancyVersion(),
                    context.progressVersion(),
                    context.requestId(),
                    context.authorityTokenId())));
  }

  private static OccupancyRequest invokeMarkDirectedRequest(
      RuntimeDispatchService service,
      OccupancyRequest request,
      SignalComputationTrace.Source source)
      throws Exception {
    java.lang.reflect.Method method =
        RuntimeDispatchService.class.getDeclaredMethod(
            "markDirectedRequest", OccupancyRequest.class, SignalComputationTrace.Source.class);
    method.setAccessible(true);
    return (OccupancyRequest) method.invoke(service, request, source);
  }

  private static VerifiedSwitcherDrainClaims.VerificationSnapshot switcherVerificationSnapshot(
      List<OccupancyClaim> claims) {
    return new VerifiedSwitcherDrainClaims.VerificationSnapshot(claims, 1L, 1L);
  }

  private static SignalPublicationGate.Decision invokePublicationGate(
      RuntimeDispatchService service, OccupancyRequest request, OccupancyDecision decision)
      throws Exception {
    java.lang.reflect.Method method =
        RuntimeDispatchService.class.getDeclaredMethod(
            "evaluatePublicationGate",
            String.class,
            OccupancyRequest.class,
            OccupancyDecision.class,
            SignalAspect.class,
            boolean.class,
            SignalComputationTrace.TokenState.class);
    method.setAccessible(true);
    return (SignalPublicationGate.Decision)
        method.invoke(
            service,
            request.trainName(),
            request,
            decision,
            SignalAspect.PROCEED,
            false,
            SignalComputationTrace.TokenState.ACTIVE);
  }

  private static OccupancyDecision invokeAdvisoryPreviewDecision(
      RuntimeDispatchService service,
      OccupancyRequest advisoryRequest,
      OccupancyDecision fallback,
      OccupancyRequest authorizationRequest)
      throws Exception {
    java.lang.reflect.Method method =
        RuntimeDispatchService.class.getDeclaredMethod(
            "previewAdvisoryLookaheadReadOnly",
            OccupancyRequest.class,
            OccupancyDecision.class,
            OccupancyRequest.class,
            OccupancyDecision.class);
    method.setAccessible(true);
    Object result =
        method.invoke(service, advisoryRequest, fallback, authorizationRequest, fallback);
    java.lang.reflect.Method decisionAccessor = result.getClass().getDeclaredMethod("decision");
    decisionAccessor.setAccessible(true);
    return (OccupancyDecision) decisionAccessor.invoke(result);
  }

  private static OccupancyRequest singleConflictRequest(
      String trainName, OccupancyResource conflict, CorridorDirection direction) {
    NodeId from = direction == CorridorDirection.B_TO_A ? NodeId.of("B") : NodeId.of("A");
    NodeId to = direction == CorridorDirection.B_TO_A ? NodeId.of("A") : NodeId.of("B");
    OccupancyRequest request =
        new OccupancyRequest(
            trainName,
            Optional.empty(),
            Instant.parse("2026-01-01T00:00:00Z"),
            List.of(conflict),
            Map.of(conflict.key(), direction),
            Map.of(conflict.key(), 0),
            0);
    return request.withDirectedContext(
        Optional.of(
            new DirectedTraversalContext(
                trainName,
                Optional.empty(),
                0,
                Optional.of(from),
                Optional.empty(),
                Optional.of(from),
                Optional.of(to),
                List.of(from, to),
                List.of(
                    new DirectedTraversalContext.DirectedEdge(
                        EdgeId.undirected(from, to), from, to)),
                Map.of(conflict.key(), direction),
                Map.of(),
                "TEST",
                -1L,
                -1L,
                "test",
                Optional.empty())));
  }

  private static OccupancyRequest singleConflictRequestWithoutPlanDirection(
      String trainName, OccupancyResource conflict) {
    NodeId from = NodeId.of("A");
    NodeId to = NodeId.of("B");
    OccupancyRequest request =
        new OccupancyRequest(
            trainName,
            Optional.empty(),
            Instant.parse("2026-01-01T00:00:00Z"),
            List.of(conflict),
            Map.of(),
            Map.of(conflict.key(), 0),
            0);
    return request.withDirectedContext(
        Optional.of(
            new DirectedTraversalContext(
                trainName,
                Optional.empty(),
                0,
                Optional.of(from),
                Optional.empty(),
                Optional.of(from),
                Optional.of(to),
                List.of(from, to),
                List.of(
                    new DirectedTraversalContext.DirectedEdge(
                        EdgeId.undirected(from, to), from, to)),
                Map.of(),
                Map.of(),
                "TEST",
                -1L,
                -1L,
                "test-empty-single-conflict-direction",
                Optional.empty())));
  }

  private static void stubSmartRecoveryNoCandidate(RuntimeDispatchService dispatchService) {
    when(dispatchService.smartRecoveryInput(anyString(), any(), any()))
        .thenAnswer(
            invocation ->
                RuntimeDispatchService.SmartRecoveryInput.fallback(
                    invocation.getArgument(0),
                    invocation.getArgument(1),
                    invocation.getArgument(2)));
    when(dispatchService.applySmartSelfOwnedStaleRetainRelease(any()))
        .thenReturn(RuntimeDispatchService.SmartRecoveryActionResult.skipped("not-candidate"));
    when(dispatchService.applySmartDrainUnlock(any()))
        .thenReturn(RuntimeDispatchService.SmartRecoveryActionResult.skipped("not-candidate"));
    when(dispatchService.applySmartForwardUnlock(any()))
        .thenReturn(RuntimeDispatchService.SmartRecoveryActionResult.skipped("not-candidate"));
  }

  private static OccupancyRequestContext singleConflictContext(
      String trainName,
      OccupancyResource conflict,
      CorridorDirection direction,
      RailEdge first,
      RailEdge second,
      NodeId firstNode,
      NodeId middleNode,
      NodeId lastNode) {
    DirectedTraversalContext directedContext =
        new DirectedTraversalContext(
            trainName,
            Optional.empty(),
            0,
            Optional.of(firstNode),
            Optional.of(firstNode),
            Optional.of(firstNode),
            Optional.of(middleNode),
            List.of(firstNode, middleNode, lastNode),
            List.of(
                new DirectedTraversalContext.DirectedEdge(first.id(), firstNode, middleNode),
                new DirectedTraversalContext.DirectedEdge(second.id(), middleNode, lastNode)),
            Map.of(conflict.key(), direction),
            Map.of(),
            AuthorizationPurpose.DEPOT_SPAWN.name(),
            0L,
            0L,
            "test-single-conflict",
            Optional.empty());
    OccupancyRequest request =
        new OccupancyRequest(
            trainName,
            Optional.empty(),
            Instant.parse("2026-01-01T00:00:01Z"),
            List.of(conflict),
            Map.of(conflict.key(), direction),
            Map.of(conflict.key(), 0),
            0,
            AuthorizationPurpose.DEPOT_SPAWN,
            Map.of(),
            Map.of(),
            Optional.of(directedContext));
    return new OccupancyRequestContext(
        request, List.of(firstNode, middleNode, lastNode), List.of(first, second));
  }

  private static OccupancyRequestContext singleConflictContextWithResources(
      String trainName,
      OccupancyResource conflict,
      CorridorDirection direction,
      RailEdge first,
      RailEdge second,
      NodeId firstNode,
      NodeId middleNode,
      NodeId lastNode,
      List<OccupancyResource> resources) {
    DirectedTraversalContext directedContext =
        new DirectedTraversalContext(
            trainName,
            Optional.empty(),
            0,
            Optional.of(firstNode),
            Optional.of(firstNode),
            Optional.of(firstNode),
            Optional.of(middleNode),
            List.of(firstNode, middleNode, lastNode),
            List.of(
                new DirectedTraversalContext.DirectedEdge(first.id(), firstNode, middleNode),
                new DirectedTraversalContext.DirectedEdge(second.id(), middleNode, lastNode)),
            Map.of(conflict.key(), direction),
            Map.of(),
            AuthorizationPurpose.DEPOT_SPAWN.name(),
            0L,
            0L,
            "test-single-conflict-with-resources",
            Optional.empty());
    OccupancyRequest request =
        new OccupancyRequest(
            trainName,
            Optional.empty(),
            Instant.parse("2026-01-01T00:00:01Z"),
            resources == null ? List.of(conflict) : List.copyOf(resources),
            Map.of(conflict.key(), direction),
            Map.of(conflict.key(), 0),
            0,
            AuthorizationPurpose.DEPOT_SPAWN,
            Map.of(),
            Map.of(),
            Optional.of(directedContext));
    return new OccupancyRequestContext(
        request, List.of(firstNode, middleNode, lastNode), List.of(first, second));
  }

  private static OccupancyRequestContext singleConflictPathContext(
      String trainName,
      OccupancyResource conflict,
      CorridorDirection direction,
      List<NodeId> nodes,
      List<RailEdge> edges,
      List<OccupancyResource> resources) {
    if (nodes == null || nodes.size() < 2 || edges == null || edges.isEmpty()) {
      throw new IllegalArgumentException("single conflict path must include nodes and edges");
    }
    List<DirectedTraversalContext.DirectedEdge> directedEdges = new ArrayList<>();
    int edgeCount = Math.min(edges.size(), nodes.size() - 1);
    for (int i = 0; i < edgeCount; i++) {
      directedEdges.add(
          new DirectedTraversalContext.DirectedEdge(
              edges.get(i).id(), nodes.get(i), nodes.get(i + 1)));
    }
    LinkedHashSet<OccupancyResource> resourceSet = new LinkedHashSet<>();
    resourceSet.add(conflict);
    if (resources != null) {
      resourceSet.addAll(resources);
    }
    DirectedTraversalContext directedContext =
        new DirectedTraversalContext(
            trainName,
            Optional.empty(),
            0,
            Optional.of(nodes.get(0)),
            Optional.of(nodes.get(0)),
            Optional.of(nodes.get(0)),
            Optional.of(nodes.get(1)),
            nodes,
            directedEdges,
            Map.of(conflict.key(), direction),
            Map.of(),
            AuthorizationPurpose.DEPOT_SPAWN.name(),
            0L,
            0L,
            "test-single-conflict-path",
            Optional.empty());
    OccupancyRequest request =
        new OccupancyRequest(
            trainName,
            Optional.empty(),
            Instant.parse("2026-01-01T00:00:01Z"),
            List.copyOf(resourceSet),
            Map.of(conflict.key(), direction),
            Map.of(conflict.key(), 0),
            0,
            AuthorizationPurpose.DEPOT_SPAWN,
            Map.of(),
            Map.of(),
            Optional.of(directedContext));
    return new OccupancyRequestContext(request, nodes, edges);
  }

  private static OccupancyRequestContext singleConflictBoundaryContext(
      String trainName,
      OccupancyResource conflict,
      CorridorDirection direction,
      RailEdge edge,
      NodeId firstNode,
      NodeId lastNode) {
    DirectedTraversalContext directedContext =
        new DirectedTraversalContext(
            trainName,
            Optional.empty(),
            0,
            Optional.of(firstNode),
            Optional.of(firstNode),
            Optional.of(firstNode),
            Optional.of(lastNode),
            List.of(firstNode, lastNode),
            List.of(new DirectedTraversalContext.DirectedEdge(edge.id(), firstNode, lastNode)),
            Map.of(conflict.key(), direction),
            Map.of(),
            AuthorizationPurpose.DEPOT_SPAWN.name(),
            0L,
            0L,
            "test-single-conflict-boundary",
            Optional.empty());
    OccupancyRequest request =
        new OccupancyRequest(
            trainName,
            Optional.empty(),
            Instant.parse("2026-01-01T00:00:01Z"),
            List.of(conflict),
            Map.of(conflict.key(), direction),
            Map.of(conflict.key(), 0),
            0,
            AuthorizationPurpose.DEPOT_SPAWN,
            Map.of(),
            Map.of(),
            Optional.of(directedContext));
    return new OccupancyRequestContext(request, List.of(firstNode, lastNode), List.of(edge));
  }

  private RuntimeDispatchService createServiceWithHardNodeBlocker() {
    NodeId current = NodeId.of("A");
    NodeId next = NodeId.of("B");
    RouteDefinition route =
        new RouteDefinition(RouteId.of("r"), List.of(current, next), Optional.empty());

    ConfigManager configManager = mock(ConfigManager.class);
    when(configManager.current()).thenReturn(testConfigView(20, 20.0));

    RailGraphService railGraphService = mock(RailGraphService.class);
    when(railGraphService.getSnapshot(any(UUID.class)))
        .thenReturn(
            Optional.of(
                new RailGraphService.RailGraphSnapshot(
                    graphWithSingleEdge(current, next, 10), Instant.now())));
    when(railGraphService.effectiveSpeedLimitBlocksPerSecond(any(), any(), any(), anyDouble()))
        .thenReturn(1000.0);

    RouteDefinitionCache routeDefinitions = mock(RouteDefinitionCache.class);
    when(routeDefinitions.findByCodes("op", "l1", "r1")).thenReturn(Optional.of(route));

    OccupancyManager occupancyManager = mockOccupancyManager();
    when(occupancyManager.snapshotClaims()).thenReturn(List.of());
    when(occupancyManager.acquire(any())).thenAnswer(allowProceed());
    when(occupancyManager.canEnter(any()))
        .thenAnswer(
            invocation -> {
              OccupancyRequest request = invocation.getArgument(0);
              OccupancyClaim blocker =
                  new OccupancyClaim(
                      OccupancyResource.forNode(next),
                      "front-train",
                      Optional.of(route.id()),
                      request.now(),
                      Duration.ZERO,
                      Optional.empty());
              return new OccupancyDecision(
                  true, request.now(), SignalAspect.PROCEED, List.of(blocker));
            });

    return new RuntimeDispatchService(
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
  }

  private static void backdateBlockerSnapshot(
      RuntimeDispatchService service, String trainName, Instant sampledAt) throws Exception {
    java.lang.reflect.Field field =
        RuntimeDispatchService.class.getDeclaredField("blockerSnapshots");
    field.setAccessible(true);
    @SuppressWarnings("unchecked")
    java.util.concurrent.ConcurrentMap<String, Object> blockerSnapshots =
        (java.util.concurrent.ConcurrentMap<String, Object>) field.get(service);
    Class<?> snapshotClass =
        Class.forName(
            "org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchService$BlockerSnapshot");
    java.lang.reflect.Constructor<?> constructor =
        snapshotClass.getDeclaredConstructor(Set.class, Instant.class);
    constructor.setAccessible(true);
    blockerSnapshots.put(trainName, constructor.newInstance(Set.of("front-train"), sampledAt));
  }
}
