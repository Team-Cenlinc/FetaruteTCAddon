package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.company.model.RouteStopPassType;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailEdge;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.query.RailGraphPath;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.query.RailGraphPathFinder;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.query.RailTravelTimeModel;
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
 *   <li>时分：{@link RailTravelTimeModel}（生产上是 {@code DynamicTravelTimeModel}），
 *       它按<b>每条边的实际限速</b>加减速积分，因此一条穿越多个限速区间的 route 不会被一个全线平均速度抹平。
 *   <li>停站：{@link RouteStop#dwellSeconds()}，缺省值由调用方从配置传入，绝不在这里塞魔法数。
 * </ul>
 *
 * <p>终端折返时间<b>不</b>计入 stop profile：它发生在两趟车之间，属于车辆周转（duty）的范畴， 记在这里会让"这趟车跑多久"和"这辆车多久能再发一趟"混成同一个数。
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
   * @param travelTimeModel 行程时间模型（按边限速 + 加减速）
   * @param route 已解析的交路定义
   * @param stops route 的停靠配置，按 sequence 升序；索引与 {@code route.waypoints()} 对齐
   * @param defaultDwell 未配置 dwell 时使用的停站时长
   * @return 计算结果；任一区段不可达或无法估算时返回失败，并说明是哪一段
   */
  public TimingResult compute(
      RailGraph graph,
      RailTravelTimeModel travelTimeModel,
      RouteDefinition route,
      List<RouteStop> stops,
      Duration defaultDwell) {
    Objects.requireNonNull(travelTimeModel, "travelTimeModel");
    Objects.requireNonNull(route, "route");
    if (graph == null) {
      return TimingResult.failure("缺少调度图快照");
    }
    List<NodeId> waypoints = route.waypoints();
    if (waypoints.size() < 2) {
      return TimingResult.failure("交路至少需要两个节点");
    }
    long dwellFallback = defaultDwell == null ? 0L : Math.max(0L, defaultDwell.toSeconds());

    List<TimetableStop> profile = new ArrayList<>(waypoints.size());
    List<SegmentTiming> segments = new ArrayList<>(waypoints.size() - 1);
    int arrivalOffset = 0;
    int departureOffset = 0;
    profile.add(stopAt(waypoints.get(0), stops, 0, 0, 0));

    for (int i = 1; i < waypoints.size(); i++) {
      NodeId from = waypoints.get(i - 1);
      NodeId to = waypoints.get(i);
      Optional<RailGraphPath> pathOpt =
          pathFinder.shortestPath(graph, from, to, RailGraphPathFinder.Options.shortestDistance());
      if (pathOpt.isEmpty()) {
        return TimingResult.failure(
            "区段不可达: #" + (i - 1) + "→#" + i + " " + from.value() + " → " + to.value());
      }
      RailGraphPath path = pathOpt.get();
      Optional<Duration> travel = travelTimeModel.pathTravelTime(graph, path.nodes(), path.edges());
      if (travel.isEmpty()) {
        return TimingResult.failure(
            "区段时分无法估算（缺限速或长度）: #" + (i - 1) + "→#" + i + " " + from.value() + " → " + to.value());
      }
      int legSeconds = roundSeconds(travel.get());
      segments.add(
          new SegmentTiming(
              i - 1,
              i,
              path.nodes(),
              path.edges(),
              nodeOffsets(graph, travelTimeModel, path, departureOffset, legSeconds)));
      arrivalOffset = departureOffset + legSeconds;
      boolean last = i == waypoints.size() - 1;
      long dwell = last ? 0L : resolveDwellSeconds(stops, i, dwellFallback);
      departureOffset = arrivalOffset + (int) dwell;
      profile.add(stopAt(to, stops, i, arrivalOffset, last ? arrivalOffset : departureOffset));
    }
    return TimingResult.success(List.copyOf(profile), List.copyOf(segments));
  }

  /**
   * 把一段区间的总时分摊到路径上的每一条边，得到列车到达每个节点的时刻。
   *
   * <p>总时分以 {@code pathTravelTime} 为准（生产模型会按整条路径做加减速积分）；逐边的分摊比例优先用模型的逐边估算， 模型给不出时按边长分摊。
   * 分摊后的末节点时刻精确等于总时分，四舍五入的余数全部记在最后一条边上，保证站间时分与逐边时分不会出现两套数。
   *
   * @return 长度为 {@code nodes.size()} 的到达时刻（相对 route 首站发车的秒偏移）
   */
  private static List<Integer> nodeOffsets(
      RailGraph graph,
      RailTravelTimeModel model,
      RailGraphPath path,
      int departureOffset,
      int legSeconds) {
    List<RailEdge> edges = path.edges();
    int count = edges.size();
    List<Integer> offsets = new ArrayList<>(count + 1);
    offsets.add(departureOffset);
    if (count == 0) {
      return offsets;
    }
    double[] weights = new double[count];
    boolean allEstimated = true;
    for (int k = 0; k < count; k++) {
      Optional<Duration> dt =
          model.edgeTravelTime(graph, edges.get(k), path.nodes().get(k), path.nodes().get(k + 1));
      if (dt.isEmpty() || dt.get().isNegative()) {
        allEstimated = false;
        break;
      }
      weights[k] = dt.get().toMillis();
    }
    if (!allEstimated) {
      for (int k = 0; k < count; k++) {
        weights[k] = Math.max(0, edges.get(k).lengthBlocks());
      }
    }
    double total = 0.0D;
    for (double weight : weights) {
      total += weight;
    }
    double cumulative = 0.0D;
    for (int k = 0; k < count - 1; k++) {
      cumulative += total <= 0.0D ? 1.0D / count : weights[k] / total;
      offsets.add(departureOffset + (int) Math.round(cumulative * legSeconds));
    }
    offsets.add(departureOffset + legSeconds);
    return offsets;
  }

  /**
   * 秒级四舍五入。
   *
   * <p>用四舍五入而不是截断：截断在每个区段都少算最多一秒，一条二十站的线累计下来能少算十几秒， 而那正好是"表定时分总是偏乐观"的一个隐蔽来源。四舍五入的误差在长路径上互相抵消。
   */
  private static int roundSeconds(Duration duration) {
    long millis = Math.max(0L, duration.toMillis());
    return (int) Math.min(Integer.MAX_VALUE, (millis + 500L) / 1000L);
  }

  /**
   * 停站时长：PASS 路径点不停站，永远是 0——兜底值只给"停靠却没配 dwell"的站。
   *
   * <p>否则一条 route 上每个路径点都会被算成一次 {@code --dwell} 长的停站：全程时分被虚增一到三成， 冲突检查里每个路径点还会多出一段假的站台占用。
   */
  /**
   * 终到停靠点的停站时长：车按这条 route 到达终点后，多久能再发车。
   *
   * <p>行程时分把末站的 dwell 记作 0（班次到这儿就结束了，它不属于走行时间），于是这个字段一直没有消费者。 {@link TurnaroundTable}
   * 是它的第一个读者——折返时间不是另立的常数，就是 route 定义里写着的这个数。 解析规则与行程时分共用同一个方法，不另开一套：PASS 不停站算 0，停靠却没配 dwell 的用兜底值。
   *
   * @param stops route 的停靠配置，按 sequence 升序
   * @param fallbackSeconds 停靠却没配 dwell 时的兜底值（{@code --dwell}）
   * @return 终到点停站秒数；没有停靠配置时返回兜底值
   */
  public static int terminalDwellSeconds(List<RouteStop> stops, int fallbackSeconds) {
    long fallback = Math.max(0, fallbackSeconds);
    if (stops == null || stops.isEmpty()) {
      return (int) fallback;
    }
    return (int) Math.max(0L, resolveDwellSeconds(stops, stops.size() - 1, fallback));
  }

  private static long resolveDwellSeconds(List<RouteStop> stops, int index, long fallback) {
    if (stops == null || index < 0 || index >= stops.size()) {
      return fallback;
    }
    RouteStop stop = stops.get(index);
    if (stop == null) {
      return fallback;
    }
    if (stop.passType() == RouteStopPassType.PASS) {
      return 0L;
    }
    return stop.dwellSeconds().filter(value -> value >= 0).map(Long::valueOf).orElse(fallback);
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
