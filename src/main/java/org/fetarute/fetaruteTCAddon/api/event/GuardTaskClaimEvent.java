package org.fetarute.fetaruteTCAddon.api.event;

import java.util.Objects;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.fetarute.fetaruteTCAddon.api.drive.GuardApi;

/** 玩家将要领取（或被派）一个车掌任务（1.14.0）：各项检查都通过、登记之前发出，可取消。 */
public final class GuardTaskClaimEvent extends GuardEvent implements Cancellable {

  private static final HandlerList HANDLERS = new HandlerList();

  private final GuardApi.TaskView task;
  private boolean cancelled;

  public GuardTaskClaimEvent(GuardApi.TaskView task) {
    super(Objects.requireNonNull(task, "task").playerId());
    this.task = task;
  }

  /** 将要登记的任务（状态为 {@code CLAIMED}）。 */
  public GuardApi.TaskView getTask() {
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
