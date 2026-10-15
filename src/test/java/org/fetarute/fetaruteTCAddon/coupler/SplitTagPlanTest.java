package org.fetarute.fetaruteTCAddon.coupler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.RouteProgressRegistry;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.TrainCartsTimetableDutyStore;
import org.junit.jupiter.api.Test;

/** 拆下的那一半：换新身份，去掉只属于原车这一趟的标记，交路与进度照搬。 */
class SplitTagPlanTest {

  private static final UUID RUN = UUID.fromString("00000000-0000-0000-0000-000000000123");

  @Test
  void theDetachedHalfGetsANewIdentityAndKeepsItsRoute() {
    SplitTagPlan plan = SplitTagPlan.forDetached("LINE-A-0291", RUN);

    assertEquals("LINE-A-0291", plan.writes().get(RouteProgressRegistry.TAG_TRAIN_NAME));
    assertEquals(RUN.toString(), plan.writes().get(SplitTagPlan.TAG_RUN_ID));
    assertEquals(RUN.toString(), plan.writes().get("FTA_TRAIN_UID"));
    assertTrue(plan.removals().contains(TrainCartsTimetableDutyStore.TAG_DUTY), "时刻表下一班归原车");
    assertTrue(plan.removals().contains("FTA_CALL"));
    assertTrue(plan.removals().contains("FTA_EARLY_HOLD"));
    assertTrue(plan.removals().contains("FTA_DRIVER"));
    assertTrue(plan.removals().contains(CouplerService.TAG_CO_LOCATED));
    assertFalse(plan.removals().contains(RouteProgressRegistry.TAG_ROUTE_ID), "交路照搬");
    assertFalse(plan.removals().contains(RouteProgressRegistry.TAG_ROUTE_INDEX), "进度照搬");
    assertFalse(plan.removals().contains("FTA_OP_TRIPS"), "圈数照搬");
    assertFalse(plan.removals().contains(RouteProgressRegistry.TAG_TRAIN_NAME), "车名是写不是删");
  }

  @Test
  void theDetachedNameKeepsThePrefixAndRedrawsTheSequence() {
    String name = SplitTagPlan.detachedName("LINE-A-1234", RUN);

    assertTrue(name.startsWith("LINE-A-"), name);
    assertEquals("LINE-A-".length() + 4, name.length());
    assertEquals(name, SplitTagPlan.detachedName("LINE-A-1234", RUN), "同一运行 ID 同一序号");
    assertTrue(SplitTagPlan.detachedName("MyTrain", RUN).startsWith("MyTrain-"), "不是出车格式时加序号");
    assertTrue(SplitTagPlan.detachedName(" ", RUN).startsWith("TRAIN-"));
  }
}
