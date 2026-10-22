package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchTestFixtures.sectionlessGraph;
import static org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchTestFixtures.testConfigView;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
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
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
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
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteLifecycleMode;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchTestFixtures.FakeTrain;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchTestFixtures.TagStore;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.config.TrainConfigResolver;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.AuthorizationPurpose;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyRequest;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyResource;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.ResourceIntent;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspectPolicy;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SimpleOccupancyManager;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeRegistry;
import org.junit.jupiter.api.Test;

/**
 * 终到停站结束、由出站门控登记成待命车：车身上可能一个停因都没有（停妥晚于调度那一拍“由动转停”的巡检）。巡检不唤醒静止又没有停因的车，
 * 待命保持（只保持车身）就永远跑不到，进站时一路盖到身后道岔的尾部保护会一直留着，去另一个站台的后车只能等。
 *
 * <p>夹具照实服 PPK1：S:RVS:1 —38— RVS:1:002 —41— RVS:1:001 —21— SW650 —18— SW630 —25— PPK1；两节车，
 * 进站途中滑动取得的尾部保护是保护性保持。
 */
class LayoverRegistrationRecheckTest {

  private static final String TRAIN = "SURC-MT-LP-0752";
  private static final NodeId SRVS = NodeId.of("SURC:S:RVS:1");
  private static final NodeId RVS2 = NodeId.of("SURC:PPK:RVS:1:002");
  private static final NodeId RVS1 = NodeId.of("SURC:PPK:RVS:1:001");
  private static final NodeId SW650 = NodeId.of("SWITCHER:Towny:-579:65:650");
  private static final NodeId SW630 = NodeId.of("SWITCHER:Towny:-579:65:630");
  private static final NodeId PPK1 = NodeId.of("SURC:S:PPK:1");
  private static final EdgeId SRVS_RVS2 = EdgeId.undirected(SRVS, RVS2);
  private static final EdgeId RVS2_RVS1 = EdgeId.undirected(RVS2, RVS1);
  private static final EdgeId RVS1_SW650 = EdgeId.undirected(RVS1, SW650);
  private static final EdgeId SW650_SW630 = EdgeId.undirected(SW650, SW630);
  private static final EdgeId SW630_PPK1 = EdgeId.undirected(SW630, PPK1);
  private static final OccupancyResource SWITCHER_650 =
      OccupancyResource.forConflict("switcher:" + SW650.value());
  private static final OccupancyResource SWITCHER_630 =
      OccupancyResource.forConflict("switcher:" + SW630.value());

  /** 由出站门控登记：登记即记下待命停因，巡检随即复查；下一拍待命 tick 把尾部保护收窄到车身。 */
  @Test
  void aTrainRegisteredByTheDepartureGateIsRecheckedAndKeepsOnlyItsBody() {
    Scenario scenario = new Scenario();
    assertTrue(scenario.service.getActiveStopState(TRAIN).isEmpty(), "前提：车身上没有停因");

    assertFalse(scenario.service.checkDeparture(scenario.train(), scenario.platformSign()));

    assertTrue(scenario.layoverRegistry.get(TRAIN).isPresent(), "终到停站结束登记成待命车");
    assertEquals(
        Optional.of("LAYOVER_HOLD"),
        scenario.service.getActiveStopState(TRAIN).map(RuntimeStopState::reasonCode));
    assertTrue(
        scenario.service.heldTrainOccupancyVersion(TRAIN).isPresent(), "停车态的车巡检会按占用变化与兜底节拍复查");

    scenario.service.handleSignalTick(scenario.train(), false);

    assertTrue(scenario.heldByTrain(OccupancyResource.forEdge(SW630_PPK1)), scenario.claims());
    assertTrue(scenario.heldByTrain(OccupancyResource.forNode(PPK1)), scenario.claims());
    assertFalse(scenario.heldByTrain(OccupancyResource.forNode(SW650)), scenario.claims());
    assertFalse(scenario.heldByTrain(SWITCHER_650), scenario.claims());
    assertFalse(scenario.heldByTrain(OccupancyResource.forNode(RVS1)), scenario.claims());
    assertFalse(scenario.heldByTrain(OccupancyResource.forNode(RVS2)), scenario.claims());
  }

  /** 已有停因（比如终到停站的门控停车）的不改写，由待命 tick 自己接着改写成待命停因。 */
  @Test
  void anExistingStopStateIsLeftForTheLayoverTickToRewrite() {
    Scenario scenario = new Scenario();
    scenario.service.recordStopState(
        RuntimeStopState.plannedStop(
            TRAIN,
            "DEPARTURE_GATE_HOLD",
            "autostation_dwell@test",
            RuntimeStopState.ReleaseCondition.PLANNED_STOP_COMPLETED,
            RuntimeStopState.RetryTrigger.DWELL_OR_DOOR_EVENT,
            Instant.now()));

    assertFalse(scenario.service.checkDeparture(scenario.train(), scenario.platformSign()));

    assertEquals(
        Optional.of("DEPARTURE_GATE_HOLD"),
        scenario.service.getActiveStopState(TRAIN).map(RuntimeStopState::reasonCode));
    scenario.service.handleSignalTick(scenario.train(), false);
    assertEquals(
        Optional.of("LAYOVER_HOLD"),
        scenario.service.getActiveStopState(TRAIN).map(RuntimeStopState::reasonCode));
    assertFalse(scenario.heldByTrain(OccupancyResource.forNode(RVS1)), scenario.claims());
  }

  /** 停在 PPK1、刚结束终到停站的两节车，持有进站授权与滑动取得的尾部保护。 */
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
            "FTA_ROUTE_INDEX=2");
    private final RouteProgressRegistry registry = new RouteProgressRegistry();
    private final LayoverRegistry layoverRegistry = new LayoverRegistry();
    private final RouteDefinition route =
        new RouteDefinition(
            RouteId.of("MT-9F_Join"),
            List.of(SRVS, RVS1, PPK1),
            Optional.empty(),
            RouteLifecycleMode.REUSE_AT_TERM);
    private final RuntimeDispatchService service;

    private Scenario() {
      registry.initFromTags(TRAIN, tags.properties(), route);
      service = service();
      Map<OccupancyResource, ResourceIntent> held = new LinkedHashMap<>();
      held.put(OccupancyResource.forNode(RVS2), ResourceIntent.PROTECTIVE_RETAIN);
      held.put(OccupancyResource.forEdge(RVS2_RVS1), ResourceIntent.PROTECTIVE_RETAIN);
      held.put(OccupancyResource.forNode(RVS1), ResourceIntent.PROTECTIVE_RETAIN);
      held.put(OccupancyResource.forEdge(RVS1_SW650), ResourceIntent.PROTECTIVE_RETAIN);
      held.put(OccupancyResource.forNode(SW650), ResourceIntent.PROTECTIVE_RETAIN);
      held.put(OccupancyResource.forEdge(SW650_SW630), ResourceIntent.PROTECTIVE_RETAIN);
      held.put(SWITCHER_650, ResourceIntent.MOVEMENT_REQUIRED);
      held.put(OccupancyResource.forNode(SW630), ResourceIntent.MOVEMENT_REQUIRED);
      held.put(SWITCHER_630, ResourceIntent.MOVEMENT_REQUIRED);
      held.put(OccupancyResource.forEdge(SW630_PPK1), ResourceIntent.MOVEMENT_REQUIRED);
      held.put(OccupancyResource.forNode(PPK1), ResourceIntent.MOVEMENT_REQUIRED);
      held.forEach(
          (resource, intent) ->
              assertTrue(
                  manager
                      .acquire(
                          new OccupancyRequest(
                              TRAIN,
                              Optional.empty(),
                              Instant.now(),
                              List.of(resource),
                              Map.of(),
                              Map.of(),
                              0,
                              AuthorizationPurpose.RUNTIME_MOVE,
                              Map.of(),
                              Map.of(resource, intent)))
                      .allowed()));
    }

    /** 停稳的两节车，车身只压着站台那一段。 */
    private FakeTrain train() {
      FakeTrain train = new FakeTrain(worldId, tags.properties(), false, 0.0);
      train.liveRailFootprintCells = Optional.of(Set.of(CELLS.get(SW630_PPK1)));
      train.estimatedTrainLengthBlocks = OptionalDouble.of(38.6);
      return train;
    }

    private SignNodeDefinition platformSign() {
      return new SignNodeDefinition(PPK1, NodeType.STATION, Optional.empty(), Optional.empty());
    }

    private boolean heldByTrain(OccupancyResource resource) {
      return manager
          .getClaim(resource)
          .filter(claim -> TRAIN.equals(claim.trainName()))
          .isPresent();
    }

    private String claims() {
      return manager.snapshotClaims().stream()
          .filter(claim -> TRAIN.equals(claim.trainName()))
          .map(claim -> claim.resource() + "@" + claim.role())
          .toList()
          .toString();
    }

    private RuntimeDispatchService service() {
      ConfigManager configManager = mock(ConfigManager.class);
      when(configManager.current()).thenReturn(testConfigView(20, 20.0));
      RailGraphService railGraphService = mock(RailGraphService.class);
      when(railGraphService.getSnapshot(worldId))
          .thenReturn(
              Optional.of(
                  new RailGraphService.RailGraphSnapshot(
                      sectionlessGraph(ppk(worldId)), Instant.now())));
      when(railGraphService.effectiveSpeedLimitBlocksPerSecond(
              any(), any(), any(), anyDouble(), anyDouble()))
          .thenReturn(1000.0);
      RouteDefinitionCache routeDefinitions = mock(RouteDefinitionCache.class);
      when(routeDefinitions.findByCodes("op", "l1", "r1")).thenReturn(Optional.of(route));
      when(routeDefinitions.findStop(eq(route.id()), anyInt())).thenReturn(Optional.empty());
      when(routeDefinitions.findStop(route.id(), 2))
          .thenReturn(
              Optional.of(
                  new RouteStop(
                      UUID.randomUUID(),
                      2,
                      Optional.empty(),
                      Optional.of(PPK1.value()),
                      Optional.of(20),
                      RouteStopPassType.TERMINATE,
                      Optional.empty())));
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
  }

  private static final Map<EdgeId, RailFootprintCell> CELLS =
      Map.of(
          SRVS_RVS2, new RailFootprintCell(-30, 64, 0),
          RVS2_RVS1, new RailFootprintCell(-10, 64, 0),
          RVS1_SW650, new RailFootprintCell(10, 64, 0),
          SW650_SW630, new RailFootprintCell(30, 64, 0),
          SW630_PPK1, new RailFootprintCell(50, 64, 0));

  /** 每条边一个互不相交的足迹方块，联锁目录完整。 */
  private static SimpleRailGraph ppk(UUID worldId) {
    Map<NodeId, RailNode> nodes = new LinkedHashMap<>();
    nodes.put(SRVS, node(SRVS, NodeType.STATION, -79.0));
    nodes.put(RVS2, node(RVS2, NodeType.WAYPOINT, -41.0));
    nodes.put(RVS1, node(RVS1, NodeType.WAYPOINT, 0.0));
    nodes.put(SW650, node(SW650, NodeType.SWITCHER, 21.0));
    nodes.put(SW630, node(SW630, NodeType.SWITCHER, 39.0));
    nodes.put(PPK1, node(PPK1, NodeType.STATION, 64.0));
    Map<EdgeId, RailEdge> edges = new LinkedHashMap<>();
    putEdge(edges, SRVS_RVS2, 38);
    putEdge(edges, RVS2_RVS1, 41);
    putEdge(edges, RVS1_SW650, 21);
    putEdge(edges, SW650_SW630, 18);
    putEdge(edges, SW630_PPK1, 25);
    Map<EdgeId, RailEdgeFootprint> footprints = new LinkedHashMap<>();
    CELLS.forEach(
        (edge, cell) -> footprints.put(edge, new RailEdgeFootprint(1, true, Set.of(cell))));
    return new SimpleRailGraph(
        nodes, edges, Set.of(), RailInterlockingState.from(worldId, edges.keySet(), footprints));
  }

  private static RailNode node(NodeId id, NodeType type, double x) {
    return new SignRailNode(id, type, new Vector(x, 64.0, 0.0), Optional.empty(), Optional.empty());
  }

  private static void putEdge(Map<EdgeId, RailEdge> edges, EdgeId id, int length) {
    edges.put(id, new RailEdge(id, id.a(), id.b(), length, -1.0, true, Optional.empty()));
  }
}
