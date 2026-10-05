package org.fetarute.fetaruteTCAddon.drive.dynamics;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("紧急制动防误触")
class NotchSelectorEbGraceTest {

  private static final long GRACE = 20L;

  @Test
  @DisplayName("选到 EB 立即生效，宽限内回拨到制动档或 N 即撤销，不必停稳")
  void backOutWithinGrace() {
    NotchSelector selector = new NotchSelector();
    selector.select(Notch.B4.slot(), false, 100L, GRACE);

    assertEquals(Notch.EB, selector.select(Notch.EB.slot(), false, 100L, GRACE).notch());
    assertEquals(15L, selector.ebGraceRemaining(105L));
    assertEquals(Notch.B4, selector.select(Notch.B4.slot(), false, 110L, GRACE).notch());
    assertEquals(0L, selector.ebGraceRemaining(110L));
  }

  @Test
  @DisplayName("误按 9 也一样：宽限内回到 N 撤销；回绕到牵引档按 N 处理")
  void accidentalKeyNine() {
    NotchSelector selector = new NotchSelector();
    selector.select(Notch.P2.slot(), false, 0L, GRACE);
    selector.select(Notch.EB.slot(), false, 0L, GRACE);

    NotchSelector.Selection wrapped = selector.select(Notch.P3.slot(), false, 5L, GRACE);
    assertEquals(Notch.N, wrapped.notch());
    assertEquals(Notch.N.slot(), wrapped.correctedSlot());
  }

  @Test
  @DisplayName("停满宽限才锁定，之后行驶中离不开 EB")
  void latchesAfterGrace() {
    NotchSelector selector = new NotchSelector();
    selector.select(Notch.EB.slot(), false, 0L, GRACE);

    NotchSelector.Selection held = selector.select(Notch.B2.slot(), false, GRACE, GRACE);
    assertEquals(Notch.EB, held.notch());
    assertEquals(Notch.EB.slot(), held.correctedSlot());
    assertEquals(Notch.N, selector.select(Notch.N.slot(), true, GRACE + 40L, GRACE).notch());
  }

  @Test
  @DisplayName("防护、警惕装置施加的 EB 立即锁定，没有宽限")
  void forcedEmergencyHasNoGrace() {
    NotchSelector selector = new NotchSelector();
    selector.select(Notch.EB.slot(), false, 0L, GRACE);
    selector.force(Notch.EB);

    assertEquals(0L, selector.ebGraceRemaining(1L));
    assertEquals(Notch.EB, selector.select(Notch.B4.slot(), false, 2L, GRACE).notch());
  }

  @Test
  @DisplayName("宽限设为 0 时立即锁定（旧行为）")
  void zeroGraceLatchesImmediately() {
    NotchSelector selector = new NotchSelector();
    selector.select(Notch.EB.slot(), false, 0L, 0L);

    assertEquals(Notch.EB, selector.select(Notch.B4.slot(), false, 1L, 0L).notch());
  }
}
