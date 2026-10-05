package org.fetarute.fetaruteTCAddon.api.event;

import java.util.Objects;
import java.util.UUID;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.fetarute.fetaruteTCAddon.api.drive.DriveApi;

/**
 * 玩家将要领取驾驶任务（1.10.0）：在任务板领取或插件派出（{@code DriveApi#assign}）之前发出，可取消。
 *
 * <p>任务插件可以据此拦下还没解锁某条线路的玩家；取消后任务板提示领取被拒绝，{@code assign} 返回 {@code CANCELLED}。
 *
 * <p>驾驶员没领任务直接接管调度列车、或终点站结算后接着开同一列车的下一趟时，插件也会把那一趟当场记成任务并发出本事件，任务来源分别为 {@code "takeover"}、 {@code
 * "continuation"}（见 {@code DriveApi.TaskView#source}），不是玩家主动领取；取消时那一趟照常驾驶，只是不计分。
 */
public final class DriverTaskClaimEvent extends DriverEvent implements Cancellable {

  private static final HandlerList HANDLERS = new HandlerList();

  private final DriveApi.TaskView task;
  private boolean cancelled;

  public DriverTaskClaimEvent(UUID playerId, DriveApi.TaskView task) {
    super(playerId);
    this.task = Objects.requireNonNull(task, "task");
  }

  /** 将要领取的任务（状态为已领取） */
  public DriveApi.TaskView getTask() {
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
