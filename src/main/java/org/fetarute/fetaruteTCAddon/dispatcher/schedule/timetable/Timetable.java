package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 一条线路的一份时刻表：由 FTCA 主动生成的运营计划。
 *
 * <p>三层结构，各自回答一个问题：
 *
 * <ul>
 *   <li>{@link #routePlans()} —— 每条 route 跑一趟要多久（从路网算出来的站间时分 + 目标服务比例）
 *   <li>{@link #trips()} —— 今天几点各发哪条线的车（由 weight 决定，不是抽签）
 *   <li>{@link #duties()} —— 每一班由哪辆车跑，以及那辆车什么时候必须回库
 * </ul>
 *
 * <p>三层刻意分开，因为它们的变更原因不同：改限速只影响第一层，改运营比例只影响第二层， 改车辆周转策略只影响第三层。把它们揉在一起时，任何一处调整都要重算全部。
 *
 * <p>时刻表<b>不是</b>从历史跑车记录聚合出来的。实际运行数据只用于事后对表（validation/calibration）， 不参与构建；参见 {@code
 * docs/dev/timetable.md}。
 *
 * @param id 时刻表 UUID
 * @param companyId 公司 UUID
 * @param operatorId 运营商 UUID
 * @param lineId 线路 UUID
 * @param code 时刻表 code，在同一条线路下唯一
 * @param name 展示名
 * @param status 生命周期状态
 * @param zoneId 发车时刻所用时区
 * @param serviceStartSecondOfDay 计划窗口起点（当日秒数）
 * @param serviceEndSecondOfDay 计划窗口终点（相对同一服务日的秒数，可超过一天）
 * @param routePlans 各 route 的时分档案与目标比例
 * @param trips 发车表，按发车时刻升序
 * @param duties 车辆交路
 * @param notes 备注
 * @param createdAt 创建时间
 * @param updatedAt 最后更新时间
 */
public record Timetable(
    UUID id,
    UUID companyId,
    UUID operatorId,
    UUID lineId,
    String code,
    String name,
    TimetableStatus status,
    ZoneId zoneId,
    int serviceStartSecondOfDay,
    int serviceEndSecondOfDay,
    List<TimetableRoutePlan> routePlans,
    List<TimetableTrip> trips,
    List<VehicleDuty> duties,
    Optional<String> notes,
    Instant createdAt,
    Instant updatedAt) {

  public Timetable {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(companyId, "companyId");
    Objects.requireNonNull(operatorId, "operatorId");
    Objects.requireNonNull(lineId, "lineId");
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(zoneId, "zoneId");
    Objects.requireNonNull(createdAt, "createdAt");
    Objects.requireNonNull(updatedAt, "updatedAt");
    code = code == null ? "" : code.trim();
    if (code.isBlank()) {
      throw new IllegalArgumentException("时刻表 code 不能为空");
    }
    name = name == null || name.isBlank() ? code : name.trim();
    if (serviceEndSecondOfDay < serviceStartSecondOfDay) {
      throw new IllegalArgumentException("计划窗口终点不能早于起点");
    }
    routePlans =
        routePlans == null
            ? List.of()
            : routePlans.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(TimetableRoutePlan::routeCode))
                .toList();
    trips =
        trips == null
            ? List.of()
            : trips.stream()
                .filter(Objects::nonNull)
                .sorted(
                    Comparator.comparingInt(TimetableTrip::departureSecondOfDay)
                        .thenComparing(TimetableTrip::tripCode))
                .toList();
    duties =
        duties == null
            ? List.of()
            : duties.stream()
                .filter(Objects::nonNull)
                .sorted(
                    Comparator.comparingInt(VehicleDuty::plannedStartSecondOfDay)
                        .thenComparing(VehicleDuty::dutyCode))
                .toList();
    notes = notes == null ? Optional.empty() : notes.map(String::trim).filter(s -> !s.isBlank());
  }

  /** 是否处于运行时会消费的状态。 */
  public boolean published() {
    return status == TimetableStatus.PUBLISHED;
  }

  /** 本时刻表覆盖的 route 集合（含外方走行线路）。 */
  public List<UUID> routeIds() {
    return routePlans.stream().map(TimetableRoutePlan::routeId).toList();
  }

  /** 受本表管辖的 route：发布后它们的 headway 票会被拦下改按表发车。外方的走行线路不在其中——那是别人线路的资源， 我只是借它出库/回库，不能把人家自己的发车也拦掉。 */
  public List<UUID> managedRouteIds() {
    return routePlans.stream()
        .filter(plan -> !plan.external())
        .map(TimetableRoutePlan::routeId)
        .toList();
  }

  /** 按 route 查计划。 */
  public Optional<TimetableRoutePlan> routePlan(UUID routeId) {
    if (routeId == null) {
      return Optional.empty();
    }
    for (TimetableRoutePlan plan : routePlans) {
      if (plan.routeId().equals(routeId)) {
        return Optional.of(plan);
      }
    }
    return Optional.empty();
  }

  /** 按车次号查发车记录。 */
  public Optional<TimetableTrip> tripByCode(String tripCode) {
    if (tripCode == null || tripCode.isBlank()) {
      return Optional.empty();
    }
    String normalized = tripCode.trim();
    for (TimetableTrip trip : trips) {
      if (trip.tripCode().equalsIgnoreCase(normalized)) {
        return Optional.of(trip);
      }
    }
    return Optional.empty();
  }

  /** 按 UUID 查车次。 */
  public Optional<TimetableTrip> trip(UUID tripId) {
    if (tripId == null) {
      return Optional.empty();
    }
    for (TimetableTrip trip : trips) {
      if (trip.id().equals(tripId)) {
        return Optional.of(trip);
      }
    }
    return Optional.empty();
  }

  /** 按 duty UUID 查车辆交路。 */
  public Optional<VehicleDuty> duty(UUID dutyId) {
    if (dutyId == null) {
      return Optional.empty();
    }
    for (VehicleDuty duty : duties) {
      if (duty.id().equals(dutyId)) {
        return Optional.of(duty);
      }
    }
    return Optional.empty();
  }

  /**
   * 计算某趟车在某个停靠点的计划发车时间。
   *
   * <p>这是"按表运行"唯一的时间来源：扣留判定、晚点统计与导出都必须走这里，避免同一件事出现两种算法。
   *
   * @param trip 车次
   * @param stopSequence 停靠序号
   * @param serviceDate 服务日期
   * @return 计划发车时间；该 route 或停靠点不在表内时返回空
   */
  public Optional<Instant> scheduledDeparture(
      TimetableTrip trip, int stopSequence, LocalDate serviceDate) {
    if (trip == null || serviceDate == null) {
      return Optional.empty();
    }
    return routePlan(trip.routeId())
        .flatMap(plan -> plan.stopAt(stopSequence))
        .map(
            stop ->
                trip.departureAt(serviceDate, zoneId).plusSeconds(stop.departureOffsetSeconds()));
  }

  /**
   * 计算某趟车在某个停靠点的计划到达时间。
   *
   * @param trip 车次
   * @param stopSequence 停靠序号
   * @param serviceDate 服务日期
   * @return 计划到达时间；该 route 或停靠点不在表内时返回空
   */
  public Optional<Instant> scheduledArrival(
      TimetableTrip trip, int stopSequence, LocalDate serviceDate) {
    if (trip == null || serviceDate == null) {
      return Optional.empty();
    }
    return routePlan(trip.routeId())
        .flatMap(plan -> plan.stopAt(stopSequence))
        .map(
            stop -> trip.departureAt(serviceDate, zoneId).plusSeconds(stop.arrivalOffsetSeconds()));
  }

  /**
   * 某趟车在某个日历日发车时，它属于哪个<b>服务日</b>。
   *
   * <p>发车时刻存的是取模后的当日秒数，跨零点的班次会落到下一个日历日；而车辆交路的出库/回库时刻不取模，始终相对服务日。 两边要对上同一个
   * duty，必须用同一个日期口径：首班发车时刻早于计划窗口起点的班次，服务日就是前一天。
   *
   * @param trip 车次
   * @param calendarDate 该次发车实际落在的日历日
   * @return 服务日
   */
  public LocalDate serviceDayOf(TimetableTrip trip, LocalDate calendarDate) {
    Objects.requireNonNull(trip, "trip");
    Objects.requireNonNull(calendarDate, "calendarDate");
    return trip.departureSecondOfDay() < serviceStartSecondOfDay
        ? calendarDate.minusDays(1)
        : calendarDate;
  }

  /**
   * {@link #serviceDayOf} 的逆：某趟车在某个服务日里的起点发车时刻。
   *
   * <p>跨零点的班次（发车时刻早于计划窗口起点）落在服务日的下一个日历日。
   *
   * @param trip 车次
   * @param serviceDay 服务日
   * @return 起点绝对发车时间
   */
  public Instant departureOnServiceDay(TimetableTrip trip, LocalDate serviceDay) {
    Objects.requireNonNull(trip, "trip");
    Objects.requireNonNull(serviceDay, "serviceDay");
    LocalDate calendarDate =
        trip.departureSecondOfDay() < serviceStartSecondOfDay ? serviceDay.plusDays(1) : serviceDay;
    return trip.departureAt(calendarDate, zoneId);
  }

  /** 返回替换了发车表与 duty 的新实例，供加载后回填。 */
  public Timetable withTripsAndDuties(List<TimetableTrip> nextTrips, List<VehicleDuty> nextDuties) {
    return new Timetable(
        id,
        companyId,
        operatorId,
        lineId,
        code,
        name,
        status,
        zoneId,
        serviceStartSecondOfDay,
        serviceEndSecondOfDay,
        routePlans,
        nextTrips,
        nextDuties,
        notes,
        createdAt,
        updatedAt);
  }
}
