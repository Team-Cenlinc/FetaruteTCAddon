package org.fetarute.fetaruteTCAddon.api.event;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.event.HandlerList;

/** 列车发车：已拿到发车许可、松开门锁离站。 */
public final class TrainDepartStationEvent extends TrainStationEvent {

  private static final HandlerList HANDLERS = new HandlerList();

  public TrainDepartStationEvent(
      String trainName,
      Optional<UUID> routeId,
      String routeKey,
      int stopIndex,
      String nodeId,
      boolean terminal,
      Instant at) {
    super(trainName, routeId, routeKey, stopIndex, nodeId, terminal, at);
  }

  @Override
  public HandlerList getHandlers() {
    return HANDLERS;
  }

  public static HandlerList getHandlerList() {
    return HANDLERS;
  }
}
