package org.fetarute.fetaruteTCAddon.dispatcher.health;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyClaim;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyManager;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyResource;

/**
 * 占用状态自愈器：检测并清理不一致的占用状态。
 *
 * <p>检测项：
 *
 * <ul>
 *   <li>孤儿占用：占用存在但列车已不存在
 *   <li>超时占用：占用时间超过配置阈值（仅针对离线列车，避免误清理仍在线列车的合法占用）
 * </ul>
 */
public final class OccupancyHealer {

  private final OccupancyManager occupancyManager;
  private final HealthAlertBus alertBus;
  private final Consumer<String> debugLogger;

  /** 占用超时阈值（默认 10 分钟）。 */
  private Duration occupancyTimeout = Duration.ofMinutes(10);

  /** 是否启用孤儿清理。 */
  private boolean orphanCleanupEnabled = true;

  /** 是否启用超时清理。 */
  private boolean timeoutCleanupEnabled = true;

  public OccupancyHealer(
      OccupancyManager occupancyManager, HealthAlertBus alertBus, Consumer<String> debugLogger) {
    this.occupancyManager = Objects.requireNonNull(occupancyManager, "occupancyManager");
    this.alertBus = alertBus != null ? alertBus : new HealthAlertBus();
    this.debugLogger = debugLogger != null ? debugLogger : msg -> {};
  }

  /** 设置占用超时阈值。 */
  public void setOccupancyTimeout(Duration timeout) {
    if (timeout != null && !timeout.isNegative()) {
      this.occupancyTimeout = timeout;
    }
  }

  /** 设置是否启用孤儿清理。 */
  public void setOrphanCleanupEnabled(boolean enabled) {
    this.orphanCleanupEnabled = enabled;
  }

  /** 设置是否启用超时清理。 */
  public void setTimeoutCleanupEnabled(boolean enabled) {
    this.timeoutCleanupEnabled = enabled;
  }

  /**
   * 执行一次自愈检查。
   *
   * @param activeTrains 当前存活的列车名集合
   * @param now 当前时间
   * @return 修复结果
   */
  public HealResult heal(Set<String> activeTrains, Instant now) {
    if (now == null) {
      now = Instant.now();
    }
    Set<String> active = activeTrains == null ? Set.of() : Set.copyOf(activeTrains);

    List<OccupancyClaim> claims = occupancyManager.snapshotClaims();
    int orphanCount = 0;
    int timeoutCount = 0;
    List<OccupancyResource> toRelease = new ArrayList<>();

    for (OccupancyClaim claim : claims) {
      if (claim == null || claim.resource() == null) {
        continue;
      }
      String trainName = claim.trainName();
      boolean activeTrain = active.contains(trainName);

      // 孤儿检测
      if (orphanCleanupEnabled && !activeTrain) {
        toRelease.add(claim.resource());
        orphanCount++;
        alertBus.publish(
            HealthAlert.fixed(
                HealthAlert.AlertType.ORPHAN_OCCUPANCY, trainName, "孤儿占用已清理: " + claim.resource()));
        debugLogger.accept(
            "OccupancyHealer 清理孤儿占用: train=" + trainName + " resource=" + claim.resource());
        continue;
      }

      // 超时检测
      if (timeoutCleanupEnabled) {
        // 对在线列车不做超时清理：事件反射式占用的 acquiredAt 可能较早，直接按时长清理会误删合法占用。
        if (activeTrain) {
          continue;
        }
        Duration age = Duration.between(claim.acquiredAt(), now);
        if (age.compareTo(occupancyTimeout) > 0) {
          toRelease.add(claim.resource());
          timeoutCount++;
          alertBus.publish(
              HealthAlert.fixed(
                  HealthAlert.AlertType.OCCUPANCY_TIMEOUT,
                  trainName,
                  "超时占用已清理: " + claim.resource() + " 持续=" + age.toMinutes() + "分钟"));
          debugLogger.accept(
              "OccupancyHealer 清理超时占用: train="
                  + trainName
                  + " resource="
                  + claim.resource()
                  + " age="
                  + age.toMinutes()
                  + "m");
        }
      }
    }

    // 释放
    for (OccupancyResource resource : toRelease) {
      occupancyManager.releaseResource(resource, Optional.empty());
    }

    return new HealResult(orphanCount, timeoutCount);
  }

  /** 自愈结果。 */
  public record HealResult(int orphanCleaned, int timeoutCleaned) {
    public int total() {
      return orphanCleaned + timeoutCleaned;
    }

    public boolean hasChanges() {
      return total() > 0;
    }
  }
}
