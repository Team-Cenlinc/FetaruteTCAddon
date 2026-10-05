package org.fetarute.fetaruteTCAddon.drive;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/**
 * 玩家自选的仿真等级：在任务板上选择，记在玩家数据里，换线、重登、重启都保留。
 *
 * <p>只对持有 {@link DrivePermissions#LEVEL} 的玩家生效；没有选过或没有该节点时用 {@code drive.yml} 的 {@code level}。
 * 选择从下一次开始驾驶起生效，驾驶调度列车与自由驾驶都适用。
 */
public final class DriveLevelPreference {

  private final NamespacedKey key;

  public DriveLevelPreference(Plugin plugin) {
    this.key = new NamespacedKey(Objects.requireNonNull(plugin, "plugin"), "drive_level");
  }

  /** 玩家选过的等级；没有选过时为空。 */
  public Optional<SimulationLevel> chosen(Player player) {
    return SimulationLevel.parse(
        player.getPersistentDataContainer().get(key, PersistentDataType.STRING));
  }

  /** 记下玩家的选择。 */
  public void choose(Player player, SimulationLevel level) {
    Objects.requireNonNull(level, "level");
    player
        .getPersistentDataContainer()
        .set(key, PersistentDataType.STRING, level.name().toLowerCase(Locale.ROOT));
  }

  /** 玩家下一次开始驾驶时用的等级。 */
  public SimulationLevel effective(Player player, SimulationLevel serverDefault) {
    return resolve(player.hasPermission(DrivePermissions.LEVEL), chosen(player), serverDefault);
  }

  /**
   * 选定生效的等级。
   *
   * @param allowed 玩家能否自选等级
   * @param chosen 玩家选过的等级
   * @param serverDefault {@code drive.yml} 的 {@code level}
   */
  static SimulationLevel resolve(
      boolean allowed, Optional<SimulationLevel> chosen, SimulationLevel serverDefault) {
    Objects.requireNonNull(serverDefault, "serverDefault");
    return allowed ? chosen.orElse(serverDefault) : serverDefault;
  }
}
