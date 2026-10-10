package org.fetarute.fetaruteTCAddon.api.event;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.event.HandlerList;

/** 叫车没派出就结束了（1.14.0）：玩家取消、等太久撤票，或排不出车作废。派出以后不再发。 */
public final class TrainCallEndedEvent extends TrainCallEvent {

  private static final HandlerList HANDLERS = new HandlerList();

  /** 结束原因。 */
  public enum Reason {
    /** 玩家取消。 */
    CANCELLED,
    /** 等太久没派出，撤票。 */
    TIMED_OUT,
    /** 票没了或排不出车（交路改了、线路关了叫车、折返车没开出来等），作废。 */
    FAILED
  }

  private final Reason reason;

  public TrainCallEndedEvent(
      UUID callId,
      Optional<UUID> playerId,
      String operatorCode,
      String stationCode,
      Reason reason) {
    super(callId, playerId, operatorCode, stationCode);
    this.reason = Objects.requireNonNull(reason, "reason");
  }

  /** 结束原因。 */
  public Reason getReason() {
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
