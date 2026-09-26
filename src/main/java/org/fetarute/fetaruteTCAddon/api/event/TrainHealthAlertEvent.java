package org.fetarute.fetaruteTCAddon.api.event;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** 健康监控告警（已按告警总线的限流规则去重：同一告警一分钟内只发一次，状态转换立即可见）。 */
public final class TrainHealthAlertEvent extends Event {

  private static final HandlerList HANDLERS = new HandlerList();

  private final String type;
  private final Optional<String> trainName;
  private final String message;
  private final boolean autoFixed;
  private final Instant at;

  public TrainHealthAlertEvent(
      String type, Optional<String> trainName, String message, boolean autoFixed, Instant at) {
    this.type = Objects.requireNonNull(type, "type");
    this.trainName = trainName == null ? Optional.empty() : trainName;
    this.message = message == null ? "" : message;
    this.autoFixed = autoFixed;
    this.at = Objects.requireNonNull(at, "at");
  }

  /** 告警类型（如 {@code STALL}、{@code PROGRESS_STUCK}、{@code ORPHAN_OCCUPANCY}）。 */
  public String getType() {
    return type;
  }

  /** 相关列车（全局告警为空）。 */
  public Optional<String> getTrainName() {
    return trainName;
  }

  /** 告警文本。 */
  public String getMessage() {
    return message;
  }

  /** true 表示这是一条“已自动修复”的恢复通知。 */
  public boolean isAutoFixed() {
    return autoFixed;
  }

  /** 告警时刻。 */
  public Instant getAt() {
    return at;
  }

  @Override
  public HandlerList getHandlers() {
    return HANDLERS;
  }

  public static HandlerList getHandlerList() {
    return HANDLERS;
  }
}
