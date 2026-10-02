package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.company.model.RouteStopPassType;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.model.RunTimeModel;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.model.StopApproach;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailEdge;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.query.RailGraphPath;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.query.RailGraphPathFinder;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteTerminals;

/**
 * 从路网计算一条 Route 的计划时分。
 *
 * <p>这是「时刻表是造出来的，不是录出来的」这条不变量的落点：输入只有 route 定义、调度图与限速， 没有任何历史跑车记录。同一份网络状态 + 同一份 route
 * 配置，永远得到同一张时分表。
 *
 * <p>计算链路刻意全部复用运行时已有的权威组件，不另起一套平行网络模型：
 *
 * <ul>
 *   <li>路径：{@link RailGraphPathFinder}，与诊断命令、ETA 用的是同一套最短路。
 *   <li>走行：{@link RunTimeModel}（生产上是 {@code RunCurveModel}）。路径点按停车方式切成若干段<b>走行</b>： 每段从停车点静止起步，途中的
 *       PASS 点按线路速度通过，到下一个停车点按进站规则减速——站间的起步与制动正是旧口径（每站满速通过）少算的那部分。
 *   <li>停站：{@link RouteStop#dwellSeconds()}，缺省值由调用方从配置传入；车站停车另加 {@link
 *       RunTimeModel#stationStopOverheadSeconds()}（居中刹停 + 开门延迟，dwell 从开门起算）。
 * </ul>
 *
 * <p>终端折返时间<b>不</b>计入 stop profile：它发生在两趟车之间，属于车辆周转（duty）的范畴， 记在这里会让"这趟车跑多久"和"这辆车多久能再发一趟"混成同一个数。
 * 折返读 {@link #terminalStopSeconds}，与途中停站同一套规则。
 */
public final class TimetableTimingCalculator {

  private final RailGraphPathFinder pathFinder;

  public TimetableTimingCalculator() {
    this(new RailGraphPathFinder());
  }

  public TimetableTimingCalculator(RailGraphPathFinder pathFinder) {
    this.pathFinder = Objects.requireNonNull(pathFinder, "pathFinder");
  }

  /**
   * 计算一条 route 的站间时分档案。
   *
   * @param graph 调度图快照
   * @param runTimeModel 走行时分模型
   * @param route 已解析的交路定义
   * @param stops route 的停靠配置，按 sequence 升序；索引与 {@code route.waypoints()} 对齐
   * @param defaultDwell 未配置 dwell 时使用的停站时长
   * @return 计算结果；任一区段不可达或无法估算时返回失败，并说明是哪一段
   */
  public TimingResult compute(
      RailGraph graph,
      RunTimeModel runTimeModel,
      RouteDefinition route,
      List<RouteStop> stops,
      Duration defaultDwell) {
    Objects.requireNonNull(runTimeModel, "runTimeModel");
    Objects.requireNonNull(route, "route");
    if (graph == null) {
      return TimingResult.failure("缺少调度图快照");
    }
    List<NodeId> waypoints = route.waypoints();
    if (waypoints.size() < 2) {
      return TimingResult.failure("交路至少需要两个节点");
    }
    int dwellFallback = defaultDwell == null ? 0 : (int) Math.max(0L, defaultDwell.toSeconds());

    List<RailGraphPath> legs = new ArrayList<>(waypoints.size() - 1);
    for (int i = 1; i < waypoints.size(); i++) {
      NodeId from = waypoints.get(i - 1);
      NodeId to = waypoints.get(i);
      Optional<RailGraphPath> path =
          pathFinder.shortestPath(graph, from, to, RailGraphPathFinder.Options.shortestDistance());
      if (path.isEmpty()) {
        return TimingResult.failure(
            "区段不可达: #" + (i - 1) + "→#" + i + " " + from.value() + " → " + to.value());
      }
      legs.add(path.get());
    }

    List<TimetableStop> profile = new ArrayList<>(waypoints.size());
    List<SegmentTiming> segments = new ArrayList<>(legs.size());
    profile.add(stopAt(waypoints.get(0), stops, 0, 0, 0));
    int departure = 0;
    int runStart = 0;
    for (int end = 1; end < waypoints.size(); end++) {
      boolean last = end == waypoints.size() - 1;
      if (!last && !stopsAt(stops, end)) {
        continue;
      }
      RunSpan run = RunSpan.of(legs, runStart, end);
      Optional<double[]> times =
          runTimeModel.nodeTimes(
              graph, new RunTimeModel.Run(run.nodes(), run.edges(), 0.0, stopsAt(stops, end)));
      if (times.isEmpty()) {
        return TimingResult.failure(
            "区段时分无法估算（缺限速或长度）: #"
                + runStart
                + "→#"
                + end
                + " "
                + waypoints.get(runStart).value()
                + " → "
                + waypoints.get(end).value());
      }
      int[] offsets = new int[times.get().length];
      for (int j = 0; j < offsets.length; j++) {
        offsets[j] = departure + (int) Math.round(times.get()[j]);
      }
      for (int leg = runStart; leg < end; leg++) {
        int from = run.boundary(leg);
        int to = run.boundary(leg + 1);
        List<Integer> legOffsets = new ArrayList<>(to - from + 1);
        for (int j = from; j <= to; j++) {
          legOffsets.add(offsets[j]);
        }
        segments.add(
            new SegmentTiming(
                leg, leg + 1, legs.get(leg).nodes(), legs.get(leg).edges(), legOffsets));
        if (leg + 1 < end) {
          int passing = offsets[to];
          profile.add(stopAt(waypoints.get(leg + 1), stops, leg + 1, passing, passing));
        }
      }
      int arrival = offsets[offsets.length - 1];
      int stopSeconds =
          last
              ? 0
              : stopSeconds(graph, runTimeModel, waypoints.get(end), stops, end, dwellFallback);
      profile.add(stopAt(waypoints.get(end), stops, end, arrival, arrival + stopSeconds));
      departure = arrival + stopSeconds;
      runStart = end;
    }
    return TimingResult.success(List.copyOf(profile), List.copyOf(segments));
  }

  /**
   * 终到停靠点的停站时长：车按这条 route 到达终点后，多久能再发车。
   *
   * <p>行程时分把末站的停站记作 0（班次到这儿就结束了，它不属于走行时间），{@link TurnaroundTable} 是这个量的读者—— 折返时间不是另立的常数，就是 route
   * 定义里写着的终到 dwell，车站终到再加停站开销：运行时车在开门计时结束后才进入待命。 解析规则与途中停站共用同一个方法，不另开一套。
   *
   * @param graph 调度图，用来判断终点是不是车站；为空时不加停站开销
   * @param runTimeModel 走行时分模型（提供停站开销）
   * @param route 交路定义
   * @param stops route 的停靠配置（按 sequence 升序）
   * @param fallbackSeconds 停靠却没配 dwell 时的兜底值（{@code --dwell}）
   * @return 终到点停站秒数
   */
  public static int terminalStopSeconds(
      RailGraph graph,
      RunTimeModel runTimeModel,
      RouteDefinition route,
      List<RouteStop> stops,
      int fallbackSeconds) {
    Objects.requireNonNull(runTimeModel, "runTimeModel");
    Objects.requireNonNull(route, "route");
    List<NodeId> waypoints = route.waypoints();
    if (waypoints.isEmpty()) {
      return Math.max(0, fallbackSeconds);
    }
    int last = waypoints.size() - 1;
    return stopSeconds(
        graph, runTimeModel, waypoints.get(last), stops, last, Math.max(0, fallbackSeconds));
  }

  /**
   * 停车点的停站时长：dwell，车站再加停站开销。PASS 不停站算 0。
   *
   * <p>停站开销只加在车站上：它由 AutoStation 的居中刹停与开门延迟构成，车库与区间停车点没有这一段。
   */
  private static int stopSeconds(
      RailGraph graph,
      RunTimeModel runTimeModel,
      NodeId node,
      List<RouteStop> stops,
      int index,
      int fallback) {
    if (!stopsAt(stops, index)) {
      return 0;
    }
    int dwell = resolveDwellSeconds(stops, index, fallback);
    boolean station =
        graph != null && StopApproach.targetOf(graph, node).kind() == StopApproach.Kind.STATION;
    return station ? dwell + runTimeModel.stationStopOverheadSeconds() : dwell;
  }

  /** 列车是否在这个路径点停车：只有 PASS 不停；缺停靠配置时按停车处理（与 {@link #resolveDwellSeconds} 的兜底一致）。 */
  private static boolean stopsAt(List<RouteStop> stops, int index) {
    if (stops == null || index < 0 || index >= stops.size() || stops.get(index) == null) {
      return true;
    }
    return stops.get(index).passType() != RouteStopPassType.PASS;
  }

  /**
   * 停站时长：PASS 路径点不停站，永远是 0——兜底值只给"停靠却没配 dwell"的站。
   *
   * <p>否则一条 route 上每个路径点都会被算成一次 {@code --dwell} 长的停站：全程时分被虚增一到三成， 冲突检查里每个路径点还会多出一段假的站台占用。
   */
  private static int resolveDwellSeconds(List<RouteStop> stops, int index, int fallback) {
    if (stops == null || index < 0 || index >= stops.size()) {
      return fallback;
    }
    RouteStop stop = stops.get(index);
    if (stop == null) {
      return fallback;
    }
    if (stop.passType() == RouteStopPassType.PASS) {
      return 0;
    }
    return stop.dwellSeconds().filter(value -> value >= 0).orElse(fallback);
  }

  /**
   * 停靠点：停车方式照抄 route 定义，站码只取车站本体节点（见 {@link RouteTerminals#stationCodeOf}）。
   *
   * <p>停车方式必须在这里记下来：到发时刻相同既可能是通过，也可能是停站 0 秒的 STOP，事后无法从时刻反推。
   */
  private static TimetableStop stopAt(
      NodeId nodeId, List<RouteStop> stops, int index, int arrivalOffset, int departureOffset) {
    RouteStopPassType passType =
        stops != null && index < stops.size() && stops.get(index) != null
            ? stops.get(index).passType()
            : RouteStopPassType.STOP;
    return new TimetableStop(
        index,
        RouteTerminals.stationCodeOf(nodeId.value()),
        Optional.of(nodeId.value()),
        arrivalOffset,
        departureOffset,
        passType);
  }

  /**
   * 一段走行：相邻两个停车点之间的若干路径点区段首尾相接。
   *
   * @param nodes 走行的全部节点
   * @param edges 走行的全部边
   * @param firstLeg 走行里第一个路径点区段的序号
   * @param legStarts 每个路径点区段的起点在 {@code nodes} 里的下标，末项是终点下标
   */
  private record RunSpan(
      List<NodeId> nodes, List<RailEdge> edges, int firstLeg, List<Integer> legStarts) {

    static RunSpan of(List<RailGraphPath> legs, int fromWaypoint, int toWaypoint) {
      List<NodeId> nodes = new ArrayList<>();
      List<RailEdge> edges = new ArrayList<>();
      List<Integer> starts = new ArrayList<>(toWaypoint - fromWaypoint + 1);
      nodes.add(legs.get(fromWaypoint).nodes().get(0));
      for (int leg = fromWaypoint; leg < toWaypoint; leg++) {
        RailGraphPath path = legs.get(leg);
        starts.add(nodes.size() - 1);
        nodes.addAll(path.nodes().subList(1, path.nodes().size()));
        edges.addAll(path.edges());
      }
      starts.add(nodes.size() - 1);
      return new RunSpan(List.copyOf(nodes), List.copyOf(edges), fromWaypoint, List.copyOf(starts));
    }

    /** 第 {@code waypoint} 个路径点在本段走行节点里的下标。 */
    int boundary(int waypoint) {
      return legStarts.get(waypoint - firstLeg);
    }
  }

  /**
   * 一个站间区段的逐边时分：列车沿着哪些边走、几点到达每个节点。
   *
   * <p>这是冲突检查的输入：有了它才能知道两趟车会不会同时占用同一条边、同一个道岔、同一段单线。 站间时分（{@link
   * TimetableStop}）回答"几点到站"，本记录回答"路上每一步在哪"。
   *
   * @param fromStop 区段起点在 route 里的停靠序号
   * @param toStop 区段终点在 route 里的停靠序号
   * @param nodes 实际穿越的节点序列（含两端）
   * @param edges 实际穿越的边序列，{@code edges.size() == nodes.size() - 1}
   * @param nodeOffsets 到达每个节点的时刻（相对 route 首站发车的秒偏移），与 {@code nodes} 对齐
   */
  public record SegmentTiming(
      int fromStop,
      int toStop,
      List<NodeId> nodes,
      List<RailEdge> edges,
      List<Integer> nodeOffsets) {

    public SegmentTiming {
      nodes = nodes == null ? List.of() : List.copyOf(nodes);
      edges = edges == null ? List.of() : List.copyOf(edges);
      nodeOffsets = nodeOffsets == null ? List.of() : List.copyOf(nodeOffsets);
      if (nodes.size() != edges.size() + 1 || nodeOffsets.size() != nodes.size()) {
        throw new IllegalArgumentException("nodes / edges / nodeOffsets 数量不匹配");
      }
    }

    /** 第 k 条边的进入时刻。 */
    public int enterOffset(int k) {
      return nodeOffsets.get(k);
    }

    /** 第 k 条边的离开时刻。 */
    public int exitOffset(int k) {
      return nodeOffsets.get(k + 1);
    }
  }

  /**
   * 计算结果。
   *
   * @param stops 站间时分档案；失败时为空
   * @param segments 逐边时分，与 {@code stops} 两两之间一一对应；失败时为空
   * @param failure 失败原因；成功时为空
   */
  public record TimingResult(
      List<TimetableStop> stops, List<SegmentTiming> segments, Optional<String> failure) {

    public TimingResult {
      stops = stops == null ? List.of() : List.copyOf(stops);
      segments = segments == null ? List.of() : List.copyOf(segments);
      failure = failure == null ? Optional.empty() : failure;
    }

    static TimingResult success(List<TimetableStop> stops, List<SegmentTiming> segments) {
      return new TimingResult(stops, segments, Optional.empty());
    }

    static TimingResult failure(String reason) {
      return new TimingResult(List.of(), List.of(), Optional.ofNullable(reason));
    }

    public boolean ok() {
      return failure.isEmpty() && !stops.isEmpty();
    }

    /** 全程时分（首站发车 → 末站到达）。 */
    public int totalRunSeconds() {
      return stops.isEmpty() ? 0 : stops.get(stops.size() - 1).arrivalOffsetSeconds();
    }
  }
}
