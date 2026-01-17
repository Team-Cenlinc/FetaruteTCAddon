package org.fetarute.fetaruteTCAddon.dispatcher.runtime.train;

import java.util.Objects;

/**
 * 单列车加减速配置（加速度为 blocks/second^2）。
 *
 * <p>巡航/警示速度由调度图与连通分量规则决定，本配置仅负责加减速曲线。
 */
public record TrainConfig(TrainType type, double accelBps2, double decelBps2) {

  public TrainConfig {
    Objects.requireNonNull(type, "type");
    if (!Double.isFinite(accelBps2) || accelBps2 <= 0.0) {
      throw new IllegalArgumentException("accelBps2 必须为正数");
    }
    if (!Double.isFinite(decelBps2) || decelBps2 <= 0.0) {
      throw new IllegalArgumentException("decelBps2 必须为正数");
    }
  }
}
