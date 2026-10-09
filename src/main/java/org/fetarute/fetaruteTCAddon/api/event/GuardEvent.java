package org.fetarute.fetaruteTCAddon.api.event;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;

/**
 * 车掌事件的公共字段（1.14.0）；请监听具体子类。
 *
 * <p>车掌事件由车掌模块在主线程当场发出。{@link GuardTaskClaimEvent}、{@link GuardDutyStartEvent} 可以取消，其余只读。
 * 玩家可能已经下线（例如任务因列车没等到而作废），所以只保证有 {@link #getPlayerId()}。
 */
public abstract class GuardEvent extends Event {

  private final UUID playerId;

  protected GuardEvent(UUID playerId) {
    this.playerId = Objects.requireNonNull(playerId, "playerId");
  }

  /** 车掌。 */
  public UUID getPlayerId() {
    return playerId;
  }

  /** 在线的车掌；已下线时为空。 */
  public Optional<Player> getPlayer() {
    Player player = Bukkit.getPlayer(playerId);
    return player != null && player.isOnline() ? Optional.of(player) : Optional.empty();
  }
}
