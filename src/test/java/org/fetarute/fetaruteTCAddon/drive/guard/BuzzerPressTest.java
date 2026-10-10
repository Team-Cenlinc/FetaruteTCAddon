package org.fetarute.fetaruteTCAddon.drive.guard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** 发车铃的按法：按住约一秒为一长，短按一下为一短，一秒内两短为呼叫。 */
class BuzzerPressTest {

  /** 从 {@code from} 起按住 {@code holdTicks}（客户端每 4 tick 重发一次），返回之后逐 tick 认出的按法。 */
  private static List<BuzzerPress.Kind> hold(BuzzerPress press, long from, long holdTicks) {
    for (long t = from; t < from + holdTicks; t += 4) {
      press.press(t);
    }
    return drain(press, from, from + holdTicks + 40);
  }

  private static List<BuzzerPress.Kind> drain(BuzzerPress press, long from, long to) {
    List<BuzzerPress.Kind> out = new ArrayList<>();
    for (long t = from; t <= to; t++) {
      press.tick(t).ifPresent(out::add);
    }
    return out;
  }

  @Test
  void holdingAboutASecondIsLong() {
    BuzzerPress press = new BuzzerPress(16, 20);
    assertEquals(List.of(BuzzerPress.Kind.LONG), hold(press, 100, 20));
  }

  @Test
  void aTapIsShortOnceTheCallWindowPasses() {
    BuzzerPress press = new BuzzerPress(16, 20);
    press.press(100);
    assertEquals(Optional.empty(), press.tick(107), "松开后还在等是不是两短");
    assertEquals(List.of(BuzzerPress.Kind.SHORT), drain(press, 108, 140));
  }

  @Test
  void twoTapsWithinTheWindowAreACall() {
    BuzzerPress press = new BuzzerPress(16, 20);
    press.press(100);
    List<BuzzerPress.Kind> seen = new ArrayList<>(drain(press, 100, 110));
    press.press(112);
    seen.addAll(drain(press, 111, 160));
    assertEquals(List.of(BuzzerPress.Kind.CALL), seen);
  }

  @Test
  void ringsWhileHeldAndStopsAfterRelease() {
    BuzzerPress press = new BuzzerPress(16, 20);
    assertFalse(press.ringing(100));
    press.press(100);
    press.press(104);
    assertTrue(press.ringing(108));
    assertFalse(press.ringing(111));
  }
}
