package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.model.StopApproach;
import org.junit.jupiter.api.Test;

/**
 * 进站限速：控车与编表运行曲线同一判据。
 *
 * <p>限速区由 {@link StopApproach#zones} 划定（编表与控车共用，划区用例见 {@code StopApproachTest}），控车在区内限进站速度、
 * 区外按车型减速度制动至区起点，与运行曲线反向推算一致。
 */
class RuntimeDispatchApproachSpeedTest {

  @Test
  void limitIsApproachSpeedInsideAndBrakesIntoTheZoneOutside() {
    List<StopApproach.Zone> zones = List.of(new StopApproach.Zone(60.0, 150.0));

    assertEquals(
        Math.sqrt(100.0 + 2.0 * 1.0 * 60.0),
        RuntimeTrainController.approachSpeedLimit(10.0, 1.0, zones, 0.0),
        1.0e-9);
    assertEquals(
        Math.sqrt(100.0 + 2.0 * 1.0 * 20.0),
        RuntimeTrainController.approachSpeedLimit(10.0, 1.0, zones, 40.0),
        1.0e-9);
    assertEquals(10.0, RuntimeTrainController.approachSpeedLimit(10.0, 1.0, zones, 60.0), 1.0e-9);
    assertEquals(10.0, RuntimeTrainController.approachSpeedLimit(10.0, 1.0, zones, 149.0), 1.0e-9);
    assertEquals(
        Double.POSITIVE_INFINITY,
        RuntimeTrainController.approachSpeedLimit(10.0, 1.0, zones, 151.0),
        "驶过限速区后不再设限");
  }

  @Test
  void limitTakesTheTightestZoneAhead() {
    // 前方先是离得远的区，后是离得近但更早要刹的区：取两者刹车曲线的较低者。
    List<StopApproach.Zone> zones =
        List.of(new StopApproach.Zone(80.0, 80.0), new StopApproach.Zone(30.0, 40.0));

    assertEquals(
        Math.sqrt(100.0 + 2.0 * 0.8 * 30.0),
        RuntimeTrainController.approachSpeedLimit(10.0, 0.8, zones, 0.0),
        1.0e-9);
  }

  @Test
  void withoutValidDecelerationOnlyTheZoneItselfIsLimited() {
    List<StopApproach.Zone> zones = List.of(new StopApproach.Zone(60.0, 150.0));

    assertEquals(
        Double.POSITIVE_INFINITY,
        RuntimeTrainController.approachSpeedLimit(10.0, Double.NaN, zones, 0.0));
    assertEquals(
        10.0, RuntimeTrainController.approachSpeedLimit(10.0, Double.NaN, zones, 70.0), 1.0e-9);
  }

  @Test
  void limitFallsContinuouslyAsTheTrainAdvances() {
    List<StopApproach.Zone> zones =
        List.of(new StopApproach.Zone(210.0, 300.0), new StopApproach.Zone(300.0, 300.0));
    double previous = Double.POSITIVE_INFINITY;
    for (int traveled = 0; traveled <= 300; traveled++) {
      double limit = RuntimeTrainController.approachSpeedLimit(10.0, 1.0, zones, traveled);
      assertTrue(limit <= previous + 1.0e-9, "限速不得随前进回升，traveled=" + traveled);
      if (Double.isFinite(previous) && Double.isFinite(limit)) {
        assertTrue(previous - limit < 0.2, "每前进 1 格限速变化应很小，traveled=" + traveled);
      }
      previous = limit;
    }
  }

  @Test
  void approachConstraintEvaluatesTheSameLimitPerTick() {
    List<StopApproach.Zone> zones = List.of(new StopApproach.Zone(60.0, 150.0));
    var constraint = RuntimeTrainController.approachConstraint(10.0, 1.0, zones);

    for (double traveled : new double[] {0.0, 12.5, 59.9, 60.0, 100.0, 151.0}) {
      assertEquals(
          RuntimeTrainController.approachSpeedLimit(10.0, 1.0, zones, traveled),
          constraint.limitBps(traveled),
          1.0e-12,
          "traveled=" + traveled);
    }
  }
}
