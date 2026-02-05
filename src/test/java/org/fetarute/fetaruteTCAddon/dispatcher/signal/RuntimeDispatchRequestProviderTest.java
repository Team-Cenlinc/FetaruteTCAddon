package org.fetarute.fetaruteTCAddon.dispatcher.signal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.fetarute.fetaruteTCAddon.config.ConfigManager;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphService;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinitionCache;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.RouteProgressRegistry;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.CorridorDirection;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyManager;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyQueueEntry;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyQueueSnapshot;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyQueueSupport;
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
}
