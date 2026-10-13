package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * 整趟晚发时的始发站等待容量：在已有占用上试放完整等待窗口。
 *
 * <p>复用冲突模型投影与扫描，避免把待命记录条数当成车辆数，也避免只看站台组总容量而忽略具体股道或 DYNAMIC 资格。 资源仅在创建时投影一次；每次查询只复制等待涉及的站台桶与窗口内占用。
 */
final class TimetableWaitingCapacity {
  private final Map<String, TimetableConflictChecker.Resource> resources = new LinkedHashMap<>();
  private final Map<String, TimetableConflictChecker.Platform> origins;
  private final TimetableConflictChecker.Footprints footprints;
  private final Function<String, String> vehicleOf;
  private final Map<Hold, Boolean> answers = new HashMap<>();

  private record Hold(String code, String group, int from, int until, int separation) {}

  private TimetableWaitingCapacity(
      Map<String, TimetableConflictChecker.Platform> origins,
      TimetableConflictChecker.GraphIndex index,
      Function<String, String> vehicleOf) {
    this.origins = Map.copyOf(origins);
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
    for (TimetableConflictChecker.Movement movement : actual.movements()) {
      TimetableConflictChecker.RouteProfile profile = profiles.get(movement.routeId());
      if (profile != null && movement.owner().isEmpty()) {
        profile.origin().ifPresent(platform -> origins.put(movement.code(), platform));
      }
    }
    Function<String, String> vehicleOf =
        timetable == null ? Function.identity() : TimetableConflictChecker.vehicleOf(timetable);
    TimetableWaitingCapacity out =
        new TimetableWaitingCapacity(
            origins,
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
    int until = (int) Math.min(Integer.MAX_VALUE, (long) from + Math.max(0, waitSeconds));
    Hold hold = new Hold(code, group, from, until, Math.max(0, separation));
    return answers.computeIfAbsent(
        hold,
        key -> {
          TimetableConflictChecker.Platform platform =
              origins.getOrDefault(code, new TimetableConflictChecker.Platform("", group, true));
          return TimetableConflictChecker.accommodatesStay(
              resources,
              footprints,
              new TimetableConflictChecker.Stay(code, platform, from, until, Optional.empty()),
              vehicleOf,
              key.separation());
        });
  }
}
