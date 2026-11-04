package org.fetarute.fetaruteTCAddon.stationmaster;

import com.bergerkiller.bukkit.tc.controller.MinecartGroup;
import com.bergerkiller.bukkit.tc.controller.MinecartMember;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import org.fetarute.fetaruteTCAddon.FetaruteTCAddon;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.StopAlignment;
import org.fetarute.fetaruteTCAddon.drive.crew.SignallerCallDialog;
import org.fetarute.fetaruteTCAddon.drive.hud.CrewMarks;
import org.fetarute.fetaruteTCAddon.drive.hud.DriveSidebar;
import org.fetarute.fetaruteTCAddon.drive.inventory.CrewHotbar;
import org.fetarute.fetaruteTCAddon.drive.inventory.CrewMenuHolder;
import org.fetarute.fetaruteTCAddon.drive.session.DriveSessionManager;
import org.fetarute.fetaruteTCAddon.utils.LocaleManager;

/**
 * 站长模块在服务器上的外壳：建服务、每秒巡检、监听下线、死亡与换游戏模式，插件关闭时让所有站长下岗。
 *
 * <p>配置在 {@code config.yml} 的 {@code station-master} 段；重载时整个模块重建（站长下岗、股道回到自动）。
 */
public final class StationMasterModule implements Listener {

  private static final long TICK_PERIOD = 20L;

  /** 监视采样与快捷栏、侧边栏刷新的周期（tick）。 */
  private static final long SAMPLE_PERIOD = 5L;

  /** 车厢罩子亮几拍、灭几拍（一拍是一个采样周期）。 */
  private static final long FLASH_SAMPLES = 2L;

  private final FetaruteTCAddon plugin;
  private final StationMasterService service;
  private final StationMasterConfig config;
  private final StationMasterHotbar hotbar;
  private final DriveSidebar sidebar;
  private final StationMasterBoard board;
  private final Map<UUID, BossBar> bars = new HashMap<>();
  private final Map<UUID, CrewMarks> marks = new HashMap<>();
  private long samples;
  private BukkitTask task;
  private BukkitTask sampleTask;

  public StationMasterModule(FetaruteTCAddon plugin) {
    this.plugin = Objects.requireNonNull(plugin, "plugin");
    this.config =
        StationMasterConfig.from(
            plugin.getConfig().getConfigurationSection("station-master"),
            plugin.getLogger()::warning);
    this.service =
        new StationMasterService(
            new DispatcherStationMasterWorld(plugin),
            () -> config,
            Instant::now,
            new Messenger(plugin, plugin::getLocaleManager));
    this.hotbar =
        new StationMasterHotbar(
            plugin, plugin::getLocaleManager, this::pressed, this::openMenu, this::clientMillis);
    this.sidebar = new DriveSidebar(plugin.getLocaleManager());
    this.board =
        new StationMasterBoard(
            clicked ->
                service.boardClicked(
                    clicked.player().getUniqueId(),
                    clicked.player().getName(),
                    clicked.player().hasPermission(StationMasterService.PERMISSION),
                    clicked.entry().station()));
    service.setDutyListener(
        new StationMasterService.DutyListener() {
          @Override
          public void started(UUID player) {
            Player online = Bukkit.getPlayer(player);
            if (online != null) {
              refresh(online);
            }
          }

          @Override
          public void ended(UUID player) {
            Player online = Bukkit.getPlayer(player);
            if (online != null) {
              hotbar.remove(online);
              sidebar.hide(online);
              if (online.getOpenInventory().getTopInventory().getHolder()
                  instanceof CrewMenuHolder) {
                online.closeInventory();
              }
            }
            sidebar.forget(player);
            hideBar(player, online);
            Optional.ofNullable(marks.remove(player)).ifPresent(mark -> mark.hide(online));
          }
        });
  }

  /** 站长的快捷栏：主类要把它接到驾驶模块的数据包监听上。 */
  @SuppressFBWarnings(value = "EI_EXPOSE_REP", justification = "数据包监听要问同一个快捷栏。")
  public CrewHotbar hotbar() {
    return hotbar;
  }

  /** 站长服务本体：主类要把它接到调度与在岗登记。 */
  @SuppressFBWarnings(value = "EI_EXPOSE_REP", justification = "故意交出服务本体：站台、出站门控、命令与在岗查询都要问同一个服务。")
  public StationMasterService service() {
    return service;
  }

  /** 打开站长任务板。 */
  public void openBoard(Player player) {
    LocaleManager locale = plugin.getLocaleManager();
    if (locale == null) {
      return;
    }
    if (service.onDuty(player.getUniqueId())) {
      // 在岗时物品保护不让开别的窗口
      player.sendMessage(locale.component("stationmaster.duty.start.already-on-duty"));
      return;
    }
    StationMasterBoard.open(player, locale, service.board());
  }

  /** 开始巡检、监听事件。 */
  public void start() {
    plugin.getServer().getPluginManager().registerEvents(this, plugin);
    plugin.getServer().getPluginManager().registerEvents(board, plugin);
    task =
        plugin
            .getServer()
            .getScheduler()
            .runTaskTimer(plugin, this::tick, TICK_PERIOD, TICK_PERIOD);
    sampleTask =
        plugin
            .getServer()
            .getScheduler()
            .runTaskTimer(plugin, this::sample, SAMPLE_PERIOD, SAMPLE_PERIOD);
  }

  /** 停止：所有站长下岗。 */
  public void stop() {
    if (task != null) {
      task.cancel();
      task = null;
    }
    if (sampleTask != null) {
      sampleTask.cancel();
      sampleTask = null;
    }
    HandlerList.unregisterAll(this);
    HandlerList.unregisterAll(board);
    service.shutdown();
    hotbar.removeAll();
    sidebar.hideAll();
    for (UUID id : List.copyOf(bars.keySet())) {
      hideBar(id, Bukkit.getPlayer(id));
    }
    marks.forEach((id, mark) -> mark.hide(Bukkit.getPlayer(id)));
    marks.clear();
  }

  private void sample() {
    try {
      service.sample();
      samples++;
      for (UUID id : service.onDutyPlayers()) {
        Player online = Bukkit.getPlayer(id);
        if (online != null) {
          refresh(online);
        }
      }
    } catch (RuntimeException ex) {
      plugin.getLogger().warning("站长监视采样失败：" + ex);
    }
  }

  /** 按此刻的样子刷新快捷栏与侧边栏（样子没变时不重发）。 */
  private void refresh(Player player) {
    UUID id = player.getUniqueId();
    service.view(id).ifPresent(view -> hotbar.show(player, view));
    hotbar.showHand(player);
    service
        .snapshot(id)
        .ifPresent(
            snapshot -> {
              sidebar.update(
                  player,
                  "stationmaster.sidebar.title",
                  snapshot.station(),
                  StationMasterDisplay.rows(snapshot));
              showBar(player, snapshot);
            });
    drawMarks(player);
  }

  /** Boss 栏：当前这一列到了哪一步，只发给本人。 */
  private void showBar(Player player, StationMasterDisplay.Snapshot snapshot) {
    LocaleManager locale = plugin.getLocaleManager();
    if (locale == null) {
      return;
    }
    StationMasterDisplay.Bar shown =
        StationMasterDisplay.bar(snapshot, config.signalTimeoutSeconds(), config.maxHoldSeconds());
    BossBar bar =
        bars.computeIfAbsent(
            player.getUniqueId(),
            id -> {
              BossBar created =
                  BossBar.bossBar(
                      Component.empty(), 1.0f, BossBar.Color.BLUE, BossBar.Overlay.PROGRESS);
              player.showBossBar(created);
              return created;
            });
    bar.name(locale.component(shown.key(), shown.values()));
    bar.progress(shown.progress());
    bar.color(shown.urgent() ? BossBar.Color.GREEN : BossBar.Color.BLUE);
  }

  private void hideBar(UUID id, Player online) {
    BossBar bar = bars.remove(id);
    if (bar != null && online != null) {
      online.hideBossBar(bar);
    }
  }

  /** 站位标线一直画到这一列办完；车厢罩子只在要认车时闪：两拍亮两拍灭。 */
  private void drawMarks(Player player) {
    UUID id = player.getUniqueId();
    Optional<StationMasterDisplay.Marks> wanted = service.marks(id);
    if (wanted.isEmpty()) {
      Optional.ofNullable(marks.get(id)).ifPresent(mark -> mark.hide(player));
      return;
    }
    CrewMarks mark = marks.computeIfAbsent(id, ignored -> new CrewMarks());
    StationMasterWorld.StandPoint stand = wanted.get().stand();
    World world = Bukkit.getWorld(stand.head().world());
    if (world == null || !world.equals(player.getWorld())) {
      mark.hide(player);
      return;
    }
    mark.showStand(
        player,
        world,
        new Vector(stand.head().x(), stand.head().y(), stand.head().z()),
        new Vector(stand.forwardX(), 0.0, stand.forwardZ()));
    boolean lit = wanted.get().flashing() && (samples / FLASH_SAMPLES) % 2L == 0L;
    if (lit) {
      mark.showCars(player, world, cars(wanted.get().trainName()));
    } else {
      mark.hideCars(player);
    }
  }

  private static List<CrewMarks.Car> cars(String trainName) {
    Optional<MinecartGroup> found = DispatcherStationMasterWorld.group(trainName);
    if (found.isEmpty()) {
      return List.of();
    }
    MinecartGroup group = found.get();
    double length = 2.0 * StopAlignment.frontOffsetBlocks(group);
    List<CrewMarks.Car> cars = new ArrayList<>(group.size());
    for (MinecartMember<?> member : group) {
      Vector forward = member.getOrientationForward();
      cars.add(
          new CrewMarks.Car(
              member.getEntity().getLocation().toVector(),
              forward == null ? new Vector(1.0, 0.0, 0.0) : forward,
              length));
    }
    return cars;
  }

  /** 玩家的客户端时钟；驾驶模块没接上时按真实时间。 */
  private long clientMillis(UUID id) {
    DriveSessionManager drive = plugin.getDriveSessionManager();
    return drive == null ? System.currentTimeMillis() : drive.clientClock().now(id);
  }

  /** 打开菜单（F）。 */
  private void openMenu(Player player) {
    UUID id = player.getUniqueId();
    LocaleManager locale = plugin.getLocaleManager();
    Optional<StationMasterDisplay.Snapshot> snapshot = service.snapshot(id);
    if (locale == null || snapshot.isEmpty() || hotbar.menuTopSize(id) > 0) {
      return;
    }
    Inventory inventory =
        StationMasterMenu.create(
            locale,
            snapshot.get(),
            service.view(id).map(StationMasterDisplay.View::offConfirm).orElse(false),
            this::menuClicked,
            closed -> hotbar.setMenuOpen(closed.getUniqueId(), 0));
    hotbar.setMenuOpen(id, StationMasterMenu.SIZE);
    if (player.openInventory(inventory) == null) {
      hotbar.setMenuOpen(id, 0);
    }
  }

  private void menuClicked(Player player, int slot) {
    UUID id = player.getUniqueId();
    Optional<StationMasterMenu.Action> action = StationMasterMenu.Action.at(slot);
    if (action.isEmpty()) {
      return;
    }
    switch (action.get()) {
      case CALL -> {
        player.closeInventory();
        openCall(player);
      }
      case OFF -> {
        service.pressOff(id);
        if (service.onDuty(id)) {
          LocaleManager locale = plugin.getLocaleManager();
          Inventory top = player.getOpenInventory().getTopInventory();
          service
              .snapshot(id)
              .ifPresent(
                  snapshot ->
                      StationMasterMenu.render(
                          top,
                          locale,
                          snapshot,
                          service
                              .view(id)
                              .map(StationMasterDisplay.View::offConfirm)
                              .orElse(false)));
        }
      }
      case INFO -> {}
    }
  }

  /** 快捷栏上按下的按钮（主线程）。 */
  private void pressed(Player player, StationMasterHotbar.Button button) {
    UUID id = player.getUniqueId();
    switch (button) {
      case SIGNAL -> service.signal(id);
      case HOLD -> service.toggleHold(id);
      case CLOSE -> service.urgeClose(id);
      case EMERGENCY -> service.emergency(id);
    }
    refresh(player);
  }

  /** 呼叫信号员：就身旁的车弹出选原因的对话框。 */
  private void openCall(Player player) {
    UUID id = player.getUniqueId();
    Optional<String> train = service.callTarget(id);
    LocaleManager locale = plugin.getLocaleManager();
    if (locale == null) {
      return;
    }
    if (train.isEmpty()) {
      player.sendMessage(locale.component("stationmaster.call.no-train"));
      return;
    }
    SignallerCallDialog.open(
        plugin,
        locale,
        player,
        service.stationName(id).orElse(""),
        false,
        (who, reason) -> service.call(who.getUniqueId(), train.get(), reason));
  }

  private void tick() {
    try {
      service.tick();
    } catch (RuntimeException ex) {
      plugin.getLogger().warning("站长巡检失败：" + ex);
    }
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

  private static NamespacedKey tipKey(Plugin plugin, StationMasterMessenger.Tip tip) {
    return new NamespacedKey(plugin, "stationmaster_tip_" + tip.key().replace('-', '_'));
  }

  /** 重置站长的第一次提示（与驾驶、车掌提示一起，{@code /fta drive tutorial reset}）。 */
  public static void resetTips(Plugin plugin, Player player) {
    for (StationMasterMessenger.Tip tip : StationMasterMessenger.Tip.values()) {
      player.getPersistentDataContainer().remove(tipKey(plugin, tip));
    }
  }

  /** 发语言文件里的消息；提醒另加动作栏与提示音。第一次提示记在玩家持久数据里，每种只出一次。 */
  private static final class Messenger implements StationMasterMessenger {

    private final Plugin plugin;
    private final Supplier<LocaleManager> locale;

    Messenger(Plugin plugin, Supplier<LocaleManager> locale) {
      this.plugin = plugin;
      this.locale = locale;
    }

    @Override
    public void tip(UUID player, Tip tip) {
      Player online = player == null ? null : Bukkit.getPlayer(player);
      LocaleManager manager = locale.get();
      if (online == null || manager == null) {
        return;
      }
      NamespacedKey flag = tipKey(plugin, tip);
      if (online.getPersistentDataContainer().has(flag, PersistentDataType.BYTE)) {
        return;
      }
      online.getPersistentDataContainer().set(flag, PersistentDataType.BYTE, (byte) 1);
      online.sendMessage(manager.component("stationmaster.tips." + tip.key()));
      online.playSound(online.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 0.6f, 1.6f);
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
    public void notice(UUID player, String key, Map<String, String> values) {
      Player online = player == null ? null : Bukkit.getPlayer(player);
      LocaleManager manager = locale.get();
      if (online == null || manager == null) {
        return;
      }
      online.sendActionBar(manager.component(key, values));
      online.playSound(online.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 0.8f, 1.4f);
    }

    @Override
    public void cue(UUID player, String key, Map<String, String> values) {
      Player online = player == null ? null : Bukkit.getPlayer(player);
      LocaleManager manager = locale.get();
      if (online == null || manager == null) {
        return;
      }
      online.sendActionBar(manager.component(key, values));
    }

    @Override
    public String text(String key) {
      LocaleManager manager = locale.get();
      return manager == null ? key : manager.text(key);
    }
  }
}
