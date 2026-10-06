package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchTestFixtures.routeStop;
import static org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchTestFixtures.sectionlessGraph;
import static org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchTestFixtures.testConfigView;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bergerkiller.bukkit.tc.controller.components.RailState;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
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
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeRegistry;
import org.junit.jupiter.api.Test;

/**
 * 信号 tick 里“放掉再取回同一份占用”不算账本变化，停着的车不会因此被重新唤醒。
 *
 * <p>每拍开头按“硬窗口 + 当前位置”收缩本车占用，末尾再以列尾防护（停车时以停车保持）取回。这一放一取推进原始占用版本；停着的车若以原始版本为重评估条件，
 * 只要有一辆车在跑——包括它自己的每一拍——就每个周期都要完整重算一次。重评估改看净变化版本：一拍前后账本的语义状态没变就不前进；真的放掉了、换了主人、排队变了，照常前进。
 * 占用本身的取舍与角色一律不变。
 *
 * <p>夹具：车头刚过 {@code 3:007}，车长 34，列尾防护盖住身后的道岔节点、两侧区间与道岔冲突键。现场方块不落在任何区间上，车身释放下限不参与。
 */
class LedgerNetChangeSignalTickTest {

  private static final String TRAIN = "SURC-MT-LH-2689";
  private static final String BLOCKER = "SURC-DS-LH-3597";
  private static final NodeId P4 = NodeId.of("SURC:SPB:WSD:3:004");
  private static final NodeId SWITCH = NodeId.of("SWITCHER:Towny:-400:74:1130");
  private static final NodeId BRANCH = NodeId.of("SURC:SPB:WSD:2:005");
  private static final NodeId P6 = NodeId.of("SURC:SPB:WSD:3:006");
  private static final NodeId P7 = NodeId.of("SURC:SPB:WSD:3:007");
  private static final NodeId WSD = NodeId.of("SURC:S:WSD:3");
  private static final NodeId BEYOND = NodeId.of("SURC:WSD:SCC:3:001");
  private static final NodeId SCC = NodeId.of("SURC:S:SCC:3");
  private static final RouteId ROUTE_ID = RouteId.of("MT-LH");
  private static final OccupancyResource SWITCH_CONFLICT =
      OccupancyResource.forConflict("switcher:" + SWITCH.value());
  private static final OccupancyResource NODE_SWITCH = OccupancyResource.forNode(SWITCH);
  private static final OccupancyResource EDGE_SWITCH_P6 =
      OccupancyResource.forEdge(EdgeId.undirected(SWITCH, P6));
  private static final OccupancyResource NODE_P6 = OccupancyResource.forNode(P6);
  private static final OccupancyResource EDGE_P6_P7 =
      OccupancyResource.forEdge(EdgeId.undirected(P6, P7));

  /** 正常行驶、两拍之间什么都没变：原始版本照常推进（一放一取还在），净变化版本不动，本车占用一项不差。 */
  @Test
  void steadyRunningTickIsNotANetChange() {
    Scenario scenario = new Scenario();
    scenario.tick(103.0, true);
    assertHeldByTrain(scenario, SWITCH_CONFLICT, ClaimRole.PROTECTIVE_RETAIN);
    assertHeldByTrain(scenario, EDGE_SWITCH_P6, ClaimRole.PROTECTIVE_RETAIN);
    List<String> claimsBefore = scenario.claims();
    long rawBefore = scenario.manager.version();
    long netBefore = scenario.manager.netChangeVersion();

    scenario.tick(104.0, true);

    assertEquals(claimsBefore, scenario.claims());
    assertTrue(scenario.manager.version() > rawBefore, "原始版本应照常推进，否则本用例是空的");
    assertEquals(netBefore, scenario.manager.netChangeVersion());
  }

  /** 被挡停着的车再跑一拍、什么都没变：它的重评估版本不变；挡路的车真放掉之后才变。 */
  @Test
  void stoppedTrainIsReawakenedOnlyByARealChange() {
    Scenario scenario = new Scenario();
    scenario.blockStation();
    scenario.tick(103.0, false);
    assertTrue(scenario.service.getActiveStopState(TRAIN).isPresent(), "站台被占，应停车保持");
    long held = scenario.service.heldTrainOccupancyVersion(TRAIN).orElseThrow();
    long rawBefore = scenario.manager.version();

    scenario.tick(103.0, false);

    assertTrue(scenario.manager.version() > rawBefore, "原始版本应照常推进，否则本用例是空的");
    assertEquals(held, scenario.service.heldTrainOccupancyVersion(TRAIN).orElseThrow());

    scenario.manager.releaseResource(OccupancyResource.forNode(WSD), Optional.of(BLOCKER));

    assertNotEquals(held, scenario.service.heldTrainOccupancyVersion(TRAIN).orElseThrow());
  }

  /** 从硬窗口退到身后的区段（含道岔冲突键）仍是 MOVEMENT_REQUIRED：本拍照旧降为保护性占用。 */
  @Test
  void resourcesLeavingTheWindowAreStillDowngradedToProtective() {
    Scenario scenario = new Scenario();
    scenario.seedPreviousWindow();

    scenario.tick(103.0, true);

    for (OccupancyResource resource :
        List.of(SWITCH_CONFLICT, NODE_SWITCH, EDGE_SWITCH_P6, NODE_P6, EDGE_P6_P7)) {
      assertHeldByTrain(scenario, resource, ClaimRole.PROTECTIVE_RETAIN);
    }
  }

  private static void assertHeldByTrain(
      Scenario scenario, OccupancyResource resource, ClaimRole role) {
    OccupancyClaim claim =
        scenario
            .manager
            .getClaim(resource)
            .orElseThrow(() -> new AssertionError(resource + " 未被持有; 本车现存: " + scenario.claims()));
    assertEquals(TRAIN, claim.trainName(), resource.toString());
    assertEquals(role, claim.role(), resource + "; 本车现存: " + scenario.claims());
  }

  private static final class Scenario {
    private final UUID worldId = UUID.randomUUID();
    private final SimpleOccupancyManager manager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    private final TagStore tags =
        new TagStore(
            TRAIN,
            "FTA_OPERATOR_CODE=op",
            "FTA_LINE_CODE=l1",
            "FTA_ROUTE_CODE=r1",
            "FTA_ROUTE_INDEX=3");
    private final RouteProgressRegistry registry = new RouteProgressRegistry();
    private final RuntimeDispatchService service;

    private Scenario() {
      RouteDefinition route =
          new RouteDefinition(
              ROUTE_ID, List.of(P4, SWITCH, P6, P7, WSD, BEYOND, SCC), Optional.empty());
      registry.initFromTags(TRAIN, tags.properties(), route);
      service = service(manager, worldId, route, registry);
    }

    /** 另一列车以硬授权占着站台节点：本车的硬窗口到不了站台，停车保持。 */
    private void blockStation() {
      OccupancyResource station = OccupancyResource.forNode(WSD);
      assertTrue(
          manager
              .acquire(
                  new OccupancyRequest(
                      BLOCKER,
                      Optional.empty(),
                      Instant.now(),
                      List.of(station),
                      Map.of(),
                      Map.of(),
                      0,
                      AuthorizationPurpose.RUNTIME_MOVE,
                      Map.of(),
                      Map.of(station, ResourceIntent.MOVEMENT_REQUIRED)))
              .allowed());
    }

    /** 车头还在后方时的硬窗口：刚驶过的区段、道岔与它的冲突键都是 MOVEMENT_REQUIRED。 */
    private void seedPreviousWindow() {
      List<OccupancyResource> resources =
          List.of(SWITCH_CONFLICT, NODE_SWITCH, EDGE_SWITCH_P6, NODE_P6, EDGE_P6_P7);
      Map<OccupancyResource, ResourceIntent> intents = new LinkedHashMap<>();
      resources.forEach(resource -> intents.put(resource, ResourceIntent.MOVEMENT_REQUIRED));
      assertTrue(
          manager
              .acquire(
                  new OccupancyRequest(
                      TRAIN,
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

    private void tick(double headX, boolean moving) {
      FakeTrain train = new FakeTrain(worldId, tags.properties(), moving, moving ? 0.5 : 0.0);
      train.estimatedTrainLengthBlocks = OptionalDouble.of(34.0);
      RailState railState = mock(RailState.class);
      when(railState.positionLocation()).thenReturn(new Location(null, headX, 64.0, 0.0));
      train.railState = Optional.of(railState);
      service.handleSignalTick(train, false);
    }

    private List<String> claims() {
      return manager.snapshotClaims().stream()
          .filter(claim -> TRAIN.equals(claim.trainName()))
          .map(claim -> claim.resource() + "@" + claim.role())
          .sorted()
          .toList();
    }
  }

  private static RuntimeDispatchService service(
      SimpleOccupancyManager manager,
      UUID worldId,
      RouteDefinition route,
      RouteProgressRegistry registry) {
    ConfigManager configManager = mock(ConfigManager.class);
    when(configManager.current()).thenReturn(testConfigView(20, 20.0, 3, 2));
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
   * 3:004 —40— 道岔 —40— 3:006 —20— 3:007 —30— S:WSD:3 —30— 站后节点 —100— S:SCC:3，沿 x 轴；侧线节点在道岔正侧方 30 格。
   * 联锁足迹目录完整（每条区间一个互不相交的方块）。
   */
  private static SimpleRailGraph chain(UUID worldId) {
    Map<NodeId, RailNode> nodes = new LinkedHashMap<>();
    nodes.put(P4, railNode(P4, NodeType.WAYPOINT, 0.0, 0.0));
    nodes.put(SWITCH, railNode(SWITCH, NodeType.SWITCHER, 40.0, 0.0));
    nodes.put(BRANCH, railNode(BRANCH, NodeType.WAYPOINT, 40.0, 30.0));
    nodes.put(P6, railNode(P6, NodeType.WAYPOINT, 80.0, 0.0));
    nodes.put(P7, railNode(P7, NodeType.WAYPOINT, 100.0, 0.0));
    nodes.put(WSD, railNode(WSD, NodeType.STATION, 130.0, 0.0));
    nodes.put(BEYOND, railNode(BEYOND, NodeType.WAYPOINT, 160.0, 0.0));
    nodes.put(SCC, railNode(SCC, NodeType.STATION, 260.0, 0.0));
    Map<EdgeId, RailEdge> edges = new LinkedHashMap<>();
    putEdge(edges, P4, SWITCH, 40);
    putEdge(edges, SWITCH, BRANCH, 30);
    putEdge(edges, SWITCH, P6, 40);
    putEdge(edges, P6, P7, 20);
    putEdge(edges, P7, WSD, 30);
    putEdge(edges, WSD, BEYOND, 30);
    putEdge(edges, BEYOND, SCC, 100);
    Map<EdgeId, RailEdgeFootprint> footprints = new LinkedHashMap<>();
    int z = 0;
    for (EdgeId id : edges.keySet()) {
      footprints.put(
          id,
          new RailEdgeFootprint(
              RailEdgeFootprint.CURRENT_FORMAT_VERSION,
              true,
              Set.of(new RailFootprintCell(0, 64, 100 + z++))));
    }
    return new SimpleRailGraph(
        nodes, edges, Set.of(), RailInterlockingState.from(worldId, edges.keySet(), footprints));
  }

  private static RailNode railNode(NodeId id, NodeType type, double x, double z) {
    return new SignRailNode(id, type, new Vector(x, 64.0, z), Optional.empty(), Optional.empty());
  }

  private static void putEdge(Map<EdgeId, RailEdge> edges, NodeId from, NodeId to, int length) {
    EdgeId id = EdgeId.undirected(from, to);
    edges.put(id, new RailEdge(id, from, to, length, -1.0, true, Optional.empty()));
  }
}
