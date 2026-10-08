package org.fetarute.fetaruteTCAddon.drive.driver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.util.Vector;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.DriverStationStop;
import org.fetarute.fetaruteTCAddon.drive.seat.CabSeats;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("终点站开门后换端与确认座位")
class CabChangeTerminalTest {

  private static final Instant T0 = Instant.parse("2026-10-04T08:00:00Z");

  private static CabChange.Input before(CabSeats.End seat, long atSecond) {
    return new CabChange.Input(
        true,
        true,
        CabSeats.Departure.TAIL,
        seat,
        false,
        T0.plusSeconds(atSecond),
        24L,
        null,
        false,
        0L,
        3,
        false);
  }

  private static DriverStationStop stop(String node) {
    return new DriverStationStop(
        NodeId.of(node), "站", UUID.randomUUID(), new Vector(), null, false, true);
  }

  @Test
  @DisplayName("放行前：转入待命，或终点站开门后还没放行")
  void preReleaseRule() {
    assertTrue(CabChange.preRelease(true, false, false), "转入待命");
    assertTrue(CabChange.preRelease(true, true, false), "待命里拿到许可的那一拍仍按待命（放行时取走调头标记）");
    assertTrue(CabChange.preRelease(false, false, true), "终点站开门后");
    assertFalse(CabChange.preRelease(false, true, true), "终点站已放行：按放行后处理，免得调头后把车尾当成目标端");
    assertFalse(CabChange.preRelease(false, false, false));
  }

  @Test
  @DisplayName("确认座位：只对没有标记驾驶座的列车、换端中坐进要换到的那一端")
  void seatConfirmRule() {
    assertTrue(
        CabChange.awaitsSeatConfirm(
            false, true, true, CabSeats.End.TAIL, false, CabSeats.End.TAIL));
    assertTrue(
        CabChange.awaitsSeatConfirm(
            false, false, true, CabSeats.End.TAIL, false, CabSeats.End.TAIL),
        "告知前已坐到车尾");
    assertTrue(
        CabChange.awaitsSeatConfirm(
            false, true, false, CabSeats.End.HEAD, false, CabSeats.End.HEAD),
        "放行后计时中");
    assertFalse(
        CabChange.awaitsSeatConfirm(true, true, true, CabSeats.End.TAIL, false, CabSeats.End.TAIL),
        "有标记的列车不用确认");
    assertFalse(
        CabChange.awaitsSeatConfirm(false, true, true, CabSeats.End.TAIL, true, CabSeats.End.TAIL),
        "已确认");
    assertFalse(
        CabChange.awaitsSeatConfirm(false, true, true, CabSeats.End.HEAD, false, CabSeats.End.TAIL),
        "还坐在原来那一端");
    assertFalse(
        CabChange.awaitsSeatConfirm(
            false, false, false, CabSeats.End.HEAD, false, CabSeats.End.HEAD),
        "不换端时每站发车都不用确认");
  }

  @Test
  @DisplayName("发车端预测：开门时算一次，转入待命时再算一次，其余沿用")
  void predictionRecomputedOnLayover() {
    CabChange change = new CabChange();
    int[] calls = {0};
    java.util.function.Supplier<CabSeats.Departure> either =
        () -> {
          calls[0]++;
          return CabSeats.Departure.EITHER;
        };
    assertEquals(CabSeats.Departure.EITHER, change.prediction(true, false, either));
    change.prediction(true, false, either);
    assertEquals(1, calls[0], "停站期间沿用");
    assertEquals(
        CabSeats.Departure.TAIL, change.prediction(true, true, () -> CabSeats.Departure.TAIL));
    assertEquals(CabSeats.Departure.TAIL, change.prediction(true, true, either), "待命期间沿用转入待命时算的");
    assertEquals(1, calls[0]);
  }

  @Test
  @DisplayName("没确认前坐在车尾也不算完成，确认后才完成")
  void completesOnlyAfterConfirm() {
    CabChange change = new CabChange();
    assertEquals(CabChange.Event.ANNOUNCED, change.tick(before(CabSeats.End.HEAD, 0)));
    assertEquals(CabChange.Event.NONE, change.tick(before(CabSeats.End.NONE, 5)), "开着门走向另一端");
    // 坐进车尾但没确认：驾驶侧按“还没坐好”交 NONE。
    assertEquals(CabChange.Event.NONE, change.tick(before(CabSeats.End.NONE, 12)));
    assertEquals(CabChange.Stage.ANNOUNCED, change.stage());
    assertTrue(change.allowsLeavingSeat());
    assertEquals(CabChange.Event.COMPLETED, change.tick(before(CabSeats.End.TAIL, 15)));
  }

  @Test
  @DisplayName("终点站停站认定：开门后到等待发车都算，换了一次停站就不算")
  void terminalStopLatch() {
    DriverLink link = new DriverLink(UUID.randomUUID(), "T", null, () -> 0.0, () -> 0L);
    DriverStationStop terminal = stop("OP:S:END:1");
    link.beginStationStop(terminal);
    link.markTerminalStop(terminal);
    assertFalse(link.atTerminalStop(), "还在进站");
    assertEquals(Optional.of(terminal), link.terminalStopNow());
    terminal.setPhase(DriverStationStop.Phase.OPEN_DOORS);
    assertFalse(link.atTerminalStop(), "等开门");
    terminal.setPhase(DriverStationStop.Phase.DWELL);
    assertTrue(link.atTerminalStop());
    terminal.setPhase(DriverStationStop.Phase.CLOSE_DOORS);
    assertTrue(link.atTerminalStop());
    terminal.setPhase(DriverStationStop.Phase.WAIT_DEPARTURE);
    assertTrue(link.atTerminalStop());

    DriverStationStop next = stop("OP:S:MID:1");
    link.beginStationStop(next);
    next.setPhase(DriverStationStop.Phase.DWELL);
    assertFalse(link.atTerminalStop(), "没认定为终点站的停站");
  }

  @Test
  @DisplayName("接续的下一趟同一趟只告诉一次，换了一趟再告诉")
  void nextTripAnnouncedOnce() {
    DriverLink link = new DriverLink(UUID.randomUUID(), "T", null, () -> 0.0, () -> 0L);
    assertTrue(link.takeNextTripAnnouncement().isEmpty());
    DriverNextTrip first = new DriverNextTrip(UUID.randomUUID(), "MT-086", "壑湖", T0);
    link.setNextTrip(first);
    assertEquals(Optional.of(first), link.takeNextTripAnnouncement());
    assertTrue(link.takeNextTripAnnouncement().isEmpty());
    link.setNextTrip(new DriverNextTrip(first.tripId(), "MT-086", "壑湖", T0.plusSeconds(60)));
    assertTrue(link.takeNextTripAnnouncement().isEmpty(), "同一趟改了时刻不再重复告诉");
    DriverNextTrip second = new DriverNextTrip(UUID.randomUUID(), "MT-087", "港", T0);
    link.setNextTrip(second);
    assertEquals(Optional.of(second), link.takeNextTripAnnouncement());
  }
}
