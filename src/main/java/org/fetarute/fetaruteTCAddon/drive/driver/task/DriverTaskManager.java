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
import org.fetarute.fetaruteTCAddon.drive.driver.DriverCircuitBreaker;
import org.fetarute.fetaruteTCAddon.drive.driver.DriverRecovery;
import org.fetarute.fetaruteTCAddon.drive.driver.DrivingMode;
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
    UNAVAILABLE
  }

  /** 列车没在接班站停站、计划发车又已过去这么久，任务作废。 */
  static final Duration EXPIRE_AFTER = Duration.ofMinutes(3);

  /** 交还后列车走过这么远就不再救援。 */
  private static final double RESCUE_PROGRESS_BLOCKS = 3.0;

  /** 交还后等着救援的驾驶员。 */
  private record RescueWatch(
      UUID playerId, String trainName, Vector headAtStart, long dueTick, Location target) {}

  private final FetaruteTCAddon plugin;
  private final Consumer<String> trace;
  private final Map<UUID, DriverTask> byPlayer = new HashMap<>();
  private final Map<TaskKey, UUID> byKey = new HashMap<>();
  private final List<RescueWatch> rescues = new ArrayList<>();
  private final DriverCircuitBreaker breaker = new DriverCircuitBreaker();

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
    return keys;
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
    DriverTask previous = byPlayer.put(player.getUniqueId(), task);
    if (previous != null) {
      byKey.remove(previous.key(), previous.playerId());
    }
    byKey.put(row.key(), player.getUniqueId());
    trace.accept(
        "领取任务 "
            + player.getName()
            + " -> "
            + row.routeCode()
            + " "
            + row.key().tripCode()
            + " "
            + mode);
    return ClaimOutcome.CLAIMED;
  }

  /** 放弃还没开始驾驶的任务；驾驶中的由调用方先请求交还。 */
  public boolean abandon(UUID playerId, String reason) {
    DriverTask task = byPlayer.get(playerId);
    if (task == null || task.state().finished()) {
      return false;
    }
    task.finish(DriverTask.State.ABANDONED, reason);
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
    task.finish(finalState, reason);
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
      task.finish(DriverTask.State.FAILED, reason);
    }
  }

  /** 驾驶中的任务完成。 */
  public void complete(UUID playerId) {
    DriverTask task = byPlayer.get(playerId);
    if (task != null && task.state() == DriverTask.State.DRIVING) {
      task.finish(DriverTask.State.COMPLETED, "terminal");
    }
  }

  /**
   * 列车是否正停在这趟车次的终点站：时刻表绑定显示已过本站、没有下一个停靠站。
   *
   * @return 查不到绑定时为 {@code false}
   */
  public boolean atTerminal(String trainName) {
    return timetables()
        .flatMap(api -> api.getAssignment(trainName))
        .map(
            assignment ->
                assignment.lastStopSequence().isPresent()
                    && assignment.nextStopSequence().isEmpty())
        .orElse(false);
  }

  /** 时刻表接口；公开 API 未就绪时为空。 */
  public static Optional<TimetableApi> timetables() {
    return FetaruteApi.get().map(FetaruteApi::timetables);
  }

  // ---- 等车与接管 ----

  /** 接管尝试的回调：返回是否已开始驾驶。 */
  public interface Starter {
    /**
     * @return 开始驾驶时为 {@code null}；否则是给玩家的提示语言键
     */
    String tryStart(Player player, DriverTask task);
  }

  /**
   * 推进已领取的任务：对上列车、到站时接管、过时作废。
   *
   * @param notify 给玩家发动作栏提示（语言键、占位符）
   */
  public void tickClaims(Starter starter, Notifier notify, Instant now) {
    Optional<TimetableApi> api = timetables();
    Collection<TimetableApi.TrainAssignment> assignments =
        api.map(TimetableApi::listAssignments).orElse(List.of());
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
        if (now.isAfter(task.plannedDeparture().plus(EXPIRE_AFTER))) {
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

  /** 给玩家发提示。 */
  public interface Notifier {
    void send(Player player, String key, Map<String, String> values);
  }

  private void expire(DriverTask task, Notifier notify, String reason) {
    task.finish(DriverTask.State.EXPIRED, reason);
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
    Location target = rescueTarget(lastStop).orElse(null);
    Vector head = group.head().getEntity().getLocation().toVector();
    rescues.add(
        new RescueWatch(
            player.getUniqueId(), group.getProperties().getTrainName(), head, dueTick, target));
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
      if (nowTick < watch.dueTick()) {
        continue;
      }
      it.remove();
      trace.accept("救援 " + player.getName() + " 离开 " + watch.trainName());
      player.leaveVehicle();
      if (watch.target() != null) {
        player.teleport(watch.target());
      }
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
    boolean tripped = breaker.evaluate(holds, driverTrains, recovery, now);
    if (tripped) {
      plugin.getLogger().warning("驾驶员接班熔断：" + breaker.lastReason());
    }
    return tripped;
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
