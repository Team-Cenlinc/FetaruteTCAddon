package org.fetarute.fetaruteTCAddon.dispatcher.eta.model;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.SignalLookahead;
import org.fetarute.fetaruteTCAddon.drive.DriveConfig;
import org.fetarute.fetaruteTCAddon.drive.driver.DriverGuidance;
import org.fetarute.fetaruteTCAddon.drive.driver.DriverGuidanceConfig;
import org.fetarute.fetaruteTCAddon.drive.dynamics.DriveDynamics;
import org.fetarute.fetaruteTCAddon.drive.dynamics.DriveMode;
import org.fetarute.fetaruteTCAddon.drive.dynamics.DriveParams;
import org.fetarute.fetaruteTCAddon.drive.dynamics.Notch;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 照着建议速度开能不能达到时刻表：用手动驾驶的动力学（档位、力的爬升速率、惰行阻力）和一个照 Boss 栏操作、有反应迟滞的驾驶员， 逐 tick
 * 开完一段，与编表运行曲线同一段的用时比较。编表另有每站的对位与开门开销（{@code station-stop-overhead-seconds}，默认 4 秒）， 这里不计。
 *
 * <p>车种取 metro 预设（加速度 1.1、减速度 1.2 格/秒²），线路速度 80 km/h、进站限速 10 格/秒，与实服配置一致。驾驶员看到“开始制动”拉到 B4，低于建议速度就松到
 * B1；低于建议速度较多时 P3，略低时 P1，贴着就惰行。
 */
@DisplayName("照建议速度开能达到编表")
class ManualDrivingAchievabilityTest {

  private static final double ACCEL = 1.1;
  private static final double DECEL = 1.2;
  private static final double LINE = 80.0 / 3.6;
  private static final double SLOW = 40.0 / 3.6;
  private static final double APPROACH = 10.0;
  private static final double DT = 0.05;

  /** 停在停车点前后这么近算停准（格）。 */
  private static final double STOP_WINDOW = 1.0;

  /** simulation 级：电制动退出速度，低于它只剩空气制动，最多发挥常用全制动的 0.85。 */
  private static final double AIR_ONLY_BELOW = 15.0 / 3.6;

  private static final double AIR_ONLY_FRACTION = 0.85;

  private static final SpeedCurve CURVE = new SpeedCurve(ACCEL, DECEL);

  /** 一段线路：编表的边与进站限速区，驾驶员看到的分段限速与停车点。 */
  private record Route(
      double[] lengths, double[] speeds, List<SpeedCeiling.Cap> caps, List<Section> sections) {

    double stopAt() {
      double total = 0.0;
      for (double length : lengths) {
        total += length;
      }
      return total;
    }

    double timetableSeconds() {
      return RunCurve.nodeTimes(lengths, speeds, caps, 0.0, 0.0, CURVE)[lengths.length];
    }
  }

  /** 从 {@code fromBlocks} 起限速 {@code speedBps}。 */
  private record Section(double fromBlocks, double speedBps) {}

  /** 车辆特性：恒功率拐点（0 表示没有）、低速是否只剩空气制动。 */
  private record Vehicle(double kneeBps, boolean airOnlyTail) {}

  private static final List<Route> ROUTES =
      List.of(
          // 起步、80 km/h 巡航、进站停车。
          new Route(
              new double[] {740.0, 60.0},
              new double[] {LINE, LINE},
              List.of(new SpeedCeiling.Cap(740.0, 800.0, APPROACH)),
              List.of(new Section(0.0, LINE))),
          // 途中一段 40 km/h 慢行，再进站停车。
          new Route(
              new double[] {400.0, 200.0, 240.0, 60.0},
              new double[] {LINE, SLOW, LINE, LINE},
              List.of(new SpeedCeiling.Cap(840.0, 900.0, APPROACH)),
              List.of(new Section(0.0, LINE), new Section(400.0, SLOW), new Section(600.0, LINE))),
          // 短站间：还没到线路速度就要进站。
          new Route(
              new double[] {200.0, 60.0},
              new double[] {LINE, LINE},
              List.of(new SpeedCeiling.Cap(200.0, 260.0, APPROACH)),
              List.of(new Section(0.0, LINE))));

  @ParameterizedTest(name = "反应 {0} tick")
  @ValueSource(ints = {4, 6, 10})
  @DisplayName("standard 级：反应 0.2–0.5 秒都能停准，且不比编表慢")
  void standardLevelMakesTheTimetable(int reactionTicks) {
    for (Route route : ROUTES) {
      double manual = drive(route, new Vehicle(0.0, false), reactionTicks);
      double timetable = route.timetableSeconds();
      assertTrue(
          manual <= timetable,
          "人工 " + manual + " 秒，编表 " + timetable + " 秒（" + route.stopAt() + " 格）");
    }
  }

  @Test
  @DisplayName("simulation 级恒功率与低速纯空气制动：多出的不到 1 秒，由每站的对位开销吸收")
  void simulationLevelStaysWithinTheStopOverhead() {
    for (Vehicle vehicle : List.of(new Vehicle(40.0 / 3.6, true), new Vehicle(50.0 / 3.6, true))) {
      for (Route route : ROUTES) {
        double manual = drive(route, vehicle, 6);
        double timetable = route.timetableSeconds();
        assertTrue(
            manual <= timetable + 1.0,
            "人工 " + manual + " 秒，编表 " + timetable + " 秒（" + route.stopAt() + " 格，" + vehicle + "）");
      }
    }
  }

  @Test
  @DisplayName("反证：建议速度按满常用制动反推（与编表完全相同）时，人跟不住会冲标")
  void fullServiceBrakeCurveIsNotFollowable() {
    DriverGuidanceConfig full = new DriverGuidanceConfig(true, 400.0, 1.0, 0.3);
    boolean overran = false;
    for (Route route : ROUTES) {
      try {
        drive(route, new Vehicle(0.0, false), 6, full);
      } catch (AssertionError expected) {
        overran = true;
      }
    }
    assertTrue(overran, "按满常用制动反推也停准了：比例的余量可以收窄，重新评估默认值");
  }

  private static double drive(Route route, Vehicle vehicle, int reactionTicks) {
    return drive(route, vehicle, reactionTicks, DriverGuidanceConfig.defaults());
  }

  /** 从静止开到停车点停准，返回用时（秒）；十分钟内停不准（冲标后停住）时抛 {@link AssertionError}。 */
  private static double drive(
      Route route, Vehicle vehicle, int reactionTicks, DriverGuidanceConfig guidance) {
    DriveConfig config = DriveConfig.defaults();
    DriveDynamics dynamics =
        new DriveDynamics(new DriveParams(DriveMode.MU, ACCEL, DECEL, 30.0, 1.0), config);
    List<SignalLookahead.EdgeSpeedConstraint> edges = new ArrayList<>();
    for (Section section : route.sections()) {
      edges.add(
          new SignalLookahead.EdgeSpeedConstraint((long) section.fromBlocks(), section.speedBps()));
    }
    double stopAt = route.stopAt();
    double serviceDecel = DECEL * config.brakeFraction(Notch.B4);
    double reaction = 1.0 / (2.0 * config.effortRatePerSecond());
    Deque<Notch> pending = new ArrayDeque<>();
    for (int i = 0; i < reactionTicks; i++) {
      pending.add(Notch.N);
    }
    double position = 0.0;
    boolean advising = false;
    for (int tick = 1; tick < 20 * 600; tick++) {
      double speed = dynamics.speedBps();
      DriverGuidance.Advice advice =
          DriverGuidance.evaluate(
              new DriverGuidance.Input(
                  speed,
                  speed <= 0.0,
                  Double.POSITIVE_INFINITY,
                  speedAt(route.sections(), position),
                  Double.NaN,
                  stopAt - position,
                  edges,
                  position,
                  serviceDecel,
                  reaction,
                  config.driver().stopMarginBlocks(),
                  advising),
              guidance);
      advising = advice.brake();
      pending.add(choose(speed, advice, stopAt - position));
      Notch notch = pending.poll();
      double traction =
          vehicle.kneeBps() > 0.0 && speed > vehicle.kneeBps() ? vehicle.kneeBps() / speed : 1.0;
      boolean airOnly = vehicle.airOnlyTail() && speed < AIR_ONLY_BELOW;
      dynamics.step(
          DT,
          notch,
          Double.POSITIVE_INFINITY,
          traction,
          demand -> airOnly ? Math.min(1.0, AIR_ONLY_FRACTION / Math.max(demand, 1.0e-9)) : 1.0);
      position += dynamics.speedBps() * DT;
      if (dynamics.speedBps() <= 0.0 && Math.abs(stopAt - position) <= STOP_WINDOW) {
        return tick * DT;
      }
    }
    throw new AssertionError("十分钟内没有停准，停在 " + position + " 格（停车点 " + stopAt + "）");
  }

  /** 驾驶员照 Boss 栏操作。 */
  private static Notch choose(double speed, DriverGuidance.Advice advice, double remaining) {
    double over = speed - advice.suggestedBps();
    if (remaining < STOP_WINDOW && speed < 1.0) {
      return Notch.B4;
    }
    if (advice.brake() || over > 0.05) {
      if (over < 0.05 && !advice.brake()) {
        return Notch.B1;
      }
      return Notch.B4;
    }
    if (over < -0.6) {
      return Notch.P3;
    }
    return over < -0.15 ? Notch.P1 : Notch.N;
  }

  private static double speedAt(List<Section> sections, double position) {
    double speed = sections.get(0).speedBps();
    for (Section section : sections) {
      if (position >= section.fromBlocks()) {
        speed = section.speedBps();
      }
    }
    return speed;
  }
}
