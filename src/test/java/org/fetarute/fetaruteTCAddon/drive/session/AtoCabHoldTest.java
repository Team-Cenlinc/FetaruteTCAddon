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
  @DisplayName("终点站结算后原地等下一趟、还没放行：放行那一拍要调头（不必等看到待命登记）；放行后、开走后、没结算都不算")
  void awaitsTurnbackFromSettlementUntilReleased() {
    assertTrue(DriveSessionManager.awaitingTurnback(true, true, 0.0, false));
    assertTrue(DriveSessionManager.awaitingTurnback(true, true, 1.5, false), "停站时对位挪动一点仍算原地");
    assertFalse(DriveSessionManager.awaitingTurnback(true, true, 0.0, true), "已放行：调头标记已用掉");
    assertFalse(DriveSessionManager.awaitingTurnback(true, true, 30.0, false), "已经开走（例如接管后继续开往车库）");
    assertFalse(DriveSessionManager.awaitingTurnback(true, false, 0.0, false), "列车在动");
    assertFalse(DriveSessionManager.awaitingTurnback(false, true, 0.0, false), "还没结算");
  }

  @Test
  @DisplayName("终点站只认结算之后收到的放行许可：进站前留下的“允许前进”不算放行，停车许可也不算")
  void onlyReleasesReceivedAfterSettlementCount() {
    assertFalse(DriveSessionManager.releasedSince(true, 90L, 100L), "进站前收到的许可");
    assertTrue(DriveSessionManager.releasedSince(true, 100L, 100L));
    assertTrue(DriveSessionManager.releasedSince(true, 250L, 100L));
    assertFalse(DriveSessionManager.releasedSince(false, 250L, 100L), "结算后收到的是停车许可");
    assertFalse(DriveSessionManager.releasedSince(true, Long.MIN_VALUE, 100L), "还没收到过许可");
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
