package org.fetarute.fetaruteTCAddon.api.event;

import java.util.Objects;
import java.util.UUID;
import org.bukkit.event.HandlerList;
import org.fetarute.fetaruteTCAddon.api.drive.DriveApi;

/** 驾驶员接班、开始驾驶任务（1.10.0）。 */
public final class DriverTaskStartedEvent extends DriverEvent {

  private static final HandlerList HANDLERS = new HandlerList();

  private final DriveApi.TaskView task;
  private final String trainName;

  public DriverTaskStartedEvent(UUID playerId, DriveApi.TaskView task, String trainName) {
    super(playerId);
    this.task = Objects.requireNonNull(task, "task");
    this.trainName = Objects.requireNonNull(trainName, "trainName");
  }

  /** 任务（状态为驾驶中） */
  public DriveApi.TaskView getTask() {
    return task;
  }

  /** 担当的列车。 */
  public String getTrainName() {
    return trainName;
  }

  @Override
  public HandlerList getHandlers() {
    return HANDLERS;
  }

  public static HandlerList getHandlerList() {
    return HANDLERS;
  }
}
