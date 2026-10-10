package org.fetarute.fetaruteTCAddon.api.event;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;

/**
 * 叫车事件的公共字段（1.14.0）；请监听具体子类。
 *
 * <p>叫车事件在主线程发出，都只读；派出与到站事件在发生后的下一拍发出。管理员加开的车不是叫车，不发这些事件。叫车的玩家可能已经下线， 已派出的叫车在重启后查不到叫车的玩家，所以 {@link
 * #getPlayerId()} 可能为空。
 */
public abstract class TrainCallEvent extends Event {

  private final UUID callId;
  private final Optional<UUID> playerId;
  private final String operatorCode;
  private final String stationCode;

  protected TrainCallEvent(
      UUID callId, Optional<UUID> playerId, String operatorCode, String stationCode) {
    this.callId = Objects.requireNonNull(callId, "callId");
    this.playerId = playerId == null ? Optional.empty() : playerId;
    this.operatorCode = Objects.requireNonNull(operatorCode, "operatorCode");
    this.stationCode = Objects.requireNonNull(stationCode, "stationCode");
  }

  /** 叫车编号（{@code CallApi#callOf}）。 */
  public UUID getCallId() {
    return callId;
  }

  /** 叫车的玩家；查不到时为空。 */
  public Optional<UUID> getPlayerId() {
    return playerId;
  }

  /** 在线的叫车玩家；已下线或查不到时为空。 */
  public Optional<Player> getPlayer() {
    return playerId.map(Bukkit::getPlayer).filter(player -> player.isOnline());
  }

  /** 叫车车站的运营商代码。 */
  public String getOperatorCode() {
    return operatorCode;
  }

  /** 叫车的站码。 */
  public String getStationCode() {
    return stationCode;
  }
}
