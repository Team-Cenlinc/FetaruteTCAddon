package org.fetarute.fetaruteTCAddon.dispatcher.eta;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.bukkit.util.Vector;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.model.PathProgressModel;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailEdge;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.SignRailNode;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.SimpleRailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteId;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteLifecycleMode;
import org.junit.jupiter.api.Test;

public class PathProgressModelTest {

  @Test
  void remainingToNode_expandsShortestPath_betweenWaypoints() {
    NodeId a = new NodeId("A");
    NodeId b = new NodeId("B");
    NodeId x = new NodeId("X");
    NodeId c = new NodeId("C");

    Map<NodeId, RailNode> nodes = new HashMap<>();
    nodes.put(
        a,
        new SignRailNode(
            a, NodeType.WAYPOINT, new Vector(0, 0, 0), Optional.empty(), Optional.empty()));
    nodes.put(
        x,
        new SignRailNode(
            x, NodeType.WAYPOINT, new Vector(0, 0, 0), Optional.empty(), Optional.empty()));
    nodes.put(
        b,
        new SignRailNode(
            b, NodeType.WAYPOINT, new Vector(0, 0, 0), Optional.empty(), Optional.empty()));
    nodes.put(
        c,
        new SignRailNode(
            c, NodeType.WAYPOINT, new Vector(0, 0, 0), Optional.empty(), Optional.empty()));

    Map<EdgeId, RailEdge> edges = new HashMap<>();
    edges.put(
        EdgeId.undirected(a, x),
        new RailEdge(EdgeId.undirected(a, x), a, x, 10, 6.0, true, Optional.empty()));
    edges.put(
        EdgeId.undirected(x, b),
        new RailEdge(EdgeId.undirected(x, b), x, b, 10, 6.0, true, Optional.empty()));
    edges.put(
        EdgeId.undirected(b, c),
        new RailEdge(EdgeId.undirected(b, c), b, c, 10, 6.0, true, Optional.empty()));

    SimpleRailGraph graph = new SimpleRailGraph(nodes, edges, Set.of());

    RouteDefinition route =
        new RouteDefinition(
            RouteId.of("SURN:BS:EXP-01"),
            List.of(a, b, c),
            Optional.empty(),
            RouteLifecycleMode.DESTROY_AFTER_TERM);

    PathProgressModel model = new PathProgressModel();
    Optional<PathProgressModel.PathProgress> opt = model.remainingToNode(graph, route, 0, c);

    assertTrue(opt.isPresent());
    assertEquals(List.of(a, x, b, c), opt.get().remainingNodes());
    assertEquals(3, opt.get().remainingEdgeCount());
  }
}
