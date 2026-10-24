package org.fetarute.fetaruteTCAddon.display.pids.map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 一次右键会触发主手、副手两次点击：同一玩家同一 tick 只认第一次，别的玩家与下一 tick 照常。 */
class PidsClickDedupeTest {

  @Test
  void onlyTheFirstClickOfAPlayerInATickCounts() {
    PidsClickDedupe clicks = new PidsClickDedupe();
    UUID alice = UUID.randomUUID();
    UUID bob = UUID.randomUUID();

    assertTrue(clicks.first(alice, 100));
    assertFalse(clicks.first(alice, 100), "副手那一次不算");
    assertTrue(clicks.first(bob, 100), "别的玩家同一 tick 照常");
    assertTrue(clicks.first(alice, 101), "下一 tick 照常");
    assertFalse(clicks.first(alice, 101));
    assertTrue(clicks.first(bob, 101));
  }
}
