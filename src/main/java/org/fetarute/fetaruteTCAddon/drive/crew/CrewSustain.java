package org.fetarute.fetaruteTCAddon.drive.crew;

import org.bukkit.GameMode;
import org.bukkit.attribute.Attribute;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

/**
 * 当班时的体力：岗位一站就是几十分钟，生存模式的玩家不该因为饿或者摔了一下就得离岗，所以当班时喂饱、慢慢回血。
 *
 * <p>回血有速度上限，不是无敌：掉进虚空、被一击打死照样下岗。创造与旁观模式不掉血也不饿，不用管。
 *
 * @param enabled 是否启用
 * @param healthPerSecond 每秒回多少生命值（一颗心是 2）
 */
public record CrewSustain(boolean enabled, double healthPerSecond) {

  /** 饱食度上限。 */
  private static final int FULL_FOOD = 20;

  /** 喂饱时补到的饱和度：够抵消日常消耗，又不会让原版的快速回血一直开着。 */
  private static final float SATURATION = 5.0f;

  public static final CrewSustain DEFAULT = new CrewSustain(true, 1.0);

  public CrewSustain {
    healthPerSecond =
        Double.isFinite(healthPerSecond) ? Math.max(0.0, Math.min(40.0, healthPerSecond)) : 1.0;
  }

  /**
   * 读配置。
   *
   * @param section {@code crew.sustain} 段；没有时用默认值
   */
  public static CrewSustain from(ConfigurationSection section) {
    if (section == null) {
      return DEFAULT;
    }
    return new CrewSustain(
        section.getBoolean("enabled", DEFAULT.enabled()),
        section.getDouble("health-per-second", DEFAULT.healthPerSecond()));
  }

  /** 这一秒回血后的生命值。 */
  static double healed(double health, double max, double perSecond) {
    return Math.min(max, health + perSecond);
  }

  /** 每秒一次：给当班的玩家回血喂饱。死了的不管（死亡由各岗位按下岗处理）。 */
  public void apply(Player player) {
    if (!appliesTo(player)) {
      return;
    }
    feed(player);
    var attribute = player.getAttribute(Attribute.MAX_HEALTH);
    double max = attribute == null ? 20.0 : attribute.getValue();
    if (player.getHealth() < max) {
      player.setHealth(healed(player.getHealth(), max, healthPerSecond));
    }
  }

  /** 启用着，玩家活着，而且是会掉血、会饿的游戏模式。 */
  boolean appliesTo(Player player) {
    return enabled
        && !player.isDead()
        && (player.getGameMode() == GameMode.SURVIVAL
            || player.getGameMode() == GameMode.ADVENTURE);
  }

  static void feed(Player player) {
    if (player.getFoodLevel() < FULL_FOOD) {
      player.setFoodLevel(FULL_FOOD);
    }
    if (player.getSaturation() < SATURATION) {
      player.setSaturation(SATURATION);
    }
  }
}
