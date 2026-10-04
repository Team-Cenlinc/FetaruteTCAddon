package org.fetarute.fetaruteTCAddon.drive.session;

import com.bergerkiller.bukkit.common.utils.PacketUtil;
import com.bergerkiller.bukkit.tc.controller.MinecartGroup;
import com.bergerkiller.bukkit.tc.controller.MinecartGroupStore;
import com.bergerkiller.bukkit.tc.controller.MinecartMember;
import com.bergerkiller.generated.net.minecraft.network.protocol.game.PacketPlayOutSetSlotHandle;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import org.fetarute.fetaruteTCAddon.FetaruteTCAddon;
import org.fetarute.fetaruteTCAddon.api.drive.DriveApi;
import org.fetarute.fetaruteTCAddon.api.event.DriveSessionEndedEvent;
import org.fetarute.fetaruteTCAddon.api.event.DriveSessionStartEvent;
import org.fetarute.fetaruteTCAddon.api.event.DriverPickupEvent;
import org.fetarute.fetaruteTCAddon.api.event.DriverStopScoredEvent;
import org.fetarute.fetaruteTCAddon.api.event.DriverTaskClaimEvent;
import org.fetarute.fetaruteTCAddon.api.event.DriverTaskFinishedEvent;
import org.fetarute.fetaruteTCAddon.api.event.DriverTaskStartedEvent;
import org.fetarute.fetaruteTCAddon.company.api.StationDirectory;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.DriverControlTags;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.LayoverRegistry;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.TrainTagHelper;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.config.TrainConfigResolver;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.ControlAuthority;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.ControlDiagnostics;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.DriverInterrupt;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.DriverStationStop;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.StopAlignment;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspect;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SimpleTicketAssigner;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableService;
import org.fetarute.fetaruteTCAddon.display.hud.TrainHudContext;
import org.fetarute.fetaruteTCAddon.drive.DriveConfig;
import org.fetarute.fetaruteTCAddon.drive.SimulationLevel;
import org.fetarute.fetaruteTCAddon.drive.cab.AirSystem;
import org.fetarute.fetaruteTCAddon.drive.cab.BrakeTest;
import org.fetarute.fetaruteTCAddon.drive.cab.CabSystems;
import org.fetarute.fetaruteTCAddon.drive.cab.Vigilance;
import org.fetarute.fetaruteTCAddon.drive.driver.DriverCongestion;
import org.fetarute.fetaruteTCAddon.drive.driver.DriverControlRegistry;
import org.fetarute.fetaruteTCAddon.drive.driver.DriverDoorSide;
import org.fetarute.fetaruteTCAddon.drive.driver.DriverGuidance;
import org.fetarute.fetaruteTCAddon.drive.driver.DriverGuidanceConfig;
import org.fetarute.fetaruteTCAddon.drive.driver.DriverLink;
import org.fetarute.fetaruteTCAddon.drive.driver.DriverRecovery;
import org.fetarute.fetaruteTCAddon.drive.driver.DriverRescueLadder;
import org.fetarute.fetaruteTCAddon.drive.driver.DriverSchedule;
import org.fetarute.fetaruteTCAddon.drive.driver.DrivingMode;
import org.fetarute.fetaruteTCAddon.drive.driver.StationStopPoints;
import org.fetarute.fetaruteTCAddon.drive.driver.record.DriveTaskRecord;
import org.fetarute.fetaruteTCAddon.drive.driver.record.DriveTaskRecordCodec;
import org.fetarute.fetaruteTCAddon.drive.driver.score.ScoreRules;
import org.fetarute.fetaruteTCAddon.drive.driver.score.StopScore;
import org.fetarute.fetaruteTCAddon.drive.driver.score.TaskScore;
import org.fetarute.fetaruteTCAddon.drive.driver.task.DriverPickups;
import org.fetarute.fetaruteTCAddon.drive.driver.task.DriverTask;
import org.fetarute.fetaruteTCAddon.drive.driver.task.DriverTaskManager;
import org.fetarute.fetaruteTCAddon.drive.driver.task.PickupSpot;
import org.fetarute.fetaruteTCAddon.drive.driver.task.TaskBoardEntries;
import org.fetarute.fetaruteTCAddon.drive.driver.task.TaskBoardHolder;
import org.fetarute.fetaruteTCAddon.drive.driver.task.TaskKey;
import org.fetarute.fetaruteTCAddon.drive.driver.task.TaskViews;
import org.fetarute.fetaruteTCAddon.drive.dynamics.DriveMode;
import org.fetarute.fetaruteTCAddon.drive.dynamics.DriveParams;
import org.fetarute.fetaruteTCAddon.drive.dynamics.Notch;
import org.fetarute.fetaruteTCAddon.drive.dynamics.ReverserPosition;
import org.fetarute.fetaruteTCAddon.drive.hud.DriveBossBar;
import org.fetarute.fetaruteTCAddon.drive.hud.DriveHud;
import org.fetarute.fetaruteTCAddon.drive.hud.DriveSidebar;
import org.fetarute.fetaruteTCAddon.drive.hud.DriverReport;
import org.fetarute.fetaruteTCAddon.drive.hud.StopMarker;
import org.fetarute.fetaruteTCAddon.drive.inventory.DrivePacketListener;
import org.fetarute.fetaruteTCAddon.drive.inventory.HotbarItems;
import org.fetarute.fetaruteTCAddon.drive.inventory.HotbarRewriter;
import org.fetarute.fetaruteTCAddon.drive.inventory.InputSignal;
import org.fetarute.fetaruteTCAddon.drive.menu.DriveDoors;
import org.fetarute.fetaruteTCAddon.drive.menu.DriveMenu;
import org.fetarute.fetaruteTCAddon.drive.menu.MenuAction;
import org.fetarute.fetaruteTCAddon.drive.menu.MenuLayout;
import org.fetarute.fetaruteTCAddon.drive.menu.TaskCard;
import org.fetarute.fetaruteTCAddon.drive.seat.SeatBinding;
import org.fetarute.fetaruteTCAddon.drive.seat.SeatLocator;
import org.fetarute.fetaruteTCAddon.drive.setup.PowerSupply;
import org.fetarute.fetaruteTCAddon.drive.setup.SetupAnimations;
import org.fetarute.fetaruteTCAddon.drive.setup.SetupSystem;
import org.fetarute.fetaruteTCAddon.drive.setup.TrainSetup;
import org.fetarute.fetaruteTCAddon.drive.setup.TrainSetupStore;
import org.fetarute.fetaruteTCAddon.drive.sound.DriveCue;
import org.fetarute.fetaruteTCAddon.drive.sound.DriveCueTracker;
import org.fetarute.fetaruteTCAddon.drive.sound.DriveSounds;
import org.fetarute.fetaruteTCAddon.interlink.ServerIdentity;
import org.fetarute.fetaruteTCAddon.utils.LocaleManager;

/**
 * 手动驾驶会话的生命周期：开始、逐 tick 维护、结束，以及网络线程与主线程之间的交接。
 *
 * <p>线程约定：{@link #rewriterFor}、{@link #onInput}、{@link #onSneak} 与 {@link #trace}
 * 由网络线程调用，只读并发表、把工作投递回主线程；其余方法都在主线程。
 *
 * <p>服务器端的玩家背包在整个过程中只读：快捷栏的驾驶物品只出现在数据包里，结束时重发真实背包即可恢复客户端画面。
 */
public final class DriveSessionManager implements DrivePacketListener.Host {

  /** 开始驾驶的结果。 */
  public enum StartOutcome {
    STARTED,
    DISABLED,
    BAD_GAME_MODE,
    ALREADY_DRIVING,
    NOT_SEATED,
    MANAGED_TRAIN,
    TRAIN_IN_USE,
    TRAIN_MOVING,
    CURSOR_NOT_EMPTY,
    INVENTORY_BUSY,
    UNAVAILABLE,
    FAILED,
    /** 调度列车：没有可用的驾驶任务（或权限）。 */
    NO_TASK,
    /** 调度列车：只能在车站停站时接管。 */
    NOT_STOPPED_AT_STATION,
    /** 调度列车：只能坐在车头驾驶室接管。 */
    NOT_HEAD_CAB,
    /** 调度列车：线路拥堵熔断，暂停接班。 */
    BREAKER_OPEN,
    /** 被外部插件拦下（{@code DriveSessionStartEvent} 取消）。 */
    CANCELLED
  }

  /** 领取驾驶任务、驾驶调度列车。 */
  public static final String PERMISSION_DRIVER = "fetarute.drive.driver";

  /** 不领任务也能直接接管调度列车（调试、运营人员）。 */
  public static final String PERMISSION_DRIVER_ADMIN = "fetarute.drive.driver.admin";

  /** 每隔多少 tick 推进一次已领取的任务。 */
  private static final int TASK_TICKS = 10;

  /** 终点站接车留出的余量（秒）：票据到 assign-tolerance 作废之前先放行。 */
  private static final long PICKUP_TOLERANCE_MARGIN_SECONDS = 30L;

  /** 每隔多少 tick 评估一次全网熔断。 */
  private static final int BREAKER_TICKS = 100;

  /** 一直被扣住这么久就交还自动运行（表定停站、按表扣车一般远短于它）。 */
  private static final long HELD_HANDBACK_SECONDS = 600L;

  /** 每隔多少 tick 检查一次是否到了终点站。 */
  private static final int TERMINAL_CHECK_TICKS = 20;

  /** 按交路进度刷新“下一站”的间隔（tick）。 */
  private static final int NEXT_STOP_REFRESH_TICKS = 20;

  /** 每多少 tick 重发一次背包，兜底没被数据包改写覆盖到的背包更新。重发的内容本身已被改写成驾驶物品，不会触发客户端的“收到物品”动画。 */
  private static final int HOTBAR_REFRESH_TICKS = 100;

  private static final double TICKS_PER_SECOND = 20.0;

  /** 1 格/秒折合的 km/h。 */
  private static final double KMH_PER_BPS = 3.6;

  private static final long MILLIS_PER_MINUTE = 60_000L;

  /** 启动流程进行中或有风压等实时数值时，打开着的菜单每隔多少 tick 刷新一次。 */
  private static final int MENU_PROGRESS_TICKS = 10;

  /** 驾驶台提示在动作栏停留的时间（tick），期间驾驶 HUD 不覆盖动作栏。 */
  private static final long NOTICE_HOLD_TICKS = 40L;

  /** “结束驾驶”第一次点击后，多少 tick 内再点才算确认。 */
  private static final long END_CONFIRM_TICKS = 60L;

  /** 尝试重新入座的间隔（tick）。 */
  private static final int RESEAT_RETRY_TICKS = 5;

  private final FetaruteTCAddon plugin;
  private final ConcurrentHashMap<UUID, DriveSession> active = new ConcurrentHashMap<>();
  private final List<DriveSession> stopping = new ArrayList<>();
  private final ConcurrentHashMap<UUID, Boolean> refreshPending = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<UUID, Boolean> ackPending = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<UUID, Long> lastUseTick = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<UUID, Boolean> freshUse = new ConcurrentHashMap<>();

  /** 右键包间隔超过这么多 tick 才算新按下（按住时客户端每几 tick 发一次）。 */
  private static final long USE_HOLD_GAP_TICKS = 8L;

  private final DrivePacketListener packetListener = new DrivePacketListener(this);
  private final DriveMenu menu;
  private final DriveSidebar sidebar;
  private final DriveBossBar bossBar = new DriveBossBar();
  private final DriveSounds sounds = new DriveSounds();
  private final Map<UUID, DriveCueTracker> cueTrackers = new HashMap<>();
  private final Map<UUID, Long> lastHornTick = new HashMap<>();
  private final StationStopPoints stationStopPoints;
  private final StopMarker stopMarker;
  private final Map<UUID, DriveDoors> doors = new HashMap<>();

  /** 已提示过确认接班的座位：换了座位才再在聊天栏提示一次。 */
  private final Map<UUID, SeatBinding> seatPrompts = new HashMap<>();

  /** 对外查询的快照（任务、驾驶会话）：主线程每半秒刷新，供其他线程读取（例如 Typewriter 在异步线程读事实值）。 */
  private volatile Map<UUID, DriveApi.TaskView> taskViews = Map.of();

  private volatile Map<UUID, DriveApi.SessionView> sessionViews = Map.of();

  /** 始发站与车库接班：派车时先留着列车等驾驶员上车。 */
  private final DriverPickups pickups = new DriverPickups();

  /** 已提示过换端失败的驾驶员：每次会话只提示一次。 */
  private final Set<UUID> cabChangeFailed = new HashSet<>();

  private final DriverControlRegistry driverRegistry = new DriverControlRegistry();
  private final DriverTaskManager tasks;

  private volatile DriveConfig config;
  private volatile boolean trace;
  private volatile boolean packetsReady;
  private BukkitTask tickTask;
  private long tickCounter;

  public DriveSessionManager(FetaruteTCAddon plugin, DriveConfig config) {
    this.plugin = plugin;
    this.config = config;
    this.menu = new DriveMenu(plugin.getLocaleManager(), this::taskSummary);
    this.sidebar = new DriveSidebar(plugin.getLocaleManager());
    this.stationStopPoints =
        new StationStopPoints(
            node ->
                plugin.getSignNodeRegistry() == null
                    ? Optional.empty()
                    : plugin.getSignNodeRegistry().findByNodeId(node, null),
            plugin::getStopMarkIndex);
    this.stopMarker = new StopMarker(stationStopPoints::lookup);
    driverRegistry.setHandler(new DriverHandler());
    driverRegistry.setAwaitingDriver(pickups::awaiting);
    this.tasks = new DriverTaskManager(plugin, this::traceTask);
    tasks.setListener(new TaskEvents());
    applyDriverConfig(config);
  }

  /** 驾驶任务。 */
  public DriverTaskManager tasks() {
    return tasks;
  }

  private void applyDriverConfig(DriveConfig current) {
    sounds.configure(current.sounds());
    driverRegistry.setAtoConfirmTicks(current.driver().recovery().atoConfirmSeconds() * 20L);
    for (DriveSession session : active.values()) {
      if (session.driverLink() != null) {
        session.driverLink().setStopWindow(current.driver().stopWindow());
      }
    }
  }

  private void traceTask(String message) {
    if (trace) {
      plugin.getLogger().info("[drive-probe] " + message);
    }
  }

  /** 调度层经它判断列车是否由驾驶员控制。 */
  public ControlAuthority controlAuthority() {
    return driverRegistry;
  }

  /** 把调度层的停车、销毁要求与交还请求转给对应的驾驶会话。 */
  private final class DriverHandler implements DriverControlRegistry.Handler {
    @Override
    public void onInterrupt(DriverLink link, DriverInterrupt interrupt) {
      onDriverInterrupt(link, interrupt);
    }

    @Override
    public void onHandbackRequested(DriverLink link, String reason) {
      DriveSession session = sessionOf(link);
      if (session == null) {
        driverRegistry.unbind(link);
        return;
      }
      requestHandback(session, reason);
    }
  }

  /**
   * 注册数据包监听并启动逐 tick 维护任务。
   *
   * <p>数据包监听注册失败时手动驾驶保持不可用（{@link StartOutcome#UNAVAILABLE}），不影响插件其余功能：没有数据包改写就无法保证不动玩家背包。
   */
  public void start() {
    if (tickTask != null) {
      return;
    }
    try {
      packetListener.register(plugin);
      packetsReady = true;
    } catch (RuntimeException | LinkageError ex) {
      packetsReady = false;
      plugin.getLogger().severe("手动驾驶的数据包监听注册失败，手动驾驶不可用: " + ex);
    }
    tickTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    // 服务器重启前若在驾驶中崩溃，列车标签里还留着调整前的属性；此时没有任何会话，直接还原。
    Bukkit.getScheduler().runTask(plugin, this::restoreStaleProperties);
    Bukkit.getScheduler().runTaskLater(plugin, this::restoreStaleProperties, 100L);
  }

  private void restoreStaleProperties() {
    for (MinecartGroup group : MinecartGroupStore.getGroups()) {
      if (group == null || !group.isValid() || group.getProperties() == null) {
        continue;
      }
      var properties = group.getProperties();
      if (properties.getTrainName() != null && trainInUse(properties.getTrainName())) {
        // 启动后马上开始的会话已经按自己的方式调整了属性，不能当作崩服残留还原。
        continue;
      }
      TrainPropertyGuard.restore(properties);
      // 崩服前在驾驶的调度列车：标签还在、绑定已不在，清掉标签让它回到自动运行。
      DriverControlTags.clear(properties);
    }
  }

  /** 结束全部会话并注销监听。插件停用时调用；重载配置不会调用它。 */
  public void shutdown() {
    if (tickTask != null) {
      tickTask.cancel();
      tickTask = null;
    }
    for (DriveSession session : new ArrayList<>(active.values())) {
      endNow(session, DriveSession.EndReason.DISABLED);
    }
    for (DriveSession session : new ArrayList<>(stopping)) {
      endNow(session, DriveSession.EndReason.DISABLED);
    }
    stopping.clear();
    for (DriverPickups.Pickup pickup : pickups.all()) {
      pickups.remove(pickup.playerId());
      releasePickup(pickup);
    }
    sidebar.hideAll();
    for (UUID playerId : new ArrayList<>(active.keySet())) {
      bossBar.hide(playerId);
    }
    stopMarker.removeAll();
    DrivePacketListener.unregister(plugin);
  }

  public DriveConfig config() {
    return config;
  }

  public void reload(DriveConfig newConfig) {
    this.config = newConfig;
    applyDriverConfig(newConfig);
    if (!newConfig.driver().enabled()) {
      handbackAll("disabled");
    }
  }

  public void setTrace(boolean enabled) {
    this.trace = enabled;
  }

  public boolean trace() {
    return trace;
  }

  public boolean isDriving(UUID playerId) {
    return active.containsKey(playerId);
  }

  public Optional<DriveSession> sessionOf(UUID playerId) {
    return Optional.ofNullable(active.get(playerId));
  }

  /** 全部会话：驾驶中的与制动停车中的。 */
  public List<DriveSession> sessions() {
    List<DriveSession> all = new ArrayList<>(active.values());
    all.addAll(stopping);
    return all;
  }

  // ---- 网络线程入口 ----

  @Override
  public HotbarRewriter<ItemStack> rewriterFor(UUID playerId) {
    DriveSession session = active.get(playerId);
    return session == null ? null : session.rewriter();
  }

  @Override
  public void onRewriteFailed(Player player, RuntimeException cause) {
    DriveSession session = active.get(player.getUniqueId());
    if (session == null) {
      return;
    }
    if (!session.rewriteFailed()) {
      session.markRewriteFailed();
      plugin.getLogger().warning("改写 " + player.getName() + " 的背包数据包失败，改为逐格补发驾驶物品: " + cause);
    }
  }

  @Override
  public int menuTopSize(UUID playerId) {
    DriveSession session = active.get(playerId);
    return session == null ? 0 : session.menuTopSize();
  }

  @Override
  public void onInput(Player player, InputSignal signal) {
    UUID id = player.getUniqueId();
    if (signal == InputSignal.SWAP_HANDS) {
      // F 键打开停车后菜单。这个信号不能和其它信号合并，否则同一 tick 里的丢弃键会把它吞掉。
      Bukkit.getScheduler().runTask(plugin, () -> openMenu(player));
    }
    if (signal == InputSignal.USE) {
      // 右键是警惕装置、信号与 ATO 发车的确认键。它不会让客户端改动快捷栏，不必重发背包；按住右键时客户端会连续发包，合并到一次。
      // 只认新按下的右键：一直按住不算确认（否则按住不放就能让警惕装置与信号确认失效）。
      long nowTick = Bukkit.getCurrentTick();
      Long previous = lastUseTick.put(id, nowTick);
      if (previous == null || nowTick - previous > USE_HOLD_GAP_TICKS) {
        freshUse.put(id, Boolean.TRUE);
      }
      if (ackPending.putIfAbsent(id, Boolean.TRUE) == null) {
        Bukkit.getScheduler()
            .runTask(
                plugin,
                () -> {
                  ackPending.remove(id);
                  boolean fresh = freshUse.remove(id) != null;
                  DriveSession session = active.get(id);
                  if (session != null && fresh) {
                    acknowledgeVigilance(session, "右键");
                    if (session.isAto() && session.driverLink().confirmDeparture()) {
                      traceSession(session, "ATO 确认发车");
                      notice(player, "drive.driver.ato.confirmed", Map.of());
                      sounds.play(player, DriveCue.SIGNAL_CONFIRMED);
                    } else if (session.driverLink() != null
                        && !session.isAto()
                        && session
                            .driverLink()
                            .signalConfirm()
                            .acknowledge(Bukkit.getCurrentTick())
                            .isPresent()) {
                      traceSession(session, "确认信号");
                      notice(player, "drive.driver.signal.confirmed", Map.of());
                      sounds.play(player, DriveCue.SIGNAL_CONFIRMED);
                    }
                  }
                });
      }
      return;
    }
    if (refreshPending.putIfAbsent(id, Boolean.TRUE) != null) {
      return;
    }
    Bukkit.getScheduler()
        .runTask(
            plugin,
            () -> {
              refreshPending.remove(id);
              DriveSession session = active.get(id);
              if (session != null && player.isOnline()) {
                refreshInventory(player, session);
              }
            });
  }

  @Override
  public void onSneak(Player player) {
    UUID id = player.getUniqueId();
    Bukkit.getScheduler().runTask(plugin, () -> noteSneak(id));
  }

  @Override
  public void trace(Player player, String message) {
    if (trace) {
      plugin.getLogger().info("[drive-probe] " + player.getName() + ": " + message);
    }
  }

  private void traceSession(DriveSession session, String message) {
    if (trace) {
      plugin
          .getLogger()
          .info(
              "[drive-probe] " + session.playerName() + "@" + session.trainName() + ": " + message);
    }
  }

  // ---- 主线程入口 ----

  /** 让玩家开始驾驶他当前所坐的列车。 */
  public StartOutcome startSession(Player player) {
    DriveConfig current = config;
    if (!current.enabled()) {
      return StartOutcome.DISABLED;
    }
    if (!packetsReady) {
      return StartOutcome.UNAVAILABLE;
    }
    if (!isGameModeAllowed(player.getGameMode())) {
      return StartOutcome.BAD_GAME_MODE;
    }
    if (active.containsKey(player.getUniqueId())) {
      return StartOutcome.ALREADY_DRIVING;
    }
    // 光标上有物品、或开着容器、合成格里有东西时，关闭界面会把它们塞回背包，违反“对背包只读”：拒绝开始。
    if (!player.getItemOnCursor().getType().isAir()) {
      return StartOutcome.CURSOR_NOT_EMPTY;
    }
    if (isInventoryBusy(player)) {
      return StartOutcome.INVENTORY_BUSY;
    }
    Optional<SeatBinding> located = SeatLocator.locate(player);
    if (located.isEmpty()) {
      return StartOutcome.NOT_SEATED;
    }
    SeatBinding binding = located.get();
    Optional<MinecartGroup> groupOpt = SeatLocator.findGroup(binding.trainName());
    if (groupOpt.isEmpty()) {
      return StartOutcome.NOT_SEATED;
    }
    MinecartGroup group = groupOpt.get();
    boolean dispatchTrain = ManagedTrains.isFtaManaged(group.getProperties());
    if (trainInUse(binding.trainName())) {
      return StartOutcome.TRAIN_IN_USE;
    }
    if (dispatchTrain) {
      StartOutcome refusal = checkDispatchTakeover(player, binding, group, current);
      if (refusal != null) {
        return refusal;
      }
    }
    double rollingBps = measureSpeedBps(group);
    boolean rolling = rollingBps > current.startMaxSpeedBps();
    DriveParams params;
    DriveSession session;
    try {
      params = resolveParams(group, current);
      session =
          new DriveSession(
              player.getUniqueId(),
              player.getName(),
              binding,
              params,
              current,
              HotbarItems.build(plugin.getLocaleManager()),
              dispatchTrain ? handoverSetup(group, current) : loadSetup(group, current),
              dispatchTrain
                  ? handoverCab(group, current, params)
                  : loadCab(group, current, params));
    } catch (RuntimeException ex) {
      plugin.getLogger().warning("开始驾驶失败: " + ex);
      return StartOutcome.FAILED;
    }
    // 全服关了自然减速，没人管的车被推一下就会一直溜。顺着驾驶员前进方向溜（列车总是朝车头方向走）就沿用当前车速直接接管，
    // 由驾驶员自己刹停；朝驾驶室后方溜时要先调头才能前进，而调头必须停稳，只能拒绝。
    if (rolling && !session.travelsTowardHead(group.size())) {
      return StartOutcome.TRAIN_MOVING;
    }
    // 拒绝条件都已查过、还没动列车：此时问外部插件；之后万一失败补发结束事件，开始与结束总是成对。
    Optional<DriveApi.TaskView> startTask =
        tasks.claimFor(player.getUniqueId(), binding.trainName()).map(TaskViews::of);
    DriveSessionStartEvent startEvent =
        new DriveSessionStartEvent(
            player.getUniqueId(), binding.trainName(), dispatchTrain, startTask);
    callEvent(startEvent);
    if (startEvent.isCancelled()) {
      return StartOutcome.CANCELLED;
    }
    DriverLink driverLink = null;
    if (dispatchTrain) {
      driverLink =
          new DriverLink(
              player.getUniqueId(),
              group.getProperties().getTrainName(),
              group.getProperties(),
              session::odometerBlocks,
              Bukkit::getCurrentTick);
      driverLink.setStopWindow(config.driver().stopWindow());
      driverLink.setMode(
          tasks
              .claimFor(player.getUniqueId(), group.getProperties().getTrainName())
              .map(DriverTask::mode)
              .orElse(DrivingMode.MANUAL));
      session.attachDriverLink(driverLink);
    }
    try {
      // 上车后的初始状态：换向手柄前进、档位惰行、车门视为全关；停着的车清掉残余速度，溜着的车沿用当前车速。
      if (rolling) {
        session.resetSpeed(rollingBps);
      } else {
        session.resetSpeed(0.0);
        group.stop();
      }
      session.setOriginalHeldSlot(player.getInventory().getHeldItemSlot());
      if (driverLink != null) {
        // 先登记控制权再动列车属性：同一 tick 里调度层的控车命令已经不会再写限速或发车。
        driverRegistry.bind(group.getProperties(), driverLink);
      }
      if (!session.isAto()) {
        // ATO 下列车仍由自动运行操纵：不改列车属性，也不挂控车动作。
        TrainPropertyGuard.apply(group.getProperties(), params.maxSpeedBps());
        session.setGuardedSpeedLimit(group.getProperties().getSpeedLimit());
        attachAction(group, session);
      }
      alignHead(group, session, player, Bukkit.getCurrentTick());
    } catch (RuntimeException ex) {
      plugin.getLogger().warning("开始驾驶失败: " + ex);
      // 控车动作可能已经挂上：结束会话让它自行退出，否则它会一直把列车按在原地、挡住队列里的其它动作。
      session.finish(DriveSession.EndReason.DISABLED);
      driverRegistry.unbind(driverLink);
      SeatLocator.findGroup(binding.trainName())
          .ifPresent(found -> TrainPropertyGuard.restore(found.getProperties()));
      callEvent(
          new DriveSessionEndedEvent(
              player.getUniqueId(),
              binding.trainName(),
              dispatchTrain,
              StartOutcome.FAILED.name(),
              startTask));
      return StartOutcome.FAILED;
    }
    active.put(player.getUniqueId(), session);
    seatPrompts.remove(player.getUniqueId());
    cabChangeFailed.remove(player.getUniqueId());
    if (driverLink != null) {
      onPickupBoarded(session, group.getProperties().getTrainName());
    }
    if (driverLink != null) {
      tasks.onSessionStarted(
          player.getUniqueId(), group.getProperties().getTrainName(), Bukkit.getCurrentTick());
      driverLink
          .score()
          .setDelayAtStart(taskDelayOf(player.getUniqueId(), group.getProperties().getTrainName()));
      tasks
          .activeTaskOf(player.getUniqueId())
          .filter(task -> task.state() == DriverTask.State.DRIVING)
          .ifPresent(
              task -> {
                player.sendMessage(
                    plugin
                        .getLocaleManager()
                        .component(
                            task.alightStopSequence() >= 0
                                ? "drive.task.started-interval"
                                : "drive.task.started",
                            Map.of(
                                "trip",
                                task.key().tripCode(),
                                "station",
                                task.alightStationName(),
                                "mode",
                                plugin
                                    .getLocaleManager()
                                    .text(
                                        "drive.driver.mode."
                                            + task.mode().name().toLowerCase(Locale.ROOT)))));
                callEvent(
                    new DriverTaskStartedEvent(
                        task.playerId(), TaskViews.of(task), group.getProperties().getTrainName()));
              });
    }
    traceSession(
        session,
        (rolling ? String.format(Locale.ROOT, "接管溜行列车 %.1f 格/秒; ", rollingBps) : "")
            + "开始驾驶: 车种参数="
            + session.params()
            + " 驾驶室在车头端="
            + session.cabAtHead(group.size())
            + " 座位="
            + session.binding());
    player.getInventory().setHeldItemSlot(Notch.N.slot());
    refreshInventory(player, session);
    return StartOutcome.STARTED;
  }

  /** 玩家所坐列车当前的实际速度（km/h）；没有坐在列车上时为空。 */
  public Optional<Double> trainSpeedKmh(Player player) {
    return SeatLocator.locate(player)
        .flatMap(binding -> SeatLocator.findGroup(binding.trainName()))
        .map(group -> measureSpeedBps(group) * KMH_PER_BPS);
  }

  /** 编组里最快一节车厢的实际速度（格/秒）。 */
  private static double measureSpeedBps(MinecartGroup group) {
    double fastest = 0.0;
    for (MinecartMember<?> member : group) {
      fastest = Math.max(fastest, member.getRealSpeed());
    }
    return fastest * TICKS_PER_SECOND;
  }

  /**
   * 结束玩家的驾驶会话。
   *
   * @return 玩家原本是否有会话
   */
  public boolean stopSession(UUID playerId, DriveSession.EndReason reason) {
    DriveSession session = active.get(playerId);
    if (session == null) {
      return false;
    }
    leave(session, reason);
    return true;
  }

  /** 玩家下线、死亡、切换到不允许的游戏模式等：立即结束，无需（也无法）重发背包。 */
  public void onPlayerGone(UUID playerId, DriveSession.EndReason reason) {
    DriveSession session = active.get(playerId);
    if (session != null) {
      leave(session, reason);
    }
  }

  /** 玩家按了潜行：记下时刻，离座时据此判断是不是主动离座。 */
  public void noteSneak(UUID playerId) {
    DriveSession session = active.get(playerId);
    if (session != null) {
      session.noteSneak(Bukkit.getCurrentTick());
    }
  }

  /**
   * 玩家在快捷栏上选了另一个槽位。
   *
   * @return 选择后应当显示的槽位；玩家没有会话时返回 -1
   */
  public int onHeldSlot(Player player, int newSlot) {
    DriveSession session = active.get(player.getUniqueId());
    if (session == null) {
      return -1;
    }
    if (session.isAto()) {
      // ATO 下手柄不起作用；拉到 EB 立即转人工驾驶并紧急制动。
      acknowledgeVigilance(session, "换档");
      if (newSlot == Notch.EB.slot()) {
        findSessionGroup(session).ifPresent(group -> switchToManual(session, group, true));
        return Notch.EB.slot();
      }
      notice(player, "drive.driver.ato.notch-ignored", Map.of());
      return Notch.N.slot();
    }
    // 换档就是一次操作：警惕装置重新计时。
    acknowledgeVigilance(session, "换档");
    return session.selector().select(newSlot, session.isStopped()).correctedSlot();
  }

  /** 驾驶员有操作：警惕装置重新计时；正在报警时记一条诊断日志，便于确认确认键在骑乘时生效。 */
  private void acknowledgeVigilance(DriveSession session, String source) {
    long now = Bukkit.getCurrentTick();
    if (session.cab().enabled() && session.cab().vigilance().warning(now)) {
      traceSession(session, "警惕确认: " + source);
    }
    session.acknowledgeVigilance(now);
  }

  /** 玩家换了世界或重生之后，下一 tick 重发快捷栏。 */
  public void refreshLater(Player player) {
    Bukkit.getScheduler()
        .runTask(
            plugin,
            () -> {
              DriveSession session = active.get(player.getUniqueId());
              if (session != null && player.isOnline()) {
                refreshInventory(player, session);
              }
            });
  }

  /** Boss 栏的行车引导与提示音：每次刷新 HUD 时算一次。 */
  private void updateGuidance(Player player, DriveSession session, DriveConfig current, long now) {
    DriverGuidanceConfig guidance = current.driver().guidance();
    Optional<DriverGuidance.Advice> advice = session.updateGuidance(guidance);
    if (advice.isPresent() && guidance.bossBar()) {
      bossBar.refresh(player, plugin.getLocaleManager(), session, advice.get(), guidance);
    } else {
      bossBar.hide(player.getUniqueId());
    }
    sounds.playAll(
        player,
        cueTrackers
            .computeIfAbsent(player.getUniqueId(), id -> new DriveCueTracker())
            .observe(session.cueSnapshot(advice.orElse(null)), now));
  }

  /** 撤掉驾驶员的 Boss 栏，丢掉提示音与鸣笛的状态。 */
  private void forgetHud(UUID playerId) {
    bossBar.hide(playerId);
    cueTrackers.remove(playerId);
    lastHornTick.remove(playerId);
  }

  /** Space 鸣笛：在驾驶员所在位置播放，附近的玩家都听得到；也算一次警惕确认。 */
  public void onHorn(Player player) {
    DriveSession session = active.get(player.getUniqueId());
    if (session == null || session.phase() != DriveSession.Phase.ACTIVE) {
      return;
    }
    long now = Bukkit.getCurrentTick();
    Long last = lastHornTick.get(player.getUniqueId());
    if (last != null && now - last < sounds.config().hornCooldownTicks()) {
      return;
    }
    if (sounds.broadcast(player.getLocation(), DriveCue.HORN)) {
      lastHornTick.put(player.getUniqueId(), now);
      acknowledgeVigilance(session, "鸣笛");
      traceSession(session, "鸣笛");
    }
  }

  // ---- 停车后菜单 ----

  /** 按 F 键：停稳且不在牵引档时打开停车后菜单。 */
  private void openMenu(Player player) {
    DriveSession session = active.get(player.getUniqueId());
    if (session == null || !player.isOnline() || session.menuTopSize() > 0) {
      return;
    }
    if (!session.isStopped()) {
      denyMenu(player, "drive.menu.deny.moving");
      return;
    }
    if (session.selector().current().isTraction()) {
      denyMenu(player, "drive.menu.deny.traction");
      return;
    }
    if (!menu.open(player, session)) {
      denyMenu(player, "drive.menu.deny.unavailable");
      return;
    }
    traceSession(session, "打开停车后菜单");
  }

  private void denyMenu(Player player, String key) {
    notice(player, key, Map.of());
  }

  /** 在动作栏给驾驶员一条提示，并让驾驶 HUD 暂停覆盖动作栏一小段时间，否则提示在下一次 HUD 刷新时就被盖掉，玩家看不到。 */
  private void notice(Player player, String key, Map<String, String> values) {
    player.sendActionBar(plugin.getLocaleManager().component(key, values));
    DriveSession session = active.get(player.getUniqueId());
    if (session != null) {
      session.holdActionBar(Bukkit.getCurrentTick() + NOTICE_HOLD_TICKS);
    }
  }

  /** 玩家点了菜单上半部分的一个槽位。由事件监听在取消点击之后调用。 */
  public void onMenuClick(Player player, int slot) {
    DriveSession session = active.get(player.getUniqueId());
    if (session == null) {
      return;
    }
    Optional<MenuAction> actionOpt = MenuLayout.actionAt(slot);
    if (actionOpt.isEmpty()) {
      return;
    }
    if (!session.isStopped()) {
      denyMenu(player, "drive.menu.deny.moving");
      closeMenuIfOpen(player);
      return;
    }
    MenuAction action = actionOpt.get();
    switch (action) {
      case REVERSER_FORWARD -> setReverser(player, session, ReverserPosition.FORWARD);
      case REVERSER_NEUTRAL -> setReverser(player, session, ReverserPosition.NEUTRAL);
      case REVERSER_REVERSE -> setReverser(player, session, ReverserPosition.REVERSE);
      case DOOR_LEFT -> toggleDoor(player, session, true);
      case DOOR_RIGHT -> toggleDoor(player, session, false);
      case KEY, POWER, BREAKER, AUX -> action
          .system()
          .ifPresent(system -> toggleSystem(player, session, system));
      case START -> pressStart(player, session);
      case COMPRESSOR -> toggleCompressor(player, session);
      case PARKING_BRAKE -> toggleParkingBrake(player, session);
      case BRAKE_TEST -> startBrakeTest(player, session);
      case DRIVING_MODE -> toggleDrivingMode(player, session);
      case END_DRIVING -> {
        if (pressEnd(player, session)) {
          return;
        }
      }
      case TASK_CARD -> {}
    }
    Inventory top = player.getOpenInventory().getTopInventory();
    if (DriveMenu.isMenu(top)) {
      menu.render(top, session);
    }
  }

  /** 驾驶台上切换人工驾驶与 ATO（只在调度列车）。 */
  private void toggleDrivingMode(Player player, DriveSession session) {
    if (!session.isDispatchDriving()) {
      return;
    }
    String key = setDrivingMode(player, session.isAto() ? DrivingMode.MANUAL : DrivingMode.ATO);
    player.sendMessage(plugin.getLocaleManager().component(key));
  }

  /**
   * 驾驶台的“结束驾驶”：第一次点击只进入待确认，{@value #END_CONFIRM_TICKS} tick 内再点才结束。调度列车按放弃任务处理， 交还自动运行；没有领任务的直接交还。
   *
   * @return 是否已经结束驾驶（菜单已关闭）
   */
  private boolean pressEnd(Player player, DriveSession session) {
    long now = Bukkit.getCurrentTick();
    if (!session.endArmed(now)) {
      session.armEnd(now + END_CONFIRM_TICKS);
      return false;
    }
    session.armEnd(Long.MIN_VALUE);
    closeMenuIfOpen(player);
    traceSession(session, "驾驶台结束驾驶");
    if (session.isDispatchDriving()) {
      if (!abandonTask(player.getUniqueId(), "menu")) {
        requestHandback(session, "menu");
      }
    } else {
      stopSession(player.getUniqueId(), DriveSession.EndReason.COMMAND);
    }
    return true;
  }

  /** 任务卡用的驾驶任务摘要。 */
  private Optional<TaskCard.TaskSummary> taskSummary(UUID playerId) {
    return tasks
        .activeTaskOf(playerId)
        .map(
            task ->
                new TaskCard.TaskSummary(
                    task.routeCode(),
                    task.key().tripCode(),
                    task.alightStopSequence() >= 0 ? task.alightStationName() : ""));
  }

  private void setReverser(Player player, DriveSession session, ReverserPosition position) {
    if (session.reverser() == position) {
      return;
    }
    if (session.isDispatchDriving()) {
      // 调度列车的方向由交路决定，调头只能由自动运行完成。
      denyMenu(player, "drive.menu.deny.reverser-locked");
      return;
    }
    if (session.anyDoorOpen()) {
      denyMenu(player, "drive.menu.deny.doors-open");
      return;
    }
    session.setReverser(position);
    traceSession(session, "换向手柄: " + position);
  }

  private void toggleDoor(Player player, DriveSession session, boolean left) {
    Optional<MinecartGroup> group = findSessionGroup(session);
    if (group.isEmpty()) {
      denyMenu(player, "drive.menu.deny.unavailable");
      return;
    }
    boolean opening = left ? !session.isLeftDoorOpen() : !session.isRightDoorOpen();
    if (opening && !doorsReleased(session)) {
      denyMenu(player, "drive.menu.deny.doors-not-released");
      return;
    }
    var settings = plugin.getConfigManager().current().autoStationSettings();
    DriveDoors.Result result =
        doors
            .computeIfAbsent(session.playerId(), id -> new DriveDoors())
            .toggle(group.get(), session, left, settings);
    traceSession(
        session,
        (left ? "左" : "右")
            + "车门: "
            + result
            + " ("
            + doors.get(session.playerId()).lastSummary()
            + ")");
    if (result == DriveDoors.Result.UNAVAILABLE) {
      denyMenu(player, "drive.menu.deny.no-door-animation");
    }
  }

  // ---- 启动流程 ----

  /** 按列车标签建立启动流程状态：受电方式取 {@code FTA_TRAIN_POWER}，已接通的系统取列车上保存的记录（超过冷车时限的不算）。 */
  private TrainSetup loadSetup(MinecartGroup group, DriveConfig current) {
    var properties = group.getProperties();
    PowerSupply supply =
        TrainTagHelper.readTagValue(properties, TrainConfigResolver.TAG_TRAIN_POWER)
            .flatMap(PowerSupply::parse)
            .orElse(current.defaultPower());
    TrainSetup setup = new TrainSetup(supply, current.setupTimings());
    setup.restore(
        TrainSetupStore.load(
            properties,
            System.currentTimeMillis(),
            current.coldAfterMinutes() * MILLIS_PER_MINUTE));
    return setup;
  }

  /** simulation 级：拨动一个系统开关。 */
  private void toggleSystem(Player player, DriveSession session, SetupSystem system) {
    if (session.setupMode() != SimulationLevel.SetupMode.MANUAL) {
      denyMenu(player, "drive.menu.deny.use-start-button");
      return;
    }
    TrainSetup setup = session.setup();
    TrainSetup.State powerBefore = setup.state(SetupSystem.POWER);
    TrainSetup.ToggleResult result = setup.toggle(system, Bukkit.getCurrentTick());
    switch (result) {
      case NEEDS_PREREQUISITE -> denyMenu(player, "drive.menu.deny.setup-prerequisite");
      case INTERLOCKED -> denyMenu(player, "drive.menu.deny.setup-interlocked");
      case BUSY -> denyMenu(player, "drive.menu.deny.setup-busy");
      default -> {}
    }
    traceSession(session, "启动流程 " + system + ": " + result);
    onSetupChanged(session, powerBefore);
  }

  /** standard 级：启动按钮。未启动时一键启动，已启动时一键关机。 */
  private void pressStart(Player player, DriveSession session) {
    if (session.setupMode() != SimulationLevel.SetupMode.ONE_CLICK) {
      return;
    }
    TrainSetup setup = session.setup();
    if (setup.busy()) {
      denyMenu(player, "drive.menu.deny.setup-busy");
      return;
    }
    TrainSetup.State powerBefore = setup.state(SetupSystem.POWER);
    if (setup.ready()) {
      setup.shutdown();
      traceSession(session, "一键关机");
    } else {
      setup.startAll(Bukkit.getCurrentTick());
      traceSession(session, "一键启动");
    }
    onSetupChanged(session, powerBefore);
  }

  /** 启动流程状态变了：受电开始接通就升弓、断开就降弓，并把列车已接通的系统写回标签。 */
  private void onSetupChanged(DriveSession session, TrainSetup.State powerBefore) {
    Optional<MinecartGroup> group = findSessionGroup(session);
    if (group.isEmpty()) {
      return;
    }
    TrainSetup setup = session.setup();
    TrainSetup.State powerAfter = setup.state(SetupSystem.POWER);
    boolean raised = powerBefore == TrainSetup.State.OFF && powerAfter != TrainSetup.State.OFF;
    boolean lowered = powerBefore != TrainSetup.State.OFF && powerAfter == TrainSetup.State.OFF;
    if (raised || lowered) {
      boolean played = SetupAnimations.playPower(group.get(), setup.supply(), raised);
      traceSession(session, (raised ? "升弓" : "降弓") + " 动画=" + played);
    }
    saveTrainState(session, group.get());
  }

  /** 把列车已接通的系统与（simulation 级的）气压写回标签。 */
  private static void saveTrainState(DriveSession session, MinecartGroup group) {
    if (session.isDispatchDriving()) {
      // 调度列车的启动状态由热车交接给出，不写进列车标签。
      return;
    }
    long now = System.currentTimeMillis();
    var properties = group.getProperties();
    TrainSetupStore.save(properties, session.setup().persistentOn(), now);
    CabSystems cab = session.cab();
    if (cab.enabled()) {
      TrainSetupStore.saveAir(
          properties,
          new TrainSetupStore.AirSnapshot(
              cab.air().mainReservoirKpa(), cab.air().compressorSwitch()),
          now);
    }
  }

  /** simulation 级的车上系统：主风缸压力取列车上保存的值（扣除漏泄），停放制动总是施加着，制动试验要重做。机车牵引的压缩机要手动打开。 standard 级没有这些约束。 */
  private CabSystems loadCab(MinecartGroup group, DriveConfig current, DriveParams params) {
    if (current.level() != SimulationLevel.SIMULATION) {
      return CabSystems.disabled();
    }
    TrainSetupStore.AirSnapshot air =
        TrainSetupStore.loadAir(
            group.getProperties(), System.currentTimeMillis(), current.cab().leakKpaPerMinute());
    return CabSystems.simulation(
        current.cab(),
        params.mode() == DriveMode.LOCO,
        air.mainReservoirKpa(),
        air.compressorSwitch(),
        Bukkit.getCurrentTick());
  }

  /** simulation 级：机车的压缩机开关；动车组的压缩机自动运转。 */
  private void toggleCompressor(Player player, DriveSession session) {
    CabSystems cab = session.cab();
    if (!cab.enabled()) {
      return;
    }
    if (!cab.air().manualCompressor()) {
      denyMenu(player, "drive.menu.deny.compressor-auto");
      return;
    }
    traceSession(session, "压缩机开关: " + (cab.air().toggleCompressor() ? "开" : "关"));
  }

  /** simulation 级：缓解或施加停放制动。 */
  private void toggleParkingBrake(Player player, DriveSession session) {
    CabSystems cab = session.cab();
    if (!cab.enabled()) {
      return;
    }
    AirSystem.ParkingResult result = cab.air().toggleParking();
    if (result == AirSystem.ParkingResult.NOT_ENOUGH_AIR) {
      denyMenu(player, "drive.menu.deny.parking-air");
    }
    traceSession(session, "停放制动: " + result);
  }

  /** simulation 级：开始制动试验。须先启动列车（压缩机由辅助电源供电）。 */
  private void startBrakeTest(Player player, DriveSession session) {
    CabSystems cab = session.cab();
    if (!cab.enabled()) {
      return;
    }
    if (!session.setup().ready()) {
      denyMenu(player, "drive.menu.deny.start-first");
      return;
    }
    if (!cab.brakeTest().passed()
        && cab.air().mainReservoirKpa() < cab.config().brakeTestApplyKpa()) {
      // 制动缸压力不会超过主风缸，主风缸不够时试验永远升不到试验压力。
      notice(
          player,
          "drive.menu.deny.brake-test-air",
          Map.of(
              "need", String.valueOf(Math.round(cab.config().brakeTestApplyKpa())),
              "mr", String.valueOf(Math.round(cab.air().mainReservoirKpa()))));
      return;
    }
    if (cab.brakeTest().start()) {
      denyMenu(player, "drive.menu.notice.brake-test-started");
      traceSession(session, "开始制动试验");
    }
  }

  /** simulation 级车上系统的事件：制动试验进度（通过时提示）、失风自动施加停放制动（提示），警惕装置报警时每秒响一次，超时施加紧急制动时把手柄拨到 EB。 */
  private void handleCabEvents(Player player, DriveSession session) {
    CabSystems cab = session.cab();
    cab.takeBrakeTestChange()
        .ifPresent(
            stage -> {
              traceSession(session, "制动试验: " + stage);
              if (stage == BrakeTest.Stage.PASSED) {
                notice(player, "drive.menu.notice.brake-test-passed", Map.of());
              }
            });
    if (cab.air().takeParkingAutoApplied()) {
      traceSession(session, "主风缸 " + Math.round(cab.air().mainReservoirKpa()) + " kPa，停放制动自动施加");
      notice(player, "drive.menu.notice.parking-auto-applied", Map.of());
    }
    Vigilance.Event event = session.takeVigilanceEvent();
    if (event == Vigilance.Event.WARNING_STARTED) {
      traceSession(session, "警惕装置开始报警");
    }
    switch (event) {
      case WARNING_STARTED, WARNING_SECOND -> sounds.play(player, DriveCue.VIGILANCE_WARNING);
      case TRIPPED -> {
        player.getInventory().setHeldItemSlot(Notch.EB.slot());
        sounds.play(player, DriveCue.VIGILANCE_TRIPPED);
        player.sendMessage(plugin.getLocaleManager().component("drive.cab.vigilance-tripped"));
        traceSession(session, "警惕装置超时，施加紧急制动");
      }
      default -> {}
    }
  }

  /** 驾驶员离开驾驶室：钥匙随人离开，正在进行的接通作废；列车已接通的系统与气压保留在标签里。 */
  private void releaseCab(DriveSession session) {
    TrainSetup.State powerBefore = session.setup().state(SetupSystem.POWER);
    session.setup().cabDeactivated();
    onSetupChanged(session, powerBefore);
  }

  /** 玩家关闭了菜单：不再改写菜单窗口里的快捷栏，并重发背包把客户端画面恢复到驾驶物品。 */
  public void onMenuClosed(Player player) {
    DriveSession session = active.get(player.getUniqueId());
    if (session == null) {
      return;
    }
    session.setMenuTopSize(0);
    refreshLater(player);
  }

  /** 关闭玩家打开着的驾驶台菜单。菜单是虚拟界面，关闭不会改动任何真实物品。 */
  private void closeMenuIfOpen(Player player) {
    if (DriveMenu.isMenu(player.getOpenInventory().getTopInventory())) {
      player.closeInventory();
    }
  }

  /** 会话结束时关上还开着的车门。 */
  private void closeDoors(DriveSession session) {
    DriveDoors held = doors.remove(session.playerId());
    if (held != null) {
      held.closeAll(session);
    }
  }

  // ---- 内部 ----

  /** 玩家开着容器，或自己的合成格里有物品。 */
  private static boolean isInventoryBusy(Player player) {
    Inventory top = player.getOpenInventory().getTopInventory();
    if (top.getType() != InventoryType.CRAFTING) {
      return true;
    }
    for (int slot = 0; slot < top.getSize(); slot++) {
      ItemStack item = top.getItem(slot);
      if (item != null && !item.getType().isAir()) {
        return true;
      }
    }
    return false;
  }

  /**
   * 停稳时让车头指向驾驶员想去的方向。
   *
   * <p>TrainCarts 的前进力只会沿车头方向推，所以后端驾驶室开车、或换向手柄拨到后退，都靠把整列调头来实现，而不是传负的力。 调头后车厢序号翻转，座位绑定随即刷新。
   */
  private void alignHead(MinecartGroup group, DriveSession session, Player player, long now) {
    if (session.isDispatchDriving()) {
      return;
    }
    int members = group.size();
    if (session.travelsTowardHead(members) || !session.mayReverse(now) || group.isMoving()) {
      return;
    }
    group.reverse();
    session.noteReversedByUs(members, now);
    SeatLocator.locate(player)
        .filter(found -> found.trainName().equals(session.trainName()))
        .ifPresent(session::rebind);
    traceSession(
        session, "调头: 驾驶室在车头端=" + session.cabAtHead(members) + " 换向=" + session.reverser());
  }

  /** 把玩家的选中槽位还原到驾驶之前的位置。这是玩家的选择状态，不是物品。 */
  private static void restoreHeldSlot(Player player, DriveSession session) {
    int slot = session.originalHeldSlot();
    if (slot >= 0 && slot < Notch.SLOT_COUNT) {
      player.getInventory().setHeldItemSlot(slot);
    }
  }

  private boolean trainInUse(String trainName) {
    for (DriveSession session : active.values()) {
      if (session.trainName().equals(trainName)) {
        return true;
      }
    }
    for (DriveSession session : stopping) {
      if (session.trainName().equals(trainName)) {
        return true;
      }
    }
    return false;
  }

  /** 该游戏模式下能否驾驶：生存与冒险始终可以，创造看配置，旁观不行。 */
  public boolean isGameModeAllowed(GameMode mode) {
    return mode == GameMode.SURVIVAL
        || mode == GameMode.ADVENTURE
        || (mode == GameMode.CREATIVE && config.allowCreativeMode());
  }

  private DriveParams resolveParams(MinecartGroup group, DriveConfig current) {
    return DriveParamsResolver.resolve(
        group.getProperties(), group.size(), plugin.getConfigManager().current(), current);
  }

  /**
   * 清空编组的动作队列并挂上控车动作。
   *
   * <p>队首若是别的动作（牌子排的等待、发车），我们的动作永远轮不到执行；驾驶员接管后由他决定怎么走，所以先清队列。 清队列同时保证重复挂载不会让队列无限增长。
   */
  private void attachAction(MinecartGroup group, DriveSession session) {
    group.getActions().clear();
    group.getActions().addAction(new ManualDriveAction(session));
    session.touch(Bukkit.getCurrentTick());
  }

  /** 驾驶员离开：转入制动停车；列车已经停稳或无法控车时直接结束。 */
  private void leave(DriveSession session, DriveSession.EndReason reason) {
    traceSession(session, "驾驶结束: " + reason);
    closeDoors(session);
    releaseCab(session);
    stopMarker.remove(session.playerId());
    forgetHud(session.playerId());
    if (active.remove(session.playerId(), session)) {
      Player player = Bukkit.getPlayer(session.playerId());
      if (player != null && player.isOnline()) {
        closeMenuIfOpen(player);
        player.updateInventory();
        restoreHeldSlot(player, session);
        player.sendActionBar(Component.empty());
        sidebar.hide(player);
        notifyEnd(player, reason);
      } else {
        sidebar.forget(session.playerId());
      }
    }
    // ATO 下列车由自动运行操纵，没有控车动作替驾驶员制动停车：直接交还。
    if (session.isStopped() || session.isAto() || !session.beginStopping(reason)) {
      endNow(session, reason);
      return;
    }
    stopping.add(session);
  }

  /** 告诉玩家驾驶为什么结束；玩家主动结束、下线、死亡等不需要提示。 */
  private void notifyEnd(Player player, DriveSession.EndReason reason) {
    switch (reason) {
      case LEFT_SEAT,
          SEAT_LOST,
          TRAIN_GONE,
          GAME_MODE,
          ADMIN,
          HANDBACK,
          DISPATCH_ABORT,
          TASK_COMPLETE,
          WATCHDOG -> player.sendMessage(
          plugin
              .getLocaleManager()
              .component("drive.end." + reason.name().toLowerCase(Locale.ROOT).replace('_', '-')));
      default -> {}
    }
  }

  /** 立即结束会话并还原列车属性。 */
  private void endNow(DriveSession session, DriveSession.EndReason reason) {
    session.finish(reason);
    closeDoors(session);
    releaseCab(session);
    stopMarker.remove(session.playerId());
    forgetHud(session.playerId());
    if (active.remove(session.playerId(), session)) {
      Player player = Bukkit.getPlayer(session.playerId());
      if (player != null && player.isOnline()) {
        closeMenuIfOpen(player);
        player.updateInventory();
        restoreHeldSlot(player, session);
        sidebar.hide(player);
      } else {
        sidebar.forget(session.playerId());
      }
    }
    stopping.remove(session);
    DriverLink link = session.driverLink();
    Optional<DriveApi.TaskScore> score = Optional.empty();
    if (link != null) {
      // 交还自动运行：解除控制权，下一 tick 让调度层从当前状态重新控车。
      driverRegistry.unbind(link);
      traceSession(session, "交还自动运行: " + reason);
      tasks.onSessionEnded(session.playerId(), taskStateFor(reason), reason.name());
      score = recordTask(session, link);
      // 停在站内结束时，最后一站在评分时才记下。
      announceStops(session, link, session.trainName(), tasks.taskOf(session.playerId()));
    }
    // 先定下结束的是哪一趟：结束事件的监听器可能当场给玩家派下一班。
    Optional<DriveApi.TaskView> endedView =
        tasks
            .taskOf(session.playerId())
            .filter(task -> session.trainName().equalsIgnoreCase(task.trainName()))
            .map(TaskViews::of);
    if (link != null) {
      Optional<DriveApi.TaskScore> finalScore = score;
      tasks
          .taskOf(session.playerId())
          .filter(task -> task.startedAt() != null && task.state().finished())
          .filter(DriverTask::announceFinish)
          .ifPresent(
              task ->
                  callEvent(
                      new DriverTaskFinishedEvent(
                          task.playerId(), TaskViews.of(task), finalScore)));
    }
    callEvent(
        new DriveSessionEndedEvent(
            session.playerId(), session.trainName(), link != null, reason.name(), endedView));
    Optional<MinecartGroup> groupOpt = findSessionGroup(session);
    groupOpt.ifPresent(
        group ->
            TrainPropertyGuard.restore(group.getProperties(), session.observedSpeedLimitBpt()));
    // 插件停用时不能再排任务：重启后列车标签会被清掉，自动运行从头接手。
    if (link != null && groupOpt.isPresent() && plugin.isEnabled()) {
      MinecartGroup group = groupOpt.get();
      Bukkit.getScheduler()
          .runTask(
              plugin,
              () -> {
                if (group.isValid()) {
                  plugin.getRuntimeDispatchService().ifPresent(d -> d.refreshSignal(group));
                }
              });
    }
  }

  // ---- 驾驶调度列车 ----

  /**
   * 能否接管这列调度列车：驾驶功能开着、有任务或权限、车停在车站里、坐在车头驾驶室。
   *
   * @return 不能接管时的原因；可以接管时为 {@code null}
   */
  private StartOutcome checkDispatchTakeover(
      Player player, SeatBinding binding, MinecartGroup group, DriveConfig current) {
    if (!current.driver().enabled()) {
      return StartOutcome.MANAGED_TRAIN;
    }
    if (!player.hasPermission(PERMISSION_DRIVER_ADMIN)) {
      if (tasks.breaker().open(Instant.now())) {
        return StartOutcome.BREAKER_OPEN;
      }
      if (!player.hasPermission(PERMISSION_DRIVER)
          || tasks.claimFor(player.getUniqueId(), group.getProperties().getTrainName()).isEmpty()) {
        return StartOutcome.NO_TASK;
      }
    }
    if (measureSpeedBps(group) > current.startMaxSpeedBps()) {
      return StartOutcome.NOT_STOPPED_AT_STATION;
    }
    String name = group.getProperties().getTrainName();
    boolean dwelling =
        plugin.getDwellRegistry().map(r -> r.remainingSeconds(name).isPresent()).orElse(false)
            || plugin.getRuntimeDispatchService().map(d -> d.hasDepartureGate(name)).orElse(false);
    // 始发站、车库接车：列车停着等这名驾驶员，不在停站中也可以接班。
    Optional<DriverPickups.Pickup> pickup =
        pickups
            .ofTrain(name)
            .filter(
                waiting ->
                    waiting.playerId().equals(player.getUniqueId())
                        && waiting.stage() == DriverPickups.Stage.WAITING);
    if (!dwelling && pickup.isEmpty()) {
      return StartOutcome.NOT_STOPPED_AT_STATION;
    }
    // 终点站折返：发车方向要到派车时才定，后端车厢也可以坐，发车时按需换端。
    boolean eitherEnd =
        pickup.filter(waiting -> waiting.kind() == DriverPickups.Kind.TERMINAL).isPresent();
    if (binding.cabSign(group.size()) < 0
        && !(eitherEnd && binding.memberIndex() == group.size() - 1)) {
      return StartOutcome.NOT_HEAD_CAB;
    }
    return null;
  }

  /** 接管调度列车时的启动状态：热车交接时受电、主断、辅助电源都已接通，只差钥匙。 */
  private TrainSetup handoverSetup(MinecartGroup group, DriveConfig current) {
    if (!current.driver().hotHandover()) {
      return loadSetup(group, current);
    }
    PowerSupply supply =
        TrainTagHelper.readTagValue(group.getProperties(), TrainConfigResolver.TAG_TRAIN_POWER)
            .flatMap(PowerSupply::parse)
            .orElse(current.defaultPower());
    TrainSetup setup = new TrainSetup(supply, current.setupTimings());
    setup.restore(EnumSet.of(SetupSystem.POWER, SetupSystem.BREAKER, SetupSystem.AUX));
    return setup;
  }

  /** 接管调度列车时的车上系统：热车交接时满风、停放已缓解、制动试验视为已做。 */
  private CabSystems handoverCab(MinecartGroup group, DriveConfig current, DriveParams params) {
    if (current.level() != SimulationLevel.SIMULATION) {
      return CabSystems.disabled();
    }
    if (!current.driver().hotHandover()) {
      return loadCab(group, current, params);
    }
    return CabSystems.hotHandover(
        current.cab(), params.mode() == DriveMode.LOCO, Bukkit.getCurrentTick());
  }

  /** 这条控制链路所属的会话（驾驶中或制动停车中）。 */
  private DriveSession sessionOf(DriverLink link) {
    for (DriveSession session : active.values()) {
      if (session.driverLink() == link) {
        return session;
      }
    }
    for (DriveSession session : stopping) {
      if (session.driverLink() == link) {
        return session;
      }
    }
    return null;
  }

  private void onDriverInterrupt(DriverLink link, DriverInterrupt interrupt) {
    DriveSession session = sessionOf(link);
    if (session == null) {
      driverRegistry.unbind(link);
      return;
    }
    traceSession(session, "调度要求: " + interrupt);
    switch (interrupt) {
      case SERVICE_STOP -> link.requestServiceStop();
      case EMERGENCY -> {
        link.latchEmergency();
        session.forceEmergency();
      }
      case EMERGENCY_INSTANT -> {
        session.resetSpeed(0.0);
        session.forceEmergency();
        link.countForcedStop();
      }
      case RELEASE_FOR_DESTROY -> handback(session, DriveSession.EndReason.DISPATCH_ABORT);
      case HANDBACK_REQUIRED -> requestHandback(session, "dispatch");
    }
  }

  /** 请求交还自动运行：已停稳立即交还，否则先常用制动停车。 */
  private void requestHandback(DriveSession session, String reason) {
    DriverLink link = session.driverLink();
    if (link == null) {
      return;
    }
    link.requestHandback(reason);
    traceSession(session, "请求交还自动运行: " + reason);
    if (session.isStopped() || session.phase() != DriveSession.Phase.ACTIVE) {
      handback(session, DriveSession.EndReason.HANDBACK);
    }
  }

  /** 交还自动运行并告诉驾驶员。 */
  private void handback(DriveSession session, DriveSession.EndReason reason) {
    if (active.get(session.playerId()) == session) {
      Player player = Bukkit.getPlayer(session.playerId());
      if (player != null && player.isOnline()) {
        notifyEnd(player, reason);
      }
    }
    endNow(session, reason);
  }

  /** 把所有驾驶员控制的列车交还自动运行（总开关关闭、管理员命令）。 */
  public int handbackAll(String reason) {
    int count = 0;
    for (DriveSession session : sessions()) {
      if (session.isDispatchDriving()) {
        requestHandback(session, reason);
        count++;
      }
    }
    return count;
  }

  /**
   * 把某名玩家驾驶的列车交还自动运行。
   *
   * @return 玩家是否在驾驶调度列车
   */
  public boolean handback(UUID playerId, String reason) {
    DriveSession session = active.get(playerId);
    if (session == null || !session.isDispatchDriving()) {
      return false;
    }
    requestHandback(session, reason);
    return true;
  }

  private void tick() {
    tickCounter++;
    long now = Bukkit.getCurrentTick();
    DriveConfig current = config;
    try {
      tickTasks(now);
    } catch (RuntimeException ex) {
      plugin.getLogger().warning("驾驶任务维护失败: " + ex);
    }
    try {
      stopMarker.tick(now);
    } catch (RuntimeException ex) {
      plugin.getLogger().warning("停车标沿轨道采样失败: " + ex);
    }
    for (DriveSession session : new ArrayList<>(active.values())) {
      try {
        tickActive(session, now, current);
      } catch (RuntimeException ex) {
        plugin.getLogger().warning("驾驶会话维护失败，已结束 " + session.playerName() + ": " + ex);
        endNow(session, DriveSession.EndReason.DISABLED);
      }
    }
    for (DriveSession session : new ArrayList<>(stopping)) {
      try {
        tickStopping(session, now, current);
      } catch (RuntimeException ex) {
        plugin.getLogger().warning("制动停车会话维护失败，已结束 " + session.trainName() + ": " + ex);
        endNow(session, DriveSession.EndReason.DISABLED);
      }
    }
  }

  private void tickActive(DriveSession session, long now, DriveConfig current) {
    Player player = Bukkit.getPlayer(session.playerId());
    if (player == null || !player.isOnline()) {
      leave(session, DriveSession.EndReason.OFFLINE);
      return;
    }
    if (!isGameModeAllowed(player.getGameMode())) {
      leave(session, DriveSession.EndReason.GAME_MODE);
      return;
    }
    Optional<MinecartGroup> groupOpt = findSessionGroup(session);
    if (groupOpt.isEmpty()) {
      long missingTicks = session.markGroupMissing(now);
      if (missingTicks == 0) {
        traceSession(session, "找不到编组（可能正在跨世界传送或区块卸载）");
      }
      if (missingTicks > current.reseatTimeoutTicks()) {
        endNow(session, DriveSession.EndReason.TRAIN_GONE);
      }
      return;
    }
    session.markGroupFound();
    MinecartGroup group = groupOpt.get();
    if (session.driverLink() != null) {
      followRename(session, group);
    }
    if (session.isAto()) {
      // ATO 下由自动运行操纵：显示实际车速，按实测车速累计里程。
      double measured = measureSpeedBps(group);
      session.resetSpeed(measured);
      session.addOdometer(measured / 20.0);
    } else {
      ensureAction(group, session, now);
    }
    if (session.driverLink() != null && tickDriverLink(session, group)) {
      return;
    }

    // 以玩家当前所坐的座位为准：编组被调头后车厢序号变了，按旧序号找座位会误判成离座。
    Optional<SeatBinding> seat =
        SeatLocator.locate(player).filter(found -> found.trainName().equals(session.trainName()));
    if (seat.isPresent()) {
      session.rebind(seat.get());
      session.markSeated();
      alignHead(group, session, player, now);
      if (changeCabIfNeeded(session, group, player)) {
        return;
      }
    } else {
      long lostTicks = session.markSeatLost(now);
      boolean sneaked = session.sneakedRecently(now);
      boolean canReseat = SeatLocator.canReseat(player, group);
      if (lostTicks == 0) {
        traceSession(
            session,
            "离开座位: 玩家世界="
                + player.getWorld().getName()
                + " 列车世界="
                + group.getWorld().getName()
                + " 近期潜行="
                + sneaked
                + " 可重新入座="
                + canReseat);
      }
      if (sneaked || !canReseat) {
        leave(session, DriveSession.EndReason.LEFT_SEAT);
        return;
      }
      boolean attempt = lostTicks % RESEAT_RETRY_TICKS == 0;
      boolean reseated = attempt && SeatLocator.reseat(player, group, session.binding());
      if (attempt) {
        traceSession(session, "重新入座 " + (reseated ? "成功" : "失败") + "（已离座 " + lostTicks + " tick）");
      }
      if (reseated) {
        session.markSeated();
        refreshInventory(player, session);
      } else if (lostTicks > current.reseatTimeoutTicks()) {
        leave(session, DriveSession.EndReason.SEAT_LOST);
        return;
      }
    }
    TrainSetup.State powerBefore = session.setup().state(SetupSystem.POWER);
    boolean setupChanged = session.setup().tick(now);
    if (setupChanged) {
      onSetupChanged(session, powerBefore);
    }
    handleCabEvents(player, session);
    boolean liveValues =
        session.setup().busy()
            || session.cab().enabled()
            || session.isDispatchDriving()
            || session.endArmed(now);
    if (session.menuTopSize() > 0 && !session.isStopped()) {
      denyMenu(player, "drive.menu.deny.moving");
      closeMenuIfOpen(player);
    } else if (session.menuTopSize() > 0
        && (setupChanged || (liveValues && tickCounter % MENU_PROGRESS_TICKS == 0))) {
      Inventory top = player.getOpenInventory().getTopInventory();
      if (DriveMenu.isMenu(top)) {
        menu.render(top, session);
      }
    }
    if (tickCounter % HOTBAR_REFRESH_TICKS == 0) {
      refreshInventory(player, session);
    }
    if (tickCounter % current.hudIntervalTicks() == 0) {
      boolean sidebarShown = false;
      if (current.sidebar()) {
        sidebarShown = sidebar.update(player, session);
      } else {
        sidebar.hide(player);
      }
      if (now >= session.actionBarHeldUntil()) {
        player.sendActionBar(DriveHud.render(plugin.getLocaleManager(), session, sidebarShown));
      }
      updateGuidance(player, session, current, now);
      if (current.driver().stopMarker() && session.driverLink() != null) {
        stopMarker.update(
            player,
            session,
            new StopMarker.Train(
                StopAlignment.travel(group),
                StopAlignment.head(group),
                SeatLocator.seatEyePosition(player)
                    .orElseGet(() -> player.getLocation().toVector()),
                StopAlignment.bodyLengthBlocks(group),
                group.size()),
            now);
      } else {
        stopMarker.remove(player.getUniqueId());
      }
    }
  }

  /**
   * 调度列车的控制链路：已请求交还（调度要求、指令长时间不来、管理员命令）且停稳时交还自动运行。
   *
   * @return 会话是否已经结束
   */
  private boolean tickDriverLink(DriveSession session, MinecartGroup group) {
    DriverLink link = session.driverLink();
    if (link.handbackRequested() && (session.isStopped() || session.isAto())) {
      traceSession(session, "停稳，交还原因: " + link.handbackReason());
      handback(session, DriveSession.EndReason.HANDBACK);
      return true;
    }
    String trainName = group.getProperties().getTrainName();
    boolean layover =
        plugin
            .getLayoverRegistry()
            .map(registry -> registry.get(trainName).isPresent())
            .orElse(false);
    if (superviseTask(session, link, group, trainName, layover)) {
      return true;
    }
    // 停在终点站待命：派车放行那一拍按发车方向调头（自动运行由发车动作自己调头）。
    link.setTurnbackPending(layover);
    if (tickCounter % NEXT_STOP_REFRESH_TICKS == 0 && !link.score().hasDelayAtStart()) {
      // 接班时还查不到晚点（车库出车、终点站发车前）：等第一次查得到时再记，回送与等驾驶员的时间不算驾驶员的晚点。
      link.score().setDelayAtStart(taskDelayOf(session.playerId(), trainName));
    }
    if (tickCounter % NEXT_STOP_REFRESH_TICKS == 0) {
      session.setLiveScore(link.liveResult(taskDelayOf(session.playerId(), trainName)));
    }
    plugin
        .getRuntimeDispatchService()
        .flatMap(dispatch -> dispatch.getDiagnostics(trainName))
        .ifPresent(diagnostics -> updateApproach(link, group, diagnostics));
    Optional<DriverStationStop> stop = link.stationStop();
    announceStops(session, link, trainName, tasks.activeTaskOf(session.playerId()));
    link.takeSkippedStation()
        .ifPresent(
            station -> {
              traceSession(session, "越站 " + station);
              Player player = Bukkit.getPlayer(session.playerId());
              if (player != null) {
                notice(player, "drive.hud.station.skipped", Map.of("station", station));
              }
            });
    // 停站刚结束时（上一拍还在停站）立刻刷新，否则发车后会把刚停过的站显示成下一站。
    if (tickCounter % NEXT_STOP_REFRESH_TICKS == 0
        || (stop.isEmpty() && session.lastStationPhase() != null)) {
      refreshNextStop(link, group);
    }
    if (stop.isPresent()) {
      DriverStationStop current = stop.get();
      DriverDoorSide side = DriverDoorSide.required(current, DriveDoors.cabFacing(group, session));
      boolean left = session.isLeftDoorOpen();
      boolean right = session.isRightDoorOpen();
      boolean closing = session.doorsClosing(Bukkit.getCurrentTick());
      // 关门动画放完前仍按车门开着报给站台：动画结束才进入等待发车。
      current.reportDoors(
          side.satisfied(left, right), left || right || closing, side.wrong(left, right));
      link.setDoorsClosing(closing);
      link.setRequiredDoorSide(side);
      link.setTargetLabel(current.stationName());
      if (current.phase() != DriverStationStop.Phase.APPROACH
          && current.stopped()
          && session.markStopReported(current)) {
        reportStop(session, current);
      }
      if (current.phase() != session.lastStationPhase()) {
        traceSession(
            session,
            "停站 "
                + current.stationName()
                + ": "
                + current.phase()
                + String.format(Locale.ROOT, " 偏移 %.2f 格", current.offsetBlocks()));
        session.setLastStationPhase(current.phase());
      }
    } else {
      link.setRequiredDoorSide(DriverDoorSide.NONE);
      link.setDoorsClosing(false);
      // 前方有停车点时按它的站名；还没进入调度的进站范围时按交路进度的下一站。
      link.setTargetLabel(
          link.stationTarget()
              .map(target -> stationLabel(target.node()))
              .orElse(link.nextStopLabel()));
      session.setLastStationPhase(null);
    }
    return false;
  }

  /**
   * 驾驶任务的看护：到终点站完成任务；卡住太久依次告警、转 ATO、交还；超过任务时限交还。
   *
   * @return 会话是否已经结束
   */
  private boolean superviseTask(
      DriveSession session,
      DriverLink link,
      MinecartGroup group,
      String trainName,
      boolean layover) {
    DriveConfig current = config;
    DriverRecovery recovery = current.driver().recovery();
    long now = Bukkit.getCurrentTick();
    Optional<DriverTask> task =
        tasks.activeTaskOf(session.playerId()).filter(t -> t.state() == DriverTask.State.DRIVING);
    if (task.isPresent()
        && tickCounter % TERMINAL_CHECK_TICKS == 0
        && session.isStopped()
        && link.stationStop()
            .map(stop -> stop.phase() != DriverStationStop.Phase.APPROACH)
            .orElse(true)
        && tasks.atTerminal(task.get(), trainName)) {
      traceSession(session, "到达终点站，任务完成");
      tasks.complete(session.playerId());
      handback(session, DriveSession.EndReason.TASK_COMPLETE);
      return true;
    }
    if (task.isPresent()
        && task.get().startedTick() >= 0L
        && now - task.get().startedTick() > recovery.maxTaskMinutes() * 1200L) {
      traceSession(session, "超过任务时限");
      tasks.fail(session.playerId(), "max-task-minutes");
      requestHandback(session, "max-task-minutes");
      return session.phase() == DriveSession.Phase.ENDED;
    }
    // 停在终点站待命等派车是计划内的：既不算卡住，也不算被扣。
    if (!layover) {
      link.tickStuck(heldByDispatch(session, link, trainName));
    }
    if (link.heldSeconds() >= HELD_HANDBACK_SECONDS && !link.handbackRequested()) {
      // 被扣住太久：驾驶员车不参与健康层的恢复，交还自动运行，让恢复手段（重算信号、释放残留占用等）接手。
      traceSession(session, "被扣住 " + link.heldSeconds() + " 秒，交还自动运行");
      requestHandback(session, "held");
      return session.phase() == DriveSession.Phase.ENDED;
    }
    DriverRescueLadder.Stage stage = DriverRescueLadder.stage(link.stuckSeconds(), recovery);
    if (stage.ordinal() <= link.ladderStage().ordinal()) {
      return false;
    }
    link.setLadderStage(stage);
    Player player = Bukkit.getPlayer(session.playerId());
    traceSession(session, "卡住 " + link.stuckSeconds() + " 秒: " + stage);
    switch (stage) {
      case WARN -> {
        if (player != null) {
          player.sendMessage(
              plugin
                  .getLocaleManager()
                  .component(
                      "drive.driver.rescue.warn",
                      Map.of("seconds", String.valueOf(link.stuckSeconds()))));
        }
        return false;
      }
      case ATO -> {
        if (!session.isAto() && session.isStopped()) {
          switchToAto(session, group, "rescue");
          if (player != null) {
            player.sendMessage(plugin.getLocaleManager().component("drive.driver.rescue.ato"));
          }
        }
        return false;
      }
      default -> {
        tasks.fail(session.playerId(), "stuck");
        if (player != null) {
          tasks.watchForRescue(
              player,
              group,
              link.lastStop().orElse(null),
              now + (recovery.rescueSeconds() - recovery.handbackSeconds()) * 20L);
        }
        handback(session, DriveSession.EndReason.WATCHDOG);
        return true;
      }
    }
  }

  /** 此刻是不是表定停站或被调度扣住（不算驾驶员卡住）；开关门、起步是驾驶员的事，照算。 */
  private boolean heldByDispatch(DriveSession session, DriverLink link, String trainName) {
    Optional<DriverStationStop> stop = link.stationStop();
    if (stop.isPresent()) {
      switch (stop.get().phase()) {
        case DWELL, WAIT_DEPARTURE -> {
          return true;
        }
        case OPEN_DOORS, CLOSE_DOORS, DEPART -> {
          return false;
        }
        default -> {}
      }
    }
    if (!session.isStopped()) {
      return false;
    }
    boolean dwell =
        plugin.getDwellRegistry().map(r -> r.remainingSeconds(trainName).isPresent()).orElse(false);
    boolean gate =
        plugin.getRuntimeDispatchService().map(d -> d.hasDepartureGate(trainName)).orElse(false);
    if (dwell || gate) {
      return true;
    }
    if (session.isAto()) {
      return plugin
          .getRuntimeDispatchService()
          .flatMap(d -> d.getDiagnostics(trainName))
          .map(diagnostics -> diagnostics.currentSignal() == SignalAspect.STOP)
          .orElse(true);
    }
    return link.directive() == null || link.directive().isStop();
  }

  /** 转为 ATO：解除控车动作、还原列车属性，下一 tick 由自动运行接着控车。只在停稳时调用。 */
  private void switchToAto(DriveSession session, MinecartGroup group, String reason) {
    DriverLink link = session.driverLink();
    if (link == null || session.isAto()) {
      return;
    }
    closeDoors(session);
    link.enterAto();
    session.releaseAction();
    TrainPropertyGuard.restore(group.getProperties(), session.observedSpeedLimitBpt());
    traceSession(session, "转为 ATO: " + reason);
    refreshSignalLater(group);
  }

  /** 转为人工驾驶：从当前车速接管；{@code emergency} 时立即紧急制动。 */
  private void switchToManual(DriveSession session, MinecartGroup group, boolean emergency) {
    DriverLink link = session.driverLink();
    if (link == null || !session.isAto()) {
      return;
    }
    session.resetSpeed(measureSpeedBps(group));
    link.enterManual();
    // ATO 期间警惕装置没有计时：从现在起重新计时，避免转人工的第一拍就报警。
    session.acknowledgeVigilance(Bukkit.getCurrentTick());
    TrainPropertyGuard.apply(group.getProperties(), session.params().maxSpeedBps());
    session.setGuardedSpeedLimit(group.getProperties().getSpeedLimit());
    attachAction(group, session);
    if (emergency) {
      session.forceEmergency();
    }
    // 请调度层马上给出当前的行车许可。
    refreshSignalLater(group);
    traceSession(session, "转为人工驾驶" + (emergency ? "（紧急制动）" : ""));
  }

  /**
   * 驾驶员切换人工驾驶与 ATO（只在停稳时）。
   *
   * @return 给驾驶员的提示语言键
   */
  public String setDrivingMode(Player player, DrivingMode mode) {
    DriveSession session = active.get(player.getUniqueId());
    if (session == null || !session.isDispatchDriving()) {
      return "drive.command.mode.not-dispatch";
    }
    if (!session.isStopped()) {
      return "drive.command.mode.need-stop";
    }
    Optional<MinecartGroup> group = findSessionGroup(session);
    if (group.isEmpty()) {
      return "drive.command.unavailable";
    }
    if (mode == DrivingMode.ATO) {
      switchToAto(session, group.get(), "driver");
    } else {
      switchToManual(session, group.get(), false);
    }
    tasks.activeTaskOf(player.getUniqueId()).ifPresent(task -> task.setMode(mode));
    return mode == DrivingMode.ATO ? "drive.command.mode.ato" : "drive.command.mode.manual";
  }

  private void refreshSignalLater(MinecartGroup group) {
    if (!plugin.isEnabled()) {
      return;
    }
    Bukkit.getScheduler()
        .runTask(
            plugin,
            () -> {
              if (group.isValid()) {
                plugin.getRuntimeDispatchService().ifPresent(d -> d.refreshSignal(group));
              }
            });
  }

  /** 会话结束原因对应的任务终态。 */
  static DriverTask.State taskStateFor(DriveSession.EndReason reason) {
    return switch (reason) {
      case TASK_COMPLETE -> DriverTask.State.COMPLETED;
      case WATCHDOG -> DriverTask.State.FAILED;
      case COMMAND, LEFT_SEAT, SEAT_LOST, OFFLINE, GAME_MODE, DEATH -> DriverTask.State.ABANDONED;
      default -> DriverTask.State.INTERRUPTED;
    };
  }

  /** 推进驾驶任务：已领取的车次到站时接管，交还后的救援，全网熔断。 */
  private void tickTasks(long now) {
    DriveConfig current = config;
    if (tickCounter % TASK_TICKS == 0) {
      refreshViews();
      tickPickups();
      tasks.tickClaims(this::tryStartTask, this::sendTaskHint, Instant.now());
      tasks.tickRescues(now, this::sendTaskChat);
    }
    if (tickCounter % BREAKER_TICKS == 0 && !driverRegistry.isEmpty()) {
      Set<String> drivers = new HashSet<>();
      for (DriverLink link : driverRegistry.links()) {
        drivers.add(link.currentTrainName());
      }
      boolean tripped = tasks.tickBreaker(drivers, current.driver().recovery(), Instant.now());
      superviseCongestion(current.driver().recovery());
      if (tripped) {
        int count = handbackAll("breaker");
        for (Player online : Bukkit.getOnlinePlayers()) {
          if (online.hasPermission("fetarute.drive.admin")) {
            online.sendMessage(
                plugin
                    .getLocaleManager()
                    .component(
                        "drive.driver.breaker.tripped",
                        Map.of(
                            "count",
                            String.valueOf(count),
                            "reason",
                            tasks.breaker().lastReason())));
          }
        }
      }
    }
  }

  /**
   * 管理员救援的结果。
   *
   * @param found 找到了这列车
   * @param moved 送下车的玩家数
   * @param relocated 是否找到了可送达的站台（找不到时只让玩家下车，不传送）
   * @param destroyed 是否销毁了列车
   */
  public record RescueResult(boolean found, int moved, boolean relocated, boolean destroyed) {}

  /** 管理员救援一列车：结束车上的驾驶（驾驶任务判未完成），让车上的玩家下车并送到最近停过的车站站台；{@code destroy} 时随后销毁列车， 交路换车机制会补车。 */
  public RescueResult rescueTrain(String trainName, boolean destroy) {
    Optional<MinecartGroup> groupOpt = SeatLocator.findGroup(trainName);
    if (groupOpt.isEmpty()) {
      return new RescueResult(false, 0, false, false);
    }
    MinecartGroup group = groupOpt.get();
    String name = group.getProperties().getTrainName();
    UUID driverId = null;
    DriverStationStop lastStop = null;
    for (DriveSession session : new ArrayList<>(active.values())) {
      DriverLink link = session.driverLink();
      boolean onThisTrain =
          session.trainName().equalsIgnoreCase(name)
              || (link != null && link.currentTrainName().equalsIgnoreCase(name));
      if (!onThisTrain) {
        continue;
      }
      driverId = session.playerId();
      if (link != null) {
        lastStop = link.lastStop().orElse(null);
        tasks
            .activeTaskOf(driverId)
            .filter(task -> task.state() == DriverTask.State.DRIVING)
            .ifPresent(task -> tasks.fail(session.playerId(), "rescue"));
      }
      traceSession(session, "管理员救援，结束驾驶");
      endNow(session, DriveSession.EndReason.ADMIN);
    }
    Optional<Location> target = tasks.rescueLocation(driverId, lastStop);
    int moved = 0;
    for (MinecartMember<?> member : group) {
      for (Player rider : member.getEntity().getPlayerPassengers()) {
        rider.leaveVehicle();
        target.ifPresent(rider::teleport);
        moved++;
      }
    }
    plugin
        .getLogger()
        .warning("管理员救援列车 " + name + "：送下车 " + moved + " 名玩家" + (destroy ? "，并销毁列车" : ""));
    if (destroy) {
      group.destroy();
    }
    return new RescueResult(true, moved, target.isPresent(), destroy);
  }

  /** 驾驶员列车挡住后车：超过告警线提醒驾驶员（聊天栏一次，动作栏持续显示被挡秒数），超过强制线且列车停着、不在表定停站时转 ATO。 在行进中到达强制线的，等停下再转。 */
  private void superviseCongestion(DriverRecovery recovery) {
    for (DriveSession session : new ArrayList<>(active.values())) {
      DriverLink link = session.driverLink();
      if (link == null) {
        continue;
      }
      DriverCongestion.Stage stage =
          link.updateBlocking(tasks.blockedBehindSeconds(link.currentTrainName()), recovery);
      Player player = Bukkit.getPlayer(session.playerId());
      switch (stage) {
        case WARN -> {
          traceSession(session, "后车被挡 " + link.warnedBlockingSeconds() + " 秒，提醒驾驶员");
          if (player != null) {
            player.sendMessage(
                plugin
                    .getLocaleManager()
                    .component(
                        "drive.driver.congestion.warn",
                        Map.of("seconds", String.valueOf(link.warnedBlockingSeconds()))));
            sounds.play(player, DriveCue.ATP_BRAKE);
          }
        }
        case ATO -> {
          boolean dwelling =
              link.stationStop()
                  .map(stop -> stop.phase() == DriverStationStop.Phase.DWELL)
                  .orElse(false);
          Optional<MinecartGroup> group = findSessionGroup(session);
          if (session.isAto() || dwelling || !session.isStopped() || group.isEmpty()) {
            link.deferCongestionStage();
            continue;
          }
          switchToAto(session, group.get(), "congestion");
          tasks.activeTaskOf(session.playerId()).ifPresent(task -> task.setMode(DrivingMode.ATO));
          if (player != null) {
            player.sendMessage(plugin.getLocaleManager().component("drive.driver.congestion.ato"));
          }
        }
        default -> {}
      }
    }
  }

  /**
   * 已领取的车次停在接班站：提示玩家坐进车头驾驶室，坐好后由玩家确认（{@code /fta drive on}）才接班。
   *
   * <p>坐下不立即接班：往驾驶室走的途中坐过的座位，或前半列车里的客室座位，都会被当成驾驶室，接班后快捷栏就换成了手柄。
   *
   * @return 已在驾驶这列车时为 {@code null}，否则是给玩家的提示语言键
   */
  private String tryStartTask(Player player, DriverTask task) {
    DriveSession existing = active.get(player.getUniqueId());
    if (existing != null) {
      return existing.trainName().equalsIgnoreCase(task.trainName()) ? null : "drive.task.busy";
    }
    SeatBinding seat = SeatLocator.locate(player).orElse(null);
    Optional<MinecartGroup> group =
        seat == null ? Optional.empty() : SeatLocator.findGroup(seat.trainName());
    boolean eitherEnd =
        pickups
            .ofPlayer(player.getUniqueId())
            .filter(
                pickup ->
                    pickup.kind() == DriverPickups.Kind.TERMINAL
                        && pickup.stage() == DriverPickups.Stage.WAITING
                        && pickup.key().equals(task.key()))
            .isPresent();
    DriverTaskManager.SeatCheck check =
        DriverTaskManager.checkSeat(
            group.isPresent() ? seat : null,
            task.trainName(),
            group.map(MinecartGroup::size).orElse(0),
            eitherEnd);
    if (check != DriverTaskManager.SeatCheck.CONFIRM) {
      seatPrompts.remove(player.getUniqueId());
      return check == DriverTaskManager.SeatCheck.WRONG_SEAT
          ? "drive.task.go-to-cab"
          : "drive.task.arrived";
    }
    if (!seat.equals(seatPrompts.put(player.getUniqueId(), seat))) {
      player.sendMessage(
          plugin
              .getLocaleManager()
              .component(
                  "drive.task.confirm-seat-chat",
                  Map.of(
                      "train",
                      seat.trainName(),
                      "car",
                      String.valueOf(seat.memberIndex() + 1),
                      "cars",
                      String.valueOf(group.get().size()))));
    }
    return "drive.task.confirm-seat";
  }

  // ---- 始发站与车库接班 ----

  /** 有没有可能要等驾驶员接车：没有驾驶任务时派车侧不必逐张票去查车次。 */
  public boolean hasDriverPickupInterest() {
    return tasks.hasActiveTasks() || !pickups.isEmpty();
  }

  /**
   * 派车侧问：终点站待命车 {@code trainName} 能不能派去跑 {@code trip}。
   *
   * <p>这一班有人领、要在始发站接班时，先留着这列车，通知驾驶员上车坐进驾驶室并确认座位；驾驶员接班后放行，发车由驾驶员完成。 等到时限照常派车。
   *
   * @param trip 票据要开的车次；不是表定车次时为 {@code null}
   */
  public boolean allowLayoverDispatch(TimetableService.DueTrip trip, String trainName) {
    Instant now = Instant.now();
    Optional<DriverTask> task =
        trip == null
            ? Optional.empty()
            : tasks.taskForTrip(trip.timetable().id(), trip.trip().tripCode(), trip.serviceDate());
    UUID claimant = task.map(DriverTask::playerId).orElse(null);
    DriverPickups.Verdict verdict =
        pickups.layover(
            task.map(DriverTask::key).orElse(null),
            claimant,
            trainName,
            claimant != null && drivingTrain(claimant, trainName),
            now);
    if (verdict != DriverPickups.Verdict.START) {
      return verdict == DriverPickups.Verdict.DISPATCH;
    }
    DriverTask claimed = task.orElseThrow();
    DriveConfig current = config;
    // 驾驶员不在线就不等：留着车只会让这一班白白晚点。
    if (claimed.state() != DriverTask.State.CLAIMED
        || claimed.boardStopSequence() != 0
        || !pickupOpen(current)
        || !online(claimed.playerId())) {
      return true;
    }
    startPickup(
        claimed,
        DriverPickups.Kind.TERMINAL,
        trainName,
        claimed.stationName(),
        pickupDeadline(now, trip.departure()));
    return false;
  }

  /**
   * 派车侧问：车库刚出车的 {@code trainName}（担当 {@code trip}，或为它出库）要不要先扣在车库股道上等驾驶员。
   *
   * <p>只对领取后选了“从车库接车”的任务生效；扣住后由驾驶员上车或等到时限放行。
   *
   * @param trip 这列车要开的车次；不是表定车次时为 {@code null}
   */
  public boolean holdDepotSpawn(TimetableService.DueTrip trip, String trainName) {
    DriveConfig current = config;
    if (trip == null || trainName == null || !pickupOpen(current)) {
      return false;
    }
    Optional<DriverTask> task =
        tasks
            .taskForTrip(trip.timetable().id(), trip.trip().tripCode(), trip.serviceDate())
            .filter(t -> t.state() == DriverTask.State.CLAIMED && t.depotPickup())
            .filter(t -> online(t.playerId()));
    if (task.isEmpty()) {
      return false;
    }
    switch (pickups.depot(task.get().playerId(), task.get().key(), trainName)) {
      case SKIP -> {
        return false;
      }
      case KEEP -> {
        return true;
      }
      case START -> {
        // 同一班又出了一次车（上次出车在提交前回滚了）：放开旧车的门控，改等这一列。
        pickups
            .ofPlayer(task.get().playerId())
            .filter(previous -> previous.key().equals(task.get().key()))
            .ifPresent(
                stale -> {
                  pickups.remove(stale.playerId());
                  releasePickup(stale);
                });
      }
    }
    startPickup(
        task.get(),
        DriverPickups.Kind.DEPOT,
        trainName,
        depotOriginOf(task.get()).orElse("-"),
        Instant.now().plusSeconds(current.driver().pickupWaitSeconds()));
    return true;
  }

  private boolean pickupOpen(DriveConfig current) {
    return current.enabled() && current.driver().enabled() && packetsReady;
  }

  /** 接车时限：等 {@code pickup-wait-seconds}，且不晚于票据作废之前（见 {@link DriverPickups#deadline}）。 */
  private Instant pickupDeadline(Instant from, Instant plannedDeparture) {
    return DriverPickups.deadline(
        from,
        config.driver().pickupWaitSeconds(),
        plannedDeparture,
        plugin
            .getTimetableService()
            .map(service -> service.settings().assignTolerance())
            .orElse(null),
        PICKUP_TOLERANCE_MARGIN_SECONDS);
  }

  private static boolean online(UUID playerId) {
    Player player = Bukkit.getPlayer(playerId);
    return player != null && player.isOnline();
  }

  /** 这名驾驶员此刻是否正在驾驶这列车。 */
  private boolean drivingTrain(UUID playerId, String trainName) {
    DriveSession session = active.get(playerId);
    return session != null
        && session.driverLink() != null
        && trainName != null
        && session.trainName().equalsIgnoreCase(trainName);
  }

  private void startPickup(
      DriverTask task,
      DriverPickups.Kind kind,
      String trainName,
      String location,
      Instant deadline) {
    DriverPickups.Pickup pickup =
        pickups.start(task.playerId(), task.key(), kind, trainName, location, deadline);
    task.setTrainName(trainName);
    announcePickup(task, pickup, DriverPickupEvent.Stage.WAITING);
    traceTask(
        "等驾驶员接车 "
            + task.playerName()
            + " "
            + task.key().tripCode()
            + " "
            + kind
            + " 列车 "
            + trainName
            + " @"
            + location);
    Player player = Bukkit.getPlayer(task.playerId());
    if (player == null || !player.isOnline()) {
      return;
    }
    player.sendMessage(
        plugin
            .getLocaleManager()
            .component(
                kind == DriverPickups.Kind.TERMINAL
                    ? "drive.task.pickup.terminal"
                    : "drive.task.pickup.depot",
                Map.of(
                    "trip",
                    task.key().tripCode(),
                    "train",
                    trainName,
                    "location",
                    location,
                    "seconds",
                    String.valueOf(pickup.secondsLeft(Instant.now())))));
    if (config.driver().pickupTeleport()) {
      player.sendMessage(plugin.getLocaleManager().component("drive.task.pickup.goto-button"));
    }
  }

  /** 推进接车：提示驾驶员上车、到时限放行、任务结束时清掉；坐进终点站待命车也可以提前开始接车。 */
  private void tickPickups() {
    DriveConfig current = config;
    if (!pickupOpen(current)) {
      for (DriverPickups.Pickup pickup : pickups.all()) {
        pickups.remove(pickup.playerId());
        releasePickup(pickup);
      }
      return;
    }
    Instant now = Instant.now();
    startEarlyPickups(now, current);
    if (pickups.isEmpty()) {
      return;
    }
    for (DriverPickups.Pickup expired : pickups.expire(now)) {
      onPickupExpired(expired);
    }
    for (DriverPickups.Pickup pickup : pickups.all()) {
      Optional<DriverTask> task =
          tasks.activeTaskOf(pickup.playerId()).filter(active -> active.key().equals(pickup.key()));
      if (task.isEmpty()) {
        // 任务已结束（放弃、作废、收回）：放开还扣着的车。
        pickups.remove(pickup.playerId());
        releasePickup(pickup);
        continue;
      }
      if (pickup.stage() == DriverPickups.Stage.BOARDED) {
        // 驾驶员在车上；派车改名后（或已离开驾驶）这条记录就用完了。
        if (!drivingTrain(pickup.playerId(), pickup.trainName())) {
          pickups.remove(pickup.playerId());
        }
        continue;
      }
      if (pickup.stage() != DriverPickups.Stage.WAITING) {
        continue;
      }
      Player player = Bukkit.getPlayer(pickup.playerId());
      if (player == null || !player.isOnline()) {
        continue;
      }
      String hint = tryStartTask(player, task.get());
      if (hint == null) {
        continue;
      }
      sendTaskHint(
          player,
          "drive.task.arrived".equals(hint) ? "drive.task.pickup.waiting" : hint,
          Map.of(
              "trip",
              pickup.key().tripCode(),
              "train",
              pickup.trainName(),
              "location",
              pickup.location(),
              "station",
              task.get().stationName(),
              "seconds",
              String.valueOf(pickup.secondsLeft(now))));
    }
  }

  /** 坐进终点站待命车、它的下一班正是自己领的那一班：不必等派车，提前开始接车。 */
  private void startEarlyPickups(Instant now, DriveConfig current) {
    Optional<TimetableService> timetables = plugin.getTimetableService();
    Optional<LayoverRegistry> layovers = plugin.getLayoverRegistry();
    if (timetables.isEmpty() || layovers.isEmpty()) {
      return;
    }
    for (DriverTask task : tasks.activeTasks()) {
      if (task.state() != DriverTask.State.CLAIMED
          || task.boardStopSequence() != 0
          || pickups.ofPlayer(task.playerId()).isPresent()) {
        continue;
      }
      Player player = Bukkit.getPlayer(task.playerId());
      SeatBinding seat =
          player == null || !player.isOnline() ? null : SeatLocator.locate(player).orElse(null);
      if (seat == null
          || layovers.get().get(seat.trainName()).isEmpty()
          || pickups.ofTrain(seat.trainName()).isPresent()) {
        continue;
      }
      boolean next =
          timetables
              .get()
              .nextDepartureOf(seat.trainName())
              .filter(
                  due ->
                      task.key()
                          .matches(due.timetable().id(), due.trip().tripCode(), due.serviceDate()))
              .isPresent();
      if (next) {
        Instant from = task.plannedDeparture().isAfter(now) ? task.plannedDeparture() : now;
        startPickup(
            task,
            DriverPickups.Kind.TERMINAL,
            seat.trainName(),
            task.stationName(),
            pickupDeadline(from, task.plannedDeparture()));
      }
    }
  }

  /** 接车等到时限：终点站待命车照常派车、任务作废；车库出车放开门控，车次仍可在接班站接班。 */
  private void onPickupExpired(DriverPickups.Pickup pickup) {
    Optional<DriverTask> task =
        tasks.activeTaskOf(pickup.playerId()).filter(active -> active.key().equals(pickup.key()));
    task.ifPresent(active -> announcePickup(active, pickup, DriverPickupEvent.Stage.EXPIRED));
    traceTask(
        "接车等到时限 " + pickup.key().tripCode() + " " + pickup.kind() + " 列车 " + pickup.trainName());
    Player player = Bukkit.getPlayer(pickup.playerId());
    if (pickup.kind() == DriverPickups.Kind.DEPOT) {
      releasePickup(pickup);
      // 出库走行接的是终点站发出的首班：改为在终点站接车。
      if (task.filter(active -> active.boardStopSequence() == 0).isPresent()) {
        pickups.remove(pickup.playerId());
      }
      if (player != null && player.isOnline()) {
        player.sendMessage(
            plugin
                .getLocaleManager()
                .component(
                    "drive.task.pickup.depot-expired",
                    Map.of("station", task.map(DriverTask::stationName).orElse("-"))));
      }
      return;
    }
    tasks.expireClaim(pickup.playerId(), "pickup-timeout");
    if (player != null && player.isOnline()) {
      player.sendMessage(
          plugin
              .getLocaleManager()
              .component(
                  "drive.task.pickup.terminal-expired", Map.of("trip", pickup.key().tripCode())));
    }
  }

  /**
   * 放开车库出车扣着的车：撤掉发车门控，强制刷新一次信号让自动运行把车开走。
   *
   * <p>等驾驶员期间这列车按驾驶员控制处理、调度没给它下发过发车，信号不变就不会再要求发车，所以要强制刷新。 调用前须先把接车记录移除或标为过时，否则它仍算在等驾驶员。
   */
  private void releasePickup(DriverPickups.Pickup pickup) {
    if (pickup.kind() != DriverPickups.Kind.DEPOT
        || pickup.stage() == DriverPickups.Stage.BOARDED) {
      return;
    }
    plugin
        .getRuntimeDispatchService()
        .ifPresent(
            dispatch -> {
              dispatch.releaseDepartureGate(
                  pickup.trainName(), SimpleTicketAssigner.DRIVER_PICKUP_GATE);
              SeatLocator.findGroup(pickup.trainName()).ifPresent(dispatch::refreshSignal);
            });
  }

  /** 驾驶员上了留给他的车：车库出车立即放开门控，由调度按信号给出行车许可。 */
  private void onPickupBoarded(DriveSession session, String trainName) {
    pickups
        .board(session.playerId(), trainName)
        .ifPresent(
            pickup -> {
              traceSession(session, "接车上车 " + pickup.kind() + " " + pickup.location());
              tasks
                  .activeTaskOf(session.playerId())
                  .ifPresent(task -> announcePickup(task, pickup, DriverPickupEvent.Stage.BOARDED));
              if (pickup.kind() != DriverPickups.Kind.DEPOT) {
                return;
              }
              plugin
                  .getRuntimeDispatchService()
                  .ifPresent(
                      dispatch ->
                          dispatch.releaseDepartureGate(
                              trainName, SimpleTicketAssigner.DRIVER_PICKUP_GATE));
              boolean viaTerminal =
                  tasks
                      .activeTaskOf(session.playerId())
                      .filter(task -> task.boardStopSequence() == 0)
                      .isPresent();
              if (!viaTerminal) {
                pickups.remove(session.playerId());
              }
            });
  }

  /** 这一车次的列车从哪个车库出车；不从车库出车时为空。 */
  private Optional<String> depotOriginOf(DriverTask task) {
    return plugin
        .getTimetableService()
        .flatMap(service -> service.depotOriginOf(task.key().timetableId(), task.key().tripCode()));
  }

  /**
   * 领取后改为从车库接车，或取消。只对从车库出车的车次有效。
   *
   * @return 是否有可切换的任务
   */
  public boolean togglePickup(Player player) {
    Optional<DriverTask> task = tasks.activeTaskOf(player.getUniqueId());
    if (task.isEmpty()) {
      return false;
    }
    if (task.get().state() != DriverTask.State.CLAIMED) {
      player.sendMessage(plugin.getLocaleManager().component("drive.task.pickup.started"));
      return true;
    }
    Optional<String> depot = depotOriginOf(task.get());
    if (depot.isEmpty()) {
      player.sendMessage(plugin.getLocaleManager().component("drive.task.pickup.not-depot"));
      return true;
    }
    boolean on = !task.get().depotPickup();
    task.get().setDepotPickup(on);
    player.sendMessage(
        plugin
            .getLocaleManager()
            .component(
                on ? "drive.task.pickup.depot-on" : "drive.task.pickup.depot-off",
                Map.of("depot", depot.get(), "station", task.get().stationName())));
    return true;
  }

  /**
   * 前往接车：传送到要接的那列车的车头驾驶室旁边（不塞进座位）。只能去留给自己、还在等的那列车。
   *
   * @return 给玩家的提示语言键
   */
  public String gotoPickup(Player player) {
    if (!config.driver().pickupTeleport()) {
      return "drive.task.goto.disabled";
    }
    if (active.containsKey(player.getUniqueId())) {
      return "drive.task.goto.driving";
    }
    Optional<DriverPickups.Pickup> pickup =
        pickups
            .ofPlayer(player.getUniqueId())
            .filter(waiting -> waiting.stage() == DriverPickups.Stage.WAITING);
    if (pickup.isEmpty()) {
      return "drive.task.goto.none";
    }
    Optional<MinecartGroup> group = SeatLocator.findGroup(pickup.get().trainName());
    MinecartMember<?> head = group.map(MinecartGroup::head).orElse(null);
    if (head == null || head.getEntity() == null) {
      return "drive.task.goto.train-missing";
    }
    org.bukkit.Location at = head.getEntity().getLocation();
    org.bukkit.World world = at.getWorld();
    if (world == null) {
      return "drive.task.goto.train-missing";
    }
    Optional<Vector> spot =
        PickupSpot.find(
            at.toVector(),
            StopAlignment.travel(group.get()),
            (x, y, z) ->
                world.getBlockAt(x, y - 1, z).getType().isSolid()
                    && world.getBlockAt(x, y, z).isPassable()
                    && world.getBlockAt(x, y + 1, z).isPassable());
    if (spot.isEmpty()) {
      return "drive.task.goto.no-spot";
    }
    org.bukkit.Location target =
        new org.bukkit.Location(world, spot.get().getX(), spot.get().getY(), spot.get().getZ());
    Vector facing = at.toVector().subtract(spot.get());
    if (facing.lengthSquared() > 1.0e-6) {
      target.setDirection(facing);
    }
    if (player.isInsideVehicle()) {
      player.leaveVehicle();
    }
    player.teleport(target);
    traceTask("前往接车 " + player.getName() + " -> " + pickup.get().trainName());
    return "drive.task.goto.teleported";
  }

  /** 调度给列车改了名（终点站复用接下一班）：会话与任务跟着新车名走。 */
  private void followRename(DriveSession session, MinecartGroup group) {
    String current = group.getProperties().getTrainName();
    if (current == null || current.isBlank() || current.equals(session.trainName())) {
      return;
    }
    traceSession(session, "列车改名 " + session.trainName() + " -> " + current);
    session.followRename(current);
    tasks.activeTaskOf(session.playerId()).ifPresent(task -> task.setTrainName(current));
  }

  /**
   * 驾驶员坐在列车后端（终点站折返调头之后，或接车时坐了后端驾驶室）：停稳、拿到行车许可时换到前端驾驶室。
   *
   * <p>按车厢位置对调（第 n 节换到倒数第 n 节）、座位序号不变；前端没有空座位时交还自动运行。
   *
   * @return 会话是否已经结束
   */
  private boolean changeCabIfNeeded(DriveSession session, MinecartGroup group, Player player) {
    DriverLink link = session.driverLink();
    int members = group.size();
    if (link == null
        || session.isAto()
        || members < 2
        || session.binding().cabSign(members) >= 0
        || !session.isStopped()
        || link.directive() == null
        || link.directive().isStop()) {
      return false;
    }
    SeatBinding front =
        new SeatBinding(
            session.trainName(),
            members - 1 - session.binding().memberIndex(),
            session.binding().seatIndex());
    boolean moved = false;
    // 先确认前端座位存在且空着，再动驾驶员：坐不过去时不能把他留在车外。
    if (SeatLocator.seatAvailable(group, front, player)) {
      moved = SeatLocator.reseat(player, group, front);
      if (!moved && player.isInsideVehicle()) {
        // 有的座位不接受直接从另一个座位换过去：先下车再坐，还坐不上就坐回原座位。
        SeatBinding back = session.binding();
        player.leaveVehicle();
        moved = SeatLocator.reseat(player, group, front);
        if (!moved) {
          SeatLocator.reseat(player, group, back);
        }
      }
    }
    if (moved) {
      session.rebind(front);
      refreshInventory(player, session);
      traceSession(session, "换端：坐到前端驾驶室（第 " + (front.memberIndex() + 1) + " 节）");
      player.sendMessage(
          plugin
              .getLocaleManager()
              .component(
                  "drive.task.cab-changed",
                  Map.of("car", String.valueOf(front.memberIndex() + 1))));
      return false;
    }
    if (cabChangeFailed.add(session.playerId())) {
      traceSession(session, "换端失败，交还自动运行");
      player.sendMessage(plugin.getLocaleManager().component("drive.task.cab-change-failed"));
      requestHandback(session, "cab-change");
    }
    return session.phase() == DriveSession.Phase.ENDED;
  }

  // ---- 对外事件 ----

  /** 驾驶任务的对外事件：领取前可被外部插件拦下，没开过车就结束的当场报。 */
  private final class TaskEvents implements DriverTaskManager.Listener {
    @Override
    public boolean beforeClaim(DriverTask task) {
      DriverTaskClaimEvent event = new DriverTaskClaimEvent(task.playerId(), TaskViews.of(task));
      callEvent(event);
      return !event.isCancelled();
    }

    @Override
    public void onUnstartedFinished(DriverTask task) {
      callEvent(new DriverTaskFinishedEvent(task.playerId(), TaskViews.of(task), Optional.empty()));
    }
  }

  /** 发出驾驶事件；监听器的异常由 Bukkit 记录，不影响驾驶。 */
  private void callEvent(Event event) {
    // 监听方可能转到其他线程读任务与会话（例如 Typewriter 的事实）：先让快照跟上事件。
    refreshViews();
    try {
      Bukkit.getPluginManager().callEvent(event);
    } catch (RuntimeException ex) {
      plugin.getLogger().warning("驾驶事件处理失败: " + event.getEventName() + " " + ex);
    }
  }

  private void announcePickup(
      DriverTask task, DriverPickups.Pickup pickup, DriverPickupEvent.Stage stage) {
    callEvent(
        new DriverPickupEvent(
            task.playerId(),
            TaskViews.of(task),
            pickup.trainName(),
            DriverPickupEvent.Kind.valueOf(pickup.kind().name()),
            stage,
            pickup.location()));
  }

  /**
   * 新记下成绩的停站逐站对外报。
   *
   * @param owner 这些停站所属的任务（驾驶中为未结束的任务，结束时为刚结束的那一趟）
   */
  private void announceStops(
      DriveSession session, DriverLink link, String trainName, Optional<DriverTask> owner) {
    int recorded = link.score().stopCount();
    int announced = link.announcedStops();
    if (recorded <= announced) {
      return;
    }
    Optional<DriveApi.TaskView> task =
        owner.filter(active -> trainName.equalsIgnoreCase(active.trainName())).map(TaskViews::of);
    List<StopScore> stops = link.score().stops();
    for (int i = announced; i < stops.size(); i++) {
      callEvent(
          new DriverStopScoredEvent(
              session.playerId(), task, trainName, TaskViews.stop(stops.get(i))));
    }
    link.setAnnouncedStops(stops.size());
  }

  // ---- 插件派任务（DriveApi）----

  /**
   * 插件派出一个驾驶任务（主线程）。成功时按需给玩家发领取提示。
   *
   * @param notify 是否给玩家发领取提示
   */
  public DriverTaskManager.ClaimOutcome assignTask(
      Player player, DriverTaskManager.TaskSpec spec, DrivingMode mode, boolean notify) {
    DriverTaskManager.ClaimOutcome outcome =
        tasks.assign(player, spec, mode, pickupOpen(config), Instant.now());
    if (outcome == DriverTaskManager.ClaimOutcome.CLAIMED) {
      refreshViews();
    }
    if (outcome != DriverTaskManager.ClaimOutcome.CLAIMED || !notify) {
      return outcome;
    }
    player.sendMessage(
        plugin
            .getLocaleManager()
            .component(
                spec.alightStopSequence() >= 0
                    ? "drive.task.claim.assigned-interval"
                    : "drive.task.claim.assigned",
                Map.of(
                    "route",
                    spec.routeCode(),
                    "trip",
                    spec.key().tripCode(),
                    "station",
                    spec.stationName(),
                    "alight",
                    spec.alightStationName())));
    if (!spec.depotPickup()) {
      tasks
          .activeTaskOf(player.getUniqueId())
          .flatMap(this::depotOriginOf)
          .ifPresent(
              depot ->
                  player.sendMessage(
                      plugin
                          .getLocaleManager()
                          .component("drive.task.pickup.depot-option", Map.of("depot", depot))));
    }
    return outcome;
  }

  /**
   * 放弃玩家的任务（插件调用）：还没开始驾驶的直接作废；驾驶中的先停车再交还。
   *
   * @return 玩家是否有未结束的任务
   */
  public boolean abandonTask(UUID playerId, String reason) {
    Optional<DriverTask> task = tasks.activeTaskOf(playerId);
    if (task.isEmpty()) {
      return false;
    }
    String why = reason == null || reason.isBlank() ? "api" : reason;
    if (task.get().state() == DriverTask.State.DRIVING) {
      DriveSession session = active.get(playerId);
      tasks.abandon(playerId, why);
      if (session != null) {
        requestHandback(session, "abandon");
      }
      return true;
    }
    return tasks.abandon(playerId, why);
  }

  /** 玩家当前驾驶会话的快照。 */
  public Optional<DriveApi.SessionView> sessionView(UUID playerId) {
    DriveSession session = active.get(playerId);
    if (session == null) {
      return Optional.empty();
    }
    DriverLink link = session.driverLink();
    Optional<String> next =
        link == null
            ? Optional.empty()
            : Optional.of(link.targetLabel().isBlank() ? link.nextStopLabel() : link.targetLabel())
                .filter(label -> !label.isBlank());
    return Optional.of(
        new DriveApi.SessionView(
            playerId,
            session.trainName(),
            link != null,
            session.isAto() ? DriveApi.Mode.ATO : DriveApi.Mode.MANUAL,
            session.speedBps() * 3.6,
            next,
            tasks
                .activeTaskOf(playerId)
                .filter(task -> session.trainName().equalsIgnoreCase(task.trainName()))
                .map(DriverTask::taskId)));
  }

  /**
   * 玩家最近的任务快照：主线程上取实时值，其他线程取最多半秒前的快照。
   *
   * @return 没有任务时为空
   */
  public Optional<DriveApi.TaskView> taskView(UUID playerId) {
    if (Bukkit.isPrimaryThread()) {
      return tasks.taskOf(playerId).map(TaskViews::of);
    }
    return Optional.ofNullable(taskViews.get(playerId));
  }

  /** 玩家驾驶会话的快照：主线程上取实时值，其他线程取最多半秒前的快照。 */
  public Optional<DriveApi.SessionView> sessionViewSnapshot(UUID playerId) {
    if (Bukkit.isPrimaryThread()) {
      return sessionView(playerId);
    }
    return Optional.ofNullable(sessionViews.get(playerId));
  }

  private void refreshViews() {
    Map<UUID, DriveApi.TaskView> nextTasks = new HashMap<>();
    for (DriverTask task : tasks.allTasks()) {
      nextTasks.put(task.playerId(), TaskViews.of(task));
    }
    Map<UUID, DriveApi.SessionView> nextSessions = new HashMap<>();
    for (UUID playerId : active.keySet()) {
      sessionView(playerId).ifPresent(view -> nextSessions.put(playerId, view));
    }
    taskViews = Map.copyOf(nextTasks);
    sessionViews = Map.copyOf(nextSessions);
  }

  /** 驾驶调度列车（任务模式）此刻是否可用。 */
  public boolean driverTasksOpen() {
    return pickupOpen(config);
  }

  private void sendTaskHint(Player player, String key, Map<String, String> values) {
    player.sendActionBar(plugin.getLocaleManager().component(key, values));
  }

  private void sendTaskChat(Player player, String key, Map<String, String> values) {
    player.sendMessage(plugin.getLocaleManager().component(key, values));
  }

  /**
   * 领取任务板上的一个车次。
   *
   * @return 给玩家的提示语言键
   */
  public String claimTask(
      Player player, TaskBoardHolder holder, TaskBoardEntries.Row row, DrivingMode mode) {
    if (!player.hasPermission(PERMISSION_DRIVER)) {
      return "drive.task.claim.no-permission";
    }
    DriveConfig current = config;
    DriverTaskManager.ClaimOutcome outcome =
        tasks.claim(
            player,
            row,
            holder.operatorCode(),
            holder.stationCode(),
            holder.stationName(),
            mode,
            current.enabled() && current.driver().enabled() && packetsReady,
            Instant.now());
    if (outcome == DriverTaskManager.ClaimOutcome.CLAIMED) {
      // 车次的列车从车库出车：告诉驾驶员可以改为从车库接车。
      tasks
          .activeTaskOf(player.getUniqueId())
          .flatMap(this::depotOriginOf)
          .ifPresent(
              depot ->
                  Bukkit.getScheduler()
                      .runTask(
                          plugin,
                          () ->
                              player.sendMessage(
                                  plugin
                                      .getLocaleManager()
                                      .component(
                                          "drive.task.pickup.depot-option",
                                          Map.of("depot", depot)))));
    }
    return "drive.task.claim." + outcome.name().toLowerCase(Locale.ROOT).replace('_', '-');
  }

  /**
   * 放弃任务：驾驶中先停车交还。
   *
   * @return 是否有任务可放弃
   */
  public boolean abandonTask(Player player) {
    return abandonTask(player.getUniqueId(), "command");
  }

  /**
   * 列车跑这一班的当前晚点（秒）：列车此刻绑定的不是这一班时为空（终点站接车时派车之前还绑着上一班）。
   *
   * @param key 车次；为空时按列车当前绑定的车次
   */
  private static OptionalLong delayOf(String trainName, TaskKey key) {
    return DriverTaskManager.timetables()
        .flatMap(api -> api.getAssignment(trainName))
        .map(assignment -> DriverTaskManager.delayOfTrip(assignment, key))
        .orElse(OptionalLong.empty());
  }

  /** 驾驶员所领车次的当前晚点；没有任务（运营人员直接接管）时按列车当前绑定的车次。 */
  private OptionalLong taskDelayOf(UUID playerId, String trainName) {
    return delayOf(trainName, tasks.activeTaskOf(playerId).map(DriverTask::key).orElse(null));
  }

  /** 任务随驾驶结束：评分、告诉驾驶员、写记录（异步）。没有任务（运营人员直接接管）时不记。 */
  private Optional<DriveApi.TaskScore> recordTask(DriveSession session, DriverLink link) {
    Optional<DriverTask> taskOpt =
        tasks
            .taskOf(session.playerId())
            .filter(task -> task.startedAt() != null && task.state().finished())
            .filter(task -> session.trainName().equalsIgnoreCase(task.trainName()))
            .filter(task -> task.points() < 0);
    if (taskOpt.isEmpty()) {
      return Optional.empty();
    }
    DriverTask task = taskOpt.get();
    TaskScore score = link.finalizeScore();
    score.setDelayAtEnd(delayOf(session.trainName(), task.key()));
    ScoreRules.Result result =
        ScoreRules.evaluate(score, task.state() == DriverTask.State.COMPLETED);
    task.setResult(result.points(), result.grade().name());
    Player player = Bukkit.getPlayer(session.playerId());
    if (player != null && player.isOnline()) {
      showResult(player, task, score, result);
      player.sendMessage(
          plugin
              .getLocaleManager()
              .component(
                  "drive.task.result",
                  Map.of(
                      "trip",
                      task.key().tripCode(),
                      "state",
                      plugin
                          .getLocaleManager()
                          .text("drive.task.state." + task.state().name().toLowerCase(Locale.ROOT)),
                      "points",
                      String.valueOf(result.points()),
                      "grade",
                      result.grade().name())));
      for (DriverReport.Line line : DriverReport.sheet(score)) {
        player.sendMessage(DriverReport.render(plugin.getLocaleManager(), line));
      }
    }
    DriveTaskRecord record =
        new DriveTaskRecord(
            UUID.randomUUID(),
            ServerIdentity.id().orElse(null),
            task.playerId(),
            task.playerName(),
            task.key().timetableId(),
            task.key().tripCode(),
            task.key().serviceDate(),
            task.routeCode(),
            task.trainName(),
            task.mode().name(),
            task.state().name(),
            result.points(),
            result.grade().name(),
            task.startedAt(),
            Instant.now(),
            DriveTaskRecordCodec.encode(score));
    saveRecord(record);
    return Optional.of(TaskViews.score(score, result.points(), result.grade().name()));
  }

  /** 任务结束时的大字评级：标题是评级，副标题是车次、终态与得分；完成时配音效。 */
  private void showResult(
      Player player, DriverTask task, TaskScore score, ScoreRules.Result result) {
    LocaleManager locale = plugin.getLocaleManager();
    Component subtitle =
        locale.component(
            "drive.task.result-subtitle",
            Map.of(
                "trip",
                task.key().tripCode(),
                "state",
                locale.text("drive.task.state." + task.state().name().toLowerCase(Locale.ROOT)),
                "points",
                String.valueOf(result.points())));
    player.showTitle(
        Title.title(
            locale.component("drive.grade." + result.grade().name().toLowerCase(Locale.ROOT)),
            subtitle,
            Title.Times.times(
                Duration.ofMillis(250), Duration.ofSeconds(3), Duration.ofMillis(750))));
    if (task.state() == DriverTask.State.COMPLETED) {
      sounds.play(player, DriveCue.TASK_COMPLETE);
    }
  }

  /** 停妥那一刻：动作栏停留显示对标结果，并配对应的音效。 */
  private void reportStop(DriveSession session, DriverStationStop stop) {
    Player player = Bukkit.getPlayer(session.playerId());
    StopScore score = StopScore.of(stop);
    if (player == null || score == null) {
      return;
    }
    DriverReport.stopResult(score)
        .ifPresent(
            line -> {
              player.sendActionBar(DriverReport.render(plugin.getLocaleManager(), line));
              session.holdActionBar(Bukkit.getCurrentTick() + NOTICE_HOLD_TICKS);
              sounds.play(player, DriverReport.stopCue(score.window()));
            });
  }

  /** 重载时存储连接池会被换掉：写入失败隔这么久用新的连接池再试一次。 */
  private static final long RECORD_RETRY_TICKS = 100L;

  private void saveRecord(DriveTaskRecord record) {
    if (!plugin.isEnabled()) {
      // 停用时驾驶模块先于存储关闭，同步写入仍打到打开着的连接池。
      writeRecord(record, false);
      return;
    }
    Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> writeRecord(record, true));
  }

  /** 写入时才取当前的存储（重载可能刚换了连接池）；失败且允许重试时隔一会儿再试一次。 */
  private void writeRecord(DriveTaskRecord record, boolean retry) {
    try {
      org.fetarute.fetaruteTCAddon.storage.StorageManager storage = plugin.getStorageManager();
      if (storage == null || !storage.isReady() || storage.provider().isEmpty()) {
        throw new IllegalStateException("存储未就绪");
      }
      storage.provider().get().driveTaskRecords().save(record);
    } catch (RuntimeException ex) {
      if (retry && plugin.isEnabled()) {
        Bukkit.getScheduler()
            .runTaskLaterAsynchronously(
                plugin, () -> writeRecord(record, false), RECORD_RETRY_TICKS);
        return;
      }
      plugin.getLogger().warning("保存驾驶任务记录失败: " + record.tripCode() + " " + ex);
    }
  }

  /**
   * 管理员收回某名玩家的任务：驾驶中先停车交还。
   *
   * @return 玩家是否有未结束的任务
   */
  public boolean revokeTask(UUID playerId) {
    Optional<DriverTask> task = tasks.activeTaskOf(playerId);
    if (task.isEmpty()) {
      return false;
    }
    DriveSession session = active.get(playerId);
    if (task.get().state() == DriverTask.State.DRIVING && session != null) {
      requestHandback(session, "revoked");
      return true;
    }
    tasks.interruptClaim(playerId, "revoked");
    return true;
  }

  /** 会话所驾驶的编组：驾驶调度列车时按控制链路记住的列车属性找（调度可能给列车改名），否则按车名找。 */
  private static Optional<MinecartGroup> findSessionGroup(DriveSession session) {
    DriverLink link = session.driverLink();
    if (link != null && link.properties() != null && link.properties().hasHolder()) {
      MinecartGroup holder = link.properties().getHolder();
      if (holder != null && holder.isValid()) {
        return Optional.of(holder);
      }
    }
    return SeatLocator.findGroup(session.trainName());
  }

  /** 用调度采样更新前方停车点的估计：车站股道上有对应节数的停车位置标时按车头对准标志，否则按列车中心对准车站牌子。 */
  private void updateApproach(
      DriverLink link, MinecartGroup group, ControlDiagnostics diagnostics) {
    NodeId node = diagnostics.stopNode();
    if (node != null && "station".equals(diagnostics.stopKind()) && group.getWorld() != null) {
      Optional<StationStopPoints.StopPoint> point =
          stationStopPoints.lookup(
              node,
              group.getWorld(),
              StopAlignment.travel(group),
              group.size(),
              Bukkit.getCurrentTick());
      if (point.isPresent() && point.get().reference() == StopAlignment.Reference.HEAD) {
        link.updateApproach(
            node,
            diagnostics.stopKind(),
            diagnostics.distanceToStopNode(),
            diagnostics.sampledAt(),
            point.get().aheadBlocks(),
            StopAlignment.Reference.HEAD);
        return;
      }
    }
    link.updateApproach(
        node,
        diagnostics.stopKind(),
        diagnostics.distanceToStopNode(),
        diagnostics.sampledAt(),
        StopAlignment.halfLengthBlocks(group));
  }

  /** 按交路进度刷新下一个停靠站与表定时刻（与乘客 HUD 同一口径）；展示层未启用或不明时清空。 */
  private void refreshNextStop(DriverLink link, MinecartGroup group) {
    Optional<TrainHudContext> context =
        plugin.getDisplayService().flatMap(display -> display.hudContext(group));
    link.setNextStopLabel(
        context
            .map(TrainHudContext::nextStation)
            .filter(station -> !station.isEmpty())
            .map(TrainHudContext.StationDisplay::label)
            .orElse(""));
    Optional<TimetableService> timetables = plugin.getTimetableService();
    link.setSchedule(
        context
            .flatMap(
                ctx ->
                    DriverSchedule.of(
                        ctx.trainName(),
                        ctx.stop(),
                        ctx.routeIndex(),
                        ctx.nextStopIndex(),
                        ctx.nextStopDelaySeconds(),
                        (train, index) ->
                            timetables.flatMap(service -> service.plannedArrivalOf(train, index)),
                        (train, index) ->
                            timetables.flatMap(service -> service.plannedDepartureOf(train, index)),
                        Instant.now()))
            .orElse(null));
  }

  /** 节点所属车站的站名；查不到时用节点编号。 */
  private String stationLabel(NodeId node) {
    return plugin
        .getStationDirectory()
        .flatMap(directory -> directory.snapshot().stationOfNode(node.value()))
        .map(StationDirectory.StationEntry::name)
        .orElse(node.value());
  }

  /** 调度列车上，车门只在停站的开门、停站阶段才能打开（站台放行车门）。 */
  private static boolean doorsReleased(DriveSession session) {
    DriverLink link = session.driverLink();
    if (link == null) {
      return true;
    }
    return link.stationStop()
        .map(
            stop ->
                stop.phase() == DriverStationStop.Phase.OPEN_DOORS
                    || stop.phase() == DriverStationStop.Phase.DWELL)
        .orElse(false);
  }

  private void tickStopping(DriveSession session, long now, DriveConfig current) {
    if (session.phase() == DriveSession.Phase.ENDED) {
      endNow(session, session.endReason());
      return;
    }
    Optional<MinecartGroup> groupOpt = findSessionGroup(session);
    if (groupOpt.isEmpty()) {
      endNow(session, DriveSession.EndReason.TRAIN_GONE);
      return;
    }
    ensureAction(groupOpt.get(), session, now);
  }

  /** TrainCarts 的牌子可能清空动作队列：发现动作停止推进就重新挂上。 */
  private void ensureAction(MinecartGroup group, DriveSession session, long now) {
    if (now - session.lastAdvanceTick() > 2) {
      try {
        attachAction(group, session);
      } catch (RuntimeException ex) {
        plugin.getLogger().warning("重新挂载驾驶动作失败: " + ex);
      }
    }
  }

  /**
   * 重发背包，让客户端的快捷栏回到驾驶物品。
   *
   * <p>重发的背包数据包在出站时已被改写成驾驶物品，客户端看到的始终是“驾驶物品换驾驶物品”，不会播放收到物品的动画。 只有改写失败过，才逐格补发驾驶物品兜底。
   */
  private void refreshInventory(Player player, DriveSession session) {
    player.updateInventory();
    if (!session.rewriteFailed()) {
      return;
    }
    List<ItemStack> hotbar = session.hotbar();
    for (int i = 0; i < hotbar.size(); i++) {
      PacketUtil.sendPacket(
          player,
          PacketPlayOutSetSlotHandle.createNew(
              HotbarRewriter.PLAYER_WINDOW,
              HotbarRewriter.HOTBAR_FIRST_SLOT + i,
              hotbar.get(i).clone()));
    }
  }
}
