package org.fetarute.fetaruteTCAddon.coupler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.company.model.RouteStopPassType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.junit.jupiter.api.Test;

/** 联挂索引：主干在哪一站挂哪条回程支线，支线按同线同组的交路码找、终点须是联挂站。 */
class CouplingIndexTest {

  private static final UUID LINE = UUID.randomUUID();
  private static final UUID OTHER_LINE = UUID.randomUUID();

  private static CouplingIndex.RouteInfo route(
      UUID line, String code, String group, List<String> nodes, String notesAt1) {
    UUID id =
        UUID.nameUUIDFromBytes((line + code).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    List<RouteStop> stops = new ArrayList<>();
    for (int i = 0; i < nodes.size(); i++) {
      stops.add(
          new RouteStop(
              id,
              i,
              Optional.empty(),
              Optional.of(nodes.get(i)),
              Optional.empty(),
              i == nodes.size() - 1 ? RouteStopPassType.TERMINATE : RouteStopPassType.STOP,
              Optional.ofNullable(i == 1 ? notesAt1 : null)));
    }
    return new CouplingIndex.RouteInfo(
        id, line, code, group, stops, nodes.stream().map(NodeId::of).toList());
  }

  @Test
  void findsTheFeederEndingAtTheJoinStation() {
    CouplingIndex.RouteInfo trunk =
        route(
            LINE,
            "RR",
            "default",
            List.of("OP:S:Z:1", "OP:S:S:1", "OP:S:P:1"),
            "COPL FROM:BB:WAIT:60");
    CouplingIndex.RouteInfo feeder =
        route(LINE, "BB", "default", List.of("OP:S:T:1", "OP:S:S:2"), null);

    CouplingIndex index = CouplingIndex.build(List.of(trunk, feeder));

    CouplingIndex.Join join = index.atTrunkStop(trunk.id(), 1).orElseThrow();
    assertEquals(feeder.id(), join.feederRoute());
    assertEquals(1, join.feederEndIndex());
    assertEquals(OptionalInt.of(60), join.waitSeconds());
    assertEquals(List.of(join), index.ofFeeder(feeder.id()));
    assertTrue(join.atStation(NodeId.of("OP:S:S:3")), "同站别的股道也算联挂站");
    assertTrue(index.atTrunkStop(trunk.id(), 0).isEmpty());
  }

  @Test
  void ignoresFeedersElsewhere() {
    CouplingIndex.RouteInfo trunk =
        route(LINE, "RR", "default", List.of("OP:S:Z:1", "OP:S:S:1", "OP:S:P:1"), "COPL FROM:BB");
    CouplingIndex.RouteInfo otherLine =
        route(OTHER_LINE, "BB", "default", List.of("OP:S:T:1", "OP:S:S:2"), null);
    CouplingIndex.RouteInfo otherGroup =
        route(LINE, "BB", "express", List.of("OP:S:T:1", "OP:S:S:2"), null);
    CouplingIndex.RouteInfo wrongEnd =
        route(LINE, "BX", "default", List.of("OP:S:T:1", "OP:S:Q:1"), null);
    CouplingIndex.RouteInfo trunkToWrongEnd =
        route(LINE, "RX", "default", List.of("OP:S:Z:1", "OP:S:S:1", "OP:S:P:1"), "COPL FROM:BX");

    assertTrue(CouplingIndex.build(List.of(trunk, otherLine, otherGroup)).isEmpty());
    assertTrue(CouplingIndex.build(List.of(trunkToWrongEnd, wrongEnd)).isEmpty());
  }
}
