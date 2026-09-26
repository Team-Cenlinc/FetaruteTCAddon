package org.fetarute.fetaruteTCAddon.dispatcher.route;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.company.model.RouteOperationType;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.company.model.RouteStopPassType;
import org.fetarute.fetaruteTCAddon.company.model.Station;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.junit.jupiter.api.Test;

/** 终点口径：停靠表形态取自实服路线（2026-09-24 库）。 */
class RouteTerminalsTest {

  private static final UUID ROUTE = UUID.randomUUID();

  /** MT-1O_ShortR 尾部：TERMINATE 落在 OFL 与 MLU 之间的折返线上。 */
  private static List<RouteStop> terminateOnTurnbackSiding() {
    return stops(
        stop(RouteStopPassType.STOP, "SURC:S:HAS:2", null),
        stop(RouteStopPassType.STOP, "SURC:S:OFL:2", null),
        stop(RouteStopPassType.TERMINATE, "SURC:OFL:MLU:2:004", null));
  }

  /** DS-1F_Full 尾部：TERMINATE 是 DYNAMIC 车站（WYB），车名 DS-LW 是有意设计。 */
  private static List<RouteStop> terminateOnDynamicStation() {
    return stops(
        stop(RouteStopPassType.STOP, "SURC:S:KON:2", null),
        stop(RouteStopPassType.STOP, "SURC:S:CGL:2", null),
        stop(RouteStopPassType.TERMINATE, null, "DYNAMIC:SURC:S:WYB"));
  }

  /** WS-1C_ShortD（RETURN）：最后一个载客站 HHU:3，之后经车库咽喉回库。 */
  private static List<RouteStop> returnToDepot() {
    return stops(
        stop(RouteStopPassType.STOP, "SURC:S:KPO:1", null),
        stop(RouteStopPassType.PASS, "SURC:KPO:HHU:1:002", null),
        stop(RouteStopPassType.STOP, "SURC:S:HHU:3", null),
        stop(RouteStopPassType.PASS, "SURC:D:LWN:1:005", null),
        stop(RouteStopPassType.PASS, "SURC:D:LWN:1", "DSTY SURC:D:LWN:1"));
  }

  @Test
  void namingFallsBackToLastPassengerStationWhenTerminateIsOnSiding() {
    List<RouteStop> stops = terminateOnTurnbackSiding();
    assertEquals(OptionalInt.of(1), RouteTerminals.namingIndex(stops));

    RouteDestinationResolver.DestinationInfo dest =
        RouteDestinationResolver.resolve(stops, id -> Optional.<Station>empty(), "R", "R")
            .orElseThrow();
    // 修复前：code = "SURC:OFL:MLU:2:004"，车名首字母取成 "S"
    assertEquals("OFL", dest.code());
  }

  @Test
  void namingKeepsTerminateWhenItIsADynamicStation() {
    List<RouteStop> stops = terminateOnDynamicStation();
    assertEquals(OptionalInt.of(2), RouteTerminals.namingIndex(stops));

    RouteDestinationResolver.DestinationInfo dest =
        RouteDestinationResolver.resolve(stops, id -> Optional.<Station>empty(), "R", "R")
            .orElseThrow();
    assertEquals("WYB", dest.code());
  }

  @Test
  void namingWithoutAnyStationKeepsOriginalSelection() {
    List<RouteStop> stops =
        stops(
            stop(RouteStopPassType.PASS, "SURC:D:HHU:3", "CRET SURC:D:HHU:3"),
            stop(RouteStopPassType.TERMINATE, "SURC:OFL:MLU:2:004", null));
    assertEquals(OptionalInt.of(1), RouteTerminals.namingIndex(stops));
  }

  @Test
  void endOfOperationIsLastNonPassStationAndSkipsSidingAndDepot() {
    assertEquals(
        OptionalInt.of(1), RouteTerminals.endOfOperationIndex(terminateOnTurnbackSiding()));
    assertEquals(
        OptionalInt.of(2), RouteTerminals.endOfOperationIndex(terminateOnDynamicStation()));
    assertEquals(OptionalInt.of(2), RouteTerminals.endOfOperationIndex(returnToDepot()));
  }

  @Test
  void endOfRouteIsLastNodeWhileEndOfOperationIsLastStoppingStation() {
    List<RouteStop> stops =
        stops(
            stop(RouteStopPassType.STOP, "SURC:S:AAA:1", null),
            stop(RouteStopPassType.STOP, "SURC:S:BBB:1", null),
            stop(RouteStopPassType.PASS, "SURC:S:CCC:1", null),
            stop(RouteStopPassType.PASS, "SURC:S:CCC:1:001", null),
            stop(RouteStopPassType.PASS, "SURC:D:CCC:1", null));
    // EOR 是交路最后一个节点（车库）；EOP 是车次最后停靠的车站，回库途中只通过的 CCC 不算。
    assertEquals(OptionalInt.of(4), RouteTerminals.endOfRouteIndex(stops));
    assertEquals(OptionalInt.of(1), RouteTerminals.endOfOperationIndex(stops));
    assertEquals(OptionalInt.of(4), RouteTerminals.endOfRouteIndex(returnToDepot()));
  }

  @Test
  void returnRouteShowsStationUntilLeavingEndOfOperation() {
    List<RouteStop> stops = returnToDepot();
    assertFalse(RouteTerminals.outOfService(RouteOperationType.RETURN, stops, 0));
    assertFalse(RouteTerminals.outOfService(RouteOperationType.RETURN, stops, 2));
    assertTrue(RouteTerminals.outOfService(RouteOperationType.RETURN, stops, 3));
    assertTrue(RouteTerminals.outOfService(RouteOperationType.RETURN, stops, 4));
  }

  @Test
  void onlyReturnRoutesGoOutOfService() {
    List<RouteStop> stops = returnToDepot();
    assertFalse(RouteTerminals.outOfService(RouteOperationType.OPERATION, stops, 4));
    assertFalse(RouteTerminals.outOfService(RouteOperationType.CREATE, stops, 4));
    assertFalse(RouteTerminals.outOfService(null, stops, 4));
  }

  @Test
  void returnRouteWithoutPassengerStationIsOutOfServiceFromStart() {
    List<RouteStop> stops =
        stops(
            stop(RouteStopPassType.PASS, "SURC:KPO:HHU:1:002", null),
            stop(RouteStopPassType.PASS, "SURC:D:LWN:1", "DSTY SURC:D:LWN:1"));
    assertTrue(RouteTerminals.outOfService(RouteOperationType.RETURN, stops, 0));
  }

  @Test
  void dynamicTerminalMatchesAnyTrackInRange() {
    RouteStop dynamic = stop(RouteStopPassType.TERMINATE, null, "DYNAMIC:SURC:S:WYB:[1:3]");
    // 修复前 HUD 用占位节点（第一条股道）做字符串相等：停进 2 道就永远不算“已到终点”。
    assertTrue(RouteTerminals.matches(NodeId.of("SURC:S:WYB:2"), dynamic));
    assertTrue(RouteTerminals.matches(NodeId.of("SURC:S:WYB:1"), dynamic));
    assertFalse(RouteTerminals.matches(NodeId.of("SURC:S:CGL:2"), dynamic));
  }

  @Test
  void endOfRouteLabelUsesPrecedingStationForTurnbackSiding() {
    // MT-1O_ShortR：终点节点是 OFL 与 MLU 之间的折返线；按“前方站”会显示车根本不去的 MLU。
    assertEquals(OptionalInt.of(2), RouteTerminals.endOfRouteIndex(terminateOnTurnbackSiding()));
    assertEquals(
        OptionalInt.of(1), RouteTerminals.endOfRouteLabelIndex(terminateOnTurnbackSiding()));
    // 车站、车库作终点时锚点就是它自己。
    assertEquals(OptionalInt.of(4), RouteTerminals.endOfRouteLabelIndex(returnToDepot()));
    assertEquals(
        OptionalInt.of(2), RouteTerminals.endOfRouteLabelIndex(terminateOnDynamicStation()));
  }

  @Test
  void depotRefRecognisesDepotNodesAndDynamicDepots() {
    assertEquals(
        "LWN",
        RouteTerminals.depotRef(stop(RouteStopPassType.PASS, "SURC:D:LWN:1", "DSTY SURC:D:LWN:1"))
            .orElseThrow()
            .stationCode());
    assertEquals(
        "OFL",
        RouteTerminals.depotRef(stop(RouteStopPassType.PASS, null, "DYNAMIC:SURC:D:OFL:[1:2]"))
            .orElseThrow()
            .stationCode());
    assertTrue(
        RouteTerminals.depotRef(stop(RouteStopPassType.STOP, "SURC:S:LWN:1", null)).isEmpty());
    assertTrue(
        RouteTerminals.depotRef(stop(RouteStopPassType.PASS, "SURC:D:LWN:1:005", null)).isEmpty(),
        "车库咽喉不是车库本体");
    assertEquals("LWN Depot", RouteTerminals.depotCodeLabel("LWN"));
  }

  @Test
  void stationRefResolvesWaypointAndDynamicButNotThroat() {
    assertEquals(
        "OFL",
        RouteTerminals.stationRef(stop(RouteStopPassType.STOP, "SURC:S:OFL:2", null))
            .orElseThrow()
            .stationCode());
    assertEquals(
        "WYB",
        RouteTerminals.stationRef(stop(RouteStopPassType.TERMINATE, null, "DYNAMIC:SURC:S:WYB"))
            .orElseThrow()
            .stationCode());
    assertTrue(
        RouteTerminals.stationRef(stop(RouteStopPassType.PASS, "SURC:S:JBS:1:001", null))
            .isEmpty());
    assertTrue(
        RouteTerminals.stationRef(stop(RouteStopPassType.PASS, "SURC:OFL:MLU:2:004", null))
            .isEmpty());
  }

  private static RouteStop stop(RouteStopPassType type, String waypoint, String notes) {
    return new RouteStop(
        ROUTE,
        0,
        Optional.empty(),
        Optional.ofNullable(waypoint),
        Optional.empty(),
        type,
        Optional.ofNullable(notes));
  }

  private static List<RouteStop> stops(RouteStop... stops) {
    List<RouteStop> out = new ArrayList<>();
    for (int i = 0; i < stops.length; i++) {
      RouteStop s = stops[i];
      out.add(
          new RouteStop(
              s.routeId(),
              i,
              s.stationId(),
              s.waypointNodeId(),
              s.dwellSeconds(),
              s.passType(),
              s.notes()));
    }
    return out;
  }
}
