package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.StringJoiner;

/**
 * 把时刻表导出为 stop-level CSV。
 *
 * <p>时刻写成 {@code HH:mm:ss}，跨零点的班次在时刻后加 {@code +1} 后缀（与纸质时刻表一致），
 * 而不是回绕成看起来像清晨的时间——回绕过的表在排序时会把末班车排到最前面。
 *
 * <p>每行同时带上 {@code duty_code}：车辆周转是这份表的一部分，不是实现细节。运营方需要一眼看出 哪几趟车是同一辆车连着跑的、它什么时候回库。
 */
public final class TimetableCsvExporter {

  /** 固定宽度时刻格式；{@code LocalTime#toString()} 会在秒为 0 时省略秒段，导出表格不能有这种不齐。 */
  private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss");

  /** CSV 表头。 */
  public static final String HEADER =
      "timetable_code,timetable_name,status,zone,trip_code,route_code,duty_code,"
          + "stop_sequence,station_code,node_id,arrival,departure,dwell_seconds";

  private TimetableCsvExporter() {}

  /**
   * 导出整份时刻表。
   *
   * @param timetable 时刻表
   * @return CSV 文本；空表只含表头
   */
  public static String export(Timetable timetable) {
    StringBuilder out = new StringBuilder(HEADER).append('\n');
    if (timetable == null) {
      return out.toString();
    }
    for (TimetableTrip trip : timetable.trips()) {
      Optional<TimetableRoutePlan> planOpt = timetable.tripPlan(trip);
      if (planOpt.isEmpty()) {
        continue;
      }
      TimetableRoutePlan plan = planOpt.get();
      String dutyCode =
          trip.dutyId().flatMap(timetable::duty).map(VehicleDuty::dutyCode).orElse("");
      for (TimetableStop stop : plan.stops()) {
        out.append(row(timetable, trip, plan, dutyCode, stop)).append('\n');
      }
    }
    return out.toString();
  }

  private static String row(
      Timetable timetable,
      TimetableTrip trip,
      TimetableRoutePlan plan,
      String dutyCode,
      TimetableStop stop) {
    StringJoiner row = new StringJoiner(",");
    row.add(csv(timetable.code()));
    row.add(csv(timetable.name()));
    row.add(csv(timetable.status().name()));
    row.add(csv(timetable.zoneId().getId()));
    row.add(csv(trip.tripCode()));
    row.add(csv(plan.routeCode()));
    row.add(csv(dutyCode));
    row.add(csv(String.valueOf(stop.stopSequence())));
    row.add(csv(stop.stationCode().orElse("")));
    row.add(csv(stop.nodeId().orElse("")));
    row.add(csv(clock(trip.departureSecondOfDay() + stop.arrivalOffsetSeconds())));
    row.add(csv(clock(trip.departureSecondOfDay() + stop.departureOffsetSeconds())));
    row.add(csv(String.valueOf(stop.dwell().toSeconds())));
    return row.toString();
  }

  /**
   * 把"当日秒数 + 偏移"渲染成时刻文本。
   *
   * @param secondOfDay 可能超过一天的秒数
   * @return {@code HH:mm:ss}，跨日时追加 {@code +N}
   */
  public static String clock(int secondOfDay) {
    // duty 的出库时刻可能早于服务日零点（负数），回库时刻可能跨过零点（超过一天）：都带上天数偏移。
    int days = Math.floorDiv(secondOfDay, TimetableTrip.SECONDS_PER_DAY);
    int withinDay = Math.floorMod(secondOfDay, TimetableTrip.SECONDS_PER_DAY);
    String text = LocalTime.ofSecondOfDay(withinDay).format(CLOCK);
    if (days == 0) {
      return text;
    }
    return days > 0 ? text + "+" + days : text + days;
  }

  private static String csv(String value) {
    if (value == null || value.isEmpty()) {
      return "";
    }
    if (value.indexOf(',') < 0 && value.indexOf('"') < 0 && value.indexOf('\n') < 0) {
      return value;
    }
    return '"' + value.replace("\"", "\"\"") + '"';
  }
}
