package org.fetarute.fetaruteTCAddon.drive.driver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.OptionalLong;
import java.util.UUID;
import org.bukkit.util.Vector;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.DriverStationStop;
import org.fetarute.fetaruteTCAddon.drive.driver.score.TaskScore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("终点站结算：本趟成绩交出、下一趟从零计")
class DriverLinkSettleTripTest {

  /** 已停稳对标的停站（只有停稳的停站才记成绩）。 */
  private static DriverStationStop stop(String node) {
    DriverStationStop stop =
        new DriverStationStop(
            NodeId.of(node), "站", UUID.randomUUID(), new Vector(), null, false, true);
    stop.updateOffset(0.5);
    stop.markStopped();
    return stop;
  }

  @Test
  @DisplayName("正在停的终点站记进本趟，停站不被摘掉，结束时也不再记进下一趟")
  void terminalStopBelongsToSettledTrip() {
    DriverLink link = new DriverLink(UUID.randomUUID(), "T", null, () -> 0.0, () -> 0L);
    DriverStationStop middle = stop("OP:S:MID:1");
    link.beginStationStop(middle);
    middle.setPhase(DriverStationStop.Phase.DWELL);
    middle.end();
    link.stationStop();
    DriverStationStop terminal = stop("OP:S:END:1");
    link.beginStationStop(terminal);
    terminal.setPhase(DriverStationStop.Phase.DWELL);
    link.score().setDelayAtStart(OptionalLong.of(5L));

    TaskScore settled = link.settleTrip();
    assertEquals(2, settled.stopCount(), "中途站与正在停的终点站");
    assertEquals(OptionalLong.of(5L), settled.delayAtStartSeconds());
    assertSame(terminal, link.stationStop().orElseThrow(), "终点站停站照常进行，换端与停站显示还要用");
    assertEquals(0, link.score().stopCount());
    assertTrue(link.score().delayAtStartSeconds().isEmpty(), "下一趟的起始晚点等记成任务后再记");
    assertEquals(0, link.announcedStops());

    terminal.setPhase(DriverStationStop.Phase.WAIT_DEPARTURE);
    terminal.end();
    link.stationStop();
    assertEquals(0, link.score().stopCount(), "终点站已记进上一趟");

    DriverStationStop next = stop("OP:S:NEXT:1");
    link.beginStationStop(next);
    next.setPhase(DriverStationStop.Phase.DWELL);
    next.end();
    link.stationStop();
    assertEquals(1, link.score().stopCount(), "下一趟的停站照常记");
  }
}
