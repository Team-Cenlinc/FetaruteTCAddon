package org.fetarute.fetaruteTCAddon.dispatcher.graph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.bukkit.util.Vector;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.control.EdgeOverrideRailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.network.RailNetwork;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode;
import org.fetarute.fetaruteTCAddon.dispatcher.route.DynamicStopMatcher;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 生产图包装与跨世界视图必须保留站点候选索引，查询时不能扫描全图。 */
class RailGraphPlatformIndexTest {
  @ParameterizedTest
  @ValueSource(strings = {"snapshot", "override", "network", "network-override"})
  void candidateLookupUsesTheIndexThroughProductionViews(String view) {
    Map<NodeId, RailNode> nodes = new LinkedHashMap<>();
    for (int i = 1; i <= 100; i++) {
      NodeId id = NodeId.of(i <= 3 ? "OP:S:DEST:" + i : "OP:S:OTHER" + i + ":1");
      nodes.put(
          id,
          new SignRailNode(
              id, NodeType.STATION, new Vector(i, 64, 0), Optional.empty(), Optional.empty()));
    }
    SimpleRailGraph snapshot = spy(new SimpleRailGraph(nodes, Map.of(), Set.of()));
    RailGraph graph = snapshot;
    if (view.startsWith("network")) {
      UUID world = UUID.randomUUID();
      graph = spy(RailNetwork.build(Map.of(world, snapshot), List.of()).view(world));
    }
    if (view.endsWith("override")) {
      graph = spy(new EdgeOverrideRailGraph(graph, Map.of(), Instant.EPOCH));
    }
    clearInvocations(snapshot, graph);
    var spec = DynamicStopMatcher.parseDynamicSpec("DYNAMIC:OP:S:DEST:[1,3]").orElseThrow();
    assertEquals(
        List.of(NodeId.of("OP:S:DEST:1"), NodeId.of("OP:S:DEST:3")),
        DynamicStopMatcher.candidateNodes(spec, graph));
    assertEquals(
        List.of(NodeId.of("OP:S:DEST:1"), NodeId.of("OP:S:DEST:3")),
        DynamicStopMatcher.matchingNodes(spec, graph));
    verify(graph, never()).nodes();
    verify(snapshot, never()).nodes();
  }
}
