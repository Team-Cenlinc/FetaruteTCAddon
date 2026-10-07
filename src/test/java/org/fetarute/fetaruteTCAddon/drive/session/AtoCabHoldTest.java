package org.fetarute.fetaruteTCAddon.drive.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import org.fetarute.fetaruteTCAddon.drive.driver.CabChange;
import org.fetarute.fetaruteTCAddon.drive.seat.CabSeats;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ATO 折返换端：扣车何时开始、何时解除")
class AtoCabHoldTest {

  private static final Instant T0 = Instant.parse("2026-10-07T08:00:00Z");

  private static CabChange.Input input(
      boolean preRelease,
      CabSeats.Departure predicted,
      CabSeats.End seat,
      boolean released,
      long atSecond) {
    return new CabChange.Input(
        true,
        preRelease,
        predicted,
        seat,
        released,
        T0.plusSeconds(atSecond),
        24L,
        T0.plusSeconds(60),
        false,
        0L,
        4,
        true);
  }

  @Test
  @DisplayName("终点站结算后、转入待命前：ATO 不扣车但照常引导；已扣车、正驾驶任务中或列车在动都不算")
  void waitingAtTerminalBeforeLayover() {
    assertTrue(DriveSessionManager.atoWaitingAtTerminal(true, false, true, true));
    assertFalse(DriveSessionManager.atoWaitingAtTerminal(false, false, true, true), "人工驾驶另有停站对象");
    assertFalse(DriveSessionManager.atoWaitingAtTerminal(true, true, true, true), "已在扣车：按扣车处理");
    assertFalse(DriveSessionManager.atoWaitingAtTerminal(true, false, false, true), "还没结算");
    assertFalse(DriveSessionManager.atoWaitingAtTerminal(true, false, true, false), "列车在动");
  }

  @Test
  @DisplayName("待命时坐在车头：放行调头后坐在车尾，计时换端期间一直扣着，坐进车头端才解除")
  void holdLastsUntilTheDriverSitsAtTheDepartureEnd() {
    CabChange change = new CabChange();

    // 待命（派车没放行）：方向已知是车尾端，驾驶员仍坐在此刻的车头。
    change.tick(input(true, CabSeats.Departure.TAIL, CabSeats.End.HEAD, false, 0));
    assertTrue(DriveSessionManager.keepCabHold(true, change.stage()));

    // 放行那一拍按发车方向调头：原来的车头成了车尾，开始计时换端。
    assertEquals(
        CabChange.Event.STARTED,
        change.tick(input(false, CabSeats.Departure.EITHER, CabSeats.End.TAIL, true, 10)));
    assertTrue(DriveSessionManager.keepCabHold(false, change.stage()), "换端没完成：继续扣着");

    // 坐进车头端驾驶室：换端完成，解除扣车交回 ATO。
    assertEquals(
        CabChange.Event.COMPLETED,
        change.tick(input(false, CabSeats.Departure.EITHER, CabSeats.End.HEAD, true, 20)));
    assertFalse(DriveSessionManager.keepCabHold(false, change.stage()));
  }

  @Test
  @DisplayName("待命时已坐在发车端：放行后不用换端，当拍就解除")
  void noCabChangeReleasesAtOnce() {
    CabChange change = new CabChange();
    change.tick(input(true, CabSeats.Departure.TAIL, CabSeats.End.TAIL, false, 0));
    assertTrue(DriveSessionManager.keepCabHold(true, change.stage()), "还在待命");

    change.tick(input(false, CabSeats.Departure.EITHER, CabSeats.End.HEAD, true, 10));
    assertEquals(CabChange.Stage.IDLE, change.stage());
    assertFalse(DriveSessionManager.keepCabHold(false, change.stage()));
  }
}
