package org.fetarute.fetaruteTCAddon.coupler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

/** 同股道列车对写在列车标签上，重启后据此找回。 */
class CouplerPairTagTest {

  @Test
  void theTagRoundTripsThePartnerAndTheAwayDirection() {
    String raw = CouplerService.formatTag("LINE-A-0291", new Vector(0.6, 0, -0.8));

    CouplerService.PairTag tag = CouplerService.PairTag.parse(raw).orElseThrow();

    assertEquals("LINE-A-0291", tag.partner());
    assertEquals(0.6, tag.away().getX(), 1e-4);
    assertEquals(-0.8, tag.away().getZ(), 1e-4);
  }

  @Test
  void brokenTagsAreIgnored() {
    assertTrue(CouplerService.PairTag.parse(null).isEmpty());
    assertTrue(CouplerService.PairTag.parse("no-bar").isEmpty());
    assertTrue(CouplerService.PairTag.parse("T|1").isEmpty());
    assertTrue(CouplerService.PairTag.parse("T|a,b").isEmpty());
    assertTrue(CouplerService.PairTag.parse("T|0,0").isEmpty(), "没有方向");
  }
}
