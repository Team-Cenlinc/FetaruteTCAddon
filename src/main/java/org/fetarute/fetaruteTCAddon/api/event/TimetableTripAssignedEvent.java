package org.fetarute.fetaruteTCAddon.api.event;

import java.util.Objects;
import java.util.UUID;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** 列车绑定到时刻表车次（按表运行启用时；每 tick 检测）。 */
public final class TimetableTripAssignedEvent extends Event {

  private static final HandlerList HANDLERS = new HandlerList();

  private final String trainName;
  private final UUID timetableId;
  private final String tripCode;
  private final UUID routeId;
  private final long initialDeviationSeconds;

  public TimetableTripAssignedEvent(
      String trainName,
      UUID timetableId,
      String tripCode,
      UUID routeId,
      long initialDeviationSeconds) {
    this.trainName = Objects.requireNonNull(trainName, "trainName");
    this.timetableId = Objects.requireNonNull(timetableId, "timetableId");
    this.tripCode = Objects.requireNonNull(tripCode, "tripCode");
    this.routeId = Objects.requireNonNull(routeId, "routeId");
    this.initialDeviationSeconds = initialDeviationSeconds;
  }

  /** 列车名。 */
  public String getTrainName() {
    return trainName;
  }

  /** 时刻表 ID。 */
  public UUID getTimetableId() {
    return timetableId;
  }

  /** 车次号。 */
  public String getTripCode() {
    return tripCode;
  }

  /** 交路 ID。 */
  public UUID getRouteId() {
    return routeId;
  }

  /** 绑定时相对计划的偏差（秒，正数为晚点）。 */
  public long getInitialDeviationSeconds() {
    return initialDeviationSeconds;
  }

  @Override
  public HandlerList getHandlers() {
    return HANDLERS;
  }

  public static HandlerList getHandlerList() {
    return HANDLERS;
  }
}
