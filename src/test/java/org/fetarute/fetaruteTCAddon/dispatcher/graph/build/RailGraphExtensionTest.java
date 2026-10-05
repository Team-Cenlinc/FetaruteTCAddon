package org.fetarute.fetaruteTCAddon.dispatcher.graph.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.util.Vector;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.ExploredRailEdge;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailEdge;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphInterlockingSupport;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphMerger;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.SignRailNode;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.SimpleRailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.explore.RailBlockPos;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.interlocking.RailEdgeFootprint;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.interlocking.RailFootprintCell;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.interlocking.RailInterlockingState;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.persist.RailNodeRecord;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyResource;
import org.junit.jupiter.api.Test;

final class RailGraphExtensionTest {

  private static final UUID WORLD = UUID.randomUUID();

  @Test
  void planReturnsNodesTheGraphDoesNotHaveYet() {
    RailGraph served = graph(Map.of(edge("A", "B"), cells(1, 2)));

    RailGraphExtension.Planned planned =
        RailGraphExtension.plan(served, List.of(record("A", 1), record("B", 2), record("C", 20)));

    assertTrue(planned.refusal().isEmpty());
    assertEquals(
        List.of(NodeId.of("C")), planned.newNodes().stream().map(RailNodeRecord::nodeId).toList());
  }

  @Test
  void planRefusesRemovedMovedOrNothingNew() {
    RailGraph served = graph(Map.of(edge("A", "B"), cells(1, 2)));

    assertTrue(
        RailGraphExtension.plan(served, List.of(record("A", 1)))
            .refusal()
            .orElseThrow()
            .contains("拆除"));
    assertTrue(
        RailGraphExtension.plan(served, List.of(record("A", 1), record("B", 9)))
            .refusal()
            .orElseThrow()
            .contains("换了位置"));
    assertTrue(
        RailGraphExtension.plan(served, List.of(record("A", 1), record("B", 2)))
            .refusal()
            .orElseThrow()
            .contains("没有需要增补"));
  }

  @Test
  void anchorOnAnExploredEdgeIsRefused() {
    RailInterlockingState state = stateOf(graph(Map.of(edge("A", "B"), cells(1, 2))));

    Optional<String> refusal =
        RailGraphExtension.checkUnexplored(state, Map.of(NodeId.of("C"), Set.of(pos(2))));

    assertTrue(refusal.orElseThrow().contains("A ↔ B"), refusal.toString());
    assertTrue(
        RailGraphExtension.checkUnexplored(state, Map.of(NodeId.of("C"), Set.of(pos(30))))
            .isEmpty());
  }

  @Test
  void withoutCompleteFootprintsNothingCanBeExtended() {
    RailInterlockingState state =
        stateOf(graph(Map.of(edge("A", "B"), cells(1, 2)))).withCoverageMarkedIncomplete();

    assertTrue(
        RailGraphExtension.checkUnexplored(state, Map.of(NodeId.of("C"), Set.of(pos(30))))
            .orElseThrow()
            .contains("完整的区间足迹"));
  }

  @Test
  void lookupFindsNewAnchorsExistingAnchorsAndFlagsMidEdgeCells() {
    RailGraph served = graph(Map.of(edge("A", "B"), cells(0, 1, 2, 3)));
    AtomicInteger resolved = new AtomicInteger();
    RailGraphExtension.AnchorLookup lookup =
        new RailGraphExtension.AnchorLookup(
            Map.of(pos(20), NodeId.of("C")),
            served.nodes(),
            node -> {
              resolved.incrementAndGet();
              int x = node.worldPosition().getBlockX();
              return Set.of(pos(x));
            },
            stateOf(served),
            6);

    assertEquals(NodeId.of("C"), lookup.get(pos(20)));
    assertEquals(NodeId.of("B"), lookup.get(pos(3)));
    int afterFirstLookup = resolved.get();
    assertEquals(NodeId.of("B"), lookup.get(pos(3)));
    assertEquals(afterFirstLookup, resolved.get(), "附近旧节点的锚点只解析一次，之后走缓存");
    assertTrue(lookup.get(pos(2)).value().startsWith(RailGraphExtension.MID_EDGE_PREFIX));
    assertNull(lookup.get(pos(40)));
  }

  @Test
  void verifyRefusesMidEdgeIncompleteEvidenceAndUnmarkedJunctions() {
    Map<NodeId, RailNode> nodes = nodesOf("B", "C");
    EdgeId mid =
        EdgeId.undirected(NodeId.of("C"), NodeId.of(RailGraphExtension.MID_EDGE_PREFIX + "2"));
    assertTrue(
        RailGraphExtension.verify(Map.of(mid, explored(5, 6)), true, pos -> false, nodes)
            .refusal()
            .orElseThrow()
            .contains("接进了已有区间中间"));

    Map<EdgeId, ExploredRailEdge> good = Map.of(edgeId("B", "C"), explored(3, 4, 5));
    assertTrue(
        RailGraphExtension.verify(good, false, pos -> false, nodes)
            .refusal()
            .orElseThrow()
            .contains("足迹不完整"));
    assertTrue(
        RailGraphExtension.verify(good, true, pos -> pos.x() == 4, nodes)
            .refusal()
            .orElseThrow()
            .contains("道岔"));

    RailGraphExtension.Verified verified =
        RailGraphExtension.verify(good, true, pos -> false, nodes);
    assertTrue(verified.refusal().isEmpty());
    assertEquals(List.of(edgeId("B", "C")), verified.edges().stream().map(RailEdge::id).toList());
    assertTrue(verified.edges().get(0).metadata().isPresent());
  }

  @Test
  void appendKeepsExactInterlockingAndOnlyTouchesOldEdgesTheNewOneOverlaps() {
    RailGraph served =
        graph(Map.of(edge("A", "B"), cells(0, 1, 2, 3), edge("X", "Y"), cells(50, 51)));
    RailEdge bc = edge("B", "C");
    RailGraph appended =
        RailGraphMerger.append(
            served, List.of(node("C", 20)), List.of(bc), Map.of(bc.id(), footprint(3, 4, 5, 20)));

    assertTrue(stateOf(appended).coverage().complete());
    assertEquals(3, appended.edges().size());
    Set<OccupancyResource> changed =
        RailGraphExtension.changedResources(served, appended, List.of(NodeId.of("C")));
    assertFalse(changed.contains(OccupancyResource.forEdge(edgeId("X", "Y"))), "别的分量不受影响");
  }

  @Test
  void appendRejectsExistingNodesOrEdges() {
    RailGraph served = graph(Map.of(edge("A", "B"), cells(0, 1)));
    RailEdge ab = edge("A", "B");

    assertThrows(
        IllegalArgumentException.class,
        () -> RailGraphMerger.append(served, List.of(node("A", 0)), List.of(), Map.of()));
    assertThrows(
        IllegalArgumentException.class,
        () -> RailGraphMerger.append(served, List.of(), List.of(ab), Map.of()));
  }

  @Test
  void crossingAnOldEdgeChangesItsResources() {
    RailGraph served =
        graph(Map.of(edge("A", "B"), cells(0, 1, 2, 3), edge("P", "Q"), cells(100, 101)));
    RailEdge cd = edge("C", "D");
    RailGraph appended =
        RailGraphMerger.append(
            served,
            List.of(node("C", 30), node("D", 31)),
            List.of(cd),
            Map.of(cd.id(), footprint(1, 30, 31)));

    Set<OccupancyResource> changed =
        RailGraphExtension.changedResources(served, appended, List.of(NodeId.of("C")));

    assertTrue(
        changed.contains(OccupancyResource.forEdge(edgeId("A", "B"))),
        "新区间压过 A-B 产生联锁区，A-B 的旧资源都要核验");
  }

  @Test
  void componentKeyChangeOnACautionedComponentIsReported() {
    RailGraph served = graph(Map.of(edge("M", "N"), cells(0, 1)));
    RailEdge an = edge("A", "N");
    RailGraph appended =
        RailGraphMerger.append(
            served, List.of(node("A", 9)), List.of(an), Map.of(an.id(), footprint(1, 9)));

    assertEquals(
        Set.of("M"),
        RailGraphExtension.componentKeysLosingCautions(
            served, appended, List.of(NodeId.of("A")), Set.of("M")));
    assertTrue(
        RailGraphExtension.componentKeysLosingCautions(
                served, appended, List.of(NodeId.of("A")), Set.of())
            .isEmpty());
  }

  private static RailGraph graph(Map<RailEdge, Set<RailFootprintCell>> edges) {
    Map<NodeId, RailNode> nodes = new HashMap<>();
    Map<EdgeId, RailEdge> edgesById = new HashMap<>();
    Map<EdgeId, RailEdgeFootprint> footprints = new HashMap<>();
    edges.forEach(
        (edge, edgeCells) -> {
          int min = edgeCells.stream().mapToInt(RailFootprintCell::x).min().orElse(0);
          int max = edgeCells.stream().mapToInt(RailFootprintCell::x).max().orElse(0);
          nodes.putIfAbsent(edge.from(), node(edge.from().value(), min));
          nodes.putIfAbsent(edge.to(), node(edge.to().value(), max));
          edgesById.put(edge.id(), edge);
          footprints.put(edge.id(), new RailEdgeFootprint(1, true, edgeCells));
        });
    return new SimpleRailGraph(
        nodes,
        edgesById,
        Set.of(),
        RailInterlockingState.from(WORLD, edgesById.keySet(), footprints));
  }

  private static RailInterlockingState stateOf(RailGraph graph) {
    return ((RailGraphInterlockingSupport) graph).interlockingState();
  }

  private static Map<NodeId, RailNode> nodesOf(String... ids) {
    Map<NodeId, RailNode> nodes = new HashMap<>();
    for (int i = 0; i < ids.length; i++) {
      nodes.put(NodeId.of(ids[i]), node(ids[i], i * 10));
    }
    return nodes;
  }

  private static SignRailNode node(String id, int x) {
    return new SignRailNode(
        NodeId.of(id), NodeType.WAYPOINT, new Vector(x, 64, 0), Optional.empty(), Optional.empty());
  }

  private static RailNodeRecord record(String id, int x) {
    return new RailNodeRecord(
        WORLD, NodeId.of(id), NodeType.WAYPOINT, x, 64, 0, Optional.empty(), Optional.empty());
  }

  private static RailEdge edge(String a, String b) {
    EdgeId id = edgeId(a, b);
    return new RailEdge(id, id.a(), id.b(), 10, 0.0, true, Optional.empty());
  }

  private static EdgeId edgeId(String a, String b) {
    return EdgeId.undirected(NodeId.of(a), NodeId.of(b));
  }

  private static Set<RailFootprintCell> cells(int... xs) {
    Set<RailFootprintCell> out = new java.util.HashSet<>();
    for (int x : xs) {
      out.add(new RailFootprintCell(x, 64, 0));
    }
    return out;
  }

  private static RailEdgeFootprint footprint(int... xs) {
    return new RailEdgeFootprint(1, true, cells(xs));
  }

  private static ExploredRailEdge explored(int... xs) {
    return new ExploredRailEdge(10, footprint(xs));
  }

  private static RailBlockPos pos(int x) {
    return new RailBlockPos(x, 64, 0);
  }
}
