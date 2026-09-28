package org.fetarute.fetaruteTCAddon.dispatcher.eta.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 控车与编表是同一条速度曲线。
 *
 * <p>按控车的规则与节拍逐 tick 模拟，到站时刻应与编表运行曲线（{@link RunCurve}）一致：
 *
 * <ul>
 *   <li>限速跟随同一个速度天花板（逐 tick 斜坡）；
 *   <li>提速按发车动作的逐 tick 规则（{@link SpeedCurve#nextSpeedBps}，发车动作内部即调用它），动作目标取下发那一刻的天花板， 贴住限速即结束；
 *   <li>动作结束后，限速回升要等下一个调度周期（{@value #CYCLE_TICKS} tick），且高出车速 1%（至少 {@value #RESUME_EPSILON_BPT}
 *       格/tick）才重新下发，作为新的一段加速——与 {@code TrainLaunchManager#shouldResumeTraction} 的门槛一致。
 * </ul>
 *
 * <p>剩余偏差来自调度节拍：驶出慢速边后最多晚一个周期才重新加速，每次约 0.1 秒。
 */
class ControlCurveAlignmentTest {

  private static final SpeedCurve METRO = new SpeedCurve(1.1, 1.2);

  /** 调度周期（tick），实服每秒一次。 */
  private static final int CYCLE_TICKS = 20;

  /** 补牵引门槛：目标比车速高出的比例，同 {@code TrainLaunchManager.RESUME_TRACTION_TOLERANCE_RATIO}。 */
  private static final double RESUME_RATIO = 0.01;

  /** 补牵引门槛的下限（blocks/tick），同 {@code TrainLaunchManager.MOVING_CONTROL_EPSILON_BPT}。 */
  private static final double RESUME_EPSILON_BPT = 0.005;

  /** 600 格 @20，末端 90 格进站限速区 @10，到站速度 10。 */
  @Test
  void stationToStation() {
    assertAligned(
        new double[] {510, 90},
        new double[] {20.0, 20.0},
        List.of(new SpeedCeiling.Cap(510.0, 600.0, 10.0)),
        10.0);
  }

  /** 中途 50 格慢速边 @8：先刹进去、驶出后等下一个调度周期重新起步。 */
  @Test
  void slowEdgeInTheMiddle() {
    assertAligned(
        new double[] {300, 50, 250, 90},
        new double[] {22.2, 8.0, 22.2, 22.2},
        List.of(new SpeedCeiling.Cap(600.0, 690.0, 10.0)),
        10.0);
  }

  /** 站距很短，加速还没到线路速度就要开始制动。 */
  @Test
  void shortSpacingNeverReachesLineSpeed() {
    assertAligned(
        new double[] {90, 40},
        new double[] {22.2, 22.2},
        List.of(new SpeedCeiling.Cap(90.0, 130.0, 10.0)),
        10.0);
  }

  /** 限速小幅回升（8.0→8.33，4%）：补牵引门槛若是 5%，控车会一直按 8.0 跑完，比编表慢约 1 秒。 */
  @Test
  void smallSpeedLimitRiseIsFollowed() {
    assertAligned(
        new double[] {100, 200, 90},
        new double[] {8.0, 8.33, 8.33},
        List.of(new SpeedCeiling.Cap(300.0, 390.0, 6.0)),
        6.0);
  }

  private static void assertAligned(
      double[] lengths, double[] speeds, List<SpeedCeiling.Cap> caps, double exitSpeed) {
    double model = RunCurve.nodeTimes(lengths, speeds, caps, 0.0, exitSpeed, METRO)[lengths.length];
    SpeedCeiling ceiling = SpeedCeiling.of(lengths, speeds, caps, exitSpeed, METRO);
    double total = 0.0;
    for (double length : lengths) {
      total += length;
    }
    double control = controlArrivalSeconds(ceiling, total);
    assertTrue(model > 0.0);
    assertEquals(model, control, 0.3, "控车 " + control + " 秒，编表 " + model + " 秒");
  }

  /** 按控车规则与节拍逐 tick 模拟走完 {@code total} 格的秒数（最后一 tick 按比例插值）。 */
  private static double controlArrivalSeconds(SpeedCeiling ceiling, double total) {
    double tick = 1.0 / 20.0;
    double velocityBpt = 0.0;
    double phaseStartBps = 0.0;
    double targetBpt = ceiling.limitBps(0.0) / 20.0;
    boolean launching = true;
    double traveled = 0.0;
    double seconds = 0.0;
    for (int n = 0; ; n++) {
      double capBpt = ceiling.limitBps(traveled) / 20.0;
      if (!launching
          && n % CYCLE_TICKS == 0
          && capBpt > velocityBpt + Math.max(RESUME_EPSILON_BPT, capBpt * RESUME_RATIO)) {
        launching = true;
        phaseStartBps = velocityBpt * 20.0;
        targetBpt = capBpt;
      }
      if (launching) {
        double limitBpt = Math.min(targetBpt, capBpt);
        velocityBpt =
            SpeedCurve.nextSpeedBps(
                    METRO.accelBps2(), velocityBpt * 20.0, phaseStartBps, limitBpt * 20.0, tick)
                / 20.0;
        launching = velocityBpt < limitBpt - 1e-6;
      } else {
        velocityBpt = Math.min(velocityBpt, capBpt);
      }
      if (traveled + velocityBpt >= total) {
        return seconds + tick * (total - traveled) / velocityBpt;
      }
      traveled += velocityBpt;
      seconds += tick;
    }
  }
}
