package org.fetarute.fetaruteTCAddon.dispatcher.health;

import java.time.Duration;
import java.time.Instant;
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
 *   <li>进度不推进：触发节点但进度索引长时间不变（排除正在停站的列车）
 * </ul>
 */
public final class TrainHealthMonitor {

  /** 列车状态快照。 */
  private record TrainSnapshot(
      int progressIndex,
      SignalAspect signal,
      double speedBpt,
      Instant captureTime,
      Instant lastMoveTime,
      Instant lastProgressTime) {}

  private final RuntimeDispatchService dispatchService;
  private final DwellRegistry dwellRegistry;
  private final HealthAlertBus alertBus;
  private final Consumer<String> debugLogger;

  private final Map<String, TrainSnapshot> snapshots = new ConcurrentHashMap<>();

  /** 静止阈值（秒）：有 PROCEED 信号但静止超过此时间触发告警。 */
  private Duration stallThreshold = Duration.ofSeconds(30);

  /** 进度不推进阈值（秒）。 */
  private Duration progressStuckThreshold = Duration.ofSeconds(60);

  /** 是否启用自动修复。 */
  private boolean autoFixEnabled = true;

  /** 低速判定阈值（blocks per tick）。 */
  private double lowSpeedThresholdBpt = 0.01;

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

    // 清理已消失列车的快照
    snapshots.keySet().removeIf(name -> !active.contains(name));

    int stallCount = 0;
    int progressStuckCount = 0;
    int fixedCount = 0;

    for (String trainName : active) {
      Optional<RuntimeDispatchService.TrainRuntimeState> stateOpt =
          dispatchService.getTrainState(trainName);
      if (stateOpt.isEmpty()) {
        continue;
      }
      RuntimeDispatchService.TrainRuntimeState state = stateOpt.get();

      TrainSnapshot prev = snapshots.get(trainName);
      int currentProgress = state.progressIndex();
      SignalAspect currentSignal = state.signalAspect();
      double currentSpeed = state.speedBlocksPerTick();

      boolean isMoving = currentSpeed > lowSpeedThresholdBpt;

      // 更新快照
      Instant lastMove =
          prev != null && isMoving ? now : (prev != null ? prev.lastMoveTime() : now);
      Instant lastProgress =
          prev != null && currentProgress != prev.progressIndex()
              ? now
              : (prev != null ? prev.lastProgressTime() : now);

      TrainSnapshot current =
          new TrainSnapshot(
              currentProgress, currentSignal, currentSpeed, now, lastMove, lastProgress);
      snapshots.put(trainName, current);

      if (prev == null) {
        continue; // 首次采样，跳过检测
      }

      // 排除正在停站（dwell）的列车：停站期间静止和进度不变都是正常的
      boolean isDwelling =
          dwellRegistry != null && dwellRegistry.remainingSeconds(trainName).isPresent();
      if (isDwelling) {
        continue;
      }

      // 检测：有 PROCEED 信号但长时间静止
      if (currentSignal == SignalAspect.PROCEED && !isMoving) {
        Duration stallDuration = Duration.between(lastMove, now);
        if (stallDuration.compareTo(stallThreshold) > 0) {
          stallCount++;
          boolean fixed = false;
          if (autoFixEnabled) {
            fixed = tryFixStall(trainName);
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
      }

      // 检测：进度长时间不推进
      Duration progressDuration = Duration.between(lastProgress, now);
      if (progressDuration.compareTo(progressStuckThreshold) > 0) {
        progressStuckCount++;
        boolean fixed = false;
        if (autoFixEnabled) {
          fixed = tryFixProgressStuck(trainName);
          if (fixed) {
            fixedCount++;
          }
        }
        alertBus.publish(
            fixed
                ? HealthAlert.fixed(
                    HealthAlert.AlertType.PROGRESS_STUCK,
                    trainName,
                    "进度停滞已修复: 持续=" + progressDuration.toSeconds() + "秒 idx=" + currentProgress)
                : HealthAlert.of(
                    HealthAlert.AlertType.PROGRESS_STUCK,
                    trainName,
                    "进度停滞: 持续=" + progressDuration.toSeconds() + "秒 idx=" + currentProgress));
      }
    }

    return new CheckResult(stallCount, progressStuckCount, fixedCount);
  }

  /** 清除所有快照。 */
  public void clear() {
    snapshots.clear();
  }

  /** 尝试修复静止列车。 */
  private boolean tryFixStall(String trainName) {
    debugLogger.accept("TrainHealthMonitor 尝试修复静止: train=" + trainName);
    // 刷新信号 + 强制重发
    dispatchService.refreshSignalByName(trainName);
    // 触发 relaunch（如果当前有有效 route）
    return dispatchService.forceRelaunchByName(trainName);
  }

  /** 尝试修复进度停滞。 */
  private boolean tryFixProgressStuck(String trainName) {
    debugLogger.accept("TrainHealthMonitor 尝试修复进度停滞: train=" + trainName);
    // 刷新信号
    dispatchService.refreshSignalByName(trainName);
    return true;
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
