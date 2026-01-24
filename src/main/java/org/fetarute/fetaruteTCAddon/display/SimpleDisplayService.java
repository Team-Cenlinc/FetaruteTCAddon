package org.fetarute.fetaruteTCAddon.display;

import java.util.Objects;
import org.bukkit.scheduler.BukkitTask;
import org.fetarute.fetaruteTCAddon.FetaruteTCAddon;
import org.fetarute.fetaruteTCAddon.config.ConfigManager;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.EtaService;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinitionCache;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.LayoverRegistry;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.RouteProgressRegistry;
import org.fetarute.fetaruteTCAddon.display.hud.bossbar.BossBarTrainHudManager;
import org.fetarute.fetaruteTCAddon.display.template.HudDefaultTemplateService;
import org.fetarute.fetaruteTCAddon.display.template.HudTemplateService;

/** 展示层实现：目前只包含车上 BossBar HUD。 */
public final class SimpleDisplayService implements DisplayService {

  private final FetaruteTCAddon plugin;
  private final ConfigManager configManager;
  private final BossBarTrainHudManager bossBarHud;

  private BukkitTask bossBarTask;

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
  }

  @Override
  /** 根据配置启动 BossBar HUD 定时刷新。 */
  public void start() {
    if (bossBarTask != null) {
      return;
    }

    ConfigManager.ConfigView view = configManager.current();
    if (view == null || view.runtimeSettings() == null) {
      return;
    }

    if (!view.runtimeSettings().hudBossBarEnabled()) {
      return;
    }

    int interval = view.runtimeSettings().hudBossBarTickIntervalTicks();
    bossBarHud.register();
    bossBarTask =
        plugin
            .getServer()
            .getScheduler()
            .runTaskTimer(plugin, bossBarHud::tick, (long) interval, (long) interval);
  }

  @Override
  /** 停止 BossBar HUD 并清理已展示的 BossBar。 */
  public void stop() {
    if (bossBarTask != null) {
      bossBarTask.cancel();
      bossBarTask = null;
    }
    bossBarHud.shutdown();
    bossBarHud.unregister();
  }
}
