package org.fetarute.fetaruteTCAddon.api.event;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.event.HandlerList;
import org.fetarute.fetaruteTCAddon.api.drive.DriveApi;

/** 驾驶员结束一站的停站并记下成绩（1.10.0）：停准、停短、停过头或越站。 */
public final class DriverStopScoredEvent extends DriverEvent {

  private static final HandlerList HANDLERS = new HandlerList();

  private final Optional<DriveApi.TaskView> task;
  private final String trainName;
  private final DriveApi.StopResult stop;

  public DriverStopScoredEvent(
      UUID playerId, Optional<DriveApi.TaskView> task, String trainName, DriveApi.StopResult stop) {
    super(playerId);
    this.task = task == null ? Optional.empty() : task;
    this.trainName = Objects.requireNonNull(trainName, "trainName");
    this.stop = Objects.requireNonNull(stop, "stop");
  }

  /** 所属任务；运营人员直接接管时为空。 */
  public Optional<DriveApi.TaskView> getTask() {
    return task;
  }

  /** 列车。 */
  public String getTrainName() {
    return trainName;
  }

  /** 这一站的成绩。 */
  public DriveApi.StopResult getStop() {
    return stop;
  }

  @Override
  public HandlerList getHandlers() {
    return HANDLERS;
  }

  public static HandlerList getHandlerList() {
    return HANDLERS;
  }
}
