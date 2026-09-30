package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchTestFixtures.sectionlessGraph;
import static org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchTestFixtures.testConfigView;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.config.ConfigManager;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailEdge;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphService;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.SimpleRailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.interlocking.RailEdgeFootprint;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.interlocking.RailFootprintCell;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.interlocking.RailInterlockingState;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinitionCache;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteId;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchTestFixtures.FakeTrain;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchTestFixtures.TagStore;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.config.TrainConfigResolver;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.AuthorityHandoffSupport;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.ClaimRole;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyClaim;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyDecision;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyManager;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyRequest;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyResource;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyResourceResolver;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.ResourceIntent;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspect;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspectPolicy;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SimpleOccupancyManager;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeRegistry;
import org.junit.jupiter.api.Test;

/**
 * 位置保持（当前位置保护、停车保持）对当前边上物理联锁区的取舍，照实服 2026-09-27 OFL 车库口复刻。
 *
 * <p>DS 停在 MLU:1:004，车头刚越过节点牌子，节点模型认为它进了 MLU:1:004→岔口 这条边；边末端 4 格处与 1 号库线交叠（联锁区），
 * 车体离交叠格还有二十几格。旧行为按当前边整体保持，把交叠格一起扣住，回库的 MT 进不了库，两车顶牛到关服。
 *
 * <p>独立成类：{@code RuntimeDispatchServiceTest} 已贴着 SpotBugs 单类 1000 方法的上限，再加会整类跳过静态分析。
 */
class RuntimeDispatchPositionHoldTest {

  private static final String DS_TRAIN = "SURC-DS-LH-0368";
  private static final String MT_TRAIN = "SURC-MT-LO-6900";

  /** 制动中照旧整条保持（车头可能压进去）；停稳且整列足迹没压到交叠格后放掉它，库线随即可排；停着但足迹读不到时 fail-retain。 */
  @Test
  void stopRetainReleasesForwardCrossingOnceStationaryBodyIsClearOfIt() throws Exception {
    UUID worldId = UUID.randomUUID();
    OflJunctionFixture fixture = oflDepotJunction(worldId);
    RouteDefinition route =
        new RouteDefinition(
            RouteId.of("DS-1W_FullD"),
            List.of(fixture.behind(), fixture.current(), fixture.junction()),
            Optional.empty());
    SimpleOccupancyManager manager = occupancyManager();
    RuntimeDispatchService service =
        service(manager, fixture, worldId, route, new RouteProgressRegistry());

    retainStop(service, route, fixture, fixture.dsTrain(worldId, true));

    assertTrue(manager.getClaim(fixture.crossingZone()).isPresent(), "制动中车头仍可能压进交叠格：按当前边整体保持");
    assertFalse(manager.canEnter(fixture.depotRunIn()).allowed());

    retainStop(service, route, fixture, fixture.dsTrain(worldId, false));

    assertTrue(manager.getClaim(fixture.crossingZone()).isEmpty(), "停稳且车体没压到交叠格：停车保持不再扣它");
    assertTrue(
        manager.getClaim(OccupancyResource.forEdge(fixture.approach().id())).isPresent(),
        "当前边本身照旧保持");
    assertTrue(manager.canEnter(fixture.depotRunIn()).allowed());

    SimpleOccupancyManager unobserved = occupancyManager();
    FakeTrain footprintUnavailable = fixture.dsTrain(worldId, false);
    footprintUnavailable.liveRailFootprintCells = Optional.empty();
    retainStop(
        service(unobserved, fixture, worldId, route, new RouteProgressRegistry()),
        route,
        fixture,
        footprintUnavailable);

    assertTrue(
        unobserved.getClaim(fixture.crossingZone()).isPresent(), "停着但足迹读不到：fail-retain，按当前边整体保持");
  }

  /**
   * 同一现场走完整个信号 tick：前方被 MT 挡住时，当前位置保护与停车保持都不能再把没压到的交叠格占回来——只改其中一处的话，另一处每个 tick 都会把它重新扣上，MT 照样进不了库。
   */
  @Test
  void signalTickPositionHoldFollowsStationaryFootprintAtForwardCrossing() {
    UUID worldId = UUID.randomUUID();
    OflJunctionFixture fixture = oflDepotJunction(worldId);

    List<OccupancyRequest> stationaryHolds = signalTickPositionHolds(fixture, worldId, false);
    List<OccupancyRequest> brakingHolds = signalTickPositionHolds(fixture, worldId, true);

    OccupancyResource approachEdge = OccupancyResource.forEdge(fixture.approach().id());
    assertTrue(
        stationaryHolds.stream().anyMatch(request -> request.resourceList().contains(approachEdge)),
        "tick 必须走到位置保持，当前边照旧保持");
    assertTrue(
        stationaryHolds.stream()
            .noneMatch(request -> request.resourceList().contains(fixture.crossingZone())),
        "停稳且车体没压到交叠格：当前位置保护与停车保持都不再扣它");
    assertTrue(
        brakingHolds.stream()
            .anyMatch(request -> request.resourceList().contains(fixture.crossingZone())),
        "制动中按当前边整体保持");
  }

  /** 对 OFL 夹具跑一次信号 tick（前方被 MT 挡住），返回本 tick 以保持类意图申请的请求。 */
  private static List<OccupancyRequest> signalTickPositionHolds(
      OflJunctionFixture fixture, UUID worldId, boolean moving) {
    RouteDefinition route =
        new RouteDefinition(
            RouteId.of("DS-1W_FullD"),
            List.of(fixture.current(), fixture.junction(), fixture.beyond()),
            Optional.empty());
    OccupancyManager occupancyManager =
        mock(OccupancyManager.class, withSettings().extraInterfaces(AuthorityHandoffSupport.class));
    when(((AuthorityHandoffSupport) occupancyManager).holdsHardAuthority(any())).thenReturn(true);
    when(((AuthorityHandoffSupport) occupancyManager)
            .migrateAuthorityOwner(anyString(), anyString()))
        .thenReturn(true);
    when(occupancyManager.snapshotClaims()).thenReturn(List.of());
    OccupancyClaim mtAhead =
        new OccupancyClaim(
            OccupancyResource.forEdge(fixture.exit().id()),
            MT_TRAIN,
            Optional.empty(),
            Instant.now(),
            Duration.ZERO,
            Optional.empty(),
            ClaimRole.MOVEMENT_REQUIRED);
    when(occupancyManager.canEnter(any()))
        .thenAnswer(
            invocation -> {
              OccupancyRequest request = invocation.getArgument(0);
              return new OccupancyDecision(
                  false, request.now(), SignalAspect.STOP, List.of(mtAhead));
            });
    List<OccupancyRequest> acquired = new ArrayList<>();
    when(occupancyManager.acquire(any()))
        .thenAnswer(
            invocation -> {
              OccupancyRequest request = invocation.getArgument(0);
              acquired.add(request);
              return new OccupancyDecision(true, request.now(), SignalAspect.PROCEED, List.of());
            });
    RouteProgressRegistry registry = new RouteProgressRegistry();
    registry.initFromTags(DS_TRAIN, dsTags().properties(), route);
    FakeTrain train = fixture.dsTrain(worldId, moving);

    service(occupancyManager, fixture, worldId, route, registry).handleSignalTick(train, false);

    return acquired.stream()
        .filter(
            request ->
                request.resourceList().stream()
                    .map(request::intentFor)
                    .anyMatch(
                        intent ->
                            intent == ResourceIntent.HOLD_ONLY
                                || intent == ResourceIntent.PROTECTIVE_RETAIN))
        .toList();
  }

  /**
   * 1 道 MLU:1:004→岔口 的边末端 4 格与 1 号库线交叠一格（联锁区）；DS 车体停在 MLU:1:004 前后，离交叠格二十几格。岔口后留一段清出边，供行车授权跨过联锁区。
   */
  private static OflJunctionFixture oflDepotJunction(UUID worldId) {
    NodeId behind = NodeId.of("SURC:OFL:MLU:1:005");
    NodeId current = NodeId.of("SURC:OFL:MLU:1:004");
    NodeId junction = NodeId.of("SWITCHER:Towny:-516:77:2268");
    NodeId beyond = NodeId.of("SURC:OFL:MLU:1:003");
    NodeId depotSwitcher = NodeId.of("SWITCHER:Towny:-515:77:2272");
    NodeId depotLeadEnd = NodeId.of("SURC:D:OFL:1:001");
    RailFootprintCell body = new RailFootprintCell(-516, 77, 2296);
    RailFootprintCell crossing = new RailFootprintCell(-516, 77, 2272);
    RailEdge rear = edge(behind, current, 48);
    RailEdge approach = edge(current, junction, 28);
    RailEdge exit = edge(junction, beyond, 60);
    RailEdge depotLead = edge(depotSwitcher, depotLeadEnd, 17);
    Map<EdgeId, RailEdge> edges =
        Map.of(
            rear.id(), rear, approach.id(), approach, exit.id(), exit, depotLead.id(), depotLead);
    Map<EdgeId, RailEdgeFootprint> footprints =
        Map.of(
            rear.id(),
            new RailEdgeFootprint(1, true, Set.of(new RailFootprintCell(-516, 77, 2320))),
            approach.id(),
            new RailEdgeFootprint(1, true, Set.of(body, crossing)),
            exit.id(),
            new RailEdgeFootprint(1, true, Set.of(new RailFootprintCell(-516, 77, 2240))),
            depotLead.id(),
            new RailEdgeFootprint(
                1, true, Set.of(crossing, new RailFootprintCell(-515, 77, 2274))));
    RailInterlockingState interlocking =
        RailInterlockingState.from(worldId, edges.keySet(), footprints);
    SimpleRailGraph graph =
        new SimpleRailGraph(
            Map.of(
                behind, new RailNodeTest(behind),
                current, new RailNodeTest(current),
                junction, new RailNodeTest(junction),
                beyond, new RailNodeTest(beyond),
                depotSwitcher, new RailNodeTest(depotSwitcher),
                depotLeadEnd, new RailNodeTest(depotLeadEnd)),
            edges,
            Set.of(),
            interlocking);
    OccupancyResource crossingZone =
        OccupancyResourceResolver.resourcesForEdge(graph, approach).stream()
            .filter(OccupancyResourceResolver::isInterlockingConflict)
            .findFirst()
            .orElseThrow();
    OccupancyRequest depotRunIn =
        new OccupancyRequest(
            MT_TRAIN,
            Optional.empty(),
            Instant.now(),
            OccupancyResourceResolver.resourcesForEdge(graph, depotLead),
            Map.of());
    return new OflJunctionFixture(
        graph,
        sectionlessGraph(graph),
        behind,
        current,
        junction,
        beyond,
        approach,
        exit,
        body,
        crossingZone,
        depotRunIn);
  }

  private static RailEdge edge(NodeId from, NodeId to, int lengthBlocks) {
    return new RailEdge(
        EdgeId.undirected(from, to), from, to, lengthBlocks, -1.0, true, Optional.empty());
  }

  private static SimpleOccupancyManager occupancyManager() {
    return new SimpleOccupancyManager(
        (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
  }

  /**
   * 信号 tick 通过 {@link RailGraphService} 取图，这里给它去掉单线区段语义的视图。
   *
   * @param registry 进度登记表；停车保持直接调用时留空，信号 tick 需要预先登记 DS 的 route 进度
   */
  private static RuntimeDispatchService service(
      OccupancyManager occupancyManager,
      OflJunctionFixture fixture,
      UUID worldId,
      RouteDefinition route,
      RouteProgressRegistry registry) {
    ConfigManager configManager = mock(ConfigManager.class);
    when(configManager.current()).thenReturn(testConfigView(20, 20.0));
    RailGraphService railGraphService = mock(RailGraphService.class);
    when(railGraphService.getSnapshot(worldId))
        .thenReturn(
            Optional.of(
                new RailGraphService.RailGraphSnapshot(fixture.doubleTrack(), Instant.now())));
    when(railGraphService.effectiveSpeedLimitBlocksPerSecond(
            any(), any(), any(), anyDouble(), anyDouble()))
        .thenReturn(1000.0);
    RouteDefinitionCache routeDefinitions = mock(RouteDefinitionCache.class);
    when(routeDefinitions.findByCodes("op", "l1", "r1")).thenReturn(Optional.of(route));
    return new RuntimeDispatchService(
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
  }

  private static TagStore dsTags() {
    return new TagStore(
        DS_TRAIN,
        "FTA_OPERATOR_CODE=op",
        "FTA_LINE_CODE=l1",
        "FTA_ROUTE_CODE=r1",
        "FTA_ROUTE_INDEX=0");
  }

  /** 停车保持停在 {@code current}（route index 1），与信号 tick 的停车入口是同一个私有方法。 */
  private static void retainStop(
      RuntimeDispatchService service,
      RouteDefinition route,
      OflJunctionFixture fixture,
      RuntimeTrainHandle train)
      throws Exception {
    Method method =
        RuntimeDispatchService.class.getDeclaredMethod(
            "retainStopOccupancy",
            String.class,
            RouteDefinition.class,
            int.class,
            NodeId.class,
            Optional.class,
            RailGraph.class,
            Instant.class,
            RuntimeTrainHandle.class,
            Set.class);
    method.setAccessible(true);
    method.invoke(
        service,
        DS_TRAIN,
        route,
        1,
        fixture.current(),
        Optional.empty(),
        fixture.graph(),
        Instant.now(),
        train,
        Set.of());
  }

  /** OFL 车库口夹具：DS 停在 {@code approach} 起点，MT 以 {@code depotRunIn} 进库。 */
  private record OflJunctionFixture(
      RailGraph graph,
      RailGraph doubleTrack,
      NodeId behind,
      NodeId current,
      NodeId junction,
      NodeId beyond,
      RailEdge approach,
      RailEdge exit,
      RailFootprintCell body,
      OccupancyResource crossingZone,
      OccupancyRequest depotRunIn) {

    FakeTrain dsTrain(UUID worldId, boolean moving) {
      FakeTrain train = new FakeTrain(worldId, dsTags().properties(), moving);
      train.liveRailFootprintCells = Optional.of(Set.of(body));
      return train;
    }
  }
}
