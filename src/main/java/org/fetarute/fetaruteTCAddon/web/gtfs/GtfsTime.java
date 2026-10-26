package org.fetarute.fetaruteTCAddon.web.gtfs;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Locale;

/** GTFS 的日期与时刻写法：时刻可以超过 24 点，日期一律 {@code YYYYMMDD}。 */
public final class GtfsTime {

  /** 日历的有效期：起点前 1 天、终点后 30 天，随每次生成自动往后滚。 */
  static final int CALENDAR_BEFORE_DAYS = 1;

  static final int CALENDAR_AFTER_DAYS = 30;

  private GtfsTime() {}

  /**
   * 当日秒数写成 {@code HH:MM:SS}；小时允许超过 24（跨零点的班次写 {@code 24:xx:xx}），这是 GTFS 允许的写法。
   *
   * @param seconds 相对服务日零点的秒数；负数按 0 算
   */
  public static String timeOfDay(int seconds) {
    int safe = Math.max(0, seconds);
    int hours = safe / 3600;
    int minutes = safe % 3600 / 60;
    int secs = safe % 60;
    return String.format(Locale.ROOT, "%02d:%02d:%02d", hours, minutes, secs);
  }

  /** GTFS 日期：{@code YYYYMMDD}。 */
  public static String date(LocalDate date) {
    return String.format(
        Locale.ROOT, "%04d%02d%02d", date.getYear(), date.getMonthValue(), date.getDayOfMonth());
  }

  /** 日历起点：生成时刻所在日历日的前一天。 */
  public static LocalDate calendarStart(Instant now, ZoneId zone) {
    return LocalDate.ofInstant(now, zone).minusDays(CALENDAR_BEFORE_DAYS);
  }

  /** 日历终点：生成时刻所在日历日的后 30 天。 */
  public static LocalDate calendarEnd(Instant now, ZoneId zone) {
    return LocalDate.ofInstant(now, zone).plusDays(CALENDAR_AFTER_DAYS);
  }
}
