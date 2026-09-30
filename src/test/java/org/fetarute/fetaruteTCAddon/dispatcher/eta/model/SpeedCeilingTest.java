package org.fetarute.fetaruteTCAddon.dispatcher.eta.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** 速度天花板：逐点限速按 S 形制动曲线往回推，编表与控车共用。 */
class SpeedCeilingTest {

  private static final double FREE = Double.POSITIVE_INFINITY;
  private static final SpeedCurve CURVE = new SpeedCurve(1.0, 1.0);

  /** 运行时前瞻用的单点制动与编表天花板是同一条曲线：等于一条单边天花板在起点的值。 */
  @Test
  void brakingLimitMatchesASingleEdgeCeiling() {
    for (double distance : new double[] {6.0, 26.0, 50.25, 120.0, 400.0}) {
      SpeedCeiling ceiling =
          SpeedCeiling.of(new double[] {distance}, new double[] {22.2}, List.of(), 8.0, CURVE);
      assertEquals(
          ceiling.limitBps(0.0),
          SpeedCeiling.brakingLimitBps(CURVE, 22.2, 8.0, distance),
          1.0e-9,
          "距离 " + distance);
    }
    assertEquals(22.2, SpeedCeiling.brakingLimitBps(CURVE, 22.2, 8.0, 5000.0), 1.0e-9, "远处不收紧");
    assertEquals(8.0, SpeedCeiling.brakingLimitBps(CURVE, 22.2, 8.0, 0.0), 1.0e-9);
    assertEquals(30.0, SpeedCeiling.brakingLimitBps(CURVE, 22.2, 30.0, 10.0), 1.0e-9, "不低于巡航速度");
    double halfStep = SpeedCeiling.brakingLimitBps(CURVE, 22.2, 8.0, 26.125);
    assertTrue(
        halfStep > SpeedCeiling.brakingLimitBps(CURVE, 22.2, 8.0, 26.0)
            && halfStep < SpeedCeiling.brakingLimitBps(CURVE, 22.2, 8.0, 26.25),
        "步长之间插值");
  }

  @Test
  void neverExceedsTheLimitsAndReachesEachLowerLimitInTime() {
    // 300 格 @20 → 100 格 @8 → 200 格 @20，末端进站限速区 [520, 600] 限 10。
    SpeedCeiling ceiling =
        SpeedCeiling.of(
            new double[] {300, 100, 200},
            new double[] {20.0, 8.0, 20.0},
            List.of(new SpeedCeiling.Cap(520.0, 600.0, 10.0)),
            10.0,
            CURVE);

    for (int i = 0; i < ceiling.samples(); i++) {
      double blocks = (double) i / SpeedCeiling.STEPS_PER_BLOCK;
      double limit = blocks < 300 ? 20.0 : blocks <= 400 ? 8.0 : blocks < 520 ? 20.0 : 10.0;
      assertTrue(ceiling.speedAt(i) <= limit + 1e-9, "里程 " + blocks);
    }
    assertEquals(8.0, ceiling.limitBps(300.0), 1e-9, "进慢速边时已降到它的限速");
    assertEquals(10.0, ceiling.limitBps(520.0), 1e-9, "进站限速区起点已降到进站限速");
    assertEquals(20.0, ceiling.limitBps(0.0), 1e-9, "远处不受影响");
  }

  /**
   * 制动两端柔和：刚离开巡航速度时减速度只有 START_FLOOR 倍，贴近末速度时只有 END_FLOOR 倍。从天花板相邻两点反推减速度 {@code (v₁² − v₂²) /
   * (2Δs)}。
   */
  @Test
  void brakingIsSoftAtBothEnds() {
    SpeedCeiling ceiling =
        SpeedCeiling.of(new double[] {500, 100}, new double[] {20.0, 10.0}, List.of(), FREE, CURVE);

    int firstBelowCruise = -1;
    for (int i = 0; i < ceiling.samples(); i++) {
      if (ceiling.speedAt(i) < 20.0 - 1e-9) {
        firstBelowCruise = i;
        break;
      }
    }
    int zoneStart = 500 * SpeedCeiling.STEPS_PER_BLOCK;
    double onset = decel(ceiling, firstBelowCruise);
    double arrival = decel(ceiling, zoneStart - 1);
    double middle = decel(ceiling, (firstBelowCruise + zoneStart) / 2);

    assertEquals(SpeedCurve.START_FLOOR, onset, 0.05, "开始制动时的减速度比例");
    assertEquals(SpeedCurve.END_FLOOR, arrival, 0.02, "降到末速度前的减速度比例");
    assertEquals(1.0, middle, 1e-6, "中段常用制动");
  }

  /** 每一点记下受的是哪类约束：慢速边前的制动段归边限速，进站限速区前的制动段与区内归进站。 */
  @Test
  void remembersWhetherTheBindingConstraintIsACapOrAnEdge() {
    SpeedCeiling ceiling =
        SpeedCeiling.of(
            new double[] {300, 100, 200},
            new double[] {20.0, 8.0, 20.0},
            List.of(new SpeedCeiling.Cap(520.0, 600.0, 10.0)),
            10.0,
            CURVE);

    assertTrue(ceiling.limitBps(290.0) < 20.0, "290 格处应在慢速边的制动段内");
    assertEquals(false, ceiling.capBoundAt(290.0), "慢速边前的制动归边限速");
    assertTrue(ceiling.limitBps(510.0) < 20.0, "510 格处应在进站限速区的制动段内");
    assertEquals(true, ceiling.capBoundAt(510.0), "进站限速区前的制动归进站");
    assertEquals(true, ceiling.capBoundAt(560.0), "区内归进站");
    assertEquals(false, ceiling.capBoundAt(0.0), "远处按线路速度，归边限速");
  }

  @Test
  void withoutACurveItIsJustThePointwiseLimit() {
    SpeedCeiling ceiling =
        SpeedCeiling.of(new double[] {100, 100}, new double[] {20.0, 8.0}, List.of(), FREE, null);

    assertEquals(20.0, ceiling.limitBps(99.0), 1e-12);
    assertEquals(8.0, ceiling.limitBps(100.0), 1e-12);
  }

  @Test
  void interpolatesBetweenSamplesAndHoldsTheEndBeyondIt() {
    SpeedCeiling ceiling =
        SpeedCeiling.of(new double[] {100}, new double[] {20.0}, List.of(), 0.0, CURVE);

    double a = ceiling.speedAt(40);
    double b = ceiling.speedAt(41);
    assertEquals(Math.sqrt((a * a + b * b) / 2.0), ceiling.limitBps(10.125), 1e-12);
    assertEquals(0.0, ceiling.limitBps(1000.0), 1e-12);
  }

  @Test
  void rejectsInvalidInputs() {
    assertThrows(
        IllegalArgumentException.class,
        () -> SpeedCeiling.of(new double[] {0}, new double[] {10.0}, List.of(), FREE, CURVE));
    assertThrows(
        IllegalArgumentException.class,
        () -> SpeedCeiling.of(new double[] {10}, new double[] {0.0}, List.of(), FREE, CURVE));
    assertThrows(IllegalArgumentException.class, () -> new SpeedCeiling.Cap(0.0, 10.0, 0.0));
  }

  /** 第 i 与 i+1 点之间的减速度，按满减速度归一。 */
  private static double decel(SpeedCeiling ceiling, int i) {
    double v1 = ceiling.speedAt(i);
    double v2 = ceiling.speedAt(i + 1);
    return (v1 * v1 - v2 * v2) / (2.0 * SpeedCeiling.STEP_BLOCKS) / CURVE.decelBps2();
  }
}
