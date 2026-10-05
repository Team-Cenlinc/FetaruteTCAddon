package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.model.RunTimeModel;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;

/**
 * 瓶颈：成品表上每个互斥资源（区间、道岔、站台股道）在最忙的一小时里被占了多久。
 *
 * <p>占用按闭塞时间算（{@link BlockingTimes}）：从车头前方的授权窗口要用到它，到车尾与尾部保护离开、下一个调度 tick 放出它。各班的占用首尾相接时，
 * 就是运行时恰好不减速的最密排法，所以一个资源在一小时里被占的秒数之和除以一小时，就是按现有班次结构等比压缩的余地：所有间隔最多能压到现在的这个比例，
 * 压到底是最紧的那个资源先占满。相位、折返接续等别的约束可能更早卡住，这是下界性质的量。超过 100% 说明表上就有车次的闭塞时间重叠，运行时会在那里减速或等待。
 *
 * <p>站台按具体股道算：固定股道的停靠用它自己的股道，动态站台（DYNAMIC）的停靠用编表排出的计划股道（{@link PlatformPlan}）。
 * 出库走行没有车次、折返与待命不属于哪一班，它们跟着同一辆车在同一站台组里相邻那次停靠的股道走（计划股道本来就是这样排的）；
 * 还是定不下股道的单独计数，不计入。车库股道不算（出入段另有车库咽喉一节）。同一辆车前后几段在同一资源上的占用合并，不重复计。
 *
 * <p>闭塞时间缺的项（车长未知放不出时刻、闭塞时间里没有的股道）退回冲突模型的占用区间，报告标出来。最忙的一小时按小时计，是通过能力的惯用单位； 各小时一样忙时取最早的一小时。
 */
public final class CapacityReport {

  /** 统计窗口：一小时。 */
  public static final int WINDOW_SECONDS = 3600;

  /** 报告列几个资源。 */
  public static final int LIMIT = 5;

  private CapacityReport() {}

  /**
   * 一个资源在最忙一小时里的占用。
   *
   * @param key 资源键（{@code edge:}/{@code junction:}/{@code platform:}）
   * @param kind 资源种类
   * @param utilization 最忙一小时里被占的比例（可以超过 1）
   * @param peakStartSeconds 最忙一小时的起点（相对零点）
   * @param passes 这一小时里开始的占用次数（同一辆车连着的几段算一次）
   * @param approachSeconds 平均每次：车头到达之前、授权窗口就要用到它的时长
   * @param occupySeconds 平均每次：车占着它的时长（站台含停站）
   * @param clearSeconds 平均每次：车头离开之后、车尾与尾部保护放出它之前的时长
   * @param standSeconds 平均每次：站台上的折返、待命
   * @param occupationOnly 有一部分占用只按占用区间算（车长未知，或闭塞时间里没有这一项）
   */
  public record Bottleneck(
      String key,
      TimetableConflictChecker.Kind kind,
      double utilization,
      int peakStartSeconds,
      int passes,
      double approachSeconds,
      double occupySeconds,
      double clearSeconds,
      double standSeconds,
      boolean occupationOnly) {

    /** 平均每次占用的总秒数。 */
    public double perPassSeconds() {
      return approachSeconds + occupySeconds + clearSeconds + standSeconds;
    }
  }

  /**
   * 报告。
   *
   * @param top 最忙一小时占用最高的几个资源，从高到低
   * @param unplaced 定不下股道、没有计入的动态站台停留段数
   */
  public record Report(List<Bottleneck> top, int unplaced) {

    /** 没量。 */
    public static final Report NONE = new Report(List.of(), 0);

    public Report {
      top = top == null ? List.of() : List.copyOf(top);
      unplaced = Math.max(0, unplaced);
    }
  }

  /**
   * 量编出来的表（联编时几张一起量，共用的资源合在一起算）。
   *
   * @param tables 成品表
   * @param profilesByTable 每张表各 route 的投影（含出库、回库走行），键是表 id；时刻须是表上落库的时刻
   * @param plansByTable 每张表的计划股道，键是表 id
   * @param index 图索引
   * @param following 跟车规则与车长；未启用时全部按占用区间
   * @param model 走行模型（闭塞时间用它的逐点轨迹）
   * @param graph 调度图
   * @param zeroSecondOfDay 零点（日内秒）
   * @param limit 最多列几个
   */
  public static Report measure(
      List<Timetable> tables,
      Map<UUID, Map<UUID, TimetableConflictChecker.RouteProfile>> profilesByTable,
      Map<UUID, List<PlatformPlan>> plansByTable,
      TimetableConflictChecker.GraphIndex index,
      TimetableBuildOptions.Following following,
      RunTimeModel model,
      RailGraph graph,
      int zeroSecondOfDay,
      int limit) {
    BlockingTimes.Trajectories trajectories =
        model == null || graph == null
            ? BlockingTimes.Trajectories.NONE
            : run -> model.trajectory(graph, run);
    return measure(
        tables,
        profilesByTable,
        plansByTable,
        index,
        following,
        trajectories,
        zeroSecondOfDay,
        limit);
  }

  static Report measure(
      List<Timetable> tables,
      Map<UUID, Map<UUID, TimetableConflictChecker.RouteProfile>> profilesByTable,
      Map<UUID, List<PlatformPlan>> plansByTable,
      TimetableConflictChecker.GraphIndex index,
      TimetableBuildOptions.Following following,
      BlockingTimes.Trajectories trajectories,
      int zeroSecondOfDay,
      int limit) {
    if (tables == null || tables.isEmpty() || limit <= 0) {
      return Report.NONE;
    }
    Map<String, Resource> resources = new HashMap<>();
    int unplaced = 0;
    for (Timetable table : tables) {
      Map<UUID, TimetableConflictChecker.RouteProfile> profiles =
          profilesByTable == null ? Map.of() : profilesByTable.getOrDefault(table.id(), Map.of());
      Routes routes = new Routes(profiles, index, following, trajectories);
      Map<UUID, Map<Integer, String>> planned = new HashMap<>();
      if (plansByTable != null) {
        for (PlatformPlan plan : plansByTable.getOrDefault(table.id(), List.of())) {
          planned
              .computeIfAbsent(plan.tripId(), key -> new HashMap<>())
              .put(plan.stopSequence(), plan.nodeId());
        }
      }
      Map<UUID, TimetableTrip> tripsById = new HashMap<>();
      for (TimetableTrip trip : table.trips()) {
        tripsById.put(trip.id(), trip);
      }
      Set<UUID> returnRouteIds = TimetableOccupancyProjector.returnRouteIdsOf(table);
      for (VehicleDuty duty : table.duties()) {
        List<TimetableTrip> trips = new ArrayList<>();
        Map<String, TimetableTrip> byCode = new HashMap<>();
        for (UUID tripId : duty.tripIds()) {
          TimetableTrip trip = tripsById.get(tripId);
          if (trip != null) {
            trips.add(trip);
            byCode.put(trip.tripCode(), trip);
          }
        }
        TimetableOccupancyProjector.Occupancy occupancy =
            TimetableOccupancyProjector.projectDuty(
                duty,
                trips,
                profiles,
                returnRouteIds,
                table.serviceStartSecondOfDay(),
                zeroSecondOfDay);
        Vehicle vehicle = new Vehicle();
        for (TimetableConflictChecker.Movement movement : occupancy.movements()) {
          TimetableTrip trip = byCode.get(movement.code());
          Map<Integer, String> plans =
              trip != null && trip.routeId().equals(movement.routeId())
                  ? planned.getOrDefault(trip.id(), Map.of())
                  : Map.of();
          routes.add(movement, plans, vehicle);
        }
        for (TimetableConflictChecker.Stay stay : occupancy.stays()) {
          vehicle.stay(stay);
        }
        unplaced += vehicle.resolve();
        vehicle.spans.forEach(
            (key, spans) ->
                resources.computeIfAbsent(key, Resource::new).spans.addAll(merged(spans)));
      }
    }
    List<Bottleneck> out = new ArrayList<>();
    for (Resource resource : resources.values()) {
      resource.peak().ifPresent(out::add);
    }
    out.sort(
        Comparator.comparingDouble(Bottleneck::utilization)
            .reversed()
            .thenComparing(Bottleneck::key));
    return new Report(out.subList(0, Math.min(limit, out.size())), unplaced);
  }

  /**
   * 一辆车在一个资源上的一段占用。
   *
   * @param from 开始（相对零点）
   * @param to 结束
   * @param approach 进入前的授权窗口
   * @param occupy 占着的时长
   * @param clear 离开后到放出
   * @param stand 站台待命
   * @param occupationOnly 只按占用区间算
   */
  private record Span(
      TimetableConflictChecker.Kind kind,
      int from,
      int to,
      int approach,
      int occupy,
      int clear,
      int stand,
      boolean occupationOnly) {}

  /** 动态站台上还没定股道的一段停留：跟着同一辆车在同一站台组里相邻那次停靠的股道走。 */
  private record Pending(String group, Span span) {}

  /** 定了股道的一段站台停留，给相邻的待定停留认股道用。 */
  private record Placed(String group, String track, int from, int to) {}

  /** 一辆车（一条交路）在各资源上的占用。 */
  private static final class Vehicle {
    private final Map<String, List<Span>> spans = new HashMap<>();
    private final List<Pending> pending = new ArrayList<>();
    private final List<Placed> placed = new ArrayList<>();

    void add(String key, Span span) {
      spans.computeIfAbsent(key, ignored -> new ArrayList<>()).add(span);
    }

    /** 站台上的一段停留：固定股道直接记；动态站台有计划股道用计划股道，没有的按组待定。车库股道不算（出入段另有车库咽喉一节）。 */
    void platform(TimetableConflictChecker.Platform platform, Optional<String> track, Span span) {
      if (depot(platform)) {
        return;
      }
      String node = track.orElse(platform.dynamic() ? "" : platform.nodeId());
      if (!node.isBlank()) {
        add("platform:" + node, span);
        if (!platform.group().isBlank()) {
          placed.add(new Placed(platform.group(), node, span.from(), span.to()));
        }
      } else if (!platform.group().isBlank()) {
        pending.add(new Pending(platform.group(), span));
      }
    }

    /** 车库股道：节点或站台组按 {@code OP:D:NAME} 命名。 */
    private static boolean depot(TimetableConflictChecker.Platform platform) {
      String group =
          platform.group().isBlank()
              ? TimetableConflictChecker.groupOf(platform.nodeId())
              : platform.group();
      String[] parts = group.split(":");
      return parts.length >= 2 && parts[1].equals("D");
    }

    void stay(TimetableConflictChecker.Stay stay) {
      if (stay.to() <= stay.from()) {
        return;
      }
      platform(
          stay.platform(),
          Optional.empty(),
          new Span(
              TimetableConflictChecker.Kind.PLATFORM,
              stay.from(),
              stay.to(),
              0,
              0,
              0,
              stay.to() - stay.from(),
              false));
    }

    /**
     * 给待定的停留认股道：同一站台组里与它首尾相接或重叠、离得最近的那段定了股道的停留。认出来的又能给下一段认：出库到站、待命、首班发车是首尾相接的一串， 只有首班发车有计划股道。
     *
     * @return 认不出股道的段数
     */
    int resolve() {
      List<Pending> left = new ArrayList<>(pending);
      boolean progress = true;
      while (progress && !left.isEmpty()) {
        progress = false;
        for (java.util.Iterator<Pending> it = left.iterator(); it.hasNext(); ) {
          Pending one = it.next();
          Placed best = nearest(one);
          if (best != null) {
            add("platform:" + best.track(), one.span());
            placed.add(new Placed(one.group(), best.track(), one.span().from(), one.span().to()));
            it.remove();
            progress = true;
          }
        }
      }
      return left.size();
    }

    private Placed nearest(Pending one) {
      Placed best = null;
      int bestGap = Integer.MAX_VALUE;
      for (Placed candidate : placed) {
        if (!candidate.group().equals(one.group())
            || one.span().from() > candidate.to()
            || one.span().to() < candidate.from()) {
          continue;
        }
        int gap =
            Math.abs(one.span().from() - candidate.to())
                + Math.abs(one.span().to() - candidate.from());
        if (gap < bestGap) {
          bestGap = gap;
          best = candidate;
        }
      }
      return best;
    }
  }

  /** 同一辆车的几段占用：重叠或相接的并成一段，组成各项相加（重叠部分只有一个 tick 量级）。 */
  private static List<Span> merged(List<Span> spans) {
    spans.sort(Comparator.comparingInt(Span::from).thenComparingInt(Span::to));
    List<Span> out = new ArrayList<>();
    Span current = null;
    for (Span span : spans) {
      if (current != null && span.from() <= current.to()) {
        current =
            new Span(
                current.kind(),
                current.from(),
                Math.max(current.to(), span.to()),
                current.approach() + span.approach(),
                current.occupy() + span.occupy(),
                current.clear() + span.clear(),
                current.stand() + span.stand(),
                current.occupationOnly() || span.occupationOnly());
      } else {
        if (current != null) {
          out.add(current);
        }
        current = span;
      }
    }
    if (current != null) {
      out.add(current);
    }
    return out;
  }

  /** 一个资源上所有车的占用。 */
  private static final class Resource {
    private final String key;
    private final List<Span> spans = new ArrayList<>();

    Resource(String key) {
      this.key = key;
    }

    /** 最忙的一小时：各车占用在窗口里的秒数之和最大的那一段，并列取最早。 */
    Optional<Bottleneck> peak() {
      if (spans.isEmpty()) {
        return Optional.empty();
      }
      int start = Integer.MAX_VALUE;
      int end = Integer.MIN_VALUE;
      for (Span span : spans) {
        start = Math.min(start, span.from());
        end = Math.max(end, span.to());
      }
      int length = Math.max(1, end - start);
      int[] covering = new int[length + 1];
      for (Span span : spans) {
        covering[span.from() - start]++;
        covering[span.to() - start]--;
      }
      // busy[x]：起点到第 x 秒为止被占的车·秒；窗口和 = busy[x + W] − busy[x]。
      long[] busy = new long[length + 1];
      int level = 0;
      for (int x = 0; x < length; x++) {
        level += covering[x];
        busy[x + 1] = busy[x] + level;
      }
      int window = Math.min(WINDOW_SECONDS, length);
      long best = -1L;
      int bestAt = 0;
      for (int x = 0; x + window <= length; x++) {
        long sum = busy[x + window] - busy[x];
        if (sum > best) {
          best = sum;
          bestAt = x;
        }
      }
      int peakFrom = start + bestAt;
      int peakTo = peakFrom + WINDOW_SECONDS;
      int passes = 0;
      long approach = 0L;
      long occupy = 0L;
      long clear = 0L;
      long stand = 0L;
      boolean occupationOnly = false;
      for (Span span : spans) {
        if (span.from() >= peakFrom && span.from() < peakTo) {
          passes++;
          approach += span.approach();
          occupy += span.occupy();
          clear += span.clear();
          stand += span.stand();
          occupationOnly |= span.occupationOnly();
        }
      }
      int n = Math.max(1, passes);
      return Optional.of(
          new Bottleneck(
              key,
              spans.get(0).kind(),
              best / (double) WINDOW_SECONDS,
              peakFrom,
              passes,
              approach / (double) n,
              occupy / (double) n,
              clear / (double) n,
              stand / (double) n,
              occupationOnly));
    }
  }

  /** 各交路的占用与闭塞时间，按交路只算一次。 */
  private static final class Routes {
    private final Map<UUID, TimetableConflictChecker.RouteProfile> profiles;
    private final TimetableConflictChecker.GraphIndex index;
    private final TimetableConflictChecker.Footprints footprints;
    private final TimetableBuildOptions.Following following;
    private final BlockingTimes.Trajectories trajectories;
    private final Map<UUID, List<CorridorCatchUp.Passage>> tracks = new HashMap<>();
    private final Map<UUID, Optional<BlockingTimes>> blocking = new HashMap<>();

    Routes(
        Map<UUID, TimetableConflictChecker.RouteProfile> profiles,
        TimetableConflictChecker.GraphIndex index,
        TimetableBuildOptions.Following following,
        BlockingTimes.Trajectories trajectories) {
      this.profiles = profiles;
      this.index = index == null ? TimetableConflictChecker.GraphIndex.of(null) : index;
      this.footprints = new TimetableConflictChecker.Footprints(this.index);
      this.following = following == null ? TimetableBuildOptions.Following.NONE : following;
      this.trajectories = trajectories == null ? BlockingTimes.Trajectories.NONE : trajectories;
    }

    /**
     * 一次运行在各资源上的占用，记到这辆车名下。区间与道岔取冲突模型的足迹；站台逐个停靠点取股道（动态站台用计划股道）。
     *
     * @param plans 这一班的计划股道：停靠序号 → 股道节点
     */
    void add(
        TimetableConflictChecker.Movement movement, Map<Integer, String> plans, Vehicle vehicle) {
      TimetableConflictChecker.RouteProfile profile = profiles.get(movement.routeId());
      if (profile == null) {
        return;
      }
      Optional<BlockingTimes> times =
          blocking.computeIfAbsent(
              movement.routeId(),
              id ->
                  following.enabled()
                      ? Optional.of(BlockingTimes.of(profile, index, following, trajectories))
                      : Optional.empty());
      int at = movement.startSeconds();
      List<CorridorCatchUp.Passage> mine =
          tracks.computeIfAbsent(
              movement.routeId(),
              id ->
                  CorridorCatchUp.passagesOf(footprints.of(id, profile)).stream()
                      .filter(passage -> passage.kind() != TimetableConflictChecker.Kind.PLATFORM)
                      .toList());
      for (CorridorCatchUp.Passage passage : mine) {
        vehicle.add(
            passage.key(),
            span(passage.kind(), at, passage.entry(), passage.exit(), times, passage.key()));
      }
      List<TimetableStop> stops = profile.stops();
      List<TimetableConflictChecker.Platform> platforms = profile.platforms();
      for (int k = 0; k < stops.size() && k < platforms.size(); k++) {
        TimetableConflictChecker.Platform platform = platforms.get(k);
        if (platform.absent()) {
          continue;
        }
        TimetableStop stop = stops.get(k);
        // 闭塞时间按交路走过的那个站台节点记：动态站台换了股道，进出站的时刻不变。
        String pathKey = "platform:" + stop.nodeId().orElse(platform.nodeId());
        vehicle.platform(
            platform,
            Optional.ofNullable(plans.get(stop.stopSequence())),
            span(
                TimetableConflictChecker.Kind.PLATFORM,
                at,
                stop.arrivalOffsetSeconds(),
                stop.departureOffsetSeconds(),
                times,
                pathKey));
      }
    }

    /** 车占着 {@code [entry, exit]}，闭塞时间有这一项时往前延到要用、往后延到放出。 */
    private static Span span(
        TimetableConflictChecker.Kind kind,
        int at,
        int entry,
        int exit,
        Optional<BlockingTimes> times,
        String key) {
      OptionalInt need = times.map(one -> one.need(key)).orElse(OptionalInt.empty());
      OptionalInt release = times.map(one -> one.release(key)).orElse(OptionalInt.empty());
      int from = Math.min(entry, need.orElse(entry));
      int to = Math.max(exit, release.orElse(exit));
      return new Span(
          kind, at + from, at + to, entry - from, exit - entry, to - exit, 0, release.isEmpty());
    }
  }
}
