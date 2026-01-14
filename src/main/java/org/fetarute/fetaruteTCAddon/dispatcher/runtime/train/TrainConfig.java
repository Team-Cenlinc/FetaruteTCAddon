package org.fetarute.fetaruteTCAddon.dispatcher.runtime.train;

import java.util.Objects;

/** 单列车速度与加减速配置（速度为 blocks/second，加速度为 blocks/second^2）。 */
public record TrainConfig(
    TrainType type,
    double cruiseSpeedBps,
    double cautionSpeedBps,
    double accelBps2,
    double decelBps2) {

  public TrainConfig {
    Objects.requireNonNull(type, "type");
    if (!Double.isFinite(cruiseSpeedBps) || cruiseSpeedBps <= 0.0) {
      throw new IllegalArgumentException("cruiseSpeedBps 必须为正数");
    }
    if (!Double.isFinite(cautionSpeedBps) || cautionSpeedBps < 0.0) {
      throw new IllegalArgumentException("cautionSpeedBps 必须为非负数");
    }
    if (!Double.isFinite(accelBps2) || accelBps2 <= 0.0) {
      throw new IllegalArgumentException("accelBps2 必须为正数");
    }
    if (!Double.isFinite(decelBps2) || decelBps2 <= 0.0) {
      throw new IllegalArgumentException("decelBps2 必须为正数");
    }
  }
}
