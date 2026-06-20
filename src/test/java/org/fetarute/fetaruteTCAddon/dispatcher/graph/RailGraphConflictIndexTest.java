package org.fetarute.fetaruteTCAddon.dispatcher.graph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.bukkit.util.Vector;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode;
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

  @Test
  void sectionIndexMergesAcrossSwitcherWithoutChangingCorridorSplit() {
    NodeId nodeA = NodeId.of("A");
    NodeId nodeS = NodeId.of("S");
    NodeId nodeB = NodeId.of("B");
    SignRailNode a = node(nodeA, NodeType.WAYPOINT, 0.0);
    SignRailNode s = node(nodeS, NodeType.SWITCHER, 1.0);
    SignRailNode b = node(nodeB, NodeType.WAYPOINT, 2.0);
    EdgeId edgeAS = EdgeId.undirected(nodeA, nodeS);
    EdgeId edgeSB = EdgeId.undirected(nodeS, nodeB);
    RailEdge as = edge(edgeAS, nodeA, nodeS);
    RailEdge sb = edge(edgeSB, nodeS, nodeB);
    SimpleRailGraph graph =
        new SimpleRailGraph(
            Map.of(nodeA, a, nodeS, s, nodeB, b), Map.of(edgeAS, as, edgeSB, sb), Set.of());

    RailGraphConflictIndex conflictIndex = RailGraphConflictIndex.fromGraph(graph);
    String corridorAS = conflictIndex.conflictKeyForEdge(edgeAS).orElseThrow();
    String corridorSB = conflictIndex.conflictKeyForEdge(edgeSB).orElseThrow();
    SingleLineSectionIndex sectionIndex = SingleLineSectionIndex.fromGraph(graph);
    SingleLineSectionInfo sectionAS = sectionIndex.sectionInfoForEdge(edgeAS).orElseThrow();
    SingleLineSectionInfo sectionSB = sectionIndex.sectionInfoForEdge(edgeSB).orElseThrow();

    assertNotEquals(corridorAS, corridorSB);
    assertEquals(sectionAS.key(), sectionSB.key());
    assertTrue(sectionAS.key().startsWith("single:section:"));
    assertTrue(sectionAS.corridorKeys().contains(corridorAS));
    assertTrue(sectionAS.corridorKeys().contains(corridorSB));
  }

  @Test
  void sectionIndexDoesNotMergeOffAxisBranchIntoMainLineSection() {
    NodeId nodeWsd2 = NodeId.of("SURC:S:WSD:2");
    NodeId nodeWsdMid = NodeId.of("SURC:SPB:WSD:2:001");
    NodeId nodeS = NodeId.of("SWITCHER:Towny:-566:77:1179");
    NodeId nodeWsd3Mid = NodeId.of("SURC:SPB:WSD:3:001");
    NodeId nodeWsd3 = NodeId.of("SURC:S:WSD:3");
    NodeId nodeJbs1 = NodeId.of("SURC:S:JBS:1");
    NodeId nodeJbsMid = NodeId.of("SURC:SPB:JBS:1:002");
    NodeId nodeJbs2 = NodeId.of("SURC:S:JBS:2");

    Map<NodeId, RailNode> nodes =
        Map.of(
            nodeWsd2, node(nodeWsd2, NodeType.STATION, 0.0),
            nodeWsdMid, node(nodeWsdMid, NodeType.WAYPOINT, 1.0),
            nodeS, node(nodeS, NodeType.SWITCHER, 2.0),
            nodeWsd3Mid, node(nodeWsd3Mid, NodeType.WAYPOINT, 3.0),
            nodeWsd3, node(nodeWsd3, NodeType.STATION, 4.0),
            nodeJbs1, node(nodeJbs1, NodeType.STATION, 10.0),
            nodeJbsMid, node(nodeJbsMid, NodeType.WAYPOINT, 11.0),
            nodeJbs2, node(nodeJbs2, NodeType.STATION, 12.0));
    EdgeId edgeWsdEntry = EdgeId.undirected(nodeWsd2, nodeWsdMid);
    EdgeId edgeWsdLeft = EdgeId.undirected(nodeWsdMid, nodeS);
    EdgeId edgeWsdRight = EdgeId.undirected(nodeS, nodeWsd3Mid);
    EdgeId edgeWsdExit = EdgeId.undirected(nodeWsd3Mid, nodeWsd3);
    EdgeId edgeJbsEntry = EdgeId.undirected(nodeJbs1, nodeJbsMid);
    EdgeId edgeJbsLeft = EdgeId.undirected(nodeJbsMid, nodeS);
    EdgeId edgeJbsRight = EdgeId.undirected(nodeS, nodeJbs2);
    Map<EdgeId, RailEdge> edges =
        Map.of(
            edgeWsdEntry, edge(edgeWsdEntry, nodeWsd2, nodeWsdMid, 100),
            edgeWsdLeft, edge(edgeWsdLeft, nodeWsdMid, nodeS, 100),
            edgeWsdRight, edge(edgeWsdRight, nodeS, nodeWsd3Mid, 100),
            edgeWsdExit, edge(edgeWsdExit, nodeWsd3Mid, nodeWsd3, 100),
            edgeJbsEntry, edge(edgeJbsEntry, nodeJbs1, nodeJbsMid, 10),
            edgeJbsLeft, edge(edgeJbsLeft, nodeJbsMid, nodeS, 10),
            edgeJbsRight, edge(edgeJbsRight, nodeS, nodeJbs2, 10));
    SimpleRailGraph graph = new SimpleRailGraph(nodes, edges, Set.of());

    SingleLineSectionIndex sectionIndex = SingleLineSectionIndex.fromGraph(graph);
    SingleLineSectionInfo wsdSection = sectionIndex.sectionInfoForEdge(edgeWsdLeft).orElseThrow();
    SingleLineSectionInfo jbsSection = sectionIndex.sectionInfoForEdge(edgeJbsLeft).orElseThrow();

    assertEquals(
        wsdSection.key(), sectionIndex.sectionInfoForEdge(edgeWsdRight).orElseThrow().key());
    assertEquals(
        jbsSection.key(), sectionIndex.sectionInfoForEdge(edgeJbsRight).orElseThrow().key());
    assertNotEquals(wsdSection.key(), jbsSection.key());
    assertSectionMembersStayOnAxis(sectionIndex);
  }

  @Test
  void sectionIndexKeepsMainLineAcrossSwitcherWithDeadEndStub() {
    NodeId nodeA = NodeId.of("A");
    NodeId nodeS = NodeId.of("S");
    NodeId nodeB = NodeId.of("B");
    NodeId nodeC = NodeId.of("C");
    SignRailNode a = node(nodeA, NodeType.WAYPOINT, 0.0);
    SignRailNode s = node(nodeS, NodeType.SWITCHER, 1.0);
    SignRailNode b = node(nodeB, NodeType.WAYPOINT, 2.0);
    SignRailNode c = node(nodeC, NodeType.WAYPOINT, 1.0);
    EdgeId edgeAS = EdgeId.undirected(nodeA, nodeS);
    EdgeId edgeSB = EdgeId.undirected(nodeS, nodeB);
    EdgeId edgeSC = EdgeId.undirected(nodeS, nodeC);
    RailEdge as = edge(edgeAS, nodeA, nodeS);
    RailEdge sb = edge(edgeSB, nodeS, nodeB);
    RailEdge sc = edge(edgeSC, nodeS, nodeC);
    SimpleRailGraph graph =
        new SimpleRailGraph(
            Map.of(nodeA, a, nodeS, s, nodeB, b, nodeC, c),
            Map.of(edgeAS, as, edgeSB, sb, edgeSC, sc),
            Set.of());

    SingleLineSectionIndex sectionIndex = SingleLineSectionIndex.fromGraph(graph);
    String sectionAS = sectionIndex.sectionInfoForEdge(edgeAS).orElseThrow().key();
    String sectionSB = sectionIndex.sectionInfoForEdge(edgeSB).orElseThrow().key();

    assertEquals(sectionAS, sectionSB);
    assertTrue(sectionIndex.sectionInfoForEdge(edgeSC).isEmpty());
    assertSectionMembersStayOnAxis(sectionIndex);
  }

  @Test
  void debugSqliteSectionMembersStayOnAxis() throws Exception {
    Path database = Path.of("test/data/debug/fetarute.sqlite");
    assumeTrue(Files.exists(database), "debug sqlite fixture is not available");

    try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database)) {
      try (PreparedStatement worlds =
              connection.prepareStatement("SELECT DISTINCT world_id FROM fta_rail_nodes");
          ResultSet worldRows = worlds.executeQuery()) {
        while (worldRows.next()) {
          String worldId = worldRows.getString("world_id");
          SimpleRailGraph graph = graphFromDebugSqlite(connection, worldId);
          SingleLineSectionIndex sectionIndex = SingleLineSectionIndex.fromGraph(graph);
          assertSectionMembersStayOnAxis(sectionIndex);

          EdgeId jbsBranch =
              EdgeId.undirected(
                  NodeId.of("SWITCHER:Towny:-566:77:1179"), NodeId.of("SURC:SPB:JBS:1:002"));
          sectionIndex
              .sectionInfoForEdge(jbsBranch)
              .ifPresent(
                  section ->
                      assertFalse(
                          section.key().contains("SURC:S:WSD:2~SURC:S:WSD:3"),
                          "JBS branch must not be a member of the WSD section"));
        }
      }
    }
  }

  private static void assertSectionMembersStayOnAxis(SingleLineSectionIndex sectionIndex) {
    sectionIndex
        .snapshot()
        .forEach(
            (edgeId, section) -> {
              assertTrue(
                  section.directional(), "section snapshot should only expose directional axes");
              Set<NodeId> axisNodes = Set.copyOf(section.nodes());
              assertFalse(axisNodes.isEmpty());
              assertTrue(axisNodes.contains(edgeId.a()), edgeId + " missing a endpoint in axis");
              assertTrue(axisNodes.contains(edgeId.b()), edgeId + " missing b endpoint in axis");
            });
  }

  private static SimpleRailGraph graphFromDebugSqlite(Connection connection, String worldId)
      throws Exception {
    Map<NodeId, RailNode> nodes = new java.util.LinkedHashMap<>();
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT node_id,node_type,x,y,z FROM fta_rail_nodes WHERE world_id=?")) {
      statement.setString(1, worldId);
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          NodeId nodeId = NodeId.of(rows.getString("node_id"));
          NodeType type = NodeType.valueOf(rows.getString("node_type"));
          nodes.put(
              nodeId,
              new SignRailNode(
                  nodeId,
                  type,
                  new Vector(rows.getInt("x"), rows.getInt("y"), rows.getInt("z")),
                  Optional.empty(),
                  Optional.empty()));
        }
      }
    }

    Map<EdgeId, RailEdge> edges = new java.util.LinkedHashMap<>();
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT node_a,node_b,length_blocks,base_speed_limit,bidirectional "
                + "FROM fta_rail_edges WHERE world_id=?")) {
      statement.setString(1, worldId);
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          NodeId nodeA = NodeId.of(rows.getString("node_a"));
          NodeId nodeB = NodeId.of(rows.getString("node_b"));
          EdgeId edgeId = EdgeId.undirected(nodeA, nodeB);
          edges.put(
              edgeId,
              new RailEdge(
                  edgeId,
                  nodeA,
                  nodeB,
                  rows.getInt("length_blocks"),
                  rows.getDouble("base_speed_limit"),
                  rows.getInt("bidirectional") != 0,
                  Optional.empty()));
        }
      }
    }
    return new SimpleRailGraph(nodes, edges, Set.of());
  }

  private static SignRailNode node(NodeId id, NodeType type, double x) {
    return new SignRailNode(id, type, new Vector(x, 64.0, 0.0), Optional.empty(), Optional.empty());
  }

  private static RailEdge edge(EdgeId id, NodeId from, NodeId to) {
    return new RailEdge(id, from, to, 10, 8.0, true, Optional.empty());
  }

  private static RailEdge edge(EdgeId id, NodeId from, NodeId to, int lengthBlocks) {
    return new RailEdge(id, from, to, lengthBlocks, 8.0, true, Optional.empty());
  }
}
