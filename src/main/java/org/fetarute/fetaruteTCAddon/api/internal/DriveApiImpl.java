package org.fetarute.fetaruteTCAddon.api.internal;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.fetarute.fetaruteTCAddon.FetaruteTCAddon;
import org.fetarute.fetaruteTCAddon.api.drive.DriveApi;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableService;
import org.fetarute.fetaruteTCAddon.drive.driver.record.DriveTaskRecord;
import org.fetarute.fetaruteTCAddon.drive.driver.record.DriveTaskRecordRepository;
import org.fetarute.fetaruteTCAddon.drive.driver.score.ScoreRules;
import org.fetarute.fetaruteTCAddon.drive.driver.task.DriverTaskManager;
import org.fetarute.fetaruteTCAddon.drive.driver.task.TaskBoardEntries;
import org.fetarute.fetaruteTCAddon.drive.driver.task.TaskBoardSource;
import org.fetarute.fetaruteTCAddon.drive.driver.task.TaskViews;
import org.fetarute.fetaruteTCAddon.drive.session.DriveSessionManager;

/** 驾驶任务 API 的实现：转给驾驶会话管理器与时刻表；驾驶模块未加载时按不可用处理。 */
public final class DriveApiImpl implements DriveApi {

  private final FetaruteTCAddon plugin;

  public DriveApiImpl(FetaruteTCAddon plugin) {
    this.plugin = Objects.requireNonNull(plugin, "plugin");
  }

  private Optional<DriveSessionManager> manager() {
    return Optional.ofNullable(plugin.getDriveSessionManager());
  }

  @Override
  public boolean enabled() {
    return manager().map(DriveSessionManager::driverTasksOpen).orElse(false);
  }

  @Override
  public List<TaskOffer> offersAt(String stationCode, Instant from, Duration window, int limit) {
    requireMainThread("offersAt");
    Optional<DriveSessionManager> drive = manager();
    if (drive.isEmpty() || stationCode == null || stationCode.isBlank() || limit <= 0) {
      return List.of();
    }
    Instant start = from == null ? Instant.now() : from;
    Duration span = window == null || window.isNegative() ? Duration.ZERO : window;
    String code = stationCode.trim();
    List<TaskBoardEntries.Row> rows =
        TaskBoardEntries.select(
            TaskBoardSource.departures(
                plugin, new TaskBoardSource.Station(null, code, code), start, span),
            drive.get().tasks().takenKeys(),
            start,
            limit);
    return offers(plugin, rows);
  }

  /** 任务板的发车行换成可领取的车次（驾驶员与车掌共用）。 */
  static List<TaskOffer> offers(FetaruteTCAddon plugin, List<TaskBoardEntries.Row> rows) {
    Optional<TimetableService> timetables = plugin.getTimetableService();
    List<TaskOffer> offers = new ArrayList<>(rows.size());
    for (TaskBoardEntries.Row row : rows) {
      offers.add(
          new TaskOffer(
              row.key().timetableId(),
              row.key().tripCode(),
              row.key().serviceDate(),
              row.routeCode(),
              row.stopSequence(),
              row.plannedDeparture(),
              Optional.ofNullable(row.trainName()),
              row.dwelling(),
              timetables.flatMap(
                  service ->
                      service.depotOriginOf(row.key().timetableId(), row.key().tripCode()))));
    }
    return offers;
  }

  /** 驾驶记录换成公开的写法（驾驶员与车掌共用）。 */
  static TaskRecord record(DriveTaskRecord record) {
    return toRecord(record);
  }

  @Override
  public Optional<TaskView> taskOf(UUID playerId) {
    return playerId == null ? Optional.empty() : manager().flatMap(m -> m.taskView(playerId));
  }

  @Override
  public Optional<SessionView> sessionOf(UUID playerId) {
    return playerId == null
        ? Optional.empty()
        : manager().flatMap(m -> m.sessionViewSnapshot(playerId));
  }

  @Override
  public AssignResult assign(Player player, TaskRequest request) {
    Objects.requireNonNull(player, "player");
    Objects.requireNonNull(request, "request");
    requireMainThread("assign");
    Optional<DriveSessionManager> drive = manager();
    if (drive.isEmpty() || !drive.get().driverTasksOpen()) {
      return AssignResult.DISABLED;
    }
    TaskSpecResolver.Result resolved =
        TaskSpecResolver.resolve(
            plugin,
            new TaskSpecResolver.Request(
                request.timetableId(),
                request.tripCode(),
                request.serviceDate(),
                request.takeoverStation(),
                request.takeoverStopSequence(),
                request.handoverStation(),
                request.depotPickup(),
                request.source(),
                request.metadata(),
                request.rewards()));
    if (resolved.failure() != null) {
      return switch (resolved.failure()) {
        case UNAVAILABLE -> AssignResult.UNAVAILABLE;
        case INVALID_STATIONS -> AssignResult.INVALID_STATIONS;
        case DEPARTED -> AssignResult.DEPARTED;
      };
    }
    DriverTaskManager.TaskSpec spec = resolved.spec();
    DriverTaskManager.ClaimOutcome outcome =
        drive
            .get()
            .assignTask(player, spec, TaskViews.mode(request.mode()), request.notifyPlayer());
    return switch (outcome) {
      case CLAIMED -> AssignResult.ASSIGNED;
      case DISABLED -> AssignResult.DISABLED;
      case PROTECTION_ACTIVE -> AssignResult.BREAKER_OPEN;
      case ALREADY_HAS_TASK -> AssignResult.ALREADY_HAS_TASK;
      case TAKEN -> AssignResult.TAKEN;
      case UNAVAILABLE -> AssignResult.UNAVAILABLE;
      case CANCELLED -> AssignResult.CANCELLED;
    };
  }

  @Override
  public boolean abandon(UUID playerId, String reason) {
    if (playerId == null) {
      return false;
    }
    requireMainThread("abandon");
    return manager().map(m -> m.abandonTask(playerId, reason)).orElse(false);
  }

  @Override
  public CompletableFuture<List<TaskRecord>> records(UUID playerId, int limit) {
    if (playerId == null || limit <= 0) {
      return CompletableFuture.completedFuture(List.of());
    }
    return async(
        () -> {
          List<TaskRecord> records = new ArrayList<>();
          for (DriveTaskRecord record : loadRecords(playerId, limit)) {
            records.add(toRecord(record));
          }
          return records;
        });
  }

  @Override
  public CompletableFuture<TaskStats> stats(UUID playerId) {
    if (playerId == null) {
      return CompletableFuture.completedFuture(new TaskStats(0, 0, 0L, Optional.empty()));
    }
    return async(
        () ->
            repository()
                .map(repository -> repository.totalsByPlayer(playerId))
                .map(
                    totals ->
                        new TaskStats(
                            totals.tasks(),
                            totals.completed(),
                            totals.completedPoints(),
                            Optional.of(totals.bestGrade()).filter(grade -> !grade.isEmpty())))
                .orElseGet(() -> new TaskStats(0, 0, 0L, Optional.empty())));
  }

  private List<DriveTaskRecord> loadRecords(UUID playerId, int limit) {
    return repository()
        .map(repository -> repository.listByPlayer(playerId, limit))
        .orElse(List.of());
  }

  /** 驾驶记录仓库；存储未就绪时为空。 */
  private Optional<DriveTaskRecordRepository> repository() {
    org.fetarute.fetaruteTCAddon.storage.StorageManager storage = plugin.getStorageManager();
    if (storage == null || !storage.isReady() || storage.provider().isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(storage.provider().get().driveTaskRecords());
  }

  private static TaskRecord toRecord(DriveTaskRecord record) {
    return new TaskRecord(
        record.timetableId(),
        record.tripCode(),
        record.serviceDate(),
        record.routeCode(),
        record.trainName(),
        record.mode(),
        record.state(),
        record.points(),
        // 不评级的旧记录库里记作“-”，对外为空串。
        ScoreRules.UNGRADED.equals(record.grade()) ? "" : record.grade(),
        record.startedAt(),
        record.finishedAt());
  }

  private <T> CompletableFuture<T> async(Supplier<T> work) {
    Executor executor =
        plugin.isEnabled()
            ? runnable -> Bukkit.getScheduler().runTaskAsynchronously(plugin, runnable)
            : Runnable::run;
    return CompletableFuture.supplyAsync(work, executor);
  }

  private static void requireMainThread(String action) {
    if (!Bukkit.isPrimaryThread()) {
      throw new IllegalStateException("DriveApi#" + action + " 只能在服务器主线程调用");
    }
  }
}
