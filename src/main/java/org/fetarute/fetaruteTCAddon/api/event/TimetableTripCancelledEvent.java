package org.fetarute.fetaruteTCAddon.api.event;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * 时刻表车次取消（1.8.0）：整趟没开出，或开出后车离开了运行时、剩下的站不再停。
 *
 * <p>每趟车（按起点发车日期区分）只发一次。取消之后又有车接上这趟车时，{@code TimetableApi#departuresAt} 恢复正常显示，但不另发事件。
 * 取消只存内存，重启后清空。
 */
public final class TimetableTripCancelledEvent extends Event {

  private static final HandlerList HANDLERS = new HandlerList();

  /** 取消范围。 */
  public enum Scope {
    /** 整趟没开出：起点就没有发车。 */
    FULL,
    /** 开出了，从 {@link #getFirstCancelledStopSequence()} 那一站起不再停。 */
    PARTIAL
  }

  /** 取消原因。 */
  public enum Reason {
    /** 到点没有派出车：票过了发车容差作废，或服务器卡顿超过追补上限被跳过。 */
    NOT_DISPATCHED,
    /** 执行这趟车的列车离开了运行时（销毁、异常清理等）。 */
    VEHICLE_REMOVED
  }

  private final UUID timetableId;
  private final UUID tripId;
  private final String tripCode;
  private final UUID routeId;
  private final LocalDate serviceDate;
  private final Instant plannedDeparture;
  private final Scope scope;
  private final int firstCancelledStopSequence;
  private final Reason reason;
  private final Optional<String> trainName;

  public TimetableTripCancelledEvent(
      UUID timetableId,
      UUID tripId,
      String tripCode,
      UUID routeId,
      LocalDate serviceDate,
      Instant plannedDeparture,
      Scope scope,
      int firstCancelledStopSequence,
      Reason reason,
      Optional<String> trainName) {
    this.timetableId = Objects.requireNonNull(timetableId, "timetableId");
    this.tripId = Objects.requireNonNull(tripId, "tripId");
    this.tripCode = Objects.requireNonNull(tripCode, "tripCode");
    this.routeId = Objects.requireNonNull(routeId, "routeId");
    this.serviceDate = Objects.requireNonNull(serviceDate, "serviceDate");
    this.plannedDeparture = Objects.requireNonNull(plannedDeparture, "plannedDeparture");
    this.scope = Objects.requireNonNull(scope, "scope");
    this.firstCancelledStopSequence = firstCancelledStopSequence;
    this.reason = Objects.requireNonNull(reason, "reason");
    this.trainName = trainName == null ? Optional.empty() : trainName;
  }

  /** 时刻表 ID。 */
  public UUID getTimetableId() {
    return timetableId;
  }

  /** 车次 ID（{@code TimetableApi.Trip#id}）。 */
  public UUID getTripId() {
    return tripId;
  }

  /** 车次号；站牌上用 {@code 时刻表 ID + 车次号 + 服务日} 对上 {@code TimetableApi.Departure}。 */
  public String getTripCode() {
    return tripCode;
  }

  /** 交路 ID。 */
  public UUID getRouteId() {
    return routeId;
  }

  /** 服务日（起点发车所在日期），与 {@code TimetableApi.Departure#serviceDate} 同一口径。 */
  public LocalDate getServiceDate() {
    return serviceDate;
  }

  /** 起点计划发车时刻。 */
  public Instant getPlannedDeparture() {
    return plannedDeparture;
  }

  /** 取消范围。 */
  public Scope getScope() {
    return scope;
  }

  /** 第一个不再停的停靠序号（与 {@code Departure#stopSequence} 同一口径）；整趟取消时为首个停车站。 */
  public int getFirstCancelledStopSequence() {
    return firstCancelledStopSequence;
  }

  /** 取消原因。 */
  public Reason getReason() {
    return reason;
  }

  /** 执行这趟车的列车；整趟没派出车时为空。 */
  public Optional<String> getTrainName() {
    return trainName;
  }

  /** 这一站是否不再停。 */
  public boolean covers(int stopSequence) {
    return stopSequence >= firstCancelledStopSequence;
  }

  @Override
  public HandlerList getHandlers() {
    return HANDLERS;
  }

  public static HandlerList getHandlerList() {
    return HANDLERS;
  }
}
