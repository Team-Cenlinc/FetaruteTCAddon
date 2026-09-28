package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.company.model.RouteStopPassType;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.model.RunCurveModel;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.model.RunTimeModel;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.model.StopApproach;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.query.RailTravelTimeModels;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
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

  /**
   * 停车方式照抄 route 定义：停站 0 秒的 STOP 与 PASS 到发同刻，事后无法从时刻区分，必须构建时记下来。
   *
   * <p>站码只取车站本体节点：区间点 {@code OP:A:B:1:01} 的第三段是去向站、车库与同代码车站共用第三段，都不是车站。
   */
  @Test
  void stopsRecordPassTypeAndStationCodeOnlyForStationNodes() {
    List<String> nodes = List.of("OP:D:AAA:1", "OP:AAA:BBB:1:01", "OP:S:BBB:1", "OP:S:CCC:1");
    RailGraph graph =
        TimetableTestFixtures.chain(nodes, new int[] {100, 100, 100}, new double[] {10, 10, 10});
    RouteDefinition route = TimetableTestFixtures.route("R1", nodes);
    List<RouteStopPassType> passTypes =
        List.of(
            RouteStopPassType.PASS,
            RouteStopPassType.PASS,
            RouteStopPassType.STOP,
            RouteStopPassType.TERMINATE);
    List<RouteStop> stops =
        IntStream.range(0, nodes.size())
            .mapToObj(
                i ->
                    new RouteStop(
                        ROUTE,
                        i,
                        Optional.empty(),
                        Optional.of(nodes.get(i)),
                        Optional.of(0),
                        passTypes.get(i),
                        Optional.empty()))
            .toList();

    TimetableTimingCalculator.TimingResult result =
        new TimetableTimingCalculator()
            .compute(
                graph,
                RunTimeModel.perEdge(RailTravelTimeModels.constantSpeed(10.0)),
                route,
                stops,
                Duration.ZERO);

    assertTrue(result.ok(), () -> result.failure().toString());
    assertEquals(passTypes, result.stops().stream().map(TimetableStop::passType).toList());
    TimetableStop bbb = result.stops().get(2);
    assertEquals(bbb.arrivalOffsetSeconds(), bbb.departureOffsetSeconds(), "停站 0 秒");
    assertTrue(bbb.stops(), "停站 0 秒的 STOP 仍是停车点");
    assertEquals(
        List.of(Optional.empty(), Optional.empty(), Optional.of("BBB"), Optional.of("CCC")),
        result.stops().stream().map(TimetableStop::stationCode).toList());
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
                RunTimeModel.perEdge(RailTravelTimeModels.constantSpeed(10.0)),
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
                RunTimeModel.perEdge(new PerEdgeSpeedModel()),
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
                RunTimeModel.perEdge(RailTravelTimeModels.constantSpeed(10.0)),
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
                RunTimeModel.perEdge(RailTravelTimeModels.constantSpeed(10.0)),
                route,
                TimetableTestFixtures.stops(ROUTE, 3, null),
                Duration.ofSeconds(25));

    assertEquals(35, result.stops().get(1).departureOffsetSeconds(), "到达 10 + 缺省停站 25");
  }

  /** PASS 路径点不停站：即便它没配 dwell、即便给了兜底值，到达即离开；兜底只作用于没配 dwell 的 STOP。 */
  @Test
  void passStopsHaveZeroDwellRegardlessOfFallback() {
    RailGraph graph =
        TimetableTestFixtures.chain(
            List.of("OP:S:A:1", "OP:A:B:1:001", "OP:S:B:1"),
            new int[] {100, 100},
            new double[] {10.0, 10.0});
    RouteDefinition route =
        TimetableTestFixtures.route("R1", List.of("OP:S:A:1", "OP:A:B:1:001", "OP:S:B:1"));
    List<RouteStop> stops =
        List.of(
            new RouteStop(
                ROUTE,
                0,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                RouteStopPassType.STOP,
                Optional.empty()),
            new RouteStop(
                ROUTE,
                1,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                RouteStopPassType.PASS,
                Optional.empty()),
            new RouteStop(
                ROUTE,
                2,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                RouteStopPassType.TERMINATE,
                Optional.empty()));

    TimetableTimingCalculator.TimingResult result =
        new TimetableTimingCalculator()
            .compute(
                graph,
                RunTimeModel.perEdge(RailTravelTimeModels.constantSpeed(10.0)),
                route,
                stops,
                Duration.ofSeconds(25));

    assertEquals(10, result.stops().get(1).arrivalOffsetSeconds());
    assertEquals(10, result.stops().get(1).departureOffsetSeconds(), "PASS 到达即离开，兜底 25 不作用于它");
    assertEquals(20, result.stops().get(2).arrivalOffsetSeconds(), "全程时分不含路径点的假停站");
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
            RunTimeModel.perEdge(RailTravelTimeModels.constantSpeed(3.0)),
            route,
            TimetableTestFixtures.stops(ROUTE, 2, 0),
            Duration.ZERO);
    TimetableTimingCalculator.TimingResult second =
        calculator.compute(
            graph,
            RunTimeModel.perEdge(RailTravelTimeModels.constantSpeed(3.0)),
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
                RunTimeModel.perEdge(RailTravelTimeModels.constantSpeed(10.0)),
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
                RunTimeModel.perEdge(RailTravelTimeModels.constantSpeed(10.0)),
                route,
                TimetableTestFixtures.stops(ROUTE, 2, 0),
                Duration.ZERO);

    assertFalse(result.ok());
  }

  /**
   * 走行从静止起步：100 格、限速 10、加速度 1，前 10 秒加到限速（走过 50 格），剩下 50 格匀速 5 秒，共 15 秒。
   *
   * <p>旧口径每站都按限速"飞"出去，同一段只算 10 秒——实服 32 个站间区段合计少算了 42%，少的就是这一段起步。
   */
  @Test
  void runCurveStartsFromRest() {
    RailGraph graph =
        TimetableTestFixtures.chain(
            List.of("OP:S:A:1", "OP:S:B:1"), new int[] {100}, new double[] {10.0});
    RouteDefinition route = TimetableTestFixtures.route("R1", List.of("OP:S:A:1", "OP:S:B:1"));

    TimetableTimingCalculator.TimingResult result =
        new TimetableTimingCalculator()
            .compute(
                graph,
                curve(StopApproach.Rule.disabled(), 0),
                route,
                TimetableTestFixtures.stops(ROUTE, 2, 20),
                Duration.ZERO);

    assertTrue(result.ok(), () -> result.failure().toString());
    assertEquals(15, result.stops().get(1).arrivalOffsetSeconds());
  }

  /**
   * 进站限速区从"离触发节点不超过窗口的第一个图节点"起算，与运行时逐节点判断同一口径。
   *
   * <p>A→M 150 格、M→B 50 格，限速 20，B 前 50 格内限 10，加减速都是 1：先加速到 √200 ≈ 14.14（走过 100 格， 14.14 秒）， 再制动到 10
   * 正好到 M（50 格，4.14 秒），M→B 按 10 走 5 秒。M 约 18.3 秒，B 约 23.3 秒。
   */
  @Test
  void approachWindowStartsAtTheFirstGraphNodeWithinReach() {
    RailGraph graph =
        TimetableTestFixtures.chain(
            List.of("OP:S:A:1", "OP:A:B:1:001", "OP:S:B:1"),
            List.of(NodeType.STATION, NodeType.WAYPOINT, NodeType.STATION),
            new int[] {150, 50},
            new double[] {20.0, 20.0});
    RouteDefinition route = TimetableTestFixtures.route("R1", List.of("OP:S:A:1", "OP:S:B:1"));

    TimetableTimingCalculator.TimingResult result =
        new TimetableTimingCalculator()
            .compute(
                graph,
                curve(new StopApproach.Rule(50.0, 0, 10.0, 5.0), 0),
                route,
                TimetableTestFixtures.stops(ROUTE, 2, 20),
                Duration.ZERO);

    assertTrue(result.ok(), () -> result.failure().toString());
    assertEquals(List.of(0, 18, 23), result.segments().get(0).nodeOffsets());
    assertEquals(23, result.stops().get(1).arrivalOffsetSeconds());
  }

  /**
   * 途中的 PASS 点不打断走行：车按线路速度开过去，不在那里起步第二次。
   *
   * <p>A→W→B 各 100 格、限速 10、加减速 1：W 是 PASS 时一次起步，B 在 25 秒到（10 秒加速 + 150 格匀速）。 W 若是区间停车点（停 0 秒），车要在 W
   * 前刹停再重新起步：A→W 加速 50 格、制动 50 格共 20 秒，W→B 再 15 秒，B 在 35 秒到。
   */
  @Test
  void passWaypointsDoNotSplitTheRun() {
    RailGraph graph =
        TimetableTestFixtures.chain(
            List.of("OP:S:A:1", "OP:A:B:1:001", "OP:S:B:1"),
            List.of(NodeType.STATION, NodeType.WAYPOINT, NodeType.STATION),
            new int[] {100, 100},
            new double[] {10.0, 10.0});
    RouteDefinition route =
        TimetableTestFixtures.route("R1", List.of("OP:S:A:1", "OP:A:B:1:001", "OP:S:B:1"));
    RunTimeModel model = curve(StopApproach.Rule.disabled(), 0);
    TimetableTimingCalculator calculator = new TimetableTimingCalculator();

    TimetableTimingCalculator.TimingResult passing =
        calculator.compute(
            graph,
            model,
            route,
            stopsWithPassTypes(
                RouteStopPassType.STOP, RouteStopPassType.PASS, RouteStopPassType.TERMINATE),
            Duration.ZERO);
    TimetableTimingCalculator.TimingResult stopping =
        calculator.compute(
            graph,
            model,
            route,
            List.of(
                stop(0, RouteStopPassType.STOP, 20),
                stop(1, RouteStopPassType.STOP, 0),
                stop(2, RouteStopPassType.TERMINATE, 20)),
            Duration.ZERO);

    assertEquals(15, passing.stops().get(1).arrivalOffsetSeconds(), "W 按线路速度通过");
    assertEquals(15, passing.stops().get(1).departureOffsetSeconds());
    assertEquals(25, passing.stops().get(2).arrivalOffsetSeconds());
    assertEquals(20, stopping.stops().get(1).arrivalOffsetSeconds(), "W 前刹停到 0");
    assertEquals(35, stopping.stops().get(2).arrivalOffsetSeconds(), "W 之后重新起步");
  }

  /** 区间停车点由调度层在节点处刹停，终点速度为 0：100 格、限速 10、加减速 1，加速 50 格、制动 50 格，共 20 秒。 */
  @Test
  void waypointStopBrakesToStandstill() {
    RailGraph graph =
        TimetableTestFixtures.chain(
            List.of("OP:S:A:1", "OP:A:B:1:001"),
            List.of(NodeType.STATION, NodeType.WAYPOINT),
            new int[] {100},
            new double[] {10.0});
    RouteDefinition route = TimetableTestFixtures.route("R1", List.of("OP:S:A:1", "OP:A:B:1:001"));

    TimetableTimingCalculator.TimingResult result =
        new TimetableTimingCalculator()
            .compute(
                graph,
                curve(StopApproach.Rule.disabled(), 0),
                route,
                TimetableTestFixtures.stops(ROUTE, 2, 20),
                Duration.ZERO);

    assertEquals(20, result.stops().get(1).arrivalOffsetSeconds());
  }

  /**
   * 停站开销只加在车站：AutoStation 居中刹停与开门延迟只在车站发生，区间停车点与车库没有这一段。
   *
   * <p>折返（终到停站）同一套规则：车站终到 = dwell + 开销，车库终到只有 dwell。
   */
  @Test
  void stationStopOverheadAppliesToStationsOnly() {
    List<String> nodes = List.of("OP:S:A:1", "OP:S:B:1", "OP:A:C:1:001", "OP:D:DEP:1");
    RailGraph graph =
        TimetableTestFixtures.chain(
            nodes,
            List.of(NodeType.STATION, NodeType.STATION, NodeType.WAYPOINT, NodeType.DEPOT),
            new int[] {100, 100, 100},
            new double[] {10.0, 10.0, 10.0});
    RouteDefinition route = TimetableTestFixtures.route("R1", nodes);
    List<RouteStop> stops =
        List.of(
            stop(0, RouteStopPassType.STOP, 20),
            stop(1, RouteStopPassType.STOP, 20),
            stop(2, RouteStopPassType.STOP, 10),
            stop(3, RouteStopPassType.TERMINATE, 5));
    RunTimeModel model =
        withOverhead(RunTimeModel.perEdge(RailTravelTimeModels.constantSpeed(10.0)), 4);

    TimetableTimingCalculator.TimingResult result =
        new TimetableTimingCalculator().compute(graph, model, route, stops, Duration.ZERO);

    assertEquals(34, result.stops().get(1).departureOffsetSeconds(), "车站：到达 10 + dwell 20 + 开销 4");
    assertEquals(44, result.stops().get(2).arrivalOffsetSeconds());
    assertEquals(54, result.stops().get(2).departureOffsetSeconds(), "区间停车点：只有 dwell 10");
    assertEquals(
        5,
        TimetableTimingCalculator.terminalStopSeconds(graph, model, route, stops, 0),
        "车库终到不加开销");
    RouteDefinition toStation = TimetableTestFixtures.route("R2", List.of("OP:S:A:1", "OP:S:B:1"));
    assertEquals(
        24,
        TimetableTimingCalculator.terminalStopSeconds(
            graph, model, toStation, TimetableTestFixtures.stops(ROUTE, 2, 20), 0),
        "车站终到：dwell 20 + 开销 4");
  }

  /** 终到停站按 route 自己的 dwell；没配 dwell 用兜底值；末站 PASS 不停站，折返为 0。 */
  @Test
  void terminalStopSecondsFollowTheRouteDefinition() {
    RailGraph graph =
        TimetableTestFixtures.chain(
            List.of("OP:S:A:1", "OP:S:B:1"), new int[] {100}, new double[] {10.0});
    RouteDefinition route = TimetableTestFixtures.route("R1", List.of("OP:S:A:1", "OP:S:B:1"));
    RunTimeModel model = RunTimeModel.perEdge(RailTravelTimeModels.constantSpeed(10.0));

    assertEquals(
        30,
        TimetableTimingCalculator.terminalStopSeconds(
            graph, model, route, TimetableTestFixtures.stops(ROUTE, 2, 30), 99));
    assertEquals(
        25,
        TimetableTimingCalculator.terminalStopSeconds(
            graph, model, route, TimetableTestFixtures.stops(ROUTE, 2, null), 25),
        "没配 dwell 走 --dwell 兜底");
    assertEquals(
        0,
        TimetableTimingCalculator.terminalStopSeconds(
            graph,
            model,
            route,
            List.of(stop(0, RouteStopPassType.STOP, null), stop(1, RouteStopPassType.PASS, null)),
            40),
        "末站 PASS 不停站");
  }

  /** 生产用的走行模型：给定进站规则与停站开销，加减速都取 1，便于手算。 */
  private static RunTimeModel curve(StopApproach.Rule approach, int overhead) {
    return new RunCurveModel(
        new RunCurveModel.Settings(
            new RunCurveModel.MotionParams(1.0, 1.0), 8.0, approach, overhead),
        null);
  }

  /** 在任意走行模型上叠加车站停站开销。 */
  private static RunTimeModel withOverhead(RunTimeModel base, int overhead) {
    return new RunTimeModel() {
      @Override
      public Optional<double[]> nodeTimes(RailGraph graph, Run run) {
        return base.nodeTimes(graph, run);
      }

      @Override
      public int stationStopOverheadSeconds() {
        return overhead;
      }
    };
  }

  private static List<RouteStop> stopsWithPassTypes(RouteStopPassType... passTypes) {
    return IntStream.range(0, passTypes.length).mapToObj(i -> stop(i, passTypes[i], 20)).toList();
  }

  private static RouteStop stop(int sequence, RouteStopPassType passType, Integer dwellSeconds) {
    return new RouteStop(
        ROUTE,
        sequence,
        Optional.empty(),
        Optional.empty(),
        Optional.ofNullable(dwellSeconds),
        passType,
        Optional.empty());
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
