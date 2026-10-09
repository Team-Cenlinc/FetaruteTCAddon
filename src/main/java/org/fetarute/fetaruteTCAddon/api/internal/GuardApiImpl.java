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
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.fetarute.fetaruteTCAddon.FetaruteTCAddon;
import org.fetarute.fetaruteTCAddon.api.drive.DriveApi;
import org.fetarute.fetaruteTCAddon.api.drive.GuardApi;
import org.fetarute.fetaruteTCAddon.drive.driver.record.DriveTaskRecord;
import org.fetarute.fetaruteTCAddon.drive.driver.task.TaskBoardEntries;
import org.fetarute.fetaruteTCAddon.drive.driver.task.TaskBoardSource;
import org.fetarute.fetaruteTCAddon.drive.guard.GuardSessionManager;
import org.fetarute.fetaruteTCAddon.drive.guard.GuardTasks;
import org.fetarute.fetaruteTCAddon.drive.session.DriveSessionManager;

/** 车掌 API 的实现：转给车掌会话管理器与时刻表；驾驶模块或车掌功能未加载时按不可用处理。 */
public final class GuardApiImpl implements GuardApi {

  private final FetaruteTCAddon plugin;

  public GuardApiImpl(FetaruteTCAddon plugin) {
    this.plugin = Objects.requireNonNull(plugin, "plugin");
  }

  private Optional<GuardSessionManager> guards() {
    DriveSessionManager drive = plugin.getDriveSessionManager();
    return drive == null ? Optional.empty() : drive.guards();
  }

  @Override
  public boolean enabled() {
    return guards().map(GuardSessionManager::available).orElse(false);
  }

  @Override
  public List<DriveApi.TaskOffer> offersAt(
      String stationCode, Instant from, Duration window, int limit) {
    requireMainThread("offersAt");
    Optional<GuardSessionManager> guards = guards();
    if (guards.isEmpty() || stationCode == null || stationCode.isBlank() || limit <= 0) {
      return List.of();
    }
    Instant start = from == null ? Instant.now() : from;
    Duration span = window == null || window.isNegative() ? Duration.ZERO : window;
    String code = stationCode.trim();
    return DriveApiImpl.offers(
        plugin,
        TaskBoardEntries.select(
            TaskBoardSource.departures(
                plugin, new TaskBoardSource.Station(null, code, code), start, span),
            guards.get().tasks().claimants().keySet(),
            start,
            limit));
  }

  @Override
  public Optional<TaskView> taskOf(UUID playerId) {
    return guards().flatMap(guards -> guards.taskView(playerId));
  }

  @Override
  public Optional<DutyView> dutyOf(UUID playerId) {
    return guards().flatMap(guards -> guards.dutyView(playerId));
  }

  @Override
  public Optional<UUID> guardOf(String trainName) {
    return guards().flatMap(guards -> guards.guardOfTrainName(trainName));
  }

  @Override
  public AssignResult assign(Player player, TaskRequest request) {
    Objects.requireNonNull(player, "player");
    Objects.requireNonNull(request, "request");
    requireMainThread("assign");
    Optional<GuardSessionManager> guards = guards();
    if (guards.isEmpty() || !guards.get().available()) {
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
                false,
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
    return result(guards.get().assign(player, resolved.spec(), request.notifyPlayer()));
  }

  /** 车掌任务的领取结果换成公开的写法。 */
  static AssignResult result(GuardTasks.ClaimOutcome outcome) {
    return switch (outcome) {
      case CLAIMED -> AssignResult.ASSIGNED;
      case DISABLED -> AssignResult.DISABLED;
      case ON_DUTY -> AssignResult.ON_DUTY;
      case DRIVING -> AssignResult.DRIVING;
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
    return guards().map(guards -> guards.abandonTask(playerId, reason)).orElse(false);
  }

  @Override
  public CompletableFuture<List<DriveApi.TaskRecord>> records(UUID playerId, int limit) {
    if (playerId == null || limit <= 0) {
      return CompletableFuture.completedFuture(List.of());
    }
    Executor executor =
        plugin.isEnabled()
            ? runnable -> Bukkit.getScheduler().runTaskAsynchronously(plugin, runnable)
            : Runnable::run;
    return CompletableFuture.supplyAsync(
        () -> {
          org.fetarute.fetaruteTCAddon.storage.StorageManager storage = plugin.getStorageManager();
          if (storage == null || !storage.isReady() || storage.provider().isEmpty()) {
            return List.<DriveApi.TaskRecord>of();
          }
          List<DriveApi.TaskRecord> records = new ArrayList<>();
          for (DriveTaskRecord record :
              storage
                  .provider()
                  .get()
                  .driveTaskRecords()
                  .listByPlayerAndMode(playerId, DriveTaskRecord.MODE_GUARD, limit)) {
            records.add(DriveApiImpl.record(record));
          }
          return records;
        },
        executor);
  }

  private static void requireMainThread(String action) {
    if (!Bukkit.isPrimaryThread()) {
      throw new IllegalStateException("GuardApi#" + action + " 只能在服务器主线程调用");
    }
  }
}
