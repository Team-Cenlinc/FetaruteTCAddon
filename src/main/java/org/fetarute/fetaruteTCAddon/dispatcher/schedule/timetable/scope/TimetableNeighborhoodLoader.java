package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.scope;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.query.RailTravelTimeModel;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.Timetable;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableConflictChecker;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableRoutePlan;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableTimingCalculator;

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
  private final RailTravelTimeModel travelTimeModel;
  private final Function<UUID, Optional<RouteDefinition>> routeDefinitions;
  private final Function<UUID, List<RouteStop>> routeStops;
  private final Function<Timetable, String> displayCodes;

  /**
   * @param timingCalculator 与 build 同一个计时器：足迹用的路径就是它算出的逐边路径
   * @param travelTimeModel 行程时间模型；足迹只关心路径，时分本身不重要
   * @param routeDefinitions 按 route UUID 取交路定义
   * @param routeStops 按 route UUID 取停靠配置（DYNAMIC 判定要用）
   * @param displayCodes 时刻表的显示码，形如 {@code company/operator/line/code}
   */
  public TimetableNeighborhoodLoader(
      TimetableTimingCalculator timingCalculator,
      RailTravelTimeModel travelTimeModel,
      Function<UUID, Optional<RouteDefinition>> routeDefinitions,
      Function<UUID, List<RouteStop>> routeStops,
      Function<Timetable, String> displayCodes) {
    this.timingCalculator = Objects.requireNonNull(timingCalculator, "timingCalculator");
    this.travelTimeModel = Objects.requireNonNull(travelTimeModel, "travelTimeModel");
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
    Objects.requireNonNull(mine, "mine");
    List<FootprintNeighbor> out = new ArrayList<>();
    for (Timetable timetable : published == null ? List.<Timetable>of() : published) {
      if (timetable == null
          || !timetable.published()
          || (excludeLineId != null && excludeLineId.equals(timetable.lineId()))) {
        continue;
      }
      List<String> warnings = new ArrayList<>();
      List<TimetableConflictChecker.RouteProfile> profiles = new ArrayList<>();
      for (TimetableRoutePlan plan : timetable.routePlans()) {
        profileOf(plan.routeId(), plan.routeCode(), graph, index)
            .ifPresentOrElse(
                profiles::add,
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
        timingCalculator.compute(graph, travelTimeModel, definition.get(), stops, Duration.ZERO);
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
                timing.stops(), stops, definition.get().waypoints())));
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
