package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.query.RailTravelTimeModels;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
import org.junit.jupiter.api.Test;

/**
 * 计划时分必须来自路网，且必须逐段用<b>该段自己的</b>限速。
 *
 * <p>这一组用例针对的是最容易犯的那个错：拿一个全线平均速度乘以总长度。对一条前半段 20 bps、 后半段 5 bps 的线，平均速度算出来的全程时分可以差到两倍以上，而表定时分一旦偏乐观，
 * 按表运行就会把每一趟车都变成晚点。
 */
class TimetableTimingCalculatorTest {

  private static final UUID ROUTE = UUID.randomUUID();

  /**
   * 逐边时分与站间时分是同一份数：每段的节点时刻从该段发车开始、到该段到达结束，中间按各边自己的时分分摊。
   *
   * <p>A→B 经 100 blocks @ 20 bps（5 秒）+ 100 blocks @ 5 bps（20 秒），中间节点 M 不停靠。
   */
  @Test
  void segmentTimingsFollowEachEdgeAndMatchStopTimes() {
    RailGraph graph =
        TimetableTestFixtures.chain(
            List.of("OP:S:A:1", "OP:X:M:1", "OP:S:B:1"),
            new int[] {100, 100},
            new double[] {20.0, 5.0});
    RouteDefinition route = TimetableTestFixtures.route("R1", List.of("OP:S:A:1", "OP:S:B:1"));

    TimetableTimingCalculator.TimingResult result =
        new TimetableTimingCalculator()
            .compute(
                graph,
                TimetableTestFixtures.perEdgeSpeedModel(),
                route,
                TimetableTestFixtures.stops(ROUTE, 2, 0),
                Duration.ZERO);

    assertTrue(result.ok(), () -> result.failure().toString());
    assertEquals(1, result.segments().size());
    TimetableTimingCalculator.SegmentTiming segment = result.segments().get(0);
    assertEquals(3, segment.nodes().size());
    assertEquals(List.of(0, 5, 25), segment.nodeOffsets(), "逐边时分按各边自己的限速");
    assertEquals(0, segment.enterOffset(0));
    assertEquals(5, segment.exitOffset(0));
    assertEquals(25, segment.exitOffset(1));
    assertEquals(
        result.stops().get(1).arrivalOffsetSeconds(), segment.exitOffset(1), "末节点时刻等于到站时刻");
  }

  /** 单一限速：时分 = 长度 / 限速，停站单独叠加。 */
  @Test
  void singleSpeedLimitSegment() {
    RailGraph graph =
        TimetableTestFixtures.chain(
            List.of("OP:S:A:1", "OP:S:B:1"), new int[] {100}, new double[] {10.0});
    RouteDefinition route = TimetableTestFixtures.route("R1", List.of("OP:S:A:1", "OP:S:B:1"));

    TimetableTimingCalculator.TimingResult result =
        new TimetableTimingCalculator()
            .compute(
                graph,
                RailTravelTimeModels.constantSpeed(10.0),
                route,
                TimetableTestFixtures.stops(ROUTE, 2, 30),
                Duration.ofSeconds(20));

    assertTrue(result.ok(), () -> "应当算得出来：" + result.failure());
    assertEquals(2, result.stops().size());
    assertEquals(0, result.stops().get(0).arrivalOffsetSeconds());
    assertEquals(10, result.stops().get(1).arrivalOffsetSeconds(), "100 blocks / 10 bps");
    assertEquals(10, result.totalRunSeconds());
  }

  /**
   * 多段不同限速：必须逐段积分。
   *
   * <p>两段各 100 blocks，限速 20 与 5：正确答案是 5 + 20 = 25 秒。若用平均速度 12.5 bps 乘总长 200， 会得到 16 秒——少算了
   * 36%，而且这个误差随着慢段占比升高而放大。
   */
  @Test
  void multipleSegmentsUseTheirOwnSpeedLimits() {
    RailGraph graph =
        TimetableTestFixtures.chain(
            List.of("OP:S:A:1", "OP:S:B:1", "OP:S:C:1"),
            new int[] {100, 100},
            new double[] {20.0, 5.0});
    RouteDefinition route =
        TimetableTestFixtures.route("R1", List.of("OP:S:A:1", "OP:S:B:1", "OP:S:C:1"));

    TimetableTimingCalculator.TimingResult result =
        new TimetableTimingCalculator()
            .compute(
                graph,
                new PerEdgeSpeedModel(),
                route,
                TimetableTestFixtures.stops(ROUTE, 3, 0),
                Duration.ZERO);

    assertTrue(result.ok(), () -> "应当算得出来：" + result.failure());
    assertEquals(5, result.stops().get(1).arrivalOffsetSeconds(), "100 / 20");
    assertEquals(25, result.stops().get(2).arrivalOffsetSeconds(), "5 + 100 / 5");
    assertEquals(25, result.totalRunSeconds());
  }

  /** 停站叠加在到达偏移之上，并推后后续全部区段；末站不写停站。 */
  @Test
  void dwellShiftsSubsequentStopsAndTerminalCarriesNone() {
    RailGraph graph =
        TimetableTestFixtures.chain(
            List.of("OP:S:A:1", "OP:S:B:1", "OP:S:C:1"),
            new int[] {100, 100},
            new double[] {10.0, 10.0});
    RouteDefinition route =
        TimetableTestFixtures.route("R1", List.of("OP:S:A:1", "OP:S:B:1", "OP:S:C:1"));

    TimetableTimingCalculator.TimingResult result =
        new TimetableTimingCalculator()
            .compute(
                graph,
                RailTravelTimeModels.constantSpeed(10.0),
                route,
                TimetableTestFixtures.stops(ROUTE, 3, 40),
                Duration.ofSeconds(20));

    List<TimetableStop> stops = result.stops();
    assertEquals(0, stops.get(0).departureOffsetSeconds(), "首站发车是原点");
    assertEquals(10, stops.get(1).arrivalOffsetSeconds());
    assertEquals(50, stops.get(1).departureOffsetSeconds(), "到达 10 + 停站 40");
    assertEquals(60, stops.get(2).arrivalOffsetSeconds(), "50 + 第二段 10");
    assertEquals(0L, stops.get(2).dwell().toSeconds(), "末站不写停站");
  }

  /** RouteStop 没配 dwell 时用缺省值，而不是当成 0。 */
  @Test
  void missingDwellFallsBackToConfiguredDefault() {
    RailGraph graph =
        TimetableTestFixtures.chain(
            List.of("OP:S:A:1", "OP:S:B:1", "OP:S:C:1"),
            new int[] {100, 100},
            new double[] {10.0, 10.0});
    RouteDefinition route =
        TimetableTestFixtures.route("R1", List.of("OP:S:A:1", "OP:S:B:1", "OP:S:C:1"));

    TimetableTimingCalculator.TimingResult result =
        new TimetableTimingCalculator()
            .compute(
                graph,
                RailTravelTimeModels.constantSpeed(10.0),
                route,
                TimetableTestFixtures.stops(ROUTE, 3, null),
                Duration.ofSeconds(25));

    assertEquals(35, result.stops().get(1).departureOffsetSeconds(), "到达 10 + 缺省停站 25");
  }

  /** 秒级四舍五入：33.4 秒进 33，33.6 秒进 34，且同样输入永远给同样结果。 */
  @Test
  void roundsToWholeSecondsDeterministically() {
    RailGraph graph =
        TimetableTestFixtures.chain(
            List.of("OP:S:A:1", "OP:S:B:1"), new int[] {100}, new double[] {3.0});
    RouteDefinition route = TimetableTestFixtures.route("R1", List.of("OP:S:A:1", "OP:S:B:1"));
    TimetableTimingCalculator calculator = new TimetableTimingCalculator();

    // 100 / 3 = 33.333…s → 33
    TimetableTimingCalculator.TimingResult first =
        calculator.compute(
            graph,
            RailTravelTimeModels.constantSpeed(3.0),
            route,
            TimetableTestFixtures.stops(ROUTE, 2, 0),
            Duration.ZERO);
    TimetableTimingCalculator.TimingResult second =
        calculator.compute(
            graph,
            RailTravelTimeModels.constantSpeed(3.0),
            route,
            TimetableTestFixtures.stops(ROUTE, 2, 0),
            Duration.ZERO);

    assertEquals(33, first.totalRunSeconds());
    assertEquals(first.totalRunSeconds(), second.totalRunSeconds(), "同样输入必须同样结果");
  }

  /** 区段不可达时明确失败并指出是哪一段，而不是悄悄用一个默认时分顶上。 */
  @Test
  void unreachableSegmentFailsWithReason() {
    RailGraph graph =
        TimetableTestFixtures.chain(
            List.of("OP:S:A:1", "OP:S:B:1"), new int[] {100}, new double[] {10.0});
    RouteDefinition route =
        TimetableTestFixtures.route("R1", List.of("OP:S:A:1", "OP:S:B:1", "OP:S:Z:1"));

    TimetableTimingCalculator.TimingResult result =
        new TimetableTimingCalculator()
            .compute(
                graph,
                RailTravelTimeModels.constantSpeed(10.0),
                route,
                TimetableTestFixtures.stops(ROUTE, 3, 0),
                Duration.ZERO);

    assertFalse(result.ok());
    assertTrue(result.failure().orElse("").contains("OP:S:Z:1"), () -> result.failure().orElse(""));
  }

  /** 缺少调度图时失败，不做任何猜测。 */
  @Test
  void missingGraphFails() {
    RouteDefinition route = TimetableTestFixtures.route("R1", List.of("OP:S:A:1", "OP:S:B:1"));

    TimetableTimingCalculator.TimingResult result =
        new TimetableTimingCalculator()
            .compute(
                null,
                RailTravelTimeModels.constantSpeed(10.0),
                route,
                TimetableTestFixtures.stops(ROUTE, 2, 0),
                Duration.ZERO);

    assertFalse(result.ok());
  }

  /** 按每条边自己的 {@code baseSpeedLimit} 估时的最小模型，用来验证"逐段限速"这件事本身。 */
  private static final class PerEdgeSpeedModel
      implements org.fetarute.fetaruteTCAddon.dispatcher.graph.query.RailTravelTimeModel {

    @Override
    public java.util.Optional<Duration> edgeTravelTime(
        RailGraph graph,
        org.fetarute.fetaruteTCAddon.dispatcher.graph.RailEdge edge,
        org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId from,
        org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId to) {
      if (edge == null || edge.lengthBlocks() <= 0 || edge.baseSpeedLimit() <= 0.0) {
        return java.util.Optional.empty();
      }
      double seconds = edge.lengthBlocks() / edge.baseSpeedLimit();
      return java.util.Optional.of(Duration.ofMillis(Math.round(seconds * 1000.0)));
    }
  }
}
