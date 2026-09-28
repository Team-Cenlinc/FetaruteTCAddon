package org.fetarute.fetaruteTCAddon.dispatcher.eta.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** S 形加减速曲线：两端比例、中段满值，过渡带按时长换算。 */
class SpeedCurveTest {

  private static final SpeedCurve METRO = new SpeedCurve(1.1, 1.2);

  @Test
  void accelerationStartsSoftReachesFullAndTapersBeforeTheTarget() {
    assertEquals(SpeedCurve.START_FLOOR * 1.1, METRO.accelerationBps2(0.0, 0.0, 22.2), 1e-12, "起步");
    assertEquals(1.1, METRO.accelerationBps2(10.0, 0.0, 22.2), 1e-12, "中段满加速度");
    assertEquals(SpeedCurve.END_FLOOR * 1.1, METRO.accelerationBps2(22.2, 0.0, 22.2), 1e-12, "到速");
  }

  @Test
  void decelerationStartsSoftFromCruiseAndTapersBeforeTheEndSpeed() {
    assertEquals(
        SpeedCurve.START_FLOOR * 1.2, METRO.decelerationBps2(22.2, 22.2, 10.0), 1e-12, "开始制动");
    assertEquals(1.2, METRO.decelerationBps2(16.0, 22.2, 10.0), 1e-12, "中段常用制动");
    assertEquals(
        SpeedCurve.END_FLOOR * 1.2, METRO.decelerationBps2(10.0, 22.2, 10.0), 1e-12, "减到末速度");
  }

  /**
   * 起步段按比例从 START_FLOOR 升到 1，用时恰为 START_SECONDS；收尾段先线性收到 END_FLOOR 再贴上目标，用时恰为 END_SECONDS。 所以 0→V
   * 的用时是两段时长加中段 {@code (V − 两段带宽) / a}，与加速度大小无关地比恒加速度多约 2.1 秒。
   */
  @Test
  void timeFromRestToTargetIsTheTwoTransitionsPlusTheFullSegment() {
    for (double accel : new double[] {0.35, 0.9, 1.1}) {
      SpeedCurve curve = new SpeedCurve(accel, 1.0);
      double target = 22.2;
      double seconds = integrateSeconds(curve, target);
      double startBand =
          SpeedCurve.START_SECONDS
              * accel
              * (1.0 - SpeedCurve.START_FLOOR)
              / Math.log(1.0 / SpeedCurve.START_FLOOR);
      double endBand =
          SpeedCurve.END_SECONDS * accel / (Math.log(1.0 / SpeedCurve.END_FLOOR) + 1.0);
      double expected =
          SpeedCurve.START_SECONDS
              + SpeedCurve.END_SECONDS
              + (target - startBand - endBand) / accel;
      assertEquals(expected, seconds, 0.01, "accel=" + accel);
      assertEquals(2.08, expected - target / accel, 0.01, "比恒加速度多出的秒数");
    }
  }

  @Test
  void rejectsNonPositiveRates() {
    assertThrows(IllegalArgumentException.class, () -> new SpeedCurve(0.0, 1.0));
    assertThrows(IllegalArgumentException.class, () -> new SpeedCurve(1.0, -1.0));
    assertThrows(IllegalArgumentException.class, () -> new SpeedCurve(Double.NaN, 1.0));
  }

  /** 按时间细步积分 0 → target，速度到 target 为止。 */
  private static double integrateSeconds(SpeedCurve curve, double target) {
    double dt = 1.0e-4;
    double speed = 0.0;
    double seconds = 0.0;
    while (speed < target - 1e-9) {
      speed = Math.min(target, speed + curve.accelerationBps2(speed, 0.0, target) * dt);
      seconds += dt;
    }
    return seconds;
  }
}
