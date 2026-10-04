package org.fetarute.fetaruteTCAddon.api.event;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.fetarute.fetaruteTCAddon.api.drive.DriveApi;

/** 玩家将要开始驾驶（1.10.0）：各项检查都通过、接管列车之前发出，可取消。取消后玩家收到“无法开始驾驶”的提示。 */
public final class DriveSessionStartEvent extends DriverEvent implements Cancellable {

  private static final HandlerList HANDLERS = new HandlerList();

  private final String trainName;
  private final boolean dispatched;
  private final Optional<DriveApi.TaskView> task;
  private boolean cancelled;

  public DriveSessionStartEvent(
      UUID playerId, String trainName, boolean dispatched, Optional<DriveApi.TaskView> task) {
    super(playerId);
    this.trainName = Objects.requireNonNull(trainName, "trainName");
    this.dispatched = dispatched;
    this.task = task == null ? Optional.empty() : task;
  }

  /** 列车。 */
  public String getTrainName() {
    return trainName;
  }

  /** 是否为调度列车（驾驶任务或运营人员接管）；否则为自由驾驶。 */
  public boolean isDispatched() {
    return dispatched;
  }

  /** 所属任务；没有任务时为空。 */
  public Optional<DriveApi.TaskView> getTask() {
    return task;
  }

  @Override
  public boolean isCancelled() {
    return cancelled;
  }

  @Override
  public void setCancelled(boolean cancel) {
    this.cancelled = cancel;
  }

  @Override
  public HandlerList getHandlers() {
    return HANDLERS;
  }

  public static HandlerList getHandlerList() {
    return HANDLERS;
  }
}
