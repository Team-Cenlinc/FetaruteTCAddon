package org.fetarute.fetaruteTCAddon.drive.guard;

import com.bergerkiller.bukkit.tc.controller.MinecartGroup;
import com.bergerkiller.bukkit.tc.controller.MinecartMember;
import com.bergerkiller.bukkit.tc.controller.MinecartMemberStore;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.fetarute.fetaruteTCAddon.config.ConfigManager;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.DriverStationStop;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.DriverStationStop.Phase;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.StopAlignment;
import org.fetarute.fetaruteTCAddon.drive.DriveConfig;
import org.fetarute.fetaruteTCAddon.drive.driver.DriveRewards;
import org.fetarute.fetaruteTCAddon.drive.driver.DriverControlRegistry;
import org.fetarute.fetaruteTCAddon.drive.driver.DriverDoorSide;
import org.fetarute.fetaruteTCAddon.drive.driver.record.DriveTaskRecord;
import org.fetarute.fetaruteTCAddon.drive.driver.score.ScoreRules;
import org.fetarute.fetaruteTCAddon.drive.driver.task.DriverTask;
import org.fetarute.fetaruteTCAddon.drive.driver.task.TaskKey;
import org.fetarute.fetaruteTCAddon.drive.hud.DriveSidebar;
import org.fetarute.fetaruteTCAddon.drive.inventory.HotbarRewriter;
import org.fetarute.fetaruteTCAddon.drive.inventory.InputSignal;
import org.fetarute.fetaruteTCAddon.drive.menu.DriveDoors;
import org.fetarute.fetaruteTCAddon.drive.seat.CabSeatKey;
import org.fetarute.fetaruteTCAddon.drive.seat.CabSeats;
import org.fetarute.fetaruteTCAddon.drive.seat.SeatBinding;
import org.fetarute.fetaruteTCAddon.drive.seat.SeatLocator;
import org.fetarute.fetaruteTCAddon.drive.session.ManagedTrains;
import org.fetarute.fetaruteTCAddon.drive.sound.DriveCue;
import org.fetarute.fetaruteTCAddon.drive.sound.DriveSounds;
import org.fetarute.fetaruteTCAddon.interlink.ServerIdentity;
import org.fetarute.fetaruteTCAddon.utils.LocaleManager;

/**
 * 车掌值乘：上岗、离岗，每 tick 推进站停作业（开关门、监视、出发确认、发车铃），快捷栏按钮、菜单、侧边栏与动作栏提示。
 *
 * <p>车掌坐车尾驾驶室，站台把停站交给他（{@link GuardLink}）；他开关门、回报车门，站台在等发车那一步问他是否还扣着。驾驶员那边的提示与铃声经 {@link
 * DriverSide} 转达。主线程入口都在服务器主线程调用；{@link #rewriterFor}、{@link #menuTopSize} 与 {@link #onInput}
 * 运行在网络线程。
 */
public final class GuardSessionManager {

  /** 驾驶员那边：找这列车的驾驶员、给他提示。由驾驶会话管理器实现。 */
  public interface DriverSide {
    /** 这列车上的驾驶员（人工或 ATO，按当前车名）；没有时为空。 */
    Optional<UUID> driverOf(String trainName);

    /** 这名驾驶员是否在 ATO。 */
    boolean driverAto(UUID driverId);

    /** 给驾驶员一条动作栏提示（停留一会儿，不被驾驶 HUD 覆盖），可带提示音。 */
    void notifyDriver(UUID driverId, String key, Map<String, String> values, DriveCue cue);

    /** 玩家是否在驾驶（驾驶员不能同时当车掌）。 */
    boolean isDriving(UUID playerId);

    /** 快捷栏数据包改写已就绪（BKCommonLib 监听注册成功）。 */
    boolean hotbarReady();

    /** 这列车的驾驶员亲手开着车门（人工驾驶）。 */
    boolean driverDoorsOpen(String trainName);

    /** 让调度立即重算这列车的信号（解除紧急停车后起步）。 */
    void refreshSignal(MinecartGroup group);

    /**
     * 车掌拉下紧急停车：人工驾驶的驾驶员的手柄拨到 EB（等同驾驶员自己拉 EB，不记防护介入），停稳后由驾驶员缓解。
     *
     * @return 这列车由人工驾驶的驾驶员控车、已拨到 EB
     */
    boolean emergencyByGuard(String trainName);

    /** 这列车有驾驶员（人工或 ATO）时车掌换端看的发车端（见 {@link GuardCabChange#fromDriver}）；没有驾驶员时为空。 */
    Optional<GuardCabChange.Outlook> driverOutlook(MinecartGroup group);

    /** 列车停在终点站待命（登记了待命、派车还没放行）。 */
    boolean atLayover(String trainName);

    /** 终点站待命的列车下一趟由哪一端发车（要查线路图）；分不出时为 {@link CabSeats.Departure#EITHER}。 */
    CabSeats.Departure layoverDeparture(MinecartGroup group);

    /**
     * 车掌被直接送进要换到的那一端（换端超时、点了传送入座）：驾驶员也在换端、要去另一头时一起送进发车端。驾驶员坐在车掌要去的那一端时先请下来，车掌入座后再送驾驶员。
     *
     * @param guardEnd 车掌要换到的那一端
     * @param seatGuard 送车掌入座
     * @return 车掌是否已入座
     */
    boolean moveWithGuard(String trainName, CabSeats.End guardEnd, BooleanSupplier seatGuard);

    /** 列车此刻跑的车次（按时刻表的列车分配）；不按时刻表运行时为空。 */
    Optional<TaskKey> tripOf(String trainName);

    /** 车次的交路代码；查不到时为空串。 */
    String routeCodeOf(TaskKey key);

    /**
     * 发车掌一趟的奖励，并在成绩单后说明发了多少（玩家不在线时只发钱币、不说明）。
     *
     * @param forfeited 判为未完成：不发，只说明不发
     */
    void payGuard(UUID playerId, String playerName, DriveRewards.Reward reward, boolean forfeited);

    /** 写一条驾驶记录（后台写库）。 */
    void saveRecord(DriveTaskRecord record);

    /** 这名驾驶员收到发车信号后要不要回一短：simulation 级人工驾驶。 */
    boolean ackRequired(UUID driverId);

    /** 驾驶员没在时限内回一短：记一次漏确认。 */
    void missedAck(UUID driverId);
  }

  /** 上岗的结果。 */
  public enum StartOutcome {
    STARTED,
    DISABLED,
    UNAVAILABLE,
    ALREADY_ON_DUTY,
    DRIVING,
    BAD_GAME_MODE,
    NOT_SEATED,
    NOT_MANAGED,
    NOT_TAIL_CAB,
    GUARD_PRESENT,
    CURSOR_NOT_EMPTY,
    /** 驾驶员亲手开着车门：等他关好再上岗，否则车门由谁回报会乱。 */
    DRIVER_DOORS_OPEN
  }

  /** 异常情况报告的原因。 */
  public enum IncidentReason {
    /** 夹人夹物。 */
    CAUGHT,
    /** 上下车拥挤。 */
    CROWDED,
    /** 乘客求助。 */
    PASSENGER,
    /** 设备异常。 */
    EQUIPMENT
  }

  /** 动作栏提示停留多久（tick），不被常驻提示覆盖。 */
  private static final long NOTICE_HOLD_TICKS = 40L;

  /** 侧边栏与常驻提示的刷新间隔。 */
  private static final int DISPLAY_INTERVAL_TICKS = 10;

  /** 右键按住时客户端连续发包：相隔超过这么久才算新按下（发车铃以外的按钮按一下只算一次）。 */
  private static final long FRESH_PRESS_GAP_TICKS = 6L;

  /** 结束值乘的第一次点击之后，多久内再点一次才结束。 */
  private static final long END_CONFIRM_TICKS = 60L;

  private final Plugin plugin;
  private final DriverControlRegistry registry;
  private final DriveSounds sounds;
  private final LocaleManager locale;
  private final Supplier<DriveConfig> config;
  private final Supplier<ConfigManager.AutoStationSettings> chime;
  private final DriverSide drivers;
  private final DriveSidebar sidebar;
  private final Map<UUID, GuardSession> sessions = new ConcurrentHashMap<>();
  private final Map<UUID, Long> lastUseTick = new ConcurrentHashMap<>();
  private final Map<UUID, BuzzerPress> driverPresses = new ConcurrentHashMap<>();
  private final PendingAcks pendingAcks = new PendingAcks();
  private BukkitTask task;
  private long tickCounter;
  private GuardExaminer examiner = GuardExaminer.NONE;

  public GuardSessionManager(
      Plugin plugin,
      DriverControlRegistry registry,
      DriveSounds sounds,
      LocaleManager locale,
      Supplier<DriveConfig> config,
      Supplier<ConfigManager.AutoStationSettings> chime,
      DriverSide drivers) {
    this.plugin = Objects.requireNonNull(plugin, "plugin");
    this.registry = Objects.requireNonNull(registry, "registry");
    this.sounds = Objects.requireNonNull(sounds, "sounds");
    this.locale = Objects.requireNonNull(locale, "locale");
    this.config = Objects.requireNonNull(config, "config");
    this.chime = Objects.requireNonNull(chime, "chime");
    this.drivers = Objects.requireNonNull(drivers, "drivers");
    this.sidebar = new DriveSidebar(locale);
  }

  /** 接上车掌考法的考官（驾驶证服务）。 */
  public void setExaminer(GuardExaminer examiner) {
    this.examiner = examiner == null ? GuardExaminer.NONE : examiner;
  }

  // ---- 查询 ----

  /** 玩家是否在当车掌。 */
  public boolean isOnDuty(UUID playerId) {
    return playerId != null && sessions.containsKey(playerId);
  }

  public Optional<GuardSession> sessionOf(UUID playerId) {
    return Optional.ofNullable(playerId == null ? null : sessions.get(playerId));
  }

  /** 有车掌在值乘。 */
  public boolean anyOnDuty() {
    return !sessions.isEmpty();
  }

  /** 全部值乘中的车掌。 */
  public List<GuardSession> sessions() {
    return new ArrayList<>(sessions.values());
  }

  /** 这列车上的车掌（按当前车名）。 */
  public Optional<GuardSession> guardOfTrain(String trainName) {
    return registry
        .guardOfName(trainName)
        .map(link -> sessions.get(link.playerId()))
        .filter(Objects::nonNull);
  }

  // ---- 网络线程入口 ----

  /** 车掌的快捷栏改写规则；不是车掌时为 {@code null}。 */
  public HotbarRewriter<ItemStack> rewriterFor(UUID playerId) {
    GuardSession session = sessions.get(playerId);
    return session == null ? null : session.rewriter();
  }

  /** 车掌打开着的菜单上半部分的槽位数；没有打开时为 0。 */
  public int menuTopSize(UUID playerId) {
    GuardSession session = sessions.get(playerId);
    return session == null ? 0 : session.menuTopSize();
  }

  /** 车掌的按键（数据包层截获）。 */
  public void onInput(Player player, InputSignal signal) {
    UUID id = player.getUniqueId();
    switch (signal) {
      case SWAP_HANDS -> Bukkit.getScheduler().runTask(plugin, () -> openMenu(player));
      case USE -> {
        long now = Bukkit.getCurrentTick();
        Long previous = lastUseTick.put(id, now);
        boolean fresh = previous == null || now - previous > FRESH_PRESS_GAP_TICKS;
        Bukkit.getScheduler().runTask(plugin, () -> onUse(player, fresh));
      }
      default -> Bukkit.getScheduler().runTask(plugin, player::updateInventory);
    }
  }

  // ---- 上岗与离岗 ----

  /** 让坐在车尾驾驶室的玩家上岗当车掌。 */
  public StartOutcome start(Player player) {
    DriveConfig current = config.get();
    UUID id = player.getUniqueId();
    if (!current.guard().enabled()) {
      return StartOutcome.DISABLED;
    }
    if (!drivers.hotbarReady()) {
      return StartOutcome.UNAVAILABLE;
    }
    if (sessions.containsKey(id)) {
      return StartOutcome.ALREADY_ON_DUTY;
    }
    if (drivers.isDriving(id)) {
      return StartOutcome.DRIVING;
    }
    if (!gameModeAllowed(player.getGameMode(), current)) {
      return StartOutcome.BAD_GAME_MODE;
    }
    // 光标上有物品时关闭界面会把它塞回背包：车掌的快捷栏与驾驶员一样对背包只读，拒绝上岗。
    if (!player.getItemOnCursor().getType().isAir()) {
      return StartOutcome.CURSOR_NOT_EMPTY;
    }
    Optional<SeatBinding> seat = SeatLocator.locate(player);
    Optional<MinecartGroup> group = seat.flatMap(s -> SeatLocator.findGroup(s.trainName()));
    if (seat.isEmpty() || group.isEmpty()) {
      return StartOutcome.NOT_SEATED;
    }
    MinecartGroup train = group.get();
    if (!ManagedTrains.isFtaManaged(train.getProperties())) {
      return StartOutcome.NOT_MANAGED;
    }
    CabSeats cabs = SeatLocator.cabSeats(train, current.driver().cabSeatNames());
    if (!guardCab(cabs.endOf(seat.get()), train.size())) {
      return StartOutcome.NOT_TAIL_CAB;
    }
    if (registry.guardOf(train.getProperties()).isPresent()) {
      return StartOutcome.GUARD_PRESENT;
    }
    if (drivers.driverDoorsOpen(train.getProperties().getTrainName())) {
      return StartOutcome.DRIVER_DOORS_OPEN;
    }
    Optional<CabSeatKey> key = CabSeatKey.of(train, seat.get());
    if (key.isEmpty()) {
      return StartOutcome.NOT_SEATED;
    }
    GuardLink link =
        new GuardLink(
            id,
            seat.get().trainName(),
            train.getProperties(),
            current.guard(),
            Bukkit::getCurrentTick);
    registry.bindGuard(train.getProperties(), link);
    GuardSession session =
        new GuardSession(
            id,
            player.getName(),
            link,
            key.get(),
            seat.get(),
            new BuzzerPress(current.guard().buzzerLongTicks(), current.guard().buzzerDoubleTicks()),
            Bukkit.getCurrentTick());
    TaskKey trip = drivers.tripOf(seat.get().trainName()).orElse(null);
    session.setTrip(
        new GuardTrip(trip, trip == null ? "" : drivers.routeCodeOf(trip), Instant.now()));
    sessions.put(id, session);
    refreshHotbar(player, session, true);
    ensureTask();
    drivers
        .driverOf(link.trainName())
        .ifPresent(
            driver ->
                drivers.notifyDriver(
                    driver,
                    "drive.guard.driver.on-duty",
                    Map.of("guard", player.getName()),
                    DriveCue.BUZZER));
    return StartOutcome.STARTED;
  }

  /** 车掌所坐的这一端能不能当车掌座：车尾端的驾驶室；单节车两头在同一节车里，坐另一头的驾驶室即可。 */
  static boolean guardCab(CabSeats.End end, int memberCount) {
    return end == CabSeats.End.TAIL || (memberCount <= 1 && end == CabSeats.End.HEAD);
  }

  static boolean gameModeAllowed(GameMode mode, DriveConfig current) {
    return mode == GameMode.SURVIVAL
        || mode == GameMode.ADVENTURE
        || (mode == GameMode.CREATIVE && current.allowCreativeMode());
  }

  /** 结束值乘：车掌开着的门随之关上，车门交还驾驶员或站台。 */
  public void stop(UUID playerId, GuardSession.EndReason reason) {
    GuardSession session = sessions.remove(playerId);
    if (session == null) {
      return;
    }
    lastUseTick.remove(playerId);
    Player gone = Bukkit.getPlayer(playerId);
    // 已做完的站按结束原因结算：中途离开、被撤下的按做过的站给，连续超时、漏乘、换端没坐进车尾的判为未完成。
    recordSettled(gone, session);
    flushExamStop(gone, session);
    examiner.onDutyEnded(playerId, reason);
    settleTrip(gone, session, session.trip(), GuardTrip.stateFor(reason));
    Optional<MinecartGroup> train = findGroup(session);
    boolean held = session.link().holdsTrain();
    session.link().releaseEmergency();
    session.link().setCabHold(false);
    session.link().setTurnbackPending(false);
    session.cabChange().cancel();
    registry.unbindGuard(session.link());
    train.ifPresent(group -> session.doors().closeAll(session));
    if (held) {
      // 车掌离岗时扣着的紧急停车与换端扣车一并解除，列车交还自动运行。
      train.ifPresent(drivers::refreshSignal);
    }
    Player player = Bukkit.getPlayer(playerId);
    if (player != null) {
      sidebar.hide(player);
      if (session.menuTopSize() > 0) {
        player.closeInventory();
      }
      player.updateInventory();
      player.sendMessage(
          locale.component(
              "drive.guard.end."
                  + reason.name().toLowerCase(java.util.Locale.ROOT).replace('_', '-'),
              Map.of(
                  "stops",
                  String.valueOf(session.link().completedStops()),
                  "timeouts",
                  String.valueOf(session.link().timeoutStops()))));
    } else {
      sidebar.forget(playerId);
    }
    drivers
        .driverOf(session.link().trainName())
        .ifPresent(
            driver ->
                drivers.notifyDriver(
                    driver,
                    "drive.guard.driver.off-duty",
                    Map.of("guard", session.playerName()),
                    null));
    // 车掌离岗：驾驶员不必再回这一声。
    drivers.driverOf(session.link().trainName()).ifPresent(pendingAcks::forget);
    if (sessions.isEmpty() && task != null) {
      task.cancel();
      task = null;
    }
  }

  /** 全部车掌离岗（插件停用、车掌功能被关掉）。 */
  public void stopAll(GuardSession.EndReason reason) {
    for (UUID id : new ArrayList<>(sessions.keySet())) {
      stop(id, reason);
    }
    sidebar.hideAll();
  }

  /** 重载配置：之后的停站按新的时限；车掌功能被关掉时全部离岗。 */
  public void reload() {
    DriveConfig current = config.get();
    if (!current.guard().enabled()) {
      stopAll(GuardSession.EndReason.DISABLED);
      return;
    }
    for (GuardSession session : sessions.values()) {
      session.link().setConfig(current.guard());
    }
  }

  private void ensureTask() {
    if (task == null) {
      task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }
  }

  // ---- 每 tick ----

  void tick() {
    long now = Bukkit.getCurrentTick();
    tickCounter++;
    for (GuardSession session : new ArrayList<>(sessions.values())) {
      try {
        tick(session, now);
      } catch (RuntimeException ex) {
        plugin.getLogger().warning("车掌值乘处理出错 " + session.playerName() + ": " + ex);
      }
    }
    tickDriverPresses(now);
    tickAcks(now);
  }

  private void tick(GuardSession session, long now) {
    Player player = Bukkit.getPlayer(session.playerId());
    if (player == null || !player.isOnline()) {
      stop(session.playerId(), GuardSession.EndReason.OFFLINE);
      return;
    }
    Optional<MinecartGroup> groupOpt = findGroup(session);
    if (groupOpt.isEmpty() || !updateBinding(session, groupOpt.get())) {
      stop(session.playerId(), GuardSession.EndReason.TRAIN_GONE);
      return;
    }
    MinecartGroup group = groupOpt.get();
    if (tickCabChange(player, session, group, now)) {
      return;
    }
    boolean seated = seated(player, session, group);
    if (!seated && player.getVehicle() == null && group.isMoving() && leftBehind(player, group)) {
      // 车开走了车掌还在站台上（传送回座没成）：值乘结束，车门交还驾驶员或站台。
      stop(session.playerId(), GuardSession.EndReason.LEFT_BEHIND);
      return;
    }
    // 开着门换到另一端：预留座位改了，车掌面朝的方向反了，左右车门的记录跟着对调（TrainCarts 晚一拍才让人坐下也照样对调）。
    session.doors().followCab(group, session);
    stationWork(player, session, group, seated, now);
    if (!sessions.containsKey(session.playerId())) {
      return;
    }
    trackTrip(player, session, group, now);
    if (session.link().emergencyExpired()) {
      releaseEmergency(player, session, group, "drive.guard.emergency.expired");
    }
    if (now % WATCH_SAMPLE_TICKS == 0) {
      sampleDepartureWatch(player, session, group, seated, now);
    }
    if (!sessions.containsKey(session.playerId())) {
      return;
    }
    session.buzzer().tick(now).ifPresent(kind -> onGuardBuzzer(player, session, kind, seated));
    refreshHotbar(player, session, false);
    if (tickCounter % DISPLAY_INTERVAL_TICKS == 0) {
      GuardDisplay.Snapshot snapshot = snapshot(session, group, seated, now);
      sidebar.update(
          player, "drive.guard.sidebar.title", snapshot.train(), GuardDisplay.rows(snapshot));
      if (now >= session.noticeUntilTick()) {
        GuardDisplay.Line line = GuardDisplay.prompt(snapshot);
        player.sendActionBar(locale.component(line.key(), line.values()));
      }
    }
  }

  /** 推进这一站的作业：回报车门、计时、到时限代开代关、发车信号转告驾驶员、列车开出后结算。 */
  private void stationWork(
      Player player, GuardSession session, MinecartGroup group, boolean seated, long now) {
    GuardLink link = session.link();
    Optional<DriverStationStop> stopOpt = link.stationStop();
    if (stopOpt.isPresent()) {
      DriverStationStop stop = stopOpt.get();
      if (stop != session.trackedStop()) {
        // 新的一站：这一站的提示与超时处理从头来。
        session.setTrackedStop(stop);
        session.setTrackedTrip(drivers.tripOf(group.getProperties().getTrainName()).orElse(null));
        session.setLastPhase(null);
        session.setSignalAnnounced(false);
        session.setForcedSignalHandled(false);
      }
      if (stop.takeHandedOverDoors()) {
        adoptStationDoors(session, group, stop);
      }
      DriverDoorSide side = DriverDoorSide.required(stop, DriveDoors.cabFacing(group, session));
      boolean left = session.isLeftDoorOpen();
      boolean right = session.isRightDoorOpen();
      boolean closing = session.doorsClosing(now);
      // 关门动画放完前仍按车门开着报给站台：动画结束才进入等待发车。
      stop.reportDoors(
          side.satisfied(left, right), left || right || closing, side.wrong(left, right));
      session.doors().holdClosingWhilePending(session, now);
      GuardStopWork work = link.work().orElseThrow();
      if (side.wrong(left, right)) {
        work.markWrongDoor();
      }
      sampleClosingWatch(player, session, group, work, closing, seated, now);
      switch (work.tick(stop.phase())) {
        case FORCE_OPEN -> {
          openRequired(group, session, stop, side);
          notice(player, session, "drive.guard.timeout.open", Map.of());
        }
        case FORCE_CLOSE -> {
          closeAll(group, session, stop);
          notice(player, session, "drive.guard.timeout.close", Map.of());
        }
        case NONE -> {}
      }
      if (work.forcedSignal() && !session.forcedSignalHandled()) {
        session.setForcedSignalHandled(true);
        if (!seated) {
          SeatLocator.reseat(player, group, session.binding());
        }
        notice(player, session, "drive.guard.timeout.signal", Map.of());
      }
      if (work.released() && !session.signalAnnounced()) {
        session.setSignalAnnounced(true);
        announceDepartureSignal(player, session, work.forcedSignal());
      }
      if (stop.phase() != session.lastPhase()) {
        if (stop.phase() == Phase.OPEN_DOORS) {
          sounds.play(player, DriveCue.DOORS_RELEASED);
        }
        session.setLastPhase(stop.phase());
      }
      // 车掌开关门的自动运行列车：站台放行后不再跟着这一站，列车开出时由这里结束这一站。
      if (stop.phase() == Phase.DEPART
          && group.isMoving()
          && !registry.isDriverControlled(group.getProperties())) {
        stop.end();
      }
    }
    Optional<DriverStationStop> last = link.lastStop();
    if (last.isPresent() && !last.get().active() && session.lastSettledStop() != last.get()) {
      session.setLastSettledStop(last.get());
      link.work()
          .filter(GuardStopWork::released)
          .filter(work -> group.isMoving())
          .ifPresent(work -> startDepartureWatch(session, group, last.get(), work, now));
      link.settle();
      recordSettled(player, session);
      if (link.tooManyTimeouts()) {
        stop(session.playerId(), GuardSession.EndReason.TIMEOUTS);
      }
    }
  }

  private void announceDepartureSignal(Player player, GuardSession session, boolean forced) {
    sounds.play(player, DriveCue.DEPART);
    Optional<UUID> driver = drivers.driverOf(session.link().trainName());
    if (driver.isEmpty()) {
      return;
    }
    // simulation 级人工驾驶：驾驶员要在时限内回一短表示收到，到时没回记一次漏确认。
    boolean ack = drivers.ackRequired(driver.get());
    if (ack) {
      pendingAcks.expect(
          driver.get(), Bukkit.getCurrentTick() + config.get().guard().ackSeconds() * 20L);
    }
    String key = forced ? "drive.guard.driver.signal-forced" : "drive.guard.driver.signal";
    drivers.notifyDriver(
        driver.get(),
        ack ? key + "-ack" : key,
        Map.of(
            "guard",
            session.playerName(),
            "seconds",
            String.valueOf(config.get().guard().ackSeconds())),
        DriveCue.BUZZER);
  }

  /** 到时还没回一短的驾驶员：记一次漏确认并提示。 */
  private void tickAcks(long now) {
    if (pendingAcks.isEmpty()) {
      return;
    }
    for (UUID driver : pendingAcks.expired(now)) {
      drivers.missedAck(driver);
      drivers.notifyDriver(driver, "drive.guard.driver.ack-missed", Map.of(), null);
    }
  }

  /** 站台开着的车门交给车掌（停站中途上岗）：站台侧记成开着，之后按这一侧关门。 */
  private void adoptStationDoors(
      GuardSession session, MinecartGroup group, DriverStationStop stop) {
    DriverDoorSide side = DriverDoorSide.required(stop, DriveDoors.cabFacing(group, session));
    ConfigManager.AutoStationSettings settings = chime.get();
    if (side == DriverDoorSide.LEFT || side == DriverDoorSide.BOTH || side == DriverDoorSide.ANY) {
      session.doors().adoptOpen(group, session, true, settings, stop.doorCars());
    }
    if (side == DriverDoorSide.RIGHT || side == DriverDoorSide.BOTH) {
      session.doors().adoptOpen(group, session, false, settings, stop.doorCars());
    }
  }

  /** 到时限代开站台侧的门。 */
  private void openRequired(
      MinecartGroup group, GuardSession session, DriverStationStop stop, DriverDoorSide side) {
    ConfigManager.AutoStationSettings settings = chime.get();
    boolean left = side == DriverDoorSide.LEFT || side == DriverDoorSide.BOTH;
    boolean right = side == DriverDoorSide.RIGHT || side == DriverDoorSide.BOTH;
    if (side == DriverDoorSide.ANY) {
      left = true;
    }
    if (left && !session.isLeftDoorOpen()) {
      session.doors().toggle(group, session, true, settings, stop.doorCars());
    }
    if (right && !session.isRightDoorOpen()) {
      session.doors().toggle(group, session, false, settings, stop.doorCars());
    }
  }

  /** 关上开着的车门。 */
  private void closeAll(MinecartGroup group, GuardSession session, DriverStationStop stop) {
    ConfigManager.AutoStationSettings settings = chime.get();
    if (session.isLeftDoorOpen()) {
      session.doors().toggle(group, session, true, settings, stop.doorCars());
    }
    if (session.isRightDoorOpen()) {
      session.doors().toggle(group, session, false, settings, stop.doorCars());
    }
  }

  // ---- 按钮 ----

  private void onUse(Player player, boolean fresh) {
    GuardSession session = sessions.get(player.getUniqueId());
    if (session == null) {
      return;
    }
    Optional<GuardHotbar.Button> button =
        GuardHotbar.Button.fromSlot(player.getInventory().getHeldItemSlot());
    if (button.isEmpty()) {
      return;
    }
    long now = Bukkit.getCurrentTick();
    if (button.get() == GuardHotbar.Button.BUZZER) {
      session.buzzer().press(now);
      ringBuzzer(player, session.link().trainName());
      return;
    }
    if (!fresh) {
      return;
    }
    Optional<MinecartGroup> group = findGroup(session);
    if (group.isEmpty()) {
      return;
    }
    switch (button.get()) {
      case DOOR_LEFT -> openSide(player, session, group.get(), true);
      case DOOR_RIGHT -> openSide(player, session, group.get(), false);
      case DOOR_CLOSE -> closeDoors(player, session, group.get());
      case REPORT -> GuardReportDialog.open(plugin, locale, player, this);
      case CONFIRM -> confirm(player, session);
      case EMERGENCY -> emergency(player, session, group.get());
      case BUZZER -> {}
    }
  }

  private void openSide(Player player, GuardSession session, MinecartGroup group, boolean left) {
    Optional<DriverStationStop> stop = session.link().stationStop();
    if (stop.isEmpty() || group.isMoving() || !doorsReleasable(stop.get().phase())) {
      notice(player, session, "drive.guard.deny.doors-not-released", Map.of());
      return;
    }
    if (session.link().work().map(GuardStopWork::released).orElse(false)) {
      notice(player, session, "drive.guard.deny.signal-given", Map.of());
      return;
    }
    if (left ? session.isLeftDoorOpen() : session.isRightDoorOpen()) {
      notice(player, session, "drive.guard.deny.already-open", Map.of());
      return;
    }
    DriveDoors.Result result =
        session.doors().toggle(group, session, left, chime.get(), stop.get().doorCars());
    if (result == DriveDoors.Result.UNAVAILABLE) {
      notice(player, session, "drive.guard.deny.no-door-animation", Map.of());
    }
  }

  /** 车掌能开门的阶段：停妥以后、放行以前（关门后再开门也行，例如夹人夹物）。 */
  static boolean doorsReleasable(Phase phase) {
    return switch (phase) {
      case OPEN_DOORS, DWELL, CLOSE_DOORS, WAIT_DEPARTURE -> true;
      default -> false;
    };
  }

  private void closeDoors(Player player, GuardSession session, MinecartGroup group) {
    Optional<DriverStationStop> stop = session.link().stationStop();
    if (stop.isEmpty() || !session.anyDoorOpen()) {
      notice(player, session, "drive.guard.deny.no-door-open", Map.of());
      return;
    }
    session.link().work().ifPresent(work -> work.noteClosed(stop.get().phase()));
    closeAll(group, session, stop.get());
  }

  private void confirm(Player player, GuardSession session) {
    Optional<DriverStationStop> stop = session.link().stationStop();
    Optional<GuardStopWork> work = session.link().work();
    if (stop.isEmpty() || work.isEmpty()) {
      notice(player, session, "drive.guard.confirm.not-at-station", Map.of());
      return;
    }
    GuardStopWork.Confirm result = work.get().confirm(stop.get().phase());
    switch (result) {
      case CONFIRMED -> {
        notice(player, session, "drive.guard.confirm.confirmed", Map.of());
        sounds.play(player, DriveCue.SIGNAL_ACKNOWLEDGED);
      }
      case ALREADY -> notice(player, session, "drive.guard.confirm.already", Map.of());
      case DOORS_OPEN -> notice(player, session, "drive.guard.confirm.doors-open", Map.of());
      case EXIT_CLOSED -> notice(player, session, "drive.guard.confirm.exit-closed", Map.of());
      case RELEASED -> notice(player, session, "drive.guard.confirm.released", Map.of());
    }
  }

  /** 车掌的铃：一长发车、一短收到、两短呼叫。 */
  private void onGuardBuzzer(
      Player player, GuardSession session, BuzzerPress.Kind kind, boolean seated) {
    String train = session.link().trainName();
    switch (kind) {
      case LONG -> {
        if (session.link().emergencyHold()) {
          Optional<MinecartGroup> group = findGroup(session);
          if (group.isPresent() && !group.get().isMoving()) {
            releaseEmergency(player, session, group.get(), "drive.guard.emergency.released");
          } else {
            notice(player, session, "drive.guard.emergency.still-moving", Map.of());
          }
          return;
        }
        Optional<DriverStationStop> stop = session.link().stationStop();
        Optional<GuardStopWork> work = session.link().work();
        if (stop.isEmpty() || work.isEmpty()) {
          notice(player, session, "drive.guard.signal.not-at-station", Map.of());
          return;
        }
        GuardStopWork.Signal result = work.get().signal(stop.get().phase(), seated);
        switch (result) {
          case GIVEN -> notice(player, session, "drive.guard.signal.given", Map.of());
          case ALREADY -> notice(player, session, "drive.guard.signal.already", Map.of());
          case DOORS_OPEN -> notice(player, session, "drive.guard.signal.doors-open", Map.of());
          case NOT_CONFIRMED -> notice(
              player, session, "drive.guard.signal.not-confirmed", Map.of());
          case NOT_SEATED -> notice(player, session, "drive.guard.signal.not-seated", Map.of());
        }
      }
      case SHORT -> drivers
          .driverOf(train)
          .ifPresent(
              driver ->
                  drivers.notifyDriver(
                      driver,
                      "drive.guard.driver.ack",
                      Map.of("guard", session.playerName()),
                      null));
      case CALL -> drivers
          .driverOf(train)
          .ifPresentOrElse(
              driver ->
                  drivers.notifyDriver(
                      driver,
                      "drive.guard.driver.call",
                      Map.of("guard", session.playerName()),
                      DriveCue.BUZZER),
              () -> notice(player, session, "drive.guard.call.no-driver", Map.of()));
    }
  }

  /** 铃响一下：这列车上的车掌与驾驶员都听得到（驾驶室里的蜂鸣器）。 */
  private void ringBuzzer(Player presser, String trainName) {
    sounds.play(presser, DriveCue.BUZZER);
    drivers
        .driverOf(trainName)
        .map(Bukkit::getPlayer)
        .filter(driver -> driver != presser)
        .ifPresent(driver -> sounds.play(driver, DriveCue.BUZZER));
    guardOfTrain(trainName)
        .map(guard -> Bukkit.getPlayer(guard.playerId()))
        .filter(guard -> guard != presser)
        .ifPresent(guard -> sounds.play(guard, DriveCue.BUZZER));
  }

  /**
   * 驾驶员按了铃（丢弃键）：一短收到、两短呼叫车掌。
   *
   * @return 这列车上有车掌（铃响了）
   */
  public boolean driverBuzzer(Player driver, String trainName) {
    Optional<GuardSession> guard = guardOfTrain(trainName);
    if (guard.isEmpty()) {
      return false;
    }
    DriveConfig current = config.get();
    driverPresses
        .computeIfAbsent(
            driver.getUniqueId(),
            id ->
                new BuzzerPress(
                    current.guard().buzzerLongTicks(), current.guard().buzzerDoubleTicks()))
        .press(Bukkit.getCurrentTick());
    ringBuzzer(driver, trainName);
    return true;
  }

  private void tickDriverPresses(long now) {
    for (Map.Entry<UUID, BuzzerPress> entry : new ArrayList<>(driverPresses.entrySet())) {
      Optional<BuzzerPress.Kind> kind = entry.getValue().tick(now);
      if (kind.isEmpty()) {
        continue;
      }
      Player driver = Bukkit.getPlayer(entry.getKey());
      Optional<GuardSession> guard =
          sessions.values().stream()
              .filter(
                  session ->
                      drivers
                          .driverOf(session.link().trainName())
                          .filter(entry.getKey()::equals)
                          .isPresent())
              .findFirst();
      if (driver == null || guard.isEmpty()) {
        driverPresses.remove(entry.getKey());
        continue;
      }
      if (kind.get() == BuzzerPress.Kind.SHORT) {
        pendingAcks.acknowledge(entry.getKey());
      }
      Player guardPlayer = Bukkit.getPlayer(guard.get().playerId());
      if (guardPlayer == null) {
        continue;
      }
      String key =
          kind.get() == BuzzerPress.Kind.CALL
              ? "drive.guard.from-driver.call"
              : "drive.guard.from-driver.ack";
      notice(guardPlayer, guard.get(), key, Map.of("driver", driver.getName()));
    }
  }

  /**
   * 异常情况报告：当前这一步的时限延长一次。由报告对话框调用。
   *
   * @param reason 原因
   */
  public void report(Player player, IncidentReason reason) {
    GuardSession session = sessions.get(player.getUniqueId());
    if (session == null) {
      return;
    }
    Optional<GuardStopWork> work =
        session.link().stationStop().flatMap(stop -> session.link().work());
    if (work.isEmpty()) {
      notice(player, session, "drive.guard.report.not-at-station", Map.of());
      return;
    }
    if (!work.get().report()) {
      notice(player, session, "drive.guard.report.used-up", Map.of());
      return;
    }
    String reasonKey =
        "drive.guard.report.reason." + reason.name().toLowerCase(java.util.Locale.ROOT);
    String reasonText = locale.text(reasonKey);
    notice(
        player,
        session,
        "drive.guard.report.filed",
        Map.of(
            "reason",
            reasonText,
            "seconds",
            String.valueOf(config.get().guard().incidentExtensionSeconds())));
    drivers
        .driverOf(session.link().trainName())
        .ifPresent(
            driver ->
                drivers.notifyDriver(
                    driver,
                    "drive.guard.driver.report",
                    Map.of("guard", session.playerName(), "reason", reasonText),
                    DriveCue.BUZZER));
  }

  // ---- 紧急停车与监视 ----

  /** 监视每隔这么多 tick 采样一次。 */
  private static final long WATCH_SAMPLE_TICKS = 5L;

  /** 紧急停车（车掌阀）：人工驾驶的车按调度要求紧急制动处理，由驾驶员停稳后缓解；自动运行（含 ATO）的车立即停住并扣着，车掌停稳后按住发车铃一长声或到时限才解除。 */
  private void emergency(Player player, GuardSession session, MinecartGroup group) {
    if (session.link().emergencyHold()) {
      notice(player, session, "drive.guard.emergency.already", Map.of());
      return;
    }
    if (!group.isMoving()) {
      notice(player, session, "drive.guard.emergency.stopped", Map.of());
      return;
    }
    String train = session.link().trainName();
    Optional<UUID> driver = drivers.driverOf(train);
    if (!drivers.emergencyByGuard(train)) {
      session
          .link()
          .latchEmergency(Bukkit.getCurrentTick() + config.get().guard().emergencyHoldTicks());
      group.getActions().clear();
      group.stop();
    }
    sounds.play(player, DriveCue.EMERGENCY);
    notice(player, session, "drive.guard.emergency.pulled", Map.of());
    driver.ifPresent(
        id ->
            drivers.notifyDriver(
                id,
                "drive.guard.driver.emergency",
                Map.of("guard", session.playerName()),
                DriveCue.EMERGENCY));
  }

  private void releaseEmergency(
      Player player, GuardSession session, MinecartGroup group, String key) {
    session.link().releaseEmergency();
    drivers.refreshSignal(group);
    notice(player, session, key, Map.of());
    drivers
        .driverOf(session.link().trainName())
        .ifPresent(
            id ->
                drivers.notifyDriver(
                    id,
                    "drive.guard.driver.emergency-released",
                    Map.of("guard", session.playerName()),
                    null));
  }

  /** 关门监视：关门动画放着时每 {@link #WATCH_SAMPLE_TICKS} 采样一次，动画放完时告诉车掌合格与否。 */
  private void sampleClosingWatch(
      Player player,
      GuardSession session,
      MinecartGroup group,
      GuardStopWork work,
      boolean closing,
      boolean seated,
      long now) {
    if (closing) {
      session.setClosingWatchActive(true);
      if (now % WATCH_SAMPLE_TICKS == 0) {
        work.sampleClosing(closingWatching(player, session, group, seated));
      }
      return;
    }
    if (session.closingWatchActive()) {
      session.setClosingWatchActive(false);
      work.closingWatchPassed()
          .ifPresent(
              passed ->
                  notice(
                      player,
                      session,
                      passed ? "drive.guard.watch.closing-ok" : "drive.guard.watch.closing-missed",
                      Map.of()));
    }
  }

  private boolean closingWatching(
      Player player, GuardSession session, MinecartGroup group, boolean seated) {
    int index = session.binding().memberIndex();
    if (index < 0 || index >= group.size()) {
      return false;
    }
    org.bukkit.Location car = group.get(index).getEntity().getLocation();
    org.bukkit.Location eye = player.getEyeLocation();
    double distance =
        car.getWorld() != null && car.getWorld().equals(eye.getWorld())
            ? car.distance(eye)
            : Double.POSITIVE_INFINITY;
    GuardConfig guard = config.get().guard();
    return GuardWatch.closingWatch(
        seated || player.getVehicle() != null,
        distance,
        guard.watchRadiusBlocks(),
        eye.getDirection(),
        DriveDoors.cabFacing(group, session),
        guard.watchAngleDegrees());
  }

  /** 列车从站台开出：开始出站监视，直到车尾离开站台（走过车长加余量）或到时限。 */
  private void startDepartureWatch(
      GuardSession session,
      MinecartGroup group,
      DriverStationStop stop,
      GuardStopWork work,
      long now) {
    org.bukkit.Location head = group.head().getEntity().getLocation();
    org.bukkit.Location tail = group.tail().getEntity().getLocation();
    double length = head.getWorld() == tail.getWorld() ? head.distance(tail) + 2.0 : 0.0;
    GuardConfig guard = config.get().guard();
    org.bukkit.util.Vector platformSide =
        stop.platformFace().map(face -> face.getDirection()).orElse(null);
    session.setDepartureWatch(
        new GuardSession.DepartureWatch(
            work,
            head.toVector(),
            length + guard.departureWatchExtraBlocks(),
            now + guard.departureWatchMaxSeconds() * 20L,
            platformSide));
  }

  private void sampleDepartureWatch(
      Player player, GuardSession session, MinecartGroup group, boolean seated, long now) {
    GuardSession.DepartureWatch watch = session.departureWatch();
    if (watch == null) {
      return;
    }
    double travelled = group.head().getEntity().getLocation().toVector().distance(watch.origin());
    if (travelled >= watch.blocks() || now >= watch.untilTick()) {
      session.setDepartureWatch(null);
      flushExamStop(player, session);
      watch
          .work()
          .departureWatchPassed()
          .ifPresent(
              passed ->
                  notice(
                      player,
                      session,
                      passed
                          ? "drive.guard.watch.departure-ok"
                          : "drive.guard.watch.departure-missed",
                      Map.of()));
      return;
    }
    watch
        .work()
        .sampleDeparture(
            GuardWatch.departureWatch(
                seated,
                player.getEyeLocation().getDirection(),
                watch.platformSide(),
                DriveDoors.cabFacing(group, session)));
  }

  // ---- 菜单 ----

  private void openMenu(Player player) {
    GuardSession session = sessions.get(player.getUniqueId());
    if (session == null || !player.isOnline() || session.menuTopSize() > 0) {
      return;
    }
    Inventory inventory = GuardMenu.create(player.getUniqueId(), locale, menuView(session));
    session.setMenuTopSize(GuardMenu.SIZE);
    if (player.openInventory(inventory) == null) {
      session.setMenuTopSize(0);
    }
  }

  private GuardMenu.View menuView(GuardSession session) {
    boolean endConfirm = Bukkit.getCurrentTick() < session.endConfirmUntilTick();
    boolean reportUsed =
        session
            .link()
            .stationStop()
            .flatMap(stop -> session.link().work())
            .map(work -> !work.canReport())
            .orElse(false);
    return new GuardMenu.View(endConfirm, reportUsed);
  }

  /** 车掌点了菜单上半部分的一个槽位。由事件监听在取消点击之后调用。 */
  public void onMenuClick(Player player, Inventory top, int slot) {
    GuardSession session = sessions.get(player.getUniqueId());
    if (session == null) {
      return;
    }
    Optional<GuardMenu.Action> action = GuardMenu.actionAt(slot);
    if (action.isEmpty()) {
      return;
    }
    switch (action.get()) {
      case SEAT -> teleportToSeat(player, session);
      case CALL -> callDriver(player, session);
      case REPORT -> {
        player.closeInventory();
        GuardReportDialog.open(plugin, locale, player, this);
      }
      case END -> {
        long now = Bukkit.getCurrentTick();
        if (now < session.endConfirmUntilTick()) {
          player.closeInventory();
          stop(player.getUniqueId(), GuardSession.EndReason.COMMAND);
          return;
        }
        session.setEndConfirmUntilTick(now + END_CONFIRM_TICKS);
      }
    }
    if (sessions.containsKey(player.getUniqueId()) && GuardMenu.isMenu(top)) {
      GuardMenu.render(top, locale, menuView(session));
    }
  }

  /** 车掌菜单关上了。 */
  public void onMenuClosed(Player player) {
    sessionOf(player.getUniqueId()).ifPresent(session -> session.setMenuTopSize(0));
  }

  private void callDriver(Player player, GuardSession session) {
    ringBuzzer(player, session.link().trainName());
    onGuardBuzzer(player, session, BuzzerPress.Kind.CALL, true);
  }

  /** {@code /fta guard seat}：传送入座（换端途中送进要换到的那一端）。 */
  public void seat(Player player) {
    GuardSession session = sessions.get(player.getUniqueId());
    if (session == null) {
      player.sendMessage(locale.component("drive.guard.command.not-on-duty"));
      return;
    }
    teleportToSeat(player, session);
  }

  /** 传送回自己的车掌座位：列车停着、座位空着时；换端途中送进要换到的那一端。 */
  private void teleportToSeat(Player player, GuardSession session) {
    Optional<MinecartGroup> group = findGroup(session);
    if (group.isEmpty() || group.get().isMoving()) {
      notice(player, session, "drive.guard.seat.moving", Map.of());
      return;
    }
    GuardCabChange change = session.cabChange();
    if (change.changing()) {
      // 换端途中：直接送进要换到的那一端，驾驶员也在换端时一起送。
      player.closeInventory();
      String car = String.valueOf(GuardCabChange.targetCar(change.target(), group.get().size()));
      if (moveGuard(player, session, group.get(), change.target())) {
        notice(player, session, "drive.guard.cab-change.moved", Map.of("car", car));
        notifyDriverOf(session, "drive.guard.driver.cab-changed", car);
      } else {
        notice(player, session, "drive.guard.cab-change.unavailable", Map.of("car", car));
      }
      return;
    }
    if (seated(player, session, group.get())) {
      notice(player, session, "drive.guard.seat.already", Map.of());
      return;
    }
    player.closeInventory();
    if (player.getVehicle() != null) {
      player.leaveVehicle();
    }
    if (!SeatLocator.reseat(player, group.get(), session.binding())) {
      notice(player, session, "drive.guard.seat.unavailable", Map.of());
    }
  }

  // ---- 成绩、奖励与记录 ----

  /** 一拍车头挪动超过这么远（格）不算里程：调头时车头换到另一端、传送。 */
  private static final double MAX_HEAD_STEP_BLOCKS = 4.0;

  /** 多久看一次列车换没换车次（tick）。 */
  private static final long TRIP_POLL_TICKS = 20L;

  /** 记里程；没在停站时看列车换没换车次，换了就把做过作业的这一趟结算掉（终点站折返开下一趟）。 */
  private void trackTrip(Player player, GuardSession session, MinecartGroup group, long now) {
    MinecartMember<?> head = group.head();
    if (head != null && head.getEntity() != null) {
      org.bukkit.Location at = head.getEntity().getLocation();
      session
          .trip()
          .addBlocks(
              session.moveHead(
                  at.getWorld() == null ? null : at.getWorld().getUID(),
                  at.toVector(),
                  MAX_HEAD_STEP_BLOCKS));
    }
    if (now % TRIP_POLL_TICKS != 0 || session.link().stationStop().isPresent()) {
      return;
    }
    TaskKey current = drivers.tripOf(group.getProperties().getTrainName()).orElse(null);
    if (session.trip().endedBy(current)) {
      switchTrip(player, session, current);
    }
  }

  /** 结算过的站记进这一趟：站开始时列车跑的车次与这一趟不同，先把这一趟结算掉。 */
  private void recordSettled(Player player, GuardSession session) {
    for (GuardLink.Settled settled : session.link().drainSettled()) {
      TaskKey key =
          settled.stop() == session.trackedStop()
              ? session.trackedTrip()
              : session.trip().key().orElse(null);
      switchTrip(player, session, key);
      String station = settled.stop().stationName();
      session.trip().addStop(station, settled.work());
      if (examiner.examining(session.playerId())) {
        session.trip().markExamined();
        GuardSession.DepartureWatch watch = session.departureWatch();
        session.setPendingExamStop(new GuardSession.WorkedStop(station, settled.work()));
        if (watch == null || watch.work() != settled.work()) {
          flushExamStop(player, session);
        }
      }
    }
  }

  /** 考试中做完的一站交给考官（出站监视采完、或值乘结束时）。 */
  private void flushExamStop(Player player, GuardSession session) {
    GuardSession.WorkedStop pending = session.takePendingExamStop();
    if (pending != null && player != null && player.isOnline()) {
      examiner.onStopWorked(player, GuardScore.Stop.of(pending.station(), pending.work()));
    }
  }

  /** 列车换了车次：做过作业的这一趟按开完结算，换成新的一趟。 */
  private void switchTrip(Player player, GuardSession session, TaskKey key) {
    GuardTrip trip = session.trip();
    if (Objects.equals(trip.key().orElse(null), key)) {
      return;
    }
    settleTrip(player, session, trip, DriverTask.State.COMPLETED);
    session.setTrip(new GuardTrip(key, key == null ? "" : drivers.routeCodeOf(key), Instant.now()));
  }

  /**
   * 结算一趟：评级、成绩单、发奖励、写记录（mode GUARD）。没做过作业的不结算；不按时刻表运行的只给成绩，不记录、不发奖励。
   *
   * @param player 车掌；不在线时为 {@code null}（照常发钱币、写记录）
   */
  private void settleTrip(
      Player player, GuardSession session, GuardTrip trip, DriverTask.State state) {
    if (!trip.hasStops()) {
      return;
    }
    GuardScore score = trip.score();
    ScoreRules.Result result = score.evaluate(state != DriverTask.State.FAILED);
    DriveConfig current = config.get();
    String trainName =
        session.link().properties() != null && session.link().properties().getTrainName() != null
            ? session.link().properties().getTrainName()
            : session.link().trainName();
    Optional<TaskKey> key = trip.key();
    String tripCode = key.map(TaskKey::tripCode).orElse(trainName);
    Player online = player != null && player.isOnline() ? player : null;
    if (online != null) {
      showResult(online, tripCode, state, result, score, key.isPresent());
    }
    if (key.isEmpty()) {
      return;
    }
    if (trip.examined() && online != null) {
      online.sendMessage(locale.component("drive.guard.result-exam"));
    }
    DriveRewards.Reward reward =
        GuardTrip.rewarded(state) && !trip.examined()
            ? DriveRewards.guard(
                current.rewards(),
                current.guard().rewardStopRatio(),
                current.guard().rewardKmRatio(),
                score.stopCount(),
                trip.blocks(),
                result.grade())
            : DriveRewards.Reward.NONE;
    drivers.payGuard(
        session.playerId(),
        session.playerName(),
        reward,
        current.rewards().enabled() && state == DriverTask.State.FAILED);
    drivers.saveRecord(
        new DriveTaskRecord(
            UUID.randomUUID(),
            ServerIdentity.id().orElse(null),
            session.playerId(),
            session.playerName(),
            key.get().timetableId(),
            tripCode,
            key.get().serviceDate(),
            trip.routeCode(),
            trainName,
            DriveTaskRecord.MODE_GUARD,
            state.name(),
            result.points(),
            result.grade().name(),
            trip.startedAt(),
            Instant.now(),
            GuardRecordCodec.encode(score, trip.blocks())));
  }

  /** 一趟的大字评级与成绩单。 */
  private void showResult(
      Player player,
      String tripCode,
      DriverTask.State state,
      ScoreRules.Result result,
      GuardScore score,
      boolean tracked) {
    String stateText =
        locale.text("drive.task.state." + state.name().toLowerCase(java.util.Locale.ROOT));
    Map<String, String> values =
        Map.of(
            "trip",
            tripCode,
            "state",
            stateText,
            "points",
            String.valueOf(result.points()),
            "grade",
            result.grade().name());
    player.showTitle(
        net.kyori.adventure.title.Title.title(
            locale.component(
                "drive.grade." + result.grade().name().toLowerCase(java.util.Locale.ROOT)),
            locale.component("drive.guard.result-subtitle", values),
            net.kyori.adventure.title.Title.Times.times(
                java.time.Duration.ofMillis(250),
                java.time.Duration.ofSeconds(3),
                java.time.Duration.ofMillis(750))));
    if (state == DriverTask.State.COMPLETED) {
      sounds.play(player, DriveCue.TASK_COMPLETE);
    }
    player.sendMessage(locale.component("drive.guard.result", values));
    for (GuardDisplay.Line line : GuardDisplay.sheet(score)) {
      player.sendMessage(locale.component(line.key(), line.values()));
    }
    if (!tracked) {
      player.sendMessage(locale.component("drive.guard.result-untracked"));
    }
  }

  // ---- 终点站换端 ----

  /** 驾驶室座位的认定多久重读一次（tick）。 */
  private static final long CAB_SEATS_REFRESH_TICKS = 20L;

  /** 车掌离端车超过这么远（方块）就不直接送进去。 */
  private static final double CAB_MOVE_RANGE_BLOCKS = 64.0;

  /**
   * 推进一拍终点站换端（判定见 {@link GuardCabChange}），并管换端扣车：车上没有人工驾驶的驾驶员时，从终点站待命起扣着列车，派车放行时只调头、不发车，
   * 车掌坐进车尾端（或不必换）后交回自动运行发车。人工驾驶的车由驾驶员自己起步，车掌没换好就开车时直接送进去。
   *
   * @return 值乘是否已经结束
   */
  private boolean tickCabChange(
      Player player, GuardSession session, MinecartGroup group, long now) {
    GuardLink link = session.link();
    String trainName = group.getProperties().getTrainName();
    int size = group.size();
    boolean multi = size >= 2;
    Optional<UUID> driver = drivers.driverOf(trainName);
    boolean manual = driver.filter(id -> !drivers.driverAto(id)).isPresent();
    GuardCabChange.Outlook outlook =
        multi ? outlook(session, group, trainName) : GuardCabChange.Outlook.RUNNING;
    GuardCabChange change = session.cabChange();
    CabSeats.End reservedEnd = GuardCabChange.endOfCar(session.binding().memberIndex(), size);
    CabSeats.End wanted = GuardCabChange.guardEnd(outlook.departure());
    CabSeats.End seatedEnd = CabSeats.End.NONE;
    long reserveTicks = 0L;
    // 座位在哪一端要逐个看座位附件的名字：只在可能要换端时才读。
    if (multi && (change.changing() || (wanted != CabSeats.End.NONE && wanted != reservedEnd))) {
      seatedEnd = seatedEnd(player, session, group, now);
      reserveTicks =
          config.get().driver().cabChange().reserveSeconds(StopAlignment.bodyLengthBlocks(group))
              * 20L;
    }
    GuardCabChange.Event event =
        change.tick(
            new GuardCabChange.Input(
                multi, !group.isMoving(), outlook, reservedEnd, seatedEnd, now, reserveTicks));
    if (handleCabChange(player, session, group, event, now)) {
      return true;
    }
    boolean holdable = multi && !manual;
    link.setTurnbackPending(holdable && driver.isEmpty() && outlook.predicted());
    boolean hold = keepCabHold(holdable, outlook.predicted(), link.cabHold(), change.changing());
    if (link.setCabHold(hold) && !hold) {
      // 换好了（或不必换）：请调度层马上按自动运行发车。
      drivers.refreshSignal(group);
    }
    return false;
  }

  /**
   * 换端扣车这一拍之后是否还扣着：从终点站待命（派车还没放行）起扣，已在扣的到换端完成为止。
   *
   * @param holdable 车上没有人工驾驶的驾驶员、编组不止一节
   * @param predicted 派车还没放行
   * @param holding 此刻扣着
   * @param changing 车掌正在换端
   */
  static boolean keepCabHold(
      boolean holdable, boolean predicted, boolean holding, boolean changing) {
    return holdable && (predicted || (holding && changing));
  }

  /** 车掌换端看的发车端：有驾驶员时跟驾驶员；只有车掌时终点站待命按线路图预计（待命期间只查一次），其余时候车头端发车。 */
  private GuardCabChange.Outlook outlook(
      GuardSession session, MinecartGroup group, String trainName) {
    Optional<GuardCabChange.Outlook> fromDriver = drivers.driverOutlook(group);
    if (fromDriver.isPresent()) {
      session.setLayoverDeparture(null);
      return fromDriver.get();
    }
    if (!drivers.atLayover(trainName)) {
      session.setLayoverDeparture(null);
      return GuardCabChange.Outlook.RUNNING;
    }
    CabSeats.Departure departure = session.layoverDeparture();
    if (departure == null) {
      departure = drivers.layoverDeparture(group);
      session.setLayoverDeparture(departure);
    }
    return new GuardCabChange.Outlook(departure, true);
  }

  /**
   * 处理一拍换端的结果。
   *
   * @return 值乘是否已经结束
   */
  private boolean handleCabChange(
      Player player,
      GuardSession session,
      MinecartGroup group,
      GuardCabChange.Event event,
      long now) {
    GuardCabChange change = session.cabChange();
    String car = String.valueOf(GuardCabChange.targetCar(change.target(), group.size()));
    switch (event) {
      case ANNOUNCED -> {
        player.sendMessage(locale.component("drive.guard.cab-change.announce", Map.of("car", car)));
        player.sendMessage(locale.component("drive.guard.cab-change.offer", Map.of("car", car)));
      }
      case STARTED -> {
        player.sendMessage(
            locale.component(
                "drive.guard.cab-change.start",
                Map.of("car", car, "seconds", GuardDisplay.seconds(change.remainingTicks(now)))));
        player.sendMessage(locale.component("drive.guard.cab-change.offer", Map.of("car", car)));
        notifyDriverOf(session, "drive.guard.driver.cab-change", car);
      }
      case COMPLETED -> {
        adoptSeat(player, session, group);
        notice(player, session, "drive.guard.cab-change.done", Map.of("car", car));
        notifyDriverOf(session, "drive.guard.driver.cab-changed", car);
      }
      case TIMED_OUT -> {
        if (!moveGuard(player, session, group, change.target())) {
          stop(session.playerId(), GuardSession.EndReason.CAB_CHANGE);
          return true;
        }
        player.sendMessage(
            locale.component("drive.guard.cab-change.timeout-moved", Map.of("car", car)));
        notifyDriverOf(session, "drive.guard.driver.cab-changed", car);
      }
      case CANCELLED -> notice(player, session, "drive.guard.cab-change.cancelled", Map.of());
      case NONE -> {}
    }
    return false;
  }

  private void notifyDriverOf(GuardSession session, String key, String car) {
    drivers
        .driverOf(session.link().trainName())
        .ifPresent(
            driver ->
                drivers.notifyDriver(
                    driver, key, Map.of("guard", session.playerName(), "car", car), null));
  }

  /** 坐进了要换到的那一端：预留改到此刻所坐的座位。 */
  private static void adoptSeat(Player player, GuardSession session, MinecartGroup group) {
    String trainName = group.getProperties().getTrainName();
    SeatLocator.locate(player)
        .filter(found -> found.trainName().equals(trainName))
        .ifPresent(
            found -> CabSeatKey.of(group, found).ifPresent(key -> session.moveSeat(key, found)));
  }

  /** 车掌此刻所坐的座位在这列车的哪一端；不在这列车的驾驶室里时为 {@link CabSeats.End#NONE}。 */
  private CabSeats.End seatedEnd(
      Player player, GuardSession session, MinecartGroup group, long now) {
    String trainName = group.getProperties().getTrainName();
    return SeatLocator.locate(player)
        .filter(found -> found.trainName().equals(trainName))
        .map(found -> cabSeats(session, group, now).endOf(found))
        .orElse(CabSeats.End.NONE);
  }

  /** 驾驶室座位：同一编组、同样节数时每秒最多重读一次（挂上或摘下车厢时马上重读）。 */
  private CabSeats cabSeats(GuardSession session, MinecartGroup group, long now) {
    GuardSession.CabSeatsMemo memo = session.cabSeatsMemo();
    if (memo != null
        && memo.group() == group
        && memo.size() == group.size()
        && now - memo.tick() < CAB_SEATS_REFRESH_TICKS) {
      return memo.seats();
    }
    CabSeats seats = SeatLocator.cabSeats(group, config.get().driver().cabSeatNames());
    session.setCabSeatsMemo(new GuardSession.CabSeatsMemo(group, group.size(), now, seats));
    return seats;
  }

  /** 直接把车掌送进要换到的那一端（换端超时、点了传送入座）；驾驶员也在换端时一起送（见 {@link DriverSide#moveWithGuard}）。 */
  private boolean moveGuard(
      Player player, GuardSession session, MinecartGroup group, CabSeats.End end) {
    session.setMoving(true);
    try {
      return drivers.moveWithGuard(
          group.getProperties().getTrainName(),
          end,
          () -> moveGuardTo(player, session, group, end));
    } finally {
      session.setMoving(false);
    }
  }

  /**
   * 让车掌坐进某一端驾驶室的空座位，预留改到这个座位，换端完成。
   *
   * @return 是否已坐进去；离列车太远、不在同一世界、那一端没有空的驾驶座时为 {@code false}
   */
  private boolean moveGuardTo(
      Player player, GuardSession session, MinecartGroup group, CabSeats.End end) {
    MinecartMember<?> member =
        switch (end) {
          case HEAD -> group.head();
          case TAIL -> group.tail();
          case NONE -> null;
        };
    if (member == null
        || member.getEntity() == null
        || group.getWorld() == null
        || !group.getWorld().equals(player.getWorld())
        || member.getEntity().getLocation().distanceSquared(player.getLocation())
            > CAB_MOVE_RANGE_BLOCKS * CAB_MOVE_RANGE_BLOCKS) {
      return false;
    }
    int memberIndex = group.indexOf(member);
    CabSeats cabs = SeatLocator.cabSeats(group, config.get().driver().cabSeatNames());
    OptionalInt seat =
        SeatLocator.enterNearestFreeSeatIndex(
            player, member, index -> cabs.endOf(memberIndex, index) == end);
    if (seat.isEmpty()) {
      return false;
    }
    session.moveSeat(
        new CabSeatKey(member.getEntity().getUniqueId(), seat.getAsInt()),
        new SeatBinding(group.getProperties().getTrainName(), memberIndex, seat.getAsInt()));
    session.cabChange().finish();
    return true;
  }

  /**
   * 驾驶员被直接送进发车端驾驶室（准备时间不足、换端超时、点了直接换端）：车上的车掌预留的座位不在另一头时一起送过去。车掌坐在驾驶员要去的那一端时先请下来，
   * 驾驶员入座后再送车掌，两人不会抢同一个座位。
   *
   * @param driverEnd 驾驶员要去的那一端
   * @param seatDriver 送驾驶员入座
   * @return 驾驶员是否已入座
   */
  public boolean moveWithDriver(
      MinecartGroup group, CabSeats.End driverEnd, BooleanSupplier seatDriver) {
    CabSeats.End guardEnd = GuardCabChange.opposite(driverEnd);
    GuardSession session =
        group.size() < 2 || guardEnd == CabSeats.End.NONE
            ? null
            : guardOfTrain(group.getProperties().getTrainName()).orElse(null);
    Player player = session == null ? null : Bukkit.getPlayer(session.playerId());
    if (player == null
        || !updateBinding(session, group)
        || GuardCabChange.endOfCar(session.binding().memberIndex(), group.size()) == guardEnd) {
      return seatDriver.getAsBoolean();
    }
    session.setMoving(true);
    try {
      if (seatedEnd(player, session, group, Bukkit.getCurrentTick()) == driverEnd) {
        player.leaveVehicle();
      }
      boolean driverSeated = seatDriver.getAsBoolean();
      if (moveGuardTo(player, session, group, guardEnd)) {
        player.sendMessage(
            locale.component(
                "drive.guard.cab-change.moved-with-driver",
                Map.of("car", String.valueOf(GuardCabChange.targetCar(guardEnd, group.size())))));
      } else if (player.getVehicle() == null) {
        SeatLocator.reseat(player, group, session.binding());
      }
      return driverSeated;
    } finally {
      session.setMoving(false);
    }
  }

  /**
   * 预留座位：车掌值乘期间，别人（乘客、驾驶员）不能坐进车掌预留的座位；车掌换端时预留先让出来。
   *
   * @return 是否放行这次入座
   */
  public boolean allowSeatEnter(Entity entity, MinecartMember<?> member, int seatIndex) {
    if (sessions.isEmpty() || member == null || member.getEntity() == null || seatIndex < 0) {
      return true;
    }
    UUID car = member.getEntity().getUniqueId();
    UUID entering = entity == null ? null : entity.getUniqueId();
    for (GuardSession session : sessions.values()) {
      if (!blocksSeat(
          session.seat(),
          session.seatReleased(),
          session.playerId(),
          new CabSeatKey(car, seatIndex),
          entering)) {
        continue;
      }
      if (entity instanceof Player player) {
        player.sendActionBar(
            locale.component("drive.guard.seat.reserved", Map.of("guard", session.playerName())));
      }
      return false;
    }
    return true;
  }

  /**
   * 车掌预留的座位拦不拦这次入座：预留没让出来、正是这个座位、进来的不是车掌本人。
   *
   * @param reserved 车掌预留的座位
   * @param released 预留此刻让出来了（换端中）
   * @param guardId 车掌
   * @param seat 要坐的座位
   * @param entering 要坐进来的实体；不明时为 {@code null}
   */
  static boolean blocksSeat(
      CabSeatKey reserved, boolean released, UUID guardId, CabSeatKey seat, UUID entering) {
    return !released && reserved.equals(seat) && !guardId.equals(entering);
  }

  // ---- 离座与入座 ----

  /** 潜行键状态变化（输入事件）。 */
  public void noteSneakInput(UUID playerId, boolean sneaking) {
    sessionOf(playerId).ifPresent(session -> session.setSneakHeld(sneaking));
  }

  /** 原版下车事件上的同一道判定：只管潜行键此刻按着的车掌，插件自己挪座位不受影响。 */
  public boolean allowDismount(Player player) {
    GuardSession session = sessions.get(player.getUniqueId());
    return session == null || !session.sneakHeld() || allowSeatExit(player);
  }

  /** 车掌按 Shift 离座：停稳且车门开着时放行（下到站台监视），行驶中、车门关着时拦下。 */
  public boolean allowSeatExit(Player player) {
    GuardSession session = sessions.get(player.getUniqueId());
    if (session == null) {
      return true;
    }
    Optional<MinecartGroup> group = findGroup(session);
    if (group.isEmpty()) {
      return true;
    }
    if (group.get().isMoving()) {
      player.sendMessage(locale.component("drive.guard.seat-exit.moving"));
      return false;
    }
    // 换端途中：车门关着也可以下车走到另一端。
    if (!session.anyDoorOpen() && !session.cabChange().changing()) {
      notice(player, session, "drive.guard.seat-exit.doors-closed", Map.of());
      return false;
    }
    return true;
  }

  /**
   * 车掌在站台上右键自己的列车：回到预留的车掌座位。
   *
   * @return 已处理（是车掌、点的是自己的列车）
   */
  public boolean onEntityClick(Player player, Entity clicked) {
    GuardSession session = sessions.get(player.getUniqueId());
    if (session == null || player.getVehicle() != null || clicked == null) {
      return session != null;
    }
    MinecartMember<?> member = MinecartMemberStore.getFromEntity(clicked);
    MinecartGroup group = member == null ? null : member.getGroup();
    Optional<MinecartGroup> own = findGroup(session);
    if (group == null || own.isEmpty() || own.get() != group) {
      return true;
    }
    Bukkit.getScheduler()
        .runTask(
            plugin,
            () -> {
              if (sessions.get(player.getUniqueId()) != session || player.getVehicle() != null) {
                return;
              }
              GuardCabChange change = session.cabChange();
              if (change.changing()) {
                // 换端途中：点的是要换到的那一端的车厢才坐进去，否则提示去第几节。
                MinecartMember<?> target =
                    change.target() == CabSeats.End.TAIL ? group.tail() : group.head();
                String car =
                    String.valueOf(GuardCabChange.targetCar(change.target(), group.size()));
                if (target == member && moveGuardTo(player, session, group, change.target())) {
                  notice(player, session, "drive.guard.cab-change.done", Map.of("car", car));
                  notifyDriverOf(session, "drive.guard.driver.cab-changed", car);
                } else {
                  notice(player, session, "drive.guard.cab-change.go-to", Map.of("car", car));
                }
                return;
              }
              if (!SeatLocator.reseat(player, group, session.binding())) {
                notice(player, session, "drive.guard.seat.unavailable", Map.of());
              }
            });
    return true;
  }

  // ---- 内部 ----

  private Optional<MinecartGroup> findGroup(GuardSession session) {
    GuardLink link = session.link();
    MinecartGroup cached = session.lastGroup();
    // 每 tick 都要找：编组还有效、还是同一份列车属性时直接用，不必扫一遍全服编组。
    if (cached != null && cached.isValid() && cached.getProperties() == link.properties()) {
      return Optional.of(cached);
    }
    Optional<MinecartGroup> found = Optional.empty();
    if (link.properties() != null && link.properties().getTrainName() != null) {
      found = SeatLocator.findGroup(link.properties().getTrainName());
    }
    if (found.isEmpty()) {
      found = SeatLocator.findGroup(link.trainName());
    }
    session.setLastGroup(found.orElse(null));
    return found;
  }

  /** 车掌离列车超过这么远（方块）且列车在开，算漏乘。 */
  private static final double LEFT_BEHIND_BLOCKS = 48.0;

  /** 车掌不在列车所在的世界，或离每节车厢都超过 {@link #LEFT_BEHIND_BLOCKS}。 */
  private static boolean leftBehind(Player player, MinecartGroup group) {
    org.bukkit.Location at = player.getLocation();
    double limit = LEFT_BEHIND_BLOCKS * LEFT_BEHIND_BLOCKS;
    for (int index = 0; index < group.size(); index++) {
      MinecartMember<?> member = group.get(index);
      if (member == null) {
        continue;
      }
      org.bukkit.Location car = member.getEntity().getLocation();
      if (car.getWorld() != null
          && car.getWorld().equals(at.getWorld())
          && car.distanceSquared(at) <= limit) {
        return false;
      }
    }
    return true;
  }

  /** 按车厢实体找回座位此刻在第几节车厢；车厢不在编组里了返回 false。 */
  private static boolean updateBinding(GuardSession session, MinecartGroup group) {
    String trainName = group.getProperties().getTrainName();
    for (int index = 0; index < group.size(); index++) {
      MinecartMember<?> member = group.get(index);
      if (member != null && member.getEntity().getUniqueId().equals(session.seat().member())) {
        SeatBinding binding = session.binding();
        if (binding.memberIndex() != index || !binding.trainName().equals(trainName)) {
          session.setBinding(new SeatBinding(trainName, index, session.seat().seatIndex()));
        }
        return true;
      }
    }
    return false;
  }

  private static boolean seated(Player player, GuardSession session, MinecartGroup group) {
    return SeatLocator.locate(player)
        .flatMap(binding -> CabSeatKey.of(group, binding))
        .filter(session.seat()::equals)
        .isPresent();
  }

  private GuardDisplay.Snapshot snapshot(
      GuardSession session, MinecartGroup group, boolean seated, long now) {
    Optional<UUID> driver = drivers.driverOf(session.link().trainName());
    boolean ato = driver.map(drivers::driverAto).orElse(false);
    Optional<String> driverName =
        driver.filter(id -> !drivers.driverAto(id)).map(Bukkit::getPlayer).map(Player::getName);
    Optional<GuardDisplay.StopState> stop =
        session
            .link()
            .stationStop()
            .flatMap(
                current ->
                    session
                        .link()
                        .work()
                        .map(
                            work ->
                                new GuardDisplay.StopState(
                                    current.stationName(),
                                    current.phase(),
                                    DriverDoorSide.required(
                                        current, DriveDoors.cabFacing(group, session)),
                                    session.isLeftDoorOpen(),
                                    session.isRightDoorOpen(),
                                    session.doorsClosing(now),
                                    work.remainingTicks(current.phase()),
                                    work.exitOpen(),
                                    work.confirmed(),
                                    work.released())));
    GuardCabChange change = session.cabChange();
    Optional<GuardDisplay.CabChangeState> cabChange =
        change.changing()
            ? Optional.of(
                new GuardDisplay.CabChangeState(
                    GuardCabChange.targetCar(change.target(), group.size()),
                    change.remainingTicks(now)))
            : Optional.empty();
    return new GuardDisplay.Snapshot(
        group.getProperties().getTrainName(),
        driverName,
        ato,
        stop,
        seated,
        session.link().timeoutStops(),
        cabChange);
  }

  private void refreshHotbar(Player player, GuardSession session, boolean force) {
    long now = Bukkit.getCurrentTick();
    Optional<GuardStopWork> work =
        session.link().stationStop().flatMap(stop -> session.link().work());
    GuardHotbar.ConfirmLamp lamp =
        work.map(
                w ->
                    w.confirmed()
                        ? GuardHotbar.ConfirmLamp.DONE
                        : w.exitOpen() ? GuardHotbar.ConfirmLamp.GO : GuardHotbar.ConfirmLamp.STOP)
            .orElse(GuardHotbar.ConfirmLamp.STOP);
    GuardHotbar.View view =
        new GuardHotbar.View(
            session.isLeftDoorOpen(),
            session.isRightDoorOpen(),
            session.doorsClosing(now),
            work.map(w -> !w.canReport()).orElse(false),
            lamp,
            session.buzzer().ringing(now),
            session.link().emergencyHold());
    if (!force && view.equals(session.view())) {
      return;
    }
    session.setHotbar(
        view, new HotbarRewriter<>(GuardHotbar.build(locale, view), session::menuTopSize));
    player.updateInventory();
  }

  private void notice(Player player, GuardSession session, String key, Map<String, String> values) {
    player.sendActionBar(locale.component(key, values));
    session.setNoticeUntilTick(Bukkit.getCurrentTick() + NOTICE_HOLD_TICKS);
  }
}
