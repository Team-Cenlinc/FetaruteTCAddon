package org.fetarute.fetaruteTCAddon.dispatcher.health;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.DwellRegistry;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchService;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspect;

/**
 * 列车健康监控器：检测列车运行异常并尝试自动修复。
 *
 * <p>检测项：
 *
 * <ul>
 *   <li>长时间静止（stall）：有 PROCEED 信号但速度为 0（排除正在停站的列车）
 *   <li>进度不推进：route 索引与最近经过图节点均长时间不变化（排除正在停站的列车）
 * </ul>
 *
 * <p>自动修复采用分级策略（带冷却）：
 *
 * <ul>
 *   <li>STALL：先 refreshSignal，再升级到 forceRelaunch
 *   <li>PROGRESS_STUCK：先 refreshSignal，再升级到 reissueDestination，最后 forceRelaunch
 *   <li>STOP 下的长时间停滞也会按同样链路升级，避免只 refresh 不解锁
 *   <li>STOP 信号下的 progress stuck 允许更长宽限，避免把正常排队误判为故障
 * </ul>
 */
public final class TrainHealthMonitor {

  /** 列车状态快照。 */
  private record TrainSnapshot(
      int progressIndex,
      String lastPassedGraphNodeId,
      SignalAspect signal,
      double speedBpt,
      Instant captureTime,
      Instant lastMoveTime,
      Instant lastProgressTime) {}

  /** 每列车恢复状态：记录分级进度与最近一次恢复时间。 */
  private static final class RecoveryState {
    private Instant lastStallAttemptAt = Instant.EPOCH;
    private Instant lastProgressAttemptAt = Instant.EPOCH;
    private Instant lastDeadlockAttemptAt = Instant.EPOCH;
    private int stallStage;
    private int progressStage;
    private int deadlockStage;

    private void resetStall() {
      stallStage = 0;
      lastStallAttemptAt = Instant.EPOCH;
    }

    private void resetProgress() {
      progressStage = 0;
      lastProgressAttemptAt = Instant.EPOCH;
    }

    private void resetDeadlock() {
      deadlockStage = 0;
      lastDeadlockAttemptAt = Instant.EPOCH;
    }
  }

  private final RuntimeDispatchService dispatchService;
  private final DwellRegistry dwellRegistry;
  private final HealthAlertBus alertBus;
  private final Consumer<String> debugLogger;

  private final Map<String, TrainSnapshot> snapshots = new ConcurrentHashMap<>();
  private final Map<String, RecoveryState> recoveryStates = new ConcurrentHashMap<>();

  /** 静止阈值（秒）：有 PROCEED 信号但静止超过此时间触发告警。 */
  private Duration stallThreshold = Duration.ofSeconds(30);

  /** 进度不推进阈值（秒）。 */
  private Duration progressStuckThreshold = Duration.ofSeconds(60);

  /** STOP 信号下 progress stuck 的宽限阈值（秒）。 */
  private Duration progressStopGraceThreshold = Duration.ofSeconds(180);

  /** 自动修复动作冷却，避免每轮检查都重复触发同一恢复动作。 */
  private Duration recoveryCooldown = Duration.ofSeconds(10);

  /** STOP 互卡识别阈值（秒）：用于比 progress STOP 宽限更早触发“解锁尝试”。 */
  private Duration deadlockThreshold = Duration.ofSeconds(45);

  /** 阻塞快照有效期：仅在最近可见的 blocker 信息上执行互卡解锁。 */
  private Duration blockerSnapshotMaxAge = Duration.ofSeconds(20);

  /** 是否启用自动修复。 */
  private boolean autoFixEnabled = true;

  /** 低速判定阈值（blocks per tick）。 */
  private double lowSpeedThresholdBpt = 0.01;

  /** 互卡对级别冷却：避免同一对列车在短窗口内反复执行解锁动作。 */
  private final Map<String, Instant> deadlockPairLastAttemptAt = new ConcurrentHashMap<>();

  public TrainHealthMonitor(
      RuntimeDispatchService dispatchService,
      DwellRegistry dwellRegistry,
      HealthAlertBus alertBus,
      Consumer<String> debugLogger) {
    this.dispatchService = Objects.requireNonNull(dispatchService, "dispatchService");
    this.dwellRegistry = dwellRegistry; // 可为 null，表示不排除停站列车
    this.alertBus = alertBus != null ? alertBus : new HealthAlertBus();
    this.debugLogger = debugLogger != null ? debugLogger : msg -> {};
  }

  /** 设置静止阈值。 */
  public void setStallThreshold(Duration threshold) {
    if (threshold != null && !threshold.isNegative()) {
      this.stallThreshold = threshold;
    }
  }

  /** 设置进度不推进阈值。 */
  public void setProgressStuckThreshold(Duration threshold) {
    if (threshold != null && !threshold.isNegative()) {
      this.progressStuckThreshold = threshold;
    }
  }

  /** 设置 STOP 信号下 progress stuck 的宽限阈值。 */
  public void setProgressStopGraceThreshold(Duration threshold) {
    if (threshold != null && !threshold.isNegative()) {
      this.progressStopGraceThreshold = threshold;
    }
  }

  /** 设置 STOP 互卡识别阈值。 */
  public void setDeadlockThreshold(Duration threshold) {
    if (threshold != null && !threshold.isNegative() && !threshold.isZero()) {
      this.deadlockThreshold = threshold;
    }
  }

  /** 设置 blocker 快照有效期。 */
  public void setBlockerSnapshotMaxAge(Duration maxAge) {
    if (maxAge != null && !maxAge.isNegative() && !maxAge.isZero()) {
      this.blockerSnapshotMaxAge = maxAge;
    }
  }

  /** 设置自动修复动作冷却时间。 */
  public void setRecoveryCooldown(Duration cooldown) {
    if (cooldown != null && !cooldown.isNegative()) {
      this.recoveryCooldown = cooldown;
    }
  }

  /** 设置是否启用自动修复。 */
  public void setAutoFixEnabled(boolean enabled) {
    this.autoFixEnabled = enabled;
  }

  /** 设置低速判定阈值。 */
  public void setLowSpeedThresholdBpt(double threshold) {
    if (threshold >= 0) {
      this.lowSpeedThresholdBpt = threshold;
    }
  }

  /**
   * 立即执行一次“互卡优先”的强制解锁。
   *
   * <p>该入口用于人工介入（例如命令行触发），不等待 progress stuck 阈值，也不受恢复冷却限制。策略仍保持 “非破坏优先”：先刷新信号与重发
   * destination，仅在必要时升级到 relaunch。
   *
   * @param activeTrains 当前存活列车集合
   * @param now 当前时间（为空时使用当前时刻）
   * @return 成功执行的互卡解锁次数（按列车对计数）
   */
  public int forceUnlockNow(Set<String> activeTrains, Instant now) {
    Instant effectiveNow = now != null ? now : Instant.now();
    Set<String> active = activeTrains == null ? Set.of() : Set.copyOf(activeTrains);
    Set<String> activeKeys = new java.util.HashSet<>();
    for (String trainName : active) {
      String key = keyOf(trainName);
      if (key != null) {
        activeKeys.add(key);
      }
    }

    int fixedCount = 0;
    for (String trainName : active) {
      String key = keyOf(trainName);
      if (key == null) {
        continue;
      }
      Optional<RuntimeDispatchService.TrainRuntimeState> stateOpt =
          dispatchService.getTrainState(trainName);
      if (stateOpt.isEmpty()) {
        continue;
      }
      if (dwellRegistry != null && dwellRegistry.remainingSeconds(trainName).isPresent()) {
        continue;
      }
      RuntimeDispatchService.TrainRuntimeState state = stateOpt.get();
      boolean stationary = state.speedBlocksPerTick() <= lowSpeedThresholdBpt;
      if (!stationary || state.signalAspect() == SignalAspect.PROCEED) {
        continue;
      }
      Set<String> blockers = dispatchService.recentBlockerTrains(trainName, blockerSnapshotMaxAge);
      Optional<String> mutualBlocker = findMutualBlocker(trainName, activeKeys);
      if (mutualBlocker.isEmpty()) {
        if (state.signalAspect() == SignalAspect.STOP && !blockers.isEmpty()) {
          debugLogger.accept("TrainHealthMonitor 手动解锁单车: train=" + trainName);
          dispatchService.refreshSignalByName(trainName);
          boolean fixed = dispatchService.reissueDestinationByName(trainName);
          if (!fixed) {
            fixed = dispatchService.forceRelaunchByName(trainName);
          }
          if (fixed) {
            fixedCount++;
          }
        }
        continue;
      }
      if (!isPairLeader(trainName, mutualBlocker.get())) {
        continue;
      }
      RecoveryState recovery = recoveryStates.computeIfAbsent(key, unused -> new RecoveryState());
      if (forceFixMutualDeadlock(trainName, mutualBlocker.get(), recovery, effectiveNow)) {
        fixedCount++;
      }
    }
    return fixedCount;
  }

  /**
   * 执行一次健康检查。
   *
   * @param activeTrains 当前存活的列车名集合
   * @param now 当前时间
   * @return 检查结果
   */
  public CheckResult check(Set<String> activeTrains, Instant now) {
    if (now == null) {
      now = Instant.now();
    }
    Set<String> active = activeTrains == null ? Set.of() : Set.copyOf(activeTrains);
    Set<String> activeKeys = new java.util.HashSet<>();
    for (String trainName : active) {
      String key = keyOf(trainName);
      if (key != null) {
        activeKeys.add(key);
      }
    }

    // 清理已消失列车的快照/恢复状态
    snapshots.keySet().removeIf(name -> !activeKeys.contains(name));
    recoveryStates.keySet().removeIf(name -> !activeKeys.contains(name));

    int stallCount = 0;
    int progressStuckCount = 0;
    int fixedCount = 0;

    for (String trainName : active) {
      String key = keyOf(trainName);
      if (key == null) {
        continue;
      }
      Optional<RuntimeDispatchService.TrainRuntimeState> stateOpt =
          dispatchService.getTrainState(trainName);
      if (stateOpt.isEmpty()) {
        continue;
      }
      RuntimeDispatchService.TrainRuntimeState state = stateOpt.get();

      TrainSnapshot prev = snapshots.get(key);
      RecoveryState recovery = recoveryStates.computeIfAbsent(key, unused -> new RecoveryState());

      int currentProgress = state.progressIndex();
      String currentGraphNode = state.lastPassedGraphNode().map(Object::toString).orElse(null);
      SignalAspect currentSignal = state.signalAspect();
      double currentSpeed = state.speedBlocksPerTick();

      boolean isMoving = currentSpeed > lowSpeedThresholdBpt;
      boolean progressed =
          prev != null
              && (currentProgress != prev.progressIndex()
                  || !Objects.equals(currentGraphNode, prev.lastPassedGraphNodeId()));

      // 更新快照
      Instant lastMove =
          prev != null && isMoving ? now : (prev != null ? prev.lastMoveTime() : now);
      Instant lastProgress =
          prev != null && progressed ? now : (prev != null ? prev.lastProgressTime() : now);

      TrainSnapshot current =
          new TrainSnapshot(
              currentProgress,
              currentGraphNode,
              currentSignal,
              currentSpeed,
              now,
              lastMove,
              lastProgress);
      snapshots.put(key, current);

      if (prev == null) {
        continue; // 首次采样，跳过检测
      }
      if (progressed) {
        recovery.resetProgress();
        recovery.resetDeadlock();
      }

      // 排除正在停站（dwell）的列车：停站期间静止和进度不变都是正常的
      boolean isDwelling =
          dwellRegistry != null && dwellRegistry.remainingSeconds(trainName).isPresent();
      if (isDwelling) {
        recovery.resetStall();
        recovery.resetProgress();
        recovery.resetDeadlock();
        continue;
      }

      // 检测：有 PROCEED 信号但长时间静止
      if (currentSignal == SignalAspect.PROCEED && !isMoving) {
        Duration stallDuration = Duration.between(lastMove, now);
        if (stallDuration.compareTo(stallThreshold) > 0) {
          stallCount++;
          boolean fixed = false;
          if (autoFixEnabled) {
            fixed = tryFixStall(trainName, recovery, now);
            if (fixed) {
              fixedCount++;
            }
          }
          alertBus.publish(
              fixed
                  ? HealthAlert.fixed(
                      HealthAlert.AlertType.STALL,
                      trainName,
                      "列车静止已修复: 持续=" + stallDuration.toSeconds() + "秒")
                  : HealthAlert.of(
                      HealthAlert.AlertType.STALL,
                      trainName,
                      "列车静止: 持续=" + stallDuration.toSeconds() + "秒"));
        }
      } else {
        recovery.resetStall();
      }

      // 检测：进度长时间不推进
      Duration progressDuration = Duration.between(lastProgress, now);
      Optional<String> mutualBlocker =
          !progressed
                  && !isMoving
                  && currentSignal == SignalAspect.STOP
                  && progressDuration.compareTo(deadlockThreshold) > 0
              ? findMutualBlocker(trainName, activeKeys)
              : Optional.empty();
      if (mutualBlocker.isPresent()) {
        if (!isPairLeader(trainName, mutualBlocker.get())) {
          recovery.resetDeadlock();
          continue;
        }
        progressStuckCount++;
        boolean fixed = false;
        if (autoFixEnabled) {
          fixed = tryFixMutualDeadlock(trainName, mutualBlocker.get(), recovery, now);
          if (fixed) {
            fixedCount++;
          }
        }
        String message =
            "互卡停滞: train="
                + trainName
                + " blocker="
                + mutualBlocker.get()
                + " 持续="
                + progressDuration.toSeconds()
                + "秒 idx="
                + currentProgress
                + " signal="
                + currentSignal;
        alertBus.publish(
            fixed
                ? HealthAlert.fixed(HealthAlert.AlertType.PROGRESS_STUCK, trainName, message)
                : HealthAlert.of(HealthAlert.AlertType.PROGRESS_STUCK, trainName, message));
        continue;
      }
      recovery.resetDeadlock();

      boolean allowProgressStuckCheck =
          !progressed
              && progressDuration.compareTo(progressStuckThreshold) > 0
              && (currentSignal != SignalAspect.STOP
                  || progressDuration.compareTo(progressStopGraceThreshold) > 0);
      if (allowProgressStuckCheck) {
        progressStuckCount++;
        boolean fixed = false;
        if (autoFixEnabled) {
          fixed = tryFixProgressStuck(trainName, currentSignal, progressDuration, recovery, now);
          if (fixed) {
            fixedCount++;
          }
        }
        alertBus.publish(
            fixed
                ? HealthAlert.fixed(
                    HealthAlert.AlertType.PROGRESS_STUCK,
                    trainName,
                    "进度停滞已修复: 持续="
                        + progressDuration.toSeconds()
                        + "秒 idx="
                        + currentProgress
                        + " signal="
                        + currentSignal)
                : HealthAlert.of(
                    HealthAlert.AlertType.PROGRESS_STUCK,
                    trainName,
                    "进度停滞: 持续="
                        + progressDuration.toSeconds()
                        + "秒 idx="
                        + currentProgress
                        + " signal="
                        + currentSignal));
      } else if (progressed || progressDuration.compareTo(progressStuckThreshold) <= 0) {
        recovery.resetProgress();
      }
    }

    return new CheckResult(stallCount, progressStuckCount, fixedCount);
  }

  /** 清除所有快照。 */
  public void clear() {
    snapshots.clear();
    recoveryStates.clear();
    deadlockPairLastAttemptAt.clear();
  }

  /** 尝试修复静止列车。 */
  private boolean tryFixStall(String trainName, RecoveryState recovery, Instant now) {
    if (!canAttempt(now, recovery.lastStallAttemptAt)) {
      return false;
    }
    int nextStage = Math.min(recovery.stallStage + 1, 2);
    boolean fixed;
    if (nextStage == 1) {
      debugLogger.accept("TrainHealthMonitor 修复静止(stage=refresh): train=" + trainName);
      dispatchService.refreshSignalByName(trainName);
      fixed = true;
    } else {
      debugLogger.accept("TrainHealthMonitor 修复静止(stage=relaunch): train=" + trainName);
      fixed = dispatchService.forceRelaunchByName(trainName);
      if (!fixed) {
        dispatchService.refreshSignalByName(trainName);
      }
    }
    recovery.lastStallAttemptAt = now;
    recovery.stallStage = fixed ? nextStage : 0;
    return fixed;
  }

  /** 尝试修复进度停滞。 */
  private boolean tryFixProgressStuck(
      String trainName,
      SignalAspect currentSignal,
      Duration progressDuration,
      RecoveryState recovery,
      Instant now) {
    if (!canAttempt(now, recovery.lastProgressAttemptAt)) {
      return false;
    }
    if (currentSignal == SignalAspect.STOP) {
      if (progressDuration.compareTo(progressStopGraceThreshold) <= 0) {
        return false;
      }
      int nextStage = Math.min(recovery.progressStage + 1, 3);
      boolean fixed;
      if (nextStage == 1) {
        debugLogger.accept("TrainHealthMonitor 修复停滞(stage=refresh-stop): train=" + trainName);
        dispatchService.refreshSignalByName(trainName);
        fixed = true;
      } else if (nextStage == 2) {
        debugLogger.accept("TrainHealthMonitor 修复停滞(stage=reissue-stop): train=" + trainName);
        fixed = dispatchService.reissueDestinationByName(trainName);
        if (!fixed) {
          dispatchService.refreshSignalByName(trainName);
        }
      } else {
        debugLogger.accept("TrainHealthMonitor 修复停滞(stage=relaunch-stop): train=" + trainName);
        fixed = dispatchService.forceRelaunchByName(trainName);
        if (!fixed) {
          fixed = dispatchService.reissueDestinationByName(trainName);
        }
        if (!fixed) {
          dispatchService.refreshSignalByName(trainName);
        }
      }
      recovery.lastProgressAttemptAt = now;
      // STOP 停滞下允许“失败后继续升级”，避免永远停留在 refresh/reissue。
      recovery.progressStage = nextStage;
      return fixed;
    }

    int nextStage = Math.min(recovery.progressStage + 1, 3);
    boolean fixed;
    if (nextStage == 1) {
      debugLogger.accept("TrainHealthMonitor 修复停滞(stage=refresh): train=" + trainName);
      dispatchService.refreshSignalByName(trainName);
      fixed = true;
    } else if (nextStage == 2) {
      debugLogger.accept("TrainHealthMonitor 修复停滞(stage=reissue): train=" + trainName);
      fixed = dispatchService.reissueDestinationByName(trainName);
      if (!fixed) {
        dispatchService.refreshSignalByName(trainName);
      }
    } else {
      debugLogger.accept("TrainHealthMonitor 修复停滞(stage=relaunch): train=" + trainName);
      fixed = dispatchService.forceRelaunchByName(trainName);
      if (!fixed) {
        fixed = dispatchService.reissueDestinationByName(trainName);
      }
      if (!fixed) {
        dispatchService.refreshSignalByName(trainName);
      }
    }

    recovery.lastProgressAttemptAt = now;
    recovery.progressStage = fixed ? nextStage : 0;
    return fixed;
  }

  /**
   * 查找“互相阻塞”的对端列车。
   *
   * <p>判定条件：
   *
   * <ul>
   *   <li>A 的 blocker 列表包含 B
   *   <li>B 的 blocker 列表包含 A
   *   <li>B 当前仍在线
   * </ul>
   */
  private Optional<String> findMutualBlocker(String trainName, Set<String> activeTrainKeys) {
    if (trainName == null || trainName.isBlank()) {
      return Optional.empty();
    }
    Set<String> blockers = dispatchService.recentBlockerTrains(trainName, blockerSnapshotMaxAge);
    if (blockers.isEmpty()) {
      return Optional.empty();
    }
    String trainKey = keyOf(trainName);
    if (trainKey == null) {
      return Optional.empty();
    }
    for (String blocker : blockers) {
      String blockerKey = keyOf(blocker);
      if (blockerKey == null || blockerKey.equals(trainKey)) {
        continue;
      }
      if (activeTrainKeys == null || !activeTrainKeys.contains(blockerKey)) {
        continue;
      }
      Set<String> reverse = dispatchService.recentBlockerTrains(blocker, blockerSnapshotMaxAge);
      for (String reverseTrain : reverse) {
        String reverseKey = keyOf(reverseTrain);
        if (trainKey.equals(reverseKey)) {
          return Optional.of(blocker);
        }
      }
    }
    return Optional.empty();
  }

  /**
   * 互卡解锁：优先轻量恢复，必要时升级到定向重发。
   *
   * <p>为避免两车同时执行恢复动作导致抖动，仅由 pair key 较小的一侧执行。
   */
  private boolean tryFixMutualDeadlock(
      String trainName, String blockerTrain, RecoveryState recovery, Instant now) {
    if (trainName == null || blockerTrain == null) {
      return false;
    }
    String trainKey = keyOf(trainName);
    String blockerKey = keyOf(blockerTrain);
    if (trainKey == null || blockerKey == null) {
      return false;
    }
    String pairKey = pairKey(trainKey, blockerKey);
    // 仅由 pair key 的字典序前者执行解锁，防止双方同时“抢修”。
    if (!trainKey.equals(firstTrainInPair(trainKey, blockerKey))) {
      return false;
    }
    Instant pairLast = deadlockPairLastAttemptAt.get(pairKey);
    if (!canAttempt(now, pairLast) || !canAttempt(now, recovery.lastDeadlockAttemptAt)) {
      return false;
    }
    int nextStage = Math.min(recovery.deadlockStage + 1, 3);
    boolean fixed;
    if (nextStage == 1) {
      debugLogger.accept(
          "TrainHealthMonitor 解锁互卡(stage=refresh-pair): train="
              + trainName
              + " blocker="
              + blockerTrain);
      dispatchService.refreshSignalByName(trainName);
      dispatchService.refreshSignalByName(blockerTrain);
      fixed = true;
    } else if (nextStage == 2) {
      debugLogger.accept(
          "TrainHealthMonitor 解锁互卡(stage=reissue-pair): train="
              + trainName
              + " blocker="
              + blockerTrain);
      fixed = dispatchService.reissueDestinationByName(trainName);
      if (!fixed) {
        fixed = dispatchService.reissueDestinationByName(blockerTrain);
      }
      if (!fixed) {
        dispatchService.refreshSignalByName(trainName);
        dispatchService.refreshSignalByName(blockerTrain);
      }
    } else {
      debugLogger.accept(
          "TrainHealthMonitor 解锁互卡(stage=relaunch-pair): train="
              + trainName
              + " blocker="
              + blockerTrain);
      fixed = dispatchService.forceRelaunchByName(trainName);
      if (!fixed) {
        fixed = dispatchService.forceRelaunchByName(blockerTrain);
      }
      if (!fixed) {
        fixed = dispatchService.reissueDestinationByName(trainName);
      }
      if (!fixed) {
        fixed = dispatchService.reissueDestinationByName(blockerTrain);
      }
      if (!fixed) {
        dispatchService.refreshSignalByName(trainName);
        dispatchService.refreshSignalByName(blockerTrain);
      }
    }
    recovery.lastDeadlockAttemptAt = now;
    recovery.deadlockStage = fixed ? nextStage : 0;
    deadlockPairLastAttemptAt.put(pairKey, now);
    return fixed;
  }

  /**
   * 手动模式下的互卡解锁。
   *
   * <p>一次执行完整的“refresh -> reissue -> relaunch”链路，尽量在单次命令里完成解锁，不依赖下一轮阈值触发。
   */
  private boolean forceFixMutualDeadlock(
      String trainName, String blockerTrain, RecoveryState recovery, Instant now) {
    if (trainName == null || blockerTrain == null) {
      return false;
    }
    String trainKey = keyOf(trainName);
    String blockerKey = keyOf(blockerTrain);
    if (trainKey == null || blockerKey == null) {
      return false;
    }
    String pairKey = pairKey(trainKey, blockerKey);
    debugLogger.accept(
        "TrainHealthMonitor 手动解锁互卡: train=" + trainName + " blocker=" + blockerTrain);

    dispatchService.refreshSignalByName(trainName);
    dispatchService.refreshSignalByName(blockerTrain);

    boolean fixed = false;
    // 非破坏优先：先尝试重发 destination。
    fixed = dispatchService.reissueDestinationByName(trainName) || fixed;
    fixed = dispatchService.reissueDestinationByName(blockerTrain) || fixed;
    // 仍未解开时再升级到 relaunch。
    if (!fixed) {
      fixed = dispatchService.forceRelaunchByName(trainName);
    }
    if (!fixed) {
      fixed = dispatchService.forceRelaunchByName(blockerTrain);
    }

    recovery.lastDeadlockAttemptAt = now;
    recovery.deadlockStage = fixed ? 3 : 0;
    deadlockPairLastAttemptAt.put(pairKey, now);
    return fixed;
  }

  private static String pairKey(String first, String second) {
    return first.compareTo(second) <= 0 ? first + "|" + second : second + "|" + first;
  }

  private static String firstTrainInPair(String first, String second) {
    return first.compareTo(second) <= 0 ? first : second;
  }

  private static boolean isPairLeader(String trainName, String blockerTrain) {
    String trainKey = keyOf(trainName);
    String blockerKey = keyOf(blockerTrain);
    if (trainKey == null || blockerKey == null) {
      return false;
    }
    return trainKey.equals(firstTrainInPair(trainKey, blockerKey));
  }

  private boolean canAttempt(Instant now, Instant lastAttemptAt) {
    if (now == null) {
      return false;
    }
    if (lastAttemptAt == null || lastAttemptAt.equals(Instant.EPOCH)) {
      return true;
    }
    return !now.isBefore(lastAttemptAt.plus(recoveryCooldown));
  }

  private static String keyOf(String trainName) {
    if (trainName == null || trainName.isBlank()) {
      return null;
    }
    return trainName.trim().toLowerCase(Locale.ROOT);
  }

  /** 检查结果。 */
  public record CheckResult(int stallCount, int progressStuckCount, int fixedCount) {
    public int totalAnomalies() {
      return stallCount + progressStuckCount;
    }

    public boolean hasAnomalies() {
      return totalAnomalies() > 0;
    }
  }
}
