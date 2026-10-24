package org.fetarute.fetaruteTCAddon.dispatcher.eta.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteId;
import org.junit.jupiter.api.Test;

/** 快照版本：写入与移除都会让它变，移除不存在的列车不变。 */
class TrainSnapshotStoreVersionTest {

  @Test
  void versionChangesOnEveryWriteAndRemoval() {
    TrainSnapshotStore store = new TrainSnapshotStore();
    long initial = store.version();

    store.update("train-1", snapshot());
    store.update("train-1", snapshot());
    assertEquals(initial + 2, store.version());

    store.remove("train-1");
    assertEquals(initial + 3, store.version());

    store.remove("train-1");
    store.remove("missing");
    assertEquals(initial + 3, store.version(), "没有可移除的快照时版本不变");
  }

  private static TrainRuntimeSnapshot snapshot() {
    NodeId node = NodeId.of("SURN:S:AAA:1");
    return new TrainRuntimeSnapshot(
        1L,
        Instant.EPOCH,
        UUID.randomUUID(),
        UUID.randomUUID(),
        RouteId.of("SURN:L1:R1"),
        0,
        Optional.of(node),
        Optional.of(node),
        Optional.empty(),
        Optional.empty(),
        Optional.empty());
  }
}
