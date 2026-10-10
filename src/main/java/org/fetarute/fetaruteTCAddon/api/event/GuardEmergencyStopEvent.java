package org.fetarute.fetaruteTCAddon.api.event;

import java.util.Objects;
import java.util.UUID;
import org.bukkit.event.HandlerList;

/** 车掌在行驶中拉下紧急停车（1.14.0）。人工驾驶的车由驾驶员停稳后缓解；自动运行的车由车掌停稳后按发车铃一长声解除。 */
public final class GuardEmergencyStopEvent extends GuardEvent {

  private static final HandlerList HANDLERS = new HandlerList();

  private final String trainName;

  public GuardEmergencyStopEvent(UUID playerId, String trainName) {
    super(playerId);
    this.trainName = Objects.requireNonNull(trainName, "trainName");
  }

  /** 列车。 */
  public String getTrainName() {
    return trainName;
  }

  @Override
  public HandlerList getHandlers() {
    return HANDLERS;
  }

  public static HandlerList getHandlerList() {
    return HANDLERS;
  }
}
