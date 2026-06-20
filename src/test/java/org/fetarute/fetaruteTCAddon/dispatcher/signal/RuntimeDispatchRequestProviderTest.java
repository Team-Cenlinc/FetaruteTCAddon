package org.fetarute.fetaruteTCAddon.dispatcher.signal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

import com.bergerkiller.bukkit.tc.properties.TrainProperties;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.fetarute.fetaruteTCAddon.config.ConfigManager;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailEdge;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphService;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.SimpleRailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinitionCache;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteId;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.DispatchPriorityResolution;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.DispatchPriorityResolver;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.DispatchPrioritySource;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.RouteProgressRegistry;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.CorridorDirection;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyManager;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyQueueEntry;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyQueueSnapshot;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyQueueSupport;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyRequest;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyResource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link RuntimeDispatchRequestProvider} 单元测试。
 *
 * <p>由于 buildRequest 依赖 TrainPropertiesStore（静态 TrainCarts API），此处仅测试 trainsWaitingFor 等可 mock 的逻辑。
 */
class RuntimeDispatchRequestProviderTest {

  private RailGraphService railGraphService;
  private RouteDefinitionCache routeDefinitions;
  private RouteProgressRegistry progressRegistry;
  private ConfigManager configManager;
  private OccupancyManagerWithQueueSupport occupancyManager;

  private RuntimeDispatchRequestProvider provider;

  /** 合并接口用于测试 mock。 */
  interface OccupancyManagerWithQueueSupport extends OccupancyManager, OccupancyQueueSupport {}

  @BeforeEach
  void setUp() {
    railGraphService = mock(RailGraphService.class);
    routeDefinitions = mock(RouteDefinitionCache.class);
    progressRegistry = mock(RouteProgressRegistry.class);
    configManager = mock(ConfigManager.class);
    occupancyManager = mock(OccupancyManagerWithQueueSupport.class);

    provider =
        new RuntimeDispatchRequestProvider(
            railGraphService,
            routeDefinitions,
            progressRegistry,
            configManager,
            occupancyManager,
            msg -> {});
  }

  @Test
  void trainsWaitingFor_emptyResources_returnsEmptyList() {
    List<String> result = provider.trainsWaitingFor(List.of());
    assertTrue(result.isEmpty());
  }

  @Test
  void trainsWaitingFor_nullResources_returnsEmptyList() {
    List<String> result = provider.trainsWaitingFor(null);
    assertTrue(result.isEmpty());
  }

  @Test
  void trainsWaitingFor_findsTrainsInQueue() {
    NodeId nodeA = NodeId.of("OP:S:StationA:1");
    OccupancyResource resource = OccupancyResource.forNode(nodeA);
    Instant now = Instant.now();

    OccupancyQueueEntry entry1 =
        new OccupancyQueueEntry("train-1", CorridorDirection.UNKNOWN, now, now, 0, 1);
    OccupancyQueueEntry entry2 =
        new OccupancyQueueEntry("train-2", CorridorDirection.UNKNOWN, now, now, 0, 2);

    OccupancyQueueSnapshot snapshot =
        new OccupancyQueueSnapshot(resource, Optional.empty(), 0, List.of(entry1, entry2));

    when(occupancyManager.snapshotQueues()).thenReturn(List.of(snapshot));

    List<String> result = provider.trainsWaitingFor(List.of(resource));

    assertEquals(2, result.size());
    assertTrue(result.contains("train-1"));
    assertTrue(result.contains("train-2"));
  }

  @Test
  void trainsWaitingFor_filtersNonMatchingResources() {
    NodeId nodeA = NodeId.of("OP:S:StationA:1");
    NodeId nodeB = NodeId.of("OP:S:StationB:1");
    OccupancyResource resourceA = OccupancyResource.forNode(nodeA);
    OccupancyResource resourceB = OccupancyResource.forNode(nodeB);
    Instant now = Instant.now();

    OccupancyQueueEntry entryA =
        new OccupancyQueueEntry("train-A", CorridorDirection.UNKNOWN, now, now, 0, 1);
    OccupancyQueueEntry entryB =
        new OccupancyQueueEntry("train-B", CorridorDirection.UNKNOWN, now, now, 0, 2);

    OccupancyQueueSnapshot snapshotA =
        new OccupancyQueueSnapshot(resourceA, Optional.empty(), 0, List.of(entryA));
    OccupancyQueueSnapshot snapshotB =
        new OccupancyQueueSnapshot(resourceB, Optional.empty(), 0, List.of(entryB));

    when(occupancyManager.snapshotQueues()).thenReturn(List.of(snapshotA, snapshotB));

    // 只查询 resourceA
    List<String> result = provider.trainsWaitingFor(List.of(resourceA));

    assertEquals(1, result.size());
    assertTrue(result.contains("train-A"));
  }

  @Test
  void trainsWaitingFor_deduplicatesTrains() {
    NodeId nodeA = NodeId.of("OP:S:StationA:1");
    NodeId nodeB = NodeId.of("OP:S:StationB:1");
    OccupancyResource resourceA = OccupancyResource.forNode(nodeA);
    OccupancyResource resourceB = OccupancyResource.forNode(nodeB);
    Instant now = Instant.now();

    // 同一列车在多个资源队列中
    OccupancyQueueEntry entry =
        new OccupancyQueueEntry("train-1", CorridorDirection.UNKNOWN, now, now, 0, 1);

    OccupancyQueueSnapshot snapshotA =
        new OccupancyQueueSnapshot(resourceA, Optional.empty(), 0, List.of(entry));
    OccupancyQueueSnapshot snapshotB =
        new OccupancyQueueSnapshot(resourceB, Optional.empty(), 0, List.of(entry));

    when(occupancyManager.snapshotQueues()).thenReturn(List.of(snapshotA, snapshotB));

    List<String> result = provider.trainsWaitingFor(List.of(resourceA, resourceB));

    // 应该去重，只返回一个 train-1
    assertEquals(1, result.size());
    assertTrue(result.contains("train-1"));
  }

  @Test
  void trainsWaitingFor_nonQueueSupportManager_returnsEmptyList() {
    // 使用不支持 OccupancyQueueSupport 的 mock
    OccupancyManager plainManager = mock(OccupancyManager.class);
    RuntimeDispatchRequestProvider plainProvider =
        new RuntimeDispatchRequestProvider(
            railGraphService,
            routeDefinitions,
            progressRegistry,
            configManager,
            plainManager,
            msg -> {});

    NodeId nodeA = NodeId.of("OP:S:StationA:1");
    OccupancyResource resource = OccupancyResource.forNode(nodeA);

    List<String> result = plainProvider.trainsWaitingFor(List.of(resource));
    assertTrue(result.isEmpty());
  }

  @Test
  void trainsWaitingFor_wakesForwardRouteCandidateForReleasedNodeOrEdge() {
    NodeId nodeA = NodeId.of("OP:S:A:1");
    NodeId nodeB = NodeId.of("OP:S:B:1");
    NodeId nodeC = NodeId.of("OP:S:C:1");
    RouteDefinition route =
        new RouteDefinition(RouteId.of("OP:L1:R1"), List.of(nodeA, nodeB, nodeC), Optional.empty());
    RouteProgressRegistry registry = new RouteProgressRegistry();
    registry.initFromTags("following-train", new TagStore("following-train").properties(), route);
    RouteDefinitionCache routes = mock(RouteDefinitionCache.class);
    when(routes.snapshot()).thenReturn(Map.of(java.util.UUID.randomUUID(), route));
    RuntimeDispatchRequestProvider plainProvider =
        new RuntimeDispatchRequestProvider(
            railGraphService,
            routes,
            registry,
            configManager,
            mock(OccupancyManager.class),
            msg -> {});

    List<String> result =
        plainProvider.trainsWaitingFor(
            List.of(
                OccupancyResource.forNode(nodeB),
                OccupancyResource.forEdge(EdgeId.undirected(nodeA, nodeB))));

    assertEquals(1, result.size());
    assertTrue(result.contains("following-train"));
  }

  @Test
  void resolveWaypointsForRequestUsesRuntimeEffectiveNodes() {
    NodeId dynamic = NodeId.of("OP:S:CENTRAL:DYNAMIC");
    NodeId actual = NodeId.of("OP:S:CENTRAL:2");
    NodeId next = NodeId.of("OP:S:NEXT:1");
    RouteDefinition route =
        new RouteDefinition(RouteId.of("r"), List.of(dynamic, next), Optional.empty());
    RuntimeDispatchRequestProvider effectiveProvider =
        new RuntimeDispatchRequestProvider(
            railGraphService,
            routeDefinitions,
            progressRegistry,
            configManager,
            occupancyManager,
            (trainName, ignoredRoute) -> List.of(actual, next),
            msg -> {});

    List<NodeId> resolved = effectiveProvider.resolveWaypointsForRequest("train-1", route);

    assertEquals(List.of(actual, next), resolved);
  }

  @Test
  void buildRequestUsesInjectedPriorityResolver() {
    NodeId current = NodeId.of("OP:S:A:1");
    NodeId next = NodeId.of("OP:S:B:1");
    RouteDefinition route =
        new RouteDefinition(RouteId.of("OP:L1:R1"), List.of(current, next), Optional.empty());
    RouteDefinitionCache routes = mock(RouteDefinitionCache.class);
    when(routes.findByCodes("OP", "L1", "R1")).thenReturn(Optional.of(route));
    RouteProgressRegistry registry = new RouteProgressRegistry();
    RailGraphService graphService = mock(RailGraphService.class);
    java.util.UUID worldId = java.util.UUID.randomUUID();
    when(graphService.getSnapshot(worldId))
        .thenReturn(
            Optional.of(
                new RailGraphService.RailGraphSnapshot(
                    graphWithSingleEdge(current, next), Instant.now())));
    ConfigManager config = mock(ConfigManager.class);
    ConfigManager.ConfigView view = mock(ConfigManager.ConfigView.class);
    ConfigManager.RuntimeSettings runtimeSettings = mock(ConfigManager.RuntimeSettings.class);
    when(config.current()).thenReturn(view);
    when(view.runtimeSettings()).thenReturn(runtimeSettings);
    when(runtimeSettings.lookaheadEdges()).thenReturn(1);
    when(runtimeSettings.minClearEdges()).thenReturn(0);
    when(runtimeSettings.switcherZoneEdges()).thenReturn(0);
    DispatchPriorityResolver resolver = mock(DispatchPriorityResolver.class);
    List<String> debugMessages = new ArrayList<>();
    RuntimeDispatchRequestProvider requestProvider =
        new RuntimeDispatchRequestProvider(
            graphService,
            routes,
            registry,
            config,
            occupancyManager,
            (trainName, ignoredRoute, currentIndex, graph) -> ignoredRoute.waypoints(),
            resolver,
            debugMessages::add);
    TagStore tags =
        new TagStore(
            "train-1",
            "FTA_OPERATOR_CODE=OP",
            "FTA_LINE_CODE=L1",
            "FTA_ROUTE_CODE=R1",
            "FTA_ROUTE_INDEX=0");
    DispatchPriorityResolution resolvedPriority =
        new DispatchPriorityResolution(
            37,
            DispatchPrioritySource.ROUTE_CODE_TAGS,
            Optional.empty(),
            Optional.of("OP:L1:R1"),
            Optional.empty(),
            "test");
    when(resolver.resolve(
            eq("event-request-provider"), eq("train-1"), eq(tags.properties()), eq(route), any()))
        .thenReturn(resolvedPriority);

    OccupancyRequest request =
        requestProvider
            .buildRequestFromProperties(
                "train-1", tags.properties(), worldId, Instant.parse("2026-01-01T00:00:00Z"))
            .orElseThrow();

    assertEquals(37, request.priority());
    assertTrue(
        debugMessages.stream()
            .anyMatch(message -> message.contains("SMART_DISPATCH_REQUEST_CONTEXT")));
  }

  @Test
  void buildRequestIncludesRearGuardLikePeriodicPath() {
    NodeId previous = NodeId.of("OP:S:A:1");
    NodeId current = NodeId.of("OP:S:B:1");
    NodeId next = NodeId.of("OP:S:C:1");
    RouteDefinition route =
        new RouteDefinition(
            RouteId.of("OP:L1:R1"), List.of(previous, current, next), Optional.empty());
    RouteDefinitionCache routes = mock(RouteDefinitionCache.class);
    when(routes.findByCodes("OP", "L1", "R1")).thenReturn(Optional.of(route));
    RouteProgressRegistry registry = new RouteProgressRegistry();
    RailGraphService graphService = mock(RailGraphService.class);
    java.util.UUID worldId = java.util.UUID.randomUUID();
    when(graphService.getSnapshot(worldId))
        .thenReturn(
            Optional.of(
                new RailGraphService.RailGraphSnapshot(
                    graphWithChain(previous, current, next), Instant.now())));
    ConfigManager config = mock(ConfigManager.class);
    ConfigManager.ConfigView view = mock(ConfigManager.ConfigView.class);
    ConfigManager.RuntimeSettings runtimeSettings = mock(ConfigManager.RuntimeSettings.class);
    when(config.current()).thenReturn(view);
    when(view.runtimeSettings()).thenReturn(runtimeSettings);
    when(runtimeSettings.lookaheadEdges()).thenReturn(1);
    when(runtimeSettings.minClearEdges()).thenReturn(0);
    when(runtimeSettings.rearGuardEdges()).thenReturn(1);
    when(runtimeSettings.switcherZoneEdges()).thenReturn(0);
    DispatchPriorityResolver resolver = mock(DispatchPriorityResolver.class);
    when(resolver.resolve(any(), any(), any(), any(), any()))
        .thenReturn(
            new DispatchPriorityResolution(
                0,
                DispatchPrioritySource.ROUTE_CODE_TAGS,
                Optional.empty(),
                Optional.of("OP:L1:R1"),
                Optional.empty(),
                "test"));
    RuntimeDispatchRequestProvider requestProvider =
        new RuntimeDispatchRequestProvider(
            graphService,
            routes,
            registry,
            config,
            occupancyManager,
            (trainName, ignoredRoute, currentIndex, graph) -> ignoredRoute.waypoints(),
            resolver,
            msg -> {});
    TagStore tags =
        new TagStore(
            "train-1",
            "FTA_OPERATOR_CODE=OP",
            "FTA_LINE_CODE=L1",
            "FTA_ROUTE_CODE=R1",
            "FTA_ROUTE_INDEX=1");

    OccupancyRequest request =
        requestProvider
            .buildRequestFromProperties(
                "train-1", tags.properties(), worldId, Instant.parse("2026-01-01T00:00:00Z"))
            .orElseThrow();

    OccupancyResource rearNode = OccupancyResource.forNode(previous);
    assertTrue(
        request.resourceList().contains(rearNode), "事件请求应包含与 periodic path 相同的 rear-guard 保护资源");
    assertEquals(
        org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.ResourceIntent.PROTECTIVE_RETAIN,
        request.intentFor(rearNode));
  }

  private static SimpleRailGraph graphWithChain(NodeId first, NodeId second, NodeId third) {
    RailEdge edgeA =
        new RailEdge(
            EdgeId.undirected(first, second), first, second, 10, -1.0, true, Optional.empty());
    RailEdge edgeB =
        new RailEdge(
            EdgeId.undirected(second, third), second, third, 10, -1.0, true, Optional.empty());
    return new SimpleRailGraph(
        Map.of(first, testNode(first), second, testNode(second), third, testNode(third)),
        Map.of(edgeA.id(), edgeA, edgeB.id(), edgeB),
        Set.of());
  }

  private static SimpleRailGraph graphWithSingleEdge(NodeId from, NodeId to) {
    RailEdge edge =
        new RailEdge(EdgeId.undirected(from, to), from, to, 10, -1.0, true, Optional.empty());
    RailNode fromNode = testNode(from);
    RailNode toNode = testNode(to);
    return new SimpleRailGraph(
        Map.of(from, fromNode, to, toNode), Map.of(edge.id(), edge), Set.of());
  }

  private static RailNode testNode(NodeId nodeId) {
    return new RailNode() {
      @Override
      public NodeId id() {
        return nodeId;
      }

      @Override
      public NodeType type() {
        return NodeType.WAYPOINT;
      }

      @Override
      public org.bukkit.util.Vector worldPosition() {
        return new org.bukkit.util.Vector(0, 0, 0);
      }

      @Override
      public Optional<String> trainCartsDestination() {
        return Optional.of(nodeId.value());
      }
    };
  }

  private static final class TagStore {
    private final TrainProperties properties;
    private final List<String> tags;

    private TagStore(String trainName, String... initial) {
      this.tags = new ArrayList<>(Arrays.asList(initial));
      this.properties = mock(TrainProperties.class);
      when(properties.getTrainName()).thenReturn(trainName);
      when(properties.hasTags()).thenAnswer(inv -> !tags.isEmpty());
      when(properties.getTags()).thenAnswer(inv -> List.copyOf(tags));
    }

    private TrainProperties properties() {
      return properties;
    }
  }
}
