package org.fetarute.fetaruteTCAddon.drive.guard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

  @Test
  void aMissedAnswerCountsAsAMissedAcknowledgement() {
    SignalAcknowledge signals = new SignalAcknowledge();
    signals.recordMiss();
    assertEquals(1, signals.misses());
    signals.resetCounts();
    assertEquals(0, signals.misses());
  }
}
