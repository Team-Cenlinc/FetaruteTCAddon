package org.fetarute.fetaruteTCAddon.drive.guard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("夹人夹物演练：再开车门与报告都做了才算处置完成，过了时限算没处置")
class GuardDrillTest {

  @Test
  @DisplayName("两项先后不限，都做了才算处置完成，处置完成后不再过期")
  void bothStepsInAnyOrder() {
    GuardDrill drill = new GuardDrill("A", 100L, 15);
    drill.noteReported();
    assertFalse(drill.handled(), "只报告了");
    drill.noteReopened();
    assertTrue(drill.handled());
    assertFalse(drill.expired(100L + 15 * 20L + 100L));
    assertEquals(2.5, drill.seconds(150L));
  }

  @Test
  @DisplayName("时限到时还差一项即过期；剩余秒数向上取整、不为负")
  void expiresAtTheDeadline() {
    GuardDrill drill = new GuardDrill("A", 0L, 15);
    drill.noteReopened();
    assertFalse(drill.expired(299L));
    assertTrue(drill.expired(300L));
    assertEquals(15L, drill.secondsLeft(0L));
    assertEquals(1L, drill.secondsLeft(299L));
    assertEquals(0L, drill.secondsLeft(400L));
  }
}
