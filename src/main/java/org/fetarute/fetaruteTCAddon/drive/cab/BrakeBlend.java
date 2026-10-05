package org.fetarute.fetaruteTCAddon.drive.cab;

/**
 * 电空复合制动的分配：制动力度先由电制动承担（不超过它此刻的能力），剩下的交给空气制动。
 *
 * <p>空气制动的力与制动缸压力成正比：单靠空气制动、制动缸达到常用全制动压力时发挥 {@link BlendedBrakeConfig#airFraction()}
 * 的常用全制动力，所以空气制动承担 {@code a} 时制动缸指令为 {@code a ÷ airFraction} 倍常用全制动压力（不超过上限）。本类只有纯函数。
 */
public final class BrakeBlend {

  /**
   * 一次分配。
   *
   * @param electric 电制动承担的制动力（占常用全制动）
   * @param air 空气制动承担的制动力（占常用全制动）
   */
  public record Split(double electric, double air) {

    /** 总制动力度。 */
    public double total() {
      return electric + air;
    }
  }

  private BrakeBlend() {}

  /**
   * 把制动力度分给电制动与空气制动。
   *
   * @param demand 制动力度（常用全制动为 1）
   * @param electricCapacity 电制动此刻能承担的比例；不可用时为 0
   */
  public static Split split(double demand, double electricCapacity) {
    double total = Double.isFinite(demand) ? Math.max(0.0, demand) : 0.0;
    double capacity = Double.isFinite(electricCapacity) ? Math.max(0.0, electricCapacity) : 0.0;
    double electric = Math.min(total, capacity);
    return new Split(electric, total - electric);
  }

  /**
   * 空气制动承担 {@code airDemand} 时的制动缸指令，占常用全制动压力的倍数。
   *
   * @param airDemand 空气制动承担的制动力
   * @param airFraction 单靠空气制动、制动缸全压时发挥的制动力
   * @param cap 倍数上限：常用制动为 1，紧急制动可到紧急制动倍数
   */
  public static double cylinderRatio(double airDemand, double airFraction, double cap) {
    if (!(airDemand > 0.0) || !(airFraction > 0.0)) {
      return 0.0;
    }
    return Math.min(Math.max(0.0, cap), airDemand / airFraction);
  }

  /**
   * 实际发挥的制动力与指令之比（0–1）。
   *
   * @param demand 制动力度
   * @param split 分配结果
   * @param airAvailable 空气制动此刻能发挥的制动力（按制动缸实际压力与风压折算）
   */
  public static double deliveredScale(double demand, Split split, double airAvailable) {
    if (!(demand > 1e-9)) {
      return 1.0;
    }
    double air = Math.min(split.air(), Math.max(0.0, airAvailable));
    return Math.max(0.0, Math.min(1.0, (split.electric() + air) / demand));
  }
}
