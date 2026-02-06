package org.fetarute.fetaruteTCAddon.dispatcher.health;

import com.bergerkiller.bukkit.tc.controller.MinecartGroup;
import com.bergerkiller.bukkit.tc.controller.MinecartGroupStore;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import org.fetarute.fetaruteTCAddon.config.ConfigManager;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.DwellRegistry;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchService;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyManager;

/**
 * 健康监控器：整合列车健康检查与占用自愈。
 *
 * <p>定期执行检查并触发自动修复，通过 {@link HealthAlertBus} 分发告警。
 */
public final class HealthMonitor {

  private final RuntimeDispatchService dispatchService;
  private final OccupancyManager occupancyManager;
  private final ConfigManager configManager;
  private final Consumer<String> debugLogger;

  private final HealthAlertBus alertBus;
  private final TrainHealthMonitor trainMonitor;
  private final OccupancyHealer occupancyHealer;

  /** 是否启用。 */
  private volatile boolean enabled = true;

  /** 上次检查时间。 */
  private volatile Instant lastCheckTime = Instant.EPOCH;

  /** 检查间隔。 */
  private Duration checkInterval = Duration.ofSeconds(5);

  /** 统计：总检查次数。 */
  private final java.util.concurrent.atomic.LongAdder checkCount =
      new java.util.concurrent.atomic.LongAdder();

  /** 统计：总修复次数。 */
  private final java.util.concurrent.atomic.LongAdder fixCount =
      new java.util.concurrent.atomic.LongAdder();

  public HealthMonitor(
      RuntimeDispatchService dispatchService,
      OccupancyManager occupancyManager,
      DwellRegistry dwellRegistry,
      ConfigManager configManager,
      Consumer<String> debugLogger) {
    this.dispatchService = Objects.requireNonNull(dispatchService, "dispatchService");
    this.occupancyManager = Objects.requireNonNull(occupancyManager, "occupancyManager");
    this.configManager = configManager;
    this.debugLogger = debugLogger != null ? debugLogger : msg -> {};

    this.alertBus = new HealthAlertBus();
    this.trainMonitor =
        new TrainHealthMonitor(dispatchService, dwellRegistry, alertBus, debugLogger);
    this.occupancyHealer = new OccupancyHealer(occupancyManager, alertBus, debugLogger);

    // 默认添加日志监听器
    alertBus.subscribe(this::logAlert);
  }

  /** 获取告警总线（用于注册外部监听器）。 */
  public HealthAlertBus alertBus() {
    return alertBus;
  }

  /** 设置是否启用。 */
  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  /** 设置检查间隔。 */
  public void setCheckInterval(Duration interval) {
    if (interval != null && !interval.isNegative() && !interval.isZero()) {
      this.checkInterval = interval;
    }
  }

  /** 设置静止阈值。 */
  public void setStallThreshold(Duration threshold) {
    trainMonitor.setStallThreshold(threshold);
  }

  /** 设置进度停滞阈值。 */
  public void setProgressStuckThreshold(Duration threshold) {
    trainMonitor.setProgressStuckThreshold(threshold);
  }

  /** 设置占用超时阈值。 */
  public void setOccupancyTimeout(Duration timeout) {
    occupancyHealer.setOccupancyTimeout(timeout);
  }

  /** 设置是否启用自动修复。 */
  public void setAutoFixEnabled(boolean enabled) {
    trainMonitor.setAutoFixEnabled(enabled);
  }

  /** 设置是否启用孤儿占用清理。 */
  public void setOrphanCleanupEnabled(boolean enabled) {
    occupancyHealer.setOrphanCleanupEnabled(enabled);
  }

  /** 设置是否启用超时占用清理。 */
  public void setTimeoutCleanupEnabled(boolean enabled) {
    occupancyHealer.setTimeoutCleanupEnabled(enabled);
  }

  /**
   * 执行一次 tick（由主调度循环调用）。
   *
   * <p>如果距离上次检查未超过 checkInterval，则跳过。
   */
  public void tick() {
    if (!enabled) {
      return;
    }
    Instant now = Instant.now();
    if (Duration.between(lastCheckTime, now).compareTo(checkInterval) < 0) {
      return;
    }
    lastCheckTime = now;

    // 获取当前存活列车
    Set<String> activeTrains = collectActiveTrainNames();

    // 执行检查
    checkCount.increment();

    TrainHealthMonitor.CheckResult trainResult = trainMonitor.check(activeTrains, now);
    OccupancyHealer.HealResult occupancyResult = occupancyHealer.heal(activeTrains, now);

    int totalFixed = trainResult.fixedCount() + occupancyResult.total();
    if (totalFixed > 0) {
      fixCount.add(totalFixed);
    }

    // 调试日志（仅在有异常时输出）
    if (trainResult.hasAnomalies() || occupancyResult.hasChanges()) {
      debugLogger.accept(
          "HealthMonitor tick: stall="
              + trainResult.stallCount()
              + " stuck="
              + trainResult.progressStuckCount()
              + " orphan="
              + occupancyResult.orphanCleaned()
              + " timeout="
              + occupancyResult.timeoutCleaned()
              + " fixed="
              + totalFixed);
    }
  }

  /** 立即执行一次完整检查（不受 checkInterval 限制）。 */
  public CheckResult checkNow() {
    Instant now = Instant.now();
    Set<String> activeTrains = collectActiveTrainNames();

    checkCount.increment();

    TrainHealthMonitor.CheckResult trainResult = trainMonitor.check(activeTrains, now);
    OccupancyHealer.HealResult occupancyResult = occupancyHealer.heal(activeTrains, now);

    int totalFixed = trainResult.fixedCount() + occupancyResult.total();
    if (totalFixed > 0) {
      fixCount.add(totalFixed);
    }

    lastCheckTime = now;
    return new CheckResult(
        trainResult.stallCount(),
        trainResult.progressStuckCount(),
        occupancyResult.orphanCleaned(),
        occupancyResult.timeoutCleaned(),
        totalFixed);
  }

  /** 获取诊断快照。 */
  public DiagnosticsSnapshot diagnostics() {
    return new DiagnosticsSnapshot(
        enabled, checkCount.sum(), fixCount.sum(), lastCheckTime, alertBus.recentAlerts(20));
  }

  /** 清除所有状态（用于 reload）。 */
  public void clear() {
    trainMonitor.clear();
    alertBus.clear();
    lastCheckTime = Instant.EPOCH;
  }

  private Set<String> collectActiveTrainNames() {
    Set<String> names = new HashSet<>();
    for (MinecartGroup group : MinecartGroupStore.getGroups()) {
      if (group == null || !group.isValid()) {
        continue;
      }
      if (group.getProperties() != null) {
        String name = group.getProperties().getTrainName();
        if (name != null && !name.isBlank()) {
          names.add(name);
        }
      }
    }
    return names;
  }

  private void logAlert(HealthAlert alert) {
    if (alert == null) {
      return;
    }
    String prefix = alert.autoFixed() ? "[FTA Health] 已修复: " : "[FTA Health] 告警: ";
    java.util.logging.Logger.getLogger("FetaruteTCAddon")
        .warning(prefix + alert.type() + " " + alert.message());
  }

  /** 单次检查结果。 */
  public record CheckResult(
      int stallCount,
      int progressStuckCount,
      int orphanCleaned,
      int timeoutCleaned,
      int totalFixed) {
    public boolean hasIssues() {
      return stallCount > 0 || progressStuckCount > 0 || orphanCleaned > 0 || timeoutCleaned > 0;
    }
  }

  /** 诊断快照。 */
  public record DiagnosticsSnapshot(
      boolean enabled,
      long totalChecks,
      long totalFixes,
      Instant lastCheckTime,
      java.util.List<HealthAlert> recentAlerts) {}
}
