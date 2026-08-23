package org.fetarute.fetaruteTCAddon.dispatcher.graph;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.Optional;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.junit.jupiter.api.Test;

class RailEdgeTest {

  @Test
  void topologyEdgeDoesNotExposePersistentTrackFootprint() {
    NodeId first = NodeId.of("A");
    NodeId second = NodeId.of("B");
    EdgeId id = EdgeId.undirected(first, second);

    new RailEdge(id, first, second, 12, 8.0, true, Optional.empty());

    assertFalse(
        java.util.Arrays.stream(RailEdge.class.getRecordComponents())
            .anyMatch(component -> component.getName().equals("footprint")));
  }
}
