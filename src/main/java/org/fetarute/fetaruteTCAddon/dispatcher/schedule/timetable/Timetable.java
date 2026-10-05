package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.AbstractList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.RandomAccess;
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
        new IndexedPlans(
            routePlans == null
                ? List.of()
                : routePlans.stream()
                    .filter(Objects::nonNull)
                    .sorted(Comparator.comparing(TimetableRoutePlan::routeCode))
                    .toList());
    trips =
        new IndexedTrips(
            trips == null
                ? List.of()
                : trips.stream()
                    .filter(Objects::nonNull)
                    .sorted(
                        Comparator.comparingInt(TimetableTrip::departureSecondOfDay)
                            .thenComparing(TimetableTrip::tripCode))
                    .toList());
    duties =
        new IndexedDuties(
            duties == null
                ? List.of()
                : duties.stream()
                    .filter(Objects::nonNull)
                    .sorted(
                        Comparator.comparingInt(VehicleDuty::plannedStartSecondOfDay)
                            .thenComparing(VehicleDuty::dutyCode))
                    .toList());
    notes = notes == null ? Optional.empty() : notes.map(String::trim).filter(s -> !s.isBlank());
  }

  /** 是否处于运行时会消费的状态。 */
  public boolean published() {
    return status == TimetableStatus.PUBLISHED;
  }

  /** 本时刻表覆盖的 route 集合（含外方走行线路）。 */
  public List<UUID> routeIds() {
    return routePlans.stream().map(TimetableRoutePlan::routeId).distinct().toList();
  }

  /** 受本表管辖的 route：发布后它们的 headway 票会被拦下改按表发车。外方的走行线路不在其中——那是别人线路的资源， 我只是借它出库/回库，不能把人家自己的发车也拦掉。 */
  public List<UUID> managedRouteIds() {
    return routePlans.stream()
        .filter(plan -> !plan.external())
        .map(TimetableRoutePlan::routeId)
        .distinct()
        .toList();
  }

  /** 按 route 查计划。区分车型的表里同一条 route 有几份（每个车型一份变体）时，返回不分车型的基础计划（允许车型里最慢的那份）。 */
  public Optional<TimetableRoutePlan> routePlan(UUID routeId) {
    if (routeId == null) {
      return Optional.empty();
    }
    return Optional.ofNullable(((IndexedPlans) routePlans).base.get(routeId));
  }

  /**
   * 按 route 与车型查计划：有这个车型的变体就用它，否则退回 {@link #routePlan(UUID)}。
   *
   * @param routeId route
   * @param consist 车型；为空时等同 {@link #routePlan(UUID)}
   */
  public Optional<TimetableRoutePlan> routePlan(UUID routeId, Optional<String> consist) {
    if (routeId != null && consist != null && consist.isPresent()) {
      TimetableRoutePlan variant =
          ((IndexedPlans) routePlans).byConsist.getOrDefault(routeId, Map.of()).get(consist.get());
      if (variant != null) {
        return Optional.of(variant);
      }
    }
    return routePlan(routeId);
  }

  /** 按车次号查发车记录。 */
  public Optional<TimetableTrip> tripByCode(String tripCode) {
    if (tripCode == null || tripCode.isBlank()) {
      return Optional.empty();
    }
    return ((IndexedTrips) trips).byCode(tripCode.trim());
  }

  /** 按 UUID 查车次。 */
  public Optional<TimetableTrip> trip(UUID tripId) {
    if (tripId == null) {
      return Optional.empty();
    }
    return ((IndexedTrips) trips).byId(tripId);
  }

  /** 按 duty UUID 查车辆交路。 */
  public Optional<VehicleDuty> duty(UUID dutyId) {
    if (dutyId == null) {
      return Optional.empty();
    }
    return ((IndexedDuties) duties).byId(dutyId);
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
    return tripPlan(trip)
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
    return tripPlan(trip)
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

  /** 换一份 route 计划、发车表与交路，其余不变（车型变体折回基础 route 时用）。 */
  public Timetable withPlansTripsAndDuties(
      List<TimetableRoutePlan> nextPlans,
      List<TimetableTrip> nextTrips,
      List<VehicleDuty> nextDuties) {
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
        nextPlans,
        nextTrips,
        nextDuties,
        notes,
        createdAt,
        updatedAt);
  }

  /**
   * 车次按它那辆车的车型跑的计划：交路带车型且表里有这个车型的变体时取变体，否则取基础计划。
   *
   * <p>按表运行里凡是要用某一班的各站时刻（扣车、晚点账、站牌、ETA），都走这里，而不是 {@link #routePlan(UUID)}。
   *
   * @param trip 车次
   * @return 计划；route 不在表内时为空
   */
  public Optional<TimetableRoutePlan> tripPlan(TimetableTrip trip) {
    return trip == null ? Optional.empty() : routePlan(trip.routeId(), consistOf(trip));
  }

  /**
   * 车次的车型：它所在交路的车型。不区分车型的表、或车次不挂交路时为空。
   *
   * @param trip 车次
   */
  public Optional<String> consistOf(TimetableTrip trip) {
    return trip == null
        ? Optional.empty()
        : trip.dutyId().flatMap(this::duty).flatMap(VehicleDuty::consist);
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

  /**
   * route 计划表：只读列表，附带按 route（基础计划）与按 route × 车型（变体计划）的索引。
   *
   * <p>门控每秒按车次逐班取计划，区分车型的表里计划数是 route 数的几倍，线性扫描会成为热点。基础计划取不分车型的那份，没有时取顺序在前的一份； 同一 route
   * 同一车型重复时取顺序在前的一份，与逐条查找结果相同。
   */
  private static final class IndexedPlans extends AbstractList<TimetableRoutePlan>
      implements RandomAccess {

    private final List<TimetableRoutePlan> plans;
    private final Map<UUID, TimetableRoutePlan> base;
    private final Map<UUID, Map<String, TimetableRoutePlan>> byConsist;

    private IndexedPlans(List<TimetableRoutePlan> plans) {
      this.plans = plans;
      Map<UUID, TimetableRoutePlan> plain = new HashMap<>();
      Map<UUID, TimetableRoutePlan> first = new HashMap<>();
      Map<UUID, Map<String, TimetableRoutePlan>> variants = new HashMap<>();
      for (TimetableRoutePlan plan : plans) {
        first.putIfAbsent(plan.routeId(), plan);
        if (plan.consist().isEmpty()) {
          plain.putIfAbsent(plan.routeId(), plan);
        } else {
          variants
              .computeIfAbsent(plan.routeId(), id -> new HashMap<>())
              .putIfAbsent(plan.consist().get().key(), plan);
        }
      }
      first.putAll(plain);
      this.base = first;
      this.byConsist = variants;
    }

    @Override
    public TimetableRoutePlan get(int index) {
      return plans.get(index);
    }

    @Override
    public int size() {
      return plans.size();
    }
  }

  /** 交路表：只读列表，附带按 UUID 的索引（按表运行每次取车次的车型都要查它所在的交路）。 */
  private static final class IndexedDuties extends AbstractList<VehicleDuty>
      implements RandomAccess {

    private final List<VehicleDuty> duties;
    private final Map<UUID, VehicleDuty> byId;

    private IndexedDuties(List<VehicleDuty> duties) {
      this.duties = duties;
      this.byId = new HashMap<>(duties.size() * 2);
      for (VehicleDuty duty : duties) {
        byId.putIfAbsent(duty.id(), duty);
      }
    }

    @Override
    public VehicleDuty get(int index) {
      return duties.get(index);
    }

    @Override
    public int size() {
      return duties.size();
    }

    private Optional<VehicleDuty> byId(UUID dutyId) {
      return Optional.ofNullable(byId.get(dutyId));
    }
  }

  /**
   * 发车表：只读列表，附带按车次号、按 UUID 的索引。
   *
   * <p>站牌、时刻表 API 每次刷新都要按车次号逐条查表，一份表上千班，线性扫描会成为热点。重复的车次号或 UUID 取发车顺序在前的一条，与逐条查找结果相同。
   */
  private static final class IndexedTrips extends AbstractList<TimetableTrip>
      implements RandomAccess {

    private final List<TimetableTrip> trips;
    private final Map<String, TimetableTrip> byCode;
    private final Map<UUID, TimetableTrip> byId;

    private IndexedTrips(List<TimetableTrip> trips) {
      this.trips = trips;
      this.byCode = new HashMap<>(trips.size() * 2);
      this.byId = new HashMap<>(trips.size() * 2);
      for (TimetableTrip trip : trips) {
        byCode.putIfAbsent(caseKey(trip.tripCode()), trip);
        byId.putIfAbsent(trip.id(), trip);
      }
    }

    @Override
    public TimetableTrip get(int index) {
      return trips.get(index);
    }

    @Override
    public int size() {
      return trips.size();
    }

    private Optional<TimetableTrip> byCode(String tripCode) {
      TimetableTrip trip = byCode.get(caseKey(tripCode));
      return trip != null && trip.tripCode().equalsIgnoreCase(tripCode)
          ? Optional.of(trip)
          : Optional.empty();
    }

    private Optional<TimetableTrip> byId(UUID tripId) {
      return Optional.ofNullable(byId.get(tripId));
    }

    /** 与 {@link String#equalsIgnoreCase} 同一口径的归一化：逐字符先转大写再转小写。 */
    private static String caseKey(String code) {
      StringBuilder builder = new StringBuilder(code.length());
      for (int i = 0; i < code.length(); i++) {
        builder.append(Character.toLowerCase(Character.toUpperCase(code.charAt(i))));
      }
      return builder.toString();
    }
  }
}
