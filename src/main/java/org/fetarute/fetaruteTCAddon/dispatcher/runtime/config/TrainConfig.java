package org.fetarute.fetaruteTCAddon.dispatcher.runtime.config;

import java.util.Objects;
import java.util.OptionalDouble;

/**
 * 单列车加减速配置（加速度为 blocks/second^2）。
 *
 * <p>巡航/警示速度由调度图与连通分量规则决定，本配置负责加减速曲线，以及车型自身的最高速度：自动控车的目标速度不超过它 （编表按车型排时分时同样按它封顶边限速）。
 *
 * @param maxSpeedBps 车型最高速度（格/秒）；不限时为空
 */
public record TrainConfig(
    TrainType type, double accelBps2, double decelBps2, OptionalDouble maxSpeedBps) {

  /** 不限最高速度的配置。 */
  public TrainConfig(TrainType type, double accelBps2, double decelBps2) {
    this(type, accelBps2, decelBps2, OptionalDouble.empty());
  }

  /**
   * 按车型最高速度封顶。
   *
   * @param bps 速度（格/秒）
   * @return 不超过最高速度的速度；不限时原样返回
   */
  public double capped(double bps) {
    return maxSpeedBps.isPresent() ? Math.min(bps, maxSpeedBps.getAsDouble()) : bps;
  }

  public TrainConfig {
    maxSpeedBps =
        maxSpeedBps == null
                || maxSpeedBps.isEmpty()
                || !Double.isFinite(maxSpeedBps.getAsDouble())
                || maxSpeedBps.getAsDouble() <= 0.0
            ? OptionalDouble.empty()
            : maxSpeedBps;
    Objects.requireNonNull(type, "type");
    if (!Double.isFinite(accelBps2) || accelBps2 <= 0.0) {
      throw new IllegalArgumentException("accelBps2 必须为正数");
    }
    if (!Double.isFinite(decelBps2) || decelBps2 <= 0.0) {
      throw new IllegalArgumentException("decelBps2 必须为正数");
    }
  }
}
