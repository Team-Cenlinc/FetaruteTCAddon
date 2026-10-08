package org.fetarute.fetaruteTCAddon.drive.driver;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 驾驶员列车挡住后车的提醒：后方被它直接挡住的车里，被它挡得最久的那列挡了多久；超过告警线提醒驾驶员，超过强制线且驾驶员列车停着、 不在表定停站时转 ATO。
 *
 * <p>只按驾驶员列车挡住的这一段计时，不按后车整段被扣的时长：后车的扣车时刻换阻挡者也不重置，先被别的车扣了很久的后车一转到驾驶员列车后面， 就会被当成驾驶员挡了很久。
 *
 * <p>只看直接阻挡（后车的阻挡者就是驾驶员列车）；沿阻挡链的全网判定由 {@link CongestionProtection} 负责。本类不依赖服务器对象。
 */
public final class DriverCongestion {

  /** 处置档位。 */
  public enum Stage {
    NONE,
    WARN,
    ATO
  }

  /** 一列后车被一列驾驶员列车直接挡住。 */
  public record Blocking(String follower, String driver) {}

  private DriverCongestion() {}

  /**
   * 更新每列后车从什么时候起被哪列驾驶员列车直接挡住：这一轮仍挡着的沿用上一轮的起始时刻，新挡上的从现在起算，不再挡着的去掉。
   *
   * @param holds 每列被扣住的车
   * @param drivers 要计时的驾驶员列车（表定停站、被调度扣住、终点站等开出下一趟的不在内）
   * @param previous 上一轮的结果
   */
  public static Map<Blocking, Instant> trackBlocking(
      Map<String, CongestionProtection.Hold> holds,
      Set<String> drivers,
      Map<Blocking, Instant> previous,
      Instant now) {
    Objects.requireNonNull(holds, "holds");
    Map<Blocking, Instant> since = new HashMap<>();
    for (Map.Entry<String, CongestionProtection.Hold> entry : holds.entrySet()) {
      for (String blocker : entry.getValue().blockers()) {
        if (!blocker.equals(entry.getKey()) && drivers.contains(blocker)) {
          Blocking blocking = new Blocking(entry.getKey(), blocker);
          since.put(blocking, previous.getOrDefault(blocking, now));
        }
      }
    }
    return Map.copyOf(since);
  }

  /**
   * 被驾驶员列车直接挡住的车里，被它挡得最久的秒数；没有时为 0。
   *
   * @param since {@link #trackBlocking} 的结果
   * @param driverTrain 驾驶员列车的车名
   */
  public static long blockedBehindSeconds(
      Map<Blocking, Instant> since, String driverTrain, Instant now) {
    Objects.requireNonNull(since, "since");
    if (driverTrain == null || driverTrain.isBlank()) {
      return 0L;
    }
    long longest = 0L;
    for (Map.Entry<Blocking, Instant> entry : since.entrySet()) {
      if (entry.getKey().driver().equals(driverTrain)) {
        longest =
            Math.max(longest, Math.max(0L, Duration.between(entry.getValue(), now).toSeconds()));
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
