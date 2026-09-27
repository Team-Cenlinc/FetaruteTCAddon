package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchTestFixtures.routeStop;
import static org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchTestFixtures.sectionlessGraph;
import static org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchTestFixtures.testConfigView;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import com.bergerkiller.bukkit.tc.controller.components.RailState;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
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
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.AuthorityHandoffSupport;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.ClaimRole;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyClaim;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyDecision;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyManager;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyRequest;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyResource;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.ResourceIntent;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspect;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeRegistry;
import org.junit.jupiter.api.Test;

/**
 * 硬授权窗口不越过前方第一个计划停车点。
 *
 * <p>照实服 2026-09-27 SPB:1 进站复刻：{@code SPB:JBS:1:001 —100— SPB:1 —51— PTK:SPB:1:002 —100—
 * PTK:1}。前车留在 {@code PTK:SPB:1:002} 的尾部保护在站台之后；车头离站台不到“安全余量”时，窗口会越过站台碰到它，本车被挡在站外，站台明明空着——
 * MT-LP-4909 就这样等了 143 秒，把后车堵在了 SPB 汇合岔上。SPB:1 是计划停车站时，窗口在站台收住；是通过点时照旧伸过去。
 */
class HardAuthorityPlannedStopTest {

  private static final String TRAIN = "SURC-MT-LP-4909";
  private static final NodeId APPROACH = NodeId.of("SURC:SPB:JBS:1:001");
  private static final NodeId PLATFORM = NodeId.of("SURC:S:SPB:1");
  private static final NodeId BEYOND = NodeId.of("SURC:PTK:SPB:1:002");
  private static final NodeId NEXT_STATION = NodeId.of("SURC:S:PTK:1");

  /** 停着、离站台 10 格：以前要等站台之后的尾部保护清掉才起步，现在直接进站。 */
  @Test
  void stationaryTrainEntersThePlatformItStopsAt() {
    TickResult result = signalTick(RouteStopPassType.STOP, false, 90.0);

    assertFalse(result.requestedBeyond(), "要在 SPB:1 停车，站台之后的资源发车前用不到");
    assertNotEquals(SignalAspect.STOP, result.aspect());
  }

  /** 行进中（10 bps）同样在站台收住，不会因为站台之后的尾部保护在站外停车。 */
  @Test
  void movingTrainApproachingItsStopIsNotHeldByResourcesBeyondThePlatform() {
    TickResult result = signalTick(RouteStopPassType.STOP, true, 40.0);

    assertFalse(result.requestedBeyond());
    assertNotEquals(SignalAspect.STOP, result.aspect());
  }

  /** 对照：SPB:1 只是通过点时，窗口照旧伸过站台，被前车尾部保护挡住。 */
  @Test
  void passingTrainStillNeedsTheTrackBeyondThePlatform() {
    TickResult result = signalTick(RouteStopPassType.PASS, false, 90.0);

    assertTrue(result.requestedBeyond(), "不停车就要继续往前开，窗口必须伸过站台");
    assertEquals(SignalAspect.STOP, result.aspect());
  }

  private record TickResult(boolean requestedBeyond, SignalAspect aspect) {}

  /**
   * 列车从站前节点出发跑一拍信号 tick；站台之后的节点已被前车以尾部保护占着。
   *
   * @param platformPassType SPB:1 在本 route 里的停站类型
   * @param headX 车头 x 坐标（节点坐标沿 x 轴与边长一致）
   */
  private static TickResult signalTick(
      RouteStopPassType platformPassType, boolean moving, double headX) {
    UUID worldId = UUID.randomUUID();
    RouteDefinition route =
        new RouteDefinition(
            RouteId.of("MT-2F_Short"), List.of(APPROACH, PLATFORM, NEXT_STATION), Optional.empty());
    TagStore tags =
        new TagStore(
            TRAIN,
            "FTA_OPERATOR_CODE=op",
            "FTA_LINE_CODE=l1",
            "FTA_ROUTE_CODE=r1",
            "FTA_ROUTE_INDEX=0");
    ConfigManager configManager = mock(ConfigManager.class);
    when(configManager.current()).thenReturn(testConfigView(20, 20.0));
    RailGraphService railGraphService = mock(RailGraphService.class);
    when(railGraphService.getSnapshot(worldId))
        .thenReturn(
            Optional.of(
                new RailGraphService.RailGraphSnapshot(
                    sectionlessGraph(spbApproach()), Instant.now())));
    when(railGraphService.effectiveSpeedLimitBlocksPerSecond(any(), any(), any(), anyDouble()))
        .thenReturn(1000.0);
    RouteDefinitionCache routeDefinitions = mock(RouteDefinitionCache.class);
    when(routeDefinitions.findByCodes("op", "l1", "r1")).thenReturn(Optional.of(route));
    when(routeDefinitions.findStop(eq(route.id()), eq(1)))
        .thenReturn(Optional.of(routeStop(1, PLATFORM, platformPassType)));
    when(routeDefinitions.findStop(eq(route.id()), eq(2)))
        .thenReturn(Optional.of(routeStop(2, NEXT_STATION, RouteStopPassType.STOP)));

    OccupancyManager occupancyManager =
        mock(OccupancyManager.class, withSettings().extraInterfaces(AuthorityHandoffSupport.class));
    when(((AuthorityHandoffSupport) occupancyManager).holdsHardAuthority(any())).thenReturn(true);
    when(((AuthorityHandoffSupport) occupancyManager)
            .migrateAuthorityOwner(anyString(), anyString()))
        .thenReturn(true);
    when(occupancyManager.snapshotClaims()).thenReturn(List.of());
    OccupancyResource beyond = OccupancyResource.forNode(BEYOND);
    OccupancyClaim precedingTail =
        new OccupancyClaim(
            beyond,
            "SURC-MT-LP-2989",
            Optional.empty(),
            Instant.now(),
            Duration.ZERO,
            Optional.empty(),
            ClaimRole.PROTECTIVE_RETAIN);
    List<OccupancyRequest> hardRequests = new ArrayList<>();
    when(occupancyManager.canEnter(any()))
        .thenAnswer(
            invocation -> {
              OccupancyRequest request = invocation.getArgument(0);
              if (request.resourceList().stream()
                  .anyMatch(
                      resource ->
                          request.intentFor(resource) == ResourceIntent.MOVEMENT_REQUIRED)) {
                hardRequests.add(request);
              }
              return request.resourceList().contains(beyond)
                  ? new OccupancyDecision(
                      false, request.now(), SignalAspect.STOP, List.of(precedingTail))
                  : new OccupancyDecision(true, request.now(), SignalAspect.PROCEED, List.of());
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
    FakeTrain train = new FakeTrain(worldId, tags.properties(), moving, moving ? 0.5 : 0.0);
    RailState head = mock(RailState.class);
    when(head.positionLocation()).thenReturn(new Location(null, headX, 64.0, 0.0));
    train.railState = Optional.of(head);

    service.handleSignalTick(train, false);

    boolean requestedBeyond =
        hardRequests.stream().anyMatch(request -> request.resourceList().contains(beyond));
    return new TickResult(requestedBeyond, registry.get(TRAIN).orElseThrow().lastSignal());
  }

  /** SPB:JBS:1:001 —100— SPB:1 —51— PTK:SPB:1:002 —100— PTK:1，节点沿 x 轴、坐标与边长一致。 */
  private static SimpleRailGraph spbApproach() {
    Map<NodeId, RailNode> nodes = new LinkedHashMap<>();
    nodes.put(APPROACH, node(APPROACH, NodeType.WAYPOINT, 0.0));
    nodes.put(PLATFORM, node(PLATFORM, NodeType.STATION, 100.0));
    nodes.put(BEYOND, node(BEYOND, NodeType.WAYPOINT, 151.0));
    nodes.put(NEXT_STATION, node(NEXT_STATION, NodeType.STATION, 251.0));
    Map<EdgeId, RailEdge> edges = new LinkedHashMap<>();
    edge(edges, APPROACH, PLATFORM, 100);
    edge(edges, PLATFORM, BEYOND, 51);
    edge(edges, BEYOND, NEXT_STATION, 100);
    return new SimpleRailGraph(nodes, edges, java.util.Set.of());
  }

  private static RailNode node(NodeId id, NodeType type, double x) {
    return new SignRailNode(id, type, new Vector(x, 64.0, 0.0), Optional.empty(), Optional.empty());
  }

  private static void edge(Map<EdgeId, RailEdge> edges, NodeId from, NodeId to, int lengthBlocks) {
    EdgeId id = EdgeId.undirected(from, to);
    edges.put(id, new RailEdge(id, from, to, lengthBlocks, -1.0, true, Optional.empty()));
  }
}
