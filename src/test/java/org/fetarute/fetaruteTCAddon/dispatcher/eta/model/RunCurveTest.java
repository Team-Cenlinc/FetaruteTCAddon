package org.fetarute.fetaruteTCAddon.dispatcher.eta.model;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

/** 运行曲线的每个用例都有解析解：离散步长落在整数格上，纯加速、纯制动、匀速三种步子都是精确的， 所以误差只来自浮点。 */
class RunCurveTest {

  private static final double EPS = 1e-6;
  private static final double FREE = Double.POSITIVE_INFINITY;

  /** 以限速进入、以限速离开：时分就是长度 ÷ 限速，与旧的逐边口径一致。 */
  @Test
  void cruisingAtTheLimitIsLengthOverSpeed() {
    double[] times =
        RunCurve.nodeTimes(
            new double[] {100, 50}, new double[] {10.0, 10.0}, List.of(), 10.0, FREE, 1.0, 1.0);

    assertArrayEquals(new double[] {0.0, 10.0, 15.0}, times, EPS);
  }

  /** 从静止起步：加速度 1 时 10 秒加到 10 格/秒、走过 50 格，剩下 50 格匀速 5 秒。中间节点在 50 格处，正好 10 秒。 */
  @Test
  void startingFromRestAddsTheAccelerationPhase() {
    double[] times =
        RunCurve.nodeTimes(
            new double[] {50, 50}, new double[] {10.0, 10.0}, List.of(), 0.0, FREE, 1.0, 1.0);

    assertArrayEquals(new double[] {0.0, 10.0, 15.0}, times, EPS);
  }

  /** 终点停稳：100 格、限速 10，加速 50 格、制动 50 格，20 秒。 */
  @Test
  void stoppingAtTheEndAddsTheBrakingPhase() {
    double[] times =
        RunCurve.nodeTimes(new double[] {100}, new double[] {10.0}, List.of(), 0.0, 0.0, 1.0, 1.0);

    assertEquals(20.0, times[1], EPS);
  }

  /**
   * 下一条边限速更低时在进边之前制动：200 格 @20 后接 100 格 @10，从静止起步。 加速曲线 v² = 2s 与制动曲线 v² = 100 + 2(200 − s) 交于 s =
   * 125（v = √250），加速 √250 秒、制动 √250 − 10 秒，之后 100 格按 10 走 10 秒。
   */
  @Test
  void brakesBeforeALowerLimitAhead() {
    double[] times =
        RunCurve.nodeTimes(
            new double[] {200, 100}, new double[] {20.0, 10.0}, List.of(), 0.0, FREE, 1.0, 1.0);

    double peak = Math.sqrt(250.0);
    assertEquals(peak + (peak - 10.0), times[1], EPS);
    assertEquals(peak + (peak - 10.0) + 10.0, times[2], EPS);
  }

  /** 额外限速区与边限速一样在进区之前刹到位：中段 [100, 150] 限 5，其余限 10，以 10 进入、以 10 离开。 */
  @Test
  void capsActLikeLowerLimits() {
    List<RunCurve.Cap> caps = List.of(new RunCurve.Cap(100.0, 150.0, 5.0));
    double[] times =
        RunCurve.nodeTimes(
            new double[] {100, 50, 50},
            new double[] {10.0, 10.0, 10.0},
            caps,
            10.0,
            FREE,
            1.0,
            1.0);

    // 进区前从 10 刹到 5 要 37.5 格（5 秒），其余 62.5 格匀速；区内 50 格按 5 走 10 秒；出区再加速回 10 要 37.5 格（5 秒）。
    assertEquals(62.5 / 10.0 + 5.0, times[1], EPS);
    assertEquals(62.5 / 10.0 + 5.0 + 10.0, times[2], EPS);
    assertEquals(62.5 / 10.0 + 5.0 + 10.0 + 5.0 + 12.5 / 10.0, times[3], EPS);
  }

  /** 同样输入永远给同样结果，且到达时刻严格递增。 */
  @Test
  void deterministicAndMonotonic() {
    double[] lengths = {37, 81, 5, 120};
    double[] speeds = {16.7, 22.2, 8.0, 22.2};
    double[] first = RunCurve.nodeTimes(lengths, speeds, List.of(), 0.0, 10.0, 0.8, 1.0);
    double[] second = RunCurve.nodeTimes(lengths, speeds, List.of(), 0.0, 10.0, 0.8, 1.0);

    assertArrayEquals(first, second, 0.0);
    for (int i = 1; i < first.length; i++) {
      assertEquals(true, first[i] > first[i - 1], "第 " + i + " 个节点的时刻必须晚于前一个");
    }
  }

  /** 没有边时只有起点，时刻为 0。 */
  @Test
  void emptyPathHasOnlyTheStartNode() {
    assertArrayEquals(
        new double[] {0.0},
        RunCurve.nodeTimes(new double[0], new double[0], List.of(), 0.0, FREE, 1.0, 1.0),
        EPS);
  }

  /** 长度或限速不为正、加减速不为正都是调用方的错误，直接拒绝。 */
  @Test
  void rejectsNonPositiveInputs() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            RunCurve.nodeTimes(
                new double[] {0}, new double[] {10.0}, List.of(), 0.0, FREE, 1.0, 1.0));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            RunCurve.nodeTimes(
                new double[] {10}, new double[] {0.0}, List.of(), 0.0, FREE, 1.0, 1.0));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            RunCurve.nodeTimes(
                new double[] {10}, new double[] {10.0}, List.of(), 0.0, FREE, 0.0, 1.0));
    assertThrows(IllegalArgumentException.class, () -> new RunCurve.Cap(0.0, 10.0, 0.0));
  }
}
