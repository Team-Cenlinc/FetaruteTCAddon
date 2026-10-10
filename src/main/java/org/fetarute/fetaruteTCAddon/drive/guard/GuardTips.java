package org.fetarute.fetaruteTCAddon.drive.guard;

import java.time.Duration;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.title.Title;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.fetarute.fetaruteTCAddon.drive.sound.DriveCue;
import org.fetarute.fetaruteTCAddon.drive.sound.DriveSounds;
import org.fetarute.fetaruteTCAddon.utils.LocaleManager;

/**
 * 车掌的情境提示（{@link GuardTip}）：上岗时读出这名玩家出过哪些，值乘中每次刷新侧边栏时看有没有该出的，出过就记进玩家的持久数据。
 * 两条提示之间至少隔一会儿，屏幕下方的说明来得及看完。只在服务器主线程调用。
 */
final class GuardTips {

  /** 两条提示之间至少隔多久（tick）。 */
  private static final long GAP_TICKS = 60L;

  private static final Title.Times SUBTITLE_TIMES =
      Title.Times.times(Duration.ofMillis(250), Duration.ofSeconds(4), Duration.ofMillis(750));

  private final LocaleManager locale;
  private final DriveSounds sounds;
  private final Map<GuardTip, NamespacedKey> keys = new EnumMap<>(GuardTip.class);
  private final Map<UUID, Set<GuardTip>> shown = new HashMap<>();
  private final Map<UUID, Long> lastTick = new HashMap<>();

  GuardTips(Plugin plugin, LocaleManager locale, DriveSounds sounds) {
    this.locale = locale;
    this.sounds = sounds;
    for (GuardTip tip : GuardTip.values()) {
      keys.put(
          tip,
          new NamespacedKey(
              plugin, "guard_tip_" + tip.key().replace('-', '_').toLowerCase(Locale.ROOT)));
    }
  }

  /** 上岗：读出这名玩家已经出过的提示。 */
  void onDuty(Player player) {
    PersistentDataContainer data = player.getPersistentDataContainer();
    Set<GuardTip> seen = EnumSet.noneOf(GuardTip.class);
    for (Map.Entry<GuardTip, NamespacedKey> entry : keys.entrySet()) {
      if (data.has(entry.getValue(), PersistentDataType.BYTE)) {
        seen.add(entry.getKey());
      }
    }
    shown.put(player.getUniqueId(), seen);
    lastTick.remove(player.getUniqueId());
  }

  /** 值乘中：此刻有该出的提示、离上一条也够久了就出一条。 */
  void tick(Player player, GuardDisplay.Snapshot snapshot, long nowTick) {
    Set<GuardTip> seen = shown.get(player.getUniqueId());
    if (seen == null || seen.size() == GuardTip.values().length) {
      return;
    }
    Long last = lastTick.get(player.getUniqueId());
    if (last != null && nowTick - last < GAP_TICKS) {
      return;
    }
    GuardTip.firstDue(snapshot, seen)
        .ifPresent(
            tip -> {
              seen.add(tip);
              lastTick.put(player.getUniqueId(), nowTick);
              player
                  .getPersistentDataContainer()
                  .set(keys.get(tip), PersistentDataType.BYTE, (byte) 1);
              show(player, tip);
            });
  }

  /** 离岗：丢掉缓存（出过的记录在玩家数据里）。 */
  void forget(UUID playerId) {
    shown.remove(playerId);
    lastTick.remove(playerId);
  }

  /** 清掉出过的记录：下次值乘时从头再提示一遍。 */
  void reset(Player player) {
    PersistentDataContainer data = player.getPersistentDataContainer();
    for (NamespacedKey key : keys.values()) {
      data.remove(key);
    }
    shown.computeIfPresent(player.getUniqueId(), (id, seen) -> EnumSet.noneOf(GuardTip.class));
  }

  private void show(Player player, GuardTip tip) {
    String base = "drive.guard.tips." + tip.key();
    player.sendMessage(
        locale.component(
            "drive.guard.tip", Placeholder.component("text", locale.component(base + ".chat"))));
    player.showTitle(
        Title.title(Component.empty(), locale.component(base + ".subtitle"), SUBTITLE_TIMES));
    sounds.play(player, DriveCue.TUTORIAL_TIP);
  }
}
