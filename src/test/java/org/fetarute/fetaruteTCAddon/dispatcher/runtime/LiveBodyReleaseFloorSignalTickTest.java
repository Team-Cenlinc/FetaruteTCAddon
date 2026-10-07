package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchTestFixtures.routeStop;
import static org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchTestFixtures.sectionlessGraph;
import static org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchTestFixtures.testConfigView;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bergerkiller.bukkit.tc.controller.components.RailState;
import com.bergerkiller.bukkit.tc.events.SignActionEvent;
import com.bergerkiller.bukkit.tc.signactions.SignActionType;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.util.Vector;
import org.fetarute.fetaruteTCAddon.company.model.RouteStopPassType;
import org.fetarute.fetaruteTCAddon.config.ConfigManager;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailEdge;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphService;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.SignRailNode;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.SimpleRailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.interlocking.RailEdgeFootprint;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.interlocking.RailFootprintCell;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.interlocking.RailInterlockingState;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinitionCache;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteId;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchTestFixtures.FakeTrain;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchTestFixtures.TagStore;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.config.TrainConfigResolver;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.AuthorizationPurpose;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.ClaimRole;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyClaim;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyRequest;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyResource;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.ResourceIntent;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspectPolicy;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SimpleOccupancyManager;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeRegistry;
import org.junit.jupiter.api.Test;

/**
 * 车体现场仍压着的区段，本拍收缩时不会被放空。
 *
 * <p>列尾防护按估算车长计算；估算偏短时，逻辑窗口覆盖不到车体后半段。夹具：车长估算 10，现场方块从 x=30 铺到车头 x=103， 车体实际压着 {@code
 * 3:004~3:005}，而按估算车长算出的列尾防护只到 {@code 3:005~3:006}。实测车身作为释放下限，放掉后随即以保护性占用取回。
 */
class LiveBodyReleaseFloorSignalTickTest {

  private static final String TRAIN = "SURC-MT-LH-2689";
  private static final String BLOCKER = "SURC-DS-LH-3597";
  private static final NodeId P4 = NodeId.of("SURC:SPB:WSD:3:004");
  private static final NodeId P5 = NodeId.of("SURC:SPB:WSD:3:005");
  private static final NodeId P6 = NodeId.of("SURC:SPB:WSD:3:006");
  private static final NodeId P7 = NodeId.of("SURC:SPB:WSD:3:007");
  private static final NodeId WSD = NodeId.of("SURC:S:WSD:3");
  private static final NodeId BEYOND = NodeId.of("SURC:WSD:SCC:3:001");
  private static final NodeId SCC = NodeId.of("SURC:S:SCC:3");
  private static final RouteId ROUTE_ID = RouteId.of("MT-LH");
  private static final OccupancyResource EDGE_P4_P5 =
      OccupancyResource.forEdge(EdgeId.undirected(P4, P5));
  private static final OccupancyResource NODE_P5 = OccupancyResource.forNode(P5);
  private static final OccupancyResource EDGE_P5_P6 =
      OccupancyResource.forEdge(EdgeId.undirected(P5, P6));
  private static final OccupancyResource NODE_P6 = OccupancyResource.forNode(P6);
  private static final OccupancyResource EDGE_P6_P7 =
      OccupancyResource.forEdge(EdgeId.undirected(P6, P7));

  /** 正常行驶的一拍：车体压着的后半段留在本车名下；车身区段照旧降为保护性占用，不因下限停在 MOVEMENT_REQUIRED。 */
  @Test
  void runningTickKeepsTheLiveBodyBeyondTheEstimatedRearGuard() {
    Scenario scenario = new Scenario();
    scenario.seedHeldBody();

    scenario.tick(103.0, true, 30);

    assertEquals(
        Optional.empty(),
        scenario.service.getActiveStopState(TRAIN).map(RuntimeStopState::reasonCode));
    assertHeldByTrain(scenario, EDGE_P4_P5, ClaimRole.PROTECTIVE_RETAIN);
    assertHeldByTrain(scenario, NODE_P5, ClaimRole.PROTECTIVE_RETAIN);
    assertHeldByTrain(scenario, EDGE_P6_P7, ClaimRole.PROTECTIVE_RETAIN);
    assertHeldByTrain(scenario, NODE_P6, ClaimRole.PROTECTIVE_RETAIN);
  }

  /** 车尾已离开的区段照常放掉：下限只跟随现场，不把车身之外的旧占用攥在手里。 */
  @Test
  void edgesTheTailHasLeftAreStillReleased() {
    Scenario scenario = new Scenario();
    scenario.seedHeldBody();

    scenario.tick(103.0, true, 75);

    assertTrue(scenario.manager.getClaim(EDGE_P4_P5).isEmpty(), () -> "本车现存: " + scenario.claims());
  }

  /** 被挡停车：停车保持按估算车长收缩时，车体压着的后半段同样留在本车名下。 */
  @Test
  void blockedStopKeepsTheLiveBodyBeyondTheEstimatedRearGuard() {
    Scenario scenario = new Scenario();
    scenario.seedHeldBody();
    scenario.blockStation();

    scenario.tick(103.0, false, 30);

    assertTrue(
        scenario.service.getActiveStopState(TRAIN).isPresent(),
        "站台被占，本拍应停车保持: " + scenario.claims());
    assertHeldByTrain(scenario, EDGE_P4_P5, ClaimRole.PROTECTIVE_RETAIN);
    assertHeldByTrain(scenario, NODE_P5, ClaimRole.PROTECTIVE_RETAIN);
  }

  /** 停在站台的发车门控轮询：按发车请求收缩时，车体压着的后半段同样留在本车名下。 */
  @Test
  void departurePollKeepsTheLiveBodyBeyondTheEstimatedRearGuard() {
    Scenario scenario = new Scenario(4);
    scenario.acquire(TRAIN, List.of(EDGE_P5_P6), ResourceIntent.PROTECTIVE_RETAIN);

    scenario.checkDeparture(135.0, 60);

    assertHeldByTrain(scenario, EDGE_P5_P6, ClaimRole.PROTECTIVE_RETAIN);
  }

  /** 推进点（车头过节点）按新窗口收缩时，车体压着的后半段同样留在本车名下。 */
  @Test
  void progressTriggerKeepsTheLiveBodyBeyondTheEstimatedRearGuard() {
    Scenario scenario = new Scenario(2);
    scenario.acquire(TRAIN, List.of(EDGE_P4_P5, NODE_P5), ResourceIntent.PROTECTIVE_RETAIN);

    scenario.memberEnter(P7, 101.0, 30);

    assertEquals(3, scenario.registry.get(TRAIN).orElseThrow().currentIndex(), "应已推进到 3:007");
    assertHeldByTrain(scenario, EDGE_P4_P5, ClaimRole.PROTECTIVE_RETAIN);
  }

  private static void assertHeldByTrain(
      Scenario scenario, OccupancyResource resource, ClaimRole role) {
    OccupancyClaim claim =
        scenario
            .manager
            .getClaim(resource)
            .orElseThrow(() -> new AssertionError(resource + " 已被放掉; 本车现存: " + scenario.claims()));
    assertEquals(TRAIN, claim.trainName(), resource.toString());
    assertEquals(role, claim.role(), resource + "; 本车现存: " + scenario.claims());
  }

  private static final class Scenario {
    private final UUID worldId = UUID.randomUUID();
    private final SimpleOccupancyManager manager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    private final TagStore tags;
    private final RouteProgressRegistry registry = new RouteProgressRegistry();
    private final RuntimeDispatchService service;

    private Scenario() {
      this(3);
    }

    private Scenario(int routeIndex) {
      tags =
          new TagStore(
              TRAIN,
              "FTA_OPERATOR_CODE=op",
              "FTA_LINE_CODE=l1",
              "FTA_ROUTE_CODE=r1",
              "FTA_ROUTE_INDEX=" + routeIndex);
      RouteDefinition route =
          new RouteDefinition(
              ROUTE_ID, List.of(P4, P5, P6, P7, WSD, BEYOND, SCC), Optional.empty());
      registry.initFromTags(TRAIN, tags.properties(), route);
      service = service(manager, worldId, route, registry);
    }

    /** 车头还在后方几个节点时取得的占用：车体后半段为保护性占用，刚驶过的区段仍是上一段硬授权。 */
    private void seedHeldBody() {
      acquire(TRAIN, List.of(EDGE_P4_P5, NODE_P5), ResourceIntent.PROTECTIVE_RETAIN);
      acquire(TRAIN, List.of(NODE_P6, EDGE_P6_P7), ResourceIntent.MOVEMENT_REQUIRED);
    }

    private void blockStation() {
      acquire(BLOCKER, List.of(OccupancyResource.forNode(WSD)), ResourceIntent.MOVEMENT_REQUIRED);
    }

    private void acquire(String owner, List<OccupancyResource> resources, ResourceIntent intent) {
      Map<OccupancyResource, ResourceIntent> intents = new LinkedHashMap<>();
      resources.forEach(resource -> intents.put(resource, intent));
      assertTrue(
          manager
              .acquire(
                  new OccupancyRequest(
                      owner,
                      Optional.of(ROUTE_ID),
                      Instant.now(),
                      resources,
                      Map.of(),
                      Map.of(),
                      0,
                      AuthorizationPurpose.RUNTIME_MOVE,
                      Map.of(),
                      intents))
              .allowed());
    }

    /** 车长估算 10；现场方块从 {@code tailX} 铺到车头。 */
    private void tick(double headX, boolean moving, int tailX) {
      service.handleSignalTick(train(headX, moving, tailX), false);
    }

    /** 停在站台 {@code S:WSD:3} 上的一次发车门控轮询。 */
    private void checkDeparture(double headX, int tailX) {
      service.checkDeparture(
          train(headX, false, tailX),
          new SignNodeDefinition(WSD, NodeType.STATION, Optional.empty(), Optional.empty()));
    }

    /** 车头驶过区间节点 {@code node} 的推进事件。 */
    private void memberEnter(NodeId node, double headX, int tailX) {
      SignActionEvent event = mock(SignActionEvent.class);
      World world = mock(World.class);
      when(world.getUID()).thenReturn(worldId);
      when(event.getWorld()).thenReturn(world);
      when(event.getAction()).thenReturn(SignActionType.MEMBER_ENTER);
      service.handleWaypointMemberEnter(
          train(headX, true, tailX),
          event,
          new SignNodeDefinition(node, NodeType.WAYPOINT, Optional.empty(), Optional.empty()));
    }

    private FakeTrain train(double headX, boolean moving, int tailX) {
      FakeTrain train = new FakeTrain(worldId, tags.properties(), moving, moving ? 0.5 : 0.0);
      train.estimatedTrainLengthBlocks = OptionalDouble.of(10.0);
      Set<RailFootprintCell> cells = new LinkedHashSet<>();
      for (int x = tailX; x <= (int) headX; x++) {
        cells.add(new RailFootprintCell(x, 64, 0));
      }
      train.liveRailFootprintCells = Optional.of(cells);
      RailState railState = mock(RailState.class);
      when(railState.positionLocation()).thenReturn(new Location(null, headX, 64.0, 0.0));
      train.railState = Optional.of(railState);
      return train;
    }

    private List<String> claims() {
      return manager.snapshotClaims().stream()
          .filter(claim -> TRAIN.equals(claim.trainName()))
          .map(claim -> claim.resource() + "@" + claim.role())
          .toList();
    }
  }

  private static RuntimeDispatchService service(
      SimpleOccupancyManager manager,
      UUID worldId,
      RouteDefinition route,
      RouteProgressRegistry registry) {
    ConfigManager configManager = mock(ConfigManager.class);
    when(configManager.current()).thenReturn(testConfigView(20, 20.0));
    RailGraphService railGraphService = mock(RailGraphService.class);
    when(railGraphService.getSnapshot(worldId))
        .thenReturn(
            Optional.of(
                new RailGraphService.RailGraphSnapshot(
                    sectionlessGraph(chain(worldId)), Instant.now())));
    when(railGraphService.effectiveSpeedLimitBlocksPerSecond(
            any(), any(), any(), anyDouble(), anyDouble()))
        .thenReturn(1000.0);
    RouteDefinitionCache routeDefinitions = mock(RouteDefinitionCache.class);
    when(routeDefinitions.findByCodes("op", "l1", "r1")).thenReturn(Optional.of(route));
    List<NodeId> waypoints = route.waypoints();
    for (int index = 0; index < waypoints.size(); index++) {
      NodeId node = waypoints.get(index);
      RouteStopPassType passType =
          node.equals(WSD) || node.equals(SCC) ? RouteStopPassType.STOP : RouteStopPassType.PASS;
      when(routeDefinitions.findStop(eq(ROUTE_ID), eq(index)))
          .thenReturn(Optional.of(routeStop(index, node, passType)));
    }
    return new RuntimeDispatchService(
        manager,
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

  /**
   * 3:004 —40— 3:005 —40— 3:006 —20— 3:007 —30— S:WSD:3 —30— 站后节点 —100— S:SCC:3，沿 x 轴、坐标与边长一致；
   * 逐边足迹是该区间沿 x 的轨道方块，互不相交。
   */
  private static SimpleRailGraph chain(UUID worldId) {
    Map<NodeId, RailNode> nodes = new LinkedHashMap<>();
    nodes.put(P4, railNode(P4, NodeType.WAYPOINT, 0.0));
    nodes.put(P5, railNode(P5, NodeType.WAYPOINT, 40.0));
    nodes.put(P6, railNode(P6, NodeType.WAYPOINT, 80.0));
    nodes.put(P7, railNode(P7, NodeType.WAYPOINT, 100.0));
    nodes.put(WSD, railNode(WSD, NodeType.STATION, 130.0));
    nodes.put(BEYOND, railNode(BEYOND, NodeType.WAYPOINT, 160.0));
    nodes.put(SCC, railNode(SCC, NodeType.STATION, 260.0));
    Map<EdgeId, RailEdge> edges = new LinkedHashMap<>();
    Map<EdgeId, RailEdgeFootprint> footprints = new LinkedHashMap<>();
    putEdge(edges, footprints, P4, P5, 0, 40);
    putEdge(edges, footprints, P5, P6, 40, 80);
    putEdge(edges, footprints, P6, P7, 80, 100);
    putEdge(edges, footprints, P7, WSD, 100, 130);
    putEdge(edges, footprints, WSD, BEYOND, 130, 160);
    putEdge(edges, footprints, BEYOND, SCC, 160, 260);
    return new SimpleRailGraph(
        nodes, edges, Set.of(), RailInterlockingState.from(worldId, edges.keySet(), footprints));
  }

  private static RailNode railNode(NodeId id, NodeType type, double x) {
    return new SignRailNode(id, type, new Vector(x, 64.0, 0.0), Optional.empty(), Optional.empty());
  }

  /** 区间方块取 {@code [fromX, toX)}，最后一条区间连同终点方块。 */
  private static void putEdge(
      Map<EdgeId, RailEdge> edges,
      Map<EdgeId, RailEdgeFootprint> footprints,
      NodeId from,
      NodeId to,
      int fromX,
      int toX) {
    EdgeId id = EdgeId.undirected(from, to);
    edges.put(id, new RailEdge(id, from, to, toX - fromX, -1.0, true, Optional.empty()));
    Set<RailFootprintCell> cells = new LinkedHashSet<>();
    int lastX = to.equals(SCC) ? toX : toX - 1;
    for (int x = fromX; x <= lastX; x++) {
      cells.add(new RailFootprintCell(x, 64, 0));
    }
    footprints.put(
        id, new RailEdgeFootprint(RailEdgeFootprint.CURRENT_FORMAT_VERSION, true, cells));
  }
}
