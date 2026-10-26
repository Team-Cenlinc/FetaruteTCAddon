package org.fetarute.fetaruteTCAddon.dispatcher.eta.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.bukkit.util.Vector;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.SignRailNode;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.SimpleRailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode;
import org.fetarute.fetaruteTCAddon.dispatcher.node.WaypointMetadata;
import org.junit.jupiter.api.Test;

/** 咽喉查找：同站同股道的咽喉，按图实例缓存，换图各算各的。 */
class StationThroatsTest {

  private static final NodeId STATION = NodeId.of("SURN:S:AAA:1");
  private static final NodeId THROAT_1 = NodeId.of("SURN:S:AAA:1:1");
  private static final NodeId THROAT_2 = NodeId.of("surn:s:aaa:1:2");
  private static final NodeId OTHER_TRACK = NodeId.of("SURN:S:AAA:2:1");
  private static final NodeId OTHER_STATION = NodeId.of("SURN:S:BBB:1:1");
  private static final NodeId DEPOT = NodeId.of("SURN:D:DEP:1");
  private static final NodeId DEPOT_THROAT = NodeId.of("SURN:D:DEP:1:1");

  @Test
  void findsTheThroatsOfTheSameStationAndTrack() {
    StationThroats throats = new StationThroats();

    assertEquals(Set.of(THROAT_1, THROAT_2), Set.copyOf(throats.of(graph(true), STATION)));
  }

  @Test
  void depotsUseDepotThroatsAndThroatsHaveNone() {
    StationThroats throats = new StationThroats();
    RailGraph graph = graph(true);

    assertEquals(List.of(DEPOT_THROAT), throats.of(graph, DEPOT));
    assertTrue(throats.of(graph, THROAT_1).isEmpty(), "目标本身是咽喉");
    assertTrue(throats.of(graph, NodeId.of("SURN:S:ZZZ:1")).isEmpty(), "图上没有的节点");
  }

  @Test
  void theResultIsRememberedPerGraphInstance() {
    StationThroats throats = new StationThroats();
    RailGraph withThroats = graph(true);
    RailGraph rebuiltWithout = graph(false);

    List<NodeId> first = throats.of(withThroats, STATION);

    assertSame(first, throats.of(withThroats, STATION), "同一张图第二次直接取记下的结果");
    assertTrue(throats.of(rebuiltWithout, STATION).isEmpty(), "换了图按新图算");
    assertEquals(2, throats.graphCount());
  }

  private static RailGraph graph(boolean withThroats) {
    Map<NodeId, RailNode> nodes = new HashMap<>();
    put(nodes, STATION, WaypointMetadata.station("SURN", "AAA", 1));
    put(nodes, DEPOT, WaypointMetadata.depot("SURN", "DEP", 1));
    if (withThroats) {
      put(nodes, THROAT_1, WaypointMetadata.stationThroat("SURN", "AAA", 1, "1"));
      put(nodes, THROAT_2, WaypointMetadata.stationThroat("surn", "aaa", 1, "2"));
      put(nodes, OTHER_TRACK, WaypointMetadata.stationThroat("SURN", "AAA", 2, "1"));
      put(nodes, OTHER_STATION, WaypointMetadata.stationThroat("SURN", "BBB", 1, "1"));
      put(nodes, DEPOT_THROAT, WaypointMetadata.depotThroat("SURN", "DEP", 1, "1"));
    }
    return new SimpleRailGraph(nodes, Map.of(), Set.of());
  }

  private static void put(Map<NodeId, RailNode> nodes, NodeId id, WaypointMetadata meta) {
    nodes.put(
        id,
        new SignRailNode(
            id, NodeType.WAYPOINT, new Vector(0, 0, 0), Optional.empty(), Optional.of(meta)));
  }
}
