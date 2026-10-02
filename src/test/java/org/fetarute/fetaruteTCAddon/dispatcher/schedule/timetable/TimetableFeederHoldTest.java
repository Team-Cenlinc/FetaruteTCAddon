package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.company.model.RouteOperationType;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.company.model.RouteStopPassType;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.junit.jupiter.api.Test;

/**
 * 按车接续的喂车方向中途多停：多停加在哪一站、加成什么样、时分怎么变。
 *
 * <p>形状照实服 1L：车库出库（CRET）、过咽喉路径点（PASS）、第一个中途停车点 H，再经 K 终到 C。多停加在 H——过了出库咽喉、 还没到端点。
 */
class TimetableFeederHoldTest {

  private static final UUID ROUTE = TimetableTestFixtures.routeId("1L");
  private static final String DEP = "OP:D:DEP:1";
  private static final String THROAT = "OP:W:DEP:1:001";
  private static final String H = "OP:S:H:2";
  private static final String K = "OP:S:K:2";
  private static final String C = "OP:S:C:3";

  private final RailGraph graph =
      TimetableTestFixtures.chain(
          List.of(DEP, THROAT, H, K, C),
          List.of(
              NodeType.DEPOT,
              NodeType.WAYPOINT,
              NodeType.STATION,
              NodeType.STATION,
              NodeType.STATION),
          new int[] {50, 50, 100, 100},
          new double[] {10.0, 10.0, 10.0, 10.0});

  /** 首站与 PASS 都不算中途停车点：第一个中途停车点是 H（下标 2）。 */
  @Test
  void firstIntermediateStopSkipsTheOriginAndPassingPoints() {
    assertEquals(Optional.of(2), TimetableBuilder.firstIntermediateStop(stops(null)));
    assertEquals(
        Optional.empty(),
        TimetableBuilder.firstIntermediateStop(
            List.of(
                stop(0, RouteStopPassType.STOP, 0),
                stop(1, RouteStopPassType.PASS, null),
                stop(2, RouteStopPassType.TERMINATE, null))),
        "首末之间只有 PASS：没有可以多停的地方");
  }

  /** 多停只加在 H 上；H 没配 dwell 时从缺省停站起算，其余停靠原样。 */
  @Test
  void holdIsAddedToTheFirstIntermediateStopOnly() {
    TimetableBuilder.RouteInput route = route(stops(null));

    TimetableBuilder.RouteInput held = TimetableBuilder.held(route, 15, Duration.ofSeconds(20));

    assertEquals(Optional.of(35), held.stops().get(2).dwellSeconds(), "缺省 20 秒 + 多停 15 秒");
    for (int i = 0; i < route.stops().size(); i++) {
      if (i != 2) {
        assertEquals(route.stops().get(i), held.stops().get(i), "第 " + i + " 站不该变");
      }
    }
    assertSame(route, TimetableBuilder.held(route, 0, Duration.ofSeconds(20)), "不多停原样返回");
  }

  /** 算出来的时分：H 之前各点不变，H 发车及之后各点整体晚 15 秒——喂车方向到端点的时刻随之后移，出库过咽喉不动。 */
  @Test
  void heldTimingShiftsEverythingAfterTheHoldStop() {
    TimetableBuilder.RouteInput route = route(stops(20));
    TimetableBuilder.RouteInput held = TimetableBuilder.held(route, 15, Duration.ofSeconds(20));
    TimetableTimingCalculator calculator = new TimetableTimingCalculator();

    List<TimetableStop> plain =
        calculator
            .compute(
                graph,
                TimetableTestFixtures.perEdgeSpeedModel(),
                route.definition(),
                route.stops(),
                Duration.ofSeconds(20))
            .stops();
    List<TimetableStop> shifted =
        calculator
            .compute(
                graph,
                TimetableTestFixtures.perEdgeSpeedModel(),
                held.definition(),
                held.stops(),
                Duration.ofSeconds(20))
            .stops();

    for (int i = 0; i < plain.size(); i++) {
      int arrivalShift = i <= 2 ? 0 : 15;
      int departureShift = i < 2 ? 0 : 15;
      assertEquals(
          plain.get(i).arrivalOffsetSeconds() + arrivalShift,
          shifted.get(i).arrivalOffsetSeconds(),
          "第 " + i + " 站到达");
      assertEquals(
          plain.get(i).departureOffsetSeconds() + departureShift,
          shifted.get(i).departureOffsetSeconds(),
          "第 " + i + " 站发车");
    }
  }

  private static TimetableBuilder.RouteInput route(List<RouteStop> stops) {
    return new TimetableBuilder.RouteInput(
        ROUTE,
        "1L",
        RouteOperationType.OPERATION,
        1,
        TimetableTestFixtures.route("1L", List.of(DEP, THROAT, H, K, C)),
        stops,
        Optional.empty(),
        Optional.empty(),
        false,
        Optional.of("short"));
  }

  /** 车库（CRET）→ 咽喉路径点（PASS）→ H → K → C（终到）；{@code dwell} 给 H 与 K。 */
  private static List<RouteStop> stops(Integer dwell) {
    return List.of(
        new RouteStop(
            ROUTE,
            0,
            Optional.empty(),
            Optional.empty(),
            Optional.of(0),
            RouteStopPassType.STOP,
            Optional.of("CRET " + DEP)),
        stop(1, RouteStopPassType.PASS, null),
        stop(2, RouteStopPassType.STOP, dwell),
        stop(3, RouteStopPassType.STOP, dwell),
        stop(4, RouteStopPassType.TERMINATE, dwell));
  }

  private static RouteStop stop(int sequence, RouteStopPassType passType, Integer dwell) {
    return new RouteStop(
        ROUTE,
        sequence,
        Optional.empty(),
        Optional.empty(),
        Optional.ofNullable(dwell),
        passType,
        Optional.empty());
  }
}
