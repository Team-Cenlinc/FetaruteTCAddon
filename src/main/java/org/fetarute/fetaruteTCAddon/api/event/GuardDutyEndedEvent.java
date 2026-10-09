package org.fetarute.fetaruteTCAddon.api.event;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.event.HandlerList;
import org.fetarute.fetaruteTCAddon.api.drive.GuardApi;

/** 车掌值乘结束（1.14.0）：车掌离岗、下线、被撤下、交班等。 */
public final class GuardDutyEndedEvent extends GuardEvent {

  private static final HandlerList HANDLERS = new HandlerList();

  private final String trainName;
  private final String reason;
  private final Optional<GuardApi.TaskView> task;

  public GuardDutyEndedEvent(
      UUID playerId, String trainName, String reason, Optional<GuardApi.TaskView> task) {
    super(playerId);
    this.trainName = Objects.requireNonNull(trainName, "trainName");
    this.reason = reason == null ? "" : reason;
    this.task = task == null ? Optional.empty() : task;
  }

  /** 列车（当前车名）。 */
  public String getTrainName() {
    return trainName;
  }

  /**
   * 结束原因：COMMAND（车掌自己结束）、OFFLINE、DEATH、GAME_MODE、TRAIN_GONE（列车不在了）、TIMEOUTS（连续超时）、LEFT_BEHIND（漏乘）、
   * DISABLED（功能关闭）、ADMIN（被撤下）、CAB_CHANGE（换端没坐进车尾）、EXAM（车掌考试未通过）、HANDOVER（值乘到交班站）。
   */
  public String getReason() {
    return reason;
  }

  /** 这次值乘接的车掌任务（结束时的快照）；没接任务、或任务那一趟已先结算时为空。 */
  public Optional<GuardApi.TaskView> getTask() {
    return task;
  }

  @Override
  public HandlerList getHandlers() {
    return HANDLERS;
  }

  public static HandlerList getHandlerList() {
    return HANDLERS;
  }
}
