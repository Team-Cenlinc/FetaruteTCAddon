package org.fetarute.fetaruteTCAddon.drive.guard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.List;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.drive.driver.SignalAcknowledge;
import org.junit.jupiter.api.Test;

/** simulation 级驾驶员收到车掌的发车信号要回一短：按时回算收到，到时没回记一次漏确认。 */
class PendingAcksTest {

  @Test
  void anAnswerInTimeClearsTheWait() {
    PendingAcks acks = new PendingAcks();
    UUID driver = UUID.randomUUID();
    assertFalse(acks.acknowledge(driver), "没在等时按一短只是普通的收到");
    acks.expect(driver, 100L);
    assertEquals(List.of(), acks.expired(99L));
    assertTrue(acks.acknowledge(driver));
    assertEquals(List.of(), acks.expired(200L));
    assertTrue(acks.isEmpty());
  }

  @Test
  void aLateDriverIsReportedOnce() {
    PendingAcks acks = new PendingAcks();
    UUID late = UUID.randomUUID();
    UUID gone = UUID.randomUUID();
    acks.expect(late, 100L);
    acks.expect(gone, 100L);
    acks.forget(gone);
    assertEquals(List.of(late), acks.expired(100L));
    assertEquals(List.of(), acks.expired(101L));
  }

  /** 时限最后一刻按下的一短照样算收到：期限留出了认出一短要等的时间。 */
  @Test
  void aPressAtTheLastMomentStillCounts() {
    GuardConfig guard = GuardConfig.defaults();
    PendingAcks acks = new PendingAcks();
    UUID driver = UUID.randomUUID();
    long signal = 1000L;
    acks.expect(driver, PendingAcks.deadline(signal, guard));
    BuzzerPress press = new BuzzerPress(guard.buzzerLongTicks(), guard.buzzerDoubleTicks());
    long pressedAt = signal + guard.ackSeconds() * 20L;
    press.press(pressedAt);
    // 与车掌会话每拍的顺序相同：先认铃声，再看过期。
    for (long now = pressedAt + 1; now < pressedAt + 200; now++) {
      if (press.tick(now).filter(kind -> kind == BuzzerPress.Kind.SHORT).isPresent()) {
        assertTrue(acks.acknowledge(driver), "认出一短时还在等");
        return;
      }
      assertEquals(List.of(), acks.expired(now), "认出一短之前没过期");
    }
    fail("一直没认出一短");
  }

  @Test
  void aMissedAnswerCountsAsAMissedAcknowledgement() {
    SignalAcknowledge signals = new SignalAcknowledge();
    signals.recordMiss();
    assertEquals(1, signals.misses());
    signals.resetCounts();
    assertEquals(0, signals.misses());
  }
}
