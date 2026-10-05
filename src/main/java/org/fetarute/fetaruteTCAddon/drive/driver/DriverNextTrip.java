package org.fetarute.fetaruteTCAddon.drive.driver;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 列车本趟终到后接续担当的下一趟（按交路）：驾驶员在到终点站之前就能知道这列车接下来开往哪里。
 *
 * @param tripId 车次的唯一标识（判断是否同一趟）
 * @param tripCode 车次号
 * @param destination 终点站站名；查不到停靠表时为空串
 * @param departure 计划发车时刻
 */
public record DriverNextTrip(UUID tripId, String tripCode, String destination, Instant departure) {

  public DriverNextTrip {
    Objects.requireNonNull(tripId, "tripId");
    tripCode = tripCode == null ? "" : tripCode;
    destination = destination == null ? "" : destination;
    Objects.requireNonNull(departure, "departure");
  }
}
