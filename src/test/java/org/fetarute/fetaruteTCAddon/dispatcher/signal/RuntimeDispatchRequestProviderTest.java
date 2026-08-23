package org.fetarute.fetaruteTCAddon.dispatcher.signal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.CorridorDirection;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyManager;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyQueueEntry;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyQueueSnapshot;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyQueueSupport;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyResource;
import org.junit.jupiter.api.Test;

/** {@link RuntimeDispatchRequestProvider} 的轻量 Gate Queue 查询测试。 */
class RuntimeDispatchRequestProviderTest {

  /** 合并接口用于测试可读取的 Queue 快照。 */
  interface OccupancyManagerWithQueueSupport extends OccupancyManager, OccupancyQueueSupport {}

  @Test
  void trainsWaitingFor_returnsEmptyForMissingResources() {
    RuntimeDispatchRequestProvider provider =
        new RuntimeDispatchRequestProvider(mock(OccupancyManagerWithQueueSupport.class));

    assertTrue(provider.trainsWaitingFor(null).isEmpty());
    assertTrue(provider.trainsWaitingFor(List.of()).isEmpty());
  }

  @Test
  void trainsWaitingFor_returnsOnlyQueueHeadForChangedResources() {
    OccupancyManagerWithQueueSupport occupancyManager =
        mock(OccupancyManagerWithQueueSupport.class);
    RuntimeDispatchRequestProvider provider = new RuntimeDispatchRequestProvider(occupancyManager);
    OccupancyResource changed = OccupancyResource.forNode(NodeId.of("OP:S:StationA:1"));
    OccupancyResource unrelated = OccupancyResource.forNode(NodeId.of("OP:S:StationB:1"));
    Instant now = Instant.parse("2026-08-17T00:00:00Z");
    OccupancyQueueEntry queueHead =
        new OccupancyQueueEntry("train-head", CorridorDirection.UNKNOWN, now, now, 0, 1);
    OccupancyQueueEntry laterWaiter =
        new OccupancyQueueEntry("train-later", CorridorDirection.UNKNOWN, now, now, 0, 2);
    OccupancyQueueEntry unrelatedWaiter =
        new OccupancyQueueEntry("train-unrelated", CorridorDirection.UNKNOWN, now, now, 0, 1);
    when(occupancyManager.snapshotQueues())
        .thenReturn(
            List.of(
                new OccupancyQueueSnapshot(
                    changed, Optional.empty(), 0, List.of(queueHead, laterWaiter)),
                new OccupancyQueueSnapshot(
                    unrelated, Optional.empty(), 0, List.of(unrelatedWaiter))));

    assertEquals(List.of("train-head"), provider.trainsWaitingFor(List.of(changed)));
  }

  @Test
  void trainsWaitingFor_neverPromotesUnqueuedCandidates() {
    RuntimeDispatchRequestProvider provider =
        new RuntimeDispatchRequestProvider(mock(OccupancyManager.class));

    assertTrue(
        provider
            .trainsWaitingFor(List.of(OccupancyResource.forNode(NodeId.of("OP:S:A:1"))))
            .isEmpty());
  }
}
