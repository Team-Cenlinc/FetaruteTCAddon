package org.fetarute.fetaruteTCAddon.dispatcher.graph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.persist.RailEdgeOverrideRecord;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.junit.jupiter.api.Test;

/** 边覆盖只读快照：不改时复用同一份，写入与删除后立即反映。 */
class RailGraphServiceEdgeOverrideSnapshotTest {

  private static final UUID WORLD = UUID.randomUUID();
  private static final EdgeId EDGE = EdgeId.undirected(NodeId.of("A"), NodeId.of("B"));

  @Test
  void snapshotIsReusedUntilOverridesChange() {
    RailGraphService service = new RailGraphService(world -> SimpleRailGraph.empty());

    Map<EdgeId, RailEdgeOverrideRecord> empty = service.edgeOverrides(WORLD);
    assertTrue(empty.isEmpty());
    assertSame(empty, service.edgeOverrides(WORLD));

    service.putEdgeOverride(speedLimit(10.0));
    Map<EdgeId, RailEdgeOverrideRecord> afterPut = service.edgeOverrides(WORLD);
    assertEquals(OptionalDouble.of(10.0), afterPut.get(EDGE).speedLimitBlocksPerSecond());
    assertSame(afterPut, service.edgeOverrides(WORLD));

    service.putEdgeOverride(speedLimit(12.0));
    assertEquals(
        OptionalDouble.of(12.0),
        service.edgeOverrides(WORLD).get(EDGE).speedLimitBlocksPerSecond());

    service.deleteEdgeOverride(WORLD, EDGE);
    assertTrue(service.edgeOverrides(WORLD).isEmpty());
  }

  private static RailEdgeOverrideRecord speedLimit(double speed) {
    return new RailEdgeOverrideRecord(
        WORLD,
        EDGE,
        OptionalDouble.of(speed),
        OptionalDouble.empty(),
        Optional.empty(),
        false,
        Optional.empty(),
        Instant.EPOCH);
  }
}
