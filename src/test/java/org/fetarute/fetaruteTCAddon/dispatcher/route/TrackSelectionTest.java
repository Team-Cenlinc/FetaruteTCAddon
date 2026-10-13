package org.fetarute.fetaruteTCAddon.dispatcher.route;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.bukkit.util.Vector;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.company.model.RouteStopPassType;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.SignRailNode;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.SimpleRailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode;
import org.junit.jupiter.api.Test;

/** 集合语义、数值边界与选台预算回归；声明范围不会被展开成数值空间。 */
class TrackSelectionTest {
  @Test
  void equivalentUnionsHaveOneSemanticValue() {
    var first = TrackSelection.parse("[1,2,3-5,5,9:7]").orElseThrow();
    var second = TrackSelection.parse("[7-9,5:1]").orElseThrow();
    assertEquals(first, second);
    assertEquals(first.hashCode(), second.hashCode());
    assertEquals(Optional.of("[1-5,7-9]"), first.formatSelector());
    assertFalse(first.contains(6));
    assertEquals(8, first.cardinality().orElseThrow());
  }

  @Test
  void maximumIntegerAndHugeRangesRemainBounded() {
    var maximum = TrackSelection.parse("[2147483647]").orElseThrow();
    assertEquals(List.of(Integer.MAX_VALUE), maximum.firstTracks(20));
    var huge = TrackSelection.parse("[1:2147483647]").orElseThrow();
    assertEquals(Integer.MAX_VALUE, huge.cardinality().orElseThrow());
    assertEquals(IntStream.rangeClosed(2, 21).boxed().toList(), huge.missingTracks(List.of(1), 20));
  }

  @Test
  void candidatesUseActualNodesAndApplyTheBudgetAfterFiltering() {
    Map<NodeId, RailNode> nodes =
        IntStream.rangeClosed(101, 130)
            .boxed()
            .collect(
                Collectors.toMap(
                    track -> NodeId.of("OP:S:DEST:" + track),
                    track ->
                        new SignRailNode(
                            NodeId.of("OP:S:DEST:" + track),
                            NodeType.STATION,
                            new Vector(track, 0, 0),
                            Optional.empty(),
                            Optional.empty())));
    var graph = org.mockito.Mockito.spy(new SimpleRailGraph(nodes, Map.of(), Set.of()));
    var spec =
        DynamicStopMatcher.parseDynamicSpec("DYNAMIC:OP:S:DEST:[1:2147483647]").orElseThrow();
    assertEquals(
        IntStream.rangeClosed(101, 120).mapToObj(track -> NodeId.of("OP:S:DEST:" + track)).toList(),
        DynamicStopMatcher.candidateNodes(spec, graph));
    assertTrue(DynamicStopMatcher.matches(NodeId.of("OP:S:DEST:130"), spec), "预算不改变资格");
    assertEquals(30, DynamicStopMatcher.matchingNodes(spec, nodes.keySet()).size());
    java.util.List<String> diagnostic = new java.util.ArrayList<>();
    DynamicStopMatcher.candidateNodes(spec, graph, diagnostic::add);
    assertTrue(diagnostic.get(0).contains("DYNAMIC_CANDIDATE_BUDGET"));
    org.mockito.Mockito.verify(graph, org.mockito.Mockito.never()).nodes();
  }

  @Test
  void definitionEvidenceNormalizesOnlyDynamicSyntax() {
    UUID route = UUID.randomUUID();
    var first = stop(route, "DYNAMIC:OP:S:DEST:[1,3,5]\nCHANGE:OP:L1");
    var same = stop(route, "DYNAMIC:op:s:dest:[5,3,1,1]\nCHANGE:OP:L1");
    var changed = stop(route, "DYNAMIC:OP:S:DEST:[1,3,5]\nCHANGE:OP:L2");
    assertTrue(DynamicStopMatcher.sameDefinition(Optional.of(first), Optional.of(same)));
    assertFalse(DynamicStopMatcher.sameDefinition(Optional.of(first), Optional.of(changed)));
    assertEquals(
        DynamicStopMatcher.parseDynamicSpec(first), DynamicStopMatcher.parseDynamicSpec(same));
  }

  private static RouteStop stop(UUID route, String notes) {
    return new RouteStop(
        route,
        1,
        Optional.empty(),
        Optional.of("OP:S:DEST:1"),
        Optional.of(20),
        RouteStopPassType.STOP,
        Optional.of(notes));
  }
}
