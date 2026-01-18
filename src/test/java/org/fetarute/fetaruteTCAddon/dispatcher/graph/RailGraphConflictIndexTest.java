package org.fetarute.fetaruteTCAddon.dispatcher.graph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.bukkit.util.Vector;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.junit.jupiter.api.Test;

class RailGraphConflictIndexTest {

  @Test
  void corridorSharesSameConflictKey() {
    NodeId nodeA = NodeId.of("A");
    NodeId nodeB = NodeId.of("B");
    NodeId nodeC = NodeId.of("C");
    SignRailNode a =
        new SignRailNode(
            nodeA,
            NodeType.WAYPOINT,
            new Vector(0.0, 64.0, 0.0),
            Optional.empty(),
            Optional.empty());
    SignRailNode b =
        new SignRailNode(
            nodeB,
            NodeType.WAYPOINT,
            new Vector(1.0, 64.0, 0.0),
            Optional.empty(),
            Optional.empty());
    SignRailNode c =
        new SignRailNode(
            nodeC,
            NodeType.WAYPOINT,
            new Vector(2.0, 64.0, 0.0),
            Optional.empty(),
            Optional.empty());
    EdgeId edgeAB = EdgeId.undirected(nodeA, nodeB);
    EdgeId edgeBC = EdgeId.undirected(nodeB, nodeC);
    RailEdge ab = new RailEdge(edgeAB, nodeA, nodeB, 10, 8.0, true, Optional.empty());
    RailEdge bc = new RailEdge(edgeBC, nodeB, nodeC, 10, 8.0, true, Optional.empty());
    SimpleRailGraph graph =
        new SimpleRailGraph(
            Map.of(nodeA, a, nodeB, b, nodeC, c), Map.of(edgeAB, ab, edgeBC, bc), Set.of());

    RailGraphConflictIndex index = RailGraphConflictIndex.fromGraph(graph);
    String keyAB = index.conflictKeyForEdge(edgeAB).orElseThrow();
    String keyBC = index.conflictKeyForEdge(edgeBC).orElseThrow();

    assertEquals(keyAB, keyBC);
    assertEquals("single:A:A~C", keyAB);
  }

  @Test
  void switcherSplitsConflictCorridor() {
    NodeId nodeA = NodeId.of("A");
    NodeId nodeS = NodeId.of("S");
    NodeId nodeB = NodeId.of("B");
    SignRailNode a =
        new SignRailNode(
            nodeA,
            NodeType.WAYPOINT,
            new Vector(0.0, 64.0, 0.0),
            Optional.empty(),
            Optional.empty());
    SignRailNode s =
        new SignRailNode(
            nodeS,
            NodeType.SWITCHER,
            new Vector(1.0, 64.0, 0.0),
            Optional.empty(),
            Optional.empty());
    SignRailNode b =
        new SignRailNode(
            nodeB,
            NodeType.WAYPOINT,
            new Vector(2.0, 64.0, 0.0),
            Optional.empty(),
            Optional.empty());
    EdgeId edgeAS = EdgeId.undirected(nodeA, nodeS);
    EdgeId edgeSB = EdgeId.undirected(nodeS, nodeB);
    RailEdge as = new RailEdge(edgeAS, nodeA, nodeS, 10, 8.0, true, Optional.empty());
    RailEdge sb = new RailEdge(edgeSB, nodeS, nodeB, 10, 8.0, true, Optional.empty());
    SimpleRailGraph graph =
        new SimpleRailGraph(
            Map.of(nodeA, a, nodeS, s, nodeB, b), Map.of(edgeAS, as, edgeSB, sb), Set.of());

    RailGraphConflictIndex index = RailGraphConflictIndex.fromGraph(graph);
    String keyAS = index.conflictKeyForEdge(edgeAS).orElseThrow();
    String keySB = index.conflictKeyForEdge(edgeSB).orElseThrow();

    assertNotEquals(keyAS, keySB);
  }
}
