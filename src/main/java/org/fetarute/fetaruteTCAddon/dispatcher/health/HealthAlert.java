package org.fetarute.fetaruteTCAddon.dispatcher.health;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * 健康告警事件。
 *
 * <p>由 {@link HealthMonitor} 检测到异常时生成，通过 {@link HealthAlertBus} 分发给监听器。
 */
public record HealthAlert(
    AlertType type, String trainName, String message, Instant timestamp, boolean autoFixed) {

  /** 告警类型。 */
  public enum AlertType {
    /** 列车长时间静止（有信号但不动）。 */
    STALL,
    /** 进度长时间不推进。 */
    PROGRESS_STUCK,
    /** 列车持续回退。 */
    REGRESSION,
    /** 孤儿占用（列车已消失但占用未释放）。 */
    ORPHAN_OCCUPANCY,
    /** 占用超时。 */
    OCCUPANCY_TIMEOUT,
    /** 偏离路线。 */
    ROUTE_DEVIATION,
    /** 信号状态异常。 */
    SIGNAL_ANOMALY
  }

  public HealthAlert {
    Objects.requireNonNull(type, "type");
    message = message == null ? "" : message;
    timestamp = timestamp == null ? Instant.now() : timestamp;
  }

  /** 创建告警（未自动修复）。 */
  public static HealthAlert of(AlertType type, String trainName, String message) {
    return new HealthAlert(type, trainName, message, Instant.now(), false);
  }

  /** 创建告警（已自动修复）。 */
  public static HealthAlert fixed(AlertType type, String trainName, String message) {
    return new HealthAlert(type, trainName, message, Instant.now(), true);
  }

  /** 获取列车名（可能为空，如占用告警）。 */
  public Optional<String> train() {
    return Optional.ofNullable(trainName).filter(s -> !s.isBlank());
  }
}
