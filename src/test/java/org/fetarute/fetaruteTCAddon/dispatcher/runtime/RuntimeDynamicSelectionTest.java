package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchTestFixtures.graphWithConflictFreeLinearPath;
import static org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchTestFixtures.testConfigView;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.company.model.RouteStopPassType;
import org.fetarute.fetaruteTCAddon.config.ConfigManager;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphService;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinitionCache;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteId;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchTestFixtures.FakeTrain;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchTestFixtures.TagStore;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.config.TrainConfigResolver;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.AuthorityHandoffSupport;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyClaim;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyDecision;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyManager;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyRequest;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyResource;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspect;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;

/** DYNAMIC 两条选台路径的目标粘性、物理先后与计划股道回归。 */
class RuntimeDynamicSelectionTest {
  /** 计划股道可达且有容量时保留它，咽喉繁忙只阻止发车；信号 tick 不得改选其他道。 */
  @Test
  void plannedDynamicPlatformSurvivesBusyApproachAndEquivalentDefinition() {
    NodeId current = NodeId.of("OP:S:ORIGIN:1");
    NodeId first = NodeId.of("OP:S:DEST:1");
    NodeId planned = NodeId.of("OP:S:DEST:3");
    UUID routeUuid = UUID.randomUUID();
    RouteDefinition route =
        new RouteDefinition(
            RouteId.of(routeUuid.toString()), List.of(current, first), Optional.empty());
    RailGraph graph = graphWithConflictFreeLinearPath(List.of(first, current, planned), 10);
    UUID worldId = UUID.randomUUID();
    TagStore tags =
        new TagStore(
            "planned-busy",
            "FTA_OPERATOR_CODE=op",
            "FTA_LINE_CODE=l1",
            "FTA_ROUTE_CODE=r1",
            "FTA_ROUTE_INDEX=0");
    ConfigManager config = mock(ConfigManager.class);
    when(config.current()).thenReturn(testConfigView(20, 20.0));
    RailGraphService graphs = mock(RailGraphService.class);
    when(graphs.getSnapshot(worldId))
        .thenReturn(Optional.of(new RailGraphService.RailGraphSnapshot(graph, Instant.now())));
    when(graphs.effectiveSpeedLimitBlocksPerSecond(any(), any(), any(), anyDouble(), anyDouble()))
        .thenReturn(1000.0);
    RouteDefinitionCache definitions = mock(RouteDefinitionCache.class);
    when(definitions.findByCodes("op", "l1", "r1")).thenReturn(Optional.of(route));
    when(definitions.findUuid(route.id())).thenReturn(Optional.of(routeUuid));
    RouteStop definition =
        new RouteStop(
            routeUuid,
            1,
            Optional.empty(),
            Optional.of(first.value()),
            Optional.empty(),
            RouteStopPassType.STOP,
            Optional.of("DYNAMIC:OP:S:DEST:[1,3,5]"));
    when(definitions.findStop(route.id(), 1)).thenReturn(Optional.of(definition));
    SignNodeRegistry signs = mock(SignNodeRegistry.class);
    when(signs.findByNodeId(any(), any()))
        .thenReturn(
            Optional.of(
                new SignNodeRegistry.SignNodeInfo(
                    new SignNodeDefinition(
                        planned, NodeType.STATION, Optional.empty(), Optional.empty()),
                    worldId,
                    "world",
                    0,
                    64,
                    0)));
    OccupancyManager occupancy = mockOccupancyManager();
    AtomicBoolean busy = new AtomicBoolean(true);
    OccupancyResource approach = OccupancyResource.forEdge(EdgeId.undirected(current, planned));
    Answer<OccupancyDecision> decision =
        invocation -> {
          OccupancyRequest request = invocation.getArgument(0);
          if (busy.get() && request.resourceList().contains(approach)) {
            return new OccupancyDecision(
                false,
                request.now(),
                SignalAspect.STOP,
                List.of(
                    new OccupancyClaim(
                        approach,
                        "approach-blocker",
                        Optional.empty(),
                        request.now(),
                        Duration.ZERO,
                        Optional.empty())));
          }
          return new OccupancyDecision(true, request.now(), SignalAspect.PROCEED, List.of());
        };
    when(occupancy.canEnter(any())).thenAnswer(decision);
    when(occupancy.acquire(any())).thenAnswer(decision);
    RuntimeDispatchService service =
        new RuntimeDispatchService(
            occupancy,
            graphs,
            definitions,
            new RouteProgressRegistry(),
            signs,
            mock(LayoverRegistry.class),
            new DwellRegistry(),
            config,
            null,
            new TrainConfigResolver(),
            null);
    ScheduledDeparturePlan plan = mock(ScheduledDeparturePlan.class);
    when(plan.plannedPlatformOf("planned-busy", routeUuid, 1))
        .thenReturn(Optional.of(planned.value()));
    service.stationStops().setPlan(plan);
    FakeTrain train = new FakeTrain(worldId, tags.properties, false);
    assertFalse(
        service.checkDeparture(
            train,
            new SignNodeDefinition(current, NodeType.STATION, Optional.empty(), Optional.empty())));
    assertEquals(
        List.of(current, planned),
        service.resolveEffectiveWaypointsForEvent("planned-busy", route));
    verify(tags.properties, never()).setDestination(any());

    when(definitions.findStop(route.id(), 1))
        .thenReturn(
            Optional.of(
                new RouteStop(
                    routeUuid,
                    1,
                    Optional.empty(),
                    Optional.of(first.value()),
                    Optional.empty(),
                    RouteStopPassType.STOP,
                    Optional.of("DYNAMIC:OP:S:DEST:[5,3,1,3]"))));
    when(plan.plannedPlatformOf("planned-busy", routeUuid, 1))
        .thenReturn(Optional.of(first.value()));
    service.handleSignalTick(train, false);
    assertEquals(
        List.of(current, planned),
        service.resolveEffectiveWaypointsForEvent("planned-busy", route));
    verify(tags.properties, never()).setDestination(any());
    busy.set(false);
    service.handleSignalTick(train, false);
    verify(tags.properties, atLeastOnce()).setDestination(planned.value());
    verify(tags.properties, never()).setDestination(first.value());
  }

  /** 后车先选台、前车随后登记等台时，实际发车门控必须让位并保留后车原目标。 */
  @Test
  void departureGateKeepsTheMaterializedTargetWhileYieldingToTheFrontTrain() {
    NodeId origin = NodeId.of("OP:S:A:1");
    NodeId ahead = NodeId.of("OP:S:APPROACH:1");
    NodeId first = NodeId.of("OP:S:B:1");
    NodeId target = NodeId.of("OP:S:B:3");
    UUID routeUuid = UUID.randomUUID();
    UUID frontUuid = UUID.randomUUID();
    RouteDefinition rearRoute =
        new RouteDefinition(RouteId.of("rear-route"), List.of(origin, first), Optional.empty());
    RouteDefinition frontRoute =
        new RouteDefinition(RouteId.of("front-route"), List.of(ahead, first), Optional.empty());
    RouteDefinitionCache definitions = mock(RouteDefinitionCache.class);
    when(definitions.findByCodes("op", "l1", "rear")).thenReturn(Optional.of(rearRoute));
    when(definitions.findByCodes("op", "l1", "front")).thenReturn(Optional.of(frontRoute));
    when(definitions.findStop(rearRoute.id(), 1))
        .thenReturn(
            Optional.of(
                new RouteStop(
                    routeUuid,
                    1,
                    Optional.empty(),
                    Optional.of(first.value()),
                    Optional.empty(),
                    RouteStopPassType.STOP,
                    Optional.of("DYNAMIC:OP:S:B:[1,3]"))));
    when(definitions.findStop(frontRoute.id(), 1))
        .thenReturn(
            Optional.of(
                new RouteStop(
                    frontUuid,
                    1,
                    Optional.empty(),
                    Optional.of(first.value()),
                    Optional.empty(),
                    RouteStopPassType.STOP,
                    Optional.of("DYNAMIC:OP:S:B:[1,3]"))));
    ConfigManager config = mock(ConfigManager.class);
    when(config.current()).thenReturn(testConfigView(20, 20.0, 3, 1));
    RailGraph graph = graphWithConflictFreeLinearPath(List.of(first, origin, ahead, target), 10);
    UUID world = UUID.randomUUID();
    RailGraphService graphs = mock(RailGraphService.class);
    when(graphs.getSnapshot(world))
        .thenReturn(Optional.of(new RailGraphService.RailGraphSnapshot(graph, Instant.now())));
    when(graphs.effectiveSpeedLimitBlocksPerSecond(any(), any(), any(), anyDouble(), anyDouble()))
        .thenReturn(1000.0);
    SignNodeRegistry signs = mock(SignNodeRegistry.class);
    when(signs.findByNodeId(any(), any()))
        .thenReturn(
            Optional.of(
                new SignNodeRegistry.SignNodeInfo(
                    new SignNodeDefinition(
                        target, NodeType.STATION, Optional.empty(), Optional.empty()),
                    world,
                    "world",
                    0,
                    64,
                    0)));
    java.util.List<OccupancyClaim> claims = new java.util.ArrayList<>();
    claims.add(
        new OccupancyClaim(
            OccupancyResource.forNode(first),
            "dweller",
            Optional.empty(),
            Instant.now(),
            Duration.ZERO,
            Optional.empty()));
    OccupancyManager occupancy = mockOccupancyManager();
    when(occupancy.snapshotClaims()).thenAnswer(invocation -> List.copyOf(claims));
    AtomicBoolean busy = new AtomicBoolean(true);
    OccupancyResource approach = OccupancyResource.forEdge(EdgeId.undirected(origin, ahead));
    Answer<OccupancyDecision> decision =
        invocation -> {
          OccupancyRequest request = invocation.getArgument(0);
          boolean blocked = busy.get() && request.resourceList().contains(approach);
          return new OccupancyDecision(
              !blocked,
              request.now(),
              blocked ? SignalAspect.STOP : SignalAspect.PROCEED,
              blocked
                  ? List.of(
                      new OccupancyClaim(
                          approach,
                          "throat-blocker",
                          Optional.empty(),
                          request.now(),
                          Duration.ZERO,
                          Optional.empty()))
                  : List.of());
        };
    when(occupancy.canEnter(any())).thenAnswer(decision);
    when(occupancy.acquire(any())).thenAnswer(decision);
    java.util.List<String> logs = new java.util.ArrayList<>();
    RouteProgressRegistry progress = new RouteProgressRegistry();
    RuntimeDispatchService service =
        new RuntimeDispatchService(
            occupancy,
            graphs,
            definitions,
            progress,
            signs,
            mock(LayoverRegistry.class),
            new DwellRegistry(),
            config,
            null,
            new TrainConfigResolver(),
            logs::add);
    TagStore rearTags =
        new TagStore(
            "rear",
            "FTA_OPERATOR_CODE=op",
            "FTA_LINE_CODE=l1",
            "FTA_ROUTE_CODE=rear",
            "FTA_ROUTE_INDEX=0");
    FakeTrain rear = new FakeTrain(world, rearTags.properties, false);
    progress.recordArrival(
        "rear", routeUuid, rearRoute, 0, origin, rearTags.properties, Instant.now());
    SignNodeDefinition originSign =
        new SignNodeDefinition(origin, NodeType.STATION, Optional.empty(), Optional.empty());
    assertFalse(service.checkDeparture(rear, originSign));
    assertEquals(
        List.of(origin, target), service.resolveEffectiveWaypointsForEvent("rear", rearRoute));
    TagStore frontTags =
        new TagStore(
            "front",
            "FTA_OPERATOR_CODE=op",
            "FTA_LINE_CODE=l1",
            "FTA_ROUTE_CODE=front",
            "FTA_ROUTE_INDEX=0");
    FakeTrain front = new FakeTrain(world, frontTags.properties, false);
    progress.recordArrival(
        "front", frontUuid, frontRoute, 0, ahead, frontTags.properties, Instant.now());
    assertFalse(
        service.checkDeparture(
            front,
            new SignNodeDefinition(ahead, NodeType.STATION, Optional.empty(), Optional.empty())));
    assertTrue(
        service
            .trainsWaitingForDynamicCapacity(List.of(OccupancyResource.forNode(target)))
            .contains("front"),
        "推进点选定的股道也应进入共同预订表");
    busy.set(false);
    assertFalse(service.checkDeparture(rear, originSign), "进路已经恢复，后车仍须让物理前车先用空台");
    assertEquals(
        List.of(origin, target), service.resolveEffectiveWaypointsForEvent("rear", rearRoute));
    assertTrue(
        logs.stream()
            .anyMatch(
                line ->
                    line.startsWith("DYNAMIC_PLATFORM_ORDER_WITHHELD")
                        && line.contains("train=rear")),
        () -> logs.toString());
    verify(rearTags.properties, never()).setDestination(any());
    assertTrue(
        service.checkDeparture(
            front,
            new SignNodeDefinition(ahead, NodeType.STATION, Optional.empty(), Optional.empty())),
        "后车让出内部预订后，物理前车应当可以发车");
    assertFalse(service.checkDeparture(rear, originSign), "前车预订期间后车继续等待原目标");
  }

  private static OccupancyManager mockOccupancyManager() {
    OccupancyManager manager =
        mock(OccupancyManager.class, withSettings().extraInterfaces(AuthorityHandoffSupport.class));
    when(((AuthorityHandoffSupport) manager).holdsHardAuthority(any())).thenReturn(true);
    when(((AuthorityHandoffSupport) manager).migrateAuthorityOwner(anyString(), anyString()))
        .thenReturn(true);
    return manager;
  }
}
