package org.fetarute.fetaruteTCAddon.api.event;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;

/**
 * 信号员事件的公共字段（1.15.0）；请监听具体子类。
 *
 * <p>信号员事件由信号员模块在主线程当场发出，都只读。发出前已刷新 {@code SignallerApi} 的快照。玩家可能已经下线（例如下线即下岗），所以只保证有 {@link
 * #getPlayerId()}。
 */
public abstract class SignallerEvent extends Event {

  private final UUID playerId;
  private final String operatorCode;
  private final String zoneCode;
  private final String zoneName;

  protected SignallerEvent(UUID playerId, String operatorCode, String zoneCode, String zoneName) {
    this.playerId = Objects.requireNonNull(playerId, "playerId");
    this.operatorCode = Objects.requireNonNull(operatorCode, "operatorCode");
    this.zoneCode = Objects.requireNonNull(zoneCode, "zoneCode");
    this.zoneName = zoneName == null ? zoneCode : zoneName;
  }

  /** 信号员。 */
  public UUID getPlayerId() {
    return playerId;
  }

  /** 在线的信号员；已下线时为空。 */
  public Optional<Player> getPlayer() {
    Player player = Bukkit.getPlayer(playerId);
    return player != null && player.isOnline() ? Optional.of(player) : Optional.empty();
  }

  /** 控制区所属的运营商代码。 */
  public String getOperatorCode() {
    return operatorCode;
  }

  /** 控制区代码。 */
  public String getZoneCode() {
    return zoneCode;
  }

  /** 控制区名。 */
  public String getZoneName() {
    return zoneName;
  }
}
