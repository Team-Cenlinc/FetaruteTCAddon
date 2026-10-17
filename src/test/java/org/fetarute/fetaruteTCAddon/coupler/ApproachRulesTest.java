package org.fetarute.fetaruteTCAddon.coupler;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.OptionalDouble;
import org.junit.jupiter.api.Test;

/** 联挂进站每一拍的判定与速度。 */
class ApproachRulesTest {

  private static ApproachRules.Observation seen(
      OptionalDouble gap, boolean other, double travelled, int ticks, double moved) {
    return new ApproachRules.Observation(gap, other, travelled, 40.0, ticks, 1000, moved);
  }

  @Test
  void stopsToLinkWhenTheGapCloses() {
    assertEquals(
        ApproachRules.Step.ARRIVED,
        ApproachRules.step(seen(OptionalDouble.of(0.2), false, 20.0, 400, 0.0)));
    assertEquals(
        ApproachRules.Step.CONTINUE,
        ApproachRules.step(seen(OptionalDouble.of(3.0), false, 20.0, 400, 0.0)));
  }

  @Test
  void keepsGoingBlindUntilTheBudgetIsSpent() {
    assertEquals(
        ApproachRules.Step.CONTINUE,
        ApproachRules.step(seen(OptionalDouble.empty(), false, 39.0, 400, 0.0)),
        "道岔还没扳到、看不见对方：照常往前走");
    assertEquals(
        ApproachRules.Step.OVERRUN,
        ApproachRules.step(seen(OptionalDouble.empty(), false, 40.5, 400, 0.0)));
  }

  @Test
  void givesUpOnAnyoneElseOrAMovingPartnerOrAStall() {
    assertEquals(
        ApproachRules.Step.OTHER_TRAIN,
        ApproachRules.step(seen(OptionalDouble.of(0.1), true, 5.0, 100, 0.0)),
        "先碰到别的列车：哪怕空隙已经很小也不挂");
    assertEquals(
        ApproachRules.Step.STANDING_MOVED,
        ApproachRules.step(seen(OptionalDouble.of(2.0), false, 5.0, 100, 0.8)));
    assertEquals(
        ApproachRules.Step.STALLED,
        ApproachRules.step(seen(OptionalDouble.of(2.0), false, 0.01, 60, 0.0)));
    assertEquals(
        ApproachRules.Step.STALLED,
        ApproachRules.step(
            new ApproachRules.Observation(
                OptionalDouble.of(2.0), false, 10.0, 40.0, 1001, 1000, 0.0)));
  }

  @Test
  void slowsDownForTheLastFewBlocks() {
    assertEquals(0.1, ApproachRules.speed(OptionalDouble.empty(), 0.1, 0.05), 1e-9);
    assertEquals(0.1, ApproachRules.speed(OptionalDouble.of(10.0), 0.1, 0.05), 1e-9);
    assertEquals(0.05, ApproachRules.speed(OptionalDouble.of(5.0), 0.1, 0.05), 1e-9);
    assertEquals(720, ApproachRules.maxTicks(40.0, 0.1, 0.05), "400 拍走完上限 + 120 拍对位 + 200 拍余量");
  }
}
