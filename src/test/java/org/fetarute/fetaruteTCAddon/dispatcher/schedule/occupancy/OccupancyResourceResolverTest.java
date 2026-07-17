package org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.bukkit.util.Vector;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailEdge;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphConflictSupport;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphSectionSupport;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.SignRailNode;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.SimpleRailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.SingleLineSectionInfo;
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
    assertTrue(resources.contains(OccupancyResource.forEdge(edgeId)));
    assertTrue(
        resources.contains(
            OccupancyResource.forConflict(OccupancyResourceResolver.switcherConflictId(switcher))));
    String conflictKey =
        ((RailGraphConflictSupport) graph).conflictKeyForEdge(edgeId).orElseThrow();
    assertTrue(resources.contains(OccupancyResource.forConflict(conflictKey)));
    SingleLineSectionInfo section =
        ((RailGraphSectionSupport) graph).sectionInfoForEdge(edgeId).orElseThrow();
    assertTrue(resources.contains(OccupancyResource.forConflict(section.key())));
  }

  @Test
  void resourcesForLinearEdgeAddsSectionConflict() {
    NodeId nodeA = NodeId.of("A");
    NodeId switcherId = NodeId.of("S");
    NodeId nodeB = NodeId.of("B");
    RailNode a =
        new SignRailNode(
            nodeA,
            NodeType.WAYPOINT,
            new Vector(0.0, 64.0, 0.0),
            Optional.empty(),
            Optional.empty());
    RailNode switcher =
        new SignRailNode(
            switcherId,
            NodeType.SWITCHER,
            new Vector(1.0, 64.0, 0.0),
            Optional.empty(),
            Optional.empty());
    RailNode b =
        new SignRailNode(
            nodeB,
            NodeType.WAYPOINT,
            new Vector(2.0, 64.0, 0.0),
            Optional.empty(),
            Optional.empty());
    EdgeId edgeAS = EdgeId.undirected(nodeA, switcherId);
    EdgeId edgeSB = EdgeId.undirected(switcherId, nodeB);
    RailEdge as = new RailEdge(edgeAS, nodeA, switcherId, 20, 8.0, true, Optional.empty());
    RailEdge sb = new RailEdge(edgeSB, switcherId, nodeB, 20, 8.0, true, Optional.empty());
    SimpleRailGraph graph =
        new SimpleRailGraph(
            Map.of(nodeA, a, switcherId, switcher, nodeB, b),
            Map.of(edgeAS, as, edgeSB, sb),
            Set.of());

    List<OccupancyResource> resources = OccupancyResourceResolver.resourcesForEdge(graph, as);

    SingleLineSectionInfo section =
        ((RailGraphSectionSupport) graph).sectionInfoForEdge(edgeAS).orElseThrow();
    assertTrue(resources.contains(OccupancyResource.forConflict(section.key())));
  }

  @Test
  void disjointSelectedRoutesInSameMeshDoNotShareConflictResources() {
    NodeId switcherA = NodeId.of("SW-A");
    NodeId switcherB = NodeId.of("SW-B");
    NodeId switcherC = NodeId.of("SW-C");
    NodeId switcherD = NodeId.of("SW-D");
    RailNode nodeA =
        new SignRailNode(
            switcherA,
            NodeType.SWITCHER,
            new Vector(0.0, 64.0, 0.0),
            Optional.empty(),
            Optional.empty());
    RailNode nodeB =
        new SignRailNode(
            switcherB,
            NodeType.SWITCHER,
            new Vector(1.0, 64.0, 0.0),
            Optional.empty(),
            Optional.empty());
    RailNode nodeC =
        new SignRailNode(
            switcherC,
            NodeType.SWITCHER,
            new Vector(1.0, 64.0, 1.0),
            Optional.empty(),
            Optional.empty());
    RailNode nodeD =
        new SignRailNode(
            switcherD,
            NodeType.SWITCHER,
            new Vector(0.0, 64.0, 1.0),
            Optional.empty(),
            Optional.empty());
    EdgeId edgeAB = EdgeId.undirected(switcherA, switcherB);
    EdgeId edgeBC = EdgeId.undirected(switcherB, switcherC);
    EdgeId edgeCD = EdgeId.undirected(switcherC, switcherD);
    EdgeId edgeDA = EdgeId.undirected(switcherD, switcherA);
    RailEdge ab = new RailEdge(edgeAB, switcherA, switcherB, 10, 8.0, true, Optional.empty());
    RailEdge bc = new RailEdge(edgeBC, switcherB, switcherC, 10, 8.0, true, Optional.empty());
    RailEdge cd = new RailEdge(edgeCD, switcherC, switcherD, 10, 8.0, true, Optional.empty());
    RailEdge da = new RailEdge(edgeDA, switcherD, switcherA, 10, 8.0, true, Optional.empty());
    SimpleRailGraph graph =
        new SimpleRailGraph(
            Map.of(switcherA, nodeA, switcherB, nodeB, switcherC, nodeC, switcherD, nodeD),
            Map.of(edgeAB, ab, edgeBC, bc, edgeCD, cd, edgeDA, da),
            Set.of());

    Set<OccupancyResource> firstRouteConflicts = conflictResources(graph, ab);
    Set<OccupancyResource> disjointRouteConflicts = conflictResources(graph, cd);
    Set<OccupancyResource> shared = new HashSet<>(firstRouteConflicts);
    shared.retainAll(disjointRouteConflicts);

    assertTrue(shared.isEmpty(), () -> "不相交的选定进路不应共享冲突资源: " + shared);

    Set<OccupancyResource> mergingRouteConflicts = conflictResources(graph, bc);
    assertTrue(
        firstRouteConflicts.stream().anyMatch(mergingRouteConflicts::contains), "实际共享道岔的进路必须继续互斥");
  }

  @Test
  void parallelPassingLoopBranchesDoNotShareLegacySingleConflict() {
    NodeId switcherA = NodeId.of("LOOP-SW-A");
    NodeId upperA = NodeId.of("UPPER-A");
    NodeId upperB = NodeId.of("UPPER-B");
    NodeId lowerA = NodeId.of("LOWER-A");
    NodeId lowerB = NodeId.of("LOWER-B");
    NodeId switcherB = NodeId.of("LOOP-SW-B");
    Map<NodeId, RailNode> nodes =
        Map.of(
            switcherA, node(switcherA, NodeType.SWITCHER, 0.0),
            upperA, node(upperA, NodeType.WAYPOINT, 1.0),
            upperB, node(upperB, NodeType.WAYPOINT, 2.0),
            lowerA, node(lowerA, NodeType.WAYPOINT, 3.0),
            lowerB, node(lowerB, NodeType.WAYPOINT, 4.0),
            switcherB, node(switcherB, NodeType.SWITCHER, 5.0));
    RailEdge upperEntry = edge(switcherA, upperA);
    RailEdge upperMiddle = edge(upperA, upperB);
    RailEdge upperExit = edge(upperB, switcherB);
    RailEdge lowerEntry = edge(switcherA, lowerA);
    RailEdge lowerMiddle = edge(lowerA, lowerB);
    RailEdge lowerExit = edge(lowerB, switcherB);
    Map<EdgeId, RailEdge> edges =
        Map.of(
            upperEntry.id(), upperEntry,
            upperMiddle.id(), upperMiddle,
            upperExit.id(), upperExit,
            lowerEntry.id(), lowerEntry,
            lowerMiddle.id(), lowerMiddle,
            lowerExit.id(), lowerExit);
    SimpleRailGraph graph = new SimpleRailGraph(nodes, edges, Set.of());

    RailGraphConflictSupport conflictSupport = graph;
    assertEquals(
        conflictSupport.conflictKeyForEdge(upperMiddle.id()),
        conflictSupport.conflictKeyForEdge(lowerMiddle.id()),
        "旧 micro corridor 会把两条平行支路压成同一个 key，资源层必须过滤它");
    RailGraphSectionSupport sectionSupport = graph;
    assertTrue(sectionSupport.sectionInfoForEdge(upperMiddle.id()).isEmpty());
    assertTrue(sectionSupport.sectionInfoForEdge(lowerMiddle.id()).isEmpty());

    Set<OccupancyResource> shared = new HashSet<>(conflictResources(graph, upperMiddle));
    shared.retainAll(conflictResources(graph, lowerMiddle));

    assertTrue(shared.isEmpty(), () -> "平行会让支路不应共享 single 冲突资源: " + shared);
  }

  @Test
  void boundarylessCycleKeepsStrictSharedConflictResource() {
    NodeId nodeA = NodeId.of("CYCLE-A");
    NodeId nodeB = NodeId.of("CYCLE-B");
    NodeId nodeC = NodeId.of("CYCLE-C");
    NodeId nodeD = NodeId.of("CYCLE-D");
    RailEdge edgeAB = edge(nodeA, nodeB);
    RailEdge edgeBC = edge(nodeB, nodeC);
    RailEdge edgeCD = edge(nodeC, nodeD);
    RailEdge edgeDA = edge(nodeD, nodeA);
    SimpleRailGraph graph =
        new SimpleRailGraph(
            Map.of(
                nodeA, node(nodeA, NodeType.WAYPOINT, 0.0),
                nodeB, node(nodeB, NodeType.WAYPOINT, 1.0),
                nodeC, node(nodeC, NodeType.WAYPOINT, 2.0),
                nodeD, node(nodeD, NodeType.WAYPOINT, 3.0)),
            Map.of(
                edgeAB.id(), edgeAB,
                edgeBC.id(), edgeBC,
                edgeCD.id(), edgeCD,
                edgeDA.id(), edgeDA),
            Set.of());

    String cycleKey = graph.conflictKeyForEdge(edgeAB.id()).orElseThrow();
    assertTrue(cycleKey.contains(":cycle:"));
    assertTrue(conflictResources(graph, edgeAB).contains(OccupancyResource.forConflict(cycleKey)));
    assertTrue(
        conflictResources(graph, edgeCD).contains(OccupancyResource.forConflict(cycleKey)),
        "没有安全会让边界的纯闭环必须继续共享严格互斥资源");
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

  private static Set<OccupancyResource> conflictResources(RailGraph graph, RailEdge edge) {
    Set<OccupancyResource> resources = new HashSet<>();
    for (OccupancyResource resource : OccupancyResourceResolver.resourcesForEdge(graph, edge)) {
      if (resource.kind() == ResourceKind.CONFLICT) {
        resources.add(resource);
      }
    }
    return Set.copyOf(resources);
  }

  private static RailNode node(NodeId id, NodeType type, double x) {
    return new SignRailNode(id, type, new Vector(x, 64.0, 0.0), Optional.empty(), Optional.empty());
  }

  private static RailEdge edge(NodeId from, NodeId to) {
    EdgeId id = EdgeId.undirected(from, to);
    return new RailEdge(id, from, to, 10, 8.0, true, Optional.empty());
  }
}
