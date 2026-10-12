package org.fetarute.fetaruteTCAddon.dispatcher.graph.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import com.bergerkiller.bukkit.tc.controller.components.RailPath;
import com.bergerkiller.bukkit.tc.controller.components.RailPiece;
import com.bergerkiller.bukkit.tc.controller.components.RailState;
import com.bergerkiller.bukkit.tc.utils.TrackWalkingPoint;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.util.Vector;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.build.NodeToNodeEdgeExplorer.WalkerStep;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.explore.RailBlockPos;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.interlocking.RailFootprintCell;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

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

  @Test
  void zeroLengthWarningNamesBothNodesAndAnchors() {
    String warning =
        NodeToNodeEdgeExplorer.zeroLengthWarning(
            NodeId.of("SWITCHER:Towny:-673:41:735"),
            new RailBlockPos(-673, 41, 735),
            NodeId.of("SURC:S:RVS:2"),
            null);
    assertTrue(warning.contains("SWITCHER:Towny:-673:41:735 (-673, 41, 735)"));
    assertTrue(warning.contains("SURC:S:RVS:2 (?)"));
  }

  @Test
  void adjacentRailsAreOneBlockApartEvenWhenWalkerStartsAtJunctionBoundary() throws Exception {
    assertZeroWalkerDistanceEdge(1, 0);
  }

  @Test
  void differentNodesSharingAnAnchorKeepTheirEdgeAndWarnOnce() throws Exception {
    assertZeroWalkerDistanceEdge(0, 1);
  }

  private static void assertZeroWalkerDistanceEdge(int targetX, int expectedWarnings)
      throws Exception {
    World world = mock(World.class);
    Block start = mock(Block.class);
    Block target = mock(Block.class);
    when(target.getX()).thenReturn(targetX);
    when(world.getBlockAt(0, 0, 0)).thenReturn(start);
    RailPiece piece = mock(RailPiece.class);
    when(piece.getJunctions()).thenReturn(List.of());
    RailState state = mock(RailState.class);
    when(state.clone()).thenReturn(state);
    when(state.position()).thenReturn(new RailPath.Position());
    when(state.railBlock()).thenReturn(start);
    RailPath path = RailPath.create(new Vector(0.2, 0.5, 0.5), new Vector(0.8, 0.5, 0.5));
    List<String> warnings = new ArrayList<>();
    NodeToNodeEdgeExplorer explorer =
        new NodeToNodeEdgeExplorer(
                world, Map.of(new RailBlockPos(targetX, 0, 0), OTHER), ignored -> {})
            .warnTo(warnings::add);
    explorer.addNode(START, Set.of(new RailBlockPos(0, 0, 0)));

    try (MockedStatic<RailPiece> pieces = mockStatic(RailPiece.class);
        MockedStatic<RailState> states = mockStatic(RailState.class);
        MockedConstruction<TrackWalkingPoint> walkers =
            mockConstruction(
                TrackWalkingPoint.class,
                (walker, context) -> {
                  // TC 的行走状态为公开 final 字段；测试替身不运行实际世界轨道初始化。
                  var stateField = TrackWalkingPoint.class.getField("state");
                  assertTrue(stateField.trySetAccessible());
                  stateField.set(walker, state);
                  walker.currentRailPath = path;
                  when(walker.moveFull())
                      .thenAnswer(
                          ignored -> {
                            when(state.railBlock()).thenReturn(target);
                            return true;
                          });
                })) {
      pieces.when(() -> RailPiece.create(start)).thenReturn(piece);
      states.when(() -> RailState.getSpawnState(piece)).thenReturn(state);
      explorer.step(Long.MAX_VALUE, 10);
    }

    var edge = explorer.getExploredEdges().get(EdgeId.undirected(START, OTHER));
    assertEquals(1, edge.lengthBlocks());
    assertEquals(
        targetX == 0
            ? Set.of(new RailFootprintCell(0, 0, 0))
            : Set.of(new RailFootprintCell(0, 0, 0), new RailFootprintCell(1, 0, 0)),
        edge.footprint().cells());
    assertTrue(edge.footprint().complete());
    assertEquals(expectedWarnings, warnings.size(), "相邻轨道不告警；真实共享锚点两个方向只告警一次");
  }
}
