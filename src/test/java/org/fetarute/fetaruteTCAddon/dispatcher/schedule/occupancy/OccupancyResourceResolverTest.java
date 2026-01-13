package org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.bukkit.util.Vector;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailEdge;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.SignRailNode;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.SimpleRailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode;
import org.junit.jupiter.api.Test;

class OccupancyResourceResolverTest {

  @Test
  void resourcesForEdgeAddsSwitcherConflict() {
    NodeId switcherId = NodeId.of("SWITCH_A");
    RailNode switcher =
        new SignRailNode(
            switcherId,
            NodeType.SWITCHER,
            new Vector(0.0, 64.0, 0.0),
            Optional.empty(),
            Optional.empty());
    NodeId nodeId = NodeId.of("WAYPOINT_1");
    RailNode node =
        new SignRailNode(
            nodeId,
            NodeType.WAYPOINT,
            new Vector(1.0, 64.0, 1.0),
            Optional.empty(),
            Optional.empty());
    EdgeId edgeId = EdgeId.undirected(switcherId, nodeId);
    RailEdge edge = new RailEdge(edgeId, switcherId, nodeId, 20, 8.0, true, Optional.empty());
    SimpleRailGraph graph =
        new SimpleRailGraph(
            Map.of(switcherId, switcher, nodeId, node), Map.of(edgeId, edge), Set.of());

    List<OccupancyResource> resources = OccupancyResourceResolver.resourcesForEdge(graph, edge);
    assertEquals(2, resources.size());
    assertTrue(resources.contains(OccupancyResource.forEdge(edgeId)));
    assertTrue(
        resources.contains(
            OccupancyResource.forConflict(OccupancyResourceResolver.switcherConflictId(switcher))));
  }

  @Test
  void resourcesForNodeOnlyReturnsNodeWhenNotSwitcher() {
    RailNode station =
        new SignRailNode(
            NodeId.of("STATION_A"),
            NodeType.STATION,
            new Vector(2.0, 64.0, 2.0),
            Optional.empty(),
            Optional.empty());

    List<OccupancyResource> resources = OccupancyResourceResolver.resourcesForNode(station);
    assertEquals(1, resources.size());
    assertEquals(OccupancyResource.forNode(station.id()), resources.get(0));
  }
}
