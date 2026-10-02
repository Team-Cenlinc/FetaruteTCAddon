package org.fetarute.fetaruteTCAddon.dispatcher.eta.model;

/**
 * 列车的加减速曲线：首尾柔和的 S 形，编表运行曲线与运行时控车共用。
 *
 * <p>加速度与减速度只取决于速度本身和这一段的起止速度，不取决于经过的时间：
 *
 * <ul>
 *   <li>加速：以 {@link #START_FLOOR} 倍满值起步，约 {@value #START_SECONDS} 秒升到满值；离目标速度还差约 {@value
 *       #END_SECONDS} 秒可走完的速度差时开始渐收，最后以 {@link #END_FLOOR} 倍满值贴上目标；
 *   <li>制动：从巡航速度开始时同样由小到大，接近末速度时同样渐收。
 * </ul>
 *
 * <p>只看速度而不看时间，控车在任何时刻中断、重新下发都会沿同一条曲线接着走，编表按里程积分得到的也是同一条曲线。 两端不能从 0 开始：纯按速度的规律若起点加速度为 0
 * 就永远离不开起点，所以起止各留一个下限比例。
 *
 * @param accelBps2 满加速度（格/秒²），必须为正
 * @param decelBps2 满减速度（常用制动，格/秒²），必须为正
 */
public record SpeedCurve(double accelBps2, double decelBps2) {

  /** 加速起步、制动开始时的加减速度占满值的比例。 */
  static final double START_FLOOR = 0.3;

  /** 从起始比例升到满值的时长（秒，按满值计）。 */
  static final double START_SECONDS = 1.5;

  /** 加速到速、制动到末速度时的加减速度占满值的比例。 */
  static final double END_FLOOR = 0.25;

  /** 从满值渐收到末端比例、再贴上目标速度的总时长（秒，按满值计）。 */
  static final double END_SECONDS = 2.5;

  public SpeedCurve {
    if (!Double.isFinite(accelBps2) || accelBps2 <= 0.0) {
      throw new IllegalArgumentException("accelBps2 必须为正数");
    }
    if (!Double.isFinite(decelBps2) || decelBps2 <= 0.0) {
      throw new IllegalArgumentException("decelBps2 必须为正数");
    }
  }

  /** 没有配置可读时的加减速（插件还没加载配置、单元测试）；有配置时一律读车种配置。 */
  public static SpeedCurve defaults() {
    return new SpeedCurve(1.0, 1.2);
  }

  /**
   * 加速度（格/秒²）。
   *
   * @param speedBps 当前速度
   * @param phaseStartBps 这一段加速开始时的速度（发车为 0，驶过慢速边后为当时的速度）
   * @param targetBps 要加速到的速度（当前限速）
   */
  public double accelerationBps2(double speedBps, double phaseStartBps, double targetBps) {
    return accelBps2 * shape(speedBps - phaseStartBps, targetBps - speedBps, accelBps2);
  }

  /**
   * 按时间推进一步后的速度：不超过上限，低于上限时按加速度提速；已高于上限直接落到上限。运行时发车动作每 tick 调用它。
   *
   * @param speedBps 当前速度
   * @param phaseStartBps 这一段加速开始时的速度
   * @param capBps 上限（目标速度与当前限速的较小者）
   * @param seconds 步长（秒）
   */
  public double nextSpeedBps(double speedBps, double phaseStartBps, double capBps, double seconds) {
    return nextSpeedBps(accelBps2, speedBps, phaseStartBps, capBps, seconds);
  }

  /**
   * 同 {@link #nextSpeedBps(double, double, double, double)}，只需满加速度：发车动作只加速，用不到减速度。
   *
   * @param accelBps2 满加速度（格/秒²），必须为正
   */
  public static double nextSpeedBps(
      double accelBps2, double speedBps, double phaseStartBps, double capBps, double seconds) {
    double cap = Math.max(0.0, capBps);
    if (speedBps >= cap) {
      return cap;
    }
    double accel = accelBps2 * shape(speedBps - phaseStartBps, cap - speedBps, accelBps2);
    return Math.min(cap, speedBps + accel * seconds);
  }

  /**
   * 减速度（格/秒²）。
   *
   * @param speedBps 当前速度
   * @param cruiseBps 制动开始前的巡航速度（所在区段的限速）
   * @param endBps 要减到的速度
   */
  public double decelerationBps2(double speedBps, double cruiseBps, double endBps) {
    return decelBps2 * shape(cruiseBps - speedBps, speedBps - endBps, decelBps2);
  }

  /**
   * 两端柔和的比例：离起点的速度差越小越接近 {@link #START_FLOOR}，离终点的速度差越小越接近 {@link #END_FLOOR}。
   *
   * <p>过渡带宽度由时长换算。起步段 {@code dv/dt = full·(floor + (1 − floor)·Δv/带宽)}，走完用时 {@code 带宽·ln(1/floor) /
   * (full·(1 − floor))}；收尾段先按比例线性收到 {@link #END_FLOOR}、再以它走完余下的速度差，用时 {@code 带宽·(ln(1/floor) + 1) /
   * full}。
   */
  private static double shape(double sinceStartBps, double untilEndBps, double full) {
    double startBand = START_SECONDS * full * (1.0 - START_FLOOR) / Math.log(1.0 / START_FLOOR);
    double endBand = END_SECONDS * full / (Math.log(1.0 / END_FLOOR) + 1.0);
    double rise = START_FLOOR + (1.0 - START_FLOOR) * clamp01(sinceStartBps / startBand);
    double fall = Math.max(END_FLOOR, clamp01(untilEndBps / endBand));
    return Math.min(rise, fall);
  }

  private static double clamp01(double value) {
    if (!(value > 0.0)) {
      return 0.0;
    }
    return Math.min(1.0, value);
  }
}
