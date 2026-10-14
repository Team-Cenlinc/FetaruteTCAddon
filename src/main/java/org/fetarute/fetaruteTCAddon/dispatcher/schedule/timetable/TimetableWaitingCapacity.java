package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * 让车点的等待容量：整趟晚发时的始发站，或冲突之前最后一个途中停车站，在已有占用上试放完整等待窗口。
 *
 * <p>复用冲突模型投影与扫描，避免把待命记录条数当成车辆数，也避免只看站台组总容量而忽略具体股道或 DYNAMIC 资格。 资源仅在创建时投影一次；每次查询只复制等待涉及的站台桶与窗口内占用。
 */
final class TimetableWaitingCapacity {
  private final Map<String, TimetableConflictChecker.Resource> resources = new LinkedHashMap<>();
  private final Map<String, TimetableConflictChecker.Platform> origins;
  private final Map<String, Run> runs;
  private final TimetableConflictChecker.Footprints footprints;
  private final Function<String, String> vehicleOf;
  private final Map<Hold, Boolean> answers = new HashMap<>();
  private final Map<Hold, Boolean> enRouteAnswers = new HashMap<>();

  private record Hold(
      String code,
      TimetableConflictChecker.Platform platform,
      int from,
      int until,
      int separation) {}

  /** 一次本表运行：从几时起按哪份档案跑。 */
  private record Run(int start, TimetableConflictChecker.RouteProfile profile) {}

  /**
   * 途中停车点上的等待：后车在冲突资源之前最后一个停车的车站发车前被挡住。
   *
   * @param stopIndex 停靠序号（不是起点）
   * @param platform 那一站的站台
   * @param departure 那一站的表定发车（相对零点）
   */
  record EnRoute(int stopIndex, TimetableConflictChecker.Platform platform, int departure) {
    String group() {
      return platform.group();
    }
  }

  private TimetableWaitingCapacity(
      Map<String, TimetableConflictChecker.Platform> origins,
      Map<String, Run> runs,
      TimetableConflictChecker.GraphIndex index,
      Function<String, String> vehicleOf) {
    this.origins = Map.copyOf(origins);
    this.runs = Map.copyOf(runs);
    this.footprints = new TimetableConflictChecker.Footprints(index);
    this.vehicleOf = vehicleOf;
  }

  /** 从同一次成品表检查的全部运行与待命建立只读基础投影，邻表占用也必须包含在内。 */
  static TimetableWaitingCapacity of(
      Timetable timetable,
      TimetableOccupancyProjector.Occupancy occupancy,
      TimetableConflictChecker.GraphIndex index,
      Map<UUID, TimetableConflictChecker.RouteProfile> profiles) {
    Map<String, TimetableConflictChecker.Platform> origins = new HashMap<>();
    if (timetable != null) {
      for (TimetableTrip trip : timetable.trips()) {
        timetable
            .routePlan(trip.routeId())
            .ifPresent(
                plan -> {
                  String node = plan.originNodeId();
                  origins.put(
                      trip.tripCode(),
                      new TimetableConflictChecker.Platform(
                          node, TimetableConflictChecker.groupOf(node), false));
                });
      }
    }
    TimetableOccupancyProjector.Occupancy actual =
        occupancy == null
            ? new TimetableOccupancyProjector.Occupancy(List.of(), List.of())
            : occupancy;
    Map<String, Run> runs = new HashMap<>();
    for (TimetableConflictChecker.Movement movement : actual.movements()) {
      TimetableConflictChecker.RouteProfile profile = profiles.get(movement.routeId());
      if (profile != null && movement.owner().isEmpty()) {
        profile.origin().ifPresent(platform -> origins.put(movement.code(), platform));
        runs.put(movement.code(), new Run(movement.startSeconds(), profile));
      }
    }
    Function<String, String> vehicleOf =
        timetable == null ? Function.identity() : TimetableConflictChecker.vehicleOf(timetable);
    TimetableWaitingCapacity out =
        new TimetableWaitingCapacity(
            origins,
            runs,
            index == null ? TimetableConflictChecker.GraphIndex.of(null) : index,
            vehicleOf);
    TimetableConflictChecker.projectInto(
        out.resources, out.footprints, profiles, actual.movements(), actual.stays(), vehicleOf);
    return out;
  }

  /** 车库等待不占站台；其余等待必须在完整窗口及安全裕量内满足同车去重与股道资格。 */
  boolean hasRoom(String code, String group, int from, int waitSeconds, int separation) {
    if (group == null || group.isBlank()) {
      return true;
    }
    return hasRoom(
        code,
        origins.getOrDefault(code, new TimetableConflictChecker.Platform("", group, true)),
        from,
        waitSeconds,
        separation);
  }

  /**
   * 途中停车点上的等待：股道取那一站的站台，在已有占用上试放 {@code [表定发车, 表定发车 + 等待]}。
   *
   * <p>只有<b>等待期间才来</b>的车算挡住：进入时刻晚于"表定发车 + 裕量"的占用。车按表定发车也挡得住的那些（前车刚离开、裕量盖住了发车，
   * 或紧跟着进站的车）是到站时就有的冲突，各自另有一条记录，不是多等造成的。
   */
  boolean hasRoom(String code, EnRoute stop, int waitSeconds, int separation) {
    int sep = Math.max(0, separation);
    int from = stop.departure();
    int until = (int) Math.min(Integer.MAX_VALUE, (long) from + Math.max(0, waitSeconds));
    Hold hold = new Hold(code, stop.platform(), from, until, sep);
    return enRouteAnswers.computeIfAbsent(
        hold,
        key -> {
          Map<String, TimetableConflictChecker.Resource> candidate = new LinkedHashMap<>();
          Map<String, TimetableConflictChecker.Window> windows =
              TimetableConflictChecker.projectInto(
                  candidate,
                  footprints,
                  Map.of(),
                  List.of(),
                  List.of(
                      new TimetableConflictChecker.Stay(
                          code, stop.platform(), from, until, Optional.empty())),
                  vehicleOf);
          TimetableConflictChecker.mergeOverlapping(candidate, resources, sep);
          long latest = (long) from + sep;
          return TimetableConflictChecker.scanWindows(candidate, windows, sep).conflicts().stream()
              .noneMatch(conflict -> conflict.secondFrom() > latest);
        });
  }

  private boolean hasRoom(
      String code,
      TimetableConflictChecker.Platform platform,
      int from,
      int waitSeconds,
      int separation) {
    int until = (int) Math.min(Integer.MAX_VALUE, (long) from + Math.max(0, waitSeconds));
    Hold hold = new Hold(code, platform, from, until, Math.max(0, separation));
    return answers.computeIfAbsent(
        hold,
        key ->
            TimetableConflictChecker.accommodatesStay(
                resources,
                footprints,
                new TimetableConflictChecker.Stay(code, platform, from, until, Optional.empty()),
                vehicleOf,
                key.separation()));
  }

  /**
   * 后车在冲突资源之前最后一个停车的车站：运行时它在那一站的发车门控被挡住（或驶出后在区间信号前等），不会回到起点去等。
   *
   * <p>取表定发车不晚于进入冲突资源时刻的最后一个途中停车点（PASS 不算）。冲突在第一个停车点之前（含到站本身）、 那一站不是站台、或手上没有这次运行的档案时为空，仍按起点判。
   *
   * @param code 后车的运行 code
   * @param enterSecond 后车进入冲突资源的时刻（相对零点）
   */
  Optional<EnRoute> enRoute(String code, int enterSecond) {
    Run run = runs.get(code);
    if (run == null) {
      return Optional.empty();
    }
    List<TimetableStop> stops = run.profile().stops();
    List<TimetableConflictChecker.Platform> platforms = run.profile().platforms();
    for (int i = stops.size() - 2; i >= 1; i--) {
      TimetableStop stop = stops.get(i);
      if (!stop.stops()) {
        continue;
      }
      int departure = run.start() + stop.departureOffsetSeconds();
      if (departure > enterSecond) {
        continue;
      }
      if (i >= platforms.size() || platforms.get(i).absent()) {
        return Optional.empty();
      }
      return Optional.of(new EnRoute(i, platforms.get(i), departure));
    }
    return Optional.empty();
  }
}
