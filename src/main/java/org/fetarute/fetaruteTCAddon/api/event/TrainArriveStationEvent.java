package org.fetarute.fetaruteTCAddon.api.event;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.event.HandlerList;

/** 列车到站：进度已推进到本站（此时可能仍在制动/对标，停稳后才开门）。 */
public final class TrainArriveStationEvent extends TrainStationEvent {

  private static final HandlerList HANDLERS = new HandlerList();

  public TrainArriveStationEvent(
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
