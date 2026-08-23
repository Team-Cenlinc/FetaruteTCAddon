package org.fetarute.fetaruteTCAddon.dispatcher.graph.persist;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.UUID;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.junit.jupiter.api.Test;

final class RailEdgeRecordTest {

  @Test
  void topologyRecordDoesNotExposePersistentTrackFootprint() {
    new RailEdgeRecord(
        UUID.randomUUID(), EdgeId.undirected(NodeId.of("A"), NodeId.of("B")), 12, 0.0, true);

    assertFalse(
        java.util.Arrays.stream(RailEdgeRecord.class.getRecordComponents())
            .anyMatch(component -> component.getName().equals("footprint")));
  }
}
