package org.fetarute.fetaruteTCAddon.dispatcher.eta.model;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 运行曲线：在速度天花板下按 S 形加速曲线正向推算。
 *
 * <p>匀速用例有解析解；起步、制动的用时与按时间细步积分同一条曲线的结果对照（{@link SpeedCurveTest} 已验证曲线本身的用时）。
 */
class RunCurveTest {

  private static final double EPS = 1e-6;
  private static final double FREE = Double.POSITIVE_INFINITY;
  private static final SpeedCurve CURVE = new SpeedCurve(1.0, 1.0);

  /** 以限速进入、以限速离开：时分就是长度 ÷ 限速。 */
  @Test
  void cruisingAtTheLimitIsLengthOverSpeed() {
    double[] times =
        RunCurve.nodeTimes(
            new double[] {100, 50}, new double[] {10.0, 10.0}, List.of(), 10.0, FREE, CURVE);

    assertArrayEquals(new double[] {0.0, 10.0, 15.0}, times, EPS);
  }

  /** 从静止起步走 1000 格：用时与按时间积分同一条加速曲线、再匀速走完余下里程的结果一致。 */
  @Test
  void startingFromRestFollowsTheSCurve() {
    double[] times =
        RunCurve.nodeTimes(new double[] {1000}, new double[] {10.0}, List.of(), 0.0, FREE, CURVE);

    double[] accel = accelerateInTime(CURVE, 10.0);
    double expected = accel[0] + (1000.0 - accel[1]) / 10.0;
    assertEquals(expected, times[1], 0.02);
    assertTrue(times[1] > 1000.0 / 10.0 + 10.0 / 2.0, "比恒加速度起步慢");
  }

  /** 起步柔和：头 0.25 格的平均加速度远低于满值。按时间积分同一条曲线，走完 0.25 格时速度约 0.53（平均加速度约 0.56）， 中点法应与之吻合。 */
  @Test
  void accelerationStartsSoftly() {
    SpeedCeiling ceiling =
        SpeedCeiling.of(new double[] {100}, new double[] {10.0}, List.of(), FREE, CURVE);
    double[] speed = RunCurve.profile(ceiling, 0.0, CURVE).speed();

    double firstStepAccel = speed[1] * speed[1] / (2.0 * SpeedCeiling.STEP_BLOCKS);
    assertTrue(
        firstStepAccel > SpeedCurve.START_FLOOR && firstStepAccel < 0.6, "" + firstStepAccel);
    assertEquals(0.53, speed[1], 0.03);
  }

  /** 终点停稳：天花板在终点降到 0，速度逐点不超过天花板，到达时刻有限且递增。 */
  @Test
  void stoppingAtTheEndStaysUnderTheCeiling() {
    SpeedCeiling ceiling =
        SpeedCeiling.of(new double[] {200}, new double[] {10.0}, List.of(), 0.0, CURVE);
    double[] speed = RunCurve.profile(ceiling, 0.0, CURVE).speed();

    for (int i = 0; i < speed.length; i++) {
      assertTrue(speed[i] <= ceiling.speedAt(i) + 1e-12, "第 " + i + " 点超过天花板");
    }
    assertEquals(0.0, speed[speed.length - 1], 1e-12);
    double[] times =
        RunCurve.nodeTimes(new double[] {200}, new double[] {10.0}, List.of(), 0.0, 0.0, CURVE);
    assertTrue(Double.isFinite(times[1]) && times[1] > 20.0);
  }

  /** 刹停到 0 的最后一格按匀减速（END_FLOOR 倍）走完：耗时应等于 {@code 2Δs / v}；按速度线性插值天花板时会高估近一半。 */
  @Test
  void lastStepToStandstillMatchesConstantDeceleration() {
    SpeedCeiling ceiling =
        SpeedCeiling.of(new double[] {100}, new double[] {10.0}, List.of(), 0.0, CURVE);
    RunCurve.Motion motion = RunCurve.profile(ceiling, 0.0, CURVE);
    int n = motion.speed().length;

    double lastStep = motion.seconds()[n - 1] - motion.seconds()[n - 2];
    double exact = 2.0 * SpeedCeiling.STEP_BLOCKS / motion.speed()[n - 2];
    assertEquals(exact, lastStep, 0.01 * exact);
  }

  /** 列车带速进入（ETA 从运行中位置起算）时，这一段加速按从静止起步算：起步段早已走过，按满加速度接着提速， 与控车发车动作的同一段加速一致，不重新柔和起步。 */
  @Test
  void movingEntryContinuesTheCurrentAccelerationPhase() {
    SpeedCeiling ceiling =
        SpeedCeiling.of(new double[] {400}, new double[] {20.0}, List.of(), FREE, CURVE);
    double[] speed = RunCurve.profile(ceiling, 5.0, CURVE).speed();

    double accel = (speed[1] * speed[1] - 25.0) / (2.0 * SpeedCeiling.STEP_BLOCKS);
    assertEquals(1.0, accel, 1e-9, "5 格/秒已过起步段，应按满加速度提速");
  }

  /** 驶过慢速边后重新提速是新的一段加速：离开慢速边的第一步同样处在起步段，加速度远低于满值。 */
  @Test
  void reaccelerationAfterASlowEdgeStartsSoftlyAgain() {
    SpeedCeiling ceiling =
        SpeedCeiling.of(
            new double[] {100, 50, 300}, new double[] {10.0, 5.0, 10.0}, List.of(), FREE, CURVE);
    double[] speed = RunCurve.profile(ceiling, 10.0, CURVE).speed();

    int leave = 150 * SpeedCeiling.STEPS_PER_BLOCK;
    assertEquals(5.0, speed[leave], 1e-12, "慢速边末端仍是 5");
    double accel =
        (speed[leave + 1] * speed[leave + 1] - speed[leave] * speed[leave])
            / (2.0 * SpeedCeiling.STEP_BLOCKS);
    assertTrue(accel > SpeedCurve.START_FLOOR && accel < 0.5, "离开慢速边的第一步应仍是起步段，实际 " + accel);
  }

  /** 额外限速区与边限速一样：进区时已降到区内限速，区内不超过它。 */
  @Test
  void capsActLikeLowerLimits() {
    List<SpeedCeiling.Cap> caps = List.of(new SpeedCeiling.Cap(300.0, 350.0, 5.0));
    SpeedCeiling ceiling =
        SpeedCeiling.of(
            new double[] {300, 50, 300}, new double[] {10.0, 10.0, 10.0}, caps, FREE, CURVE);
    double[] speed = RunCurve.profile(ceiling, 10.0, CURVE).speed();

    for (int i = 300 * SpeedCeiling.STEPS_PER_BLOCK; i <= 350 * SpeedCeiling.STEPS_PER_BLOCK; i++) {
      assertTrue(speed[i] <= 5.0 + 1e-12);
    }
  }

  /** 同样输入永远给同样结果，且到达时刻严格递增。 */
  @Test
  void deterministicAndMonotonic() {
    double[] lengths = {37, 81, 5, 120};
    double[] speeds = {16.7, 22.2, 8.0, 22.2};
    SpeedCurve curve = new SpeedCurve(0.8, 1.0);
    double[] first = RunCurve.nodeTimes(lengths, speeds, List.of(), 0.0, 10.0, curve);
    double[] second = RunCurve.nodeTimes(lengths, speeds, List.of(), 0.0, 10.0, curve);

    assertArrayEquals(first, second, 0.0);
    for (int i = 1; i < first.length; i++) {
      assertTrue(first[i] > first[i - 1], "第 " + i + " 个节点的时刻必须晚于前一个");
    }
  }

  /** 没有边时只有起点，时刻为 0。 */
  @Test
  void emptyPathHasOnlyTheStartNode() {
    assertArrayEquals(
        new double[] {0.0},
        RunCurve.nodeTimes(new double[0], new double[0], List.of(), 0.0, FREE, CURVE),
        EPS);
  }

  /** 长度或限速不为正是调用方的错误，直接拒绝。 */
  @Test
  void rejectsNonPositiveInputs() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            RunCurve.nodeTimes(new double[] {0}, new double[] {10.0}, List.of(), 0.0, FREE, CURVE));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            RunCurve.nodeTimes(new double[] {10}, new double[] {0.0}, List.of(), 0.0, FREE, CURVE));
  }

  /** 按时间细步积分 0 → target：返回 {秒数, 走过的格数}。 */
  private static double[] accelerateInTime(SpeedCurve curve, double target) {
    double dt = 1.0e-4;
    double speed = 0.0;
    double seconds = 0.0;
    double blocks = 0.0;
    while (speed < target - 1e-9) {
      double next = Math.min(target, speed + curve.accelerationBps2(speed, 0.0, target) * dt);
      blocks += (speed + next) / 2.0 * dt;
      speed = next;
      seconds += dt;
    }
    return new double[] {seconds, blocks};
  }
}
