package org.fetarute.fetaruteTCAddon.dispatcher.route;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.company.model.Line;
import org.fetarute.fetaruteTCAddon.company.model.Operator;
import org.fetarute.fetaruteTCAddon.company.model.Route;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.company.model.RouteStopPassType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.junit.jupiter.api.Test;

final class RouteNodeUsageTest {

  private static final UUID ROUTE = UUID.randomUUID();
  private static final UUID LINE = UUID.randomUUID();

  @Test
  void declaredWaypointIsInUse() {
    var entry = entry(List.of("SURC:PTK:GPT:1:00", "SURC:S:GPT:1"), List.of(), Map.of());

    Optional<String> use = RouteNodeUsage.findUse(List.of(entry), NodeId.of("SURC:PTK:GPT:1:00"));

    assertEquals(Optional.of("交路 SURC:MT:MT-3 第 1 个途经点"), use);
  }

  @Test
  void otherTrackOfAServedStationIsInUseButThroatsAndIntervalsMatchExactly() {
    var entry = entry(List.of("SURC:S:GPT:1", "SURC:S:GPT:1:01"), List.of(), Map.of());

    assertTrue(RouteNodeUsage.findUse(List.of(entry), NodeId.of("SURC:S:GPT:3")).isPresent());
    assertTrue(RouteNodeUsage.findUse(List.of(entry), NodeId.of("SURC:S:GPT:1:02")).isEmpty());
    assertTrue(RouteNodeUsage.findUse(List.of(entry), NodeId.of("SURC:S:PTK:1")).isEmpty());
  }

  @Test
  void dynamicStopRangeCoversItsTracks() {
    RouteStop dynamic = stop(2, Optional.empty(), Optional.of("DYNAMIC:SURC:S:OFL:[1:3]"));
    var entry = entry(List.of("SURC:S:GPT:1"), List.of(dynamic), Map.of());

    assertEquals(
        Optional.of("交路 SURC:MT:MT-3 第 3 站"),
        RouteNodeUsage.findUse(List.of(entry), NodeId.of("SURC:S:OFL:2")));
  }

  @Test
  void depotDirectiveTargetsAndLineSpawnDepotsAreInUse() {
    RouteStop terminal = stop(1, Optional.of("SURC:S:GPT:1"), Optional.of("DSTY SURC:D:HHU:2"));
    var entry =
        entry(
            List.of("SURC:S:GPT:1"),
            List.of(terminal),
            Map.of("spawn_depots", List.of("DYNAMIC:SURC:D:OFL:[1:2]")));

    assertEquals(
        Optional.of("交路 SURC:MT:MT-3 的 DSTY 目标"),
        RouteNodeUsage.findUse(List.of(entry), NodeId.of("SURC:D:HHU:2")));
    assertEquals(
        Optional.of("线路 MT 的出车车库"),
        RouteNodeUsage.findUse(List.of(entry), NodeId.of("SURC:D:OFL:1")));
    assertTrue(RouteNodeUsage.findUse(List.of(entry), NodeId.of("SURC:D:OFL:3")).isEmpty());
  }

  @Test
  void nodeNoRouteMentionsIsUnused() {
    var entry = entry(List.of("SURC:S:GPT:1"), List.of(), Map.of());

    assertTrue(RouteNodeUsage.findUse(List.of(entry), NodeId.of("SURC:NEW:X:1:00")).isEmpty());
    assertTrue(RouteNodeUsage.findUse(List.of(), NodeId.of("SURC:S:GPT:1")).isEmpty());
  }

  private static RouteDefinitionCache.RouteEntry entry(
      List<String> waypoints, List<RouteStop> stops, Map<String, Object> lineMetadata) {
    Line line = mock(Line.class);
    when(line.id()).thenReturn(LINE);
    when(line.code()).thenReturn("MT");
    when(line.metadata()).thenReturn(lineMetadata);
    RouteDefinition definition =
        new RouteDefinition(
            new RouteId("SURC:MT:MT-3"),
            waypoints.stream().map(NodeId::of).toList(),
            Optional.empty());
    return new RouteDefinitionCache.RouteEntry(
        ROUTE,
        definition,
        new RouteDefinitionCache.RouteRecord(mock(Operator.class), line, mock(Route.class)),
        stops);
  }

  private static RouteStop stop(int sequence, Optional<String> node, Optional<String> notes) {
    return new RouteStop(
        ROUTE, sequence, Optional.empty(), node, Optional.empty(), RouteStopPassType.STOP, notes);
  }
}
