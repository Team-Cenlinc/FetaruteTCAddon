package org.fetarute.fetaruteTCAddon.api.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.api.route.RouteApi;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.company.model.RouteStopPassType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinitionCache;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteId;
import org.junit.jupiter.api.Test;

/** EOR 是交路最后一个节点（常为车库），EOP 是车次最后停靠的车站。 */
class RouteApiTerminalTest {

  private static RouteApi.TerminalInfo terminalOf(String[][] shape) {
    UUID uuid = UUID.randomUUID();
    List<NodeId> waypoints = new ArrayList<>();
    List<RouteStop> stops = new ArrayList<>();
    for (int i = 0; i < shape.length; i++) {
      waypoints.add(NodeId.of(shape[i][1]));
      stops.add(
          new RouteStop(
              uuid,
              i,
              Optional.empty(),
              Optional.of(shape[i][1]),
              Optional.empty(),
              RouteStopPassType.valueOf(shape[i][0]),
              Optional.empty()));
    }
    RouteDefinition route =
        new RouteDefinition(RouteId.of("SURC:WS:TEST"), waypoints, Optional.empty());
    RouteDefinitionCache cache = mock(RouteDefinitionCache.class);
    when(cache.findById(uuid)).thenReturn(Optional.of(route));
    when(cache.listStops(route.id())).thenReturn(stops);
    return new RouteApiImpl(cache, null).getRoute(uuid).orElseThrow().terminal();
  }

  @Test
  void returnRouteEndsAtDepotWhileTripEndsAtLastStation() {
    // WS-1C_ShortD：HHU:3 载客终到，之后经车库咽喉回 LWN 车库。
    RouteApi.TerminalInfo terminal =
        terminalOf(
            new String[][] {
              {"STOP", "SURC:S:KPO:1"},
              {"STOP", "SURC:S:HHU:3"},
              {"PASS", "SURC:D:LWN:1:005"},
              {"PASS", "SURC:D:LWN:1"}
            });
    assertEquals("SURC:D:LWN:1", terminal.endOfRouteNodeId());
    assertEquals(Optional.of("LWN Depot"), terminal.endOfRouteName());
    assertEquals("SURC:S:HHU:3", terminal.endOfOperationNodeId());
    assertEquals(Optional.of("HHU"), terminal.endOfOperationName());
  }

  @Test
  void passStationAfterTripTerminalIsNotEndOfOperation() {
    RouteApi.TerminalInfo terminal =
        terminalOf(
            new String[][] {
              {"STOP", "SURC:S:RVS:2"},
              {"TERMINATE", "SURC:S:OFL:2"},
              {"PASS", "SURC:S:MLU:2"},
              {"PASS", "SURC:D:MLU:1"}
            });
    assertEquals("SURC:D:MLU:1", terminal.endOfRouteNodeId());
    assertEquals("SURC:S:OFL:2", terminal.endOfOperationNodeId());
  }

  @Test
  void routeWithoutPassengerStationHasNoEndOfOperation() {
    RouteApi.TerminalInfo terminal =
        terminalOf(
            new String[][] {
              {"PASS", "SURC:KPO:HHU:1:002"},
              {"PASS", "SURC:D:LWN:1"}
            });
    assertEquals("SURC:D:LWN:1", terminal.endOfRouteNodeId());
    // 不拿 EOR 顶替：调用方要能分辨“这条交路不载客”。
    assertEquals("", terminal.endOfOperationNodeId());
    assertTrue(terminal.endOfOperationName().isEmpty());
  }

  @Test
  void turnbackSidingIsEndOfRouteButNotEndOfOperation() {
    // MT-1O_ShortR：TERMINATE 在 OFL 与 MLU 之间的折返线上。
    RouteApi.TerminalInfo terminal =
        terminalOf(
            new String[][] {
              {"STOP", "SURC:S:HAS:2"},
              {"STOP", "SURC:S:OFL:2"},
              {"TERMINATE", "SURC:OFL:MLU:2:004"}
            });
    assertEquals("SURC:OFL:MLU:2:004", terminal.endOfRouteNodeId());
    // 修复前名称为空（只认 S/D 段）；HUD、站牌显示 MLU——车根本不去那一站。
    assertEquals(Optional.of("OFL"), terminal.endOfRouteName());
    assertEquals("SURC:S:OFL:2", terminal.endOfOperationNodeId());
  }
}
