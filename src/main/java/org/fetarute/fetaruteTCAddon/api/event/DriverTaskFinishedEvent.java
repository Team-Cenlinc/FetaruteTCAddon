package org.fetarute.fetaruteTCAddon.api.event;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.event.HandlerList;
import org.fetarute.fetaruteTCAddon.api.drive.DriveApi;

/**
 * 驾驶任务结束（1.10.0）：开完、放弃、作废、收回都会发一次。
 *
 * <p>开过车的任务带成绩；还没开车就结束的（列车没等到、领取后放弃）没有成绩。
 */
public final class DriverTaskFinishedEvent extends DriverEvent {

  private static final HandlerList HANDLERS = new HandlerList();

  private final DriveApi.TaskView task;
  private final Optional<DriveApi.TaskScore> score;

  public DriverTaskFinishedEvent(
      UUID playerId, DriveApi.TaskView task, Optional<DriveApi.TaskScore> score) {
    super(playerId);
    this.task = Objects.requireNonNull(task, "task");
    this.score = score == null ? Optional.empty() : score;
  }

  /** 结束时的任务（终态、结束原因、得分与评级） */
  public DriveApi.TaskView getTask() {
    return task;
  }

  /** 成绩；没有开过车时为空。 */
  public Optional<DriveApi.TaskScore> getScore() {
    return score;
  }

  @Override
  public HandlerList getHandlers() {
    return HANDLERS;
  }

  public static HandlerList getHandlerList() {
    return HANDLERS;
  }
}
