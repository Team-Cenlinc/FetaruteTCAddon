package org.fetarute.fetaruteTCAddon.api.event;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.event.HandlerList;
import org.fetarute.fetaruteTCAddon.api.drive.DriveApi;

/** 驾驶结束（1.10.0）：驾驶员离开、交还自动运行、任务完成等。 */
public final class DriveSessionEndedEvent extends DriverEvent {

  private static final HandlerList HANDLERS = new HandlerList();

  private final String trainName;
  private final boolean dispatched;
  private final String reason;
  private final Optional<DriveApi.TaskView> task;

  public DriveSessionEndedEvent(
      UUID playerId,
      String trainName,
      boolean dispatched,
      String reason,
      Optional<DriveApi.TaskView> task) {
    super(playerId);
    this.trainName = Objects.requireNonNull(trainName, "trainName");
    this.dispatched = dispatched;
    this.reason = reason == null ? "" : reason;
    this.task = task == null ? Optional.empty() : task;
  }

  /** 列车。 */
  public String getTrainName() {
    return trainName;
  }

  /** 是否为调度列车。 */
  public boolean isDispatched() {
    return dispatched;
  }

  /** 结束原因（如 TASK_COMPLETE、LEFT_SEAT、HANDBACK；开始事件之后接管列车出错为 FAILED） */
  public String getReason() {
    return reason;
  }

  /** 所属任务；没有任务时为空。 */
  public Optional<DriveApi.TaskView> getTask() {
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
