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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bergerkiller.bukkit.tc.signactions.SignActionType;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
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
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyClaim;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyRequest;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyResource;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspect;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspectPolicy;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SimpleOccupancyManager;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeRegistry;
import org.fetarute.fetaruteTCAddon.dispatcher.signal.RuntimeDispatchRequestProvider;
import org.fetarute.fetaruteTCAddon.dispatcher.signal.RuntimeSignalReevaluationScheduler;
import org.fetarute.fetaruteTCAddon.dispatcher.signal.SignalEvaluator;
import org.fetarute.fetaruteTCAddon.dispatcher.signal.event.SignalEventBus;
import org.junit.jupiter.api.Test;

/** 到达事实与下一跳授权分离的运行时回归测试。 */
class RuntimeArrivalProgressTest {

  @Test
  void platformReleaseWakesUnqueuedCapacityWaiterOnTheNextTick() {
    PassArrivalFixture fixture = passArrivalFixture();
    List<Runnable> nextTickTasks = new ArrayList<>();
    List<String> reevaluated = new ArrayList<>();
    RuntimeSignalReevaluationScheduler scheduler =
        new RuntimeSignalReevaluationScheduler(
            nextTickTasks::add,
            trainName -> {
              reevaluated.add(trainName);
              fixture.service().handleSignalTick(fixture.train(), false);
            });
    RuntimeDispatchRequestProvider provider =
        new RuntimeDispatchRequestProvider(
            fixture.occupancy(), fixture.service()::trainsWaitingForDynamicCapacity);
    SignalEvaluator bridge = new SignalEvaluator(fixture.eventBus(), provider, scheduler::request);
    bridge.start();
    assertTrue(
        fixture
            .occupancy()
            .acquire(
                new OccupancyRequest(
                    "platform-owner",
                    Optional.empty(),
                    Instant.now(),
                    List.of(OccupancyResource.forNode(fixture.platform())),
                    Map.of()))
            .allowed());

    fixture.arrive();

    assertFalse(
        fixture.occupancy().snapshotQueues().stream()
            .flatMap(q -> q.entries().stream())
            .anyMatch(entry -> entry.trainName().equals("incoming")));
    assertTrue(nextTickTasks.isEmpty());
    OccupancyResource unrelated = OccupancyResource.forNode(NodeId.of("OP:S:OTHER:1"));
    assertTrue(
        fixture
            .occupancy()
            .acquire(
                new OccupancyRequest(
                    "unrelated-owner",
                    Optional.empty(),
                    Instant.now(),
                    List.of(unrelated),
                    Map.of()))
            .allowed());
    fixture.occupancy().releaseByTrain("unrelated-owner");
    assertTrue(nextTickTasks.isEmpty());
    fixture.occupancy().releaseByTrain("platform-owner");

    assertTrue(reevaluated.isEmpty());
    assertEquals(SignalAspect.STOP, fixture.progress().lastSignal());
    verify(fixture.train().properties(), never()).setDestination(anyString());
    assertEquals(1, nextTickTasks.size());

    nextTickTasks.remove(0).run();

    assertEquals(List.of("incoming"), reevaluated);
    verify(fixture.train().properties()).setDestination(fixture.platform().value());
    verify(fixture.train().properties(), never()).setDestination(fixture.approach().value());
    assertTrue(
        provider
            .trainsWaitingFor(List.of(OccupancyResource.forNode(fixture.platform())))
            .isEmpty());
    bridge.stop();
    scheduler.close();
  }

  @Test
  void platformReleaseWakesCapacityWaiterBeforeAnIntermediatePassNode() {
    PassArrivalFixture fixture = passArrivalFixture();
    List<Runnable> nextTickTasks = new ArrayList<>();
    RuntimeSignalReevaluationScheduler scheduler =
        new RuntimeSignalReevaluationScheduler(
            nextTickTasks::add,
            trainName -> fixture.service().handleSignalTick(fixture.train(), false));
    RuntimeDispatchRequestProvider provider =
        new RuntimeDispatchRequestProvider(
            fixture.occupancy(), fixture.service()::trainsWaitingForDynamicCapacity);
    SignalEvaluator bridge = new SignalEvaluator(fixture.eventBus(), provider, scheduler::request);
    bridge.start();
    List<OccupancyResource> resources = List.of(OccupancyResource.forNode(fixture.platform()));
    assertTrue(
        fixture
            .occupancy()
            .acquire(
                new OccupancyRequest(
                    "platform-owner", Optional.empty(), Instant.now(), resources, Map.of()))
            .allowed());

    fixture.service().handleSignalTick(fixture.train(), false);
    fixture.service().handleSignalTick(fixture.train(), false);

    assertEquals(0, fixture.progress().currentIndex());
    assertEquals(SignalAspect.STOP, fixture.progress().lastSignal());
    verify(fixture.train().properties(), never()).setDestination(anyString());
    assertTrue(nextTickTasks.isEmpty());
    fixture.occupancy().releaseByTrain("platform-owner");
    assertEquals(1, nextTickTasks.size());
    nextTickTasks.remove(0).run();

    verify(fixture.train().properties()).setDestination(fixture.approach().value());
    assertTrue(provider.trainsWaitingFor(resources).isEmpty());
    bridge.stop();
    scheduler.close();
  }

  @Test
  void capacityWakeupRechecksOccupancyWhenThePlatformIsTakenBeforeTheNextTick() {
    PassArrivalFixture fixture = passArrivalFixture();
    List<Runnable> nextTickTasks = new ArrayList<>();
    RuntimeSignalReevaluationScheduler scheduler =
        new RuntimeSignalReevaluationScheduler(
            nextTickTasks::add,
            trainName -> fixture.service().handleSignalTick(fixture.train(), false));
    RuntimeDispatchRequestProvider provider =
        new RuntimeDispatchRequestProvider(
            fixture.occupancy(), fixture.service()::trainsWaitingForDynamicCapacity);
    SignalEvaluator bridge = new SignalEvaluator(fixture.eventBus(), provider, scheduler::request);
    bridge.start();
    List<OccupancyResource> resources = List.of(OccupancyResource.forNode(fixture.platform()));
    assertTrue(
        fixture
            .occupancy()
            .acquire(
                new OccupancyRequest(
                    "first-owner", Optional.empty(), Instant.now(), resources, Map.of()))
            .allowed());
    fixture.arrive();

    fixture.occupancy().releaseByTrain("first-owner");
    assertTrue(
        fixture
            .occupancy()
            .acquire(
                new OccupancyRequest(
                    "new-owner", Optional.empty(), Instant.now(), resources, Map.of()))
            .allowed());
    assertEquals(1, nextTickTasks.size());
    nextTickTasks.remove(0).run();

    assertEquals(SignalAspect.STOP, fixture.progress().lastSignal());
    verify(fixture.train().properties(), never()).setDestination(anyString());
    assertTrue(
        fixture.occupancy().snapshotClaims().stream()
            .anyMatch(
                claim ->
                    claim.trainName().equals("new-owner") && resources.contains(claim.resource())));
    assertEquals(List.of("incoming"), provider.trainsWaitingFor(resources));

    fixture.occupancy().releaseByTrain("new-owner");
    assertEquals(1, nextTickTasks.size());
    nextTickTasks.remove(0).run();
    verify(fixture.train().properties()).setDestination(fixture.platform().value());
    bridge.stop();
    scheduler.close();
  }

  @Test
  void renamedCapacityWaiterReceivesReleaseNotificationsUnderTheCommittedOwner() {
    PassArrivalFixture fixture = passArrivalFixture();
    List<OccupancyResource> resources = List.of(OccupancyResource.forNode(fixture.platform()));
    assertTrue(
        fixture
            .occupancy()
            .acquire(
                new OccupancyRequest(
                    "platform-owner", Optional.empty(), Instant.now(), resources, Map.of()))
            .allowed());
    fixture.arrive();

    when(fixture.train().properties().getTrainName()).thenReturn("incoming-renamed");

    assertEquals(
        "incoming-renamed", fixture.service().handleRenameIfNeeded(fixture.train().properties()));
    assertEquals(
        List.of("incoming-renamed"), fixture.service().trainsWaitingForDynamicCapacity(resources));
    assertTrue(fixture.registry().get("incoming").isEmpty());
    assertEquals(1, fixture.registry().get("incoming-renamed").orElseThrow().currentIndex());
    assertTrue(fixture.service().isMovementInhibited("incoming-renamed"));
  }

  @Test
  void waypointMemberEnterRecordsArrivalBeforeWaitingForDynamicCapacity() {
    PassArrivalFixture fixture = passArrivalFixture();
    List<OccupancyResource> rearProtection =
        List.of(
            OccupancyResource.forNode(fixture.route().waypoints().get(0)),
            OccupancyResource.forEdge(
                EdgeId.undirected(fixture.route().waypoints().get(0), fixture.approach())));
    assertTrue(
        fixture
            .occupancy()
            .acquire(
                new OccupancyRequest(
                    "incoming", Optional.empty(), Instant.now(), rearProtection, Map.of()))
            .allowed());
    assertTrue(
        fixture
            .occupancy()
            .acquire(
                new OccupancyRequest(
                    "platform-owner",
                    Optional.empty(),
                    Instant.now(),
                    List.of(OccupancyResource.forNode(fixture.platform())),
                    Map.of()))
            .allowed());

    fixture.arrive();

    assertEquals(1, fixture.progress().currentIndex());
    assertEquals(Optional.of(fixture.approach()), fixture.progress().lastPassedGraphNode());
    assertEquals(SignalAspect.STOP, fixture.progress().lastSignal());
    assertEquals(0, fixture.train().launchCalls);
    verify(fixture.train().properties(), never()).setDestination(anyString());
    assertTrue(
        fixture.occupancy().snapshotClaims().stream()
            .anyMatch(
                claim ->
                    claim.trainName().equals("platform-owner")
                        && claim.resource().equals(OccupancyResource.forNode(fixture.platform()))));
    assertTrue(
        fixture.occupancy().snapshotClaims().stream()
            .filter(claim -> claim.trainName().equals("incoming"))
            .map(OccupancyClaim::resource)
            .toList()
            .containsAll(rearProtection));

    fixture.occupancy().releaseByTrain("platform-owner");
    fixture.service().handleSignalTick(fixture.train(), false);

    verify(fixture.train().properties()).setDestination(fixture.platform().value());
    verify(fixture.train().properties(), never()).setDestination(fixture.approach().value());
  }

  @Test
  void waypointArrivalKeepsCandidateRejectionAndActualArrivalEvidence() {
    PassArrivalFixture fixture = passArrivalFixture();
    assertTrue(
        fixture
            .occupancy()
            .acquire(
                new OccupancyRequest(
                    "platform-owner",
                    Optional.empty(),
                    Instant.now(),
                    List.of(OccupancyResource.forNode(fixture.platform())),
                    Map.of()))
            .allowed());

    fixture.arrive();
    fixture.arrive();

    RuntimeStopState stop = fixture.service().getActiveStopState("incoming").orElseThrow();
    assertTrue(stop.detail().contains("candidate=" + fixture.platform().value()));
    assertTrue(stop.detail().contains("node-occupied"));
    assertTrue(stop.detail().contains("owner=platform-owner"));
    assertTrue(stop.detail().contains("role=MOVEMENT_REQUIRED"));
    assertEquals(
        1,
        fixture.logs().stream()
            .filter(
                message ->
                    message.startsWith("SMART_ROUTE_ARRIVAL train=incoming")
                        && message.contains("index=1")
                        && message.contains("arrivedNode=" + fixture.approach().value()))
            .count());
    // 候选拒绝只供诊断，不能把授权窗口之外的站台占用升级为实际前向 blocker。
    assertTrue(stop.blockers().isEmpty());
  }

  @Test
  void waypointMemberEnterKeepsArrivalAndStopsWhenGraphIsUnavailable() {
    PassArrivalFixture fixture = passArrivalFixture();
    var snapshot = fixture.graphs().getSnapshot(fixture.train().worldId());
    when(fixture.graphs().getSnapshot(fixture.train().worldId())).thenReturn(Optional.empty());
    fixture.registry().updateSignal("incoming", SignalAspect.PROCEED, Instant.now());

    fixture.arrive();

    assertEquals(1, fixture.progress().currentIndex());
    assertEquals(Optional.of(fixture.approach()), fixture.progress().lastPassedGraphNode());
    assertEquals(SignalAspect.STOP, fixture.progress().lastSignal());
    assertTrue(fixture.train().hardStopCalls > 0);
    verify(fixture.train().properties(), never()).setDestination(anyString());
    assertEquals(0, fixture.train().launchCalls);

    when(fixture.graphs().getSnapshot(fixture.train().worldId())).thenReturn(snapshot);
    fixture.service().handleSignalTick(fixture.train(), false);

    verify(fixture.train().properties()).setDestination(fixture.platform().value());
    verify(fixture.train().properties(), never()).setDestination(fixture.approach().value());
  }

  @Test
  void waypointMemberEnterKeepsArrivalWhenTheOutgoingPathCannotBeBuilt() {
    PassArrivalFixture fixture = passArrivalFixture();
    var snapshot = fixture.graphs().getSnapshot(fixture.train().worldId());
    when(fixture.routes().findStop(fixture.route().id(), 2))
        .thenReturn(Optional.of(routeStop(2, fixture.platform(), RouteStopPassType.STOP)));
    RailGraph incomplete =
        graphWithConflictFreeLinearPath(fixture.route().waypoints().subList(0, 2), 80);
    when(fixture.graphs().getSnapshot(fixture.train().worldId()))
        .thenReturn(Optional.of(new RailGraphService.RailGraphSnapshot(incomplete, Instant.now())));
    fixture.registry().updateSignal("incoming", SignalAspect.PROCEED, Instant.now());

    fixture.arrive();

    assertEquals(1, fixture.progress().currentIndex());
    assertEquals(Optional.of(fixture.approach()), fixture.progress().lastPassedGraphNode());
    assertEquals(SignalAspect.STOP, fixture.progress().lastSignal());
    assertTrue(fixture.service().isMovementInhibited("incoming"));
    verify(fixture.train().properties(), never()).setDestination(anyString());
    assertEquals(0, fixture.train().launchCalls);

    when(fixture.graphs().getSnapshot(fixture.train().worldId())).thenReturn(snapshot);
    fixture.service().handleSignalTick(fixture.train(), false);

    verify(fixture.train().properties()).setDestination(fixture.platform().value());
    verify(fixture.train().properties(), never()).setDestination(fixture.approach().value());
  }

  @Test
  void dynamicStationArrivalRecordsTheActualPlatformInsteadOfThePlaceholder() {
    PassArrivalFixture fixture = passArrivalFixture();
    NodeId actualPlatform = NodeId.of("OP:S:PPK:2");
    when(fixture.routes().findStop(fixture.route().id(), 2))
        .thenReturn(Optional.of(dynamicStop(2, fixture.platform(), "DYNAMIC:OP:S:PPK:[1:2]")));

    fixture
        .service()
        .handleStationArrival(
            fixture.train(),
            new SignNodeDefinition(
                actualPlatform, NodeType.STATION, Optional.empty(), Optional.empty()));

    assertEquals(2, fixture.progress().currentIndex());
    assertEquals(Optional.of(actualPlatform), fixture.progress().lastPassedGraphNode());
    assertEquals(0, fixture.train().launchCalls);
  }

  @Test
  void waypointMemberEnterStopsWhenRouteDefinitionIsUnavailable() {
    assertMissingArrivalRouteStops(PassArrivalFixture::arrive);
  }

  @Test
  void progressTriggerStopsWhenRouteDefinitionIsUnavailable() {
    assertMissingArrivalRouteStops(
        fixture ->
            fixture
                .service()
                .handleProgressTrigger(
                    fixture.train(),
                    fixture.event(),
                    new SignNodeDefinition(
                        fixture.approach(),
                        NodeType.WAYPOINT,
                        Optional.empty(),
                        Optional.empty())));
  }

  @Test
  void stationArrivalStopsWhenRouteDefinitionIsUnavailable() {
    assertMissingArrivalRouteStops(
        fixture ->
            fixture
                .service()
                .handleStationArrival(
                    fixture.train(),
                    new SignNodeDefinition(
                        fixture.platform(), NodeType.STATION, Optional.empty(), Optional.empty())));
  }

  /** 线路证据暂缺时立即撤销旧放行，保留已确认进度和现场占用。 */
  private void assertMissingArrivalRouteStops(
      java.util.function.Consumer<PassArrivalFixture> arrival) {
    PassArrivalFixture fixture = passArrivalFixture();
    when(fixture.routes().findByCodes("op", "l1", "capacity-arrival")).thenReturn(Optional.empty());
    fixture.registry().updateSignal("incoming", SignalAspect.PROCEED, Instant.now());
    assertTrue(
        fixture
            .occupancy()
            .acquire(
                new OccupancyRequest(
                    "incoming",
                    Optional.empty(),
                    Instant.now(),
                    List.of(OccupancyResource.forNode(fixture.approach())),
                    Map.of()))
            .allowed());
    var claimsBefore = fixture.occupancy().snapshotClaims();

    arrival.accept(fixture);

    assertEquals(SignalAspect.STOP, fixture.progress().lastSignal());
    assertTrue(fixture.train().hardStopCalls > 0);
    assertTrue(fixture.service().isMovementInhibited("incoming"));
    assertEquals(0, fixture.progress().currentIndex());
    assertEquals(claimsBefore, fixture.occupancy().snapshotClaims());
    verify(fixture.train().properties(), never()).setDestination(anyString());
    assertEquals(0, fixture.train().launchCalls);
  }

  /** 使用真实进度与占用账本，从上一站进入 PASS，覆盖到达事实与下一跳授权的交接。 */
  private PassArrivalFixture passArrivalFixture() {
    NodeId station = NodeId.of("OP:S:RVS:1");
    NodeId approach = NodeId.of("OP:PPK:RVS:1:001");
    NodeId platform = NodeId.of("OP:S:PPK:1");
    RouteDefinition route =
        new RouteDefinition(
            RouteId.of("capacity-arrival"), List.of(station, approach, platform), Optional.empty());
    TagStore tags =
        new TagStore(
            "incoming",
            "FTA_OPERATOR_CODE=op",
            "FTA_LINE_CODE=l1",
            "FTA_ROUTE_CODE=capacity-arrival",
            "FTA_ROUTE_INDEX=0");
    UUID worldId = UUID.randomUUID();
    RailGraph graph = graphWithConflictFreeLinearPath(route.waypoints(), 80);
    ConfigManager config = mock(ConfigManager.class);
    when(config.current()).thenReturn(testConfigView(20, 20.0));
    RailGraphService graphs = mock(RailGraphService.class);
    when(graphs.getSnapshot(worldId))
        .thenReturn(Optional.of(new RailGraphService.RailGraphSnapshot(graph, Instant.now())));
    when(graphs.effectiveSpeedLimitBlocksPerSecond(any(), any(), any(), anyDouble()))
        .thenReturn(1000.0);
    RouteDefinitionCache routes = mock(RouteDefinitionCache.class);
    when(routes.findByCodes("op", "l1", "capacity-arrival")).thenReturn(Optional.of(route));
    when(routes.findStop(route.id(), 1))
        .thenReturn(Optional.of(routeStop(1, approach, RouteStopPassType.PASS)));
    when(routes.findStop(route.id(), 2))
        .thenReturn(Optional.of(dynamicStop(2, platform, "DYNAMIC:OP:S:PPK:[1:1]")));
    SignalEventBus eventBus = new SignalEventBus();
    SimpleOccupancyManager occupancy =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy(), eventBus);
    SignNodeRegistry signs = mock(SignNodeRegistry.class);
    when(signs.findByNodeId(eq(platform), any()))
        .thenReturn(
            Optional.of(
                new SignNodeRegistry.SignNodeInfo(
                    new SignNodeDefinition(
                        platform, NodeType.STATION, Optional.empty(), Optional.empty()),
                    worldId,
                    "world",
                    0,
                    64,
                    0)));
    RouteProgressRegistry registry = new RouteProgressRegistry();
    registry.initFromTags("incoming", tags.properties(), route);
    List<String> logs = new ArrayList<>();
    RuntimeDispatchService service =
        new RuntimeDispatchService(
            occupancy,
            graphs,
            routes,
            registry,
            signs,
            mock(LayoverRegistry.class),
            new DwellRegistry(),
            config,
            null,
            new TrainConfigResolver(),
            logs::add);
    var event = mock(com.bergerkiller.bukkit.tc.events.SignActionEvent.class);
    var world = mock(org.bukkit.World.class);
    when(world.getUID()).thenReturn(worldId);
    when(event.getWorld()).thenReturn(world);
    when(event.getAction()).thenReturn(SignActionType.MEMBER_ENTER);
    FakeTrain train = new FakeTrain(worldId, tags.properties(), false);

    return new PassArrivalFixture(
        approach, platform, route, routes, graphs, registry, occupancy, service, train, event, logs,
        eventBus);
  }

  private record PassArrivalFixture(
      NodeId approach,
      NodeId platform,
      RouteDefinition route,
      RouteDefinitionCache routes,
      RailGraphService graphs,
      RouteProgressRegistry registry,
      SimpleOccupancyManager occupancy,
      RuntimeDispatchService service,
      FakeTrain train,
      com.bergerkiller.bukkit.tc.events.SignActionEvent event,
      List<String> logs,
      SignalEventBus eventBus) {
    void arrive() {
      service.handleWaypointMemberEnter(
          train,
          event,
          new SignNodeDefinition(approach, NodeType.WAYPOINT, Optional.empty(), Optional.empty()));
    }

    RouteProgressRegistry.RouteProgressEntry progress() {
      return registry.get("incoming").orElseThrow();
    }
  }
}
