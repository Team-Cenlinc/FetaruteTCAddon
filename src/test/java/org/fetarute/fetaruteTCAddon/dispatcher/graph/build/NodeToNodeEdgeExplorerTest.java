package org.fetarute.fetaruteTCAddon.dispatcher.graph.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.Map;
import org.bukkit.World;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.junit.jupiter.api.Test;

final class NodeToNodeEdgeExplorerTest {

  @Test
  void anyUnresolvedTraversalEvidencePermanentlyDowngradesPhysicalCoverage() {
    NodeToNodeEdgeExplorer explorer =
        new NodeToNodeEdgeExplorer(mock(World.class), Map.of(), message -> {});
    assertTrue(explorer.hasCompleteFootprintEvidence());

    explorer.markFootprintEvidenceIncomplete("invalid-anchor-rail:A");

    assertFalse(explorer.hasCompleteFootprintEvidence());
  }

  @Test
  void maxDistanceDirectionIsTreatedAsDeadEndWithoutDowngradingCoverage() {
    NodeToNodeEdgeExplorer explorer =
        new NodeToNodeEdgeExplorer(mock(World.class), Map.of(), message -> {});

    explorer.recordUnterminatedDirection(NodeId.of("SURC:S:CHT:3"));
    explorer.recordUnterminatedDirection(NodeId.of("SWITCHER:Towny:-141:90:367"));
    explorer.recordUnterminatedDirection(NodeId.of("SURC:S:CHT:3"));

    assertTrue(explorer.hasCompleteFootprintEvidence());
    assertEquals(
        List.of("SURC:S:CHT:3", "SWITCHER:Towny:-141:90:367"), explorer.unterminatedStartNodes());
  }
}
