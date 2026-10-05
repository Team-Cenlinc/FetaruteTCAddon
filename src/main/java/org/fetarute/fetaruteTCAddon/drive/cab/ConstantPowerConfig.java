package org.fetarute.fetaruteTCAddon.drive.cab;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.config.TrainType;

/**
 * 牵引的恒功率段参数（{@code drive.yml} 的 {@code simulation.constant-power} 段）。
 *
 * <p>速度高于拐点速度后牵引功率不再增加，牵引加速度按 {@code 拐点速度 ÷ 当前速度} 下降。拐点速度可按车种覆盖，未列出的车种取全局默认。
 *
 * @param kneeBps 全局默认的拐点速度（格/秒）
 * @param kneeBpsByType 按车种覆盖的拐点速度（格/秒）
 */
public record ConstantPowerConfig(double kneeBps, Map<TrainType, Double> kneeBpsByType) {

  /** 1 格/秒折合的 km/h。 */
  private static final double KMH_PER_BPS = 3.6;

  public ConstantPowerConfig {
    requirePositive(kneeBps, "kneeBps");
    Objects.requireNonNull(kneeBpsByType, "kneeBpsByType");
    for (Map.Entry<TrainType, Double> entry : kneeBpsByType.entrySet()) {
      Objects.requireNonNull(entry.getKey(), "kneeBpsByType 的车种");
      requirePositive(entry.getValue(), "kneeBpsByType." + entry.getKey().key());
    }
    kneeBpsByType = Map.copyOf(kneeBpsByType);
  }

  /** 内置默认值：全局 40 km/h；电动车组 50、电力机车 45，内燃动车组 30、内燃机车推拉 25。 */
  public static ConstantPowerConfig defaults() {
    Map<TrainType, Double> byType = new EnumMap<>(TrainType.class);
    byType.put(TrainType.EMU, 50.0 / KMH_PER_BPS);
    byType.put(TrainType.DMU, 30.0 / KMH_PER_BPS);
    byType.put(TrainType.ELECTRIC_LOCO, 45.0 / KMH_PER_BPS);
    byType.put(TrainType.DIESEL_PUSH_PULL, 25.0 / KMH_PER_BPS);
    return new ConstantPowerConfig(40.0 / KMH_PER_BPS, byType);
  }

  /** 该车种的拐点速度（格/秒）；没有覆盖时取全局默认。 */
  public double kneeBpsFor(TrainType type) {
    if (type == null) {
      return kneeBps;
    }
    return kneeBpsByType.getOrDefault(type, kneeBps);
  }

  /**
   * 恒功率段的牵引力系数：拐点速度以下为 1，以上为 {@code 拐点速度 ÷ 当前速度}。
   *
   * @param speedBps 当前速度（格/秒）
   * @param kneeBps 拐点速度（格/秒）
   */
  public static double tractionScale(double speedBps, double kneeBps) {
    if (!(kneeBps > 0.0) || !(speedBps > kneeBps)) {
      return 1.0;
    }
    return kneeBps / speedBps;
  }

  private static void requirePositive(Double value, String name) {
    if (value == null || !Double.isFinite(value) || value <= 0.0) {
      throw new IllegalArgumentException(name + " 必须为正数");
    }
  }
}
