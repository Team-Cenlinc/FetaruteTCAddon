package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.bukkit.util.Vector;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.SignRailNode;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.SimpleRailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode;
import org.fetarute.fetaruteTCAddon.dispatcher.route.DynamicStopMatcher;
import org.fetarute.fetaruteTCAddon.dispatcher.route.DynamicStopMatcher.DynamicSpec;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * 同一条 DYNAMIC 写法在运行时选台与编表冲突检查两层得到的股道集合必须相同，“受限”的判断也必须相同。
 *
 * <p>路网：DEST 有 1、2、3、5 四条股道（4 号空缺），BIG 有 25 条股道（超过选台评估上限 20）。
 */
class DynamicSelectionConsistencyTest {

  private final RailGraph graph = graph();
  private final TimetableConflictChecker.Footprints footprints =
      new TimetableConflictChecker.Footprints(TimetableConflictChecker.GraphIndex.of(graph));

  @ParameterizedTest
  @CsvSource({
    "DYNAMIC:OP:S:DEST, false",
    "'DYNAMIC:OP:S:DEST:[1,3]', true",
    "DYNAMIC:OP:S:DEST:[2:4], true",
    "DYNAMIC:OP:S:DEST:[1:5], false",
    "DYNAMIC:OP:S:DEST:2, true",
    "DYNAMIC:OP:S:BIG, false",
    "DYNAMIC:OP:S:BIG:[3:30], true"
  })
  void runtimeAndConflictCheckSeeTheSameTracks(String raw, boolean restricted) {
    DynamicSpec spec = DynamicStopMatcher.parseDynamicSpec(raw).orElseThrow();
    Set<String> runtime =
        DynamicStopMatcher.candidateNodes(spec, graph).stream()
            .map(NodeId::value)
            .collect(Collectors.toSet());
    TimetableConflictChecker.PlatformEligibility eligibility =
        footprints
            .eligibility(
                new TimetableConflictChecker.Platform(
                    spec.toPlaceholderNodeId(),
                    spec.operatorCode() + ":" + spec.nodeType() + ":" + spec.nodeName(),
                    true,
                    Optional.of(spec)))
            .orElseThrow();

    assertEquals(runtime, Set.copyOf(eligibility.nodes()));
    assertEquals(restricted, eligibility.restricted());
    assertEquals(restricted, DynamicStopMatcher.restricts(spec, graph));
  }

  private static RailGraph graph() {
    Map<NodeId, RailNode> nodes = new LinkedHashMap<>();
    for (int track : List.of(1, 2, 3, 5)) {
      station(nodes, "OP:S:DEST:" + track, track);
    }
    for (int track = 1; track <= 25; track++) {
      station(nodes, "OP:S:BIG:" + track, 100 + track);
    }
    return new SimpleRailGraph(nodes, Map.of(), Set.of());
  }

  private static void station(Map<NodeId, RailNode> nodes, String id, int x) {
    NodeId nodeId = NodeId.of(id);
    nodes.put(
        nodeId,
        new SignRailNode(
            nodeId, NodeType.STATION, new Vector(x, 64, 0), Optional.empty(), Optional.empty()));
  }
}
