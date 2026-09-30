package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 车次取消登记：整趟没开出，或执行中的车离开运行时、剩下的站不再停。
 *
 * <p>站牌查询与公开事件都读这里（以后做 PIDS 用）。只存内存：新登记时顺手清掉前一天之前发车的记录，重启后清空。 同一趟车只登记第一次；
 * 取消之后又有车绑上这趟车时撤销（站牌恢复正常显示，不另发事件）。
 */
public final class TripCancellations {

  /** 取消范围。 */
  public enum Scope {
    /** 整趟没开出。 */
    FULL,
    /** 开出了，但从某一站起不再停。 */
    PARTIAL
  }

  /** 取消原因。 */
  public enum Reason {
    /** 到点没派出车：票过了发车容差作废，或服务器卡顿超过追补上限被跳过。 */
    NOT_DISPATCHED,
    /** 执行中的车离开运行时（销毁、异常清理等），剩下的站不再停；它交路上后面替补赶不上的班次同时整趟取消。 */
    VEHICLE_REMOVED
  }

  /**
   * 一次取消。
   *
   * @param timetableId 时刻表
   * @param tripId 车次
   * @param tripCode 车次号
   * @param routeId 交路
   * @param serviceDate 起点发车所在日期（与车次绑定 {@link TimetableAssignment#serviceDate()}、站牌同一口径）
   * @param plannedDeparture 车次计划始发时刻
   * @param scope 范围
   * @param firstCancelledStopSequence 第一个不再停的停靠序号；整趟取消时为始发站序号
   * @param reason 原因
   * @param trainName 执行这趟车的列车（整趟取消时为空）
   * @param detail 诊断明细（票作废、追补超限、离开原因等），不保证稳定取值
   */
  public record Cancellation(
      UUID timetableId,
      UUID tripId,
      String tripCode,
      UUID routeId,
      LocalDate serviceDate,
      Instant plannedDeparture,
      Scope scope,
      int firstCancelledStopSequence,
      Reason reason,
      Optional<String> trainName,
      String detail) {

    public Cancellation {
      Objects.requireNonNull(timetableId, "timetableId");
      Objects.requireNonNull(tripId, "tripId");
      Objects.requireNonNull(routeId, "routeId");
      Objects.requireNonNull(serviceDate, "serviceDate");
      Objects.requireNonNull(plannedDeparture, "plannedDeparture");
      Objects.requireNonNull(scope, "scope");
      Objects.requireNonNull(reason, "reason");
      tripCode = tripCode == null ? "" : tripCode;
      trainName = trainName == null ? Optional.empty() : trainName;
      detail = detail == null ? "" : detail;
    }

    /** 这一站是否不再停。 */
    public boolean covers(int stopSequence) {
      return stopSequence >= firstCancelledStopSequence;
    }
  }

  private record Key(UUID timetableId, UUID tripId, LocalDate serviceDate) {}

  private final Map<Key, Cancellation> byTrip = new ConcurrentHashMap<>();

  /**
   * 登记一次取消。
   *
   * @return 新登记的取消；这趟车已登记过时为空
   */
  public Optional<Cancellation> record(Cancellation cancellation) {
    if (cancellation == null) {
      return Optional.empty();
    }
    LocalDate keepFrom = cancellation.serviceDate().minusDays(1);
    byTrip.keySet().removeIf(key -> key.serviceDate().isBefore(keepFrom));
    return byTrip.putIfAbsent(keyOf(cancellation), cancellation) == null
        ? Optional.of(cancellation)
        : Optional.empty();
  }

  /** 查某天发车的某趟车的取消。 */
  public Optional<Cancellation> find(UUID timetableId, UUID tripId, LocalDate serviceDate) {
    if (timetableId == null || tripId == null || serviceDate == null) {
      return Optional.empty();
    }
    return Optional.ofNullable(byTrip.get(new Key(timetableId, tripId, serviceDate)));
  }

  /** 撤销：这趟车又有车接上了。 */
  public void revoke(UUID timetableId, UUID tripId, LocalDate serviceDate) {
    if (timetableId != null && tripId != null && serviceDate != null) {
      byTrip.remove(new Key(timetableId, tripId, serviceDate));
    }
  }

  /** 只保留这些时刻表的记录（下架的时刻表连同其取消一起清掉）。 */
  public void retainTimetables(Set<UUID> timetableIds) {
    Set<UUID> keep = timetableIds == null ? Set.of() : timetableIds;
    byTrip.keySet().removeIf(key -> !keep.contains(key.timetableId()));
  }

  /** 清空。 */
  public void clear() {
    byTrip.clear();
  }

  private static Key keyOf(Cancellation cancellation) {
    return new Key(cancellation.timetableId(), cancellation.tripId(), cancellation.serviceDate());
  }
}
