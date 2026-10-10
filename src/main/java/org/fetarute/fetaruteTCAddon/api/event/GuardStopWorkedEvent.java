package org.fetarute.fetaruteTCAddon.api.event;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.event.HandlerList;
import org.fetarute.fetaruteTCAddon.api.drive.GuardApi;

/**
 * 车掌做完一站的作业（1.14.0）：列车开出这一站、作业结算时发出（出站监视可能还在采样，结果以 {@link GuardTripScoredEvent} 为准）。越站、开门前就结束的停站不发。
 */
public final class GuardStopWorkedEvent extends GuardEvent {

  private static final HandlerList HANDLERS = new HandlerList();

  private final String trainName;
  private final Optional<GuardApi.TaskView> task;
  private final GuardApi.StopWork work;

  public GuardStopWorkedEvent(
      UUID playerId, String trainName, Optional<GuardApi.TaskView> task, GuardApi.StopWork work) {
    super(playerId);
    this.trainName = Objects.requireNonNull(trainName, "trainName");
    this.task = task == null ? Optional.empty() : task;
    this.work = Objects.requireNonNull(work, "work");
  }

  /** 列车。 */
  public String getTrainName() {
    return trainName;
  }

  /** 所接的车掌任务；没接任务时为空。 */
  public Optional<GuardApi.TaskView> getTask() {
    return task;
  }

  /** 这一站的作业。 */
  public GuardApi.StopWork getWork() {
    return work;
  }

  @Override
  public HandlerList getHandlers() {
    return HANDLERS;
  }

  public static HandlerList getHandlerList() {
    return HANDLERS;
  }
}
