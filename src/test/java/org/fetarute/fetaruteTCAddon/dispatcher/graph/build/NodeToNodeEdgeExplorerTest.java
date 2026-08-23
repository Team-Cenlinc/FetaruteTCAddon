package org.fetarute.fetaruteTCAddon.dispatcher.graph.build;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.util.Map;
import org.bukkit.World;
import org.junit.jupiter.api.Test;

final class NodeToNodeEdgeExplorerTest {

  @Test
  void anyUnresolvedTraversalEvidencePermanentlyDowngradesPhysicalCoverage() {
    NodeToNodeEdgeExplorer explorer =
        new NodeToNodeEdgeExplorer(mock(World.class), Map.of(), message -> {});
    assertTrue(explorer.hasCompleteFootprintEvidence());

    explorer.markFootprintEvidenceIncomplete("max-distance:A");

    assertFalse(explorer.hasCompleteFootprintEvidence());
  }
}
