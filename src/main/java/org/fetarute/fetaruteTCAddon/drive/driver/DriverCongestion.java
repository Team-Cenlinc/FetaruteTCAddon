package org.fetarute.fetaruteTCAddon.drive.driver;

import java.util.Map;
import java.util.Objects;

/**
 * 驾驶员列车挡住后车的提醒：后方被它直接挡住的车里，被扣得最久的那列扣了多久；超过告警线提醒驾驶员，超过强制线且驾驶员列车停着、 不在表定停站时转 ATO。
 *
 * <p>只看直接阻挡（后车的阻挡者就是驾驶员列车）；沿阻挡链的全网判定由 {@link DriverCircuitBreaker} 负责。本类不依赖服务器对象。
 */
public final class DriverCongestion {

  /** 处置档位。 */
  public enum Stage {
    NONE,
    WARN,
    ATO
  }

  private DriverCongestion() {}

  /**
   * 被驾驶员列车直接挡住的车里，被扣最久的秒数；没有时为 0。
   *
   * @param holds 每列被扣住的车
   * @param driverTrain 驾驶员列车的车名
   */
  public static long blockedBehindSeconds(
      Map<String, DriverCircuitBreaker.Hold> holds, String driverTrain) {
    Objects.requireNonNull(holds, "holds");
    if (driverTrain == null || driverTrain.isBlank()) {
      return 0L;
    }
    long longest = 0L;
    for (Map.Entry<String, DriverCircuitBreaker.Hold> entry : holds.entrySet()) {
      if (entry.getKey().equals(driverTrain)) {
        continue;
      }
      if (entry.getValue().blockers().contains(driverTrain)) {
        longest = Math.max(longest, Math.max(0L, entry.getValue().held().toSeconds()));
      }
    }
    return longest;
  }

  /** 按被挡时长取档位。 */
  public static Stage stage(long blockedSeconds, DriverRecovery recovery) {
    if (blockedSeconds >= recovery.congestionAtoSeconds()) {
      return Stage.ATO;
    }
    return blockedSeconds >= recovery.congestionWarnSeconds() ? Stage.WARN : Stage.NONE;
  }
}
