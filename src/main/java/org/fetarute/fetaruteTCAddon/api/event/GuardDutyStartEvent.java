package org.fetarute.fetaruteTCAddon.api.event;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.fetarute.fetaruteTCAddon.api.drive.GuardApi;

/**
 * 玩家将要上岗当车掌（1.14.0）：各项检查都通过、接过车门之前发出，可取消。取消后玩家收到“无法上岗”的提示。
 *
 * <p>未取消时一定会跟着一个 {@link GuardDutyEndedEvent}。
 */
public final class GuardDutyStartEvent extends GuardEvent implements Cancellable {

  private static final HandlerList HANDLERS = new HandlerList();

  private final String trainName;
  private final Optional<GuardApi.TaskView> task;
  private boolean cancelled;

  public GuardDutyStartEvent(UUID playerId, String trainName, Optional<GuardApi.TaskView> task) {
    super(playerId);
    this.trainName = Objects.requireNonNull(trainName, "trainName");
    this.task = task == null ? Optional.empty() : task;
  }

  /** 列车。 */
  public String getTrainName() {
    return trainName;
  }

  /** 要接上的车掌任务；直接上岗（不是领任务）时为空。 */
  public Optional<GuardApi.TaskView> getTask() {
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
