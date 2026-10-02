package org.fetarute.fetaruteTCAddon.dispatcher.graph.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.ExploredRailEdge;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphInterlockingSupport;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.interlocking.RailEdgeFootprint;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.interlocking.RailFootprintCell;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.persist.RailNodeRecord;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.junit.jupiter.api.Test;

class RailGraphBuildJobTest {

  @Test
  void assembledGraphImmediatelyCarriesCapturedWorldInterlockingState() {
    UUID worldId = UUID.fromString("11111111-2222-3333-4444-555555555555");
    NodeId a = NodeId.of("A");
    NodeId b = NodeId.of("B");
    EdgeId edgeId = EdgeId.undirected(a, b);
    RailEdgeFootprint footprint =
        new RailEdgeFootprint(
            RailEdgeFootprint.CURRENT_FORMAT_VERSION, true, Set.of(new RailFootprintCell(1, 2, 3)));

    RailGraph graph =
        RailGraphBuildJob.buildGraph(
            worldId,
            List.of(node(worldId, a, 0), node(worldId, b, 2)),
            Map.of(edgeId, new ExploredRailEdge(2, footprint)));

    RailGraphInterlockingSupport support =
        assertInstanceOf(RailGraphInterlockingSupport.class, graph);
    assertTrue(support.interlockingState().available());
    assertTrue(support.interlockingState().coverage().complete());
    assertEquals(0, support.interlockingState().indexedZoneCellCount());
  }

  @Test
  void partialWorldDiscoveryCannotPublishCompleteEdgeFootprints() {
    EdgeId edgeId = EdgeId.undirected(NodeId.of("A"), NodeId.of("B"));
    ExploredRailEdge captured =
        new ExploredRailEdge(
            2,
            new RailEdgeFootprint(
                RailEdgeFootprint.CURRENT_FORMAT_VERSION,
                true,
                Set.of(new RailFootprintCell(1, 2, 3))));

    ExploredRailEdge published =
        RailGraphBuildJob.applyBuildCompletion(
                Map.of(edgeId, captured), RailGraphBuildCompletion.PARTIAL_UNLOADED_CHUNKS)
            .get(edgeId);

    assertEquals(captured.footprint().cells(), published.footprint().cells());
    assertFalse(published.footprint().complete());
  }

  @Test
  void unresolvedNodeAnchorCannotPublishCompleteEdgeFootprints() {
    EdgeId edgeId = EdgeId.undirected(NodeId.of("A"), NodeId.of("B"));
    ExploredRailEdge captured =
        new ExploredRailEdge(
            2,
            new RailEdgeFootprint(
                RailEdgeFootprint.CURRENT_FORMAT_VERSION,
                true,
                Set.of(new RailFootprintCell(1, 2, 3))));

    ExploredRailEdge published =
        RailGraphBuildJob.applyBuildCompletion(
                Map.of(edgeId, captured), RailGraphBuildCompletion.COMPLETE, false)
            .get(edgeId);

    assertFalse(published.footprint().complete());
  }

  private static RailNodeRecord node(UUID worldId, NodeId nodeId, int x) {
    return new RailNodeRecord(
        worldId, nodeId, NodeType.WAYPOINT, x, 0, 0, Optional.empty(), Optional.empty());
  }
}
