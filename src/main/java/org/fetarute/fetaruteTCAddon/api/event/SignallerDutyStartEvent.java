package org.fetarute.fetaruteTCAddon.api.event;

import java.util.UUID;
import org.bukkit.event.HandlerList;

/** 信号员上岗（1.15.0）：玩家点了控制台、确认上岗之后。控制区随即转为人工；调度图未就绪时 {@link #isManual()} 为 false，暂为自动。 */
public final class SignallerDutyStartEvent extends SignallerEvent {

  private static final HandlerList HANDLERS = new HandlerList();

  private final boolean manual;

  public SignallerDutyStartEvent(
      UUID playerId, String operatorCode, String zoneCode, String zoneName, boolean manual) {
    super(playerId, operatorCode, zoneCode, zoneName);
    this.manual = manual;
  }

  /** 上岗后控制区已转为人工。 */
  public boolean isManual() {
    return manual;
  }

  @Override
  public HandlerList getHandlers() {
    return HANDLERS;
  }

  public static HandlerList getHandlerList() {
    return HANDLERS;
  }
}
