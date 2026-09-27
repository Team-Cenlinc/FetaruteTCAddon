package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.scope;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.model.RunTimeModel;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.Timetable;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableConflictChecker;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableOccupancyProjector;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableRoutePlan;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableStop;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableTimingCalculator;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableTrip;

/**
 * 决定"谁算邻表"的唯一地方：把已发布的时刻表与没有表的线路展开成足迹，筛出与我共用资源的。
 *
 * <p>只做纯数据运算、无副作用；存储读取由调用方完成并把结果传进来，所以本类既能在异步线程跑，也能在单测里直接构造。 将来若引入路网管理者（决定哪些表算邻表、以什么顺序算路权），插入点就是这里。
 *
 * <p>世界维的边界不需要显式参数：不在这张图上的 route 寻路必然失败，足迹按空计，自然不会与我相交。
 *
 * <p>这一阶段只回答"共用多少资源、对方有没有表"；把邻表的运行投影进冲突检查是下一阶段的事。
 */
public final class TimetableNeighborhoodLoader {

  private final TimetableTimingCalculator timingCalculator;
  private final RunTimeModel runTimeModel;
  private final Function<UUID, Optional<RouteDefinition>> routeDefinitions;
  private final Function<UUID, List<RouteStop>> routeStops;
  private final Function<Timetable, String> displayCodes;

  /**
   * 同一次命令里 footprints / project / rebasedProfilesOf 会对同一批 route 反复求时分；缓存在实例上， 一个 loader
   * 只服务一次命令、一张图。键是 {@code timetableId:routeId}（同一条外方走行线路可能出现在两份表里、落库时分不同）。
   */
  private final Map<String, Optional<Rebased>> rebasedCache = new HashMap<>();

  /**
   * @param timingCalculator 与 build 同一个计时器：足迹用的路径就是它算出的逐边路径
   * @param runTimeModel 与 build 同一个走行时分模型；足迹只关心路径，区段内逐节点时刻的比例按它分摊
   * @param routeDefinitions 按 route UUID 取交路定义
   * @param routeStops 按 route UUID 取停靠配置（DYNAMIC 判定要用）
   * @param displayCodes 时刻表的显示码，形如 {@code company/operator/line/code}
   */
  public TimetableNeighborhoodLoader(
      TimetableTimingCalculator timingCalculator,
      RunTimeModel runTimeModel,
      Function<UUID, Optional<RouteDefinition>> routeDefinitions,
      Function<UUID, List<RouteStop>> routeStops,
      Function<Timetable, String> displayCodes) {
    this.timingCalculator = Objects.requireNonNull(timingCalculator, "timingCalculator");
    this.runTimeModel = Objects.requireNonNull(runTimeModel, "runTimeModel");
    this.routeDefinitions = Objects.requireNonNull(routeDefinitions, "routeDefinitions");
    this.routeStops = Objects.requireNonNull(routeStops, "routeStops");
    this.displayCodes = Objects.requireNonNull(displayCodes, "displayCodes");
  }

  /**
   * 已发布时刻表里与我足迹相交的那些。
   *
   * @param published 全部已发布时刻表（调用方从存储读）
   * @param excludeLineId 我这条 line：它名下的全部表都不算邻表——它们与我是替代关系，不是并存关系
   * @param graph 我的路径所在世界的图
   * @param index 图索引
   * @param mine 我的足迹
   * @return 按共用资源数降序、再按显示码排序
   */
  public List<FootprintNeighbor> footprints(
      List<Timetable> published,
      UUID excludeLineId,
      RailGraph graph,
      TimetableConflictChecker.GraphIndex index,
      TimetableFootprint mine) {
    return footprints(published, excludeSet(excludeLineId), graph, index, mine);
  }

  /** 同上，排除多条线（多线联编：一起编的线互相不算邻表）。 */
  public List<FootprintNeighbor> footprints(
      List<Timetable> published,
      Set<UUID> excludeLineIds,
      RailGraph graph,
      TimetableConflictChecker.GraphIndex index,
      TimetableFootprint mine) {
    Objects.requireNonNull(mine, "mine");
    Set<UUID> excluded = excludeLineIds == null ? Set.of() : excludeLineIds;
    List<FootprintNeighbor> out = new ArrayList<>();
    for (Timetable timetable : published == null ? List.<Timetable>of() : published) {
      if (timetable == null || !timetable.published() || excluded.contains(timetable.lineId())) {
        continue;
      }
      List<String> warnings = new ArrayList<>();
      List<TimetableConflictChecker.RouteProfile> profiles = new ArrayList<>();
      for (TimetableRoutePlan plan : timetable.routePlans()) {
        rebasedProfileOf(timetable.id(), plan, graph, index)
            .ifPresentOrElse(
                rebased -> profiles.add(rebased.profile()),
                () -> warnings.add("route " + plan.routeCode() + " 在当前图上不可达或未加载，足迹按空计"));
      }
      String displayCode = displayCodes.apply(timetable);
      TimetableFootprint footprint =
          TimetableFootprint.of(timetable.id(), displayCode, profiles, index);
      int shared = mine.sharedWith(footprint);
      if (shared == 0) {
        continue;
      }
      out.add(
          new FootprintNeighbor(
              timetable.id(),
              displayCode,
              timetable.updatedAt(),
              shared,
              List.copyOf(mine.sharedKeys(footprint)),
              List.copyOf(warnings)));
    }
    out.sort(
        Comparator.comparingInt(FootprintNeighbor::sharedResources)
            .reversed()
            .thenComparing(FootprintNeighbor::displayCode));
    return List.copyOf(out);
  }

  /**
   * 没有已发布表、却与我共用资源的线路：它们按 headway 发车，干扰是单向的，只能报告不能检查。
   *
   * @param candidates 本世界里不属于任何已发布表、也不属于我这条 line 的 route
   * @param graph 图
   * @param index 图索引
   * @param mine 我的足迹
   * @return 按 line 归组，共用资源数降序
   */
  public List<UnscheduledNeighbor> unscheduled(
      List<RouteCandidate> candidates,
      RailGraph graph,
      TimetableConflictChecker.GraphIndex index,
      TimetableFootprint mine) {
    Objects.requireNonNull(mine, "mine");
    Map<String, List<TimetableConflictChecker.RouteProfile>> byLine = new LinkedHashMap<>();
    for (RouteCandidate candidate : candidates == null ? List.<RouteCandidate>of() : candidates) {
      if (candidate == null) {
        continue;
      }
      profileOf(candidate.routeId(), candidate.routeCode(), graph, index)
          .ifPresent(
              profile ->
                  byLine
                      .computeIfAbsent(candidate.lineDisplayCode(), ignored -> new ArrayList<>())
                      .add(profile));
    }
    List<UnscheduledNeighbor> out = new ArrayList<>();
    byLine.forEach(
        (lineCode, profiles) -> {
          TimetableFootprint footprint =
              TimetableFootprint.of(
                  UUID.nameUUIDFromBytes(
                      lineCode.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                  lineCode,
                  profiles,
                  index);
          int shared = mine.sharedWith(footprint);
          if (shared > 0) {
            out.add(
                new UnscheduledNeighbor(lineCode, shared, List.copyOf(mine.sharedKeys(footprint))));
          }
        });
    out.sort(
        Comparator.comparingInt(UnscheduledNeighbor::sharedResources)
            .reversed()
            .thenComparing(UnscheduledNeighbor::displayCode));
    return List.copyOf(out);
  }

  /**
   * 把与我足迹相交的已发布表投影成冲突模型里的运行：零点 = 我的计划窗口起点，服务日按 -1/0/+1 展开并裁剪到我的窗口附近。
   *
   * <p>邻表的路径用当前图重算，时刻用它落库的站间时分（那是它实际在跑的时刻）；两者不一致时标 {@code staleAgainstGraph}
   * 并警告，但仍然参与检查。时区不同按参考日的零点偏移换算并标 {@code zoneApproximated}。
   *
   * @param published 全部已发布时刻表
   * @param excludeLineId 我这条 line：它名下的全部表都不算邻表
   * @param graph 我的路径所在世界的图
   * @param index 图索引
   * @param mine 我的足迹
   * @param myServiceStartSecondOfDay 我的零点
   * @param myHorizonSeconds 我的计划窗口长度
   * @param myZone 我的时区
   * @param referenceDate 时区换算用的参考日
   */
  public List<NeighborTimetable> project(
      List<Timetable> published,
      UUID excludeLineId,
      RailGraph graph,
      TimetableConflictChecker.GraphIndex index,
      TimetableFootprint mine,
      int myServiceStartSecondOfDay,
      int myHorizonSeconds,
      ZoneId myZone,
      LocalDate referenceDate) {
    return project(
        published,
        excludeSet(excludeLineId),
        graph,
        index,
        mine,
        myServiceStartSecondOfDay,
        myHorizonSeconds,
        myZone,
        referenceDate);
  }

  /** 同上，排除多条线（多线联编：一起编的线互相不算邻表）。 */
  public List<NeighborTimetable> project(
      List<Timetable> published,
      Set<UUID> excludeLineIds,
      RailGraph graph,
      TimetableConflictChecker.GraphIndex index,
      TimetableFootprint mine,
      int myServiceStartSecondOfDay,
      int myHorizonSeconds,
      ZoneId myZone,
      LocalDate referenceDate) {
    Objects.requireNonNull(mine, "mine");
    Set<UUID> excluded = excludeLineIds == null ? Set.of() : excludeLineIds;
    List<NeighborTimetable> out = new ArrayList<>();
    for (Timetable timetable : published == null ? List.<Timetable>of() : published) {
      if (timetable == null || !timetable.published() || excluded.contains(timetable.lineId())) {
        continue;
      }
      List<String> warnings = new ArrayList<>();
      Map<UUID, TimetableConflictChecker.RouteProfile> profiles = new LinkedHashMap<>();
      boolean stale = false;
      for (TimetableRoutePlan plan : timetable.routePlans()) {
        Optional<Rebased> rebased = rebasedProfileOf(timetable.id(), plan, graph, index);
        if (rebased.isEmpty()) {
          warnings.add("route " + plan.routeCode() + " 在当前图上不可达或未加载，足迹按空计");
          continue;
        }
        profiles.put(plan.routeId(), rebased.get().profile());
        if (rebased.get().stale()) {
          stale = true;
          warnings.add("route " + plan.routeCode() + " 落库时分与当前图重算不一致（邻表基于旧图）");
        }
      }
      String displayCode = displayCodes.apply(timetable);
      TimetableFootprint footprint =
          TimetableFootprint.of(timetable.id(), displayCode, profiles.values(), index);
      int shared = mine.sharedWith(footprint);
      if (shared == 0) {
        continue;
      }
      boolean zoneApproximated = !timetable.zoneId().equals(myZone);
      int zoneOffset =
          zoneApproximated
              ? (int)
                  Duration.between(
                          referenceDate.atStartOfDay(myZone).toInstant(),
                          referenceDate.atStartOfDay(timetable.zoneId()).toInstant())
                      .getSeconds()
              : 0;
      TimetableOccupancyProjector.Occupancy base =
          TimetableOccupancyProjector.project(
              timetable, profiles, myServiceStartSecondOfDay, Optional.of(displayCode));
      List<TimetableConflictChecker.Movement> movements = new ArrayList<>();
      List<TimetableConflictChecker.Stay> stays = new ArrayList<>();
      int lower = -TimetableTrip.SECONDS_PER_DAY / 24;
      int upper = myHorizonSeconds + TimetableTrip.SECONDS_PER_DAY / 24;
      for (int day = -1; day <= 1; day++) {
        int shift = day * TimetableTrip.SECONDS_PER_DAY + zoneOffset;
        for (TimetableConflictChecker.Movement movement : base.movements()) {
          int start = movement.startSeconds() + shift;
          int run =
              Optional.ofNullable(profiles.get(movement.routeId()))
                  .map(profile -> lastArrival(profile.stops()))
                  .orElse(0);
          if (overlaps(start, start + run, lower, upper)) {
            movements.add(
                new TimetableConflictChecker.Movement(
                    movement.code(), movement.routeId(), start, movement.owner()));
          }
        }
        for (TimetableConflictChecker.Stay stay : base.stays()) {
          int from = stay.from() + shift;
          int to = stay.to() + shift;
          if (overlaps(from, to, lower, upper)) {
            stays.add(
                new TimetableConflictChecker.Stay(
                    stay.code(), stay.platform(), from, to, stay.owner()));
          }
        }
      }
      out.add(
          new NeighborTimetable(
              timetable.id(),
              displayCode,
              timetable.updatedAt(),
              timetable.zoneId(),
              shared,
              stale,
              zoneApproximated,
              profiles,
              movements,
              stays,
              warnings));
    }
    out.sort(
        Comparator.comparingInt(NeighborTimetable::sharedResources)
            .reversed()
            .thenComparing(NeighborTimetable::displayCode));
    return List.copyOf(out);
  }

  private static boolean overlaps(int from, int to, int lower, int upper) {
    return to >= lower && from <= upper;
  }

  private static int lastArrival(List<TimetableStop> stops) {
    return stops.isEmpty() ? 0 : stops.get(stops.size() - 1).arrivalOffsetSeconds();
  }

  /**
   * 邻表一条 route 的投影：路径与逐边比例来自当前图，站间时刻来自落库值。
   *
   * <p>每个区段的逐边节点时刻按重算比例等比缩放进落库的 [发车, 到达] 区间；落库与重算的站间时分不一致就标 stale。
   */
  private Optional<Rebased> rebasedProfileOf(
      UUID timetableId,
      TimetableRoutePlan plan,
      RailGraph graph,
      TimetableConflictChecker.GraphIndex index) {
    return rebasedCache.computeIfAbsent(
        timetableId + ":" + plan.routeId(), key -> computeRebased(plan, graph, index));
  }

  private Optional<Rebased> computeRebased(
      TimetableRoutePlan plan, RailGraph graph, TimetableConflictChecker.GraphIndex index) {
    Optional<RouteDefinition> definition = routeDefinitions.apply(plan.routeId());
    if (definition.isEmpty() || graph == null) {
      return Optional.empty();
    }
    List<RouteStop> stops = routeStops.apply(plan.routeId());
    TimetableTimingCalculator.TimingResult timing =
        timingCalculator.compute(graph, runTimeModel, definition.get(), stops, Duration.ZERO);
    if (!timing.ok() || timing.segments().size() != plan.stops().size() - 1) {
      return Optional.empty();
    }
    boolean stale = false;
    List<TimetableTimingCalculator.SegmentTiming> segments = new ArrayList<>();
    for (TimetableTimingCalculator.SegmentTiming segment : timing.segments()) {
      TimetableStop from = plan.stops().get(segment.fromStop());
      TimetableStop to = plan.stops().get(segment.toStop());
      int persistedRun = to.arrivalOffsetSeconds() - from.departureOffsetSeconds();
      int recomputedRun = segment.exitOffset(segment.edges().size() - 1) - segment.enterOffset(0);
      if (persistedRun != recomputedRun) {
        stale = true;
      }
      List<Integer> offsets = new ArrayList<>(segment.nodeOffsets().size());
      int base = segment.enterOffset(0);
      for (int k = 0; k < segment.nodeOffsets().size(); k++) {
        int relative = segment.nodeOffsets().get(k) - base;
        int scaled =
            recomputedRun <= 0
                ? 0
                : (int) Math.round((double) relative * persistedRun / recomputedRun);
        offsets.add(from.departureOffsetSeconds() + scaled);
      }
      offsets.set(offsets.size() - 1, to.arrivalOffsetSeconds());
      segments.add(
          new TimetableTimingCalculator.SegmentTiming(
              segment.fromStop(), segment.toStop(), segment.nodes(), segment.edges(), offsets));
    }
    return Optional.of(
        new Rebased(
            new TimetableConflictChecker.RouteProfile(
                plan.routeId(),
                plan.routeCode(),
                plan.stops(),
                segments,
                TimetableConflictChecker.platformsOf(
                    plan.stops(), stops, definition.get().waypoints(), index.nodeTypes())),
            stale));
  }

  private record Rebased(TimetableConflictChecker.RouteProfile profile, boolean stale) {}

  /**
   * 一份已落库的表各 route 的投影：路径按当前图重算、时刻用落库值——和邻表同一口径。publish 重检与 neighbors 命令投影"我的表"时用它，
   * 这样检查的就是将要运行的那张表，而不是按当前图重算出来的另一张。算不出来的 route 略过。
   */
  public Map<UUID, TimetableConflictChecker.RouteProfile> rebasedProfilesOf(
      Timetable timetable, RailGraph graph, TimetableConflictChecker.GraphIndex index) {
    Objects.requireNonNull(timetable, "timetable");
    Map<UUID, TimetableConflictChecker.RouteProfile> out = new LinkedHashMap<>();
    for (TimetableRoutePlan plan : timetable.routePlans()) {
      rebasedProfileOf(timetable.id(), plan, graph, index)
          .ifPresent(rebased -> out.put(plan.routeId(), rebased.profile()));
    }
    return Map.copyOf(out);
  }

  /** 我自己的足迹：由 build 输入的 route 集合展开。 */
  public TimetableFootprint footprintOf(
      UUID timetableId,
      String displayCode,
      List<RouteCandidate> routes,
      RailGraph graph,
      TimetableConflictChecker.GraphIndex index) {
    List<TimetableConflictChecker.RouteProfile> profiles = new ArrayList<>();
    for (RouteCandidate route : routes == null ? List.<RouteCandidate>of() : routes) {
      if (route != null) {
        profileOf(route.routeId(), route.routeCode(), graph, index).ifPresent(profiles::add);
      }
    }
    return TimetableFootprint.of(timetableId, displayCode, profiles, index);
  }

  /** 用 build 同一个计时器算路径；停站时长与足迹无关，给零。 */
  private Optional<TimetableConflictChecker.RouteProfile> profileOf(
      UUID routeId, String routeCode, RailGraph graph, TimetableConflictChecker.GraphIndex index) {
    Optional<RouteDefinition> definition = routeDefinitions.apply(routeId);
    if (definition.isEmpty() || graph == null) {
      return Optional.empty();
    }
    List<RouteStop> stops = routeStops.apply(routeId);
    TimetableTimingCalculator.TimingResult timing =
        timingCalculator.compute(graph, runTimeModel, definition.get(), stops, Duration.ZERO);
    if (!timing.ok()) {
      return Optional.empty();
    }
    return Optional.of(
        new TimetableConflictChecker.RouteProfile(
            routeId,
            routeCode,
            timing.stops(),
            timing.segments(),
            TimetableConflictChecker.platformsOf(
                timing.stops(), stops, definition.get().waypoints(), index.nodeTypes())));
  }

  /**
   * 与我共用资源的已发布表。
   *
   * @param timetableId 邻表 UUID
   * @param displayCode 显示码
   * @param updatedAt 邻表最后更新时间；下一阶段的基线用它判断"变了没有"
   * @param sharedResources 共用资源数
   * @param sharedKeys 共用的资源键
   * @param warnings 展开足迹时的问题（route 不可达等）
   */
  private static Set<UUID> excludeSet(UUID lineId) {
    return lineId == null ? Set.of() : Set.of(lineId);
  }

  public record FootprintNeighbor(
      UUID timetableId,
      String displayCode,
      Instant updatedAt,
      int sharedResources,
      List<String> sharedKeys,
      List<String> warnings) {
    public FootprintNeighbor {
      Objects.requireNonNull(timetableId, "timetableId");
      displayCode = displayCode == null ? "" : displayCode;
      sharedKeys = sharedKeys == null ? List.of() : List.copyOf(sharedKeys);
      warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
  }

  /**
   * 没有已发布表、按 headway 发车、却与我共用资源的线路。
   *
   * @param displayCode 线路显示码
   * @param sharedResources 共用资源数
   * @param sharedKeys 共用的资源键
   */
  public record UnscheduledNeighbor(
      String displayCode, int sharedResources, List<String> sharedKeys) {
    public UnscheduledNeighbor {
      displayCode = displayCode == null ? "" : displayCode;
      sharedKeys = sharedKeys == null ? List.of() : List.copyOf(sharedKeys);
    }
  }

  /**
   * 参与足迹计算的一条 route。
   *
   * @param routeId Route UUID
   * @param routeCode Route code
   * @param lineDisplayCode 所属 line 的显示码，无表线路按它归组
   */
  public record RouteCandidate(UUID routeId, String routeCode, String lineDisplayCode) {
    public RouteCandidate {
      Objects.requireNonNull(routeId, "routeId");
      routeCode = routeCode == null ? "" : routeCode;
      lineDisplayCode = lineDisplayCode == null ? "" : lineDisplayCode;
    }
  }
}
