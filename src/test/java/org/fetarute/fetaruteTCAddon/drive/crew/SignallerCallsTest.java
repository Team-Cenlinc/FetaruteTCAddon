package org.fetarute.fetaruteTCAddon.drive.crew;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("驾驶员呼叫信号员：两短识别与重复呼叫合并")
class SignallerCallsTest {

  private static final UUID DRIVER = UUID.randomUUID();
  private static final int LONG_TICKS = 16;
  private static final int DOUBLE_TICKS = 20;

  /** 记下每次转给信号员的呼叫，按设定回答有没有人接。 */
  /** 冷却期内写明了别的原因照样转给信号员：两短铃之后马上用命令说明车门故障，不会被吞掉。 */
  @Test
  void anExplicitNewReasonIsForwardedEvenWhileCooling() {
    SignallerCalls calls = new SignallerCalls(message -> {});
    FakeLink link = new FakeLink();
    calls.call(DRIVER, "T1", CallReason.GENERAL, 0L, link);
    assertTrue(calls.call(DRIVER, "T1", CallReason.GENERAL, 1_000L, link).repeated());
    assertFalse(calls.call(DRIVER, "T1", CallReason.DOORS, 2_000L, link).repeated());
    assertTrue(calls.call(DRIVER, "T1", CallReason.DOORS, 3_000L, link).repeated());
  }

  private static final class FakeLink implements SignallerLink {
    final List<String> calls = new ArrayList<>();
    boolean answer = true;

    @Override
    public boolean call(UUID driver, String trainName) {
      calls.add(trainName);
      return answer;
    }
  }

  private static List<UUID> tickAt(SignallerCalls calls, long clientMillis) {
    return calls.tick(id -> clientMillis, id -> true, 0L);
  }

  @Test
  @DisplayName("两短（20 tick 以内按两下）认作呼叫；一短、一长不作数")
  void twoShortRingsCall() {
    SignallerCalls calls = new SignallerCalls(null);
    calls.press(DRIVER, 0L, LONG_TICKS, DOUBLE_TICKS);
    assertTrue(tickAt(calls, 100L).isEmpty(), "还按着");
    calls.press(DRIVER, 400L, LONG_TICKS, DOUBLE_TICKS);
    assertEquals(List.of(DRIVER), tickAt(calls, 800L));

    SignallerCalls single = new SignallerCalls(null);
    single.press(DRIVER, 0L, LONG_TICKS, DOUBLE_TICKS);
    assertTrue(tickAt(single, 400L).isEmpty());
    assertTrue(tickAt(single, 2000L).isEmpty(), "一短过了两短的时限也不算呼叫");

    SignallerCalls held = new SignallerCalls(null);
    for (long at = 0L; at <= 1000L; at += 200L) {
      held.press(DRIVER, at, LONG_TICKS, DOUBLE_TICKS);
    }
    assertTrue(tickAt(held, 1500L).isEmpty(), "一长不算呼叫");
  }

  @Test
  @DisplayName("不在驾驶的驾驶员按到一半的铃作废")
  void forgetsWhenNotDriving() {
    SignallerCalls calls = new SignallerCalls(null);
    calls.press(DRIVER, 0L, LONG_TICKS, DOUBLE_TICKS);
    assertTrue(calls.tick(id -> 400L, id -> false, 0L).isEmpty());
    calls.press(DRIVER, 400L, LONG_TICKS, DOUBLE_TICKS);
    assertTrue(tickAt(calls, 800L).isEmpty(), "前一下已作废，这一下只是一短");
  }

  @Test
  @DisplayName("5 秒内重复呼叫只转给信号员一次，其间沿用上一次的结果；过了冷却再转")
  void cooldownMergesRepeats() {
    SignallerCalls calls = new SignallerCalls(null);
    FakeLink link = new FakeLink();

    SignallerCalls.Outcome first = calls.call(DRIVER, "T1", 1_000L, link);
    assertTrue(first.reached());
    assertFalse(first.repeated());

    link.answer = false;
    SignallerCalls.Outcome repeat = calls.call(DRIVER, "T1", 5_999L, link);
    assertTrue(repeat.reached(), "沿用上一次：信号员已收到");
    assertTrue(repeat.repeated());
    assertEquals(1, link.calls.size());

    SignallerCalls.Outcome later = calls.call(DRIVER, "T1", 6_000L, link);
    assertFalse(later.reached());
    assertFalse(later.repeated());
    assertEquals(2, link.calls.size());

    assertFalse(calls.call(UUID.randomUUID(), "T2", 6_001L, link).repeated(), "按驾驶员分开算");
  }

  @Test
  @DisplayName("没人值守时告诉驾驶员没人接；冷却期内的重复也照样这么说")
  void noSignaller() {
    SignallerCalls calls = new SignallerCalls(null);
    SignallerCalls.Outcome none = calls.call(DRIVER, "T1", 0L, SignallerLink.NONE);
    assertFalse(none.reached());
    SignallerCalls.Outcome repeat = calls.call(DRIVER, "T1", 100L, SignallerLink.NONE);
    assertFalse(repeat.reached());
    assertTrue(repeat.repeated());
  }

  @Test
  @DisplayName("信号员模块处理呼叫出错时当作没人接，并记日志")
  void linkErrorCountsAsNoAnswer() {
    List<String> warnings = new ArrayList<>();
    SignallerCalls calls = new SignallerCalls(warnings::add);
    SignallerCalls.Outcome outcome =
        calls.call(
            DRIVER,
            "T1",
            0L,
            (driver, train) -> {
              throw new IllegalStateException("boom");
            });
    assertFalse(outcome.reached());
    assertEquals(1, warnings.size());
  }

  @Test
  @DisplayName("过了冷却的记录在定时调用时清掉")
  void prunesExpiredCooldowns() {
    SignallerCalls calls = new SignallerCalls(null);
    FakeLink link = new FakeLink();
    calls.call(DRIVER, "T1", 0L, link);
    calls.tick(id -> 0L, id -> true, 10_000L);
    assertFalse(calls.call(DRIVER, "T1", 10_001L, link).repeated());
    assertEquals(2, link.calls.size());
  }
}
