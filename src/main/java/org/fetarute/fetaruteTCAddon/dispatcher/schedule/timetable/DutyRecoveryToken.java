package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * 交路恢复标记的版本化编码。表版本与服务日共同限定身份，避免重编表或隔日开服时复用旧归属。
 *
 * @param key 交路身份
 * @param tableVersion 时刻表更新时刻（epoch millis，与 SQL 精度一致）
 * @param assignedTrips 已接班的位置，出库走行为 0
 * @param lastTrip 最近车次；纯出库走行没有展示车次时为空
 */
record DutyRecoveryToken(
    TimetableService.DutyKey key, long tableVersion, int assignedTrips, Optional<UUID> lastTrip) {
  String encode() {
    return "1,"
        + key.timetableId()
        + ","
        + tableVersion
        + ","
        + key.dutyId()
        + ","
        + key.serviceDate()
        + ","
        + assignedTrips
        + ","
        + lastTrip.map(UUID::toString).orElse("-");
  }

  static Optional<DutyRecoveryToken> parse(String value) {
    if (value == null) {
      return Optional.empty();
    }
    String[] parts = value.split(",", -1);
    if (parts.length != 7 || !parts[0].equals("1")) {
      return Optional.empty();
    }
    try {
      int reached = Integer.parseInt(parts[5]);
      if (reached < 0) {
        return Optional.empty();
      }
      return Optional.of(
          new DutyRecoveryToken(
              new TimetableService.DutyKey(
                  UUID.fromString(parts[1]), UUID.fromString(parts[3]), LocalDate.parse(parts[4])),
              Long.parseLong(parts[2]),
              reached,
              parts[6].equals("-") ? Optional.empty() : Optional.of(UUID.fromString(parts[6]))));
    } catch (RuntimeException ex) {
      return Optional.empty();
    }
  }
}
