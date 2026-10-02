package org.fetarute.fetaruteTCAddon.dispatcher.graph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.Set;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.interlocking.RailFootprintCell;
import org.junit.jupiter.api.Test;

final class ExploredRailEdgeAccumulatorTest {

  @Test
  void oneUncapturedCandidateMakesMergedPhysicalFootprintIncomplete() {
    RailFootprintCell captured = new RailFootprintCell(1, 64, 1);
    RailFootprintCell partial = new RailFootprintCell(2, 64, 2);
    ExploredRailEdgeAccumulator accumulator = new ExploredRailEdgeAccumulator();
    accumulator.recordCandidate(12, Set.of(captured), true);
    accumulator.recordCandidate(18, Set.of(partial), false);

    ExploredRailEdge result = accumulator.snapshot(true);

    assertEquals(12, result.lengthBlocks());
    assertEquals(Set.of(captured, partial), result.footprint().cells());
    assertFalse(result.footprint().complete());
  }

  @Test
  void incompleteTraversalEvidenceCannotPublishCapturedCandidateAsExact() {
    ExploredRailEdgeAccumulator accumulator = new ExploredRailEdgeAccumulator();
    accumulator.recordCandidate(12, Set.of(new RailFootprintCell(1, 64, 1)), true);

    assertFalse(accumulator.snapshot(false).footprint().complete());
  }
}
