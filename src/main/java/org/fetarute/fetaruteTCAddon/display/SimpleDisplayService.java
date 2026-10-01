package org.fetarute.fetaruteTCAddon.display;

import java.util.Objects;
import java.util.Optional;
import org.bukkit.scheduler.BukkitTask;
import org.fetarute.fetaruteTCAddon.FetaruteTCAddon;
import org.fetarute.fetaruteTCAddon.config.ConfigManager;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.EtaService;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinitionCache;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.LayoverRegistry;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.RouteProgressRegistry;
import org.fetarute.fetaruteTCAddon.display.hud.HudLanguageRotation;
import org.fetarute.fetaruteTCAddon.display.hud.actionbar.ActionBarTrainHudManager;
import org.fetarute.fetaruteTCAddon.display.hud.bossbar.BossBarTrainHudManager;
import org.fetarute.fetaruteTCAddon.display.hud.scoreboard.ScoreboardTrainHudManager;
import org.fetarute.fetaruteTCAddon.display.hud.trip.TripDialogService;
import org.fetarute.fetaruteTCAddon.display.template.HudDefaultTemplateService;
import org.fetarute.fetaruteTCAddon.display.template.HudTemplateService;

/**
 * 展示层实现：车上 BossBar、ActionBar、车内显示屏与后续站点对话框。
 *
 * <p>三块 HUD 根据配置分别启动定时任务，互不影响，可独立启停；后续站点对话框由乘客按需打开，总是可用。
 */
public final class SimpleDisplayService implements DisplayService {

  private final FetaruteTCAddon plugin;
  private final ConfigManager configManager;
  private final BossBarTrainHudManager bossBarHud;
  private final ActionBarTrainHudManager actionBarHud;
  private final ScoreboardTrainHudManager scoreboardHud;
  private final TripDialogService tripDialog;

  private BukkitTask bossBarTask;
  private BukkitTask actionBarTask;
  private BukkitTask scoreboardTask;

  public SimpleDisplayService(
      FetaruteTCAddon plugin,
      ConfigManager configManager,
      EtaService etaService,
      RouteDefinitionCache routeDefinitions,
      RouteProgressRegistry routeProgressRegistry,
      LayoverRegistry layoverRegistry,
      HudTemplateService templateService) {
    this.plugin = Objects.requireNonNull(plugin, "plugin");
    this.configManager = Objects.requireNonNull(configManager, "configManager");
    HudDefaultTemplateService defaultTemplateService = plugin.getHudDefaultTemplateService();
    this.bossBarHud =
        new BossBarTrainHudManager(
            plugin,
            plugin.getLocaleManager(),
            configManager,
            etaService,
            routeDefinitions,
            routeProgressRegistry,
            layoverRegistry,
            templateService,
            defaultTemplateService,
            plugin::debug);
    this.actionBarHud =
        new ActionBarTrainHudManager(
            plugin,
            plugin.getLocaleManager(),
            configManager,
            etaService,
            routeDefinitions,
            routeProgressRegistry,
            layoverRegistry,
            templateService,
            defaultTemplateService,
            plugin::debug);
    this.scoreboardHud =
        new ScoreboardTrainHudManager(
            plugin,
            plugin.getLocaleManager(),
            configManager,
            etaService,
            routeDefinitions,
            routeProgressRegistry,
            layoverRegistry,
            templateService,
            defaultTemplateService,
            plugin::debug);
    this.tripDialog =
        new TripDialogService(
            plugin,
            plugin.getLocaleManager(),
            etaService,
            routeDefinitions,
            routeProgressRegistry,
            layoverRegistry,
            templateService,
            plugin::debug);
  }

  @Override
  /** 根据配置启动 HUD 定时刷新。 */
  public void start() {
    if (bossBarTask != null || actionBarTask != null || scoreboardTask != null) {
      return;
    }
    tripDialog.register();

    ConfigManager.ConfigView view = configManager.current();
    if (view == null || view.runtimeSettings() == null) {
      return;
    }
    // 三块 HUD 共用一个中英文切换周期（见 HudLanguageRotation），不取各模板自己的轮播周期。
    HudLanguageRotation.setLanguagePeriodTicks(
        plugin
            .getConfig()
            .getLong(
                "runtime.hud.language-rotate-ticks",
                HudLanguageRotation.DEFAULT_LANGUAGE_PERIOD_TICKS));

    if (!view.runtimeSettings().hudBossBarEnabled()) {
      // skip
    } else {
      int interval = view.runtimeSettings().hudBossBarTickIntervalTicks();
      bossBarHud.register();
      bossBarTask =
          plugin
              .getServer()
              .getScheduler()
              .runTaskTimer(plugin, bossBarHud::tick, (long) interval, (long) interval);
    }

    if (view.runtimeSettings().hudActionBarEnabled()) {
      int interval = view.runtimeSettings().hudActionBarTickIntervalTicks();
      actionBarHud.register();
      actionBarTask =
          plugin
              .getServer()
              .getScheduler()
              .runTaskTimer(plugin, actionBarHud::tick, (long) interval, (long) interval);
    }

    if (view.runtimeSettings().hudPlayerDisplayEnabled()) {
      int interval = view.runtimeSettings().hudPlayerDisplayTickIntervalTicks();
      scoreboardHud.register();
      scoreboardTask =
          plugin
              .getServer()
              .getScheduler()
              .runTaskTimer(plugin, scoreboardHud::tick, (long) interval, (long) interval);
    }
  }

  @Override
  /** 停止 HUD 并清理展示。 */
  public void stop() {
    if (bossBarTask != null) {
      bossBarTask.cancel();
      bossBarTask = null;
    }
    if (actionBarTask != null) {
      actionBarTask.cancel();
      actionBarTask = null;
    }
    if (scoreboardTask != null) {
      scoreboardTask.cancel();
      scoreboardTask = null;
    }
    bossBarHud.shutdown();
    bossBarHud.unregister();
    actionBarHud.shutdown();
    actionBarHud.unregister();
    scoreboardHud.shutdown();
    scoreboardHud.unregister();
    tripDialog.unregister();
  }

  @Override
  public Optional<TripDialogService> tripDialog() {
    return Optional.of(tripDialog);
  }
}
