package org.fetarute.fetaruteTCAddon.drive.crew;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("乘务员呼叫信号员：两短识别与重复呼叫合并")
class SignallerCallsTest {

  private static final UUID DRIVER = UUID.randomUUID();
  private static final UUID GUARD = UUID.randomUUID();
  private static final int LONG_TICKS = 16;
  private static final int DOUBLE_TICKS = 20;

  private static final CallResult CALLED = new CallResult(CallResult.Status.CALLED, List.of("示例站"));

  /** 记下每次转给信号员的呼叫，按设定回答有没有人接。 */
  private static final class FakeLink implements SignallerLink {
    final List<String> calls = new ArrayList<>();
    final List<CallerRole> roles = new ArrayList<>();
    CallResult answer = CALLED;

    @Override
    public CallResult call(UUID caller, CallerRole role, String trainName, CallReason reason) {
      calls.add(trainName);
      roles.add(role);
      return answer;
    }
  }

  private static SignallerCalls.Outcome driverCall(
      SignallerCalls calls, CallReason reason, long at, SignallerLink link) {
    return calls.call(DRIVER, CallerRole.DRIVER, "T1", reason, at, link);
  }

  private static List<UUID> tickAt(SignallerCalls calls, long clientMillis) {
    return calls.tick(id -> clientMillis, id -> true, 0L);
  }

  /** 冷却期内写明了别的原因照样转给信号员：两短铃之后马上用命令说明车门故障，不会被吞掉。 */
  @Test
  void anExplicitNewReasonIsForwardedEvenWhileCooling() {
    SignallerCalls calls = new SignallerCalls(message -> {});
    FakeLink link = new FakeLink();
    driverCall(calls, CallReason.GENERAL, 0L, link);
    assertTrue(driverCall(calls, CallReason.GENERAL, 1_000L, link).repeated());
    assertFalse(driverCall(calls, CallReason.DOORS, 2_000L, link).repeated());
    assertTrue(driverCall(calls, CallReason.DOORS, 3_000L, link).repeated());
  }

  @Test
  @DisplayName("两短（20 tick 以内按两下）认作呼叫；一短、一长不作数")
  void twoShortRingsCall() {
    SignallerCalls calls = new SignallerCalls(null);
    calls.press(DRIVER, 0L, LONG_TICKS, DOUBLE_TICKS);
    assertTrue(tickAt(calls, 50L).isEmpty(), "还在等是不是两短");
    calls.press(DRIVER, 400L, LONG_TICKS, DOUBLE_TICKS);
    assertEquals(List.of(DRIVER), tickAt(calls, 800L));

    SignallerCalls single = new SignallerCalls(null);
    single.press(DRIVER, 0L, LONG_TICKS, DOUBLE_TICKS);
    assertTrue(tickAt(single, 400L).isEmpty());
    assertTrue(tickAt(single, 2000L).isEmpty(), "一短过了两短的时限也不算呼叫");

    SignallerCalls held = new SignallerCalls(null);
    held.press(DRIVER, 0L, LONG_TICKS, DOUBLE_TICKS);
    // 按住丢弃键：系统隔一段初始延迟后才按键重复
    for (long at = 500L; at <= 1_500L; at += 50L) {
      held.press(DRIVER, at, LONG_TICKS, DOUBLE_TICKS);
    }
    assertTrue(tickAt(held, 3_000L).isEmpty(), "一长不算呼叫");
  }

  @Test
  @DisplayName("快速连按两下丢弃键（相隔 0.15 秒）也是两短")
  void quickDoubleTapCalls() {
    SignallerCalls calls = new SignallerCalls(null);
    calls.press(DRIVER, 1_000L, LONG_TICKS, DOUBLE_TICKS);
    calls.press(DRIVER, 1_150L, LONG_TICKS, DOUBLE_TICKS);
    assertEquals(List.of(DRIVER), tickAt(calls, 1_400L));
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

    SignallerCalls.Outcome first = driverCall(calls, CallReason.GENERAL, 1_000L, link);
    assertTrue(first.result().status().reached());
    assertFalse(first.repeated());

    link.answer = CallResult.of(CallResult.Status.NO_SIGNALLER);
    SignallerCalls.Outcome repeat = driverCall(calls, CallReason.GENERAL, 5_999L, link);
    assertEquals(CALLED, repeat.result(), "沿用上一次：信号员已收到");
    assertTrue(repeat.repeated());
    assertEquals(1, link.calls.size());

    SignallerCalls.Outcome later = driverCall(calls, CallReason.GENERAL, 6_000L, link);
    assertFalse(later.result().status().reached());
    assertFalse(later.repeated());
    assertEquals(2, link.calls.size());

    assertFalse(
        calls
            .call(UUID.randomUUID(), CallerRole.DRIVER, "T2", CallReason.GENERAL, 6_001L, link)
            .repeated(),
        "按乘务员分开算");
  }

  @Test
  @DisplayName("没人接的呼叫不合并：车开近了马上再叫，照样转给信号员")
  void unansweredCallsAreNotCooled() {
    SignallerCalls calls = new SignallerCalls(null);
    FakeLink link = new FakeLink();
    link.answer = new CallResult(CallResult.Status.OUT_OF_ZONE, List.of("示例站"));
    assertEquals(
        CallResult.Status.OUT_OF_ZONE,
        driverCall(calls, CallReason.GENERAL, 0L, link).result().status());

    link.answer = CALLED;
    SignallerCalls.Outcome retry = driverCall(calls, CallReason.GENERAL, 1_000L, link);
    assertFalse(retry.repeated());
    assertEquals(CallResult.Status.CALLED, retry.result().status());
    assertEquals(2, link.calls.size());
  }

  @Test
  @DisplayName("车掌的呼叫带着车掌的岗位转给信号员，与驾驶员各算各的冷却")
  void guardCallsCarryTheirRole() {
    SignallerCalls calls = new SignallerCalls(null);
    FakeLink link = new FakeLink();
    driverCall(calls, CallReason.GENERAL, 0L, link);
    SignallerCalls.Outcome guard =
        calls.call(GUARD, CallerRole.GUARD, "T1", CallReason.DOORS, 100L, link);

    assertFalse(guard.repeated());
    assertEquals(List.of(CallerRole.DRIVER, CallerRole.GUARD), link.roles);
  }

  @Test
  @DisplayName("没有信号员模块时说明功能未启用")
  void noModuleMeansUnavailable() {
    SignallerCalls calls = new SignallerCalls(null);
    SignallerCalls.Outcome none = driverCall(calls, CallReason.GENERAL, 0L, SignallerLink.NONE);
    assertEquals(CallResult.Status.UNAVAILABLE, none.result().status());
    assertEquals("drive.command.call.unavailable", none.result().key("drive.command.call"));
  }

  @Test
  @DisplayName("提示的语言键与占位符：接到的那一片；不在区内时前方有值守的各片另用一种说法")
  void keysAndValues() {
    assertEquals("drive.driver.signaller.called", CALLED.key("drive.driver.signaller"));
    assertEquals("示例站", CALLED.values().get("zone"));

    CallResult ahead = new CallResult(CallResult.Status.OUT_OF_ZONE, List.of("甲站", "乙站"));
    assertEquals("drive.guard.signaller.out-of-zone-ahead", ahead.key("drive.guard.signaller"));
    assertEquals(Map.of("zone", "甲站", "zones", "甲站 / 乙站"), ahead.values());
    assertEquals(
        "drive.guard.signaller.out-of-zone",
        CallResult.of(CallResult.Status.OUT_OF_ZONE).key("drive.guard.signaller"),
        "前方没有值守的控制区（或认不出交路）");

    assertEquals(
        "drive.command.call.none",
        CallResult.of(CallResult.Status.NO_SIGNALLER).key("drive.command.call"));
    assertTrue(CallResult.keySuffixes().contains("out-of-zone-ahead"));
  }

  /** 两短铃之后的去向（驾驶员与车掌共用），记下弹了框还是直接回了话。 */
  private static final class BuzzLog {
    final List<String> asked = new ArrayList<>();
    final List<CallResult> told = new ArrayList<>();
    final List<Boolean> rang = new ArrayList<>();

    void buzz(SignallerCalls calls, boolean stopped, long at, SignallerLink link) {
      calls.buzz(
          GUARD,
          CallerRole.GUARD,
          "T1",
          stopped,
          at,
          link,
          (zone, waiting) -> asked.add(zone + (waiting ? " 入口等候" : "")),
          (result, ring) -> {
            told.add(result);
            rang.add(ring);
          });
    }
  }

  /** 能叫到、列车停在入口的链接：先问会落到哪里，真呼叫时记下来。 */
  private static class ProbedLink implements SignallerLink {
    final List<CallReason> calls = new ArrayList<>();
    CallResult reach = CALLED;
    boolean waiting = true;

    @Override
    public CallResult call(UUID caller, CallerRole role, String trainName, CallReason reason) {
      calls.add(reason);
      return reach;
    }

    @Override
    public CallResult probe(UUID caller, String trainName) {
      return reach;
    }

    @Override
    public boolean waiting(String trainName) {
      return waiting;
    }
  }

  @Test
  @DisplayName("两短铃：行驶中直接按一般联系呼叫、有人接时响；冷却期内再按不响")
  void aBuzzWhileMovingCallsAtOnce() {
    SignallerCalls calls = new SignallerCalls(null);
    ProbedLink link = new ProbedLink();
    BuzzLog log = new BuzzLog();

    log.buzz(calls, false, 0L, link);
    log.buzz(calls, false, 1_000L, link);

    assertTrue(log.asked.isEmpty(), "行驶中不弹框");
    assertEquals(List.of(CallReason.GENERAL), link.calls, "冷却期内只转一次");
    assertEquals(List.of(CALLED, CALLED), log.told);
    assertEquals(List.of(true, false), log.rang);
  }

  @Test
  @DisplayName("两短铃：停着且叫得到时只弹框（写明哪片、是否在入口等候），选了原因才真呼叫")
  void aBuzzWhileStoppedAsksForAReason() {
    SignallerCalls calls = new SignallerCalls(null);
    ProbedLink link = new ProbedLink();
    BuzzLog log = new BuzzLog();

    log.buzz(calls, true, 0L, link);

    assertEquals(List.of("示例站 入口等候"), log.asked);
    assertTrue(log.told.isEmpty());
    assertTrue(link.calls.isEmpty(), "弹框前不排队");
  }

  @Test
  @DisplayName("两短铃：停着但叫不到时不弹框，直接说原因、不响")
  void aBuzzThatCannotReachAnyoneSaysWhy() {
    SignallerCalls calls = new SignallerCalls(null);
    ProbedLink link = new ProbedLink();
    link.reach = new CallResult(CallResult.Status.OUT_OF_ZONE, List.of("乙站"));
    BuzzLog log = new BuzzLog();

    log.buzz(calls, true, 0L, link);

    assertTrue(log.asked.isEmpty());
    assertEquals(List.of(link.reach), log.told);
    assertEquals(List.of(false), log.rang);
    assertTrue(link.calls.isEmpty());
  }

  @Test
  @DisplayName("两短铃：查不了是否在入口等候时当作不在等，照样弹框，并记日志")
  void aBuzzSurvivesAWaitingLookupError() {
    List<String> warnings = new ArrayList<>();
    SignallerCalls calls = new SignallerCalls(warnings::add);
    ProbedLink link =
        new ProbedLink() {
          @Override
          public boolean waiting(String trainName) {
            throw new IllegalStateException("boom");
          }
        };
    BuzzLog log = new BuzzLog();

    log.buzz(calls, true, 0L, link);

    assertEquals(List.of("示例站"), log.asked);
    assertEquals(1, warnings.size());
  }

  @Test
  @DisplayName("信号员模块处理呼叫出错时当作没人接，并记日志")
  void linkErrorCountsAsNoAnswer() {
    List<String> warnings = new ArrayList<>();
    SignallerCalls calls = new SignallerCalls(warnings::add);
    SignallerCalls.Outcome outcome =
        driverCall(
            calls,
            CallReason.GENERAL,
            0L,
            (caller, role, train, reason) -> {
              throw new IllegalStateException("boom");
            });
    assertFalse(outcome.result().status().reached());
    assertEquals(1, warnings.size());
  }

  @Test
  @DisplayName("弹框前的询问：没有信号员模块时说明功能未启用，模块出错时当作没人接并记日志")
  void probeFallsBackToUnavailable() {
    List<String> warnings = new ArrayList<>();
    SignallerCalls calls = new SignallerCalls(warnings::add);
    assertEquals(
        CallResult.Status.UNAVAILABLE, calls.probe(DRIVER, "T1", SignallerLink.NONE).status());

    SignallerLink broken =
        new SignallerLink() {
          @Override
          public CallResult call(
              UUID caller, CallerRole role, String trainName, CallReason reason) {
            return CALLED;
          }

          @Override
          public CallResult probe(UUID caller, String trainName) {
            throw new IllegalStateException("boom");
          }
        };
    assertEquals(CallResult.Status.UNAVAILABLE, calls.probe(DRIVER, "T1", broken).status());
    assertEquals(1, warnings.size());
  }

  @Test
  @DisplayName("过了冷却的记录在定时调用时清掉")
  void prunesExpiredCooldowns() {
    SignallerCalls calls = new SignallerCalls(null);
    FakeLink link = new FakeLink();
    driverCall(calls, CallReason.GENERAL, 0L, link);
    calls.tick(id -> 0L, id -> true, 10_000L);
    assertFalse(driverCall(calls, CallReason.GENERAL, 10_001L, link).repeated());
    assertEquals(2, link.calls.size());
  }
}
