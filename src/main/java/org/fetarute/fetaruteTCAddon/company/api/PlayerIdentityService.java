package org.fetarute.fetaruteTCAddon.company.api;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.fetarute.fetaruteTCAddon.company.model.IdentityAuthType;
import org.fetarute.fetaruteTCAddon.company.model.PlayerIdentity;
import org.fetarute.fetaruteTCAddon.company.repository.PlayerIdentityRepository;

/**
 * 玩家身份读写门面：为命令层提供“查找或创建”逻辑，避免每个命令重复处理 Optional。
 *
 * <p>身份主键独立于 Bukkit UUID：{@link PlayerIdentity#id()} 用于内部关联与迁移，{@link PlayerIdentity#playerUuid()}
 * 保留 Bukkit 层 UUID。
 */
public final class PlayerIdentityService {

  private final PlayerIdentityRepository repository;

  public PlayerIdentityService(PlayerIdentityRepository repository) {
    this.repository = Objects.requireNonNull(repository, "repository");
  }

  /** 获取或创建命令发送者的身份；仅支持玩家。 */
  public PlayerIdentity requireIdentity(Player player) {
    Objects.requireNonNull(player, "player");
    return getOrCreate(player, IdentityAuthType.ONLINE);
  }

  /** 获取或创建指定玩家（含离线）身份。 */
  public PlayerIdentity getOrCreate(OfflinePlayer player) {
    Objects.requireNonNull(player, "player");
    IdentityAuthType authType =
        player.isOnline() ? IdentityAuthType.ONLINE : IdentityAuthType.OFFLINE;
    return getOrCreate(player, authType);
  }

  private PlayerIdentity getOrCreate(OfflinePlayer player, IdentityAuthType authType) {
    UUID playerUuid = player.getUniqueId();
    Objects.requireNonNull(playerUuid, "playerUuid");
    Optional<PlayerIdentity> existing = repository.findByPlayerUuid(playerUuid);
    if (existing.isPresent()) {
      PlayerIdentity found = existing.get();
      // 名称可能发生变化（改名/离线档），这里按需更新并保持 metadata 不动
      String playerName = player.getName();
      String currentName = playerName == null ? found.name() : playerName;
      if (!Objects.equals(found.name(), currentName) || found.authType() != authType) {
        PlayerIdentity updated =
            new PlayerIdentity(
                found.id(),
                found.playerUuid(),
                currentName,
                authType,
                found.externalRef(),
                found.metadata(),
                found.createdAt(),
                Instant.now());
        repository.save(updated);
        return updated;
      }
      return found;
    }

    Instant now = Instant.now();
    String playerName = player.getName();
    String name = playerName == null ? playerUuid.toString() : playerName;
    PlayerIdentity identity =
        new PlayerIdentity(
            UUID.randomUUID(), playerUuid, name, authType, Optional.empty(), Map.of(), now, now);
    repository.save(identity);
    return identity;
  }
}
