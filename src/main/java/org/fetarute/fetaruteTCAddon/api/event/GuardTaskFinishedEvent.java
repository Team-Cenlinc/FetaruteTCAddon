package org.fetarute.fetaruteTCAddon.api.event;

import java.util.Objects;
import org.bukkit.event.HandlerList;
import org.fetarute.fetaruteTCAddon.api.drive.GuardApi;

/**
 * 车掌任务结束（1.14.0）：完成、放弃、作废、未完成或被中断。每个任务只发一次。
 *
 * <p>做过作业的那一趟结算后结束的，{@link GuardApi.TaskView#points()} 与 {@link GuardApi.TaskView#grade()}
 * 是那一趟的成绩（详见同一刻发出的 {@link GuardTripScoredEvent}）。
 */
public final class GuardTaskFinishedEvent extends GuardEvent {

  private static final HandlerList HANDLERS = new HandlerList();

  private final GuardApi.TaskView task;

  public GuardTaskFinishedEvent(GuardApi.TaskView task) {
    super(Objects.requireNonNull(task, "task").playerId());
    this.task = task;
  }

  /** 结束的任务。 */
  public GuardApi.TaskView getTask() {
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
