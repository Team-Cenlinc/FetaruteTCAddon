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
import org.fetarute.fetaruteTCAddon.api.timetable.TimetableApi;
import org.fetarute.fetaruteTCAddon.company.api.StationDirectory;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteTerminals;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableService;
import org.fetarute.fetaruteTCAddon.drive.driver.record.DriveTaskRecord;
import org.fetarute.fetaruteTCAddon.drive.driver.task.DriverTaskManager;
import org.fetarute.fetaruteTCAddon.drive.driver.task.TaskBoardEntries;
import org.fetarute.fetaruteTCAddon.drive.driver.task.TaskBoardSource;
import org.fetarute.fetaruteTCAddon.drive.driver.task.TaskKey;
import org.fetarute.fetaruteTCAddon.drive.driver.task.TaskStations;
import org.fetarute.fetaruteTCAddon.drive.driver.task.TaskViews;
import org.fetarute.fetaruteTCAddon.drive.session.DriveSessionManager;

/** 驾驶任务 API 的实现：转给驾驶会话管理器与时刻表；驾驶模块未加载时按不可用处理。 */
public final class DriveApiImpl implements DriveApi {

  /** 累计成绩最多读多少条记录。 */
  private static final int STATS_RECORD_LIMIT = 5000;

  private static final String GRADE_ORDER = "SABCD";

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
    Optional<TimetableService> timetables = plugin.getTimetableService();
    Optional<TimetableService.TripPlan> plan =
        timetables.flatMap(
            service ->
                service.tripPlan(request.timetableId(), request.tripCode(), request.serviceDate()));
    if (plan.isEmpty()) {
      return AssignResult.UNAVAILABLE;
    }
    List<TaskStations.Stop> stops = new ArrayList<>();
    for (TimetableService.PlannedStop stop : plan.get().stops()) {
      stops.add(new TaskStations.Stop(stop.stopSequence(), stop.stationCode(), stop.stops()));
    }
    Optional<TaskStations.Resolved> resolved =
        TaskStations.resolve(stops, request.boardStation(), request.alightStation());
    if (resolved.isEmpty()) {
      return AssignResult.INVALID_STATIONS;
    }
    TimetableService.PlannedStop board = plannedStop(plan.get(), resolved.get().board().sequence());
    if (board == null || board.departure().isEmpty()) {
      return AssignResult.UNAVAILABLE;
    }
    boolean cancelled =
        timetables
            .flatMap(
                service ->
                    service.cancellationOf(
                        request.timetableId(), plan.get().tripId(), request.serviceDate()))
            .filter(cancellation -> cancellation.covers(board.stopSequence()))
            .isPresent();
    if (cancelled) {
      return AssignResult.UNAVAILABLE;
    }
    TaskKey key = new TaskKey(request.timetableId(), plan.get().tripCode(), request.serviceDate());
    Optional<TimetableApi.TrainAssignment> assignment = assignmentOf(key);
    if (assignment
        .flatMap(TimetableApi.TrainAssignment::lastStopSequence)
        .filter(last -> last > board.stopSequence())
        .isPresent()) {
      return AssignResult.DEPARTED;
    }
    Optional<TimetableService.PlannedStop> alight =
        resolved
            .get()
            .alight()
            .map(stop -> plannedStop(plan.get(), stop.sequence()))
            .filter(Objects::nonNull);
    StationName boardName = stationName(board);
    Optional<StationName> alightName = alight.map(this::stationName);
    DriverTaskManager.TaskSpec spec =
        new DriverTaskManager.TaskSpec(
            key,
            plan.get().routeCode(),
            boardName.operatorCode(),
            boardName.code(),
            boardName.name(),
            board.nodeId().orElse(null),
            board.stopSequence(),
            board.departure().get(),
            assignment.map(TimetableApi.TrainAssignment::trainName).orElse(null),
            alight.map(TimetableService.PlannedStop::stopSequence).orElse(-1),
            alightName.map(StationName::code).orElse(""),
            alightName.map(StationName::name).orElse(""),
            request.depotPickup(),
            request.source(),
            request.metadata());
    DriverTaskManager.ClaimOutcome outcome =
        drive
            .get()
            .assignTask(player, spec, TaskViews.mode(request.mode()), request.notifyPlayer());
    return switch (outcome) {
      case CLAIMED -> AssignResult.ASSIGNED;
      case DISABLED -> AssignResult.DISABLED;
      case BREAKER_OPEN -> AssignResult.BREAKER_OPEN;
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
    return async(() -> statsOf(loadRecords(playerId, STATS_RECORD_LIMIT)));
  }

  /** 累计成绩：开过车的任务数、开完的任务数、总分、最好的评级。 */
  static TaskStats statsOf(List<DriveTaskRecord> records) {
    int completed = 0;
    long total = 0L;
    String best = null;
    for (DriveTaskRecord record : records) {
      if ("COMPLETED".equals(record.state())) {
        completed++;
      }
      total += Math.max(0, record.points());
      int rank = GRADE_ORDER.indexOf(record.grade());
      if (rank >= 0 && (best == null || rank < GRADE_ORDER.indexOf(best))) {
        best = record.grade();
      }
    }
    return new TaskStats(records.size(), completed, total, Optional.ofNullable(best));
  }

  private List<DriveTaskRecord> loadRecords(UUID playerId, int limit) {
    org.fetarute.fetaruteTCAddon.storage.StorageManager storage = plugin.getStorageManager();
    if (storage == null || !storage.isReady() || storage.provider().isEmpty()) {
      return List.of();
    }
    return storage.provider().get().driveTaskRecords().listByPlayer(playerId, limit);
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
        record.grade(),
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

  private static Optional<TimetableApi.TrainAssignment> assignmentOf(TaskKey key) {
    return DriverTaskManager.timetables()
        .flatMap(
            api ->
                api.listAssignments().stream()
                    .filter(
                        candidate ->
                            key.matches(
                                candidate.timetableId(),
                                candidate.tripCode(),
                                candidate.serviceDate()))
                    .findFirst());
  }

  private static TimetableService.PlannedStop plannedStop(
      TimetableService.TripPlan plan, int sequence) {
    for (TimetableService.PlannedStop stop : plan.stops()) {
      if (stop.stopSequence() == sequence) {
        return stop;
      }
    }
    return null;
  }

  /** 车站的运营商、站码与站名。 */
  private record StationName(String operatorCode, String code, String name) {}

  private StationName stationName(TimetableService.PlannedStop stop) {
    String code = stop.stationCode().orElse("");
    String operator =
        stop.nodeId()
            .flatMap(RouteTerminals::stationIdentityOfNode)
            .map(RouteTerminals.StationRef::operatorCode)
            .orElse("");
    String name =
        plugin
            .getStationDirectory()
            .flatMap(directory -> directory.snapshot().findStation(operator, code))
            .map(StationDirectory.StationEntry::name)
            .orElse(code);
    return new StationName(operator, code, name);
  }

  private static void requireMainThread(String action) {
    if (!Bukkit.isPrimaryThread()) {
      throw new IllegalStateException("DriveApi#" + action + " 只能在服务器主线程调用");
    }
  }
}
