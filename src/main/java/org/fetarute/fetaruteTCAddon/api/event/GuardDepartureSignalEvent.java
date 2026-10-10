package org.fetarute.fetaruteTCAddon.api.event;

import java.util.Objects;
import java.util.UUID;
import org.bukkit.event.HandlerList;

/** 发出发车信号（1.14.0）：车掌按住发车铃一长声，或发车铃超时由站台代发。驾驶员（或 ATO）收到后起步。 */
public final class GuardDepartureSignalEvent extends GuardEvent {

  private static final HandlerList HANDLERS = new HandlerList();

  private final String trainName;
  private final String station;
  private final boolean byStation;

  public GuardDepartureSignalEvent(
      UUID playerId, String trainName, String station, boolean byStation) {
    super(playerId);
    this.trainName = Objects.requireNonNull(trainName, "trainName");
    this.station = station == null ? "" : station;
    this.byStation = byStation;
  }

  /** 列车。 */
  public String getTrainName() {
    return trainName;
  }

  /** 车站名。 */
  public String getStation() {
    return station;
  }

  /** 是否为发车铃超时、由站台代发（记一次超时）。 */
  public boolean isByStation() {
    return byStation;
  }

  @Override
  public HandlerList getHandlers() {
    return HANDLERS;
  }

  public static HandlerList getHandlerList() {
    return HANDLERS;
  }
}
