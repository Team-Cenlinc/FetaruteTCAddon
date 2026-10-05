package org.fetarute.fetaruteTCAddon.drive.energy;

import java.util.Objects;

/**
 * 超级电容车的储能：站间靠电容行驶，停站开门时充电。
 *
 * <p>能量按每单位车重计（见 {@link SuperCapacitorConfig}）。牵引按牵引功率（牵引加速度 × 车速）除以牵引效率耗电；常用制动在退出速度以上按回收比例再生；
 * 辅助负载按时间耗电。电量耗尽时切断牵引，制动不受影响（空气制动）。
 *
 * <p>本类不依赖服务器对象，便于单测。
 */
public final class SuperCapacitor {

  /** 电量状态：用于提示，只在跨越时报一次。 */
  public enum Level {
    NORMAL,
    LOW,
    DEPLETED
  }

  private final SuperCapacitorConfig config;
  private double energy;
  private boolean charging;

  /**
   * @param config 参数
   * @param fraction 初始电量（0–1）
   */
  public SuperCapacitor(SuperCapacitorConfig config, double fraction) {
    this.config = Objects.requireNonNull(config, "config");
    this.energy = config.capacityEnergy() * clamp01(fraction);
  }

  public SuperCapacitorConfig config() {
    return config;
  }

  /** 改成给定电量（0–1）：接管时按列车上保存的电量。 */
  public void reset(double fraction) {
    energy = config.capacityEnergy() * clamp01(fraction);
    charging = false;
  }

  /** 电量（0–1）。 */
  public double fraction() {
    double capacity = config.capacityEnergy();
    return capacity > 0.0 ? clamp01(energy / capacity) : 0.0;
  }

  /** 电量状态。 */
  public Level level() {
    if (energy <= 0.0) {
      return Level.DEPLETED;
    }
    return fraction() < config.lowFraction() ? Level.LOW : Level.NORMAL;
  }

  /** 电量耗尽：切断牵引。 */
  public boolean depleted() {
    return energy <= 0.0;
  }

  /** 此刻是否在充电（最近一次推进时）。 */
  public boolean charging() {
    return charging;
  }

  /** 是否已充满。 */
  public boolean full() {
    return energy >= config.capacityEnergy();
  }

  /**
   * 行驶中推进一步：牵引耗电、常用制动再生、辅助负载耗电。
   *
   * @param tractionBps2 本步实际的牵引加速度（格/秒²，不含阻力）；不牵引时为 0
   * @param serviceBrakeBps2 本步常用制动的减速度（格/秒²）；紧急制动与停放制动不再生，传 0
   * @param speedBps 车速
   * @param seconds 步长
   */
  public void drive(double tractionBps2, double serviceBrakeBps2, double speedBps, double seconds) {
    if (!(seconds > 0.0)) {
      return;
    }
    charging = false;
    double v = Math.max(0.0, speedBps);
    double used = Math.max(0.0, tractionBps2) * v * seconds / config.tractionEfficiency();
    double regained =
        v > config.regenCutoffBps()
            ? Math.max(0.0, serviceBrakeBps2) * v * seconds * config.regenEfficiency()
            : 0.0;
    double aux = config.auxDrainPerSecond() * config.capacityEnergy() * seconds;
    energy = Math.max(0.0, Math.min(config.capacityEnergy(), energy - used - aux + regained));
  }

  /**
   * 停站充电一步：按满充时间线性充入。
   *
   * @param seconds 步长
   */
  public void charge(double seconds) {
    if (!(seconds > 0.0)) {
      return;
    }
    charging = !full();
    energy =
        Math.min(
            config.capacityEnergy(),
            energy + config.capacityEnergy() * seconds / config.fullChargeSeconds());
  }

  /** 不再充电（离站、关门）。 */
  public void stopCharging() {
    charging = false;
  }

  /**
   * 按当前电量估计还能起步几次到给定速度（不计再生与阻力），显示用。
   *
   * @param speedBps 线路速度
   */
  public double startsLeft(double speedBps) {
    double perStart = speedBps * speedBps / 2.0 / config.tractionEfficiency();
    return perStart > 0.0 ? energy / perStart : 0.0;
  }

  private static double clamp01(double value) {
    if (!(value > 0.0)) {
      return 0.0;
    }
    return Math.min(1.0, value);
  }
}
