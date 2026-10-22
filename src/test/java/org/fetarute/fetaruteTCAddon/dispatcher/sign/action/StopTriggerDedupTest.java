package org.fetarute.fetaruteTCAddon.dispatcher.sign.action;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.junit.jupiter.api.Test;

/** 联挂合并编组时同一站台被触发两次：只开一个停站；下一站、别的车、隔了很久的再次进站照常。 */
class StopTriggerDedupTest {

  private static final NodeId HHU1 = NodeId.of("SURC:S:HHU:1");
  private static final NodeId ZKW1 = NodeId.of("SURC:S:ZKW:1");

  @Test
  void aSecondTriggerAtTheSamePlatformMomentsLaterIsADuplicate() {
    StopTriggerDedup dedup = new StopTriggerDedup();

    assertTrue(dedup.claim("SURC-MT-LP-1178", HHU1, 1000L));
    assertFalse(dedup.claim("SURC-MT-LP-1178", HHU1, 1001L), "挂上后的重新触发晚一拍：重复");
    assertFalse(
        dedup.claim("surc-mt-lp-1178", HHU1, 1000L + StopTriggerDedup.WINDOW_TICKS), "车名不分大小写");
  }

  @Test
  void otherPlatformsOtherTrainsAndLaterArrivalsAreNewStops() {
    StopTriggerDedup dedup = new StopTriggerDedup();
    assertTrue(dedup.claim("SURC-MT-LP-1178", HHU1, 1000L));

    assertTrue(dedup.claim("SURC-MT-LP-1178", ZKW1, 1002L), "下一站不是重复（相邻两站的站牌可以挨得很近）");
    assertTrue(dedup.claim("SURC-MT-LP-2806", HHU1, 1002L), "别的车不是重复");
    assertTrue(
        dedup.claim("SURC-MT-LP-1178", HHU1, 1002L + StopTriggerDedup.WINDOW_TICKS + 1),
        "隔开窗口再次进站（下一趟回到这一站）照常停");
  }
}
