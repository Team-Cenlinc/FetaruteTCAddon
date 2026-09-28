package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchTestFixtures.testConfigView;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bergerkiller.bukkit.tc.events.SignActionEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.ClaimRole;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyClaim;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyResource;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspectPolicy;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SimpleOccupancyManager;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeRegistry;
import org.junit.jupiter.api.Test;

/**
 * 全网重建先停期间的到站：只提交到达事实，重建按新进度摆放占用，不留"幽灵车"。
 *
 * <p>照 2026-09-28 实服 3291：SPB:1 发车、开到 PTK:1 的同一刻别的车断车触发全网重建，到站被丢，重建按旧进度把它的占用摆回 SPB:1，
 * 后车合法开进空站台，两车从此永久互卡。夹具：车库侧 Z —50— SPB:1 —50— 区间点 —50— PTK:1 —50— RVS:1，交路 SPB:1 → PTK:1 → RVS:1。
 */
class StartupFreezeArrivalTest {

  private static final String TRAIN = "SURC-MT-LP-3291";
  private static final NodeId Z = NodeId.of("SURC:SPB:JBS:1:001");
  private static final NodeId SPB = NodeId.of("SURC:S:SPB:1");
  private static final NodeId MID = NodeId.of("SURC:PTK:SPB:1:001");
  private static final NodeId PTK = NodeId.of("SURC:S:PTK:1");
  private static final NodeId RVS = NodeId.of("SURC:S:RVS:1");
  private static final RailFootprintCell AT_SPB = new RailFootprintCell(0, 64, 10);
  private static final RailFootprintCell AT_PTK = new RailFootprintCell(0, 64, 30);

  @Test
  void anArrivalDuringTheFreezeIsCommittedAndTheRebuildFollowsIt() {
    Scenario scenario = new Scenario();
    FakeTrain train = scenario.hydratedAtSpb();

    scenario.service.beginStartupOccupancyReconstruction("unexpected-split");
    train.liveRailFootprintCells = Optional.of(Set.of(AT_PTK));
    scenario.service.handleStationArrival(train, station(PTK, "PTK"));
    scenario.rebuild(train);

    RouteProgressRegistry.RouteProgressEntry progress = scenario.registry.get(TRAIN).orElseThrow();
    assertEquals(1, progress.currentIndex(), "到站事实已提交");
    assertEquals(Optional.of(PTK), progress.lastPassedGraphNode());
    assertEquals(
        Optional.of(ClaimRole.PROTECTIVE_RETAIN),
        scenario.roleOf(OccupancyResource.forNode(SPB)),
        "SPB:1 最多是列尾防护，不能是车所在的位置");
    assertTrue(
        scenario
            .roleOf(OccupancyResource.forNode(PTK))
            .filter(role -> role != ClaimRole.PROTECTIVE_RETAIN)
            .isPresent(),
        "车在 PTK:1");
    assertTrue(
        scenario.messages.stream()
            .anyMatch(
                message ->
                    message.startsWith("SMART_STARTUP_FREEZE_ARRIVAL_COMMITTED train=" + TRAIN)
                        && message.contains("index=1")),
        scenario.messages::toString);
  }

  /** 推进点经过交路外的区间点：只更新最近经过节点，交路索引不动。 */
  @Test
  void aPassedIntervalPointDuringTheFreezeUpdatesTheLastPassedNode() {
    Scenario scenario = new Scenario();
    FakeTrain train = scenario.hydratedAtSpb();

    scenario.service.beginStartupOccupancyReconstruction("unexpected-split");
    scenario.service.handleProgressTrigger(
        train,
        mock(SignActionEvent.class),
        new SignNodeDefinition(
            MID,
            NodeType.WAYPOINT,
            Optional.empty(),
            Optional.of(WaypointMetadata.interval("SURC", "SPB", "PTK", 1, "001"))));

    RouteProgressRegistry.RouteProgressEntry progress = scenario.registry.get(TRAIN).orElseThrow();
    assertEquals(0, progress.currentIndex());
    assertEquals(Optional.of(MID), progress.lastPassedGraphNode());
  }

  /** 同名的另一个物理实体（重复身份、残编）在先停期间到站：不是已确认的主人，不补记。 */
  @Test
  void anArrivalFromAnotherPhysicalInstanceIsNotCommitted() {
    Scenario scenario = new Scenario();
    FakeTrain train = scenario.hydratedAtSpb();

    scenario.service.beginStartupOccupancyReconstruction("unexpected-split");
    FakeTrain impostor = new FakeTrain(scenario.worldId, train.properties, false);
    impostor.liveRailFootprintCells = Optional.of(Set.of(AT_PTK));
    scenario.service.handleStationArrival(impostor, station(PTK, "PTK"));

    assertEquals(0, scenario.registry.get(TRAIN).orElseThrow().currentIndex());
  }

  private static SignNodeDefinition station(NodeId id, String code) {
    return new SignNodeDefinition(
        id,
        NodeType.STATION,
        Optional.empty(),
        Optional.of(WaypointMetadata.station("SURC", code, 1)));
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
    private final RuntimeDispatchService service;

    private Scenario() {
      RouteDefinition route =
          new RouteDefinition(RouteId.of("MT-1N_Short"), List.of(SPB, PTK, RVS), Optional.empty());
      ConfigManager configManager = mock(ConfigManager.class);
      when(configManager.current()).thenReturn(testConfigView(20, 20.0));
      RailGraphService railGraphService = mock(RailGraphService.class);
      when(railGraphService.getSnapshot(worldId))
          .thenReturn(Optional.of(new RailGraphService.RailGraphSnapshot(graph(), Instant.now())));
      when(railGraphService.effectiveSpeedLimitBlocksPerSecond(any(), any(), any(), anyDouble()))
          .thenReturn(1000.0);
      RouteDefinitionCache routeDefinitions = mock(RouteDefinitionCache.class);
      when(routeDefinitions.findByCodes("op", "l1", "r1")).thenReturn(Optional.of(route));
      service =
          new RuntimeDispatchService(
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
              messages::add);
    }

    /** 车停在 SPB:1、完成一次启动重建：它成为已确认的物理主人。 */
    private FakeTrain hydratedAtSpb() {
      FakeTrain train = new FakeTrain(worldId, tags.properties(), false);
      train.liveRailFootprintCells = Optional.of(Set.of(AT_SPB));
      rebuild(train);
      return train;
    }

    private void rebuild(FakeTrain train) {
      assertTrue(service.prepareStartupOccupancySnapshot(List.of(train)), messages::toString);
      assertTrue(
          service.completeStartupOccupancyReconstruction(List.of(train)), messages::toString);
    }

    private Optional<ClaimRole> roleOf(OccupancyResource resource) {
      return manager.snapshotClaims().stream()
          .filter(claim -> claim.resource().equals(resource) && TRAIN.equals(claim.trainName()))
          .map(OccupancyClaim::role)
          .findFirst();
    }

    private SimpleRailGraph graph() {
      Map<NodeId, RailNode> nodes = new LinkedHashMap<>();
      nodes.put(Z, interval(Z, "JBS", "SPB", "001"));
      nodes.put(SPB, stationNode(SPB, "SPB"));
      nodes.put(MID, interval(MID, "SPB", "PTK", "001"));
      nodes.put(PTK, stationNode(PTK, "PTK"));
      nodes.put(RVS, stationNode(RVS, "RVS"));
      Map<EdgeId, RailEdge> edges = new LinkedHashMap<>();
      Map<EdgeId, RailEdgeFootprint> footprints = new LinkedHashMap<>();
      edge(edges, footprints, Z, SPB, AT_SPB);
      edge(edges, footprints, SPB, MID, new RailFootprintCell(0, 64, 20));
      edge(edges, footprints, MID, PTK, AT_PTK);
      edge(edges, footprints, PTK, RVS, new RailFootprintCell(0, 64, 40));
      return new SimpleRailGraph(
          nodes, edges, Set.of(), RailInterlockingState.from(worldId, edges.keySet(), footprints));
    }
  }

  private static RailNode stationNode(NodeId id, String code) {
    return new SignRailNode(
        id,
        NodeType.STATION,
        new Vector(),
        Optional.empty(),
        Optional.of(WaypointMetadata.station("SURC", code, 1)));
  }

  private static RailNode interval(NodeId id, String from, String to, String sequence) {
    return new SignRailNode(
        id,
        NodeType.WAYPOINT,
        new Vector(),
        Optional.empty(),
        Optional.of(WaypointMetadata.interval("SURC", from, to, 1, sequence)));
  }

  private static void edge(
      Map<EdgeId, RailEdge> edges,
      Map<EdgeId, RailEdgeFootprint> footprints,
      NodeId from,
      NodeId to,
      RailFootprintCell cell) {
    EdgeId id = EdgeId.undirected(from, to);
    edges.put(id, new RailEdge(id, from, to, 50, -1.0, true, Optional.empty()));
    footprints.put(id, new RailEdgeFootprint(1, true, Set.of(cell)));
  }
}
