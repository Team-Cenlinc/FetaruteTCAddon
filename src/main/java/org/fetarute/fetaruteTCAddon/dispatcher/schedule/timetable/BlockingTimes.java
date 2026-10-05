package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.company.model.RouteStopPassType;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.model.RunTimeModel;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailEdge;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.RearGuardWindow;

/**
 * 闭塞时间：按运行时同向跟车的规则，算一条交路沿途每个互斥资源（区间、道岔、具体股道）的两个时刻—— 作为后车，最晚什么时候要拿到它才不减速；作为前车，最早什么时候把它放出来。
 *
 * <h2>为什么不能只看占用区间</h2>
 *
 * <p>冲突模型按资源的占用区间 {@code [进入, 离开]} 判先后，再在相邻占用之间留一个固定的 separation。运行时放行不是这样：
 *
 * <ul>
 *   <li>后车要不减速，得在车头前方"制动距离 + 授权余量"之内都拿到资源；朝停车站开时只要到停车点为止，起步时才往停车点之后要。
 *   <li>前车身后的资源，要等车头走过下一个能记进度的节点（交路节点、路径点或道岔）、那段资源落到车身与尾部保护之外，下一个调度 tick 才释放。
 * </ul>
 *
 * 两车之间真正需要的间隔因此随地点变，一个全网通用的裕量要么在这里不够、要么在那里白占。这里把同一套规则搬过来逐个资源算， 两条交路 A 在前、B 在后时，B 至少要比 A 晚发 {@code
 * max(A 放出 − B 要用)} 秒才一路不受阻（见 {@link #lead}）。
 *
 * <p>尾部保护的范围与运行时共用 {@link RearGuardWindow}，车长取自出车编组（{@link
 * TimetableBuildOptions.Following#trainLength}）。 车长未知的交路只有"要用"、没有"放出"：作为前车量不出间隔，调用方按占用区间算。
 */
final class BlockingTimes {

  /**
   * 一段走行（停车点到停车点、从静止起步）的逐点轨迹，与编表算时分用的是同一条曲线（{@link RunTimeModel#trajectory}）。
   *
   * <p>"要用"时刻取决于每一处的速度：进站前列车先减到进站限速，制动距离随之变短；只按节点到达秒数推平均速度， 长边上会把这段减速当成线路速度，前沿算得太远。
   */
  @FunctionalInterface
  interface Trajectories {

    /** 没有轨迹：按节点到达秒数推每条边的平均速度。 */
    Trajectories NONE = run -> Optional.empty();

    Optional<RunTimeModel.Trajectory> of(RunTimeModel.Run run);

    /**
     * 这条交路用的轨迹。各交路的走行模型不同时（多车型混跑：每个车型一条曲线）按交路取；默认所有交路同一份。
     *
     * @param routeId 交路（编表内部可以是车型变体）
     */
    default Trajectories forRoute(UUID routeId) {
      return this;
    }
  }

  /** 作为后车，最晚什么时候要拿到；相对这条交路的发车。 */
  private final Map<String, Integer> need;

  /** 作为前车，最早什么时候放出；车长未知时为空。 */
  private final Map<String, Integer> release;

  private BlockingTimes(Map<String, Integer> need, Map<String, Integer> release) {
    this.need = Map.copyOf(need);
    this.release = Map.copyOf(release);
  }

  /** 作为后车，最晚什么时候要拿到这个资源（相对发车）；路过不到的资源为空。 */
  OptionalInt need(String key) {
    Integer seconds = need.get(key);
    return seconds == null ? OptionalInt.empty() : OptionalInt.of(seconds);
  }

  /** 作为前车，最早什么时候放出这个资源（相对发车）；路过不到或车长未知时为空。 */
  OptionalInt release(String key) {
    Integer seconds = release.get(key);
    return seconds == null ? OptionalInt.empty() : OptionalInt.of(seconds);
  }

  /**
   * 两条交路在一串共用资源上：后车至少要比前车晚发多少秒才一路不受阻。
   *
   * @param ahead 前车
   * @param behind 后车
   * @param keys 共用的资源
   * @return 最小发车间隔；没有一个资源两边都有、或前车车长未知时为空
   */
  static Optional<Integer> lead(BlockingTimes ahead, BlockingTimes behind, List<String> keys) {
    Integer lead = null;
    for (String key : keys) {
      Integer first = ahead.release.get(key);
      Integer second = behind.need.get(key);
      if (first != null && second != null) {
        int gap = first - second;
        lead = lead == null ? gap : Math.max(lead, gap);
      }
    }
    return Optional.ofNullable(lead);
  }

  /**
   * 同 {@link #of(TimetableConflictChecker.RouteProfile, TimetableConflictChecker.GraphIndex,
   * TimetableBuildOptions.Following, Trajectories)}，没有轨迹。
   */
  static BlockingTimes of(
      TimetableConflictChecker.RouteProfile profile,
      TimetableConflictChecker.GraphIndex index,
      TimetableBuildOptions.Following rules) {
    return of(profile, index, rules, Trajectories.NONE);
  }

  /**
   * 按运行时规则算一条交路的闭塞时间。
   *
   * @param profile 交路投影
   * @param index 图索引（判节点是道岔、路径点还是车站）
   * @param rules 跟车规则与各交路车长
   * @param trajectories 停车点之间的逐点轨迹；拿不到的段按平均速度
   */
  static BlockingTimes of(
      TimetableConflictChecker.RouteProfile profile,
      TimetableConflictChecker.GraphIndex index,
      TimetableBuildOptions.Following rules,
      Trajectories trajectories) {
    Path path = Path.of(profile);
    int points = path.nodes.size();
    if (points < 2) {
      return new BlockingTimes(Map.of(), Map.of());
    }
    Map<NodeId, NodeType> types = index.nodeTypes();
    Front front =
        needTimes(
            path,
            rules,
            (trajectories == null ? Trajectories.NONE : trajectories).forRoute(profile.routeId()));
    OptionalLong length = rules.trainLength(profile.routeId());
    double[] release =
        length.isPresent() ? releaseTimes(path, types, rules, length.getAsLong()) : null;
    Map<String, Integer> needOut = new HashMap<>();
    Map<String, Integer> releaseOut = new HashMap<>();
    for (int k = 0; k + 1 < points; k++) {
      // 边 k 从点 k 到点 k+1：前沿越过它的起点、伸进这条边时才要用（停在点 k 的车要到起步才要）；
      // 尾部保护按整条边往回量，边 k 与点 k 同时落到保护之外。
      String key = TimetableConflictChecker.edgeKey(path.edges.get(k));
      put(needOut, releaseOut, key, front.edges[k], release, k);
    }
    for (int k = 0; k < points; k++) {
      NodeId node = path.nodes.get(k);
      NodeType type = types.get(node);
      if (type == NodeType.SWITCHER) {
        put(
            needOut,
            releaseOut,
            TimetableConflictChecker.junctionKey(node),
            front.points[k],
            release,
            k);
      } else if (type == NodeType.STATION) {
        put(needOut, releaseOut, "platform:" + node.value(), front.points[k], release, k);
      }
    }
    return new BlockingTimes(needOut, releaseOut);
  }

  /** 同一个资源路过两次时取第一次。 */
  private static void put(
      Map<String, Integer> needOut,
      Map<String, Integer> releaseOut,
      String key,
      double need,
      double[] release,
      int k) {
    if (needOut.putIfAbsent(key, (int) Math.floor(need)) == null && release != null) {
      releaseOut.put(key, (int) Math.ceil(release[k]));
    }
  }

  /**
   * 作为后车，每个点最晚什么时候要拿到。
   *
   * <p>运行时的硬授权窗口：制动距离 {@code v²/2a} 加授权余量，至少到车头所在这条边的末端，封顶到前方第一个计划停车点；停在站里时速度为零，
   * 窗口只剩余量，而且要到起步时才用得上。这里按停车点之间一段一段走：有轨迹时逐个采样点算窗口前沿，没有时按每条边两端的平均速度算、
   * 边内按时间线性插值。前沿第一次越过某个点的时刻就是那个点的"要用"时刻。
   */
  private static Front needTimes(
      Path path, TimetableBuildOptions.Following rules, Trajectories trajectories) {
    int points = path.nodes.size();
    Front front = new Front(path);
    int from = 0;
    for (int to = 1; to < points; to++) {
      if (!path.stop[to] && to < points - 1) {
        continue;
      }
      Optional<RunTimeModel.Trajectory> trajectory =
          trajectories.of(
              new RunTimeModel.Run(
                  path.nodes.subList(from, to + 1), path.edges.subList(from, to), 0.0D, true));
      if (trajectory.isPresent() && trajectory.get().samples() >= 2) {
        sampled(path, from, to, trajectory.get(), rules, front);
      } else {
        for (int k = from; k < to; k++) {
          edgeAverage(path, k, rules, front);
        }
      }
      from = to;
    }
    front.finish();
    return front;
  }

  /** 窗口前沿的推进：前沿只往前走。点在前沿到达它时要用；边在前沿越过它的起点、伸进这条边时才要用——停在站里的车前沿封顶在站上， 站后那条边要到起步时才要。 */
  private static final class Front {
    private final Path path;
    final double[] points;
    final double[] edges;
    private int covered;
    private int passed;
    private double position = Double.NEGATIVE_INFINITY;
    private double at = Double.NaN;

    Front(Path path) {
      this.path = path;
      int n = path.nodes.size();
      this.points = new double[n];
      this.edges = new double[Math.max(0, n - 1)];
      java.util.Arrays.fill(points, Double.NaN);
      java.util.Arrays.fill(edges, Double.NaN);
      points[0] = path.departure[0];
    }

    /** 前沿停在原处直到 {@code time}：停站期间不往前要，起步那一刻才跳。 */
    void hold(double time) {
      if (!Double.isInfinite(position)) {
        at = Double.isNaN(at) ? time : Math.max(at, time);
      }
    }

    /** 时刻 {@code time} 前沿到了 {@code reach}；上一次记录到这一次之间按时间线性插值。前沿没往前走时只把时刻推到现在。 */
    void advance(double time, double reach) {
      if (reach <= position) {
        hold(time);
        return;
      }
      double fromPosition = Double.isInfinite(position) ? reach : position;
      double fromTime = Double.isNaN(at) ? time : at;
      while (covered + 1 < path.nodes.size() && path.x[covered + 1] <= reach + 1.0E-9) {
        covered++;
        points[covered] = interpolate(path.x[covered], fromPosition, fromTime, reach, time);
      }
      while (passed < edges.length && path.x[passed] < reach - 1.0E-9) {
        edges[passed] = interpolate(path.x[passed], fromPosition, fromTime, reach, time);
        passed++;
      }
      position = reach;
      at = time;
    }

    private static double interpolate(
        double target, double fromPosition, double fromTime, double reach, double time) {
      if (target <= fromPosition + 1.0E-9 || reach - fromPosition < 1.0E-9) {
        return fromTime;
      }
      return fromTime + (target - fromPosition) / (reach - fromPosition) * (time - fromTime);
    }

    /** 窗口拿不到的：点最晚车头到达时、边最晚车头驶入时一定要有。 */
    void finish() {
      for (int k = 1; k < points.length; k++) {
        if (Double.isNaN(points[k]) || points[k] > path.arrival[k]) {
          points[k] = path.arrival[k];
        }
      }
      for (int k = 0; k < edges.length; k++) {
        if (Double.isNaN(edges[k]) || edges[k] > path.departure[k]) {
          edges[k] = path.departure[k];
        }
      }
    }
  }

  /** 按轨迹逐个采样点推前沿；轨迹里程与秒数按这一段在投影里的两端缩放，与节点时刻对齐。 */
  private static void sampled(
      Path path,
      int from,
      int to,
      RunTimeModel.Trajectory trajectory,
      TimetableBuildOptions.Following rules,
      Front front) {
    int last = trajectory.samples() - 1;
    double[] distances = trajectory.distance();
    double[] seconds = trajectory.seconds();
    double[] speeds = trajectory.speed();
    double length = path.x[to] - path.x[from];
    double duration = path.arrival[to] - path.departure[from];
    double distanceScale = distances[last] > 0.0D ? length / distances[last] : 0.0D;
    double timeScale = seconds[last] > 0.0D ? duration / seconds[last] : 0.0D;
    double cap = path.x[to];
    int edge = from;
    for (int s = 0; s <= last; s++) {
      double x = path.x[from] + distances[s] * distanceScale;
      double time = path.departure[from] + seconds[s] * timeScale;
      while (edge + 1 < to && path.x[edge + 1] <= x) {
        edge++;
      }
      if (s == 0) {
        front.hold(time);
      }
      double reach = Math.max(path.x[edge + 1], x + reach(speeds[s], rules));
      front.advance(time, Math.min(cap, reach));
    }
  }

  /** 没有轨迹的一条边：两端按平均速度算前沿（停车点起步时速度为零），进停车站时封顶到站。 */
  private static void edgeAverage(
      Path path, int k, TimetableBuildOptions.Following rules, Front front) {
    int points = path.nodes.size();
    double cap = path.x[path.nextStopAfter(k)];
    double speed = path.speed(k);
    double startSpeed = path.stop[k] ? 0.0D : speed;
    front.hold(path.departure[k]);
    front.advance(
        path.departure[k],
        Math.min(cap, Math.max(path.x[k + 1], path.x[k] + reach(startSpeed, rules))));
    int after = Math.min(points - 1, k + 2);
    double end =
        path.stop[k + 1]
            ? path.x[k + 1]
            : Math.min(cap, Math.max(path.x[after], path.x[k + 1] + reach(speed, rules)));
    front.advance(path.arrival[k + 1], end);
  }

  /** 制动距离加授权余量。 */
  private static double reach(double speed, TimetableBuildOptions.Following rules) {
    return speed * speed / (2.0D * rules.decelBps2()) + rules.marginBlocks();
  }

  /**
   * 作为前车，每个点（以及从它出发的那条边）最早什么时候放出。
   *
   * <p>运行时在车头走到交路节点、或经过路径点与道岔（{@link RearGuardWindow#tracksIntermediateNode}）时记一次进度，
   * 尾部保护从那个节点往回量：先覆盖车长，再多留 {@code rearGuardEdges} 条边（{@link RearGuardWindow#retainedEdges}），
   * 这之外的资源在下一个调度 tick 释放。所以点 k 在车头第一次到达"往回量落不到 k"的进度节点时放出，再加一个 tick；走完交路也落不到的， 按终点到达再加一个 tick 算。
   */
  private static double[] releaseTimes(
      Path path,
      Map<NodeId, NodeType> types,
      TimetableBuildOptions.Following rules,
      long trainLengthBlocks) {
    int points = path.nodes.size();
    double[] release = new double[points];
    double tick = rules.tickSeconds();
    int released = 0;
    for (int head = 1; head < points && released < points; head++) {
      if (!path.routeNode[head]
          && !RearGuardWindow.tracksIntermediateNode(types.get(path.nodes.get(head)))) {
        continue;
      }
      int at = head;
      int retained =
          RearGuardWindow.retainedEdges(
              at,
              back -> path.edges.get(at - 1 - back).lengthBlocks(),
              trainLengthBlocks,
              rules.rearGuardEdges());
      for (; released < at - retained; released++) {
        release[released] = path.arrival[at] + tick;
      }
    }
    for (; released < points; released++) {
      release[released] = path.arrival[points - 1] + tick;
    }
    return release;
  }

  /** 交路走过的点与边：相邻两段在停车站处共用一个点，那个点的到达与发车不同。 */
  private static final class Path {
    final List<NodeId> nodes = new ArrayList<>();
    final List<RailEdge> edges = new ArrayList<>();
    final double[] x;
    final double[] arrival;
    final double[] departure;
    final boolean[] routeNode;
    final boolean[] stop;

    static Path of(TimetableConflictChecker.RouteProfile profile) {
      return new Path(profile);
    }

    private Path(TimetableConflictChecker.RouteProfile profile) {
      List<Double> x = new ArrayList<>();
      List<Double> arrival = new ArrayList<>();
      List<Double> departure = new ArrayList<>();
      List<Boolean> routeNode = new ArrayList<>();
      List<Boolean> stop = new ArrayList<>();
      List<TimetableStop> stops = profile.stops();
      for (TimetableTimingCalculator.SegmentTiming segment : profile.segments()) {
        List<NodeId> segmentNodes = segment.nodes();
        if (segmentNodes.isEmpty()) {
          continue;
        }
        if (nodes.isEmpty()) {
          nodes.add(segmentNodes.get(0));
          x.add(0.0D);
          arrival.add((double) segment.nodeOffsets().get(0));
          departure.add((double) segment.nodeOffsets().get(0));
          routeNode.add(true);
          stop.add(true);
        } else {
          // 与上一段的末点是同一个点：到达已记，这里记发车（停站之后）。
          departure.set(departure.size() - 1, (double) segment.nodeOffsets().get(0));
        }
        for (int k = 0; k < segment.edges().size(); k++) {
          RailEdge edge = segment.edges().get(k);
          edges.add(edge);
          nodes.add(segmentNodes.get(k + 1));
          x.add(x.get(x.size() - 1) + Math.max(0, edge.lengthBlocks()));
          double at = segment.nodeOffsets().get(k + 1);
          arrival.add(at);
          departure.add(at);
          boolean end = k + 1 == segment.edges().size();
          routeNode.add(end);
          stop.add(end && stopsAt(stops, segment.toStop()));
        }
      }
      int n = nodes.size();
      this.x = new double[n];
      this.arrival = new double[n];
      this.departure = new double[n];
      this.routeNode = new boolean[n];
      this.stop = new boolean[n];
      for (int k = 0; k < n; k++) {
        this.x[k] = x.get(k);
        this.arrival[k] = arrival.get(k);
        this.departure[k] = departure.get(k);
        this.routeNode[k] = routeNode.get(k);
        this.stop[k] = stop.get(k);
      }
    }

    private static boolean stopsAt(List<TimetableStop> stops, int index) {
      return index >= 0
          && index < stops.size()
          && stops.get(index).passType() != RouteStopPassType.PASS;
    }

    /** 车头在边 k 上时前方第一个计划停车点（含 k+1）；没有就是最后一个点。 */
    int nextStopAfter(int k) {
      for (int p = k + 1; p < nodes.size(); p++) {
        if (stop[p]) {
          return p;
        }
      }
      return nodes.size() - 1;
    }

    /** 边 k 上的平均速度。 */
    double speed(int k) {
      double seconds = arrival[k + 1] - departure[k];
      double length = x[k + 1] - x[k];
      return seconds > 0.0D ? length / seconds : length;
    }
  }
}
