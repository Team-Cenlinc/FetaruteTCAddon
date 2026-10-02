package org.fetarute.fetaruteTCAddon.api.event;

import java.time.Duration;
import java.util.Objects;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** 列车扣停解除（与 {@link TrainHoldEvent} 成对出现）。 */
public final class TrainHoldReleasedEvent extends Event {

  private static final HandlerList HANDLERS = new HandlerList();

  private final String trainName;
  private final String reasonCode;
  private final Duration held;

  public TrainHoldReleasedEvent(String trainName, String reasonCode, Duration held) {
    this.trainName = Objects.requireNonNull(trainName, "trainName");
    this.reasonCode = reasonCode == null ? "UNKNOWN" : reasonCode;
    this.held = held == null || held.isNegative() ? Duration.ZERO : held;
  }

  /** 列车名。 */
  public String getTrainName() {
    return trainName;
  }

  /** 解除前最后的扣停原因代码。 */
  public String getReasonCode() {
    return reasonCode;
  }

  /** 本次扣停的完整持续时长（跨原因变化累计）。 */
  public Duration getHeld() {
    return held;
  }

  @Override
  public HandlerList getHandlers() {
    return HANDLERS;
  }

  public static HandlerList getHandlerList() {
    return HANDLERS;
  }
}
