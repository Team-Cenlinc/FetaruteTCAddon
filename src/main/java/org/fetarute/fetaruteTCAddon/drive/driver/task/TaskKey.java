package org.fetarute.fetaruteTCAddon.drive.driver.task;

import java.time.LocalDate;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * 一个车次：时刻表、车次号与运营日。站台发车记录没有车次 UUID，车次号是与列车绑定对上的键。
 *
 * @param timetableId 时刻表
 * @param tripCode 车次号（统一大写）
 * @param serviceDate 运营日（始发站发车的日期）
 */
public record TaskKey(UUID timetableId, String tripCode, LocalDate serviceDate) {
  public TaskKey {
    Objects.requireNonNull(timetableId, "timetableId");
    Objects.requireNonNull(serviceDate, "serviceDate");
    tripCode = Objects.requireNonNull(tripCode, "tripCode").trim().toUpperCase(Locale.ROOT);
  }

  /** 与一条车次绑定是否是同一个车次。 */
  public boolean matches(UUID otherTimetable, String otherTrip, LocalDate otherDate) {
    return timetableId.equals(otherTimetable)
        && otherTrip != null
        && tripCode.equalsIgnoreCase(otherTrip.trim())
        && serviceDate.equals(otherDate);
  }
}
