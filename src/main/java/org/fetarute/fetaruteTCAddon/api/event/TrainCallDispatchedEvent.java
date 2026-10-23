package org.fetarute.fetaruteTCAddon.api.event;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.event.HandlerList;

/** 叫车派出（1.14.0）：叫来的车已出车（车库出车、区间生成或首站待命车接走），正开往叫车的车站。 */
public final class TrainCallDispatchedEvent extends TrainCallEvent {

  private static final HandlerList HANDLERS = new HandlerList();

  private final String trainName;
  private final Optional<UUID> routeId;

  public TrainCallDispatchedEvent(
      UUID callId,
      Optional<UUID> playerId,
      String operatorCode,
      String stationCode,
      String trainName,
      Optional<UUID> routeId) {
    super(callId, playerId, operatorCode, stationCode);
    this.trainName = Objects.requireNonNull(trainName, "trainName");
    this.routeId = routeId == null ? Optional.empty() : routeId;
  }

  /** 叫来的车。 */
  public String getTrainName() {
    return trainName;
  }

  /** 叫来的车跑的交路；查不到时为空。 */
  public Optional<UUID> getRouteId() {
    return routeId;
  }

  @Override
  public HandlerList getHandlers() {
    return HANDLERS;
  }

  public static HandlerList getHandlerList() {
    return HANDLERS;
  }
}
