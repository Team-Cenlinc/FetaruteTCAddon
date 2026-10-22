package org.fetarute.fetaruteTCAddon.coupler;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 挂上后车站牌子再触发：主干开进来时第一次停站、其余重复；主干先到时都是重复；别的股道、窗口外照常。 */
class LinkedStopsTest {

  private static final NodeId HHU1 = NodeId.of("SURC:S:HHU:1");
  private static final NodeId HHU2 = NodeId.of("SURC:S:HHU:2");

  @Test
  @DisplayName("回程支线车先到：挂上后第一次触发停站，紧接着的重复触发跳过")
  void trunkPushedInStopsOnce() {
    LinkedStops stops = new LinkedStops();
    stops.record("surc-mt-lp-1178", HHU1, false, 1000L);

    assertEquals(
        LinkedStops.Arrival.FIRST_STOP,
        stops.take("surc-mt-lp-1178", HHU1, 1001L, () -> true),
        "开进来的是主干：它在这一站没停过，不看发车许可锁");
    assertEquals(
        LinkedStops.Arrival.DUPLICATE,
        stops.take("surc-mt-lp-1178", NodeId.of("surc:s:hhu:1"), 1001L, () -> false),
        "我们重新触发与 TrainCarts 重新检测牌子各来一次；节点不分大小写");
  }

  @Test
  @DisplayName("主干先到：它的停站还没放行就让它继续，挂上后的触发都跳过；停站已经结束时重新停一次")
  void feederPushedInLeavesARunningTrunkStopAlone() {
    LinkedStops running = new LinkedStops();
    running.record("surc-mt-lp-2806", HHU1, true, 1000L);
    assertEquals(
        LinkedStops.Arrival.DUPLICATE, running.take("surc-mt-lp-2806", HHU1, 1001L, () -> true));
    assertEquals(
        LinkedStops.Arrival.DUPLICATE,
        running.take("surc-mt-lp-2806", HHU1, 1002L, () -> false),
        "只问第一次");

    LinkedStops ended = new LinkedStops();
    ended.record("surc-mt-lp-2806", HHU1, true, 1000L);
    assertEquals(
        LinkedStops.Arrival.FIRST_STOP, ended.take("surc-mt-lp-2806", HHU1, 1001L, () -> false));
    assertEquals(
        LinkedStops.Arrival.DUPLICATE, ended.take("surc-mt-lp-2806", HHU1, 1001L, () -> false));
  }

  @Test
  @DisplayName("别的车、别的股道、窗口外的触发照常停站")
  void unrelatedTriggersAreOrdinaryStops() {
    LinkedStops stops = new LinkedStops();
    stops.record("surc-mt-lp-1178", HHU1, false, 1000L);

    assertEquals(LinkedStops.Arrival.NONE, stops.take("surc-mt-lp-2806", HHU1, 1001L, () -> false));
    assertEquals(LinkedStops.Arrival.NONE, stops.take("surc-mt-lp-1178", HHU2, 1001L, () -> false));
    assertEquals(
        LinkedStops.Arrival.NONE,
        stops.take("surc-mt-lp-1178", HHU1, 1000L + LinkedStops.WINDOW_TICKS + 1, () -> false),
        "隔开窗口（下一趟回到这一站）照常停");
  }
}
