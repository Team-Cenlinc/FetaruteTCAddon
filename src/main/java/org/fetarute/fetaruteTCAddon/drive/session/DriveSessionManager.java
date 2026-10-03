package org.fetarute.fetaruteTCAddon.drive.session;

import com.bergerkiller.bukkit.common.utils.PacketUtil;
import com.bergerkiller.bukkit.tc.controller.MinecartGroup;
import com.bergerkiller.bukkit.tc.controller.MinecartGroupStore;
import com.bergerkiller.bukkit.tc.controller.MinecartMember;
import com.bergerkiller.generated.net.minecraft.network.protocol.game.PacketPlayOutSetSlotHandle;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import org.fetarute.fetaruteTCAddon.FetaruteTCAddon;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.DriverControlTags;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.TrainTagHelper;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.config.TrainConfig;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.config.TrainConfigResolver;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.ControlAuthority;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.DriverInterrupt;
import org.fetarute.fetaruteTCAddon.drive.DriveConfig;
import org.fetarute.fetaruteTCAddon.drive.SimulationLevel;
import org.fetarute.fetaruteTCAddon.drive.cab.AirSystem;
import org.fetarute.fetaruteTCAddon.drive.cab.BrakeTest;
import org.fetarute.fetaruteTCAddon.drive.cab.CabSystems;
import org.fetarute.fetaruteTCAddon.drive.cab.Vigilance;
import org.fetarute.fetaruteTCAddon.drive.driver.DriverControlRegistry;
import org.fetarute.fetaruteTCAddon.drive.driver.DriverLink;
import org.fetarute.fetaruteTCAddon.drive.dynamics.DriveMode;
import org.fetarute.fetaruteTCAddon.drive.dynamics.DriveParams;
import org.fetarute.fetaruteTCAddon.drive.dynamics.MotorRatio;
import org.fetarute.fetaruteTCAddon.drive.dynamics.Notch;
import org.fetarute.fetaruteTCAddon.drive.dynamics.ReverserPosition;
import org.fetarute.fetaruteTCAddon.drive.hud.DriveHud;
import org.fetarute.fetaruteTCAddon.drive.hud.DriveSidebar;
import org.fetarute.fetaruteTCAddon.drive.inventory.DrivePacketListener;
import org.fetarute.fetaruteTCAddon.drive.inventory.HotbarItems;
import org.fetarute.fetaruteTCAddon.drive.inventory.HotbarRewriter;
import org.fetarute.fetaruteTCAddon.drive.inventory.InputSignal;
import org.fetarute.fetaruteTCAddon.drive.menu.DriveDoors;
import org.fetarute.fetaruteTCAddon.drive.menu.DriveMenu;
import org.fetarute.fetaruteTCAddon.drive.menu.MenuAction;
import org.fetarute.fetaruteTCAddon.drive.menu.MenuLayout;
import org.fetarute.fetaruteTCAddon.drive.seat.SeatBinding;
import org.fetarute.fetaruteTCAddon.drive.seat.SeatLocator;
import org.fetarute.fetaruteTCAddon.drive.setup.PowerSupply;
import org.fetarute.fetaruteTCAddon.drive.setup.SetupAnimations;
import org.fetarute.fetaruteTCAddon.drive.setup.SetupSystem;
import org.fetarute.fetaruteTCAddon.drive.setup.TrainSetup;
import org.fetarute.fetaruteTCAddon.drive.setup.TrainSetupStore;

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
    NOT_HEAD_CAB
  }

  /** 不领任务也能直接接管调度列车（调试、运营人员）。 */
  public static final String PERMISSION_DRIVER_ADMIN = "fetarute.drive.driver.admin";

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

  /** 尝试重新入座的间隔（tick）。 */
  private static final int RESEAT_RETRY_TICKS = 5;

  private final FetaruteTCAddon plugin;
  private final TrainConfigResolver trainConfigResolver = new TrainConfigResolver();
  private final ConcurrentHashMap<UUID, DriveSession> active = new ConcurrentHashMap<>();
  private final List<DriveSession> stopping = new ArrayList<>();
  private final ConcurrentHashMap<UUID, Boolean> refreshPending = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<UUID, Boolean> ackPending = new ConcurrentHashMap<>();
  private final DrivePacketListener packetListener = new DrivePacketListener(this);
  private final DriveMenu menu;
  private final DriveSidebar sidebar;
  private final Map<UUID, DriveDoors> doors = new HashMap<>();
  private final DriverControlRegistry driverRegistry = new DriverControlRegistry();

  private volatile DriveConfig config;
  private volatile boolean trace;
  private volatile boolean packetsReady;
  private BukkitTask tickTask;
  private long tickCounter;

  public DriveSessionManager(FetaruteTCAddon plugin, DriveConfig config) {
    this.plugin = plugin;
    this.config = config;
    this.menu = new DriveMenu(plugin.getLocaleManager());
    this.sidebar = new DriveSidebar(plugin.getLocaleManager());
    driverRegistry.setHandler(new DriverHandler());
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
    sidebar.hideAll();
    DrivePacketListener.unregister(plugin);
  }

  public DriveConfig config() {
    return config;
  }

  public void reload(DriveConfig newConfig) {
    this.config = newConfig;
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
      // 右键是警惕装置的确认键。它不会让客户端改动快捷栏，不必重发背包；按住右键时客户端会连续发包，合并到一次。
      if (ackPending.putIfAbsent(id, Boolean.TRUE) == null) {
        Bukkit.getScheduler()
            .runTask(
                plugin,
                () -> {
                  ackPending.remove(id);
                  DriveSession session = active.get(id);
                  if (session != null) {
                    acknowledgeVigilance(session, "右键");
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
    DriverLink driverLink = null;
    if (dispatchTrain) {
      driverLink =
          new DriverLink(
              player.getUniqueId(),
              group.getProperties().getTrainName(),
              group.getProperties(),
              session::odometerBlocks,
              Bukkit::getCurrentTick);
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
      TrainPropertyGuard.apply(group.getProperties(), params.maxSpeedBps());
      session.setGuardedSpeedLimit(group.getProperties().getSpeedLimit());
      attachAction(group, session);
      alignHead(group, session, player, Bukkit.getCurrentTick());
    } catch (RuntimeException ex) {
      plugin.getLogger().warning("开始驾驶失败: " + ex);
      // 控车动作可能已经挂上：结束会话让它自行退出，否则它会一直把列车按在原地、挡住队列里的其它动作。
      session.finish(DriveSession.EndReason.DISABLED);
      driverRegistry.unbind(driverLink);
      SeatLocator.findGroup(binding.trainName())
          .ifPresent(found -> TrainPropertyGuard.restore(found.getProperties()));
      return StartOutcome.FAILED;
    }
    active.put(player.getUniqueId(), session);
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
    }
    Inventory top = player.getOpenInventory().getTopInventory();
    if (DriveMenu.isMenu(top)) {
      menu.render(top, session);
    }
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
    Optional<MinecartGroup> group = SeatLocator.findGroup(session.trainName());
    if (group.isEmpty()) {
      denyMenu(player, "drive.menu.deny.unavailable");
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
    Optional<MinecartGroup> group = SeatLocator.findGroup(session.trainName());
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
      case WARNING_STARTED, WARNING_SECOND -> player.playSound(
          player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, SoundCategory.MASTER, 1.0f, 1.6f);
      case TRIPPED -> {
        player.getInventory().setHeldItemSlot(Notch.EB.slot());
        player.playSound(
            player.getLocation(), Sound.BLOCK_ANVIL_LAND, SoundCategory.MASTER, 0.6f, 0.8f);
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
    var properties = group.getProperties();
    TrainConfig base = trainConfigResolver.resolve(properties, plugin.getConfigManager().current());
    Optional<DriveMode> mode =
        TrainTagHelper.readTagValue(properties, TrainConfigResolver.TAG_TRAIN_MODE)
            .flatMap(DriveMode::parse);
    OptionalDouble motorFraction =
        TrainTagHelper.readTagValue(properties, TrainConfigResolver.TAG_TRAIN_MT)
            .map(MotorRatio::parse)
            .orElse(OptionalDouble.empty());
    OptionalDouble maxSpeed =
        TrainTagHelper.readDoubleTag(properties, TrainConfigResolver.TAG_TRAIN_MAX_BPS)
            .map(OptionalDouble::of)
            .orElse(OptionalDouble.empty());
    return DriveParams.resolve(
        base.type(),
        base.accelBps2(),
        base.decelBps2(),
        mode,
        motorFraction,
        maxSpeed,
        group.size(),
        current);
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
    if (session.isStopped() || !session.beginStopping(reason)) {
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
    if (link != null) {
      // 交还自动运行：解除控制权，下一 tick 让调度层从当前状态重新控车。
      driverRegistry.unbind(link);
      traceSession(session, "交还自动运行: " + reason);
    }
    Optional<MinecartGroup> groupOpt = SeatLocator.findGroup(session.trainName());
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
      return StartOutcome.NO_TASK;
    }
    if (measureSpeedBps(group) > current.startMaxSpeedBps()) {
      return StartOutcome.NOT_STOPPED_AT_STATION;
    }
    String name = group.getProperties().getTrainName();
    boolean dwelling =
        plugin.getDwellRegistry().map(r -> r.remainingSeconds(name).isPresent()).orElse(false)
            || plugin.getRuntimeDispatchService().map(d -> d.hasDepartureGate(name)).orElse(false);
    if (!dwelling) {
      return StartOutcome.NOT_STOPPED_AT_STATION;
    }
    if (binding.cabSign(group.size()) < 0) {
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
    Optional<MinecartGroup> groupOpt = SeatLocator.findGroup(session.trainName());
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
    ensureAction(group, session, now);
    if (session.driverLink() != null && tickDriverLink(session)) {
      return;
    }

    // 以玩家当前所坐的座位为准：编组被调头后车厢序号变了，按旧序号找座位会误判成离座。
    Optional<SeatBinding> seat =
        SeatLocator.locate(player).filter(found -> found.trainName().equals(session.trainName()));
    if (seat.isPresent()) {
      session.rebind(seat.get());
      session.markSeated();
      alignHead(group, session, player, now);
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
    boolean liveValues = session.setup().busy() || session.cab().enabled();
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
      if (now >= session.actionBarHeldUntil()) {
        player.sendActionBar(DriveHud.render(plugin.getLocaleManager(), session));
      }
      if (current.sidebar()) {
        sidebar.update(player, session);
      } else {
        sidebar.hide(player);
      }
    }
  }

  /**
   * 调度列车的控制链路：已请求交还（调度要求、指令长时间不来、管理员命令）且停稳时交还自动运行。
   *
   * @return 会话是否已经结束
   */
  private boolean tickDriverLink(DriveSession session) {
    DriverLink link = session.driverLink();
    if (link.handbackRequested() && session.isStopped()) {
      traceSession(session, "停稳，交还原因: " + link.handbackReason());
      handback(session, DriveSession.EndReason.HANDBACK);
      return true;
    }
    return false;
  }

  private void tickStopping(DriveSession session, long now, DriveConfig current) {
    if (session.phase() == DriveSession.Phase.ENDED) {
      endNow(session, session.endReason());
      return;
    }
    Optional<MinecartGroup> groupOpt = SeatLocator.findGroup(session.trainName());
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
