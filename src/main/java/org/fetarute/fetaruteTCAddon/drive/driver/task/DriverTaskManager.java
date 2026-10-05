package org.fetarute.fetaruteTCAddon.drive.driver.task;

import com.bergerkiller.bukkit.tc.controller.MinecartGroup;
import com.bergerkiller.bukkit.tc.controller.MinecartGroupStore;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.fetarute.fetaruteTCAddon.FetaruteTCAddon;
import org.fetarute.fetaruteTCAddon.api.FetaruteApi;
import org.fetarute.fetaruteTCAddon.api.timetable.TimetableApi;
import org.fetarute.fetaruteTCAddon.company.api.StationDirectory;
import org.fetarute.fetaruteTCAddon.company.model.StationLocation;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.EtaService;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.TrainHold;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeStopState;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.DriverStationStop;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.Timetable;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableAssignment;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableRoutePlan;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableService;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableStop;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableTrip;
import org.fetarute.fetaruteTCAddon.drive.driver.DriverCircuitBreaker;
import org.fetarute.fetaruteTCAddon.drive.driver.DriverCongestion;
import org.fetarute.fetaruteTCAddon.drive.driver.DriverRecovery;
import org.fetarute.fetaruteTCAddon.drive.driver.DrivingMode;
import org.fetarute.fetaruteTCAddon.drive.seat.CabSeats;
import org.fetarute.fetaruteTCAddon.drive.seat.SeatBinding;
import org.fetarute.fetaruteTCAddon.drive.session.ManagedTrains;

/**
 * 驾驶任务：领取、等车、驾驶中、结束；以及交还后的救援与全网熔断。任务只存在内存里，重启即清空。只在服务器主线程调用。
 *
 * <p>一名玩家同时只有一个未结束的任务，一个车次同时只给一名玩家。车次与列车靠时刻表绑定（时刻表、车次号、运营日）对上；列车停在接班站、 玩家坐在它的车头驾驶室时由驾驶会话管理器接管。
 */
public final class DriverTaskManager {

  /** 领取的结果。 */
  public enum ClaimOutcome {
    CLAIMED,
    DISABLED,
    BREAKER_OPEN,
    ALREADY_HAS_TASK,
    TAKEN,
    UNAVAILABLE,
    /** 被外部插件拦下（{@code DriverTaskClaimEvent} 取消）。 */
    CANCELLED
  }

  /** 任务的对外通知：领取前可拦下，没开过车就结束时报一次（开过车的由驾驶会话评分后报）。 */
  public interface Listener {
    /** 将要领取：返回 false 拦下。 */
    boolean beforeClaim(DriverTask task);

    /** 还没开车就结束（作废、领取后放弃、收回）。 */
    void onUnstartedFinished(DriverTask task);
  }

  private static final Listener NO_LISTENER =
      new Listener() {
        @Override
        public boolean beforeClaim(DriverTask task) {
          return true;
        }

        @Override
        public void onUnstartedFinished(DriverTask task) {}
      };

  /**
   * 插件派出的任务。
   *
   * @param key 车次
   * @param routeCode 交路代码
   * @param operatorCode 接班站的运营商
   * @param stationCode 接班站站码
   * @param stationName 接班站站名
   * @param boardNodeId 接班站台节点
   * @param boardStopSequence 接班站停靠序号
   * @param plannedDeparture 接班站计划发车
   * @param trainName 担当的列车；还没对上时为 {@code null}
   * @param alightStopSequence 下车站停靠序号；开到终点站为 -1
   * @param alightStationCode 下车站站码
   * @param alightStationName 下车站站名
   * @param depotPickup 是否从车库接车
   * @param source 来源标记
   * @param metadata 附加数据
   */
  public record TaskSpec(
      TaskKey key,
      String routeCode,
      String operatorCode,
      String stationCode,
      String stationName,
      String boardNodeId,
      int boardStopSequence,
      Instant plannedDeparture,
      String trainName,
      int alightStopSequence,
      String alightStationCode,
      String alightStationName,
      boolean depotPickup,
      String source,
      Map<String, String> metadata) {
    public TaskSpec {
      metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
  }

  private Listener listener = NO_LISTENER;

  public void setListener(Listener listener) {
    this.listener = listener == null ? NO_LISTENER : listener;
  }

  /** 列车没在接班站停站、计划发车又已过去这么久，任务作废。 */
  public static final Duration EXPIRE_AFTER = Duration.ofMinutes(10);

  /** 交还后列车走过这么远就不再救援。 */
  private static final double RESCUE_PROGRESS_BLOCKS = 3.0;

  /** 交还后等着救援的驾驶员。 */
  private record RescueWatch(
      UUID playerId, String trainName, Vector headAtStart, long dueTick, Location target) {}

  private final FetaruteTCAddon plugin;
  private final Consumer<String> trace;
  private final Map<UUID, DriverTask> byPlayer = new HashMap<>();
  private final Map<TaskKey, UUID> byKey = new HashMap<>();

  /** 终点站结算后等着接续下一趟的驾驶员：那一班先替他留着，任务板上显示为他所领，别人领不走。 */
  private final Map<TaskKey, TaskBoardEntries.Claimant> reserved = new HashMap<>();

  private final List<RescueWatch> rescues = new ArrayList<>();
  private final DriverCircuitBreaker breaker = new DriverCircuitBreaker();

  /** 最近一次熔断评估时每列被扣住的车（挡住后车的提醒复用）。 */
  private Map<String, DriverCircuitBreaker.Hold> lastHolds = Map.of();

  public DriverTaskManager(FetaruteTCAddon plugin, Consumer<String> trace) {
    this.plugin = plugin;
    this.trace = trace == null ? message -> {} : trace;
  }

  public DriverCircuitBreaker breaker() {
    return breaker;
  }

  /** 玩家当前的任务（含刚结束、还没被新任务替换的）。 */
  public Optional<DriverTask> taskOf(UUID playerId) {
    return Optional.ofNullable(byPlayer.get(playerId));
  }

  /** 玩家未结束的任务。 */
  public Optional<DriverTask> activeTaskOf(UUID playerId) {
    return taskOf(playerId).filter(task -> !task.state().finished());
  }

  /** 已被领走（未结束）的车次。 */
  public Set<TaskKey> takenKeys() {
    Set<TaskKey> keys = new HashSet<>();
    for (Map.Entry<TaskKey, UUID> entry : byKey.entrySet()) {
      DriverTask task = byPlayer.get(entry.getValue());
      if (task != null && !task.state().finished() && task.key().equals(entry.getKey())) {
        keys.add(entry.getKey());
      }
    }
    keys.addAll(reserved.keySet());
    return keys;
  }

  /** 已被领走（未结束）的车次与领取人，任务板据此标明谁领了哪一班。 */
  public Map<TaskKey, TaskBoardEntries.Claimant> claimants() {
    Map<TaskKey, TaskBoardEntries.Claimant> claimants = new HashMap<>();
    for (TaskKey key : takenKeys()) {
      DriverTask task = byPlayer.get(byKey.get(key));
      if (task != null) {
        claimants.put(key, new TaskBoardEntries.Claimant(task.playerId(), task.playerName()));
      }
    }
    reserved.forEach(claimants::putIfAbsent);
    return claimants;
  }

  /**
   * 替终点站结算后等着接续的驾驶员留下一班。
   *
   * @return 那一班已被别人领走或留下时为 {@code false}
   */
  public boolean reserve(UUID playerId, String playerName, TaskKey key) {
    TaskBoardEntries.Claimant holder = reserved.get(key);
    if (holder != null) {
      return holder.playerId().equals(playerId);
    }
    if (takenKeys().contains(key)) {
      return false;
    }
    reserved.put(key, new TaskBoardEntries.Claimant(playerId, playerName));
    return true;
  }

  /** 替这名驾驶员留着的班次；没有时为空。 */
  public Optional<TaskKey> reservationOf(UUID playerId) {
    for (Map.Entry<TaskKey, TaskBoardEntries.Claimant> entry : reserved.entrySet()) {
      if (entry.getValue().playerId().equals(playerId)) {
        return Optional.of(entry.getKey());
      }
    }
    return Optional.empty();
  }

  /** 放掉替这名驾驶员留着的班次（不继续了，或已开出记成任务）。 */
  public void releaseReservation(UUID playerId) {
    reserved.values().removeIf(holder -> holder.playerId().equals(playerId));
  }

  /**
   * 驾驶员已经在开这列车：把它此刻跑的（或刚开出的）车次直接记成驾驶中的任务。不看任务板的时间窗与熔断，替他留着的班次先放掉；其余规则与领取相同。
   *
   * @param nowTick 开始驾驶的服务器 tick
   */
  public ClaimOutcome adopt(
      Player player, TaskSpec spec, DrivingMode mode, Instant now, long nowTick) {
    releaseReservation(player.getUniqueId());
    if (activeTaskOf(player.getUniqueId()).isPresent()) {
      return ClaimOutcome.ALREADY_HAS_TASK;
    }
    if (takenKeys().contains(spec.key())) {
      return ClaimOutcome.TAKEN;
    }
    DriverTask task =
        new DriverTask(
            player.getUniqueId(),
            player.getName(),
            spec.key(),
            spec.routeCode(),
            spec.operatorCode(),
            spec.stationCode(),
            spec.stationName(),
            spec.boardNodeId(),
            spec.boardStopSequence(),
            spec.plannedDeparture(),
            mode,
            now);
    task.setTrainName(spec.trainName());
    task.setSource(spec.source(), spec.metadata());
    ClaimOutcome outcome = register(player, task);
    if (outcome == ClaimOutcome.CLAIMED) {
      task.start(spec.trainName(), nowTick);
    }
    return outcome;
  }

  /** 领了这一班、还没结束的任务；没人领时为空。 */
  public Optional<DriverTask> taskForTrip(
      UUID timetableId, String tripCode, java.time.LocalDate serviceDate) {
    for (DriverTask task : byPlayer.values()) {
      if (!task.state().finished() && task.key().matches(timetableId, tripCode, serviceDate)) {
        return Optional.of(task);
      }
    }
    return Optional.empty();
  }

  /** 是否有还没结束的任务。 */
  public boolean hasActiveTasks() {
    for (DriverTask task : byPlayer.values()) {
      if (!task.state().finished()) {
        return true;
      }
    }
    return false;
  }

  /**
   * 车次的当前晚点：列车此刻绑定的必须就是这一班（终点站接车时派车之前还绑着上一班，不能拿它当接班时的晚点）。
   *
   * @param key 任务的车次；为空时不核对（运营人员直接接管）
   */
  public static OptionalLong delayOfTrip(TimetableApi.TrainAssignment assignment, TaskKey key) {
    if (assignment == null
        || (key != null
            && !key.matches(
                assignment.timetableId(), assignment.tripCode(), assignment.serviceDate()))) {
      return OptionalLong.empty();
    }
    return assignment.currentDelaySeconds();
  }

  /** 已领取、还没开始驾驶的任务被收回（管理员命令）。 */
  public void interruptClaim(UUID playerId, String reason) {
    DriverTask task = byPlayer.get(playerId);
    if (task != null && task.state() == DriverTask.State.CLAIMED) {
      finish(task, DriverTask.State.INTERRUPTED, reason);
    }
  }

  /** 已领取、还没开始驾驶的任务作废（例如接车等到时限）。 */
  public void expireClaim(UUID playerId, String reason) {
    DriverTask task = byPlayer.get(playerId);
    if (task != null && task.state() == DriverTask.State.CLAIMED) {
      finish(task, DriverTask.State.EXPIRED, reason);
      trace.accept("任务作废 " + task.playerName() + " " + task.key().tripCode() + ": " + reason);
    }
  }

  /** 结束任务；还没开车就结束的当场对外报一次（开过车的由驾驶会话评分后报）。 */
  private void finish(DriverTask task, DriverTask.State state, String reason) {
    task.finish(state, reason);
    if (task.startedAt() == null && task.announceFinish()) {
      listener.onUnstartedFinished(task);
    }
  }

  /** 每名玩家最近的任务（含已结束、还没被新任务替换的）。 */
  public List<DriverTask> allTasks() {
    return new ArrayList<>(byPlayer.values());
  }

  /** 全部未结束的任务。 */
  public List<DriverTask> activeTasks() {
    List<DriverTask> tasks = new ArrayList<>();
    for (DriverTask task : byPlayer.values()) {
      if (!task.state().finished()) {
        tasks.add(task);
      }
    }
    return tasks;
  }

  /**
   * 领取一个车次。
   *
   * @param enabled 驾驶调度列车是否启用
   */
  public ClaimOutcome claim(
      Player player,
      TaskBoardEntries.Row row,
      String operatorCode,
      String stationCode,
      String stationName,
      DrivingMode mode,
      boolean enabled,
      Instant now) {
    if (!enabled) {
      return ClaimOutcome.DISABLED;
    }
    if (breaker.open(now)) {
      return ClaimOutcome.BREAKER_OPEN;
    }
    if (activeTaskOf(player.getUniqueId()).isPresent()) {
      return ClaimOutcome.ALREADY_HAS_TASK;
    }
    if (takenKeys().contains(row.key())) {
      return ClaimOutcome.TAKEN;
    }
    if (row.cancelled() || row.terminating()) {
      return ClaimOutcome.UNAVAILABLE;
    }
    DriverTask task =
        new DriverTask(
            player.getUniqueId(),
            player.getName(),
            row.key(),
            row.routeCode(),
            operatorCode,
            stationCode,
            stationName,
            row.nodeId(),
            row.stopSequence(),
            row.plannedDeparture(),
            mode,
            now);
    task.setTrainName(row.trainName());
    return register(player, task);
  }

  /**
   * 插件派出一个任务：不看任务板的时间窗、不要求玩家在车站附近；其余规则与任务板相同。
   *
   * @param enabled 驾驶调度列车是否启用
   */
  public ClaimOutcome assign(
      Player player, TaskSpec spec, DrivingMode mode, boolean enabled, Instant now) {
    if (!enabled) {
      return ClaimOutcome.DISABLED;
    }
    if (breaker.open(now)) {
      return ClaimOutcome.BREAKER_OPEN;
    }
    if (activeTaskOf(player.getUniqueId()).isPresent()) {
      return ClaimOutcome.ALREADY_HAS_TASK;
    }
    if (takenKeys().contains(spec.key())) {
      return ClaimOutcome.TAKEN;
    }
    DriverTask task =
        new DriverTask(
            player.getUniqueId(),
            player.getName(),
            spec.key(),
            spec.routeCode(),
            spec.operatorCode(),
            spec.stationCode(),
            spec.stationName(),
            spec.boardNodeId(),
            spec.boardStopSequence(),
            spec.plannedDeparture(),
            mode,
            now);
    task.setTrainName(spec.trainName());
    task.setDepotPickup(spec.depotPickup());
    if (spec.alightStopSequence() >= 0) {
      task.setAlight(spec.alightStopSequence(), spec.alightStationCode(), spec.alightStationName());
    }
    task.setSource(spec.source(), spec.metadata());
    return register(player, task);
  }

  private ClaimOutcome register(Player player, DriverTask task) {
    if (!listener.beforeClaim(task)) {
      return ClaimOutcome.CANCELLED;
    }
    DriverTask previous = byPlayer.put(player.getUniqueId(), task);
    if (previous != null) {
      byKey.remove(previous.key(), previous.playerId());
    }
    byKey.put(task.key(), player.getUniqueId());
    trace.accept(
        "领取任务 "
            + player.getName()
            + " -> "
            + task.routeCode()
            + " "
            + task.key().tripCode()
            + " "
            + task.mode()
            + (task.alightStopSequence() >= 0 ? " 下车站 " + task.alightStationName() : "")
            + (DriverTask.SOURCE_BOARD.equals(task.source()) ? "" : " 来源 " + task.source()));
    return ClaimOutcome.CLAIMED;
  }

  /** 放弃还没开始驾驶的任务；驾驶中的由调用方先请求交还。 */
  public boolean abandon(UUID playerId, String reason) {
    DriverTask task = byPlayer.get(playerId);
    if (task == null || task.state().finished()) {
      return false;
    }
    finish(task, DriverTask.State.ABANDONED, reason);
    return true;
  }

  /** 这名玩家在这列车上有没有可接管的任务（已领取或驾驶中）。 */
  public Optional<DriverTask> claimFor(UUID playerId, String trainName) {
    return activeTaskOf(playerId)
        .filter(task -> trainName != null && trainName.equalsIgnoreCase(task.trainName()));
  }

  /** 驾驶会话开始。 */
  public void onSessionStarted(UUID playerId, String trainName, long nowTick) {
    activeTaskOf(playerId)
        .filter(task -> trainName.equalsIgnoreCase(task.trainName()))
        .ifPresent(task -> task.start(trainName, nowTick));
  }

  /**
   * 驾驶会话结束：按结束原因给任务定终态。
   *
   * @param finalState 任务的终态；为 {@code null} 时不改
   */
  public void onSessionEnded(UUID playerId, DriverTask.State finalState, String reason) {
    DriverTask task = byPlayer.get(playerId);
    if (task == null || task.state() != DriverTask.State.DRIVING || finalState == null) {
      return;
    }
    finish(task, finalState, reason);
    trace.accept(
        "任务结束 "
            + task.playerName()
            + " "
            + task.key().tripCode()
            + ": "
            + finalState
            + " "
            + reason);
  }

  /** 驾驶中的任务判失败（卡住太久、超过时限）。 */
  public void fail(UUID playerId, String reason) {
    DriverTask task = byPlayer.get(playerId);
    if (task != null && task.state() == DriverTask.State.DRIVING) {
      finish(task, DriverTask.State.FAILED, reason);
    }
  }

  /** 驾驶中的任务完成。 */
  public void complete(UUID playerId) {
    DriverTask task = byPlayer.get(playerId);
    if (task != null && task.state() == DriverTask.State.DRIVING) {
      finish(
          task, DriverTask.State.COMPLETED, task.alightStopSequence() >= 0 ? "alight" : "terminal");
    }
  }

  /**
   * 列车是否正停在这趟车次的终点站：时刻表绑定显示已过本站、没有下一个停靠站。
   *
   * @return 查不到绑定时为 {@code false}
   */
  public boolean atTerminal(DriverTask task, String trainName) {
    Optional<TimetableApi> api = timetables();
    Optional<TimetableApi.TrainAssignment> assignment =
        api.flatMap(timetables -> timetables.getAssignment(trainName));
    if (task.alightStopSequence() >= 0) {
      return assignment.isPresent()
          && reachedAlight(task.alightStopSequence(), task.key(), assignment.get());
    }
    if (assignment.isEmpty()
        || assignment.get().lastStopSequence().isEmpty()
        || assignment.get().nextStopSequence().isPresent()
        || assignment.get().lastStationCode().isEmpty()
        || !task.key()
            .matches(
                assignment.get().timetableId(),
                assignment.get().tripCode(),
                assignment.get().serviceDate())) {
      return false;
    }
    // “没有下一站”在推算信息缺失时也会出现：再核对这一站确实是本车次的终到站。
    int stopSequence = assignment.get().lastStopSequence().get();
    Instant now = Instant.now();
    Optional<TimetableService> service =
        plugin == null ? Optional.empty() : plugin.getTimetableService();
    if (service.isPresent()) {
      return terminatesAt(
          service.get(), task.key(), stopSequence, assignment.get().lastStationCode().get(), now);
    }
    return api
        .get()
        .departuresAt(
            null,
            assignment.get().lastStationCode().get(),
            now.minus(TERMINAL_LOOKBACK),
            TERMINAL_LOOKBACK.plus(TERMINAL_LOOKBACK),
            TERMINAL_LOOKUP_LIMIT)
        .stream()
        .anyMatch(
            departure ->
                departure.stopSequence() == stopSequence
                    && departure.terminating()
                    && task.key()
                        .matches(
                            departure.timetableId(),
                            departure.tripCode(),
                            departure.serviceDate()));
  }

  /**
   * 这一站是不是这个车次的终到站：只查车次自己的交路计划，口径与站牌发车记录的“终到”相同（在这一站停车，且是最后一个停车点或写成终点），
   * 表定时刻也在核对窗口里。不扫整个车站的发车表——核对没通过时每秒都要再查一次。
   */
  static boolean terminatesAt(
      TimetableService service, TaskKey key, int stopSequence, String stationCode, Instant now) {
    for (Timetable timetable : service.publishedTimetables()) {
      if (!timetable.id().equals(key.timetableId())) {
        continue;
      }
      Optional<TimetableTrip> trip = timetable.tripByCode(key.tripCode());
      Optional<TimetableRoutePlan> plan = trip.flatMap(timetable::tripPlan);
      if (plan.isEmpty()) {
        return false;
      }
      // 车次终点的口径以交路计划为准（第一个终点站，没有时为最后一个停车点）。
      if (plan.get().terminatingSequence().orElse(-1) != stopSequence) {
        return false;
      }
      for (TimetableStop stop : plan.get().stops()) {
        if (stop.stopSequence() != stopSequence) {
          continue;
        }
        if (!stop.stops() || stop.stationCode().filter(stationCode::equalsIgnoreCase).isEmpty()) {
          return false;
        }
        Instant departure =
            trip.get()
                .departureAt(key.serviceDate(), timetable.zoneId())
                .plusSeconds(stop.departureOffsetSeconds());
        return !departure.isBefore(now.minus(TERMINAL_LOOKBACK))
            && departure.isBefore(now.plus(TERMINAL_LOOKBACK));
      }
      return false;
    }
    return false;
  }

  /**
   * 区间任务是否已到下车站：列车跑的就是这一班，且已经停过（或越过）下车站。
   *
   * <p>越站时站台照样记下“停过”这一站，所以越过下车站同样算到站，到下一次停稳时结束任务。
   */
  static boolean reachedAlight(
      int alightStopSequence, TaskKey key, TimetableApi.TrainAssignment assignment) {
    return key.matches(assignment.timetableId(), assignment.tripCode(), assignment.serviceDate())
        && assignment.lastStopSequence().filter(last -> last >= alightStopSequence).isPresent();
  }

  /** 核对终到站时往前后各看多久的发车记录。 */
  private static final Duration TERMINAL_LOOKBACK = Duration.ofHours(3);

  private static final int TERMINAL_LOOKUP_LIMIT = 2000;

  /** 时刻表接口；公开 API 未就绪时为空。 */
  public static Optional<TimetableApi> timetables() {
    return FetaruteApi.get().map(FetaruteApi::timetables);
  }

  // ---- 等车与接管 ----

  /** 列车停在接班站时的回调：告诉玩家该怎么接班。 */
  public interface Starter {
    /**
     * @return 已在驾驶这列车时为 {@code null}；否则是给玩家的提示语言键
     */
    String tryStart(Player player, DriverTask task);
  }

  /** 玩家此刻的座位能不能接班。 */
  public enum SeatCheck {
    /** 没坐在任务列车上。 */
    NOT_ON_TRAIN,
    /** 坐在任务列车上，但不是下一趟要驾驶的那一端的驾驶室。 */
    WRONG_SEAT,
    /** 坐在要驾驶的那一端的驾驶室：等驾驶员确认座位无误再接班。 */
    CONFIRM
  }

  /**
   * 判定座位能不能接班。
   *
   * @param seat 玩家的座位；没坐下时为 {@code null}
   * @param trainName 任务列车名
   * @param end 座位在哪一端（见 {@link CabSeats#endOf}）
   * @param expected 下一趟由哪一端驾驶：一般是车头端；终点站折返接车时方向未定为两端都可以，尽头式终点站为车尾端
   */
  public static SeatCheck checkSeat(
      SeatBinding seat, String trainName, CabSeats.End end, CabSeats.Departure expected) {
    if (seat == null || trainName == null || !seat.trainName().equalsIgnoreCase(trainName)) {
      return SeatCheck.NOT_ON_TRAIN;
    }
    return CabSeats.accepts(end, expected) ? SeatCheck.CONFIRM : SeatCheck.WRONG_SEAT;
  }

  /**
   * 推进已领取的任务：对上列车、到站时接管、过时作废。
   *
   * @param notify 给玩家发动作栏提示（语言键、占位符）
   */
  public void tickClaims(Starter starter, Notifier notify, Instant now) {
    boolean anyClaimed = false;
    for (DriverTask task : byPlayer.values()) {
      if (task.state() == DriverTask.State.CLAIMED) {
        anyClaimed = true;
        break;
      }
    }
    if (!anyClaimed) {
      return;
    }
    Collection<TimetableApi.TrainAssignment> assignments = claimedAssignments(timetables());
    for (DriverTask task : new ArrayList<>(byPlayer.values())) {
      if (task.state() != DriverTask.State.CLAIMED) {
        continue;
      }
      TimetableApi.TrainAssignment assignment = null;
      for (TimetableApi.TrainAssignment candidate : assignments) {
        if (task.key()
            .matches(candidate.timetableId(), candidate.tripCode(), candidate.serviceDate())) {
          assignment = candidate;
          break;
        }
      }
      if (assignment != null) {
        task.setTrainName(assignment.trainName());
      }
      int lastSeq = assignment == null ? -1 : assignment.lastStopSequence().orElse(-1);
      boolean atBoard = assignment != null && lastSeq == task.boardStopSequence();
      boolean dwelling = atBoard && dwelling(assignment.trainName());
      if (assignment != null && lastSeq > task.boardStopSequence()) {
        expire(task, notify, "departed");
        continue;
      }
      if (!dwelling) {
        // 已对上列车、它还没到接班站：晚点也等它来。还没对上列车（绑定在它停过一站后才有）时按时作废。
        boolean approaching = assignment != null && lastSeq < task.boardStopSequence();
        if (!approaching && now.isAfter(task.plannedDeparture().plus(EXPIRE_AFTER))) {
          expire(task, notify, "timeout");
        }
        continue;
      }
      Player player = Bukkit.getPlayer(task.playerId());
      if (player == null || !player.isOnline()) {
        continue;
      }
      String hint = starter.tryStart(player, task);
      if (hint != null) {
        notify.send(
            player,
            hint,
            Map.of(
                "train",
                assignment.trainName(),
                "trip",
                task.key().tripCode(),
                "station",
                task.stationName()));
      }
    }
  }

  /**
   * 已领取任务的车次绑定。完整绑定要换算到发记录与预计晚点（含一次 ETA），先在时刻表服务的原始绑定里按车次对上，只换算对上的那几列车，不把全网列车都算一遍； 拿不到时刻表服务时退回整份列表。
   */
  private Collection<TimetableApi.TrainAssignment> claimedAssignments(Optional<TimetableApi> api) {
    if (api.isEmpty()) {
      return List.of();
    }
    Optional<TimetableService> service =
        plugin == null ? Optional.empty() : plugin.getTimetableService();
    if (service.isEmpty()) {
      return api.get().listAssignments();
    }
    List<TaskKey> claimed = new ArrayList<>();
    for (DriverTask task : byPlayer.values()) {
      if (task.state() == DriverTask.State.CLAIMED) {
        claimed.add(task.key());
      }
    }
    List<TimetableApi.TrainAssignment> matched = new ArrayList<>();
    for (TimetableAssignment raw : service.get().assignments()) {
      boolean wanted = false;
      for (TaskKey key : claimed) {
        if (key.matches(raw.timetableId(), raw.tripCode(), raw.serviceDate())) {
          wanted = true;
          break;
        }
      }
      if (!wanted) {
        continue;
      }
      api.get().getAssignment(raw.trainName()).ifPresent(matched::add);
    }
    return matched;
  }

  /** 给玩家发提示。 */
  public interface Notifier {
    void send(Player player, String key, Map<String, String> values);
  }

  private void expire(DriverTask task, Notifier notify, String reason) {
    finish(task, DriverTask.State.EXPIRED, reason);
    trace.accept("任务作废 " + task.playerName() + " " + task.key().tripCode() + ": " + reason);
    Player player = Bukkit.getPlayer(task.playerId());
    if (player != null && player.isOnline()) {
      notify.send(
          player,
          "drive.task.expired",
          Map.of("trip", task.key().tripCode(), "station", task.stationName()));
    }
  }

  private boolean dwelling(String trainName) {
    boolean dwell =
        plugin.getDwellRegistry().map(r -> r.remainingSeconds(trainName).isPresent()).orElse(false);
    return dwell
        || plugin
            .getRuntimeDispatchService()
            .map(dispatch -> dispatch.hasDepartureGate(trainName))
            .orElse(false);
  }

  // ---- 救援 ----

  /**
   * 交还之后继续盯着：到时列车仍没走、驾驶员仍在车上，就让他下车并送到站台。
   *
   * @param lastStop 驾驶员最近停过的站（送到那里）；没有时为 {@code null}
   */
  public void watchForRescue(
      Player player, MinecartGroup group, DriverStationStop lastStop, long dueTick) {
    Location target =
        rescueTarget(lastStop)
            .or(() -> activeOrLastTask(player.getUniqueId()).flatMap(this::boardStation))
            .orElse(null);
    Vector head = group.head().getEntity().getLocation().toVector();
    rescues.add(
        new RescueWatch(
            player.getUniqueId(), group.getProperties().getTrainName(), head, dueTick, target));
  }

  /**
   * 把人从列车上送到哪里：最近停过的车站的站台，其次是驾驶员任务的接班站；都没有时为空。
   *
   * @param playerId 驾驶员；没有驾驶员时为 {@code null}
   * @param lastStop 最近停过的站；没有时为 {@code null}
   */
  public Optional<Location> rescueLocation(UUID playerId, DriverStationStop lastStop) {
    return rescueTarget(lastStop)
        .or(
            () ->
                playerId == null
                    ? Optional.empty()
                    : activeOrLastTask(playerId).flatMap(this::boardStation));
  }

  /** 推进救援：到时仍卡着就送驾驶员去站台。 */
  public void tickRescues(long nowTick, Notifier notify) {
    Iterator<RescueWatch> it = rescues.iterator();
    while (it.hasNext()) {
      RescueWatch watch = it.next();
      Player player = Bukkit.getPlayer(watch.playerId());
      Optional<MinecartGroup> group =
          org.fetarute.fetaruteTCAddon.drive.seat.SeatLocator.findGroup(watch.trainName());
      if (player == null || !player.isOnline() || group.isEmpty()) {
        it.remove();
        continue;
      }
      Vector head = group.get().head().getEntity().getLocation().toVector();
      boolean onTrain =
          player.getVehicle() != null
              && org.fetarute
                  .fetaruteTCAddon
                  .drive
                  .seat
                  .SeatLocator
                  .locate(player)
                  .filter(seat -> seat.trainName().equals(watch.trainName()))
                  .isPresent();
      if (!onTrain || head.distance(watch.headAtStart()) >= RESCUE_PROGRESS_BLOCKS) {
        it.remove();
        continue;
      }
      if (nowTick < watch.dueTick() || group.get().isMoving()) {
        continue;
      }
      it.remove();
      if (watch.target() == null) {
        // 找不到可以送去的站台：不把人放在区间轨道旁，只提示。
        notify.send(player, "drive.driver.rescue.no-target", Map.of("train", watch.trainName()));
        plugin.getLogger().warning("驾驶员列车 " + watch.trainName() + " 交还后仍未移动，且找不到可送达的站台；请检查该列车");
        continue;
      }
      trace.accept("救援 " + player.getName() + " 离开 " + watch.trainName());
      player.leaveVehicle();
      player.teleport(watch.target());
      notify.send(player, "drive.driver.rescue.rescued", Map.of("train", watch.trainName()));
      plugin
          .getLogger()
          .warning(
              "驾驶员列车 "
                  + watch.trainName()
                  + " 交还自动运行后仍未移动，已把驾驶员 "
                  + player.getName()
                  + " 送到站台；请检查该列车");
    }
  }

  private Optional<DriverTask> activeOrLastTask(UUID playerId) {
    return Optional.ofNullable(byPlayer.get(playerId));
  }

  /** 前往接班站时车站设置的位置；车站没设置位置时为空，改用 {@link #boardPlatformLocation}。 */
  public Optional<Location> boardStationConfigured(DriverTask task) {
    return boardStation(task);
  }

  /**
   * 接班站台牌子所在的轨道旁一处站得住的地方。站台区块未加载时先在后台加载（不卡主线程），结果在主线程回调。
   *
   * @param done 主线程回调；找不到站台或落脚处时为空
   */
  public void boardPlatformLocation(
      DriverTask task, java.util.function.Consumer<Optional<Location>> done) {
    Optional<org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeRegistry.SignNodeInfo> info =
        boardPlatformSign(task);
    World world = info.map(found -> Bukkit.getWorld(found.worldId())).orElse(null);
    if (info.isEmpty() || world == null) {
      done.accept(Optional.empty());
      return;
    }
    int x = info.get().x();
    int y = info.get().y();
    int z = info.get().z();
    world
        .getChunkAtAsync(x >> 4, z >> 4)
        .whenComplete(
            (chunk, error) -> {
              Runnable finish =
                  () ->
                      done.accept(error != null ? Optional.empty() : platformSpot(world, x, y, z));
              if (Bukkit.isPrimaryThread()) {
                finish.run();
              } else {
                Bukkit.getScheduler().runTask(plugin, finish);
              }
            });
  }

  /** 列车已停在这趟任务的接班站：时刻表绑定显示刚停过接班站，且站台还在停站或等发车。 */
  public boolean dwellingAtBoard(DriverTask task) {
    String train = task.trainName();
    if (train == null || train.isBlank()) {
      return false;
    }
    boolean atBoard =
        timetables()
            .flatMap(api -> api.getAssignment(train))
            .filter(
                assignment ->
                    task.key()
                        .matches(
                            assignment.timetableId(),
                            assignment.tripCode(),
                            assignment.serviceDate()))
            .filter(
                assignment -> assignment.lastStopSequence().orElse(-1) == task.boardStopSequence())
            .isPresent();
    return atBoard && dwelling(train);
  }

  /** 接班站台牌子：先认任务记下的站台节点，找不到时取同一车站的任一站台。 */
  private Optional<org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeRegistry.SignNodeInfo>
      boardPlatformSign(DriverTask task) {
    var registry = plugin.getSignNodeRegistry();
    if (registry == null) {
      return Optional.empty();
    }
    Optional<org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeRegistry.SignNodeInfo> info =
        Optional.ofNullable(task.boardNodeId())
            .filter(id -> !id.isBlank())
            .flatMap(
                id ->
                    registry.findByNodeId(
                        org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId.of(id), null));
    if (info.isEmpty() && task.operatorCode() != null && task.stationCode() != null) {
      String prefix =
          (task.operatorCode() + ":S:" + task.stationCode() + ":").toLowerCase(Locale.ROOT);
      info =
          registry.snapshotInfos().values().stream()
              .filter(
                  candidate ->
                      candidate
                          .definition()
                          .nodeId()
                          .value()
                          .toLowerCase(Locale.ROOT)
                          .startsWith(prefix))
              .findFirst();
    }
    return info;
  }

  /** 站台牌子所在轨道旁的落脚处（区块须已加载）；朝向轨道。 */
  private static Optional<Location> platformSpot(World world, int x, int y, int z) {
    Vector base =
        org.fetarute
            .fetaruteTCAddon
            .dispatcher
            .runtime
            .control
            .StopMarkIndex
            .stationRailOf(world, x, y, z)
            .map(piece -> piece.block().getLocation().toVector().add(new Vector(0.5, 0.0, 0.5)))
            .orElse(new Vector(x + 0.5, y, z + 0.5));
    return PickupSpot.find(base, null, PickupSpot.standable(world))
        .map(
            spot -> {
              Location target = new Location(world, spot.getX(), spot.getY(), spot.getZ());
              Vector facing = base.clone().subtract(spot);
              if (facing.lengthSquared() > 1.0e-6) {
                target.setDirection(facing);
              }
              return target;
            });
  }

  /** 任务接班站的位置（车站设置了位置时）。 */
  private Optional<Location> boardStation(DriverTask task) {
    return plugin
        .getStationDirectory()
        .flatMap(
            directory -> directory.snapshot().findStation(task.operatorCode(), task.stationCode()))
        .map(StationDirectory.StationEntry::station)
        .flatMap(
            station ->
                station
                    .location()
                    .flatMap(
                        location ->
                            station
                                .world()
                                .map(Bukkit::getWorld)
                                .map(world -> toLocation(world, station.world(), location))));
  }

  /** 送到哪里：驾驶员最近停过的车站的站台位置，没有就是停车点旁站台侧两格半。 */
  private Optional<Location> rescueTarget(DriverStationStop stop) {
    if (stop == null) {
      return Optional.empty();
    }
    World world = Bukkit.getWorld(stop.worldId());
    if (world == null) {
      return Optional.empty();
    }
    Optional<Location> stationLocation =
        plugin
            .getStationDirectory()
            .flatMap(directory -> directory.snapshot().stationOfNode(stop.node().value()))
            .map(StationDirectory.StationEntry::station)
            .flatMap(
                station ->
                    station
                        .location()
                        .map(location -> toLocation(world, station.world(), location)));
    if (stationLocation.isPresent() && stationLocation.get().getWorld() != null) {
      return stationLocation;
    }
    Vector point = stop.stopPoint();
    BlockFace side = stop.platformFace().orElse(null);
    if (side != null) {
      Vector offset = side.getDirection().normalize().multiply(2.5);
      point.add(offset);
    }
    return Optional.of(new Location(world, point.getX(), point.getY() + 1.0, point.getZ()));
  }

  private static Location toLocation(
      World fallback, Optional<String> worldName, StationLocation location) {
    World world = worldName.map(Bukkit::getWorld).orElse(fallback);
    return new Location(
        world, location.x(), location.y(), location.z(), location.yaw(), location.pitch());
  }

  // ---- 熔断 ----

  /**
   * 评估全网熔断。
   *
   * @param driverTrains 有驾驶员在岗的列车
   * @return 这一次是否触发熔断
   */
  public boolean tickBreaker(Set<String> driverTrains, DriverRecovery recovery, Instant now) {
    EtaService eta = plugin.getEtaService();
    if (eta == null || driverTrains.isEmpty()) {
      lastHolds = Map.of();
      return false;
    }
    Map<String, DriverCircuitBreaker.Hold> holds = new HashMap<>();
    for (MinecartGroup group : MinecartGroupStore.getGroups()) {
      if (group == null || !group.isValid() || !ManagedTrains.isFtaManaged(group.getProperties())) {
        continue;
      }
      String name = group.getProperties().getTrainName();
      Optional<TrainHold> hold = eta.currentHold(name);
      if (hold.isEmpty()) {
        continue;
      }
      Set<String> blockers = new HashSet<>();
      for (RuntimeStopState.Blocker blocker : hold.get().blockers()) {
        String owner = blocker.owner();
        if (owner != null && !owner.isBlank() && !"-".equals(owner) && !owner.equals(name)) {
          blockers.add(owner);
        }
      }
      holds.put(
          name, new DriverCircuitBreaker.Hold(Duration.between(hold.get().since(), now), blockers));
    }
    lastHolds = Map.copyOf(holds);
    boolean tripped = breaker.evaluate(holds, driverTrains, recovery, now);
    if (tripped) {
      plugin.getLogger().warning("驾驶员接班熔断：" + breaker.lastReason());
    }
    return tripped;
  }

  /** 后方被这列驾驶员列车直接挡住的车里，被扣最久的秒数（按最近一次熔断评估时的扣车情况）。 */
  public long blockedBehindSeconds(String driverTrain) {
    return DriverCongestion.blockedBehindSeconds(lastHolds, driverTrain);
  }

  /** 给运营人员看的熔断状态。 */
  public String breakerStatus(Instant now) {
    if (!breaker.open(now)) {
      return "closed";
    }
    return String.format(
        Locale.ROOT,
        "open %ds: %s",
        Duration.between(now, breaker.openUntil()).toSeconds(),
        breaker.lastReason());
  }
}
