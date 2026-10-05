package org.fetarute.fetaruteTCAddon.drive.license;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.function.Supplier;
import org.bukkit.entity.Player;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.DriverStationStop;
import org.fetarute.fetaruteTCAddon.drive.cab.CabFault;
import org.fetarute.fetaruteTCAddon.drive.cab.CabFaults;
import org.fetarute.fetaruteTCAddon.drive.driver.DriverDoorSide;
import org.fetarute.fetaruteTCAddon.drive.driver.DriverLink;
import org.fetarute.fetaruteTCAddon.drive.driver.task.DriverTaskManager;
import org.fetarute.fetaruteTCAddon.drive.dynamics.Notch;
import org.fetarute.fetaruteTCAddon.drive.session.DriveSession;
import org.fetarute.fetaruteTCAddon.drive.session.DriveSessionManager;
import org.fetarute.fetaruteTCAddon.utils.LocaleManager;

/**
 * 路考练习的教练与应急演练，以及练习、路考共用的延误保护。只在服务器主线程、由驾驶证服务定时调用。
 *
 * <ul>
 *   <li>教练（只在练习）：每个停站阶段说一句该做什么（进站减速、开哪一侧的门、关门、等信号、起步），信号变严提醒确认，超速提醒降速；
 *   <li>应急演练（只在练习）：开过第一站后随机安排一次——受电中断、车门故障、紧急停车，仿真级另有主断跳闸；逐步提示处置，记下用时供讲评；
 *   <li>延误保护（练习与路考）：练习跑在正式车次上，晚点时不安排演练；晚点过大先撤演练，再大就交还自动运行，免得调度救不过来。
 * </ul>
 */
final class TrainingCoach {

  /** 应急演练的种类。 */
  enum Drill {
    /** 受电中断：牵引失效，惰行或制动，等恢复供电。 */
    LINE_LOSS("line-loss", 5),
    /** 车门故障：门关好回路不通、牵引封锁，确认车门关好后用门旁路。 */
    DOOR("door", 30),
    /** 前方障碍：立即紧急制动，停稳后确认安全再起步。 */
    EMERGENCY("emergency", 0),
    /** 主断跳闸（仿真级）：停车后断开再闭合主断路器。 */
    BREAKER_TRIP("breaker-trip", 60);

    private final String key;
    private final int goodSeconds;

    Drill(String key, int goodSeconds) {
      this.key = key;
      this.goodSeconds = goodSeconds;
    }

    String key() {
      return key;
    }
  }

  /**
   * 一次演练的结果，供练习讲评。
   *
   * @param drill 演练种类
   * @param handled 是否处置完成
   * @param seconds 处置用时（紧急停车为反应时间）；没处置完时为 -1
   * @param good 是否在合格用时内
   */
  record DrillOutcome(Drill drill, boolean handled, double seconds, boolean good) {}

  /** 演练没处置完、过了这么久（tick）就撤销。 */
  private static final long DRILL_TIMEOUT_TICKS = 20L * 120L;

  /** 开始演练前要达到的车速（格/秒）：在区间里正常行驶时才安排。 */
  private static final double DRILL_MIN_SPEED_BPS = 6.0;

  /** 超速提醒的最短间隔（tick）。 */
  private static final long OVERSPEED_REMIND_TICKS = 200L;

  private static final double TICKS_PER_SECOND = 20.0;

  /** 一名考生这一次练习或路考的状态。 */
  private static final class Run {
    final boolean training;
    DriverStationStop.Phase lastPhase;
    DriverStationStop lastStop;
    boolean signalPending;
    long lastOverspeedTick = Long.MIN_VALUE;
    Drill drill;
    boolean drillDecided;
    long drillStartTick = -1L;
    long drillResponseTick = -1L;
    long drillResolvedTick = -1L;
    long lineRestoreTick = -1L;
    boolean drillAbandoned;
    boolean doorBypassReminded;
    boolean delayWarned;
    boolean handedBack;

    Run(boolean training) {
      this.training = training;
    }
  }

  private final Supplier<LocaleManager> locale;
  private final Supplier<LicenseConfig> config;
  private final Supplier<DriveSessionManager> drive;
  private final SplittableRandom random = new SplittableRandom();
  private final Map<UUID, Run> runs = new HashMap<>();

  TrainingCoach(
      Supplier<LocaleManager> locale,
      Supplier<LicenseConfig> config,
      Supplier<DriveSessionManager> drive) {
    this.locale = locale;
    this.config = config;
    this.drive = drive;
  }

  /** 开始跟踪一次练习或路考。 */
  void begin(UUID playerId, boolean training) {
    runs.put(playerId, new Run(training));
  }

  /**
   * 结束跟踪：撤掉还在进行的演练故障，交出演练结果。
   *
   * @return 这次练习安排过演练时为其结果
   */
  Optional<DrillOutcome> end(UUID playerId) {
    Run run = runs.remove(playerId);
    if (run == null || run.drill == null || run.drillStartTick < 0L) {
      return Optional.empty();
    }
    clearFault(playerId, run);
    return Optional.of(outcome(run));
  }

  /**
   * 推进一拍（每 10 tick 一次）。
   *
   * @param stopsDone 本次已经点评过的停站数（演练从开过第一站后开始）
   */
  void tick(Player player, DriveSession session, int stopsDone, long nowTick) {
    Run run = runs.get(player.getUniqueId());
    DriverLink link = session.driverLink();
    if (run == null || link == null || run.handedBack) {
      return;
    }
    OptionalLong delay = delayOf(session);
    if (guardDelay(player, session, run, delay)) {
      return;
    }
    if (!run.training || session.isAto()) {
      return;
    }
    coach(player, session, link, run, nowTick);
    drill(player, session, link, run, stopsDone, delay, nowTick);
  }

  // ---- 延误保护 ----

  /**
   * 晚点过大先撤演练、提示恢复正点；再大就交还自动运行。
   *
   * @return 已交还（本拍不再做别的）
   */
  private boolean guardDelay(Player player, DriveSession session, Run run, OptionalLong delay) {
    if (delay.isEmpty()) {
      return false;
    }
    TrainingConfig training = config.get().training();
    long seconds = delay.getAsLong();
    if (seconds > training.handbackDelaySeconds()) {
      run.handedBack = true;
      clearFault(player.getUniqueId(), run);
      tell(player, "drive.license.guard.handback", Map.of("delay", String.valueOf(seconds)));
      DriveSessionManager manager = drive.get();
      if (manager != null) {
        manager.handback(player.getUniqueId(), "license-delay");
      }
      return true;
    }
    if (seconds > training.maxDelaySeconds() && !run.delayWarned) {
      run.delayWarned = true;
      tell(player, "drive.license.guard.late", Map.of("delay", String.valueOf(seconds)));
      if (run.drill != null && run.drillStartTick >= 0L && run.drillResolvedTick < 0L) {
        run.drillAbandoned = true;
        clearFault(player.getUniqueId(), run);
        tell(player, "drive.license.guard.drill-cancelled", Map.of());
      }
    }
    return false;
  }

  /** 列车此刻的晚点（秒）；查不到时为空。 */
  static OptionalLong delayOf(DriveSession session) {
    return delayOf(session.trainName());
  }

  /** 按列车名查此刻的晚点（秒）；没绑车次、没有时刻表时为空。派车过滤与行车中的延误保护共用。 */
  static OptionalLong delayOf(String trainName) {
    if (trainName == null || trainName.isBlank()) {
      return OptionalLong.empty();
    }
    return DriverTaskManager.timetables()
        .flatMap(api -> api.getAssignment(trainName))
        .map(assignment -> assignment.currentDelaySeconds())
        .orElse(OptionalLong.empty());
  }

  // ---- 教练 ----

  private void coach(Player player, DriveSession session, DriverLink link, Run run, long nowTick) {
    Optional<DriverStationStop> stop = link.stationStop();
    if (stop.isPresent()) {
      DriverStationStop current = stop.get();
      if (current != run.lastStop || current.phase() != run.lastPhase) {
        run.lastStop = current;
        run.lastPhase = current.phase();
        coachPhase(player, link, current);
      }
    } else {
      run.lastStop = null;
      run.lastPhase = null;
    }
    boolean pending = link.signalConfirm().pending();
    if (pending && !run.signalPending) {
      tell(player, "drive.license.coach.signal", Map.of());
    }
    run.signalPending = pending;
    double limit = session.displayLimitBps();
    if (limit > 0.0
        && session.speedBps() > limit * 1.03 + 0.3
        && nowTick - run.lastOverspeedTick >= OVERSPEED_REMIND_TICKS) {
      run.lastOverspeedTick = nowTick;
      tell(
          player,
          "drive.license.coach.overspeed",
          Map.of("limit", String.valueOf(Math.round(limit * 3.6))));
    }
  }

  private void coachPhase(Player player, DriverLink link, DriverStationStop stop) {
    Map<String, String> values = new HashMap<>();
    values.put("station", stop.stationName());
    switch (stop.phase()) {
      case APPROACH -> tell(player, "drive.license.coach.approach", values);
      case OPEN_DOORS -> {
        DriverDoorSide side = link.requiredDoorSide();
        values.put("side", locale.get().text("drive.license.coach.side." + sideKey(side)));
        tell(
            player,
            side == DriverDoorSide.NONE
                ? "drive.license.coach.no-doors"
                : "drive.license.coach.open-doors",
            values);
      }
      case DWELL -> tell(player, "drive.license.coach.dwell", values);
      case CLOSE_DOORS -> tell(player, "drive.license.coach.close-doors", values);
      case WAIT_DEPARTURE -> tell(player, "drive.license.coach.wait-departure", values);
      case DEPART -> tell(player, "drive.license.coach.depart", values);
      default -> {}
    }
  }

  private static String sideKey(DriverDoorSide side) {
    return switch (side) {
      case LEFT -> "left";
      case RIGHT -> "right";
      case BOTH -> "both";
      default -> "platform";
    };
  }

  // ---- 应急演练 ----

  private void drill(
      Player player,
      DriveSession session,
      DriverLink link,
      Run run,
      int stopsDone,
      OptionalLong delay,
      long nowTick) {
    TrainingConfig training = config.get().training();
    if (!training.drill() || stopsDone < 1 || run.delayWarned) {
      if (run.drillStartTick >= 0L) {
        follow(player, session, run, nowTick);
      }
      return;
    }
    if (!run.drillDecided) {
      run.drillDecided = true;
      List<Drill> candidates = candidates(session);
      run.drill = candidates.isEmpty() ? null : candidates.get(random.nextInt(candidates.size()));
    }
    if (run.drill == null) {
      return;
    }
    if (run.drillStartTick >= 0L) {
      follow(player, session, run, nowTick);
      return;
    }
    // 晚点时不安排：练习跑在正式车次上，不能为演练再把车拖晚。
    if (delay.isPresent() && delay.getAsLong() > training.drillMaxDelaySeconds()) {
      return;
    }
    if (!ready(run.drill, session, link)) {
      return;
    }
    startDrill(player, session, run, nowTick);
  }

  /** 这列车此刻能演练哪些：仿真级多一项主断跳闸；电力牵引才有受电中断与主断跳闸。 */
  private static List<Drill> candidates(DriveSession session) {
    CabFaults faults = session.cab().faults();
    List<Drill> list = new ArrayList<>();
    if (faults.applicable(CabFault.LINE_LOSS)) {
      list.add(Drill.LINE_LOSS);
    }
    list.add(Drill.DOOR);
    list.add(Drill.EMERGENCY);
    if (session.cab().enabled() && faults.applicable(CabFault.BREAKER_TRIP)) {
      list.add(Drill.BREAKER_TRIP);
    }
    return list;
  }

  /** 时机：车门故障在关好门、等发车时；其余在区间里正常行驶时。 */
  private static boolean ready(Drill drill, DriveSession session, DriverLink link) {
    if (drill == Drill.DOOR) {
      return link.stationStop()
          .map(stop -> stop.phase() == DriverStationStop.Phase.WAIT_DEPARTURE)
          .orElse(false);
    }
    return link.stationStop().isEmpty() && session.speedBps() >= DRILL_MIN_SPEED_BPS;
  }

  private void startDrill(Player player, DriveSession session, Run run, long nowTick) {
    CabFaults faults = session.cab().faults();
    Map<String, String> values =
        Map.of("seconds", String.valueOf(config.get().training().lineLossSeconds()));
    switch (run.drill) {
      case LINE_LOSS -> {
        if (faults.inject(CabFault.LINE_LOSS, nowTick) != CabFaults.Outcome.INJECTED) {
          run.drill = null;
          return;
        }
        run.lineRestoreTick =
            nowTick + (long) (config.get().training().lineLossSeconds() * TICKS_PER_SECOND);
        if (!session.notch().isTraction()) {
          run.drillResponseTick = nowTick;
        }
      }
      case DOOR -> {
        if (faults.inject(CabFault.DOOR, nowTick) != CabFaults.Outcome.INJECTED) {
          run.drill = null;
          return;
        }
      }
      case BREAKER_TRIP -> {
        if (faults.inject(CabFault.BREAKER_TRIP, nowTick) != CabFaults.Outcome.INJECTED) {
          run.drill = null;
          return;
        }
      }
      case EMERGENCY -> {}
    }
    run.drillStartTick = nowTick;
    tell(player, "drive.license.drill.announce", Map.of());
    tell(player, "drive.license.drill.start." + run.drill.key(), values);
  }

  /** 跟进进行中的演练：记下反应与处置完成的时刻，到时恢复受电，超时撤销。 */
  private void follow(Player player, DriveSession session, Run run, long nowTick) {
    if (run.drill == null || run.drillResolvedTick >= 0L || run.drillAbandoned) {
      remindBypass(player, session, run);
      return;
    }
    CabFaults faults = session.cab().faults();
    switch (run.drill) {
      case LINE_LOSS -> {
        if (run.drillResponseTick < 0L && !session.notch().isTraction()) {
          run.drillResponseTick = nowTick;
        }
        if (nowTick >= run.lineRestoreTick) {
          faults.resolve(CabFault.LINE_LOSS);
          resolved(player, run, nowTick);
        }
      }
      case DOOR -> {
        if (faults.doorBypassed()) {
          resolved(player, run, nowTick);
        }
      }
      case BREAKER_TRIP -> {
        if (!faults.active(CabFault.BREAKER_TRIP)) {
          resolved(player, run, nowTick);
        }
      }
      case EMERGENCY -> {
        if (run.drillResponseTick < 0L && session.notch() == Notch.EB) {
          run.drillResponseTick = nowTick;
        }
        if (run.drillResponseTick >= 0L && session.isStopped()) {
          resolved(player, run, nowTick);
        }
      }
    }
    if (run.drillResolvedTick < 0L && nowTick - run.drillStartTick > DRILL_TIMEOUT_TICKS) {
      run.drillAbandoned = true;
      clearFault(player.getUniqueId(), run);
      tell(player, "drive.license.drill.timeout", Map.of("name", drillName(run.drill)));
    }
  }

  private void resolved(Player player, Run run, long nowTick) {
    run.drillResolvedTick = nowTick;
    DrillOutcome outcome = outcome(run);
    tell(
        player,
        "drive.license.drill.done." + run.drill.key(),
        Map.of("seconds", seconds(outcome.seconds())));
  }

  /** 车门故障演练处置完后：列车开出时排除故障，并提醒到下一站停稳后关闭门旁路。 */
  private void remindBypass(Player player, DriveSession session, Run run) {
    if (run.drill != Drill.DOOR || run.drillResolvedTick < 0L || run.doorBypassReminded) {
      return;
    }
    if (!session.isStopped()) {
      run.doorBypassReminded = true;
      session.cab().faults().resolve(CabFault.DOOR);
      tell(player, "drive.license.drill.bypass-off", Map.of());
    }
  }

  private DrillOutcome outcome(Run run) {
    long from = run.drillStartTick;
    long to =
        run.drill == Drill.EMERGENCY || run.drill == Drill.LINE_LOSS
            ? run.drillResponseTick
            : run.drillResolvedTick;
    boolean handled = run.drillResolvedTick >= 0L && !run.drillAbandoned;
    double seconds = to < 0L ? -1.0 : (to - from) / TICKS_PER_SECOND;
    int limit =
        run.drill == Drill.EMERGENCY
            ? config.get().training().emergencyReactionSeconds()
            : run.drill.goodSeconds;
    return new DrillOutcome(
        run.drill, handled, seconds, handled && seconds >= 0 && seconds <= limit);
  }

  /** 撤掉演练注入的故障，车门演练还要断开门旁路（列车或会话可能已经不在）。 */
  private void clearFault(UUID playerId, Run run) {
    if (run.drill == null || run.drill == Drill.EMERGENCY) {
      return;
    }
    DriveSessionManager manager = drive.get();
    if (manager == null) {
      return;
    }
    CabFault fault =
        switch (run.drill) {
          case LINE_LOSS -> CabFault.LINE_LOSS;
          case DOOR -> CabFault.DOOR;
          default -> CabFault.BREAKER_TRIP;
        };
    manager
        .sessionOf(playerId)
        .ifPresent(
            session -> {
              CabFaults faults = session.cab().faults();
              faults.resolve(fault);
              if (fault == CabFault.DOOR && faults.doorBypassed()) {
                faults.toggleDoorBypass();
              }
            });
  }

  /** 讲评里的一行：演练名称、是否得当、用时与要领。 */
  void review(Player player, DrillOutcome outcome) {
    Map<String, String> values = new HashMap<>();
    values.put("name", drillName(outcome.drill()));
    values.put("seconds", seconds(outcome.seconds()));
    String verdict = !outcome.handled() ? "missed" : outcome.good() ? "good" : "slow";
    tell(player, "drive.license.drill.review." + verdict, values);
    if (!outcome.good()) {
      tell(player, "drive.license.drill.tip." + outcome.drill().key(), values);
    }
  }

  String drillName(Drill drill) {
    return locale.get().text("drive.license.drill.name." + drill.key());
  }

  private static String seconds(double seconds) {
    return seconds < 0 ? "-" : String.format(Locale.ROOT, "%.1f", seconds);
  }

  private void tell(Player player, String key, Map<String, String> values) {
    player.sendMessage(locale.get().component(key, values));
  }
}
