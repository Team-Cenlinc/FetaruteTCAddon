package org.fetarute.fetaruteTCAddon.signaller;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;
import org.fetarute.fetaruteTCAddon.FetaruteTCAddon;
import org.fetarute.fetaruteTCAddon.api.event.SignallerDutyEndedEvent;
import org.fetarute.fetaruteTCAddon.api.event.SignallerDutyStartEvent;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.SignallerPlatformDesk;
import org.fetarute.fetaruteTCAddon.drive.crew.SignallerShift;
import org.fetarute.fetaruteTCAddon.utils.LocaleManager;

/**
 * 信号员模块在服务器上的外壳：建服务、每秒巡检、监听下线、死亡与换游戏模式，插件关闭时让所有值守下岗。
 *
 * <p>配置在 {@code config.yml} 的 {@code signaller} 段；重载时整个模块重建（值守下岗、回到自动）。
 */
public final class SignallerModule implements Listener {

  private static final long TICK_PERIOD = 20L;

  private final FetaruteTCAddon plugin;
  private final SignallerService service;
  private final SignallerConfig config;
  private BukkitTask task;

  /**
   * @param plugin 插件主类
   * @param lineColors （运营商代码, 线路代码）→ 线路色
   * @param platforms 改停股道（调度服务重建后会换）
   */
  public SignallerModule(
      FetaruteTCAddon plugin,
      BiFunction<String, String, Optional<Integer>> lineColors,
      Supplier<SignallerPlatformDesk> platforms) {
    this.plugin = Objects.requireNonNull(plugin, "plugin");
    this.config = readConfig();
    this.service =
        new SignallerService(
            new DispatcherSignallerWorld(plugin, lineColors, platforms),
            () -> config,
            Instant::now,
            Bukkit::getCurrentTick,
            new Messenger(plugin::getLocaleManager),
            plugin::debug);
  }

  /** 信号员服务本体：主类要把它接到调度、驾驶模块与站台屏。 */
  @SuppressFBWarnings(value = "EI_EXPOSE_REP", justification = "故意交出服务本体：闸、控制台、呼叫与在岗查询都要问同一个服务。")
  public SignallerService service() {
    return service;
  }

  public SignallerConfig config() {
    return config;
  }

  /** 开始巡检、监听事件。 */
  public void start() {
    service.setDutyEvents(new ApiEvents());
    plugin.getServer().getPluginManager().registerEvents(this, plugin);
    task =
        plugin
            .getServer()
            .getScheduler()
            .runTaskTimer(plugin, this::tick, TICK_PERIOD, TICK_PERIOD);
  }

  /** 停止：所有值守下岗并结算。 */
  public void stop() {
    if (task != null) {
      task.cancel();
      task = null;
    }
    HandlerList.unregisterAll(this);
    service.shutdown();
  }

  private void tick() {
    try {
      service.tick();
    } catch (RuntimeException ex) {
      plugin.getLogger().warning("信号员巡检失败：" + ex);
    }
  }

  private SignallerConfig readConfig() {
    return SignallerConfig.from(
        plugin.getConfig().getConfigurationSection("signaller"), plugin.getLogger()::warning);
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void onQuit(PlayerQuitEvent event) {
    service.playerQuit(event.getPlayer().getUniqueId());
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void onDeath(PlayerDeathEvent event) {
    service.playerIncapacitated(event.getEntity().getUniqueId());
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onGameMode(PlayerGameModeChangeEvent event) {
    service.playerIncapacitated(event.getPlayer().getUniqueId());
  }

  /** 上岗与下岗发成公开 API 事件（主线程当场发出）。 */
  private final class ApiEvents implements SignallerService.DutyEvents {

    @Override
    public void started(
        UUID player, String operator, String zone, String zoneName, boolean manual) {
      call(new SignallerDutyStartEvent(player, operator, zone, zoneName, manual));
    }

    @Override
    public void ended(SignallerShift shift, String zoneName) {
      call(
          new SignallerDutyEndedEvent(
              shift.player(),
              shift.operator(),
              shift.zone(),
              zoneName,
              shift.start(),
              shift.end(),
              shift.endReason().name(),
              shift.trainsHandled(),
              shift.routesSet(),
              shift.autoRouted(),
              shift.autoPassed(),
              shift.holds(),
              shift.callsAnswered(),
              shift.callsMissed()));
    }

    private void call(Event event) {
      try {
        plugin.getServer().getPluginManager().callEvent(event);
      } catch (RuntimeException ex) {
        plugin.getLogger().warning("信号员事件监听器出错：" + ex);
      }
    }
  }

  /** 发语言文件里的消息；提醒另加动作栏与提示音。 */
  private static final class Messenger implements SignallerMessenger {

    private final Supplier<LocaleManager> locale;

    Messenger(Supplier<LocaleManager> locale) {
      this.locale = locale;
    }

    @Override
    public void send(UUID player, String key, Map<String, String> values) {
      Player online = player == null ? null : Bukkit.getPlayer(player);
      LocaleManager manager = locale.get();
      if (online == null || manager == null) {
        return;
      }
      online.sendMessage(manager.component(key, values));
    }

    @Override
    public void alert(UUID player, String key, Map<String, String> values) {
      Player online = player == null ? null : Bukkit.getPlayer(player);
      LocaleManager manager = locale.get();
      if (online == null || manager == null) {
        return;
      }
      online.sendMessage(manager.component(key, values));
      online.sendActionBar(manager.component(key + "-bar", values));
      online.playSound(online.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 0.8f, 1.4f);
    }

    @Override
    public void cue(UUID player, Cue cue, Map<String, String> values) {
      Player online = player == null ? null : Bukkit.getPlayer(player);
      LocaleManager manager = locale.get();
      if (online == null || manager == null) {
        return;
      }
      online.sendActionBar(manager.component(cue.key(), values));
      switch (cue) {
          // 接近：一声轻的钟琴；到入口等候：低一些的两声，要信号员动手
        case APPROACHING -> online.playSound(
            online.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 0.6f, 1.2f);
        case WAITING -> {
          online.playSound(online.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.7f, 0.9f);
          online.playSound(online.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.7f, 0.7f);
        }
      }
    }
  }
}
