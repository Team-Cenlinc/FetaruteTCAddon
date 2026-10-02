package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.UUID;
import org.bukkit.util.Vector;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.query.RailGraphPath;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.query.RailGraphPathFinder;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.route.DynamicStopMatcher;
import org.fetarute.fetaruteTCAddon.dispatcher.route.PlatformApproach;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.scope.NeighborTimetable;

/**
 * 编表时给动态站台（DYNAMIC）排计划股道：同一站台组里，按时间把每辆车的停靠分到具体股道上，同一时段一条股道只给一辆车。
 *
 * <p>冲突模型在站台组一层保证"车不比股道多"，这里在它之上再分一次具体股道，做法是区间着色：
 *
 * <ul>
 *   <li>一辆车在同一站台组里连续的停留算一段，排同一条股道：终到、折返、再从这里发车，车始终停在同一条股道上。
 *       所以终到站与下一班的始发站合成一段，出库走行的终点与首班始发、末班终到与回库走行的起点同理。
 *   <li>固定股道的停靠、不停站经过的车站股道、邻表的固定股道占用先占住，不可挪动。
 *   <li>按进入时刻依次排：在空闲的候选股道里取进站方向最顺的那条（与运行时选台同一条规则，{@link PlatformApproach}）； 一条都不空闲就不排，运行时照常临时选台。
 * </ul>
 *
 * <p>联编的几条线一起排，互相看得见。邻表的 DYNAMIC 停靠只登记在站台组一层，不占具体股道：同站两份表排到同一条股道时， 后到的车运行时改选并播报站台变更。
 *
 * <p>计划只存在车次上（{@link PlatformPlan}）。出库走行没有车次，它终点的股道跟着首班始发的计划走；带客回库班有车次， 起点跟着末班终到的计划走。
 */
public final class TimetablePlatformPlanner {

  private TimetablePlatformPlanner() {}

  /**
   * 输入。
   *
   * @param tables 本次编出来的表（联编时多张）
   * @param profilesByTable 每张表各 route 的投影，键是表 id；时刻须是表上落库的时刻
   * @param stopsByRoute 各 route 的停靠配置（与交路节点下标对齐），用来读 DYNAMIC 范围
   * @param definitions 各 route 的交路定义，用来算进站方向
   * @param graph 调度图
   * @param index 图索引（节点类型）
   * @param neighbors 已投影的外部邻表，时刻与本表同一零点
   * @param zeroSecondOfDay 零点（相对服务日的秒数），与邻表投影一致
   * @param separationSeconds 同一股道上前车离开到后车进入的最小间隔
   */
  public record Input(
      List<Timetable> tables,
      Map<UUID, Map<UUID, TimetableConflictChecker.RouteProfile>> profilesByTable,
      Map<UUID, List<RouteStop>> stopsByRoute,
      Map<UUID, RouteDefinition> definitions,
      RailGraph graph,
      TimetableConflictChecker.GraphIndex index,
      List<NeighborTimetable> neighbors,
      int zeroSecondOfDay,
      int separationSeconds) {
    public Input {
      tables = tables == null ? List.of() : List.copyOf(tables);
      profilesByTable = profilesByTable == null ? Map.of() : Map.copyOf(profilesByTable);
      stopsByRoute = stopsByRoute == null ? Map.of() : Map.copyOf(stopsByRoute);
      definitions = definitions == null ? Map.of() : Map.copyOf(definitions);
      index = index == null ? TimetableConflictChecker.GraphIndex.of(graph) : index;
      neighbors = neighbors == null ? List.of() : List.copyOf(neighbors);
      separationSeconds = Math.max(0, separationSeconds);
    }
  }

  /**
   * 结果。
   *
   * @param plans 每张表的计划股道，键是表 id
   * @param planned 排上了股道的停留段数（每段可能覆盖终到与下一班始发两处停靠）
   * @param unplaced 计划时段里没有空闲候选股道、没有排的停留段数
   * @param failure 排程本身出错时的原因（此时没有计划，时刻表照常落库，运行时临时选台）
   */
  public record Result(
      Map<UUID, List<PlatformPlan>> plans, int planned, int unplaced, Optional<String> failure) {
    public Result {
      plans = plans == null ? Map.of() : Map.copyOf(plans);
      failure = failure == null ? Optional.empty() : failure;
    }

    /** 排程正常结束的结果。 */
    public Result(Map<UUID, List<PlatformPlan>> plans, int planned, int unplaced) {
      this(plans, planned, unplaced, Optional.empty());
    }

    /** 排程出错：没有计划。 */
    public static Result failed(String reason) {
      return new Result(Map.of(), 0, 0, Optional.of(String.valueOf(reason)));
    }

    /** 没有任何 DYNAMIC 停靠（或排程出错）。 */
    public boolean empty() {
      return planned == 0 && unplaced == 0;
    }
  }

  /** 排计划股道。 */
  public static Result plan(Input input) {
    Objects.requireNonNull(input, "input");
    if (input.graph() == null || input.tables().isEmpty()) {
      return new Result(Map.of(), 0, 0);
    }
    Planning planning = new Planning(input);
    for (Timetable table : input.tables()) {
      planning.walkTable(table);
    }
    for (NeighborTimetable neighbor : input.neighbors()) {
      planning.bookNeighbor(neighbor);
    }
    return planning.color();
  }

  /** 一处 DYNAMIC 停靠：哪条 route 的第几个节点；有车次时记车次，出库走行没有车次。 */
  private record DynamicStop(UUID timetableId, Optional<UUID> tripId, UUID routeId, int index) {}

  /** 股道上一段已占的时间；{@code vehicle} 相同的占用之间不算冲突。 */
  private record Booking(int from, int to, String vehicle) {}

  /** 一辆车在一个站台组里连续停留的一段，排同一条股道。 */
  private static final class Visit {
    private final String vehicle;
    private final String group;
    private final int from;
    private int to;
    private final List<DynamicStop> dynamicStops = new ArrayList<>();
    private final Set<String> fixedNodes = new LinkedHashSet<>();

    private Visit(String vehicle, String group, int from, int to) {
      this.vehicle = vehicle;
      this.group = group;
      this.from = from;
      this.to = Math.max(from, to);
    }

    private void extendTo(int until) {
      to = Math.max(to, until);
    }
  }

  private static final class Planning {
    private final Input input;
    private final List<Visit> visits = new ArrayList<>();
    private final Map<String, List<Booking>> bookings = new HashMap<>();
    private final Map<String, List<NodeId>> rankedCache = new HashMap<>();
    private final RailGraphPathFinder pathFinder = new RailGraphPathFinder();

    private Planning(Input input) {
      this.input = input;
    }

    // ------------------------------------------------------------ 展开本表

    private void walkTable(Timetable table) {
      Map<UUID, TimetableConflictChecker.RouteProfile> profiles =
          input.profilesByTable().getOrDefault(table.id(), Map.of());
      Map<UUID, TimetableTrip> tripsById = new HashMap<>();
      for (TimetableTrip trip : table.trips()) {
        tripsById.put(trip.id(), trip);
      }
      Set<UUID> covered = new HashSet<>();
      int zero = input.zeroSecondOfDay();
      for (VehicleDuty duty : table.duties()) {
        Walk walk = new Walk(table.id(), table.id() + "|" + duty.dutyCode());
        duty.createRouteId()
            .ifPresent(
                routeId ->
                    walk.run(
                        Optional.empty(),
                        routeId,
                        profiles.get(routeId),
                        duty.plannedStartSecondOfDay() - zero));
        for (UUID tripId : duty.tripIds()) {
          TimetableTrip trip = tripsById.get(tripId);
          if (trip == null) {
            continue;
          }
          covered.add(tripId);
          walk.run(
              Optional.of(tripId),
              trip.routeId(),
              profiles.get(trip.routeId()),
              TimetableOccupancyProjector.relativeDeparture(table, trip, zero));
        }
        duty.returnRouteId()
            .ifPresent(
                routeId -> {
                  Optional<UUID> returnTrip = returnTripOf(table, duty, routeId);
                  returnTrip.ifPresent(covered::add);
                  walk.run(
                      returnTrip, routeId, profiles.get(routeId), duty.returnSecondOfDay() - zero);
                });
        walk.close();
      }
      for (TimetableTrip trip : table.trips()) {
        if (!covered.contains(trip.id())) {
          Walk walk = new Walk(table.id(), table.id() + "|" + trip.tripCode());
          walk.run(
              Optional.of(trip.id()),
              trip.routeId(),
              profiles.get(trip.routeId()),
              TimetableOccupancyProjector.relativeDeparture(table, trip, zero));
          walk.close();
        }
      }
    }

    /** 交路的带客回库班：RETURN 线路上、属于这个交路的车次行（不在 {@code tripIds} 里）。 */
    private static Optional<UUID> returnTripOf(Timetable table, VehicleDuty duty, UUID routeId) {
      return table.trips().stream()
          .filter(trip -> trip.routeId().equals(routeId))
          .filter(trip -> trip.dutyId().filter(duty.id()::equals).isPresent())
          .map(TimetableTrip::id)
          .findFirst();
    }

    /** 沿一辆车的交路依次走：每段运行的始发并入上一段运行终到的停留（同一站台组时），中途停靠各自成段。 */
    private final class Walk {
      private final UUID timetableId;
      private final String vehicle;
      private Visit open;

      private Walk(UUID timetableId, String vehicle) {
        this.timetableId = timetableId;
        this.vehicle = vehicle;
      }

      private void run(
          Optional<UUID> tripId,
          UUID routeId,
          TimetableConflictChecker.RouteProfile profile,
          int departure) {
        if (profile == null || profile.stops().isEmpty()) {
          // 这一段算不出来：不知道车在哪，上一段的停留到此为止，不能跨过它与下一段并起来。
          close();
          return;
        }
        List<TimetableStop> stops = profile.stops();
        int last = stops.size() - 1;
        TimetableConflictChecker.Platform origin = platformAt(profile, 0);
        if (open != null && !origin.absent() && open.group.equals(origin.group())) {
          open.extendTo(departure + stops.get(0).departureOffsetSeconds());
          attach(open, origin, tripId, routeId, 0);
          close();
        } else {
          close();
          stay(origin, tripId, routeId, 0, departure, stops.get(0)).ifPresent(visits::add);
        }
        for (int k = 1; k < last; k++) {
          stay(platformAt(profile, k), tripId, routeId, k, departure, stops.get(k))
              .ifPresent(visits::add);
        }
        bookPassThroughs(profile, departure, vehicle);
        if (last > 0) {
          open =
              stay(platformAt(profile, last), tripId, routeId, last, departure, stops.get(last))
                  .orElse(null);
        }
      }

      private Optional<Visit> stay(
          TimetableConflictChecker.Platform platform,
          Optional<UUID> tripId,
          UUID routeId,
          int index,
          int departure,
          TimetableStop stop) {
        if (platform.absent() || platform.group().isBlank()) {
          return Optional.empty();
        }
        Visit visit =
            new Visit(
                vehicle,
                platform.group(),
                departure + stop.arrivalOffsetSeconds(),
                departure + stop.departureOffsetSeconds());
        attach(visit, platform, tripId, routeId, index);
        return Optional.of(visit);
      }

      private void attach(
          Visit visit,
          TimetableConflictChecker.Platform platform,
          Optional<UUID> tripId,
          UUID routeId,
          int index) {
        if (platform.dynamic()) {
          visit.dynamicStops.add(new DynamicStop(timetableId, tripId, routeId, index));
        } else if (!platform.nodeId().isBlank()) {
          visit.fixedNodes.add(platform.nodeId());
        }
      }

      private void close() {
        if (open != null) {
          visits.add(open);
          open = null;
        }
      }
    }

    private static TimetableConflictChecker.Platform platformAt(
        TimetableConflictChecker.RouteProfile profile, int index) {
      return index < profile.platforms().size()
          ? profile.platforms().get(index)
          : TimetableConflictChecker.Platform.none();
    }

    // ------------------------------------------------------------ 不可挪动的占用

    /** 不停站经过的车站股道也是一次占用：一辆停在单股道车站的车挡得住从它身上碾过去的车。 */
    private void bookPassThroughs(
        TimetableConflictChecker.RouteProfile profile, int departure, String vehicle) {
      for (TimetableTimingCalculator.SegmentTiming segment : profile.segments()) {
        List<NodeId> nodes = segment.nodes();
        for (int k = 1; k + 1 < nodes.size(); k++) {
          if (input.index().nodeTypes().get(nodes.get(k)) == NodeType.STATION) {
            int at = departure + segment.nodeOffsets().get(k);
            book(nodes.get(k).value(), new Booking(at, at, vehicle));
          }
        }
      }
    }

    /** 邻表的固定股道占用：运行中途的停靠与经过、站台待命；DYNAMIC 停靠只在站台组一层，不占具体股道。 */
    private void bookNeighbor(NeighborTimetable neighbor) {
      for (TimetableConflictChecker.Movement movement : neighbor.movements()) {
        TimetableConflictChecker.RouteProfile profile = neighbor.profiles().get(movement.routeId());
        if (profile == null) {
          continue;
        }
        String vehicle = neighbor.displayCode() + "|" + movement.code();
        int base = movement.startSeconds();
        List<TimetableStop> stops = profile.stops();
        for (int k = 1; k + 1 < stops.size(); k++) {
          TimetableConflictChecker.Platform platform = platformAt(profile, k);
          if (!platform.dynamic() && !platform.nodeId().isBlank()) {
            book(
                platform.nodeId(),
                new Booking(
                    base + stops.get(k).arrivalOffsetSeconds(),
                    base + stops.get(k).departureOffsetSeconds(),
                    vehicle));
          }
        }
        bookPassThroughs(profile, base, vehicle);
      }
      for (TimetableConflictChecker.Stay stay : neighbor.stays()) {
        TimetableConflictChecker.Platform platform = stay.platform();
        if (!platform.dynamic() && !platform.nodeId().isBlank()) {
          book(
              platform.nodeId(),
              new Booking(stay.from(), stay.to(), neighbor.displayCode() + "|" + stay.code()));
        }
      }
    }

    private void book(String node, Booking booking) {
      bookings.computeIfAbsent(node, key -> new ArrayList<>()).add(booking);
    }

    // ------------------------------------------------------------ 着色

    private Result color() {
      List<Visit> dynamicVisits = new ArrayList<>();
      for (Visit visit : visits) {
        if (visit.dynamicStops.isEmpty()) {
          for (String node : visit.fixedNodes) {
            book(node, new Booking(visit.from, visit.to, visit.vehicle));
          }
        } else {
          dynamicVisits.add(visit);
        }
      }
      dynamicVisits.sort(
          Comparator.comparingInt((Visit visit) -> visit.from)
              .thenComparingInt(visit -> visit.to)
              .thenComparing(visit -> visit.vehicle));
      Map<UUID, List<PlatformPlan>> plans = new LinkedHashMap<>();
      int planned = 0;
      int unplaced = 0;
      for (Visit visit : dynamicVisits) {
        Optional<String> chosen = choose(visit);
        if (chosen.isEmpty()) {
          unplaced++;
          for (String node : visit.fixedNodes) {
            book(node, new Booking(visit.from, visit.to, visit.vehicle));
          }
          continue;
        }
        planned++;
        book(chosen.get(), new Booking(visit.from, visit.to, visit.vehicle));
        for (DynamicStop stop : visit.dynamicStops) {
          stop.tripId()
              .ifPresent(
                  tripId ->
                      plans
                          .computeIfAbsent(stop.timetableId(), key -> new ArrayList<>())
                          .add(new PlatformPlan(tripId, stop.index(), chosen.get())));
        }
      }
      return new Result(plans, planned, unplaced);
    }

    /**
     * 这一段排哪条股道：各处 DYNAMIC 范围的交集里、整段空闲的那些，取进站方向最顺的一条。
     *
     * <p>段里有固定股道的停靠（例如固定股道终到、DYNAMIC 始发）时车就停在那条股道上，只能是它。
     */
    private Optional<String> choose(Visit visit) {
      List<NodeId> candidates = candidatesOf(visit);
      if (visit.fixedNodes.size() > 1) {
        return Optional.empty();
      }
      if (visit.fixedNodes.size() == 1) {
        String fixed = visit.fixedNodes.iterator().next();
        candidates = candidates.stream().filter(node -> node.value().equals(fixed)).toList();
      }
      for (NodeId candidate : candidates) {
        if (free(candidate.value(), visit)) {
          return Optional.of(candidate.value());
        }
      }
      return Optional.empty();
    }

    private boolean free(String node, Visit visit) {
      int separation = input.separationSeconds();
      for (Booking booking : bookings.getOrDefault(node, List.of())) {
        if (booking.vehicle().equals(visit.vehicle)) {
          continue;
        }
        if ((long) booking.from() < (long) visit.to + separation
            && (long) visit.from < (long) booking.to() + separation) {
          return false;
        }
      }
      return true;
    }

    /** 段内各处 DYNAMIC 范围的交集，按进站方向排序：有进站方向的那一处（终到、出库终点）说了算，只有始发时按股道号。 */
    private List<NodeId> candidatesOf(Visit visit) {
      DynamicStop lead =
          visit.dynamicStops.stream()
              .filter(stop -> stop.index() > 0)
              .findFirst()
              .orElse(visit.dynamicStops.get(0));
      List<NodeId> ranked = new ArrayList<>(ranked(lead));
      for (DynamicStop stop : visit.dynamicStops) {
        if (stop != lead) {
          ranked.retainAll(rawCandidates(stop));
        }
      }
      return ranked;
    }

    private List<NodeId> rawCandidates(DynamicStop stop) {
      List<RouteStop> routeStops = input.stopsByRoute().getOrDefault(stop.routeId(), List.of());
      if (stop.index() >= routeStops.size() || routeStops.get(stop.index()) == null) {
        return List.of();
      }
      return DynamicStopMatcher.parseDynamicSpec(routeStops.get(stop.index()))
          .map(spec -> DynamicStopMatcher.candidateNodes(spec, input.graph()))
          .orElse(List.of());
    }

    /** 从进站前最后一个节点看，可达的候选按进站方向排序；起点停靠没有进站方向，按股道号。 */
    private List<NodeId> ranked(DynamicStop stop) {
      return rankedCache.computeIfAbsent(
          stop.routeId() + ":" + stop.index(), key -> computeRanked(stop));
    }

    private List<NodeId> computeRanked(DynamicStop stop) {
      List<NodeId> candidates = rawCandidates(stop);
      RouteDefinition definition = input.definitions().get(stop.routeId());
      int index = stop.index();
      if (definition == null || index < 1 || index >= definition.waypoints().size()) {
        return candidates;
      }
      List<NodeId> waypoints = definition.waypoints();
      NodeId from = waypoints.get(index - 1);
      Vector travelDir =
          index >= 2
              ? PlatformApproach.direction(input.graph(), waypoints.get(index - 2), from)
                  .orElse(null)
              : null;
      List<Ranked> reachable = new ArrayList<>();
      for (int order = 0; order < candidates.size(); order++) {
        NodeId candidate = candidates.get(order);
        Optional<RailGraphPath> path =
            pathFinder.shortestPath(
                input.graph(), from, candidate, RailGraphPathFinder.Options.shortestDistance());
        if (path.isPresent()) {
          OptionalDouble score =
              PlatformApproach.score(input.graph(), from, travelDir, path.get().nodes(), candidate);
          reachable.add(
              new Ranked(
                  candidate,
                  score.isPresent() ? score.getAsDouble() : Double.NEGATIVE_INFINITY,
                  order));
        }
      }
      reachable.sort(
          Comparator.comparingDouble(Ranked::score).reversed().thenComparingInt(Ranked::order));
      return reachable.stream().map(Ranked::node).toList();
    }

    private record Ranked(NodeId node, double score, int order) {}
  }
}
