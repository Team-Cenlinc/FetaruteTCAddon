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
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyRequest;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyResource;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.ResourceIntent;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspectPolicy;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SimpleOccupancyManager;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeRegistry;
import org.junit.jupiter.api.Test;

/**
 * 授权回滚只撤销这一拍新拿到的部分：之前已持有的合流岔不能被放给汇入车。
 *
 * <p>形状照实服 SPB 合流岔（2026-09-28 一夜断车 5 次）：WSD 2 道 —40— 合流岔 {@code -566:77:1179} —33— JBS 1 道 002 —58—
 * 001 —56— SPB:1，JBS 侧 003 —20— 合流岔汇入。前车 {@code 3504} 从 WSD 来：第一拍拿下合流岔；前方被 {@code 5086}
 * 保护占用后，快车第二拍窗口伸到那条边被拒、停车状态记下这个阻挡；减速后第三拍窗口缩回、acquire 成功，但阻挡还在，最终校验失败回滚。
 * 修复前回滚把第一拍就持有的合流岔冲突资源一起放掉，JBS 来车当拍就能上岔。
 */
class MergeSwitchRollbackTest {

  private static final String LEADER = "SURC-MT-LP-3504";
  private static final String BLOCKER = "SURC-MT-LP-5086";
  private static final String MERGING = "SURC-MT-LP-3544";
  private static final NodeId WSD_002 = NodeId.of("SURC:SPB:WSD:2:002");
  private static final NodeId WSD_001 = NodeId.of("SURC:SPB:WSD:2:001");
  private static final NodeId MERGE = NodeId.of("SWITCHER:Towny:-566:77:1179");
  private static final NodeId JBS_003 = NodeId.of("SURC:SPB:JBS:1:003");
  private static final NodeId JBS_002 = NodeId.of("SURC:SPB:JBS:1:002");
  private static final NodeId JBS_001 = NodeId.of("SURC:SPB:JBS:1:001");
  private static final NodeId SPB_1 = NodeId.of("SURC:S:SPB:1");
  private static final OccupancyResource MERGE_CONFLICT =
      OccupancyResource.forConflict("switcher:" + MERGE.value());

  @Test
  void aRollbackKeepsTheMergeSwitchTheLeaderAlreadyHeld() {
    Scenario scenario = Scenario.playedOut();

    assertTrue(
        scenario.messages.stream()
            .anyMatch(
                message ->
                    message.contains(
                        "final-authorization:active-occupancy-stop-blocker-still-held")),
        "第三拍必须走到最终校验失败的回滚");
    assertTrue(scenario.heldByLeader(MERGE_CONFLICT), "第一拍就持有的合流岔冲突资源不能被回滚放掉");
    assertFalse(scenario.mergingTrainCanEnterTheMerge(), "前车还没出清，汇入车不能上岔");
    assertTrue(
        scenario.messages.stream()
            .anyMatch(
                message ->
                    message.startsWith("SMART_AUTHORITY_ROLLBACK_KEPT_HELD train=" + LEADER)
                        && message.contains("reason=AUTHORIZATION_FAILURE")
                        && message.contains(MERGE_CONFLICT.toString())),
        scenario.messages::toString);
  }

  /** 回滚与就地保持之后，第三拍重新拿到的合流岔后区间与 002（车身之外、第二拍已收回）照旧放掉，不因为保护之前持有的就整段留下。 */
  @Test
  void aRollbackStillReleasesWhatThisTickNewlyAcquired() {
    Scenario scenario = Scenario.playedOut();

    assertFalse(scenario.heldByLeader(OccupancyResource.forNode(JBS_002)));
    assertFalse(
        scenario.heldByLeader(OccupancyResource.forEdge(EdgeId.undirected(MERGE, JBS_002))));
  }

  private static final class Scenario {
    private final UUID worldId = UUID.randomUUID();
    private final SimpleOccupancyManager manager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    private final TagStore tags =
        new TagStore(
            LEADER,
            "FTA_OPERATOR_CODE=op",
            "FTA_LINE_CODE=l1",
            "FTA_ROUTE_CODE=r1",
            "FTA_ROUTE_INDEX=0");
    private final RouteProgressRegistry registry = new RouteProgressRegistry();
    private final List<String> messages = new ArrayList<>();
    private final SimpleRailGraph graph = spb(worldId);
    private final RuntimeDispatchService service;

    private Scenario() {
      RouteDefinition route =
          new RouteDefinition(RouteId.of("MT-2F_Short"), List.of(WSD_001, SPB_1), Optional.empty());
      registry.initFromTags(LEADER, tags.properties(), route);
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

    private void tick(NodeId lastPassed, double speedBlocksPerTick) {
      registry.updateLastPassedGraphNode(LEADER, lastPassed, Instant.now());
      FakeTrain train = new FakeTrain(worldId, tags.properties(), true, speedBlocksPerTick);
      train.estimatedTrainLengthBlocks = OptionalDouble.of(34.0);
      service.handleSignalTick(train, false);
    }

    private void blockAhead() {
      OccupancyResource edge = OccupancyResource.forEdge(EdgeId.undirected(JBS_002, JBS_001));
      Map<OccupancyResource, ResourceIntent> intents = new LinkedHashMap<>();
      intents.put(edge, ResourceIntent.PROTECTIVE_RETAIN);
      manager.acquire(
          new OccupancyRequest(
              BLOCKER,
              Optional.empty(),
              Instant.now(),
              List.of(edge),
              Map.of(),
              Map.of(),
              0,
              AuthorizationPurpose.RUNTIME_MOVE,
              Map.of(),
              intents));
    }

    /** 三拍：拿下合流岔 → 快车窗口伸到被挡的区间、被拒 → 减速后 acquire 成功但最终校验失败回滚。 */
    private static Scenario playedOut() {
      Scenario scenario = new Scenario();
      scenario.tick(WSD_001, 0.2);
      scenario.blockAhead();
      scenario.tick(WSD_001, 0.6);
      scenario.tick(WSD_001, 0.2);
      return scenario;
    }

    private boolean heldByLeader(OccupancyResource resource) {
      return manager.snapshotClaims().stream()
          .anyMatch(claim -> claim.resource().equals(resource) && LEADER.equals(claim.trainName()));
    }

    private boolean mergingTrainCanEnterTheMerge() {
      List<OccupancyResource> path =
          List.of(
              OccupancyResource.forNode(JBS_003),
              OccupancyResource.forEdge(EdgeId.undirected(JBS_003, MERGE)),
              OccupancyResource.forNode(MERGE),
              MERGE_CONFLICT,
              OccupancyResource.forEdge(EdgeId.undirected(MERGE, JBS_002)));
      Map<OccupancyResource, ResourceIntent> intents = new LinkedHashMap<>();
      path.forEach(resource -> intents.put(resource, ResourceIntent.MOVEMENT_REQUIRED));
      return manager
          .canEnter(
              new OccupancyRequest(
                  MERGING,
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

  private static SimpleRailGraph spb(UUID worldId) {
    Map<NodeId, RailNode> nodes = new LinkedHashMap<>();
    nodes.put(WSD_002, interval(WSD_002, "WSD", 2, "002"));
    nodes.put(WSD_001, interval(WSD_001, "WSD", 2, "001"));
    nodes.put(
        MERGE,
        new SignRailNode(
            MERGE, NodeType.SWITCHER, new Vector(), Optional.empty(), Optional.empty()));
    nodes.put(JBS_003, interval(JBS_003, "JBS", 1, "003"));
    nodes.put(JBS_002, interval(JBS_002, "JBS", 1, "002"));
    nodes.put(JBS_001, interval(JBS_001, "JBS", 1, "001"));
    nodes.put(
        SPB_1,
        new SignRailNode(
            SPB_1,
            NodeType.STATION,
            new Vector(),
            Optional.empty(),
            Optional.of(WaypointMetadata.station("SURC", "SPB", 1))));
    Map<EdgeId, RailEdge> edges = new LinkedHashMap<>();
    Map<EdgeId, RailEdgeFootprint> footprints = new LinkedHashMap<>();
    edge(edges, footprints, WSD_002, WSD_001, 47, new RailFootprintCell(-500, 74, 1209));
    edge(edges, footprints, WSD_001, MERGE, 40, new RailFootprintCell(-562, 77, 1183));
    edge(edges, footprints, JBS_003, MERGE, 20, new RailFootprintCell(-560, 77, 1190));
    edge(edges, footprints, MERGE, JBS_002, 33, new RailFootprintCell(-572, 77, 1166));
    edge(edges, footprints, JBS_002, JBS_001, 58, new RailFootprintCell(-579, 75, 1120));
    edge(edges, footprints, JBS_001, SPB_1, 56, new RailFootprintCell(-579, 75, 1060));
    return new SimpleRailGraph(
        nodes, edges, Set.of(), RailInterlockingState.from(worldId, edges.keySet(), footprints));
  }

  private static RailNode interval(NodeId id, String from, int track, String sequence) {
    return new SignRailNode(
        id,
        NodeType.WAYPOINT,
        new Vector(),
        Optional.empty(),
        Optional.of(WaypointMetadata.interval("SURC", "SPB", from, track, sequence)));
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
