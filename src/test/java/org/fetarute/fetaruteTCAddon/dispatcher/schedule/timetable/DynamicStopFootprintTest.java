package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
import org.junit.jupiter.api.Test;

/**
 * 运行时选台的起点：站台到第一组道岔之间走哪条股道到站才定，足迹不按占位的首股道登记这一截，只留站台组一层按容量管； 第一组道岔起照常登记。
 *
 * <p>路网：A 站 1、2 两条股道在道岔 SW 汇合，1 道出站先过路径点 A:1:001；SW 经区间点 AB:1:001 到 B 站。
 */
class DynamicStopFootprintTest {

  private static final String A1 = "OP:S:A:1";
  private static final String A1_EXIT = "OP:S:A:1:001";
  private static final String A2 = "OP:S:A:2";
  private static final String SW = "SWITCHER:OP:1:64:1";
  private static final String AB = "OP:A:B:1:001";
  private static final String B1 = "OP:S:B:1";

  private final RailGraph graph = graph();
  private final TimetableConflictChecker.Footprints footprints =
      new TimetableConflictChecker.Footprints(TimetableConflictChecker.GraphIndex.of(graph));

  /** 两条股道都可选：首股道那截（站台到 SW）不登记，SW 与之后的区间照常登记。 */
  @Test
  void aDynamicOriginWithSeveralTracksLeavesTheStationTracksToThePlatformGroup() {
    List<String> keys = slotKeys("DYNAMIC:OP:S:A");

    assertTrue(keys.stream().noneMatch(key -> key.contains(A1_EXIT)), keys::toString);
    assertTrue(keys.contains("junction:" + SW), keys::toString);
    assertTrue(keys.stream().anyMatch(key -> key.startsWith("edge:") && key.contains(AB)));
  }

  /** 固定股道的起点：整条路径照常登记。 */
  @Test
  void aFixedOriginKeepsItsTrackEdges() {
    List<String> keys = slotKeys(null);

    assertTrue(keys.stream().anyMatch(key -> key.contains(A1_EXIT)), keys::toString);
  }

  /** 只许一条股道的 DYNAMIC 与固定股道无异：照常登记。 */
  @Test
  void aDynamicOriginRestrictedToOneTrackKeepsItsTrackEdges() {
    List<String> keys = slotKeys("DYNAMIC:OP:S:A:1");

    assertFalse(keys.isEmpty());
    assertTrue(keys.stream().anyMatch(key -> key.contains(A1_EXIT)), keys::toString);
  }

  private List<String> slotKeys(String originNotes) {
    UUID id = TimetableTestFixtures.routeId("DYN-" + originNotes);
    List<String> waypoints = List.of(A1, B1);
    RouteDefinition route = TimetableTestFixtures.route("DYN", waypoints);
    List<RouteStop> stops = new ArrayList<>(TimetableTestFixtures.stops(id, waypoints.size(), 20));
    RouteStop first = stops.get(0);
    stops.set(
        0,
        new RouteStop(
            first.routeId(),
            first.sequence(),
            first.stationId(),
            first.waypointNodeId(),
            first.dwellSeconds(),
            first.passType(),
            Optional.ofNullable(originNotes)));
    TimetableTimingCalculator.TimingResult timing =
        new TimetableTimingCalculator()
            .compute(graph, TimetableTestFixtures.perEdgeSpeedModel(), route, stops, Duration.ZERO);
    assertTrue(timing.ok(), () -> String.valueOf(timing.failure()));
    TimetableConflictChecker.RouteProfile profile =
        new TimetableConflictChecker.RouteProfile(
            id,
            "DYN",
            timing.stops(),
            timing.segments(),
            TimetableConflictChecker.platformsOf(timing.stops(), stops, route.waypoints()));
    return footprints.of(id, profile).slots().stream()
        .map(TimetableConflictChecker.Slot::key)
        .toList();
  }

  private static RailGraph graph() {
    Map<String, NodeType> nodes = new LinkedHashMap<>();
    nodes.put(A1, NodeType.STATION);
    nodes.put(A2, NodeType.STATION);
    nodes.put(A1_EXIT, NodeType.WAYPOINT);
    nodes.put(SW, NodeType.SWITCHER);
    nodes.put(AB, NodeType.WAYPOINT);
    nodes.put(B1, NodeType.STATION);
    return TimetableTestFixtures.graph(
        nodes,
        List.of(
            new TimetableTestFixtures.Edge(A1, A1_EXIT, 30, 10.0),
            new TimetableTestFixtures.Edge(A1_EXIT, SW, 10, 10.0),
            new TimetableTestFixtures.Edge(A2, SW, 40, 10.0),
            new TimetableTestFixtures.Edge(SW, AB, 50, 10.0),
            new TimetableTestFixtures.Edge(AB, B1, 50, 10.0)));
  }
}
