package org.fetarute.fetaruteTCAddon.drive.dynamics;

import java.util.Objects;
import java.util.function.DoubleUnaryOperator;
import org.fetarute.fetaruteTCAddon.drive.DriveConfig;

/**
 * 手动驾驶的纵向动力学：按档位逐 tick 推进速度。
 *
 * <p>档位先换算成目标力（牵引为正、制动为负，满力为 1，紧急制动可超过 1），实际力以有限速率追随目标，避免换档时速度曲线出现折角； 再由实际力得到加速度，叠加惰行阻力后积分速度。速度不会小于
 * 0，也不会高于传入的速度上限。
 *
 * <p>本类不依赖任何服务器对象，只保存当前速度与实际力。速度单位为格/秒，时间步长由调用方传入。
 */
public final class DriveDynamics {

  private final DriveParams params;
  private final DriveConfig config;
  private double speedBps;
  private double effort;
  private double accelerationBps2;
  private double topSpeedBps = Double.NaN;

  public DriveDynamics(DriveParams params, DriveConfig config) {
    this.params = Objects.requireNonNull(params, "params");
    this.config = Objects.requireNonNull(config, "config");
  }

  /** 当前速度（格/秒）。 */
  public double speedBps() {
    return speedBps;
  }

  /** 当前实际力：正为牵引，负为制动。 */
  public double effort() {
    return effort;
  }

  /** 最近一步的加速度（格/秒²），含惰行阻力。 */
  public double accelerationBps2() {
    return accelerationBps2;
  }

  public DriveParams params() {
    return params;
  }

  /** 此刻能跑到的最高速度（格/秒）：没有另行设定时为车辆最高速度。 */
  public double topSpeedBps() {
    return Double.isFinite(topSpeedBps) ? topSpeedBps : params.maxSpeedBps();
  }

  /** 另行设定能跑到的最高速度（驾驶调度列车时跟随信号允许的速度）；NaN 表示回到车辆最高速度。 */
  public void setTopSpeedBps(double value) {
    this.topSpeedBps = Double.isFinite(value) && value > 0.0 ? value : Double.NaN;
  }

  /** 以给定速度重新开始，实际力归零。会话开始、接管已在运动的列车时使用。 */
  public void reset(double speedBps) {
    this.speedBps = Math.max(0.0, speedBps);
    this.effort = 0.0;
    this.accelerationBps2 = 0.0;
  }

  /**
   * 推进一步。
   *
   * @param seconds 时间步长（秒）
   * @param notch 当前档位
   * @param capBps 速度上限（格/秒），通常取最高速度与限速中较小者
   * @return 推进后的速度（格/秒）
   */
  public double step(double seconds, Notch notch, double capBps) {
    return step(seconds, notch, capBps, 1.0);
  }

  /**
   * 推进一步，制动力按可发挥的比例打折（风压不足时空气制动力下降）。
   *
   * @param brakeScale 制动力可发挥的比例，0–1
   */
  public double step(double seconds, Notch notch, double capBps, double brakeScale) {
    return step(seconds, notch, capBps, 1.0, demand -> brakeScale);
  }

  /**
   * 推进一步，牵引力与制动力分别打折。
   *
   * <p>牵引力乘以 {@code tractionScale}（恒功率段随速度下降）；制动力乘以 {@code brakeScale} 按本步实际制动力度给出的比例
   * （电空复合制动时，实际发挥多少取决于电制动承担了多少）。两者都限制在 0–1。
   *
   * @param tractionScale 牵引力可发挥的比例，0–1
   * @param brakeScale 由制动力度（常用全制动为 1）求制动力可发挥的比例
   */
  public double step(
      double seconds,
      Notch notch,
      double capBps,
      double tractionScale,
      DoubleUnaryOperator brakeScale) {
    Objects.requireNonNull(notch, "notch");
    Objects.requireNonNull(brakeScale, "brakeScale");
    if (!(seconds > 0.0) || !Double.isFinite(seconds)) {
      return speedBps;
    }
    double target = targetEffort(notch);
    double rate =
        notch == Notch.EB ? config.emergencyRatePerSecond() : config.effortRatePerSecond();
    double maxDelta = rate * seconds;
    effort += Math.max(-maxDelta, Math.min(maxDelta, target - effort));

    double a;
    if (effort >= 0.0) {
      a = effort * params.accelBps2() * clampScale(tractionScale);
    } else {
      a = effort * params.decelBps2() * clampScale(brakeScale.applyAsDouble(-effort));
    }
    if (speedBps > 0.0) {
      a -= config.coastDragBps2();
    }
    accelerationBps2 = a;
    double cap = Math.max(0.0, Math.min(capBps, topSpeedBps()));
    speedBps = Math.max(0.0, Math.min(cap, speedBps + a * seconds));
    if (speedBps <= 0.0 && effort < 0.0) {
      accelerationBps2 = 0.0;
    }
    return speedBps;
  }

  private static double clampScale(double scale) {
    return Double.isNaN(scale) ? 1.0 : Math.max(0.0, Math.min(1.0, scale));
  }

  private double targetEffort(Notch notch) {
    return switch (notch.kind()) {
      case TRACTION -> config.tractionFraction(notch);
      case COAST -> 0.0;
      case BRAKE, EMERGENCY -> -config.brakeFraction(notch);
    };
  }
}
