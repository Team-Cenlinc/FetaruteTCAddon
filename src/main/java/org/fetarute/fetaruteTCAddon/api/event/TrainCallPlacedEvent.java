package org.fetarute.fetaruteTCAddon.api.event;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import org.bukkit.event.HandlerList;

/** 玩家叫了一趟车（1.14.0）：叫车票已出，车源已排好，车还没派出。 */
public final class TrainCallPlacedEvent extends TrainCallEvent {

  private static final HandlerList HANDLERS = new HandlerList();

  private final String directionKey;
  private final UUID lineId;
  private final UUID routeId;
  private final OptionalInt etaMinutes;

  public TrainCallPlacedEvent(
      UUID callId,
      UUID playerId,
      String operatorCode,
      String stationCode,
      String directionKey,
      UUID lineId,
      UUID routeId,
      OptionalInt etaMinutes) {
    super(callId, Optional.of(playerId), operatorCode, stationCode);
    this.directionKey = Objects.requireNonNull(directionKey, "directionKey");
    this.lineId = Objects.requireNonNull(lineId, "lineId");
    this.routeId = Objects.requireNonNull(routeId, "routeId");
    this.etaMinutes = etaMinutes == null ? OptionalInt.empty() : etaMinutes;
  }

  /** 方向标识（{@code CallApi.Direction#key}）。 */
  public String getDirectionKey() {
    return directionKey;
  }

  /** 线路。 */
  public UUID getLineId() {
    return lineId;
  }

  /** 叫来的车跑的交路。 */
  public UUID getRouteId() {
    return routeId;
  }

  /** 叫车时估的约几分钟后到站；估不出时为空。 */
  public OptionalInt getEtaMinutes() {
    return etaMinutes;
  }

  @Override
  public HandlerList getHandlers() {
    return HANDLERS;
  }

  public static HandlerList getHandlerList() {
    return HANDLERS;
  }
}
