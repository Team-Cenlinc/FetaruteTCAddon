package org.fetarute.fetaruteTCAddon.dispatcher.schedule.model;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * 某个未来时间窗内的计划车次快照。
 *
 * <p>ScheduleWindow 是人工审阅、CSV 导出和机器快照导出的边界对象。第一阶段只保存由 headway 配置派生出的未来窗口，不承担数据库 source of truth
 * 职责；后续若引入 schedule_windows/schedule_trips 表，可以直接以该对象作为快照来源。
 *
 * @param generatedAt 生成快照的现实时间
 * @param windowStart 窗口开始时间
 * @param windowEnd 窗口结束时间
 * @param trips 窗口内按计划发车时间排序的车次
 */
public record ScheduleWindow(
    Instant generatedAt, Instant windowStart, Instant windowEnd, List<ServiceTrip> trips) {

  public ScheduleWindow {
    generatedAt = generatedAt == null ? Instant.EPOCH : generatedAt;
    Objects.requireNonNull(windowStart, "windowStart");
    Objects.requireNonNull(windowEnd, "windowEnd");
    if (windowEnd.isBefore(windowStart)) {
      throw new IllegalArgumentException("windowEnd 不能早于 windowStart");
    }
    if (trips == null) {
      trips = List.of();
    } else {
      List<ServiceTrip> sorted =
          trips.stream()
              .filter(Objects::nonNull)
              .sorted(
                  Comparator.comparing(ServiceTrip::plannedDeparture)
                      .thenComparing(ServiceTrip::operatorCode, String.CASE_INSENSITIVE_ORDER)
                      .thenComparing(ServiceTrip::lineCode, String.CASE_INSENSITIVE_ORDER)
                      .thenComparing(ServiceTrip::routeCode, String.CASE_INSENSITIVE_ORDER)
                      .thenComparing(ServiceTrip::tripId))
              .toList();
      trips = List.copyOf(sorted);
    }
  }

  public static ScheduleWindow empty(Instant start, Instant end) {
    return new ScheduleWindow(Instant.EPOCH, start, end, List.of());
  }
}
