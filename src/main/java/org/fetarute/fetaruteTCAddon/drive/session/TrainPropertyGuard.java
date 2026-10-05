package org.fetarute.fetaruteTCAddon.drive.session;

import com.bergerkiller.bukkit.tc.properties.TrainProperties;
import java.util.Optional;
import java.util.OptionalDouble;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.TrainTagHelper;

/**
 * 驾驶期间对 TrainCarts 列车属性的临时调整，以及会话结束后的还原。
 *
 * <p>手动驾驶由我们自己积分速度，所以要关掉 TrainCarts 的自然减速（否则与惰行阻力叠加），并把速度上限抬到列车的最高速度（TrainCarts 默认上限很低）。
 * 调整前的原值写进列车标签：服务器在会话中途崩溃时标签随列车保存，下一次驾驶或还原会沿用这份原值，而不是把调整后的值当作原值。
 */
final class TrainPropertyGuard {

  static final String TAG_SLOWDOWN = "FTA_DRIVE_SLOWDOWN_ORIG";
  static final String TAG_SPEED_LIMIT = "FTA_DRIVE_SPEEDLIMIT_ORIG";

  private static final double TICKS_PER_SECOND = 20.0;

  private TrainPropertyGuard() {}

  /** 应用驾驶所需的属性，并在标签里留存原值（已有留存时保留更早的原值）。 */
  static void apply(TrainProperties properties, double maxSpeedBps) {
    if (readTag(properties, TAG_SLOWDOWN).isEmpty()) {
      TrainTagHelper.writeTag(properties, TAG_SLOWDOWN, String.valueOf(properties.isSlowingDown()));
    }
    properties.setSlowingDown(false);
    if (readTag(properties, TAG_SPEED_LIMIT).isEmpty()) {
      TrainTagHelper.writeTag(
          properties, TAG_SPEED_LIMIT, String.valueOf(properties.getSpeedLimit()));
    }
    double wanted = maxSpeedBps / TICKS_PER_SECOND;
    if (properties.getSpeedLimit() < wanted) {
      properties.setSpeedLimit(wanted);
    }
  }

  /** 还原留存的原值并清掉标签。没有留存（从未应用过）时什么也不做。 */
  static void restore(TrainProperties properties) {
    restore(properties, OptionalDouble.empty());
  }

  /**
   * 还原留存的原值并清掉标签。
   *
   * @param observedLimitBpt 驾驶期间线路给出的限速（格/tick）；有值时还原成它而不是上车前的旧值，免得列车带着与所在位置不符的限速
   */
  static void restore(TrainProperties properties, OptionalDouble observedLimitBpt) {
    Optional<String> slowdown = readTag(properties, TAG_SLOWDOWN);
    if (slowdown.isPresent()) {
      properties.setSlowingDown(Boolean.parseBoolean(slowdown.get()));
      TrainTagHelper.removeTagKey(properties, TAG_SLOWDOWN);
    }
    Optional<String> speedLimit = readTag(properties, TAG_SPEED_LIMIT);
    if (speedLimit.isPresent()) {
      try {
        double original = Double.parseDouble(speedLimit.get());
        double wanted = observedLimitBpt.orElse(original);
        if (Double.isFinite(wanted) && wanted > 0.0) {
          properties.setSpeedLimit(wanted);
        }
      } catch (NumberFormatException ignored) {
        // 留存值损坏时保持当前上限，仍然清掉标签。
      }
      TrainTagHelper.removeTagKey(properties, TAG_SPEED_LIMIT);
    }
  }

  private static Optional<String> readTag(TrainProperties properties, String key) {
    return TrainTagHelper.readTagValue(properties, key);
  }
}
