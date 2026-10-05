package org.fetarute.fetaruteTCAddon.drive.cab;

/**
 * 电空复合制动的参数（{@code drive.yml} 的 {@code simulation.blended-brake} 段）。
 *
 * <p>电力牵引的列车在电制动退出速度以上优先用电制动（再生制动），电制动承担不了的部分由空气制动补足；只有空气制动的部分消耗主风缸。
 *
 * <p>电制动在退出速度处完全退出，退出速度以上 {@link #FADE_BAND_BPS} 内线性过渡到全额。
 *
 * @param electricFraction 电制动能承担的制动力占常用全制动的比例，(0, 1]
 * @param electricExitBps 电制动退出速度（格/秒）：低于它电制动完全退出
 * @param airFraction 只靠空气制动时能发挥的制动力占常用全制动的比例（风压正常时），(0, 1]
 */
public record BlendedBrakeConfig(
    double electricFraction, double electricExitBps, double airFraction) {

  /** 1 格/秒折合的 km/h。 */
  private static final double KMH_PER_BPS = 3.6;

  /** 电制动从退出速度过渡到全额所需的速度增量（格/秒），约 5 km/h。 */
  public static final double FADE_BAND_BPS = 5.0 / KMH_PER_BPS;

  public BlendedBrakeConfig {
    requireFraction(electricFraction, "electricFraction");
    requireFraction(airFraction, "airFraction");
    if (!Double.isFinite(electricExitBps) || electricExitBps < 0.0) {
      throw new IllegalArgumentException("electricExitBps 不能为负数");
    }
  }

  /** 内置默认值：电制动承担七成，15 km/h 退出，单靠空气制动发挥八成五。 */
  public static BlendedBrakeConfig defaults() {
    return new BlendedBrakeConfig(0.7, 15.0 / KMH_PER_BPS, 0.85);
  }

  /**
   * 给定速度下电制动能承担的比例（占常用全制动）：退出速度以下为 0，过渡带内线性增加，之上为 {@link #electricFraction()}。
   *
   * @param speedBps 当前速度（格/秒）
   */
  public double electricCapacity(double speedBps) {
    if (!(speedBps > electricExitBps)) {
      return 0.0;
    }
    double ramp = (speedBps - electricExitBps) / FADE_BAND_BPS;
    return electricFraction * Math.min(1.0, ramp);
  }

  private static void requireFraction(double value, String name) {
    if (!Double.isFinite(value) || value <= 0.0 || value > 1.0) {
      throw new IllegalArgumentException(name + " 必须在 (0, 1] 内");
    }
  }
}
