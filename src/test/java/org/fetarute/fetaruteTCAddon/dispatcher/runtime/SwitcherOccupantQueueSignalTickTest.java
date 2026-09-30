package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchTestFixtures.testConfigView;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinitionCache;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteId;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchTestFixtures.FakeTrain;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchTestFixtures.TagStore;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.config.TrainConfigResolver;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.AuthorizationPurpose;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.ClaimRole;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.DirectedTraversalContext;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyRequest;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyResource;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.ResourceIntent;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspect;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspectPolicy;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SimpleOccupancyManager;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeRegistry;
import org.junit.jupiter.api.Test;

/**
 * 信号 tick 端到端：车身压在 SPB 汇合岔上的车，不被岔外先排上队的车挡住。
 *
 * <p>实服 2026-09-27 12:42–12:51：MT-LP-6727 车头已越过 {@code -566:77:1179}，以 HOLD_ONLY 持有道岔节点、出口边与道岔冲突资源；
 * MT-LP-7340 从另一支过来，被 6727 的车体节点挡住后先进了道岔队列。已验证的出清证明每拍都成立，但排队检查不看它，6727 被排在 7340 后面，
 * 两车互等到关服。独立成类：{@code RuntimeDispatchServiceTest} 已贴着 SpotBugs 单类 1000 方法的上限。
 */
class SwitcherOccupantQueueSignalTickTest {

  /**
   * 夹具起点取当前时间：信号 tick 按真实时钟清理超过 30 秒没刷新的排队条目，写死的时间会让岔外车的排队一进 tick 就过期， 队列变空，用例就测不到排队（第一版就是这样空绿的）。
   */
  private static final Instant T0 = Instant.now().minusSeconds(5);

  private static final String OCCUPANT = "SURC-MT-LP-6727";
  private static final String ENTRANT = "SURC-MT-LP-7340";
  private static final NodeId OCCUPANT_FROM = NodeId.of("SURC:SPB:JBS:1:003");
  private static final NodeId ENTRANT_FROM = NodeId.of("SURC:SPB:WSD:2:001");
  private static final NodeId SWITCHER = NodeId.of("SWITCHER:Towny:-566:77:1179");
  private static final NodeId EXIT = NodeId.of("SURC:SPB:JBS:1:002");

  @Test
  void switcherOccupantDrainsAheadOfTheEntrantQueuedFirst() {
    RailEdge occupantEdge = edge(OCCUPANT_FROM, SWITCHER, 23);
    RailEdge entrantEdge = edge(ENTRANT_FROM, SWITCHER, 40);
    RailEdge exitEdge = edge(SWITCHER, EXIT, 33);
    RailGraph graph =
        new SimpleRailGraph(
            Map.of(
                OCCUPANT_FROM, new RailNodeTest(OCCUPANT_FROM),
                ENTRANT_FROM, new RailNodeTest(ENTRANT_FROM),
                SWITCHER, new RailNodeTest(SWITCHER, NodeType.SWITCHER, Optional.empty()),
                EXIT, new RailNodeTest(EXIT)),
            Map.of(
                occupantEdge.id(), occupantEdge,
                entrantEdge.id(), entrantEdge,
                exitEdge.id(), exitEdge),
            Set.of());
    OccupancyResource switcherConflict =
        OccupancyResource.forConflict("switcher:" + SWITCHER.value());
    OccupancyResource switcherNode = OccupancyResource.forNode(SWITCHER);
    OccupancyResource exitEdgeResource = OccupancyResource.forEdge(exitEdge.id());
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    assertTrue(
        manager
            .acquire(
                new OccupancyRequest(
                    OCCUPANT,
                    Optional.empty(),
                    T0,
                    List.of(switcherNode, exitEdgeResource, switcherConflict),
                    Map.of(),
                    Map.of(),
                    0,
                    AuthorizationPurpose.RUNTIME_MOVE,
                    Map.of(),
                    Map.of(
                        switcherNode, ResourceIntent.HOLD_ONLY,
                        exitEdgeResource, ResourceIntent.HOLD_ONLY,
                        switcherConflict, ResourceIntent.HOLD_ONLY)))
            .allowed());
    OccupancyRequest entrantTraversal =
        traversal(
            ENTRANT,
            T0.plusSeconds(1),
            List.of(ENTRANT_FROM, SWITCHER, EXIT),
            List.of(entrantEdge, exitEdge),
            List.of(
                switcherConflict,
                OccupancyResource.forEdge(entrantEdge.id()),
                switcherNode,
                exitEdgeResource));
    assertFalse(manager.canEnter(entrantTraversal).allowed(), "岔外的车被岔上车体挡住，先排进道岔队列");
    assertTrue(
        manager.snapshotQueues().stream()
            .filter(queue -> queue.resource().equals(switcherConflict))
            .flatMap(queue -> queue.entries().stream())
            .anyMatch(entry -> entry.trainName().equals(ENTRANT)));

    RouteDefinition route =
        new RouteDefinition(
            RouteId.of("SURC:MT:MT-1N_Short"),
            List.of(
                NodeId.of("SURC:TEST:0"),
                NodeId.of("SURC:TEST:1"),
                NodeId.of("SURC:TEST:2"),
                NodeId.of("SURC:TEST:3"),
                NodeId.of("SURC:TEST:4"),
                NodeId.of("SURC:TEST:5"),
                NodeId.of("SURC:TEST:6"),
                NodeId.of("SURC:TEST:7"),
                OCCUPANT_FROM,
                EXIT),
            Optional.empty());
    TagStore tags =
        new TagStore(
            OCCUPANT,
            "FTA_OPERATOR_CODE=op",
            "FTA_LINE_CODE=l1",
            "FTA_ROUTE_CODE=r1",
            "FTA_ROUTE_INDEX=8");
    UUID worldId = UUID.randomUUID();
    RouteProgressRegistry progress = new RouteProgressRegistry();
    progress.initFromTags(OCCUPANT, tags.properties(), route);
    progress.updateLastPassedGraphNode(OCCUPANT, SWITCHER, T0.plusSeconds(2));
    progress.updateSignal(OCCUPANT, SignalAspect.STOP, T0.plusSeconds(2));
    List<String> debugMessages = new ArrayList<>();
    RuntimeDispatchService service =
        service(manager, graph, route, progress, worldId, debugMessages);
    FakeTrain train = new FakeTrain(worldId, tags.properties(), false, 0.0);

    service.handleSignalTick(train, true);

    assertEquals(SignalAspect.PROCEED, progress.get(OCCUPANT).orElseThrow().lastSignal());
    assertEquals(1, train.launchCalls, debugMessages.toString());
    assertEquals(OCCUPANT, manager.getClaim(switcherConflict).orElseThrow().trainName());
    assertEquals(
        ClaimRole.MOVEMENT_REQUIRED, manager.getClaim(switcherConflict).orElseThrow().role());
    assertFalse(
        manager
            .canEnter(
                traversal(
                    ENTRANT,
                    T0.plusSeconds(3),
                    List.of(ENTRANT_FROM, SWITCHER, EXIT),
                    List.of(entrantEdge, exitEdge),
                    entrantTraversal.resourceList()))
            .allowed(),
        "岔外的车照旧等车体让开");
  }

  private static RuntimeDispatchService service(
      SimpleOccupancyManager manager,
      RailGraph graph,
      RouteDefinition route,
      RouteProgressRegistry progress,
      UUID worldId,
      List<String> debugMessages) {
    ConfigManager configManager = mock(ConfigManager.class);
    when(configManager.current()).thenReturn(testConfigView(1, 20.0));
    RailGraphService railGraphService = mock(RailGraphService.class);
    when(railGraphService.getSnapshot(worldId))
        .thenReturn(Optional.of(new RailGraphService.RailGraphSnapshot(graph, Instant.now())));
    when(railGraphService.effectiveSpeedLimitBlocksPerSecond(
            any(), any(), any(), anyDouble(), anyDouble()))
        .thenReturn(20.0);
    RouteDefinitionCache routeDefinitions = mock(RouteDefinitionCache.class);
    when(routeDefinitions.findByCodes("op", "l1", "r1")).thenReturn(Optional.of(route));
    return new RuntimeDispatchService(
        manager,
        railGraphService,
        routeDefinitions,
        progress,
        mock(SignNodeRegistry.class),
        mock(LayoverRegistry.class),
        new DwellRegistry(),
        configManager,
        null,
        new TrainConfigResolver(),
        debugMessages::add);
  }

  private static RailEdge edge(NodeId from, NodeId to, int lengthBlocks) {
    return new RailEdge(
        EdgeId.undirected(from, to), from, to, lengthBlocks, -1.0, true, Optional.empty());
  }

  private static OccupancyRequest traversal(
      String train,
      Instant now,
      List<NodeId> path,
      List<RailEdge> edges,
      List<OccupancyResource> resources) {
    List<DirectedTraversalContext.DirectedEdge> directed = new ArrayList<>();
    for (int i = 0; i < edges.size(); i++) {
      directed.add(
          new DirectedTraversalContext.DirectedEdge(
              edges.get(i).id(), path.get(i), path.get(i + 1)));
    }
    String conflictKey = "switcher:" + SWITCHER.value();
    return new OccupancyRequest(
            train,
            Optional.empty(),
            now,
            resources,
            Map.of(),
            Map.of(conflictKey, 0),
            0,
            AuthorizationPurpose.RUNTIME_MOVE)
        .withDirectedContext(
            Optional.of(
                new DirectedTraversalContext(
                    train,
                    Optional.empty(),
                    8,
                    Optional.of(path.get(0)),
                    Optional.empty(),
                    Optional.of(path.get(0)),
                    Optional.of(path.get(1)),
                    path,
                    directed,
                    Map.of(),
                    Map.of(
                        conflictKey,
                        new DirectedTraversalContext.SwitcherPathSignature(conflictKey, path)),
                    "TEST",
                    1L,
                    1L,
                    "switcher-" + train,
                    Optional.empty())));
  }
}
