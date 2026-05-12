package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.bergerkiller.bukkit.tc.properties.TrainProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.company.model.RouteStopPassType;
import org.fetarute.fetaruteTCAddon.config.ConfigManager;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailEdge;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphService;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.SimpleRailGraph;
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
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.config.SpeedCurveType;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.config.TrainConfigResolver;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.ControlDiagnostics;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.CorridorDirection;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.HeadwayRule;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyClaim;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyDecision;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyManager;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyQueueEntry;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyQueueSnapshot;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyQueueSupport;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyRequest;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyResource;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.ResourceKind;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspect;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspectPolicy;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SimpleOccupancyManager;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeDefinition;
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
    assertTrue(train.launchCalls == 1);

    service.handleSignalTick(train, false);
    assertTrue(train.launchCalls == 1);
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

    OccupancyManager occupancyManager = mock(OccupancyManager.class);
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

    OccupancyManager occupancyManager = mock(OccupancyManager.class);
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

    service.handleSignalTick(new FakeTrain(worldId, tags.properties(), false), false);

    verify(tags.properties()).setDestination(allocated.value());
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

    OccupancyManager occupancyManager = mock(OccupancyManager.class);
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

    OccupancyManager occupancyManager = mock(OccupancyManager.class);
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
  void handleSignalTickStopsWhenAuthorityEndTooCloseEvenWithoutBlocker() {
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
    assertEquals(1, train.hardStopCalls);
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

    OccupancyManager occupancyManager = mock(OccupancyManager.class);
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

    verify(queueSupport).touchQueues(any());
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

    OccupancyManager occupancyManager = mock(OccupancyManager.class);
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

    OccupancyManager occupancyManager = mock(OccupancyManager.class);
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

    OccupancyManager occupancyManager = mock(OccupancyManager.class);
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
    assertEquals(1, train.hardStopCalls);
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
    assertEquals(SignalAspect.STOP, aspect);
    assertEquals(1, train.hardStopCalls);
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
  void handleSignalTickClearsBehindLookaheadClaimsBeforeAuthorizingFrontTrain() {
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
    OccupancyResource frontNode = OccupancyResource.forNode(b);
    OccupancyResource forwardEdge = OccupancyResource.forEdge(EdgeId.undirected(b, c));
    occupancyManager.acquire(
        new OccupancyRequest(
            "train-back",
            Optional.of(route.id()),
            Instant.now(),
            List.of(frontNode, forwardEdge),
            Map.of(),
            0));

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
            0));

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
  void handleSignalTickClearsBehindQueueEntryBeforeAuthorizingFrontTrain() {
    NodeId a = NodeId.of("A");
    NodeId switcher = NodeId.of("SW");
    NodeId b = NodeId.of("B");
    RouteDefinition route =
        new RouteDefinition(RouteId.of("r"), List.of(a, switcher, b), Optional.empty());
    TagStore frontTags =
        new TagStore(
            "train-front",
            "FTA_OPERATOR_CODE=op",
            "FTA_LINE_CODE=l1",
            "FTA_ROUTE_CODE=r1",
            "FTA_ROUTE_INDEX=1");
    TagStore rearTags = new TagStore("train-rear", "FTA_ROUTE_INDEX=0");
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
    when(routeDefinitions.findByCodes("op", "l1", "r1")).thenReturn(Optional.of(route));

    SimpleOccupancyManager occupancyManager =
        new SimpleOccupancyManager(
            HeadwayRule.fixed(Duration.ZERO), SignalAspectPolicy.defaultPolicy());
    OccupancyResource switcherConflict = OccupancyResource.forConflict("switcher:SW");
    occupancyManager.touchQueues(
        new OccupancyRequest(
            "train-rear",
            Optional.of(route.id()),
            Instant.now(),
            List.of(switcherConflict),
            Map.of(),
            Map.of(switcherConflict.key(), 0),
            0));

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
            null);

    FakeTrain train = new FakeTrain(worldId, frontTags.properties(), false);
    service.handleSignalTick(train, false);

    SignalAspect aspect = registry.get("train-front").orElseThrow().lastSignal();
    assertEquals(SignalAspect.PROCEED, aspect);
    assertTrue(
        occupancyManager.snapshotQueues().stream()
            .flatMap(snapshot -> snapshot.entries().stream())
            .noneMatch(entry -> entry.trainName().equalsIgnoreCase("train-rear")));
  }

  @Test
  void handleSignalTickKeepsSwitcherQueueBlockerFromDifferentRoute() {
    NodeId a = NodeId.of("A");
    NodeId switcher = NodeId.of("SW");
    NodeId b = NodeId.of("B");
    RouteDefinition route =
        new RouteDefinition(RouteId.of("r"), List.of(a, switcher, b), Optional.empty());
    TagStore frontTags =
        new TagStore(
            "train-front",
            "FTA_OPERATOR_CODE=op",
            "FTA_LINE_CODE=l1",
            "FTA_ROUTE_CODE=r1",
            "FTA_ROUTE_INDEX=1");
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
    when(routeDefinitions.findByCodes("op", "l1", "r1")).thenReturn(Optional.of(route));

    SimpleOccupancyManager occupancyManager =
        new SimpleOccupancyManager(
            HeadwayRule.fixed(Duration.ZERO), SignalAspectPolicy.defaultPolicy());
    OccupancyResource switcherConflict = OccupancyResource.forConflict("switcher:SW");
    occupancyManager.touchQueues(
        new OccupancyRequest(
            "crossing-train",
            Optional.of(RouteId.of("other-route")),
            Instant.now(),
            List.of(switcherConflict),
            Map.of(),
            Map.of(switcherConflict.key(), 0),
            0));

    RouteProgressRegistry registry = new RouteProgressRegistry();
    registry.initFromTags("train-front", frontTags.properties(), route);

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
        service
            .recentBlockerTrains("train-front", Duration.ofSeconds(30))
            .contains("crossing-train"));
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
                    graphWithLinearPath(List.of(a, m1, m2, m3, d), 10), Instant.now())));
    when(railGraphService.effectiveSpeedLimitBlocksPerSecond(any(), any(), any(), anyDouble()))
        .thenReturn(1000.0);

    RouteDefinitionCache routeDefinitions = mock(RouteDefinitionCache.class);
    when(routeDefinitions.findByCodes("op", "l1", "r1")).thenReturn(Optional.of(route));

    OccupancyManager occupancyManager = mock(OccupancyManager.class);
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

    OccupancyManager occupancyManager = mock(OccupancyManager.class);
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
                mock(OccupancyManager.class),
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
    doNothing().when(service).refreshSignalByName("survivor");

    service.scheduleSurvivorRefreshAfterTrainRemoved("leader", "survivor");
    service.handleTrainRemoved("leader");

    verify(service).refreshSignalByName("survivor");
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
    when(occupancyManager.acquire(any())).thenAnswer(allowProceed());
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
            2.0,
            8.0,
            0.15,
            1.0,
            1.0,
            3,
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
        new ConfigManager.SpawnSettings(false, 20, 200, 1, 5, 5, 40, 10, 2.0),
        train,
        new ConfigManager.ReclaimSettings(false, 3600L, 100, 60L),
        ConfigManager.HealthSettings.defaults());
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

    OccupancyManager occupancyManager = mock(OccupancyManager.class);
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

    OccupancyManager occupancyManager = mock(OccupancyManager.class);
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

    OccupancyManager occupancyManager = mock(OccupancyManager.class);
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
    when(configManager.current())
        .thenReturn(withRuntimeSettings(base, runtimeWithApproachSpeed(base, 6.0)));

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
    when(configManager.current())
        .thenReturn(withRuntimeSettings(base, runtimeWithApproachSpeed(base, 6.0)));

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

    ControlDiagnostics diagnostics = service.getDiagnostics("train-1").orElseThrow();
    double expectedEnvelope = Math.sqrt(6.0 * 6.0 + 2.0 * 0.25 * (120.0 - 10.0));
    assertEquals("station", diagnostics.approachKind());
    assertEquals("movement_authority", diagnostics.finalLimiterSource());
    assertTrue(diagnostics.approachReason().contains("target_edge_distance=10"));
    assertTrue(diagnostics.approachReason().contains("preview=true"));
    assertTrue(diagnostics.finalTargetBps() <= expectedEnvelope);
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

    OccupancyManager occupancyManager = mock(OccupancyManager.class);
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
  void movementAuthorityHardBlockerAppliesHardStopInsteadOfSpeedCurve() {
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
    OccupancyManager occupancyManager = mock(OccupancyManager.class);
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
    assertEquals("hard_stop", diagnostics.finalLimiterSource());
    assertEquals(1, train.hardStopCalls);
  }

  @Test
  void movementAuthorityDowngradesProceedWhenAuthorityEndIsTooClose() {
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

    OccupancyManager occupancyManager = mock(OccupancyManager.class);
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
    assertEquals(SignalAspect.STOP, diagnostics.currentSignal());
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

    OccupancyManager occupancyManager = mock(OccupancyManager.class);
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
    OccupancyManager occupancyManager = mock(OccupancyManager.class);
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
    assertFalse(diagnostics.destinationPresentWhileBlocked());
    assertEquals("-", diagnostics.retainedDestination());
    assertEquals("authorization_failure", diagnostics.blockedReason());
    verify(tags.properties()).clearDestinationRoute();
    verify(tags.properties()).clearDestination();
    verify(tags.properties(), never()).setDestination(any());
    assertEquals(1, train.hardStopCalls);
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

    ControlDiagnostics diagnostics = service.getDiagnostics("train-1").orElseThrow();
    assertEquals(SignalAspect.PROCEED, diagnostics.currentSignal());
    assertTrue(diagnostics.edgeSpeedLookaheadMinBps().isPresent());
    assertEquals(
        Math.sqrt(8.0 * 8.0 + 2.0 * 1.0 * 50.0),
        diagnostics.edgeSpeedLookaheadMinBps().getAsDouble(),
        1.0e-6);
    assertEquals("edge_speed_lookahead", diagnostics.finalLimiterSource());
  }

  private static final class FakeTrain implements RuntimeTrainHandle {
    private final UUID worldId;
    private final TrainProperties properties;
    private final boolean moving;
    private final double speedBlocksPerTick;
    private int launchCalls = 0;
    private int destroyCalls = 0;
    private int stopCalls = 0;
    private int hardStopCalls = 0;

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
    public void stopHard() {
      hardStopCalls++;
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

  @Test
  @SuppressWarnings("unchecked")
  void collectImpactedTrainsForResourceRefreshIncludesClaimsAndQueues() throws Exception {
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
            "collectImpactedTrainsForResourceRefresh", Set.class, String.class);
    method.setAccessible(true);
    Map<String, String> impacted =
        (Map<String, String>) method.invoke(service, Set.of(target), "spawn-train");

    assertEquals(2, impacted.size());
    assertTrue(impacted.containsKey("train-a"));
    assertTrue(impacted.containsKey("train-c"));
    assertFalse(impacted.containsKey("spawn-train"));
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
    OccupancyResource resource = OccupancyResource.forNode(NodeId.of("B"));
    OccupancyRequest request =
        new OccupancyRequest(
            "train-1", Optional.empty(), Instant.now(), List.of(resource), Map.of());

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
            "activateMovementAuthorizationToken",
            String.class,
            MovementAuthorizationToken.class,
            String.class);
    invalidate.setAccessible(true);
    issue.setAccessible(true);
    activate.setAccessible(true);

    invalidate.invoke(service, "train-1", HardStopReason.AUTHORIZATION_FAILURE);
    MovementAuthorizationToken token =
        (MovementAuthorizationToken)
            issue.invoke(
                service,
                "train-1",
                NodeId.of("A"),
                NodeId.of("B"),
                request,
                SignalAspect.PROCEED,
                Instant.now());

    assertTrue(service.isMovementInhibited("train-1"));
    assertFalse(token.active());

    assertTrue((boolean) activate.invoke(service, "train-1", token, "B"));
    assertFalse(service.isMovementInhibited("train-1"));
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
            "activateMovementAuthorizationToken",
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
    RuntimeDispatchService service =
        createMinimalService(mock(OccupancyManager.class), debugMessages);

    service.applySignalFromEvent("train-1", SignalAspect.PROCEED);

    RuntimeDispatchService.SignalRuntimeStats stats = service.signalRuntimeStats();
    assertEquals(1, stats.dirtyTrainCount());
    assertEquals(1, stats.coalescedEventCount());
  }

  @Test
  void hardStopToProceedRequiresAcquireCommit() throws Exception {
    RuntimeDispatchService service = createMinimalService();
    OccupancyResource resource = OccupancyResource.forNode(NodeId.of("B"));
    OccupancyRequest request =
        new OccupancyRequest(
            "train-1", Optional.empty(), Instant.now(), List.of(resource), Map.of());

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
            "activateMovementAuthorizationToken",
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
            issue.invoke(
                service,
                "train-1",
                NodeId.of("A"),
                NodeId.of("B"),
                request,
                SignalAspect.PROCEED,
                Instant.now());

    assertFalse(
        (boolean)
            hasValid.invoke(service, "train-1", NodeId.of("A"), NodeId.of("B"), List.of(resource)));
    assertTrue((boolean) activate.invoke(service, "train-1", pending, "B"));
    assertTrue(
        (boolean)
            hasValid.invoke(service, "train-1", NodeId.of("A"), NodeId.of("B"), List.of(resource)));
  }

  @Test
  void acquireEventDoesNotStopSameTrainReentrantly() throws Exception {
    RuntimeDispatchService service = createMinimalService();
    OccupancyResource resource = OccupancyResource.forNode(NodeId.of("B"));
    OccupancyRequest request =
        new OccupancyRequest(
            "train-1", Optional.empty(), Instant.now(), List.of(resource), Map.of());

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
            "activateMovementAuthorizationToken",
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
            issue.invoke(
                service,
                "train-1",
                NodeId.of("A"),
                NodeId.of("B"),
                request,
                SignalAspect.PROCEED,
                Instant.now());
    assertTrue((boolean) activate.invoke(service, "train-1", token, "B"));

    service.applySignalFromEvent("train-1", SignalAspect.STOP);

    RuntimeDispatchService.SignalRuntimeStats stats = service.signalRuntimeStats();
    assertEquals(1, stats.dirtyTrainCount());
    assertEquals(1, stats.coalescedEventCount());
    assertTrue(
        (boolean)
            hasValid.invoke(service, "train-1", NodeId.of("A"), NodeId.of("B"), List.of(resource)));
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

    OccupancyManager occupancyManager = mock(OccupancyManager.class);
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
    service.handleTrainRemoved(null);
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

    OccupancyManager occupancyManager = mock(OccupancyManager.class);
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

    OccupancyManager occupancyManager = mock(OccupancyManager.class);
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

  // ====== refreshSignalsForResources 测试 ======

  @Test
  void refreshSignalsForResourcesSkipsEmptyInput() {
    RuntimeDispatchService service = createMinimalService();
    // 不应抛异常
    service.refreshSignalsForResources(List.of(), "source");
    service.refreshSignalsForResources(null, "source");
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

  private RuntimeDispatchService createMinimalService() {
    return createMinimalService(mock(OccupancyManager.class), new ArrayList<>());
  }

  private RuntimeDispatchService createMinimalService(RouteDefinitionCache routeDefinitions) {
    return createMinimalService(
        mock(OccupancyManager.class),
        routeDefinitions,
        new RouteProgressRegistry(),
        new ArrayList<>());
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
      OccupancyManager occupancyManager,
      RouteDefinitionCache routeDefinitions,
      RouteProgressRegistry progressRegistry,
      List<String> debugMessages) {
    ConfigManager configManager = mock(ConfigManager.class);
    when(configManager.current()).thenReturn(testConfigView(20, 20.0));

    return new RuntimeDispatchService(
        occupancyManager,
        mock(RailGraphService.class),
        routeDefinitions,
        progressRegistry,
        mock(SignNodeRegistry.class),
        mock(LayoverRegistry.class),
        new DwellRegistry(),
        configManager,
        null,
        mock(TrainConfigResolver.class),
        debugMessages::add);
  }

  private static RouteStop routeStop(int sequence, NodeId nodeId, RouteStopPassType passType) {
    return new RouteStop(
        UUID.randomUUID(),
        sequence,
        Optional.empty(),
        Optional.of(nodeId.value()),
        Optional.empty(),
        passType,
        Optional.empty());
  }

  private static RouteStop dynamicStop(int sequence, NodeId nodeId, String notes) {
    return new RouteStop(
        UUID.randomUUID(),
        sequence,
        Optional.empty(),
        Optional.of(nodeId.value()),
        Optional.empty(),
        RouteStopPassType.STOP,
        Optional.of(notes));
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

    OccupancyManager occupancyManager = mock(OccupancyManager.class);
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
