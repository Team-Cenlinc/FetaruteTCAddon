package org.fetarute.fetaruteTCAddon.drive.guard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.fetarute.fetaruteTCAddon.drive.inventory.ClientClock;
import org.junit.jupiter.api.Test;

/** 发车铃的按法：按住约一秒为一长，短按一下为一短，一秒内两短为呼叫。时刻是客户端时钟的读数（毫秒，一 tick 50）。 */
class BuzzerPressTest {

  /** 从 {@code from} 起按住 {@code holdMillis}（客户端每 200 毫秒重发一次），返回之后逐 tick 认出的按法。 */
  private static List<BuzzerPress.Kind> hold(BuzzerPress press, long from, long holdMillis) {
    for (long t = from; t < from + holdMillis; t += 200) {
      press.press(t);
    }
    return drain(press, from, from + holdMillis + 2_000);
  }

  private static List<BuzzerPress.Kind> drain(BuzzerPress press, long from, long to) {
    List<BuzzerPress.Kind> out = new ArrayList<>();
    for (long t = from; t <= to; t += 50) {
      press.tick(t).ifPresent(out::add);
    }
    return out;
  }

  @Test
  void holdingAboutASecondIsLong() {
    BuzzerPress press = new BuzzerPress(16, 20);
    assertEquals(List.of(BuzzerPress.Kind.LONG), hold(press, 5_000, 1_000));
  }

  @Test
  void aTapIsShortOnceTheCallWindowPasses() {
    BuzzerPress press = new BuzzerPress(16, 20);
    press.press(5_000);
    assertEquals(Optional.empty(), press.tick(5_350), "松开后还在等是不是两短");
    assertEquals(List.of(BuzzerPress.Kind.SHORT), drain(press, 5_400, 7_000));
  }

  @Test
  void twoTapsWithinTheWindowAreACall() {
    BuzzerPress press = new BuzzerPress(16, 20);
    press.press(5_000);
    List<BuzzerPress.Kind> seen = new ArrayList<>(drain(press, 5_000, 5_500));
    press.press(5_600);
    seen.addAll(drain(press, 5_550, 8_000));
    assertEquals(List.of(BuzzerPress.Kind.CALL), seen);
  }

  @Test
  void ringsWhileHeldAndStopsAfterRelease() {
    BuzzerPress press = new BuzzerPress(16, 20);
    assertFalse(press.ringing(5_000));
    press.press(5_000);
    press.press(5_200);
    assertTrue(press.ringing(5_400));
    assertFalse(press.ringing(5_550));
  }

  /** 按下在收到时当场读数：服务器主线程卡住 0.6 秒、之后连跑几拍补进度，按住也不会被当成松开（按服务器 tick 计时时补进度的几拍会把间隔撑大）。 */
  @Test
  void aServerStallDuringTheHoldStillYieldsOneLong() {
    BuzzerPress press = new BuzzerPress(16, 20);
    List<BuzzerPress.Kind> seen = new ArrayList<>();
    press.press(1_000);
    press.tick(1_050).ifPresent(seen::add);
    // 卡住期间主线程不推进，网络线程照收
    press.press(1_200);
    press.press(1_400);
    press.press(1_600);
    for (long t = 1_650; t <= 1_670; t += 5) {
      press.tick(t).ifPresent(seen::add);
    }
    press.press(1_800);
    for (long t = 1_850; t <= 2_400; t += 50) {
      press.tick(t).ifPresent(seen::add);
    }
    assertEquals(List.of(BuzzerPress.Kind.LONG), seen);
  }

  @Test
  void realTimeTapsKeepTheShortAndCallRules() {
    BuzzerPress press = new BuzzerPress(16, 20);
    press.press(1_000);
    List<BuzzerPress.Kind> seen = new ArrayList<>();
    for (long t = 1_050; t <= 1_550; t += 50) {
      press.tick(t).ifPresent(seen::add);
    }
    press.press(1_600);
    for (long t = 1_650; t <= 3_000; t += 50) {
      press.tick(t).ifPresent(seen::add);
    }
    assertEquals(List.of(BuzzerPress.Kind.CALL), seen, "一秒内两短");

    press.press(5_000);
    seen.clear();
    for (long t = 5_050; t <= 7_000; t += 50) {
      press.tick(t).ifPresent(seen::add);
    }
    assertEquals(List.of(BuzzerPress.Kind.SHORT), seen);
  }

  /** 客户端报着按住（处于使用状态，松开时另发包）：按住期间一个包也没有，照样一直响着，松开时按真实时长认一长。 */
  @Test
  void aClientHeldPressLastsUntilTheRelease() {
    BuzzerPress press = new BuzzerPress(16, 20);
    List<BuzzerPress.Kind> seen = new ArrayList<>();
    assertTrue(press.press(1_000, true), "新按下");
    for (long t = 1_050; t <= 1_900; t += 50) {
      press.tick(t).ifPresent(seen::add);
      assertTrue(press.ringing(t), "没有重发也还按着");
    }
    press.release(1_900);
    assertFalse(press.ringing(1_900));
    press.tick(1_950).ifPresent(seen::add);
    assertEquals(List.of(BuzzerPress.Kind.LONG), seen);
  }

  /** 客户端报着按住时，松开与再按下都有包：两下点得再快也分得开（按重发间隔量时相隔不到 0.3 秒的两下会并成一下）。 */
  @Test
  void quickClientHeldTapsAreACall() {
    BuzzerPress press = new BuzzerPress(16, 20);
    List<BuzzerPress.Kind> seen = new ArrayList<>();
    press.press(1_000, true);
    press.release(1_080);
    press.tick(1_100).ifPresent(seen::add);
    press.press(1_200, true);
    press.release(1_270);
    for (long t = 1_300; t <= 3_000; t += 50) {
      press.tick(t).ifPresent(seen::add);
    }
    assertEquals(List.of(BuzzerPress.Kind.CALL), seen);

    press.press(5_000, true);
    press.release(5_100);
    seen.clear();
    for (long t = 5_150; t <= 7_000; t += 50) {
      press.tick(t).ifPresent(seen::add);
    }
    assertEquals(List.of(BuzzerPress.Kind.SHORT), seen, "按得不够久是一短");
  }

  /** 按住期间又收到包，说明客户端没有处于使用状态、在重发：改按重发间隔判松开，不会一直响下去。 */
  @Test
  void aResendDuringAClientHoldFallsBackToTheGap() {
    BuzzerPress press = new BuzzerPress(16, 20);
    press.press(1_000, true);
    assertFalse(press.press(1_200, true), "还按着，不是新按下");
    assertTrue(press.ringing(1_450));
    assertFalse(press.ringing(1_550), "重发停了 0.3 秒以上算松开");
    List<BuzzerPress.Kind> seen = new ArrayList<>();
    for (long t = 1_550; t <= 3_000; t += 50) {
      press.tick(t).ifPresent(seen::add);
    }
    assertEquals(List.of(BuzzerPress.Kind.SHORT), seen);
  }

  /** 客户端报着按住却一直等不到松开：按满上限作废，不当成一长。 */
  @Test
  void aClientHoldWithoutReleaseIsDroppedAtTheLimit() {
    BuzzerPress press = new BuzzerPress(16, 20);
    press.press(1_000, true);
    long limit = BuzzerPress.MAX_HELD_TICKS * ClientClock.MILLIS_PER_TICK;
    assertTrue(press.ringing(1_000 + limit));
    List<BuzzerPress.Kind> seen = new ArrayList<>();
    for (long t = 1_000 + limit + 50; t <= 1_000 + limit + 3_000; t += 50) {
      press.tick(t).ifPresent(seen::add);
    }
    assertEquals(List.of(), seen);
    assertFalse(press.ringing(1_000 + limit + 100));
  }

  @Test
  void aReleaseWithoutAPressDoesNothing() {
    BuzzerPress press = new BuzzerPress(16, 20);
    press.release(1_000);
    assertEquals(Optional.empty(), press.tick(1_050));
    assertFalse(press.ringing(1_050));
  }

  /** 包挤在一起到、中间没有定时检查：前一下已过了松开门槛的照样先认掉，等两短等过时限的那一短也不会丢。 */
  @Test
  void bunchedPacketsAreSettledOnArrival() {
    BuzzerPress press = new BuzzerPress(16, 20);
    press.press(5_000);
    assertTrue(press.press(5_400, false), "隔了 0.4 秒是新按下，不是重发");
    assertEquals(List.of(BuzzerPress.Kind.CALL), drain(press, 5_450, 8_000), "两下 0.4 秒内是呼叫");

    press.press(10_000);
    press.press(12_000);
    List<BuzzerPress.Kind> seen = drain(press, 12_050, 14_000);
    assertEquals(List.of(BuzzerPress.Kind.SHORT, BuzzerPress.Kind.SHORT), seen, "相隔两秒是两个一短");
  }
}
