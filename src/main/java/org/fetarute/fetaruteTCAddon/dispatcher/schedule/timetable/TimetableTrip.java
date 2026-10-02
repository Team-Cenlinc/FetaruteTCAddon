package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 时刻表中的一趟车：哪条 route、几点从起点开出、属于哪个车辆交路。
 *
 * <p>沿途各站的时刻由所属 {@link TimetableRoutePlan} 的站间时分档案叠加在 {@link #departureSecondOfDay()} 上得出。
 *
 * <p>发车时刻存的是"当日秒数"而不是绝对时间：时刻表按服务日重复，绝对时间必须在运行时结合服务日期与时区换算， 而换算入口只有 {@link #departureAt(LocalDate,
 * ZoneId)} 一处，避免出现两套口径。
 *
 * <p>{@code dutyId} 是"这一班由哪辆车跑"的答案，与"下一班该跑哪条线"完全分开：前者由 {@link VehicleDutyPlanner}
 * 在班次排定<b>之后</b>指派，不允许反过来影响服务比例。
 *
 * @param id 车次 UUID
 * @param timetableId 所属时刻表
 * @param routeId 执行的 Route
 * @param sequence 表内顺序（按发车时刻升序）
 * @param tripCode 人类可读车次号，在同一份时刻表内唯一
 * @param departureSecondOfDay 起点发车时刻（当日秒数，0..86399）
 * @param dutyId 承担本班次的车辆交路
 */
public record TimetableTrip(
    UUID id,
    UUID timetableId,
    UUID routeId,
    int sequence,
    String tripCode,
    int departureSecondOfDay,
    Optional<UUID> dutyId) {

  /** 一个服务日的秒数，用于校验发车时刻取值范围。 */
  public static final int SECONDS_PER_DAY = 86_400;

  /** 固定宽度时刻格式；{@code LocalTime#toString()} 会在秒为 0 时省略秒段。 */
  private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss");

  public TimetableTrip {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(timetableId, "timetableId");
    Objects.requireNonNull(routeId, "routeId");
    if (sequence < 0) {
      throw new IllegalArgumentException("sequence 不能为负");
    }
    tripCode = tripCode == null ? "" : tripCode.trim();
    if (tripCode.isBlank()) {
      throw new IllegalArgumentException("tripCode 不能为空");
    }
    if (departureSecondOfDay < 0 || departureSecondOfDay >= SECONDS_PER_DAY) {
      throw new IllegalArgumentException("departureSecondOfDay 超出一天范围: " + departureSecondOfDay);
    }
    dutyId = dutyId == null ? Optional.empty() : dutyId;
  }

  /**
   * 把当日秒数换算成指定服务日的绝对发车时间。
   *
   * <p>{@code atStartOfDay(zone).plusSeconds(...)} 走的是时间线加法而非本地时刻构造，因此夏令时切换日
   * 也唯一确定，不会因为缺口或重叠退化成两个候选。
   *
   * @param serviceDate 服务日期
   * @param zone 时刻表所用时区
   * @return 绝对发车时间
   */
  public Instant departureAt(LocalDate serviceDate, ZoneId zone) {
    Objects.requireNonNull(serviceDate, "serviceDate");
    Objects.requireNonNull(zone, "zone");
    return serviceDate.atStartOfDay(zone).plusSeconds(departureSecondOfDay).toInstant();
  }

  /** 供命令与导出使用的 {@code HH:mm:ss} 文本。 */
  public String departureText() {
    return LocalTime.ofSecondOfDay(departureSecondOfDay).format(CLOCK);
  }

  /** 返回绑定了 duty 的新实例，供 duty 规划完成后回填。 */
  public TimetableTrip withDuty(UUID duty) {
    return new TimetableTrip(
        id,
        timetableId,
        routeId,
        sequence,
        tripCode,
        departureSecondOfDay,
        Optional.ofNullable(duty));
  }
}
