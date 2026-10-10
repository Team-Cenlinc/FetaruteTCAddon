package org.fetarute.fetaruteTCAddon.api.event;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.event.HandlerList;

/** 叫来的车到达叫车的车站（1.14.0）：进度已推进到本站（可能仍在制动、对标，停稳后才开门）。每张叫车单只发一次。 */
public final class TrainCallArrivedEvent extends TrainCallEvent {

  private static final HandlerList HANDLERS = new HandlerList();

  private final String trainName;
  private final String nodeId;

  public TrainCallArrivedEvent(
      UUID callId,
      Optional<UUID> playerId,
      String operatorCode,
      String stationCode,
      String trainName,
      String nodeId) {
    super(callId, playerId, operatorCode, stationCode);
    this.trainName = Objects.requireNonNull(trainName, "trainName");
    this.nodeId = Objects.requireNonNull(nodeId, "nodeId");
  }

  /** 叫来的车。 */
  public String getTrainName() {
    return trainName;
  }

  /** 停靠的站台节点。 */
  public String getNodeId() {
    return nodeId;
  }

  @Override
  public HandlerList getHandlers() {
    return HANDLERS;
  }

  public static HandlerList getHandlerList() {
    return HANDLERS;
  }
}
