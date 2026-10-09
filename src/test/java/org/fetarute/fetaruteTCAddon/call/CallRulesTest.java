package org.fetarute.fetaruteTCAddon.call;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

/** 叫车判定：先看有没有车可派，再看同方向已叫、下一班快到，最后才是线路上限与个人冷却。 */
class CallRulesTest {

  @Test
  void availableWhenNextTrainIsFarEnough() {
    CallRules.Verdict verdict = CallRules.evaluate(input(true, none(), OptionalInt.of(6), 0, 0));

    assertTrue(verdict.callable());
    assertEquals(OptionalInt.of(3), verdict.minutes(), "可叫时带上预计到站分钟");
  }

  @Test
  void nextTrainWithinTheWaitBlocksTheCall() {
    CallRules.Verdict verdict = CallRules.evaluate(input(true, none(), OptionalInt.of(5), 0, 0));

    assertEquals(CallRules.Outcome.NEXT_TRAIN_SOON, verdict.outcome(), "正好 5 分钟也不能叫");
    assertEquals(OptionalInt.of(5), verdict.minutes());
  }

  @Test
  void noTrainInSightIsCallable() {
    assertTrue(CallRules.evaluate(input(true, none(), none(), 0, 0)).callable());
  }

  @Test
  void orderOfReasons() {
    assertEquals(
        CallRules.Outcome.NO_SOURCE,
        CallRules.evaluate(input(false, OptionalInt.of(2), OptionalInt.of(1), 9, 30)).outcome());
    assertEquals(
        CallRules.Outcome.ALREADY_CALLED,
        CallRules.evaluate(input(true, OptionalInt.of(8), OptionalInt.of(1), 9, 30)).outcome(),
        "同方向已叫的车优先于下一班快到");
    assertEquals(
        CallRules.Outcome.LINE_LIMIT,
        CallRules.evaluate(input(true, none(), none(), 2, 30)).outcome(),
        "线路上限优先于个人冷却");
    CallRules.Verdict cooldown = CallRules.evaluate(input(true, none(), none(), 1, 30));
    assertEquals(CallRules.Outcome.COOLDOWN, cooldown.outcome());
    assertEquals(30L, cooldown.seconds());
  }

  /** 叫来的车要比下一班早到 min-lead 分钟：早得不够多就显示下一班，不让叫。 */
  @Test
  void calledTrainMustArriveWellBeforeTheNextTrain() {
    CallRules.Verdict tooClose =
        CallRules.evaluate(
            new CallRules.Input(
                true, none(), OptionalInt.of(7), 5, 0, 2, 0L, OptionalInt.of(6), 2));
    assertEquals(CallRules.Outcome.NEXT_TRAIN_SOON, tooClose.outcome(), "只早 1 分钟");
    assertEquals(OptionalInt.of(7), tooClose.minutes(), "显示下一班几分钟到");

    assertTrue(
        CallRules.evaluate(
                new CallRules.Input(
                    true, none(), OptionalInt.of(7), 5, 0, 2, 0L, OptionalInt.of(5), 2))
            .callable(),
        "正好早 2 分钟可以叫");
    assertTrue(
        CallRules.evaluate(
                new CallRules.Input(
                    true, none(), OptionalInt.of(7), 5, 0, 2, 0L, OptionalInt.of(6), 0))
            .callable(),
        "min-lead 为 0 时不比");
    assertTrue(
        CallRules.evaluate(
                new CallRules.Input(true, none(), OptionalInt.of(7), 5, 0, 2, 0L, none(), 2))
            .callable(),
        "估不出到站时间时不比");
  }

  private static CallRules.Input input(
      boolean source, OptionalInt called, OptionalInt next, int active, long cooldown) {
    return new CallRules.Input(source, called, next, 5, active, 2, cooldown, OptionalInt.of(3), 2);
  }

  private static OptionalInt none() {
    return OptionalInt.empty();
  }
}
