package org.fetarute.fetaruteTCAddon.dispatcher.route;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.company.model.RouteStopPassType;
import org.fetarute.fetaruteTCAddon.drive.setup.PowerSupply;
import org.junit.jupiter.api.Test;

/** 交路备注 POWER：这一点起应当用的受电方式，以及哪一站停站要加换模时间。 */
class RoutePowerModesTest {

  private static final UUID ROUTE = UUID.randomUUID();

  /** 停靠点：{@code pass} 为不停车的路径点，{@code power} 为 POWER 备注（可空）。 */
  private static RouteStop stop(int sequence, boolean pass, String power) {
    return new RouteStop(
        ROUTE,
        sequence,
        Optional.empty(),
        Optional.of("SURC:S:N" + sequence + ":1"),
        Optional.empty(),
        pass ? RouteStopPassType.PASS : RouteStopPassType.STOP,
        power == null ? Optional.empty() : Optional.of("CHANGE:SURC:WS\nPOWER:" + power));
  }

  private static List<RouteStop> route(Object... spec) {
    List<RouteStop> stops = new ArrayList<>();
    for (int i = 0; i < spec.length; i += 2) {
      stops.add(stop(i / 2, (Boolean) spec[i], (String) spec[i + 1]));
    }
    return stops;
  }

  @Test
  void modeAtIsTheLastPowerUpToThisPoint() {
    List<RouteStop> stops = route(false, null, false, "ptg6", true, null, false, "diesel");

    assertTrue(RoutePowerModes.modeAt(stops, 0).isEmpty(), "还没写过 POWER：沿用列车当前的");
    assertEquals(Optional.of(PowerSupply.PTG6), RoutePowerModes.modeAt(stops, 1));
    assertEquals(Optional.of(PowerSupply.PTG6), RoutePowerModes.modeAt(stops, 2));
    assertEquals(Optional.of(PowerSupply.DIESEL), RoutePowerModes.modeAt(stops, 3));
    assertEquals(
        Optional.of(PowerSupply.PTG6),
        RoutePowerModes.directive(stop(0, false, "PTG6")),
        "与其他动作行写在一起也认得");
    assertTrue(RoutePowerModes.directive(stop(0, false, "steam")).isEmpty());
  }

  @Test
  void onlySwitchesInvolvingDieselNeedAStop() {
    assertTrue(RoutePowerModes.needsStop(PowerSupply.PTG5, PowerSupply.DIESEL));
    assertTrue(RoutePowerModes.needsStop(PowerSupply.DIESEL, PowerSupply.SHOE));
    assertTrue(RoutePowerModes.needsStop(null, PowerSupply.DIESEL));
    assertFalse(RoutePowerModes.needsStop(PowerSupply.PTG5, PowerSupply.PTG6));
    assertFalse(RoutePowerModes.needsStop(PowerSupply.SHOE, PowerSupply.SUPERCAP));
  }

  /** 首站 ptg6；第 2 个点（不停车）写 diesel，推迟到第 3 站换并多停；第 4 站换回 ptg5 同样多停；电对电不加时。 */
  @Test
  void dieselSwitchesAddDwellAtTheStopWhereTheyHappen() {
    List<RouteStop> stops =
        route(false, "ptg6", false, null, true, "diesel", false, null, false, "ptg5", false, null);

    int[] expected = {0, 0, 0, RoutePowerModes.DIESEL_SWITCH_SECONDS, 25, 0};
    for (int i = 0; i < expected.length; i++) {
      assertEquals(expected[i], RoutePowerModes.switchDwellSeconds(stops, i), "第 " + i + " 个点");
    }
    List<RouteStop> electric = route(false, "ptg5", true, "ptg6", false, null, false, "shoe");
    for (int i = 0; i < electric.size(); i++) {
      assertEquals(0, RoutePowerModes.switchDwellSeconds(electric, i), "电对电经过即换，不加时");
    }
  }

  /** 交路开头不知道列车用什么：换成电力按不用换算，换成内燃按要换算；首站不加。 */
  @Test
  void anUnknownStartCountsOnlyDieselAsASwitch() {
    List<RouteStop> stops = route(false, null, false, "ptg6", false, null, false, "diesel");

    assertEquals(0, RoutePowerModes.switchDwellSeconds(stops, 1));
    assertEquals(25, RoutePowerModes.switchDwellSeconds(stops, 3));
    assertEquals(0, RoutePowerModes.switchDwellSeconds(route(false, "diesel", false, null), 0));
    assertEquals(0, RoutePowerModes.switchDwellSeconds(route(false, "diesel", false, null), 1));
  }

  /** 车库出车：首站是不停车的车库点，写在那里的内燃在出车时就换好，不推迟到第一个车站多停。 */
  @Test
  void aDepotStartSwitchesAtDispatch() {
    List<RouteStop> stops = route(true, "diesel", false, null, false, "ptg6");

    int[] plan = RoutePowerModes.switchDwellPlan(stops);

    assertEquals(0, plan[0]);
    assertEquals(0, plan[1], "首站已换成内燃，第一个车站不多停");
    assertEquals(RoutePowerModes.DIESEL_SWITCH_SECONDS, plan[2], "从内燃换回电力照常多停");
  }

  /** 整份停靠表一遍推算，与逐站查询一致。 */
  @Test
  void thePlanMatchesPerStopQueries() {
    List<RouteStop> stops =
        route(false, "ptg6", false, null, true, "diesel", false, null, false, "ptg5", false, null);

    int[] plan = RoutePowerModes.switchDwellPlan(stops);

    for (int i = 0; i < stops.size(); i++) {
      assertEquals(RoutePowerModes.switchDwellSeconds(stops, i), plan[i], "第 " + i + " 个点");
    }
    assertEquals(0, RoutePowerModes.switchDwellPlan(List.of()).length);
  }
}
