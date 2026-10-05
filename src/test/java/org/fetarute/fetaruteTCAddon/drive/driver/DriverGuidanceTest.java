package org.fetarute.fetaruteTCAddon.drive.driver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.SignalLookahead;
import org.junit.jupiter.api.Test;

class DriverGuidanceTest {

  private static final DriverGuidanceConfig CONFIG = DriverGuidanceConfig.defaults();

  private static DriverGuidance.Input input(
      double speed,
      double permitted,
      double stopSignal,
      double station,
      List<SignalLookahead.EdgeSpeedConstraint> edges,
      double travelled,
      boolean wasAdvising) {
    return new DriverGuidance.Input(
        speed,
        speed <= 0.0,
        Double.POSITIVE_INFINITY,
        permitted,
        stopSignal,
        station,
        edges,
        travelled,
        1.0,
        0.5,
        1.0,
        wasAdvising);
  }

  @Test
  void nothingAheadIsClearAndSuggestsThePermittedSpeed() {
    DriverGuidance.Advice advice =
        DriverGuidance.evaluate(
            input(10.0, 20.0, Double.POSITIVE_INFINITY, Double.NaN, List.of(), 0.0, false), CONFIG);

    assertEquals(DriverGuidance.TargetKind.CLEAR, advice.target().kind());
    assertEquals(20.0, advice.suggestedBps(), 1.0e-9);
    assertFalse(advice.brake());
    assertEquals(1.0, advice.progress(CONFIG.rangeBlocks()), 1.0e-9);
  }

  @Test
  void requestedSpeedCapsTheSuggestion() {
    DriverGuidance.Input in =
        new DriverGuidance.Input(
            10.0,
            false,
            12.0,
            20.0,
            Double.POSITIVE_INFINITY,
            Double.NaN,
            List.of(),
            0.0,
            1.0,
            0.5,
            1.0,
            false);

    assertEquals(12.0, DriverGuidance.evaluate(in, CONFIG).suggestedBps(), 1.0e-9);
  }

  @Test
  void stopSignalAheadSuggestsTheTimetableBrakingCurveAndAsksToBrakeWhenFaster() {
    DriverGuidance.Advice advice =
        DriverGuidance.evaluate(
            input(15.0, 20.0, 100.0, Double.NaN, List.of(), 0.0, false), CONFIG);

    double expected =
        DriverGuidance.curveBps(99.0, 0.0, 20.0, CONFIG.adviceBrakeFraction(), 0.5, 15.0);
    assertEquals(DriverGuidance.TargetKind.STOP_SIGNAL, advice.target().kind());
    assertEquals(99.0, advice.target().distanceBlocks(), 1.0e-9);
    assertEquals(expected, advice.suggestedBps(), 1.0e-9);
    assertTrue(advice.brake());
    assertEquals(99.0 / CONFIG.rangeBlocks(), advice.progress(CONFIG.rangeBlocks()), 1.0e-9);
  }

  @Test
  void theNearerStopWins() {
    DriverGuidance.Advice advice =
        DriverGuidance.evaluate(input(10.0, 20.0, 300.0, 120.0, List.of(), 0.0, false), CONFIG);

    assertEquals(DriverGuidance.TargetKind.STATION, advice.target().kind());
    assertEquals(120.0, advice.target().distanceBlocks(), 1.0e-9);
  }

  @Test
  void lowerSpeedLimitAheadIsMeasuredFromTheCurrentPosition() {
    List<SignalLookahead.EdgeSpeedConstraint> edges =
        List.of(
            new SignalLookahead.EdgeSpeedConstraint(200L, 8.0),
            new SignalLookahead.EdgeSpeedConstraint(30L, 5.0),
            new SignalLookahead.EdgeSpeedConstraint(100L, 25.0));

    DriverGuidance.Advice advice =
        DriverGuidance.evaluate(
            input(12.0, 20.0, Double.POSITIVE_INFINITY, Double.NaN, edges, 50.0, false), CONFIG);

    // 30 格处的限速边已经驶入（体现在容许速度里），25 格/秒不低于容许速度，只剩 200 格处的那条。
    assertEquals(DriverGuidance.TargetKind.SPEED_LIMIT, advice.target().kind());
    assertEquals(150.0, advice.target().distanceBlocks(), 1.0e-9);
    assertEquals(8.0, advice.target().endSpeedBps(), 1.0e-9);
    assertEquals(
        DriverGuidance.curveBps(150.0, 8.0, 20.0, CONFIG.adviceBrakeFraction(), 0.5, 12.0),
        advice.suggestedBps(),
        1.0e-9);
  }

  @Test
  void targetsBeyondTheRangeAreIgnored() {
    DriverGuidance.Advice advice =
        DriverGuidance.evaluate(input(10.0, 20.0, 900.0, 800.0, List.of(), 0.0, false), CONFIG);

    assertEquals(DriverGuidance.TargetKind.CLEAR, advice.target().kind());
  }

  @Test
  void brakeAdviceHasHysteresis() {
    // 建议速度随车速变（扣掉制动力爬升期间走过的距离）：按“车速 ≈ 建议速度”求出那个点。
    double between = 10.0;
    for (int i = 0; i < 50; i++) {
      double suggested =
          DriverGuidance.curveBps(99.0, 0.0, 20.0, CONFIG.adviceBrakeFraction(), 0.5, between);
      between = suggested + CONFIG.brakeAdviceToleranceBps() / 2.0;
    }

    assertFalse(
        DriverGuidance.evaluate(
                input(between, 20.0, 100.0, Double.NaN, List.of(), 0.0, false), CONFIG)
            .brake());
    assertTrue(
        DriverGuidance.evaluate(
                input(between, 20.0, 100.0, Double.NaN, List.of(), 0.0, true), CONFIG)
            .brake());
  }

  @Test
  void noBrakeAdviceWhileTheCapIsWhatBinds() {
    // 目标很远，起作用的是此刻的容许速度：超速由防护管，不提示“开始制动”。
    DriverGuidance.Advice advice =
        DriverGuidance.evaluate(
            input(25.0, 20.0, 380.0, Double.NaN, List.of(), 0.0, false), CONFIG);

    assertEquals(DriverGuidance.TargetKind.STOP_SIGNAL, advice.target().kind());
    assertFalse(advice.brake());
  }

  @Test
  void stoppedTrainIsNeverAskedToBrake() {
    DriverGuidance.Advice advice =
        DriverGuidance.evaluate(input(0.0, 0.0, 0.5, Double.NaN, List.of(), 0.0, true), CONFIG);

    assertFalse(advice.brake());
    assertEquals(0.0, advice.suggestedBps(), 1.0e-9);
  }

  @Test
  void curveHoldsTheEndSpeedAtTheTarget() {
    assertEquals(8.0, DriverGuidance.curveBps(0.0, 8.0, 20.0, 0.8, 0.5, 10.0), 1.0e-9);
    assertEquals(
        Double.POSITIVE_INFINITY, DriverGuidance.curveBps(50.0, 0.0, 20.0, 0.0, 0.5, 10.0), 1.0e-9);
    assertEquals(
        20.0, DriverGuidance.curveBps(5000.0, 0.0, 20.0, 1.0, 0.5, 20.0), 1.0e-9, "远处不超过巡航速度");
  }

  @Test
  void curveIsTheTimetableBrakingCurveShiftedByTheReactionDistance() {
    double cruise = 22.0;
    double speed = 18.0;
    double reaction = 0.4;
    double expected =
        org.fetarute.fetaruteTCAddon.dispatcher.eta.model.SpeedCeiling.brakingLimitBps(
            new org.fetarute.fetaruteTCAddon.dispatcher.eta.model.SpeedCurve(1.2, 1.2),
            cruise,
            0.0,
            120.0 - speed * reaction);
    assertEquals(
        expected, DriverGuidance.curveBps(120.0, 0.0, cruise, 1.2, reaction, speed), 1.0e-9);
  }
}
