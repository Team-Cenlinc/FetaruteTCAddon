package org.fetarute.fetaruteTCAddon.drive.hud;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.DriverStationStop;
import org.fetarute.fetaruteTCAddon.drive.driver.DriverGuidance;
import org.fetarute.fetaruteTCAddon.drive.driver.DriverGuidanceConfig;
import org.fetarute.fetaruteTCAddon.drive.driver.DriverLink;
import org.fetarute.fetaruteTCAddon.drive.session.DriveSession;
import org.fetarute.fetaruteTCAddon.utils.LocaleManager;

/**
 * 驾驶员 Boss 栏：每名驾驶员一条，只发给本人。内容由 {@link DriverBossBarView} 决定；提示开始制动时红色与原色每半秒交替一次。
 *
 * <p>只在服务器主线程调用。
 */
public final class DriveBossBar {

  /** 提示开始制动时颜色交替的周期（tick）。 */
  private static final long FLASH_TICKS = 10L;

  private static final Component SPACE = Component.text(" ");

  private final Map<UUID, Shown> shown = new HashMap<>();

  /** 一名驾驶员正在看的 Boss 栏，以及本次停站的总时长（停站进度用）。 */
  private static final class Shown {
    private final BossBar bar;
    private Object dwellStop;
    private long dwellTotalTicks;

    private Shown(BossBar bar) {
      this.bar = bar;
    }
  }

  /**
   * 本次停站的总时长：同一次停站里见过的最大剩余时长。
   *
   * @param stop 停站对象（按对象身份区分不同的停站）；不在停站时为 {@code null}
   */
  public OptionalLong dwellTotal(UUID playerId, Object stop, long remainingTicks) {
    Shown entry = shown.get(playerId);
    if (entry == null || stop == null) {
      return OptionalLong.empty();
    }
    if (entry.dwellStop != stop) {
      entry.dwellStop = stop;
      entry.dwellTotalTicks = 0L;
    }
    entry.dwellTotalTicks = Math.max(entry.dwellTotalTicks, remainingTicks);
    return entry.dwellTotalTicks > 0L
        ? OptionalLong.of(entry.dwellTotalTicks)
        : OptionalLong.empty();
  }

  /** 按会话刷新：停站中显示停站阶段，其余时候显示行车引导；驾驶非调度列车时撤掉。 */
  public void refresh(
      Player player,
      LocaleManager locale,
      DriveSession session,
      DriverGuidance.Advice advice,
      DriverGuidanceConfig guidance) {
    DriverLink link = session.driverLink();
    if (link == null) {
      hide(player.getUniqueId());
      return;
    }
    Optional<DriverStationHint.Hint> hint = DriverStationHint.of(link, session.isStopped());
    DriverStationStop dwelling =
        link.stationStop()
            .filter(stop -> stop.phase() == DriverStationStop.Phase.DWELL)
            .orElse(null);
    long remaining = dwelling == null ? 0L : dwelling.dwellRemainingTicks();
    update(
        player,
        locale,
        DriverBossBarView.of(
            hint,
            advice,
            dwellTotal(player.getUniqueId(), dwelling, remaining),
            remaining,
            session.isAto(),
            link.directive() != null,
            session.isStopped(),
            link.targetLabel(),
            guidance.rangeBlocks()));
  }

  /** 显示或刷新。 */
  public void update(Player player, LocaleManager locale, DriverBossBarView view) {
    Component title = title(locale, view);
    BossBar.Color color = color(view);
    float progress = (float) view.progress();
    Shown entry = shown.get(player.getUniqueId());
    if (entry == null) {
      entry = new Shown(BossBar.bossBar(title, progress, color, BossBar.Overlay.NOTCHED_10));
      shown.put(player.getUniqueId(), entry);
      player.showBossBar(entry.bar);
      return;
    }
    entry.bar.name(title);
    entry.bar.progress(progress);
    entry.bar.color(color);
  }

  /** 撤掉某名驾驶员的 Boss 栏（玩家已下线时只清记录）。 */
  public void hide(UUID playerId) {
    Shown entry = shown.remove(playerId);
    if (entry == null) {
      return;
    }
    Player player = Bukkit.getPlayer(playerId);
    if (player != null) {
      player.hideBossBar(entry.bar);
    }
  }

  /** 是否正在给这名驾驶员显示。 */
  public boolean isShown(UUID playerId) {
    return shown.containsKey(playerId);
  }

  private static Component title(LocaleManager locale, DriverBossBarView view) {
    Component title = locale.component(view.titleKey(), view.values());
    if (view.ato()) {
      title = locale.component("drive.bossbar.ato-prefix").append(SPACE).append(title);
    }
    if (view.brake()) {
      title = locale.component("drive.bossbar.brake-advice").append(SPACE).append(title);
    }
    if (view.suggestedKmh().isPresent()) {
      title =
          title
              .append(SPACE)
              .append(
                  locale.component(
                      "drive.bossbar.suggest",
                      Map.of("speed_kmh", String.valueOf(view.suggestedKmh().getAsInt()))));
    }
    return title;
  }

  private static BossBar.Color color(DriverBossBarView view) {
    if (view.brake()) {
      boolean redPhase = (Bukkit.getCurrentTick() / FLASH_TICKS) % 2L == 0L;
      if (redPhase) {
        return BossBar.Color.RED;
      }
      if (view.tone() == DriverBossBarView.Tone.RED) {
        return BossBar.Color.WHITE;
      }
    }
    return switch (view.tone()) {
      case GREEN -> BossBar.Color.GREEN;
      case YELLOW -> BossBar.Color.YELLOW;
      case BLUE -> BossBar.Color.BLUE;
      case RED -> BossBar.Color.RED;
      case WHITE -> BossBar.Color.WHITE;
    };
  }
}
