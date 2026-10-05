package org.fetarute.fetaruteTCAddon.dispatcher.graph.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.Map;
import org.bukkit.World;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.build.NodeToNodeEdgeExplorer.WalkerStep;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.explore.RailBlockPos;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.junit.jupiter.api.Test;

final class NodeToNodeEdgeExplorerTest {

  private static final NodeId START = NodeId.of("SURC:S:CHT:3");
  private static final NodeId OTHER = NodeId.of("SWITCHER:Towny:-141:90:367");
  private static final double LIMIT = 4096.0;

  @Test
  void anyUnresolvedTraversalEvidencePermanentlyDowngradesPhysicalCoverage() {
    NodeToNodeEdgeExplorer explorer =
        new NodeToNodeEdgeExplorer(mock(World.class), Map.of(), message -> {});
    assertTrue(explorer.hasCompleteFootprintEvidence());

    explorer.markFootprintEvidenceIncomplete("invalid-anchor-rail:A");

    assertFalse(explorer.hasCompleteFootprintEvidence());
  }

  @Test
  void walkerBeyondLimitWithoutNodeIsUnterminated() {
    assertEquals(
        WalkerStep.UNTERMINATED, NodeToNodeEdgeExplorer.classifyStep(START, null, 4096.4, LIMIT));
  }

  @Test
  void arrivalOnTheStepThatCrossesTheLimitIsStillAnEdge() {
    assertEquals(
        WalkerStep.ARRIVED, NodeToNodeEdgeExplorer.classifyStep(START, OTHER, 4096.4, LIMIT));
  }

  @Test
  void returningToOwnAnchorIsNotAnArrival() {
    assertEquals(
        WalkerStep.CONTINUE, NodeToNodeEdgeExplorer.classifyStep(START, START, 100.0, LIMIT));
    assertEquals(
        WalkerStep.UNTERMINATED, NodeToNodeEdgeExplorer.classifyStep(START, START, 4097.0, LIMIT));
  }

  @Test
  void walkerWithinLimitContinues() {
    assertEquals(
        WalkerStep.CONTINUE, NodeToNodeEdgeExplorer.classifyStep(START, null, LIMIT, LIMIT));
  }

  @Test
  void defaultLimitIsFarAboveOrdinarySectionLength() {
    assertEquals(4096, EdgeExploreMode.nodeToNode().maxDistanceBlocks());
  }

  @Test
  void unterminatedDirectionsKeepEachLegAndDoNotDowngradeCoverage() {
    NodeToNodeEdgeExplorer explorer =
        new NodeToNodeEdgeExplorer(mock(World.class), Map.of(), message -> {});
    UnterminatedDirection otherLeg =
        new UnterminatedDirection(OTHER, new RailBlockPos(-150, 90, 900));
    UnterminatedDirection startEast =
        new UnterminatedDirection(START, new RailBlockPos(10, 90, 190));
    UnterminatedDirection startWest =
        new UnterminatedDirection(START, new RailBlockPos(-400, 90, 190));

    explorer.recordUnterminatedDirection(otherLeg);
    explorer.recordUnterminatedDirection(startEast);
    explorer.recordUnterminatedDirection(startWest);
    explorer.recordUnterminatedDirection(startEast);

    assertTrue(explorer.hasCompleteFootprintEvidence());
    assertEquals(List.of(startWest, startEast, otherLeg), explorer.unterminatedDirections());
  }

  @Test
  void buildOutcomeDefaultsAndCopiesUnterminatedDirections() {
    RailGraphBuildResult result = mock(RailGraphBuildResult.class);
    RailGraphBuildOutcome withoutDirections =
        new RailGraphBuildOutcome(result, RailGraphBuildCompletion.COMPLETE, null, null);
    assertEquals(List.of(), withoutDirections.unterminatedDirections());

    List<UnterminatedDirection> directions =
        new java.util.ArrayList<>(
            List.of(new UnterminatedDirection(START, new RailBlockPos(0, 90, 0))));
    RailGraphBuildOutcome outcome =
        new RailGraphBuildOutcome(result, RailGraphBuildCompletion.COMPLETE, null, directions);
    directions.clear();
    assertEquals(1, outcome.unterminatedDirections().size());
  }
}
