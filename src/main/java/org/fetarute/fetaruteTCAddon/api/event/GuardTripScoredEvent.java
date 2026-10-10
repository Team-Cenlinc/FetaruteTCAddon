package org.fetarute.fetaruteTCAddon.api.event;

import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.event.HandlerList;
import org.fetarute.fetaruteTCAddon.api.drive.GuardApi;

/** 车掌一趟的成绩（1.14.0）：列车换了车次（终点站折返开下一趟）或值乘结束时，做过作业的这一趟结算后发出。只发按时刻表运行的车次（与车掌记录同一口径）。 */
public final class GuardTripScoredEvent extends GuardEvent {

  private static final HandlerList HANDLERS = new HandlerList();

  private final String trainName;
  private final Optional<GuardApi.TaskView> task;
  private final UUID timetableId;
  private final String tripCode;
  private final LocalDate serviceDate;
  private final String routeCode;
  private final String state;
  private final GuardApi.TripScore score;

  public GuardTripScoredEvent(
      UUID playerId,
      String trainName,
      Optional<GuardApi.TaskView> task,
      UUID timetableId,
      String tripCode,
      LocalDate serviceDate,
      String routeCode,
      String state,
      GuardApi.TripScore score) {
    super(playerId);
    this.trainName = Objects.requireNonNull(trainName, "trainName");
    this.task = task == null ? Optional.empty() : task;
    this.timetableId = Objects.requireNonNull(timetableId, "timetableId");
    this.tripCode = Objects.requireNonNull(tripCode, "tripCode");
    this.serviceDate = Objects.requireNonNull(serviceDate, "serviceDate");
    this.routeCode = routeCode == null ? "" : routeCode;
    this.state = state == null ? "" : state;
    this.score = Objects.requireNonNull(score, "score");
  }

  /** 列车。 */
  public String getTrainName() {
    return trainName;
  }

  /** 这一趟是所接车掌任务的那一班时为它的快照（已记下成绩）；否则为空。 */
  public Optional<GuardApi.TaskView> getTask() {
    return task;
  }

  public UUID getTimetableId() {
    return timetableId;
  }

  public String getTripCode() {
    return tripCode;
  }

  public LocalDate getServiceDate() {
    return serviceDate;
  }

  public String getRouteCode() {
    return routeCode;
  }

  /** 终态：COMPLETED（开完）、ABANDONED（中途离开）、INTERRUPTED（被撤下等）、FAILED（未完成，不发奖励）。 */
  public String getState() {
    return state;
  }

  /** 成绩。 */
  public GuardApi.TripScore getScore() {
    return score;
  }

  @Override
  public HandlerList getHandlers() {
    return HANDLERS;
  }

  public static HandlerList getHandlerList() {
    return HANDLERS;
  }
}
