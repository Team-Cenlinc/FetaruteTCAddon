package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchTestFixtures.sectionlessGraph;
import static org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchTestFixtures.testConfigView;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
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
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
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
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.CorridorDirection;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyRequest;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyResource;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.ResourceIntent;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspectPolicy;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SimpleOccupancyManager;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeRegistry;
import org.junit.jupiter.api.Test;

/**
 * 终点待命车只保持车身实际压着的轨道：双站台终点的另一个站台不再被它挡住。
 *
 * <p>夹具照实服 PPK：RVS —21— SW650 —18— SW630 —25— PPK1，SW650 另有一支 —7— SW643 —36— PPK2。去两个站台都要过 SW650。
 * MT 保守车长 34，停在 PPK1：从站台节点往回量 25 + 18 ≥ 34，再留 1 条边，尾部保护一路盖到 RVS，SW650 被占； 实际车体以站牌为中心，只压着 SW630—PPK1
 * 那条边。
 */
class LayoverBodyRetainTest {

  private static final String TRAIN = "SURC-MT-LP-9367";
  private static final String FOLLOWER = "SURC-MT-LP-9562";
  private static final NodeId RVS = NodeId.of("SURC:PPK:RVS:1:001");
  private static final NodeId SW650 = NodeId.of("SWITCHER:Towny:-579:65:650");
  private static final NodeId SW630 = NodeId.of("SWITCHER:Towny:-579:65:630");
  private static final NodeId PPK1 = NodeId.of("SURC:S:PPK:1");
  private static final NodeId SW643 = NodeId.of("SWITCHER:Towny:-581:65:643");
  private static final NodeId PPK2 = NodeId.of("SURC:S:PPK:2");
  private static final EdgeId RVS_SW650 = EdgeId.undirected(RVS, SW650);
  private static final EdgeId SW650_SW630 = EdgeId.undirected(SW650, SW630);
  private static final EdgeId SW630_PPK1 = EdgeId.undirected(SW630, PPK1);
  private static final EdgeId SW650_SW643 = EdgeId.undirected(SW650, SW643);
  private static final EdgeId SW643_PPK2 = EdgeId.undirected(SW643, PPK2);
  private static final OccupancyResource SWITCHER_650 =
      OccupancyResource.forConflict("switcher:" + SW650.value());
  private static final OccupancyResource SWITCHER_630 =
      OccupancyResource.forConflict("switcher:" + SW630.value());

  // ------------------------------------------------------------------ narrow 本身

  /** 停稳且车身覆盖完整：只留车身区间、两端节点与它们带出的道岔冲突；身后的共用道岔放开。 */
  @Test
  void stationaryWaitingTrainKeepsOnlyItsBody() {
    RailGraph graph = ppk(UUID.randomUUID());
    OccupancyRequest hold = approachHold();

    OccupancyRequest narrowed =
        LayoverBodyRetain.narrow(hold, Optional.of(bodyOnPlatformEdge()), true, graph, PPK1);

    assertEquals(
        Set.of(
            OccupancyResource.forNode(PPK1),
            OccupancyResource.forNode(SW630),
            OccupancyResource.forEdge(SW630_PPK1),
            SWITCHER_630),
        Set.copyOf(narrowed.resourceList()));
    assertEquals(Set.copyOf(narrowed.resourceList()), narrowed.resourceIntents().keySet());
    assertFalse(narrowed.corridorDirections().containsKey(SWITCHER_650.key()), "方向随资源一起删");
    assertTrue(narrowed.corridorDirections().containsKey(SWITCHER_630.key()));
  }

  /** 证据任一不成立就原样返回：没停稳、覆盖不完整、不是待命车。 */
  @Test
  void anyMissingEvidenceKeepsTheOriginalRequest() {
    RailGraph graph = ppk(UUID.randomUUID());
    OccupancyRequest hold = approachHold();

    assertSame(
        hold,
        LayoverBodyRetain.narrow(hold, Optional.of(bodyOnPlatformEdge()), false, graph, PPK1));
    assertSame(
        hold,
        LayoverBodyRetain.narrow(
            hold,
            Optional.of(RuntimeDispatchService.LivePhysicalEdgeCoverage.incomplete("test")),
            true,
            graph,
            PPK1));
    assertSame(hold, LayoverBodyRetain.narrow(hold, Optional.empty(), true, graph, PPK1));
  }

  /** 当前所在节点无论覆盖与否都保留；结果只会是原请求的子集。 */
  @Test
  void currentNodeIsAlwaysKeptAndNothingIsAdded() {
    RailGraph graph = ppk(UUID.randomUUID());
    OccupancyRequest hold = approachHold();
    RuntimeDispatchService.LivePhysicalEdgeCoverage elsewhere =
        RuntimeDispatchService.LivePhysicalEdgeCoverage.complete(
            Set.of(
                OccupancyResource.forEdge(SW643_PPK2),
                OccupancyResource.forNode(SW643),
                OccupancyResource.forNode(PPK2)));

    OccupancyRequest narrowed =
        LayoverBodyRetain.narrow(hold, Optional.of(elsewhere), true, graph, PPK1);

    assertEquals(List.of(OccupancyResource.forNode(PPK1)), narrowed.resourceList());
  }

  // ------------------------------------------------------------------ 信号 tick 全链路

  /**
   * 待命车停稳、车身位置完整：一拍之后身后进站留下的占用放掉，去 PPK2 的后车能拿到 SW650。
   *
   * <p>以前这一拍会按尾部保护把整条进站路重新占住，后车在 RVS 等到待命车按表发车（实服约 3 分钟）。
   */
  @Test
  void waitingTrainReleasesTheSharedEntrySwitchForTheOtherPlatform() {
    Scenario scenario = new Scenario();

    scenario.tick(false, Optional.of(Set.of(cellOf(SW630_PPK1))));

    assertTrue(scenario.heldByTrain(OccupancyResource.forEdge(SW630_PPK1)), "车身区间照旧保持");
    assertTrue(scenario.heldByTrain(OccupancyResource.forNode(PPK1)));
    assertTrue(scenario.heldByTrain(OccupancyResource.forNode(SW630)), "车身区间的端点照旧保持");
    assertFalse(scenario.heldByTrain(OccupancyResource.forNode(SW650)));
    assertFalse(scenario.heldByTrain(OccupancyResource.forEdge(RVS_SW650)));
    assertFalse(scenario.heldByTrain(SWITCHER_650));
    assertTrue(scenario.followerCanReachPpk2(), "去另一个站台的后车能过共用道岔");

    // 后车真的拿下这条进路之后，待命车下一拍不会把它抢回来，也不会与后车共占。
    scenario.followerAcquiresPpk2Path();
    scenario.tick(false, Optional.of(Set.of(cellOf(SW630_PPK1))));

    assertEquals(
        Optional.of(FOLLOWER),
        scenario
            .manager
            .getClaim(OccupancyResource.forNode(SW650))
            .map(claim -> claim.trainName()));
    assertEquals(
        Optional.of(FOLLOWER),
        scenario.manager.getClaim(SWITCHER_650).map(claim -> claim.trainName()));
    assertTrue(scenario.heldByTrain(OccupancyResource.forEdge(SW630_PPK1)));
  }

  /** 没停稳或读不到车身位置：保持原有的整条尾部保护，后车照旧等。 */
  @Test
  void withoutStationaryCompleteBodyEvidenceTheApproachStaysHeld() {
    Scenario moving = new Scenario();
    moving.tick(true, Optional.of(Set.of(cellOf(SW630_PPK1))));
    assertTrue(moving.heldByTrain(OccupancyResource.forNode(SW650)), "没停稳不收窄");
    assertFalse(moving.followerCanReachPpk2());

    Scenario blind = new Scenario();
    blind.tick(false, Optional.empty());
    assertTrue(blind.heldByTrain(OccupancyResource.forNode(SW650)), "读不到车身位置不收窄");
    assertFalse(blind.followerCanReachPpk2());
  }

  /** 停在 PPK1 的待命车，持有进站授权留下的整条进路。 */
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
            "FTA_ROUTE_INDEX=1");
    private final RouteProgressRegistry registry = new RouteProgressRegistry();
    private final LayoverRegistry layoverRegistry = new LayoverRegistry();
    private final RuntimeDispatchService service;

    private Scenario() {
      RouteDefinition route =
          new RouteDefinition(RouteId.of("MT-2F_Short"), List.of(RVS, PPK1), Optional.empty());
      registry.initFromTags(TRAIN, tags.properties(), route);
      service = service(manager, worldId, route, registry, layoverRegistry);
      List<OccupancyResource> arrival =
          List.of(
              OccupancyResource.forNode(RVS),
              OccupancyResource.forEdge(RVS_SW650),
              OccupancyResource.forNode(SW650),
              SWITCHER_650,
              OccupancyResource.forEdge(SW650_SW630),
              OccupancyResource.forNode(SW630),
              SWITCHER_630,
              OccupancyResource.forEdge(SW630_PPK1),
              OccupancyResource.forNode(PPK1));
      Map<OccupancyResource, ResourceIntent> intents = new LinkedHashMap<>();
      arrival.forEach(resource -> intents.put(resource, ResourceIntent.MOVEMENT_REQUIRED));
      assertTrue(
          manager
              .acquire(
                  new OccupancyRequest(
                      TRAIN,
                      Optional.empty(),
                      Instant.now(),
                      arrival,
                      Map.of(),
                      Map.of(),
                      0,
                      AuthorizationPurpose.RUNTIME_MOVE,
                      Map.of(),
                      intents))
              .allowed());
      layoverRegistry.register(TRAIN, "surc:s:ppk:1", PPK1, Instant.now(), Map.of());
    }

    private void tick(boolean moving, Optional<Set<RailFootprintCell>> cells) {
      FakeTrain train = new FakeTrain(worldId, tags.properties(), moving, moving ? 0.5 : 0.0);
      train.liveRailFootprintCells = cells;
      train.estimatedTrainLengthBlocks = OptionalDouble.of(34.0);
      service.handleSignalTick(train, false);
    }

    private boolean heldByTrain(OccupancyResource resource) {
      return manager
          .getClaim(resource)
          .filter(claim -> TRAIN.equals(claim.trainName()))
          .isPresent();
    }

    private boolean followerCanReachPpk2() {
      return manager.canEnter(followerPath()).allowed();
    }

    private void followerAcquiresPpk2Path() {
      assertTrue(manager.acquire(followerPath()).allowed());
    }

    private OccupancyRequest followerPath() {
      List<OccupancyResource> path =
          List.of(
              OccupancyResource.forNode(SW650),
              SWITCHER_650,
              OccupancyResource.forEdge(SW650_SW643),
              OccupancyResource.forNode(SW643),
              OccupancyResource.forConflict("switcher:" + SW643.value()));
      Map<OccupancyResource, ResourceIntent> intents = new LinkedHashMap<>();
      path.forEach(resource -> intents.put(resource, ResourceIntent.MOVEMENT_REQUIRED));
      return new OccupancyRequest(
          FOLLOWER,
          Optional.empty(),
          Instant.now(),
          path,
          Map.of(),
          Map.of(),
          0,
          AuthorizationPurpose.RUNTIME_MOVE,
          Map.of(),
          intents);
    }
  }

  private static RuntimeDispatchService service(
      SimpleOccupancyManager manager,
      UUID worldId,
      RouteDefinition route,
      RouteProgressRegistry registry,
      LayoverRegistry layoverRegistry) {
    ConfigManager configManager = mock(ConfigManager.class);
    when(configManager.current()).thenReturn(testConfigView(20, 20.0));
    RailGraphService railGraphService = mock(RailGraphService.class);
    when(railGraphService.getSnapshot(worldId))
        .thenReturn(
            Optional.of(
                new RailGraphService.RailGraphSnapshot(
                    sectionlessGraph(ppk(worldId)), Instant.now())));
    when(railGraphService.effectiveSpeedLimitBlocksPerSecond(any(), any(), any(), anyDouble()))
        .thenReturn(1000.0);
    RouteDefinitionCache routeDefinitions = mock(RouteDefinitionCache.class);
    when(routeDefinitions.findByCodes("op", "l1", "r1")).thenReturn(Optional.of(route));
    return new RuntimeDispatchService(
        manager,
        railGraphService,
        routeDefinitions,
        registry,
        mock(SignNodeRegistry.class),
        layoverRegistry,
        new DwellRegistry(),
        configManager,
        null,
        new TrainConfigResolver(),
        message -> {});
  }

  /** 停车保持按尾部保护构建出的原请求：整条进站路，道岔冲突带方向。 */
  private static OccupancyRequest approachHold() {
    List<OccupancyResource> resources =
        List.of(
            OccupancyResource.forNode(RVS),
            OccupancyResource.forEdge(RVS_SW650),
            OccupancyResource.forNode(SW650),
            SWITCHER_650,
            OccupancyResource.forEdge(SW650_SW630),
            OccupancyResource.forNode(SW630),
            SWITCHER_630,
            OccupancyResource.forEdge(SW630_PPK1),
            OccupancyResource.forNode(PPK1));
    Map<OccupancyResource, ResourceIntent> intents = new LinkedHashMap<>();
    resources.forEach(resource -> intents.put(resource, ResourceIntent.PROTECTIVE_RETAIN));
    return new OccupancyRequest(
        TRAIN,
        Optional.empty(),
        Instant.now(),
        resources,
        Map.of(
            SWITCHER_650.key(),
            CorridorDirection.A_TO_B,
            SWITCHER_630.key(),
            CorridorDirection.A_TO_B),
        Map.of(),
        0,
        AuthorizationPurpose.RUNTIME_MOVE,
        Map.of(),
        intents);
  }

  private static RuntimeDispatchService.LivePhysicalEdgeCoverage bodyOnPlatformEdge() {
    return RuntimeDispatchService.LivePhysicalEdgeCoverage.complete(
        Set.of(
            OccupancyResource.forEdge(SW630_PPK1),
            OccupancyResource.forNode(SW630),
            OccupancyResource.forNode(PPK1)));
  }

  private static RailFootprintCell cellOf(EdgeId edge) {
    return CELLS.get(edge);
  }

  private static final Map<EdgeId, RailFootprintCell> CELLS =
      Map.of(
          RVS_SW650, new RailFootprintCell(10, 64, 0),
          SW650_SW630, new RailFootprintCell(30, 64, 0),
          SW630_PPK1, new RailFootprintCell(50, 64, 0),
          SW650_SW643, new RailFootprintCell(24, 64, 5),
          SW643_PPK2, new RailFootprintCell(40, 64, 10));

  /** PPK 站前：两个站台共用入口道岔 SW650；每条边一个互不相交的足迹方块，联锁目录完整。 */
  private static SimpleRailGraph ppk(UUID worldId) {
    Map<NodeId, RailNode> nodes = new LinkedHashMap<>();
    nodes.put(RVS, node(RVS, NodeType.WAYPOINT, 0.0, 0.0));
    nodes.put(SW650, node(SW650, NodeType.SWITCHER, 21.0, 0.0));
    nodes.put(SW630, node(SW630, NodeType.SWITCHER, 39.0, 0.0));
    nodes.put(PPK1, node(PPK1, NodeType.STATION, 64.0, 0.0));
    nodes.put(SW643, node(SW643, NodeType.SWITCHER, 26.0, 5.0));
    nodes.put(PPK2, node(PPK2, NodeType.STATION, 60.0, 10.0));
    Map<EdgeId, RailEdge> edges = new LinkedHashMap<>();
    putEdge(edges, RVS_SW650, 21);
    putEdge(edges, SW650_SW630, 18);
    putEdge(edges, SW630_PPK1, 25);
    putEdge(edges, SW650_SW643, 7);
    putEdge(edges, SW643_PPK2, 36);
    Map<EdgeId, RailEdgeFootprint> footprints = new LinkedHashMap<>();
    CELLS.forEach(
        (edge, cell) -> footprints.put(edge, new RailEdgeFootprint(1, true, Set.of(cell))));
    return new SimpleRailGraph(
        nodes, edges, Set.of(), RailInterlockingState.from(worldId, edges.keySet(), footprints));
  }

  private static RailNode node(NodeId id, NodeType type, double x, double z) {
    return new SignRailNode(id, type, new Vector(x, 64.0, z), Optional.empty(), Optional.empty());
  }

  private static void putEdge(Map<EdgeId, RailEdge> edges, EdgeId id, int length) {
    edges.put(id, new RailEdge(id, id.a(), id.b(), length, -1.0, true, Optional.empty()));
  }
}
