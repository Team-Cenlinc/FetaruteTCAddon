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
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
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
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyRequest;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyResource;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.ResourceIntent;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspect;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspectPolicy;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SimpleOccupancyManager;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeRegistry;
import org.junit.jupiter.api.Test;

/**
 * 站台之后的道岔被别的车占着时，驶向站台的车照常进站停车，不在站外被扣。
 *
 * <p>夹具：车头刚过区间节点 {@code 3:007}，下一路径点是车站 {@code S:WSD:3}（计划停车），站台节点之后 64 格是道岔，另一列车正从侧线经过它
 * （占着道岔冲突键、道岔节点与侧线区间）。凡一端连着道岔的区间都带这个道岔的冲突键，前瞻因此把冲突的起点量在站台节点上。
 */
class PlannedStopSwitcherConflictTest {

  private static final String TRAIN = "SURC-MT-LH-2689";
  private static final String BLOCKER = "SURC-DS-LH-3597";
  private static final NodeId P4 = NodeId.of("SURC:SPB:WSD:3:004");
  private static final NodeId P5 = NodeId.of("SURC:SPB:WSD:3:005");
  private static final NodeId P6 = NodeId.of("SURC:SPB:WSD:3:006");
  private static final NodeId P7 = NodeId.of("SURC:SPB:WSD:3:007");
  private static final NodeId WSD = NodeId.of("SURC:S:WSD:3");
  private static final NodeId SWITCH = NodeId.of("SWITCHER:Towny:-271:74:1124");
  private static final NodeId BRANCH = NodeId.of("SURC:WSD:SCC:2:001");
  private static final NodeId BEYOND = NodeId.of("SURC:WSD:SCC:3:001");
  private static final NodeId SCC = NodeId.of("SURC:S:SCC:3");
  private static final RouteId ROUTE_ID = RouteId.of("MT-LH");

  /** 站台之后 64 格的道岔被占：本拍不停车，照常驶向站台。 */
  @Test
  void distantSwitchBeyondThePlannedStopDoesNotStopTheTrainShortOfThePlatform() {
    Scenario scenario = new Scenario(64, RouteStopPassType.STOP);
    scenario.tick(103.0);
    scenario.occupySwitchFromBranch();

    FakeTrain train = scenario.tick(105.0);

    assertEquals(
        Optional.empty(),
        scenario.service.getActiveStopState(TRAIN).map(RuntimeStopState::reasonCode),
        scenario::debugTail);
    assertTrue(scenario.signal() != SignalAspect.STOP, scenario::debugTail);
    assertEquals(0, train.stopCalls, scenario::debugTail);
    assertEquals(0, train.hardStopCalls, scenario::debugTail);
  }

  /** 道岔离站台只有 28 格（不足车长 34 + 停车余量）：停站时车头可能压到道岔附近，照旧在站外停车。道岔节点本身在制动距离之外，挡车的只能是冲突键。 */
  @Test
  void switchTooCloseToThePlatformStillStopsTheTrainShortOfIt() {
    Scenario scenario = new Scenario(28, RouteStopPassType.STOP);
    scenario.tick(103.0);
    scenario.occupySwitchFromBranch();

    scenario.tick(105.0);

    assertHeldShortOfThePlatform(scenario);
  }

  /** 车长未知：说不清停站时车头伸到哪里，不放宽。 */
  @Test
  void unknownTrainLengthDoesNotRelaxTheStop() {
    Scenario scenario = new Scenario(64, RouteStopPassType.STOP);
    scenario.trainLength = OptionalDouble.empty();
    scenario.tick(103.0);
    scenario.occupySwitchFromBranch();

    scenario.tick(105.0);

    assertHeldShortOfThePlatform(scenario);
  }

  /** 停在站台、道岔仍被占：出站时硬授权窗口要取得道岔冲突键，照旧不放行。 */
  @Test
  void departureFromThePlatformIsStillBlockedByTheOccupiedSwitch() {
    Scenario scenario = new Scenario(64, RouteStopPassType.STOP, 4);
    scenario.occupySwitchFromBranch();

    scenario.tickAt(130.0, false);

    assertEquals(SignalAspect.STOP, scenario.signal(), scenario::debugTail);
    RuntimeStopState stop =
        scenario.service.getActiveStopState(TRAIN).orElseThrow(() -> new AssertionError("未停车"));
    assertTrue(
        stop.blockers().stream()
            .anyMatch(
                blocker ->
                    blocker.owner().equals(BLOCKER) && blocker.resource().contains(SWITCH.value())),
        () -> "挡车的应是占着道岔的车: " + stop);
  }

  private static void assertHeldShortOfThePlatform(Scenario scenario) {
    assertEquals(SignalAspect.STOP, scenario.signal(), scenario::debugTail);
    assertTrue(scenario.service.getActiveStopState(TRAIN).isPresent(), scenario::debugTail);
  }

  private static final class Scenario {
    private final UUID worldId = UUID.randomUUID();
    private final SimpleOccupancyManager manager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    private final TagStore tags;
    private final RouteProgressRegistry registry = new RouteProgressRegistry();
    private final List<String> debug = new ArrayList<>();
    private final RuntimeDispatchService service;
    private OptionalDouble trainLength = OptionalDouble.of(34.0);

    /**
     * @param switchBeyondPlatform 站台节点到道岔的距离
     * @param stationPassType 车站对本交路是停车还是通过
     */
    private Scenario(int switchBeyondPlatform, RouteStopPassType stationPassType) {
      this(switchBeyondPlatform, stationPassType, 3);
    }

    private Scenario(int switchBeyondPlatform, RouteStopPassType stationPassType, int routeIndex) {
      tags =
          new TagStore(
              TRAIN,
              "FTA_OPERATOR_CODE=op",
              "FTA_LINE_CODE=l1",
              "FTA_ROUTE_CODE=r1",
              "FTA_ROUTE_INDEX=" + routeIndex);
      RouteDefinition route =
          new RouteDefinition(ROUTE_ID, List.of(P4, P5, P6, P7, WSD, SCC), Optional.empty());
      registry.initFromTags(TRAIN, tags.properties(), route);
      service =
          service(manager, worldId, route, registry, debug, switchBeyondPlatform, stationPassType);
    }

    /** 另一列车从侧线经过道岔：持有道岔冲突键、道岔节点与侧线区间。 */
    private void occupySwitchFromBranch() {
      OccupancyResource switcher = OccupancyResource.forConflict("switcher:" + SWITCH.value());
      OccupancyResource switchNode = OccupancyResource.forNode(SWITCH);
      OccupancyResource branchEdge = OccupancyResource.forEdge(EdgeId.undirected(BRANCH, SWITCH));
      List<OccupancyResource> resources = List.of(branchEdge, switchNode, switcher);
      Map<OccupancyResource, ResourceIntent> intents = new LinkedHashMap<>();
      resources.forEach(resource -> intents.put(resource, ResourceIntent.MOVEMENT_REQUIRED));
      assertTrue(
          manager
              .acquire(
                  new OccupancyRequest(
                      BLOCKER,
                      Optional.empty(),
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

    private FakeTrain tick(double headX) {
      return tickAt(headX, true);
    }

    private FakeTrain tickAt(double headX, boolean moving) {
      FakeTrain train = new FakeTrain(worldId, tags.properties(), moving, moving ? 0.5 : 0.0);
      train.estimatedTrainLengthBlocks = trainLength;
      RailState railState = mock(RailState.class);
      when(railState.positionLocation()).thenReturn(new Location(null, headX, 64.0, 0.0));
      train.railState = Optional.of(railState);
      service.handleSignalTick(train, false);
      return train;
    }

    private SignalAspect signal() {
      return registry.get(TRAIN).orElseThrow().lastSignal();
    }

    private String debugTail() {
      return String.join(
          "\n",
          debug.stream()
              .filter(
                  line ->
                      line.contains("SMART_DISPATCH")
                          || line.contains("STOP_LIFECYCLE")
                          || line.contains("移动授权降级")
                          || line.contains("信号Tick")
                          || line.contains("RECOVERABLE"))
              .toList());
    }
  }

  private static RuntimeDispatchService service(
      SimpleOccupancyManager manager,
      UUID worldId,
      RouteDefinition route,
      RouteProgressRegistry registry,
      List<String> debug,
      int switchBeyondPlatform,
      RouteStopPassType stationPassType) {
    ConfigManager configManager = mock(ConfigManager.class);
    when(configManager.current()).thenReturn(testConfigView(20, 20.0, 3, 2));
    RailGraphService railGraphService = mock(RailGraphService.class);
    when(railGraphService.getSnapshot(worldId))
        .thenReturn(
            Optional.of(
                new RailGraphService.RailGraphSnapshot(
                    sectionlessGraph(chain(worldId, switchBeyondPlatform)), Instant.now())));
    when(railGraphService.effectiveSpeedLimitBlocksPerSecond(
            any(), any(), any(), anyDouble(), anyDouble()))
        .thenReturn(1000.0);
    RouteDefinitionCache routeDefinitions = mock(RouteDefinitionCache.class);
    when(routeDefinitions.findByCodes("op", "l1", "r1")).thenReturn(Optional.of(route));
    List<NodeId> waypoints = route.waypoints();
    for (int index = 0; index < waypoints.size(); index++) {
      NodeId node = waypoints.get(index);
      RouteStopPassType passType =
          node.equals(WSD)
              ? stationPassType
              : node.equals(SCC) ? RouteStopPassType.STOP : RouteStopPassType.PASS;
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
        debug::add);
  }

  /**
   * 3:004 —40— 3:005 —40— 3:006 —20— 3:007 —30— S:WSD:3 —{@code switchBeyondPlatform}— 道岔 —30— 站后节点
   * —100— S:SCC:3，沿 x 轴；侧线节点在道岔正侧方 30 格。联锁足迹目录完整。
   */
  private static SimpleRailGraph chain(UUID worldId, int switchBeyondPlatform) {
    double switchX = 130.0 + switchBeyondPlatform;
    Map<NodeId, RailNode> nodes = new LinkedHashMap<>();
    nodes.put(P4, railNode(P4, NodeType.WAYPOINT, 0.0, 0.0));
    nodes.put(P5, railNode(P5, NodeType.WAYPOINT, 40.0, 0.0));
    nodes.put(P6, railNode(P6, NodeType.WAYPOINT, 80.0, 0.0));
    nodes.put(P7, railNode(P7, NodeType.WAYPOINT, 100.0, 0.0));
    nodes.put(WSD, railNode(WSD, NodeType.STATION, 130.0, 0.0));
    nodes.put(SWITCH, railNode(SWITCH, NodeType.SWITCHER, switchX, 0.0));
    nodes.put(BRANCH, railNode(BRANCH, NodeType.WAYPOINT, switchX, 30.0));
    nodes.put(BEYOND, railNode(BEYOND, NodeType.WAYPOINT, switchX + 30.0, 0.0));
    nodes.put(SCC, railNode(SCC, NodeType.STATION, switchX + 130.0, 0.0));
    Map<EdgeId, RailEdge> edges = new LinkedHashMap<>();
    putEdge(edges, P4, P5, 40);
    putEdge(edges, P5, P6, 40);
    putEdge(edges, P6, P7, 20);
    putEdge(edges, P7, WSD, 30);
    putEdge(edges, WSD, SWITCH, switchBeyondPlatform);
    putEdge(edges, SWITCH, BRANCH, 30);
    putEdge(edges, SWITCH, BEYOND, 30);
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
