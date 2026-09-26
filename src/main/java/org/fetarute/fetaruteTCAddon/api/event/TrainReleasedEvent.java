package org.fetarute.fetaruteTCAddon.api.event;

import java.util.Objects;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** 列车离开 FTA 运行时管辖：销毁、回库、改派交路或异常清理。 */
public final class TrainReleasedEvent extends Event {

  private static final HandlerList HANDLERS = new HandlerList();

  private final String trainName;
  private final String reason;

  public TrainReleasedEvent(String trainName, String reason) {
    this.trainName = Objects.requireNonNull(trainName, "trainName");
    this.reason = reason == null ? "" : reason;
  }

  /** 列车名。 */
  public String getTrainName() {
    return trainName;
  }

  /** 诊断用原因（如 {@code DSTY}、{@code destroyed}），不保证稳定取值。 */
  public String getReason() {
    return reason;
  }

  @Override
  public HandlerList getHandlers() {
    return HANDLERS;
  }

  public static HandlerList getHandlerList() {
    return HANDLERS;
  }
}
