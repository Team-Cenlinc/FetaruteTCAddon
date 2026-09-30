package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchTestFixtures.sectionlessGraph;
import static org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchTestFixtures.testConfigView;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
import org.bukkit.util.Vector;
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
import org.fetarute.fetaruteTCAddon.dispatcher.node.WaypointMetadata;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinitionCache;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteId;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchTestFixtures.FakeTrain;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchTestFixtures.TagStore;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.config.TrainConfigResolver;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.AuthorizationPurpose;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.ClaimRole;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyRequest;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyResource;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyResourceResolver;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.ResourceIntent;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspectPolicy;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SimpleOccupancyManager;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeRegistry;
import org.junit.jupiter.api.Test;

/**
 * 回库车已经拿下的原子进路不许被下一拍拆成两半。
 *
 * <p>2026-09-27 18:43 实服 OFL 车库口：回库 MT 在 MLU:2:001 原子拿下"剪刀渡线 → MLU:1:003 → 车库岔口 → 库线 → 车库"整条进路； 过了
 * MLU:2:002，车库岔口的联锁区进入硬授权窗口，清出规则要 74 格泊位、库线只有 45 格，硬授权构建失败，信号周期退回前瞻请求（只到 MLU:1:003）， 岔口之后全部释放。对向 DS
 * 随即排上岔口队首，MT 占着渡线、DS 要过渡线，互等 18 分钟。
 *
 * <p>夹具取实服边长；库线咽喉—岔口这条边与 1 道 MLU:1:004—岔口共用一个足迹方块（联锁区 961c）。列车在第一拍停在 MLU:2:001、第二拍到 MLU:2:002。
 */
class OflDepotRunInAtomicRouteTest {

  private static final String TRAIN = "SURC-MT-LO-9304";
  private static final String OPPOSING = "SURC-DS-LH-5416";
  private static final NodeId OFL_2 = NodeId.of("SURC:S:OFL:2");
  private static final NodeId MLU_2_001 = NodeId.of("SURC:OFL:MLU:2:001");
  private static final NodeId MLU_2_002 = NodeId.of("SURC:OFL:MLU:2:002");
  private static final NodeId CROSSOVER_2 = NodeId.of("SWITCHER:Towny:-520:77:2212");
  private static final NodeId DIAMOND_A = NodeId.of("SWITCHER:Towny:-518:77:2220");
  private static final NodeId DIAMOND_B = NodeId.of("SWITCHER:Towny:-518:77:2227");
  private static final NodeId CROSSOVER_1 = NodeId.of("SWITCHER:Towny:-516:77:2236");
  private static final NodeId MLU_1_003 = NodeId.of("SURC:OFL:MLU:1:003");
  private static final NodeId JUNCTION = NodeId.of("SWITCHER:Towny:-516:77:2268");
  private static final NodeId DEPOT_SWITCH = NodeId.of("SWITCHER:Towny:-515:77:2272");
  private static final NodeId DEPOT_THROAT = NodeId.of("SURC:D:OFL:1:001");
  private static final NodeId DEPOT = NodeId.of("SURC:D:OFL:1");
  private static final NodeId MLU_1_004 = NodeId.of("SURC:OFL:MLU:1:004");
  private static final RailFootprintCell SHARED_CELL = new RailFootprintCell(-516, 77, 2272);

  /** 列车 60 格、库线 45 格：终点车库的泊位也不够，第二拍硬授权照样构建失败、退回前瞻请求—— 已授予的岔口、联锁区与库线仍须留在本车手里，对向车进不来。 */
  @Test
  void aFailedHardAuthorityDoesNotSplitTheGrantedDepotRoute() {
    Scenario scenario = new Scenario(60.0);

    scenario.tickAt(MLU_2_001);
    assertTrue(scenario.heldByTrain(OccupancyResource.forNode(JUNCTION)), "第一拍原子拿下整条进路");

    scenario.tickAt(MLU_2_002);

    assertTrue(scenario.heldByTrain(OccupancyResource.forNode(JUNCTION)));
    assertTrue(scenario.heldByTrain(scenario.interlocking()));
    assertTrue(scenario.heldByTrain(OccupancyResource.forNode(DEPOT)));
    assertTrue(scenario.retainedTrace("hardAuthority=fallback"), "保下来的那一拍必须留下诊断");
    assertFalse(scenario.opposingTrainCanReachTheJunction());
  }

  /** 列车 34 格容得下库线：第二拍硬授权直接构建成功，不退回，也用不着保护。 */
  @Test
  void aTrainThatFitsTheStorageTrackKeepsBuildingTheWholeRoute() {
    Scenario scenario = new Scenario(34.0);

    scenario.tickAt(MLU_2_001);
    scenario.tickAt(MLU_2_002);

    assertTrue(scenario.heldByTrain(OccupancyResource.forNode(JUNCTION)));
    assertTrue(scenario.heldByTrain(OccupancyResource.forNode(DEPOT)));
    assertFalse(scenario.retainedTrace("hardAuthority=fallback"));
    assertFalse(scenario.opposingTrainCanReachTheJunction());
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
            "FTA_ROUTE_INDEX=0");
    private final RouteProgressRegistry registry = new RouteProgressRegistry();
    private final List<String> messages = new ArrayList<>();
    private final SimpleRailGraph graph = ofl(worldId);
    private final RuntimeDispatchService service;
    private final double trainLengthBlocks;

    private Scenario(double trainLengthBlocks) {
      this.trainLengthBlocks = trainLengthBlocks;
      RouteDefinition route =
          new RouteDefinition(RouteId.of("MT-1O_ShortD"), List.of(OFL_2, DEPOT), Optional.empty());
      registry.initFromTags(TRAIN, tags.properties(), route);
      ConfigManager configManager = mock(ConfigManager.class);
      when(configManager.current()).thenReturn(testConfigView(20, 20.0, 3, 2));
      RailGraphService railGraphService = mock(RailGraphService.class);
      when(railGraphService.getSnapshot(worldId))
          .thenReturn(
              Optional.of(
                  new RailGraphService.RailGraphSnapshot(sectionlessGraph(graph), Instant.now())));
      when(railGraphService.effectiveSpeedLimitBlocksPerSecond(
              any(), any(), any(), anyDouble(), anyDouble()))
          .thenReturn(10.0);
      RouteDefinitionCache routeDefinitions = mock(RouteDefinitionCache.class);
      when(routeDefinitions.findByCodes("op", "l1", "r1")).thenReturn(Optional.of(route));
      service =
          new RuntimeDispatchService(
              manager,
              railGraphService,
              routeDefinitions,
              registry,
              mock(SignNodeRegistry.class),
              new LayoverRegistry(),
              new DwellRegistry(),
              configManager,
              null,
              new TrainConfigResolver(),
              messages::add);
    }

    private void tickAt(NodeId lastPassed) {
      registry.updateLastPassedGraphNode(TRAIN, lastPassed, Instant.now());
      // 13 格/秒：测试配置的余量只有 12 格（实服 40），车速取到让第二拍的硬授权窗口正好伸进联锁区那条库线边，与实服一致。
      FakeTrain train = new FakeTrain(worldId, tags.properties(), true, 0.65);
      train.estimatedTrainLengthBlocks = OptionalDouble.of(trainLengthBlocks);
      service.handleSignalTick(train, false);
    }

    private boolean heldByTrain(OccupancyResource resource) {
      return manager.snapshotClaims().stream()
          .anyMatch(
              claim ->
                  claim.resource().equals(resource)
                      && TRAIN.equals(claim.trainName())
                      && claim.role() == ClaimRole.MOVEMENT_REQUIRED);
    }

    private OccupancyResource interlocking() {
      return OccupancyResourceResolver.resourcesForEdge(
              graph, graph.findEdge(EdgeId.undirected(DEPOT_SWITCH, DEPOT_THROAT)).orElseThrow())
          .stream()
          .filter(OccupancyResourceResolver::isInterlockingConflict)
          .findFirst()
          .orElseThrow();
    }

    private boolean retainedTrace(String marker) {
      return messages.stream()
          .anyMatch(
              message ->
                  message.startsWith("SMART_FORWARD_AUTHORITY_RETAINED train=" + TRAIN)
                      && message.contains(marker));
    }

    private boolean opposingTrainCanReachTheJunction() {
      List<OccupancyResource> path =
          List.of(
              OccupancyResource.forNode(MLU_1_004),
              OccupancyResource.forEdge(EdgeId.undirected(MLU_1_004, JUNCTION)),
              OccupancyResource.forNode(JUNCTION),
              interlocking());
      Map<OccupancyResource, ResourceIntent> intents = new LinkedHashMap<>();
      path.forEach(resource -> intents.put(resource, ResourceIntent.MOVEMENT_REQUIRED));
      return manager
          .canEnter(
              new OccupancyRequest(
                  OPPOSING,
                  Optional.empty(),
                  Instant.now(),
                  path,
                  Map.of(),
                  Map.of(),
                  0,
                  AuthorizationPurpose.RUNTIME_MOVE,
                  Map.of(),
                  intents))
          .allowed();
    }
  }

  private static SimpleRailGraph ofl(UUID worldId) {
    Map<NodeId, RailNode> nodes = new LinkedHashMap<>();
    nodes.put(OFL_2, node(OFL_2, NodeType.STATION, WaypointMetadata.station("SURC", "OFL", 2)));
    nodes.put(MLU_2_001, interval(MLU_2_001, 2, "001"));
    nodes.put(MLU_2_002, interval(MLU_2_002, 2, "002"));
    for (NodeId switcher :
        List.of(CROSSOVER_2, DIAMOND_A, DIAMOND_B, CROSSOVER_1, JUNCTION, DEPOT_SWITCH)) {
      nodes.put(
          switcher,
          new SignRailNode(
              switcher, NodeType.SWITCHER, new Vector(), Optional.empty(), Optional.empty()));
    }
    nodes.put(MLU_1_003, interval(MLU_1_003, 1, "003"));
    nodes.put(MLU_1_004, interval(MLU_1_004, 1, "004"));
    nodes.put(
        DEPOT_THROAT,
        node(
            DEPOT_THROAT,
            NodeType.WAYPOINT,
            WaypointMetadata.depotThroat("SURC", "OFL", 1, "001")));
    nodes.put(DEPOT, node(DEPOT, NodeType.DEPOT, WaypointMetadata.depot("SURC", "OFL", 1)));
    Map<EdgeId, RailEdge> edges = new LinkedHashMap<>();
    Map<EdgeId, RailEdgeFootprint> footprints = new LinkedHashMap<>();
    edge(edges, footprints, OFL_2, MLU_2_001, 50, new RailFootprintCell(-519, 76, 2120));
    edge(edges, footprints, MLU_2_001, MLU_2_002, 47, new RailFootprintCell(-519, 76, 2170));
    edge(edges, footprints, MLU_2_002, CROSSOVER_2, 15, new RailFootprintCell(-519, 76, 2204));
    edge(edges, footprints, CROSSOVER_2, DIAMOND_A, 8, new RailFootprintCell(-519, 77, 2216));
    edge(edges, footprints, DIAMOND_A, DIAMOND_B, 5, new RailFootprintCell(-518, 77, 2223));
    edge(edges, footprints, DIAMOND_B, CROSSOVER_1, 9, new RailFootprintCell(-517, 77, 2231));
    edge(edges, footprints, CROSSOVER_1, MLU_1_003, 17, new RailFootprintCell(-516, 77, 2245));
    edge(edges, footprints, MLU_1_003, JUNCTION, 11, new RailFootprintCell(-516, 77, 2262));
    edge(edges, footprints, JUNCTION, DEPOT_SWITCH, 3, new RailFootprintCell(-515, 77, 2270));
    edge(edges, footprints, DEPOT_SWITCH, DEPOT_THROAT, 17, SHARED_CELL);
    edge(edges, footprints, DEPOT_THROAT, DEPOT, 45, new RailFootprintCell(-514, 77, 2320));
    edge(edges, footprints, MLU_1_004, JUNCTION, 26, SHARED_CELL);
    return new SimpleRailGraph(
        nodes, edges, Set.of(), RailInterlockingState.from(worldId, edges.keySet(), footprints));
  }

  private static RailNode interval(NodeId id, int track, String sequence) {
    return node(
        id, NodeType.WAYPOINT, WaypointMetadata.interval("SURC", "OFL", "MLU", track, sequence));
  }

  private static RailNode node(NodeId id, NodeType type, WaypointMetadata metadata) {
    return new SignRailNode(id, type, new Vector(), Optional.empty(), Optional.of(metadata));
  }

  private static void edge(
      Map<EdgeId, RailEdge> edges,
      Map<EdgeId, RailEdgeFootprint> footprints,
      NodeId from,
      NodeId to,
      int length,
      RailFootprintCell cell) {
    EdgeId id = EdgeId.undirected(from, to);
    edges.put(id, new RailEdge(id, from, to, length, -1.0, true, Optional.empty()));
    footprints.put(id, new RailEdgeFootprint(1, true, Set.of(cell)));
  }
}
