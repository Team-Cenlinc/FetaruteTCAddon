package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.model.RunTimeModel;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailEdge;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
import org.junit.jupiter.api.Test;

/**
 * 闭塞时间：后车最晚何时要用到一段资源、前车最早何时放出来，按运行时的跟车规则算。
 *
 * <p>链 A–B–C–D–E 每段 100 格、10 格/秒（逐边相加的时分，每段 10 秒）。跟车规则：减速度 1 格/秒²、授权余量 20 格、车尾后多保留 1 条边、tick 1 秒，
 * 两条交路的车都长 30 格（比一条边短，车身占一条边）。制动距离加余量：10 格/秒时 50 + 20 = 70 格，静止时 20 格。窗口至少到车头所在这条边的末端。
 *
 * <p>慢车每站停 30 秒：A 发 0、B 10/40、C 50/80、D 90/120、E 到 130。快车只停 A、E：B 10、C 20、D 30、E 40。
 */
class BlockingTimesTest {

  private static final String A = "OP:S:A:1";
  private static final String B = "OP:S:B:1";
  private static final String C = "OP:S:C:1";
  private static final String D = "OP:S:D:1";
  private static final String E = "OP:S:E:1";
  private static final UUID LOC = TimetableTestFixtures.routeId("LOC");
  private static final UUID RAP = TimetableTestFixtures.routeId("RAP");
  private static final TimetableBuildOptions.Following RULES =
      new TimetableBuildOptions.Following(1.0D, 20.0D, 1, 1.0D, Map.of(LOC, 30L, RAP, 30L));

  private final RailGraph chain =
      TimetableTestFixtures.chain(
          List.of(A, B, C, D, E),
          new int[] {100, 100, 100, 100},
          new double[] {10.0, 10.0, 10.0, 10.0});
  private final TimetableConflictChecker.GraphIndex index =
      TimetableConflictChecker.GraphIndex.of(chain);

  /**
   * 前车身后的资源要等车头走过记进度的节点、那段落到车身一条边加尾部保护一条边之外才放：点 k 要等第一个下标不小于 k+3 的进度节点。
   *
   * <p>慢车每站都是交路节点：B 等到 E（130）、A–B 那条边等到 D（90），各加一个 tick。快车交路节点只有 A、E，途中的车站不记进度： 路上的一切都等到 E（40）才放。
   */
  @Test
  void theLeaderReleasesOnlyWhenItsHeadPassesAProgressNodeFarEnoughAhead() {
    BlockingTimes local =
        BlockingTimes.of(profile(chain, "LOC", List.of(A, B, C, D, E), 30), index, RULES);
    BlockingTimes rapid = BlockingTimes.of(profile(chain, "RAP", List.of(A, E), 0), index, RULES);

    assertEquals(OptionalInt.of(91), local.release(edge(A, B)));
    assertEquals(OptionalInt.of(131), local.release(platform(B)));
    assertEquals(OptionalInt.of(131), local.release(platform(D)), "走完也够不着就按终点到达");
    assertEquals(OptionalInt.of(41), rapid.release(edge(A, B)));
    assertEquals(OptionalInt.of(41), rapid.release(platform(C)));
  }

  /** 不在交路里的路径点与道岔也记进度（与运行时同一判据）：B、D 是路径点、C 是道岔时，快车车头过 D（30 秒）就把 A–B 那条边放出来， 不必等到终点 E。 */
  @Test
  void aWaypointOrSwitcherOnThePathAlsoAdvancesTheRearGuard() {
    RailGraph tracked =
        TimetableTestFixtures.chain(
            List.of(A, B, C, D, E),
            List.of(
                NodeType.STATION,
                NodeType.WAYPOINT,
                NodeType.SWITCHER,
                NodeType.WAYPOINT,
                NodeType.STATION),
            new int[] {100, 100, 100, 100},
            new double[] {10.0, 10.0, 10.0, 10.0});
    BlockingTimes rapid =
        BlockingTimes.of(
            profile(tracked, "RAP", List.of(A, E), 0),
            TimetableConflictChecker.GraphIndex.of(tracked),
            RULES);

    assertEquals(OptionalInt.of(31), rapid.release(edge(A, B)));
    assertEquals(OptionalInt.of(41), rapid.release(edge(B, C)));
  }

  /**
   * 车身按车长往回量：车长 150 格比一条边长，车尾落在第二条边上，连同尾部保护共留三条边。慢车 A–B 那条边要等车头到 E（130）才放， 车长 30 时到 D（90）就放了。
   * 车长恰好等于一条边（100）时车身仍是一条边。
   */
  @Test
  void theBodyIsMeasuredBackByTheTrainLength() {
    TimetableConflictChecker.RouteProfile local = profile(chain, "LOC", List.of(A, B, C, D, E), 30);

    assertEquals(
        OptionalInt.of(131),
        BlockingTimes.of(local, index, RULES.withTrainLengths(Map.of(LOC, 150L)))
            .release(edge(A, B)));
    assertEquals(
        OptionalInt.of(91),
        BlockingTimes.of(local, index, RULES.withTrainLengths(Map.of(LOC, 100L)))
            .release(edge(A, B)));
  }

  /** 车长未知时只有"要用"没有"放出"：它作为前车量不出间隔，作为后车照常。 */
  @Test
  void anUnknownTrainLengthGivesNoReleaseAndNoLeadBehindIt() {
    TimetableBuildOptions.Following rapidOnly = RULES.withTrainLengths(Map.of(RAP, 30L));
    BlockingTimes local =
        BlockingTimes.of(profile(chain, "LOC", List.of(A, B, C, D, E), 30), index, rapidOnly);
    BlockingTimes rapid =
        BlockingTimes.of(profile(chain, "RAP", List.of(A, E), 0), index, rapidOnly);
    List<String> keys = List.of(edge(A, B), platform(B));

    assertEquals(OptionalInt.empty(), local.release(edge(A, B)));
    assertEquals(OptionalInt.of(0), local.need(edge(A, B)));
    assertEquals(Optional.empty(), BlockingTimes.lead(local, rapid, keys));
    assertTrue(BlockingTimes.lead(rapid, local, keys).isPresent());
  }

  /**
   * 后车：起步时窗口只有余量、至少到首边末端；跑起来后前沿在车头前 70 格、至少到下一条边末端；进停车站时封顶到站。
   *
   * <p>快车从 A 起步时就要 B；到 B（10 秒）时前沿到 C 末端，C 在 0–10 秒之间按时间插值到 10；D 20、E 30。
   */
  @Test
  void aMovingFollowerNeedsItsBrakingDistanceAheadAndAStartingOneOnlyTheMargin() {
    BlockingTimes rapid = BlockingTimes.of(profile(chain, "RAP", List.of(A, E), 0), index, RULES);

    assertEquals(OptionalInt.of(0), rapid.need(platform(B)));
    assertEquals(OptionalInt.of(10), rapid.need(platform(C)));
    assertEquals(OptionalInt.of(20), rapid.need(platform(D)));
  }

  /** 停在站里的后车要到起步时才要站后面那一段：慢车停 C（50–80），D 在 80 才要，不是到站的 50。 */
  @Test
  void aStoppedFollowerNeedsWhatLiesAheadOnlyWhenItDeparts() {
    BlockingTimes local =
        BlockingTimes.of(profile(chain, "LOC", List.of(A, B, C, D, E), 30), index, RULES);

    assertEquals(OptionalInt.of(40), local.need(platform(C)));
    assertEquals(OptionalInt.of(80), local.need(platform(D)));
    assertEquals(OptionalInt.of(80), local.need(edge(C, D)));
  }

  /**
   * 两车最小间隔：后车至少晚发 {@code max(前车放出 − 后车要用)}。
   *
   * <p>快车跟慢车：慢车到 E 才放 B（131），快车起步就要 B（0），131 秒。慢车跟快车：快车到 E 才放 A–B（41），慢车起步就要（0），41 秒。
   */
  @Test
  void theLeadIsTheLatestReleaseMinusTheEarliestNeed() {
    BlockingTimes local =
        BlockingTimes.of(profile(chain, "LOC", List.of(A, B, C, D, E), 30), index, RULES);
    BlockingTimes rapid = BlockingTimes.of(profile(chain, "RAP", List.of(A, E), 0), index, RULES);
    List<String> keys =
        List.of(edge(A, B), platform(B), edge(B, C), platform(C), edge(C, D), platform(D));

    assertEquals(Optional.of(131), BlockingTimes.lead(local, rapid, keys));
    assertEquals(Optional.of(41), BlockingTimes.lead(rapid, local, keys));
    assertEquals(Optional.empty(), BlockingTimes.lead(local, rapid, List.of("edge:none")));
  }

  /**
   * 有轨迹时按逐点速度算制动距离：轨迹前 300 格 15 格/秒（制动距离 112.5 + 余量 20，前沿在车头前 132.5 格）， D 在车头到 167.5 格（11.2
   * 秒）时就要；没有轨迹时按每条边的平均速度 10 格/秒只算 70 格，D 要到 20 秒。
   */
  @Test
  void aTrajectoryUsesThePointSpeedForTheBrakingDistance() {
    TimetableConflictChecker.RouteProfile rapid = profile(chain, "RAP", List.of(A, E), 0);
    BlockingTimes.Trajectories fastThenSlow =
        run -> {
          int samples = 401;
          double[] distance = new double[samples];
          double[] seconds = new double[samples];
          double[] speed = new double[samples];
          for (int i = 0; i < samples; i++) {
            distance[i] = i;
            speed[i] = i < 300 ? 15.0D : 5.0D;
            seconds[i] = i <= 300 ? i / 15.0D : 20.0D + (i - 300) / 5.0D;
          }
          return Optional.of(new RunTimeModel.Trajectory(distance, seconds, speed));
        };

    assertEquals(OptionalInt.of(20), BlockingTimes.of(rapid, index, RULES).need(platform(D)));
    assertEquals(
        OptionalInt.of(11), BlockingTimes.of(rapid, index, RULES, fastThenSlow).need(platform(D)));
  }

  private static TimetableConflictChecker.RouteProfile profile(
      RailGraph network, String code, List<String> waypoints, int dwell) {
    UUID id = TimetableTestFixtures.routeId(code);
    RouteDefinition route = TimetableTestFixtures.route(code, waypoints);
    List<RouteStop> stops = TimetableTestFixtures.stops(id, waypoints.size(), dwell);
    TimetableTimingCalculator.TimingResult timing =
        new TimetableTimingCalculator()
            .compute(
                network, TimetableTestFixtures.perEdgeSpeedModel(), route, stops, Duration.ZERO);
    assertTrue(timing.ok(), () -> code + ": " + timing.failure());
    return new TimetableConflictChecker.RouteProfile(
        id,
        code,
        timing.stops(),
        timing.segments(),
        TimetableConflictChecker.platformsOf(timing.stops(), stops, route.waypoints()));
  }

  private String edge(String from, String to) {
    RailEdge edge =
        chain.edges().stream()
            .filter(
                candidate ->
                    candidate
                        .id()
                        .equals(
                            org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId.undirected(
                                NodeId.of(from), NodeId.of(to))))
            .findFirst()
            .orElseThrow();
    return TimetableConflictChecker.edgeKey(edge);
  }

  private static String platform(String node) {
    return "platform:" + node;
  }
}
