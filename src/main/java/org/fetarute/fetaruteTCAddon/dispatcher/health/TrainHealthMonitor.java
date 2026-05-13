package org.fetarute.fetaruteTCAddon.dispatcher.health;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.fetarute.fetaruteTCAddon.company.model.RouteOperationType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.DwellRegistry;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchService;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.CorridorDirection;
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
 *   <li>PROGRESS_STUCK：非 STOP 时先 refreshSignal，再升级到 reissueDestination，最后 forceRelaunch
 *   <li>STOP 下的长时间停滞只重刷信号/重下发硬 STOP，不 reissue destination，避免健康监控绕过红灯强制动车
 *   <li>若 STOP 期间仍能看到新鲜 blocker 快照，则只在 STOP 宽限窗口内视为合法排队；超宽限后仍进入非动车恢复链，避免 blocker 持续刷新导致永久不自愈
 *   <li>互相阻塞的自动恢复先按列车对执行 refresh → hard STOP；超过销毁阈值且仍未恢复时，销毁 pair leader 作为最终兜底，避免永久占线
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

  /** 一组经过确认的 STOP 互卡 episode；firstSeenAt 不随 blocker 快照抖动重置。 */
  private static final class DeadlockEpisode {
    private final String key;
    private final String trainA;
    private final String trainB;
    private final String conflictKey;
    private final boolean weak;
    private final Instant firstSeenAt;
    private Instant lastSeenAt;
    private int refreshCount;
    private int reissueCount;
    private boolean destroyAttempted;
    private String stableLeader;
    private Set<String> lastBlockerSnapshot = Set.of();

    private DeadlockEpisode(
        String key,
        String trainA,
        String trainB,
        String conflictKey,
        boolean weak,
        Instant firstSeenAt,
        String stableLeader,
        Set<String> lastBlockerSnapshot) {
      this.key = Objects.requireNonNull(key, "key");
      this.trainA = Objects.requireNonNull(trainA, "trainA");
      this.trainB = Objects.requireNonNull(trainB, "trainB");
      this.conflictKey = Objects.requireNonNull(conflictKey, "conflictKey");
      this.weak = weak;
      this.firstSeenAt = Objects.requireNonNull(firstSeenAt, "firstSeenAt");
      this.lastSeenAt = firstSeenAt;
      this.stableLeader = stableLeader;
      updateSnapshot(firstSeenAt, lastBlockerSnapshot);
    }

    private void updateSnapshot(Instant now, Set<String> blockerSnapshot) {
      lastSeenAt = now == null ? Instant.now() : now;
      lastBlockerSnapshot =
          blockerSnapshot == null || blockerSnapshot.isEmpty()
              ? Set.of()
              : Set.copyOf(blockerSnapshot);
    }

    private String survivor() {
      return stableLeader != null && stableLeader.equalsIgnoreCase(trainA) ? trainB : trainA;
    }
  }

  /** 本轮检测到的一次 confirmed mutual deadlock。 */
  private record DeadlockObservation(
      String episodeKey,
      String trainA,
      String trainB,
      String conflictKey,
      boolean weak,
      Set<String> blockerSnapshot,
      Set<String> blockerResources,
      RuntimeDispatchService.DeadlockTrainContext firstContext,
      RuntimeDispatchService.DeadlockTrainContext secondContext) {}

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
  private Duration progressStopGraceThreshold = Duration.ofSeconds(60);

  /** 自动修复动作冷却，避免每轮检查都重复触发同一恢复动作。 */
  private Duration recoveryCooldown = Duration.ofSeconds(10);

  /** STOP 互卡识别阈值（秒）：用于比 progress STOP 宽限更早触发“解锁尝试”。 */
  private Duration deadlockThreshold = Duration.ofSeconds(45);

  /** STOP 互卡最终销毁阈值：为 0 时禁用自动销毁。 */
  private Duration deadlockDestroyThreshold = Duration.ofSeconds(60);

  /** STOP 互卡最终销毁兜底是否启用。 */
  private boolean deadlockDestroyEnabled = true;

  /** 同一互卡对销毁兜底冷却，避免新 episode 立即连续销毁第二列车。 */
  private Duration deadlockDestroyCooldown = Duration.ofSeconds(120);

  /** confirmed episode 暂时失去 blocker 快照后保留的宽限。 */
  private Duration deadlockEpisodeGrace = Duration.ofSeconds(15);

  /** STOP 互卡进入 confirmed episode 前的最短静止时间。 */
  private Duration deadlockMinStopDuration = Duration.ofSeconds(20);

  /** 阻塞快照有效期：仅在最近可见的 blocker 信息上执行互卡解锁。 */
  private Duration blockerSnapshotMaxAge = Duration.ofSeconds(20);

  /** 是否启用自动修复。 */
  private boolean autoFixEnabled = true;

  /** 低速判定阈值（blocks per tick）。 */
  private double lowSpeedThresholdBpt = 0.01;

  /** 互卡对级别冷却：避免同一对列车在短窗口内反复执行解锁动作。 */
  private final Map<String, Instant> deadlockPairLastAttemptAt = new ConcurrentHashMap<>();

  /** 互卡 episode 追踪，key=canonical(trainA, trainB, conflictKey)。 */
  private final Map<String, DeadlockEpisode> deadlockEpisodes = new ConcurrentHashMap<>();

  /** 互卡对销毁尝试冷却，key=canonical(trainA, trainB)。 */
  private final Map<String, Instant> deadlockPairLastDestroyAt = new ConcurrentHashMap<>();

  /** 健康诊断 trace 限频。 */
  private final Map<String, String> traceFingerprints = new ConcurrentHashMap<>();

  private final Map<String, Instant> traceLastAt = new ConcurrentHashMap<>();

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

  /** 设置 STOP 互卡最终销毁阈值；0 表示禁用自动销毁。 */
  public void setDeadlockDestroyThreshold(Duration threshold) {
    if (threshold != null && !threshold.isNegative()) {
      this.deadlockDestroyThreshold = threshold;
    }
  }

  /** 设置 STOP 互卡最终销毁兜底是否启用。 */
  public void setDeadlockDestroyEnabled(boolean enabled) {
    this.deadlockDestroyEnabled = enabled;
  }

  /** 设置同一互卡对销毁冷却时间。 */
  public void setDeadlockDestroyCooldown(Duration cooldown) {
    if (cooldown != null && !cooldown.isNegative()) {
      this.deadlockDestroyCooldown = cooldown;
    }
  }

  /** 设置互卡 episode 在 blocker 快照抖动后的保留宽限。 */
  public void setDeadlockEpisodeGrace(Duration grace) {
    if (grace != null && !grace.isNegative()) {
      this.deadlockEpisodeGrace = grace;
    }
  }

  /** 设置进入 confirmed mutual deadlock 前的最短 STOP 静止时间。 */
  public void setDeadlockMinStopDuration(Duration minStopDuration) {
    if (minStopDuration != null && !minStopDuration.isNegative()) {
      this.deadlockMinStopDuration = minStopDuration;
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
   * <p>该入口用于人工介入（例如命令行触发），不等待 progress stuck 阈值，也不受恢复冷却限制。STOP 闭塞只会重新刷新信号并复下发硬 STOP，不会重发
   * destination 或 relaunch，避免人工修复绕过红灯运动抑制。
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
          boolean fixed = dispatchService.reapplyHardStopByName(trainName, "manual-stop-unlock");
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
      Optional<DeadlockObservation> deadlockObservation =
          !progressed
                  && !isMoving
                  && currentSignal == SignalAspect.STOP
                  && progressDuration.compareTo(deadlockThreshold) > 0
                  && progressDuration.compareTo(deadlockMinStopDuration) >= 0
              ? findConfirmedMutualDeadlock(trainName, activeKeys, current, progressDuration, now)
              : Optional.empty();
      if (deadlockObservation.isPresent()) {
        DeadlockObservation observation = deadlockObservation.get();
        if (!Objects.equals(keyOf(trainName), keyOf(observation.trainA()))) {
          recovery.resetDeadlock();
          continue;
        }
        DeadlockEpisode episode = updateDeadlockEpisode(observation, now);
        progressStuckCount++;
        boolean fixed = false;
        if (autoFixEnabled) {
          fixed =
              tryFixMutualDeadlockEpisode(episode, observation, progressDuration, recovery, now);
          if (fixed) {
            fixedCount++;
          }
        }
        String message =
            "互卡停滞: train="
                + observation.trainA()
                + " blocker="
                + observation.trainB()
                + " conflict="
                + observation.conflictKey()
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
      } else if (!progressed && !isMoving && currentSignal == SignalAspect.STOP) {
        traceDeadlockSkipped(trainName, current, progressDuration, activeKeys, now);
      }

      boolean waitingOnFreshStopBlockersWithinGrace =
          currentSignal == SignalAspect.STOP
              && progressDuration.compareTo(progressStopGraceThreshold) <= 0
              && hasRecentBlockers(trainName);
      if (waitingOnFreshStopBlockersWithinGrace) {
        recovery.resetProgress();
        continue;
      }

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

    traceSwitcherOccupantBlockingMany(active, now);
    pruneDeadlockEpisodes(activeKeys, now);
    return new CheckResult(stallCount, progressStuckCount, fixedCount);
  }

  /** 清除所有快照。 */
  public void clear() {
    snapshots.clear();
    recoveryStates.clear();
    deadlockPairLastAttemptAt.clear();
    deadlockPairLastDestroyAt.clear();
    deadlockEpisodes.clear();
    traceFingerprints.clear();
    traceLastAt.clear();
  }

  /** 尝试修复静止列车。 */
  private boolean tryFixStall(String trainName, RecoveryState recovery, Instant now) {
    if (!canAttempt(now, recovery.lastStallAttemptAt)) {
      return false;
    }
    int nextStage = Math.min(recovery.stallStage + 1, 2);
    boolean fixed = false;
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
      int nextStage = Math.min(recovery.progressStage + 1, 2);
      boolean fixed;
      if (nextStage == 1) {
        debugLogger.accept("TrainHealthMonitor 修复停滞(stage=refresh-stop): train=" + trainName);
        dispatchService.refreshSignalByName(trainName);
        fixed = true;
      } else {
        debugLogger.accept("TrainHealthMonitor 修复停滞(stage=hard-stop): train=" + trainName);
        fixed = dispatchService.reapplyHardStopByName(trainName, "health-stop-progress-stuck");
        if (!fixed) {
          dispatchService.refreshSignalByName(trainName);
        }
      }
      recovery.lastProgressAttemptAt = now;
      // STOP 停滞下不自动 relaunch/reissue，避免健康监控绕过红灯；后续 tick 继续 refresh/hard-stop。
      recovery.progressStage = nextStage;
      return fixed;
    }

    int nextStage = Math.min(recovery.progressStage + 1, 3);
    boolean fixed = false;
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

  /** 查找满足“同一 single conflict + 已知对向方向”的 confirmed mutual deadlock。 */
  private Optional<DeadlockObservation> findConfirmedMutualDeadlock(
      String trainName,
      Set<String> activeTrainKeys,
      TrainSnapshot currentSnapshot,
      Duration progressDuration,
      Instant now) {
    if (trainName == null || trainName.isBlank() || currentSnapshot == null) {
      return Optional.empty();
    }
    String trainKey = keyOf(trainName);
    if (trainKey == null) {
      return Optional.empty();
    }
    RuntimeDispatchService.DeadlockBlockerSnapshot snapshot =
        recentDeadlockBlockerSnapshot(trainName);
    if (snapshot.blockers().isEmpty()) {
      return Optional.empty();
    }
    for (RuntimeDispatchService.DeadlockBlockerInfo blocker : snapshot.blockers()) {
      if (!isUsableSingleConflictBlocker(blocker)) {
        continue;
      }
      String blockerKey = keyOf(blocker.trainName());
      if (blockerKey == null || blockerKey.equals(trainKey)) {
        continue;
      }
      if (activeTrainKeys == null || !activeTrainKeys.contains(blockerKey)) {
        continue;
      }
      Optional<RuntimeDispatchService.DeadlockBlockerInfo> reverseOpt =
          findReverseSingleConflictBlocker(blocker.trainName(), trainKey, blocker.conflictKey());
      if (reverseOpt.isEmpty()) {
        continue;
      }
      RuntimeDispatchService.DeadlockBlockerInfo reverse = reverseOpt.get();
      if (!isKnownOpposite(blocker.direction(), reverse.direction())) {
        continue;
      }
      Optional<RuntimeDispatchService.DeadlockTrainContext> firstContextOpt =
          dispatchService.deadlockTrainContext(trainName);
      Optional<RuntimeDispatchService.DeadlockTrainContext> secondContextOpt =
          dispatchService.deadlockTrainContext(blocker.trainName());
      if (firstContextOpt.isEmpty() || secondContextOpt.isEmpty()) {
        continue;
      }
      RuntimeDispatchService.DeadlockTrainContext firstContext = firstContextOpt.get();
      RuntimeDispatchService.DeadlockTrainContext secondContext = secondContextOpt.get();
      if (!isEligibleDeadlockContext(firstContext)
          || !isEligibleDeadlockContext(secondContext)
          || progressDuration.compareTo(deadlockMinStopDuration) < 0) {
        continue;
      }
      String firstKey = firstTrainInPair(trainKey, blockerKey);
      boolean currentIsFirst = trainKey.equals(firstKey);
      String canonicalTrainA = currentIsFirst ? trainName : blocker.trainName();
      String canonicalTrainB = currentIsFirst ? blocker.trainName() : trainName;
      RuntimeDispatchService.DeadlockTrainContext canonicalContextA =
          currentIsFirst ? firstContext : secondContext;
      RuntimeDispatchService.DeadlockTrainContext canonicalContextB =
          currentIsFirst ? secondContext : firstContext;
      String episodeKey = episodeKey(trainKey, blockerKey, blocker.conflictKey());
      Set<String> blockerSnapshot =
          Set.of(
              trainName + "->" + blocker.trainName() + "@" + blocker.conflictKey(),
              blocker.trainName() + "->" + trainName + "@" + blocker.conflictKey());
      Set<String> blockerResources = Set.of(blocker.conflictKey());
      return Optional.of(
          new DeadlockObservation(
              episodeKey,
              canonicalTrainA,
              canonicalTrainB,
              blocker.conflictKey(),
              false,
              blockerSnapshot,
              blockerResources,
              canonicalContextA,
              canonicalContextB));
    }
    Optional<DeadlockObservation> weakObservation =
        findWeakMutualDeadlock(trainName, trainKey, activeTrainKeys, snapshot, progressDuration);
    if (weakObservation.isPresent()) {
      return weakObservation;
    }
    return Optional.empty();
  }

  private Optional<DeadlockObservation> findWeakMutualDeadlock(
      String trainName,
      String trainKey,
      Set<String> activeTrainKeys,
      RuntimeDispatchService.DeadlockBlockerSnapshot snapshot,
      Duration progressDuration) {
    if (snapshot == null || snapshot.blockers().isEmpty()) {
      return Optional.empty();
    }
    for (RuntimeDispatchService.DeadlockBlockerInfo blocker : snapshot.blockers()) {
      String blockerKey = keyOf(blocker.trainName());
      if (blockerKey == null
          || blockerKey.equals(trainKey)
          || activeTrainKeys == null
          || !activeTrainKeys.contains(blockerKey)) {
        continue;
      }
      RuntimeDispatchService.DeadlockBlockerSnapshot reverseSnapshot =
          recentDeadlockBlockerSnapshot(blocker.trainName());
      boolean reverse =
          reverseSnapshot.blockers().stream()
              .anyMatch(
                  info ->
                      keyOf(info.trainName()) != null && keyOf(info.trainName()).equals(trainKey));
      if (!reverse) {
        continue;
      }
      Optional<RuntimeDispatchService.DeadlockTrainContext> firstContextOpt =
          dispatchService.deadlockTrainContext(trainName);
      Optional<RuntimeDispatchService.DeadlockTrainContext> secondContextOpt =
          dispatchService.deadlockTrainContext(blocker.trainName());
      if (firstContextOpt.isEmpty()
          || secondContextOpt.isEmpty()
          || !isEligibleDeadlockContext(firstContextOpt.get())
          || !isEligibleDeadlockContext(secondContextOpt.get())
          || progressDuration.compareTo(deadlockMinStopDuration) < 0) {
        continue;
      }
      String firstKey = firstTrainInPair(trainKey, blockerKey);
      boolean currentIsFirst = trainKey.equals(firstKey);
      String canonicalTrainA = currentIsFirst ? trainName : blocker.trainName();
      String canonicalTrainB = currentIsFirst ? blocker.trainName() : trainName;
      RuntimeDispatchService.DeadlockTrainContext canonicalContextA =
          currentIsFirst ? firstContextOpt.get() : secondContextOpt.get();
      RuntimeDispatchService.DeadlockTrainContext canonicalContextB =
          currentIsFirst ? secondContextOpt.get() : firstContextOpt.get();
      String conflictKey = "weaker:" + pairKey(trainKey, blockerKey);
      Set<String> blockerSnapshot =
          Set.of(
              trainName + "->" + blocker.trainName() + "@weaker",
              blocker.trainName() + "->" + trainName + "@weaker");
      Set<String> blockerResources =
          blocker.conflictKey() == null || blocker.conflictKey().isBlank()
              ? Set.of()
              : Set.of(blocker.conflictKey());
      return Optional.of(
          new DeadlockObservation(
              episodeKey(trainKey, blockerKey, conflictKey),
              canonicalTrainA,
              canonicalTrainB,
              conflictKey,
              true,
              blockerSnapshot,
              blockerResources,
              canonicalContextA,
              canonicalContextB));
    }
    return Optional.empty();
  }

  private Optional<RuntimeDispatchService.DeadlockBlockerInfo> findReverseSingleConflictBlocker(
      String blockerTrain, String targetTrainKey, String conflictKey) {
    RuntimeDispatchService.DeadlockBlockerSnapshot reverse =
        recentDeadlockBlockerSnapshot(blockerTrain);
    if (reverse.blockers().isEmpty()) {
      return Optional.empty();
    }
    for (RuntimeDispatchService.DeadlockBlockerInfo candidate : reverse.blockers()) {
      if (!isUsableSingleConflictBlocker(candidate)) {
        continue;
      }
      String candidateKey = keyOf(candidate.trainName());
      if (targetTrainKey.equals(candidateKey) && conflictKey.equals(candidate.conflictKey())) {
        return Optional.of(candidate);
      }
    }
    return Optional.empty();
  }

  private RuntimeDispatchService.DeadlockBlockerSnapshot recentDeadlockBlockerSnapshot(
      String trainName) {
    return Optional.ofNullable(
            dispatchService.recentDeadlockBlockers(trainName, blockerSnapshotMaxAge))
        .orElseGet(
            () -> new RuntimeDispatchService.DeadlockBlockerSnapshot(Set.of(), Instant.EPOCH));
  }

  private static boolean isUsableSingleConflictBlocker(
      RuntimeDispatchService.DeadlockBlockerInfo blocker) {
    return blocker != null
        && blocker.trainName() != null
        && !blocker.trainName().isBlank()
        && blocker.conflictKey() != null
        && blocker.conflictKey().startsWith("single:")
        && !blocker.conflictKey().contains(":cycle:")
        && blocker.direction().isPresent()
        && blocker.direction().get() != CorridorDirection.UNKNOWN;
  }

  private static boolean isKnownOpposite(
      Optional<CorridorDirection> first, Optional<CorridorDirection> second) {
    return first != null
        && second != null
        && first.isPresent()
        && second.isPresent()
        && first.get() != CorridorDirection.UNKNOWN
        && second.get() != CorridorDirection.UNKNOWN
        && first.get().opposite() == second.get();
  }

  private boolean isEligibleDeadlockContext(RuntimeDispatchService.DeadlockTrainContext context) {
    return context != null
        && context.signalAspect() == SignalAspect.STOP
        && context.speedBlocksPerTick() <= lowSpeedThresholdBpt
        && !context.dwelling()
        && !context.departureGateHeld()
        && !context.layoverReady()
        && !context.manualHold();
  }

  private DeadlockEpisode updateDeadlockEpisode(DeadlockObservation observation, Instant now) {
    return deadlockEpisodes.compute(
        observation.episodeKey(),
        (key, existing) -> {
          if (existing == null) {
            String leader =
                chooseStableLeader(observation.firstContext(), observation.secondContext());
            DeadlockEpisode created =
                new DeadlockEpisode(
                    key,
                    observation.trainA(),
                    observation.trainB(),
                    observation.conflictKey(),
                    observation.weak(),
                    now,
                    leader,
                    observation.blockerSnapshot());
            traceDeadlockEpisodeCreated(created, observation, now);
            return created;
          }
          existing.updateSnapshot(now, observation.blockerSnapshot());
          return existing;
        });
  }

  private void pruneDeadlockEpisodes(Set<String> activeKeys, Instant now) {
    Instant effectiveNow = now == null ? Instant.now() : now;
    deadlockEpisodes
        .values()
        .removeIf(
            episode -> {
              String firstKey = keyOf(episode.trainA);
              String secondKey = keyOf(episode.trainB);
              if (firstKey == null
                  || secondKey == null
                  || activeKeys == null
                  || !activeKeys.contains(firstKey)
                  || !activeKeys.contains(secondKey)) {
                return true;
              }
              return effectiveNow.isAfter(episode.lastSeenAt.plus(deadlockEpisodeGrace));
            });
  }

  /**
   * 判断列车当前是否仍持有“新鲜”的 blocker 快照。
   *
   * <p>用于区分“合法 STOP 排队等待”和“信号/调度状态疑似丢失”。 只要 blocker 快照仍在有效期内，就优先认为列车正在等待前车或冲突区放行， 不立即升级到 {@code
   * reissueDestination}；超过 STOP 宽限后即使 blocker 仍持续刷新，也会进入停滞恢复链，避免长时间互卡被“新鲜快照”无限掩盖。
   */
  private boolean hasRecentBlockers(String trainName) {
    return trainName != null
        && !trainName.isBlank()
        && !dispatchService.recentBlockerTrains(trainName, blockerSnapshotMaxAge).isEmpty();
  }

  private void traceDeadlockEpisodeCreated(
      DeadlockEpisode episode, DeadlockObservation observation, Instant now) {
    if (episode == null || observation == null) {
      return;
    }
    String type = episodeType(observation);
    long stoppedMs =
        Math.max(
            0L,
            Duration.between(
                    snapshots
                        .getOrDefault(keyOf(episode.trainA), emptySnapshot(now))
                        .lastProgressTime(),
                    now)
                .toMillis());
    Duration required =
        observation.weak() ? deadlockDestroyThreshold.multipliedBy(2L) : deadlockDestroyThreshold;
    traceHealthEvent(
        "DEADLOCK_EPISODE_CREATED",
        "episode-created:" + episode.key,
        "episodeId="
            + episode.key
            + " episodeType="
            + type
            + " trainA="
            + episode.trainA
            + " trainB="
            + episode.trainB
            + " blockerResources="
            + observation.blockerResources()
            + " commonConflictKey="
            + episode.conflictKey
            + " directions="
            + directionsSummary(observation)
            + " firstSeenTime="
            + episode.firstSeenAt
            + " stoppedDurationMs="
            + stoppedMs
            + " requiredDestroyThresholdMs="
            + required.toMillis()
            + " destroyEnabled="
            + deadlockDestroyEnabled
            + " sourceSnapshotAgeMs="
            + snapshotAgeMs(episode.trainA, now));
  }

  private void traceDeadlockSkipped(
      String trainName,
      TrainSnapshot current,
      Duration progressDuration,
      Set<String> activeKeys,
      Instant now) {
    RuntimeDispatchService.DeadlockBlockerSnapshot snapshot =
        recentDeadlockBlockerSnapshot(trainName);
    String reason = classifyDeadlockSkip(trainName, snapshot, activeKeys);
    if ("NONE".equals(reason)) {
      return;
    }
    traceHealthEvent(
        "DEADLOCK_DESTROY_SKIPPED",
        "deadlock-skip:" + keyOf(trainName) + ":" + reason,
        "reason="
            + reason
            + " trainA="
            + trainName
            + " trainB="
            + snapshot.trainNames()
            + " blockers="
            + summarizeBlockers(snapshot)
            + " lastBlockerSnapshotAgeMs="
            + snapshotAgeMs(trainName, now)
            + " conflictKey="
            + conflictSummary(snapshot)
            + " directions="
            + directionSummary(snapshot)
            + " stoppedDurationMs="
            + (progressDuration == null ? 0L : progressDuration.toMillis())
            + " signal="
            + (current == null ? "-" : current.signal()));
  }

  private String classifyDeadlockSkip(
      String trainName,
      RuntimeDispatchService.DeadlockBlockerSnapshot snapshot,
      Set<String> activeKeys) {
    if (snapshot == null || snapshot.blockers().isEmpty()) {
      return "BLOCKER_SNAPSHOT_MISSING";
    }
    boolean hasSingle = false;
    boolean hasUnknown = false;
    boolean hasSwitcherOrHard = false;
    String trainKey = keyOf(trainName);
    for (RuntimeDispatchService.DeadlockBlockerInfo blocker : snapshot.blockers()) {
      if (blocker == null || blocker.trainName().isBlank()) {
        continue;
      }
      String blockerKey = keyOf(blocker.trainName());
      if (blockerKey == null || blockerKey.equals(trainKey)) {
        continue;
      }
      if (activeKeys != null && !activeKeys.contains(blockerKey)) {
        continue;
      }
      if (blocker.conflictKey().startsWith("single:")) {
        hasSingle = true;
        if (blocker.direction().isEmpty()
            || blocker.direction().get() == CorridorDirection.UNKNOWN) {
          hasUnknown = true;
        }
        Optional<RuntimeDispatchService.DeadlockBlockerInfo> reverse =
            findReverseSingleConflictBlocker(blocker.trainName(), trainKey, blocker.conflictKey());
        if (reverse.isEmpty()) {
          return "NOT_MUTUAL";
        }
        if (!isKnownOpposite(blocker.direction(), reverse.get().direction())) {
          return "UNKNOWN_DIRECTION";
        }
        return "NONE";
      }
      hasSwitcherOrHard = true;
    }
    if (hasSwitcherOrHard) {
      return "SWITCHER_OR_NODE_EDGE_NOT_CONFIRMED";
    }
    if (hasSingle && hasUnknown) {
      return "UNKNOWN_DIRECTION";
    }
    return "NOT_SAME_SINGLE_CONFLICT";
  }

  private void traceSwitcherOccupantBlockingMany(Set<String> activeTrains, Instant now) {
    if (activeTrains == null || activeTrains.isEmpty()) {
      return;
    }
    Map<String, Set<String>> blockedByBlocker = new LinkedHashMap<>();
    Map<String, Set<String>> resourcesByBlocker = new LinkedHashMap<>();
    for (String blockedTrain : activeTrains) {
      RuntimeDispatchService.DeadlockBlockerSnapshot snapshot =
          recentDeadlockBlockerSnapshot(blockedTrain);
      for (RuntimeDispatchService.DeadlockBlockerInfo blocker : snapshot.blockers()) {
        if (blocker == null || blocker.trainName().isBlank()) {
          continue;
        }
        String blockerKey = keyOf(blocker.trainName());
        if (blockerKey == null || blockerKey.equals(keyOf(blockedTrain))) {
          continue;
        }
        blockedByBlocker
            .computeIfAbsent(blocker.trainName(), unused -> new LinkedHashSet<>())
            .add(blockedTrain);
        if (!blocker.conflictKey().isBlank()) {
          resourcesByBlocker
              .computeIfAbsent(blocker.trainName(), unused -> new LinkedHashSet<>())
              .add(blocker.conflictKey());
        }
      }
    }
    for (Map.Entry<String, Set<String>> entry : blockedByBlocker.entrySet()) {
      if (entry.getValue().size() < 2) {
        continue;
      }
      String blockerTrain = entry.getKey();
      TrainSnapshot blockerSnapshot = snapshots.get(keyOf(blockerTrain));
      if (blockerSnapshot == null
          || blockerSnapshot.signal() != SignalAspect.STOP
          || blockerSnapshot.speedBpt() > lowSpeedThresholdBpt) {
        continue;
      }
      Set<String> resources = resourcesByBlocker.getOrDefault(blockerTrain, Set.of());
      Set<String> switcherKeys = switcherKeys(resources);
      Set<String> ownClaims =
          Optional.ofNullable(dispatchService.currentConflictClaimKeys(blockerTrain))
              .orElse(Set.of());
      boolean protectedSwitcherClaimPresent =
          ownClaims.stream().anyMatch(key -> key != null && key.startsWith("switcher:"));
      traceHealthEvent(
          "SWITCHER_OCCUPANT_BLOCKING_MANY",
          "occupant-many:" + keyOf(blockerTrain) + ":" + entry.getValue().size(),
          "blockerTrain="
              + blockerTrain
              + " blockedTrains="
              + entry.getValue()
              + " blockedCount="
              + entry.getValue().size()
              + " commonResources="
              + resources
              + " containsSwitcherConflict="
              + !switcherKeys.isEmpty()
              + " switcherConflictKeys="
              + switcherKeys
              + " blockerStoppedDurationMs="
              + Duration.between(blockerSnapshot.lastProgressTime(), now).toMillis()
              + " blockerCurrentNode=-"
              + " blockerLastPassedGraphNode="
              + (blockerSnapshot.lastPassedGraphNodeId() == null
                  ? "-"
                  : blockerSnapshot.lastPassedGraphNodeId())
              + " blockerRouteIndex="
              + blockerSnapshot.progressIndex()
              + " protectedSwitcherClaimPresent="
              + protectedSwitcherClaimPresent
              + " whyNotDestroyed="
              + (!switcherKeys.isEmpty() ? "WEAK_ONLY" : "NOT_MUTUAL")
              + " whetherEpisodeExists="
              + hasEpisodeFor(blockerTrain)
              + " remainingMsUntilWeakDestroy="
              + remainingWeakDestroyMs(blockerTrain, now));
    }
  }

  private String episodeType(DeadlockObservation observation) {
    if (observation == null) {
      return "UNKNOWN";
    }
    if (!observation.weak() && observation.conflictKey().startsWith("single:")) {
      return "CONFIRMED_SINGLE";
    }
    if (observation.blockerResources().stream().anyMatch(key -> key.startsWith("switcher:"))) {
      return "SWITCHER_OR_NODE_EDGE_WEAK";
    }
    return observation.weak() ? "WEAK_MUTUAL" : "UNKNOWN";
  }

  private String directionsSummary(DeadlockObservation observation) {
    if (observation == null) {
      return "[]";
    }
    List<String> values = new ArrayList<>();
    RuntimeDispatchService.DeadlockBlockerSnapshot first =
        recentDeadlockBlockerSnapshot(observation.trainA());
    RuntimeDispatchService.DeadlockBlockerSnapshot second =
        recentDeadlockBlockerSnapshot(observation.trainB());
    values.addAll(directionValues(first));
    values.addAll(directionValues(second));
    return values.toString();
  }

  private String directionSummary(RuntimeDispatchService.DeadlockBlockerSnapshot snapshot) {
    return directionValues(snapshot).toString();
  }

  private List<String> directionValues(RuntimeDispatchService.DeadlockBlockerSnapshot snapshot) {
    if (snapshot == null || snapshot.blockers().isEmpty()) {
      return List.of();
    }
    List<String> values = new ArrayList<>();
    for (RuntimeDispatchService.DeadlockBlockerInfo blocker : snapshot.blockers()) {
      values.add(
          blocker.trainName()
              + "@"
              + (blocker.conflictKey().isBlank() ? "-" : blocker.conflictKey())
              + "="
              + blocker.direction().map(Enum::name).orElse("UNKNOWN"));
    }
    return values;
  }

  private String summarizeBlockers(RuntimeDispatchService.DeadlockBlockerSnapshot snapshot) {
    if (snapshot == null || snapshot.blockers().isEmpty()) {
      return "[]";
    }
    List<String> values = new ArrayList<>();
    for (RuntimeDispatchService.DeadlockBlockerInfo blocker : snapshot.blockers()) {
      values.add(
          blocker.trainName()
              + "@"
              + (blocker.conflictKey().isBlank() ? "-" : blocker.conflictKey()));
    }
    return values.toString();
  }

  private String conflictSummary(RuntimeDispatchService.DeadlockBlockerSnapshot snapshot) {
    if (snapshot == null || snapshot.blockers().isEmpty()) {
      return "-";
    }
    Set<String> conflicts = new LinkedHashSet<>();
    for (RuntimeDispatchService.DeadlockBlockerInfo blocker : snapshot.blockers()) {
      if (!blocker.conflictKey().isBlank()) {
        conflicts.add(blocker.conflictKey());
      }
    }
    return conflicts.isEmpty() ? "-" : conflicts.toString();
  }

  private long snapshotAgeMs(String trainName, Instant now) {
    RuntimeDispatchService.DeadlockBlockerSnapshot snapshot =
        recentDeadlockBlockerSnapshot(trainName);
    if (snapshot == null || snapshot.sampledAt().equals(Instant.EPOCH) || now == null) {
      return -1L;
    }
    return Math.max(0L, Duration.between(snapshot.sampledAt(), now).toMillis());
  }

  private static Set<String> switcherKeys(Set<String> resources) {
    if (resources == null || resources.isEmpty()) {
      return Set.of();
    }
    Set<String> switchers = new LinkedHashSet<>();
    for (String resource : resources) {
      if (resource != null && resource.startsWith("switcher:")) {
        switchers.add(resource);
      }
    }
    return Set.copyOf(switchers);
  }

  private boolean hasEpisodeFor(String trainName) {
    String key = keyOf(trainName);
    if (key == null) {
      return false;
    }
    return deadlockEpisodes.values().stream()
        .anyMatch(
            episode -> key.equals(keyOf(episode.trainA)) || key.equals(keyOf(episode.trainB)));
  }

  private long remainingWeakDestroyMs(String trainName, Instant now) {
    String key = keyOf(trainName);
    if (key == null || now == null) {
      return -1L;
    }
    return deadlockEpisodes.values().stream()
        .filter(
            episode ->
                episode.weak
                    && (key.equals(keyOf(episode.trainA)) || key.equals(keyOf(episode.trainB))))
        .mapToLong(
            episode -> {
              Duration threshold = deadlockDestroyThreshold.multipliedBy(2L);
              long remaining =
                  threshold.minus(Duration.between(episode.firstSeenAt, now)).toMillis();
              return Math.max(0L, remaining);
            })
        .min()
        .orElse(-1L);
  }

  private TrainSnapshot emptySnapshot(Instant now) {
    Instant effectiveNow = now == null ? Instant.now() : now;
    return new TrainSnapshot(
        0, null, SignalAspect.STOP, 0.0, effectiveNow, effectiveNow, effectiveNow);
  }

  private void traceHealthEvent(String eventName, String key, String message) {
    String event = eventName == null || eventName.isBlank() ? "HEALTH_TRACE" : eventName;
    String traceKey = key == null || key.isBlank() ? event : key;
    String fingerprint = event + "|" + message;
    Instant now = Instant.now();
    String previous = traceFingerprints.get(traceKey);
    Instant previousAt = traceLastAt.get(traceKey);
    if (fingerprint.equals(previous)
        && previousAt != null
        && Duration.between(previousAt, now).compareTo(Duration.ofSeconds(30)) < 0) {
      return;
    }
    traceFingerprints.put(traceKey, fingerprint);
    traceLastAt.put(traceKey, now);
    debugLogger.accept(event + ": " + message);
  }

  /**
   * 互卡解锁：优先轻量恢复，超过销毁阈值且多轮恢复无效时销毁 pair leader。
   *
   * <p>为避免两车同时执行恢复动作导致抖动，仅由 pair key 较小的一侧执行。
   */
  private boolean tryFixMutualDeadlockEpisode(
      DeadlockEpisode episode,
      DeadlockObservation observation,
      Duration progressDuration,
      RecoveryState recovery,
      Instant now) {
    if (episode == null || observation == null || recovery == null || now == null) {
      return false;
    }
    String pairKey = pairKey(keyOf(episode.trainA), keyOf(episode.trainB));
    Instant pairLast = deadlockPairLastAttemptAt.get(pairKey);
    if (!canAttempt(now, pairLast) || !canAttempt(now, recovery.lastDeadlockAttemptAt)) {
      return false;
    }

    if (episode.refreshCount <= 0) {
      debugLogger.accept(
          "TrainHealthMonitor 解锁互卡(stage=refresh-pair): train="
              + episode.trainA
              + " blocker="
              + episode.trainB
              + " conflict="
              + episode.conflictKey);
      dispatchService.refreshSignalByName(episode.trainA);
      dispatchService.refreshSignalByName(episode.trainB);
      episode.refreshCount++;
      recovery.lastDeadlockAttemptAt = now;
      recovery.deadlockStage = Math.max(recovery.deadlockStage, 1);
      deadlockPairLastAttemptAt.put(pairKey, now);
      return false;
    }

    if (episode.reissueCount <= 0) {
      debugLogger.accept(
          "TrainHealthMonitor 解锁互卡(stage=hard-stop-pair): pair="
              + episode.trainA
              + "/"
              + episode.trainB
              + " conflict="
              + episode.conflictKey);
      dispatchService.reapplyHardStopByName(episode.trainA, "health-deadlock-confirmed");
      dispatchService.reapplyHardStopByName(episode.trainB, "health-deadlock-confirmed");
      episode.reissueCount++;
      recovery.lastDeadlockAttemptAt = now;
      recovery.deadlockStage = Math.max(recovery.deadlockStage, 2);
      deadlockPairLastAttemptAt.put(pairKey, now);
      return false;
    }

    long remainingMsUntilDestroy = remainingMsUntilDestroy(episode, now);
    Optional<RuntimeDispatchService.DeadlockDrainability> drainability =
        dispatchService.deadlockDrainability(
            episode.key,
            episode.trainA,
            episode.trainB,
            episode.conflictKey,
            remainingMsUntilDestroy);
    if (drainability.isPresent()) {
      traceDeadlockDrainability(episode, drainability.get(), now);
      if (drainability.get().drainable()) {
        return false;
      }
    }

    if (!shouldDestroyDeadlockLeader(episode, progressDuration, now)) {
      traceDeadlockDestroySkipped(
          episode,
          observation,
          progressDuration,
          now,
          destroySkipReason(episode, progressDuration, now));
      return false;
    }
    traceDeadlockDestroyCandidateSelected(episode, observation, progressDuration, now);
    RuntimeDispatchService.RuntimeTrainResolution resolution =
        resolveForDestroyTrace(episode.stableLeader);
    traceDeadlockDestroyAttempted(episode, observation, progressDuration, now, resolution);
    debugLogger.accept(
        "TrainHealthMonitor 解锁互卡(stage=destroy-leader): train="
            + episode.stableLeader
            + " survivor="
            + episode.survivor()
            + " conflict="
            + episode.conflictKey
            + " episode="
            + episode.key
            + " blockers="
            + episode.lastBlockerSnapshot
            + " age="
            + Duration.between(episode.firstSeenAt, now).toSeconds()
            + "s");
    dispatchService.scheduleSurvivorRefreshAfterTrainRemoved(
        episode.stableLeader, episode.survivor());
    boolean destroyed =
        dispatchService.destroyTrainByName(episode.stableLeader, "health-deadlock-timeout");
    traceDeadlockDestroyResult(
        episode, resolution, destroyed, destroyFailureReason(resolution, destroyed), now);
    if (destroyed) {
      episode.destroyAttempted = true;
    }
    recovery.lastDeadlockAttemptAt = now;
    recovery.deadlockStage = 3;
    deadlockPairLastAttemptAt.put(pairKey, now);
    deadlockPairLastDestroyAt.put(pairKey, now);
    return destroyed;
  }

  private void traceDeadlockDestroyCandidateSelected(
      DeadlockEpisode episode,
      DeadlockObservation observation,
      Duration progressDuration,
      Instant now) {
    if (episode == null || observation == null) {
      return;
    }
    traceHealthEvent(
        "DEADLOCK_DESTROY_CANDIDATE_SELECTED",
        "destroy-candidate:" + episode.key,
        "episodeId="
            + episode.key
            + " selectedLeader="
            + episode.stableLeader
            + " selectionReason=stable-leader-score"
            + " episodeType="
            + episodeType(observation)
            + " episodeAgeMs="
            + Duration.between(episode.firstSeenAt, now).toMillis()
            + " stoppedDurationMs="
            + (progressDuration == null ? 0L : progressDuration.toMillis())
            + " thresholdMs="
            + requiredDestroyThreshold(episode).toMillis()
            + " weakEpisode="
            + episode.weak
            + " blockers="
            + episode.lastBlockerSnapshot);
  }

  private void traceDeadlockDrainability(
      DeadlockEpisode episode,
      RuntimeDispatchService.DeadlockDrainability drainability,
      Instant now) {
    if (episode == null || drainability == null) {
      return;
    }
    traceHealthEvent(
        drainability.drainable() ? "DEADLOCK_DRAINABLE" : "DEADLOCK_DRAIN_FAILED",
        "deadlock-drain:" + episode.key + ":" + drainability.reason(),
        "episodeId="
            + episode.key
            + " trainA="
            + episode.trainA
            + " trainB="
            + episode.trainB
            + " zoneId="
            + drainability.zoneId()
            + " drainable="
            + drainability.drainable()
            + " drainCandidate="
            + emptyDash(drainability.drainCandidate())
            + " exitNode="
            + drainability.exitNode().map(NodeId::value).orElse("-")
            + " drainPath="
            + drainability.drainPath()
            + " reason="
            + drainability.reason()
            + " remainingMsUntilDestroy="
            + drainability.remainingMsUntilDestroy()
            + " episodeAgeMs="
            + Duration.between(episode.firstSeenAt, now).toMillis());
  }

  private void traceDeadlockDestroyAttempted(
      DeadlockEpisode episode,
      DeadlockObservation observation,
      Duration progressDuration,
      Instant now,
      RuntimeDispatchService.RuntimeTrainResolution resolution) {
    if (episode == null) {
      return;
    }
    RuntimeDispatchService.RuntimeTrainResolution effective =
        resolution == null ? failedResolution(episode.stableLeader, "RESOLVE_FAILED") : resolution;
    traceHealthEvent(
        "DEADLOCK_DESTROY_ATTEMPTED",
        "health-destroy-attempt:" + episode.key,
        "episodeId="
            + episode.key
            + " requestedName="
            + emptyDash(episode.stableLeader)
            + " resolvedName="
            + emptyDash(effective.resolvedName())
            + " matchedBy="
            + effective.matchedBy()
            + " propertiesFound="
            + effective.propertiesFound()
            + " source=HEALTH_MONITOR_DEADLOCK"
            + " episodeType="
            + episodeType(observation)
            + " conflictKey="
            + episode.conflictKey
            + " blockerResources="
            + (observation == null ? Set.of() : observation.blockerResources())
            + " stoppedDurationMs="
            + (progressDuration == null ? 0L : progressDuration.toMillis())
            + " episodeAgeMs="
            + Duration.between(episode.firstSeenAt, now).toMillis()
            + " thresholdMs="
            + requiredDestroyThreshold(episode).toMillis());
  }

  private void traceDeadlockDestroyResult(
      DeadlockEpisode episode,
      RuntimeDispatchService.RuntimeTrainResolution resolution,
      boolean success,
      String failureReason,
      Instant now) {
    if (episode == null) {
      return;
    }
    RuntimeDispatchService.RuntimeTrainResolution effective =
        resolution == null ? failedResolution(episode.stableLeader, "RESOLVE_FAILED") : resolution;
    traceHealthEvent(
        "DEADLOCK_DESTROY_RESULT",
        "health-destroy-result:" + episode.key + ":" + success + ":" + failureReason,
        "episodeId="
            + episode.key
            + " requestedName="
            + emptyDash(episode.stableLeader)
            + " resolvedName="
            + emptyDash(effective.resolvedName())
            + " matchedBy="
            + effective.matchedBy()
            + " success="
            + success
            + " failureReason="
            + emptyDash(failureReason)
            + " episodeAgeMs="
            + Duration.between(episode.firstSeenAt, now).toMillis());
  }

  private void traceDeadlockDestroySkipped(
      DeadlockEpisode episode,
      DeadlockObservation observation,
      Duration progressDuration,
      Instant now,
      String reason) {
    if (episode == null || reason == null || reason.isBlank() || "NONE".equals(reason)) {
      return;
    }
    long remainingMs =
        Math.max(
            0L,
            requiredDestroyThreshold(episode)
                .minus(Duration.between(episode.firstSeenAt, now))
                .toMillis());
    traceHealthEvent(
        "DEADLOCK_DESTROY_SKIPPED",
        "destroy-skip:" + episode.key + ":" + reason,
        "episodeId="
            + episode.key
            + " reason="
            + reason
            + " trainA="
            + episode.trainA
            + " trainB="
            + episode.trainB
            + " blockers="
            + episode.lastBlockerSnapshot
            + " lastBlockerSnapshotAgeMs="
            + snapshotAgeMs(episode.trainA, now)
            + " remainingMsUntilWeakDestroy="
            + (episode.weak ? remainingMs : -1L)
            + " conflictKey="
            + episode.conflictKey
            + " directions="
            + directionsSummary(observation)
            + " stoppedDurationMs="
            + (progressDuration == null ? 0L : progressDuration.toMillis()));
  }

  private RuntimeDispatchService.RuntimeTrainResolution resolveForDestroyTrace(String trainName) {
    return dispatchService.resolveRuntimeTrainForHealth(
        trainName, RuntimeDispatchService.RuntimeTrainResolvePurpose.DESTROY);
  }

  private String destroyFailureReason(
      RuntimeDispatchService.RuntimeTrainResolution resolution, boolean destroyed) {
    if (destroyed) {
      return "NONE";
    }
    if (resolution == null
        || resolution.matchedBy() == RuntimeDispatchService.RuntimeTrainMatchKind.FAILED) {
      return "RESOLVE_FAILED";
    }
    if (!resolution.propertiesFound()) {
      return "NO_PROPERTIES";
    }
    return "DESTROY_API_FAILED";
  }

  private String destroySkipReason(
      DeadlockEpisode episode, Duration progressDuration, Instant now) {
    if (episode == null) {
      return "NOT_MUTUAL";
    }
    if (!deadlockDestroyEnabled
        || deadlockDestroyThreshold == null
        || deadlockDestroyThreshold.isZero()) {
      return "DESTROY_DISABLED";
    }
    if (episode.destroyAttempted) {
      return "DESTROY_ALREADY_ATTEMPTED";
    }
    Duration episodeAge = Duration.between(episode.firstSeenAt, now);
    Duration requiredThreshold = requiredDestroyThreshold(episode);
    if (episodeAge.compareTo(requiredThreshold) < 0) {
      return episode.weak ? "WEAK_EPISODE_BELOW_THRESHOLD" : "BELOW_DESTROY_THRESHOLD";
    }
    Duration requiredStop = minPositive(progressStopGraceThreshold, requiredThreshold);
    if (progressDuration == null || progressDuration.compareTo(requiredStop) < 0) {
      return "NOT_STATIONARY";
    }
    String pairKey = pairKey(keyOf(episode.trainA), keyOf(episode.trainB));
    Instant lastDestroy = deadlockPairLastDestroyAt.get(pairKey);
    if (lastDestroy != null && now.isBefore(lastDestroy.plus(deadlockDestroyCooldown))) {
      return "RECENTLY_SEEN_MOVING";
    }
    return "NONE";
  }

  private Duration requiredDestroyThreshold(DeadlockEpisode episode) {
    if (deadlockDestroyThreshold == null) {
      return Duration.ZERO;
    }
    return episode != null && episode.weak
        ? deadlockDestroyThreshold.multipliedBy(2L)
        : deadlockDestroyThreshold;
  }

  private long remainingMsUntilDestroy(DeadlockEpisode episode, Instant now) {
    if (episode == null || now == null) {
      return -1L;
    }
    long remaining =
        requiredDestroyThreshold(episode)
            .minus(Duration.between(episode.firstSeenAt, now))
            .toMillis();
    return Math.max(0L, remaining);
  }

  private static RuntimeDispatchService.RuntimeTrainResolution failedResolution(
      String trainName, String reason) {
    return new RuntimeDispatchService.RuntimeTrainResolution(
        trainName,
        "",
        null,
        RuntimeDispatchService.RuntimeTrainMatchKind.FAILED,
        Optional.empty(),
        Optional.empty(),
        reason);
  }

  private static String emptyDash(String value) {
    return value == null || value.isBlank() ? "-" : value;
  }

  private boolean shouldDestroyDeadlockLeader(
      DeadlockEpisode episode, Duration progressDuration, Instant now) {
    if (!deadlockDestroyEnabled
        || episode == null
        || episode.destroyAttempted
        || deadlockDestroyThreshold == null
        || deadlockDestroyThreshold.isZero()
        || now == null) {
      return false;
    }
    Duration episodeAge = Duration.between(episode.firstSeenAt, now);
    Duration requiredThreshold = requiredDestroyThreshold(episode);
    if (episodeAge.compareTo(requiredThreshold) < 0) {
      return false;
    }
    Duration requiredStop = minPositive(progressStopGraceThreshold, requiredThreshold);
    if (progressDuration == null || progressDuration.compareTo(requiredStop) < 0) {
      return false;
    }
    String pairKey = pairKey(keyOf(episode.trainA), keyOf(episode.trainB));
    Instant lastDestroy = deadlockPairLastDestroyAt.get(pairKey);
    return lastDestroy == null || !now.isBefore(lastDestroy.plus(deadlockDestroyCooldown));
  }

  private static Duration minPositive(Duration first, Duration second) {
    if (first == null || first.isNegative() || first.isZero()) {
      return second == null ? Duration.ZERO : second;
    }
    if (second == null || second.isNegative() || second.isZero()) {
      return first;
    }
    return first.compareTo(second) <= 0 ? first : second;
  }

  private static String chooseStableLeader(
      RuntimeDispatchService.DeadlockTrainContext first,
      RuntimeDispatchService.DeadlockTrainContext second) {
    int firstScore = destroyScore(first);
    int secondScore = destroyScore(second);
    if (firstScore != secondScore) {
      return firstScore > secondScore ? first.trainName() : second.trainName();
    }
    if (first.progressIndex() != second.progressIndex()) {
      return first.progressIndex() < second.progressIndex()
          ? first.trainName()
          : second.trainName();
    }
    if (first.priority() != second.priority()) {
      return first.priority() < second.priority() ? first.trainName() : second.trainName();
    }
    String firstKey = keyOf(first.trainName());
    String secondKey = keyOf(second.trainName());
    if (firstKey == null) {
      return second.trainName();
    }
    if (secondKey == null) {
      return first.trainName();
    }
    return firstKey.compareTo(secondKey) <= 0 ? first.trainName() : second.trainName();
  }

  private static int destroyScore(RuntimeDispatchService.DeadlockTrainContext context) {
    if (context == null) {
      return Integer.MIN_VALUE;
    }
    int score = 0;
    if (context.dwelling()
        || context.departureGateHeld()
        || context.layoverReady()
        || context.manualHold()) {
      score -= 10_000;
    }
    if (context.nearRouteEnd()) {
      score -= 200;
    }
    if (context.hasPassengers()) {
      score -= 100;
    }
    if (context.operationType() == RouteOperationType.RETURN) {
      score += 500;
    } else if (context.operationType() == RouteOperationType.CREATE) {
      score += 250;
    }
    if (context.depotRelated()) {
      score += 100;
    }
    return score;
  }

  /**
   * 手动模式下的互卡解锁。
   *
   * <p>一次执行完整的“refresh -> hard-stop”链路，确保 STOP 互卡等待期间不会通过健康修复重新注入运动 destination。
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
    fixed = dispatchService.reapplyHardStopByName(trainName, "manual-mutual-deadlock") || fixed;
    fixed = dispatchService.reapplyHardStopByName(blockerTrain, "manual-mutual-deadlock") || fixed;

    recovery.lastDeadlockAttemptAt = now;
    recovery.deadlockStage = fixed ? 3 : 0;
    deadlockPairLastAttemptAt.put(pairKey, now);
    return fixed;
  }

  private static String pairKey(String first, String second) {
    return first.compareTo(second) <= 0 ? first + "|" + second : second + "|" + first;
  }

  private static String episodeKey(String first, String second, String conflictKey) {
    return pairKey(first, second) + "|" + (conflictKey == null ? "unknown" : conflictKey);
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
