package org.fetarute.fetaruteTCAddon.drive.driver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import org.fetarute.fetaruteTCAddon.drive.seat.CabSeats;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("终点站等接续下一趟时离座：引导到发车端")
class CabChangeWalkTest {

  private static final Instant T0 = Instant.parse("2026-10-06T08:00:00Z");

  private final CabChange change = new CabChange();

  private static CabChange.Input waiting(
      CabSeats.Departure predicted, CabSeats.End seat, boolean walkAllowed, long atSecond) {
    return new CabChange.Input(
        true,
        true,
        predicted,
        seat,
        false,
        T0.plusSeconds(atSecond),
        24L,
        T0.plusSeconds(300),
        false,
        0L,
        4,
        walkAllowed);
  }

  private static CabChange.Input released(CabSeats.End seat, long atSecond) {
    return new CabChange.Input(
        true,
        false,
        CabSeats.Departure.EITHER,
        seat,
        true,
        T0.plusSeconds(atSecond),
        24L,
        T0.plusSeconds(300),
        false,
        0L,
        4,
        true);
  }

  @Test
  @DisplayName("方向已知：离座不结束驾驶，提示到发车端；坐到另一端照样提示，坐进发车端才完成")
  void walksToKnownEnd() {
    assertEquals(
        CabChange.Event.WALK,
        change.tick(waiting(CabSeats.Departure.HEAD, CabSeats.End.NONE, true, 0)));
    assertTrue(change.allowsLeavingSeat());
    assertEquals(CabSeats.End.HEAD, change.target());
    assertFalse(change.eitherEnd());
    assertEquals(1, change.targetCar());

    assertEquals(
        CabChange.Event.NONE,
        change.tick(waiting(CabSeats.Departure.HEAD, CabSeats.End.TAIL, true, 5)),
        "坐到了另一端：继续提示");
    assertTrue(change.allowsLeavingSeat());
    assertEquals(
        CabChange.Event.COMPLETED,
        change.tick(waiting(CabSeats.Departure.HEAD, CabSeats.End.HEAD, true, 10)));
    assertFalse(change.allowsLeavingSeat());

    assertEquals(
        CabChange.Event.WALK,
        change.tick(waiting(CabSeats.Departure.HEAD, CabSeats.End.NONE, true, 20)),
        "等接续期间再离座：再次引导，不按离岗处理");
  }

  @Test
  @DisplayName("方向未定：两端驾驶室都可以先坐下等候；放行后坐在后面那一端要换过去，不能从后端开车")
  void waitsAtEitherEndUntilReleased() {
    assertEquals(
        CabChange.Event.WALK,
        change.tick(waiting(CabSeats.Departure.EITHER, CabSeats.End.NONE, true, 0)));
    assertTrue(change.eitherEnd());
    assertEquals(0, change.targetCar());
    assertEquals(
        CabChange.Event.COMPLETED,
        change.tick(waiting(CabSeats.Departure.EITHER, CabSeats.End.TAIL, true, 5)),
        "任一端驾驶室都算坐好");

    assertEquals(
        CabChange.Event.STARTED,
        change.tick(released(CabSeats.End.TAIL, 60)),
        "放行后坐在车尾端：开始换端计时（牵引封锁）");
    assertTrue(change.holding());
    assertEquals(CabSeats.End.HEAD, change.target());
    assertEquals(CabChange.Event.COMPLETED, change.tick(released(CabSeats.End.HEAD, 70)));
  }

  @Test
  @DisplayName("离座后到放行都没坐下：放行时按换端计时处理，目标是车头端")
  void releaseWhileWalkingStartsTimer() {
    change.tick(waiting(CabSeats.Departure.EITHER, CabSeats.End.NONE, true, 0));
    assertEquals(CabChange.Event.STARTED, change.tick(released(CabSeats.End.NONE, 60)));
    assertEquals(CabSeats.End.HEAD, change.target());
    assertEquals(1, change.targetCar());
  }

  @Test
  @DisplayName("不在等接续：沿用原来的规则，方向不是车尾端时离座不引导（按离岗处理）")
  void withoutContinuationLeavingIsNotGuided() {
    assertEquals(
        CabChange.Event.NONE,
        change.tick(waiting(CabSeats.Departure.HEAD, CabSeats.End.NONE, false, 0)));
    assertFalse(change.allowsLeavingSeat());
    assertEquals(
        CabChange.Event.ANNOUNCED,
        change.tick(waiting(CabSeats.Departure.TAIL, CabSeats.End.HEAD, false, 5)),
        "尽头式照常提前告知");
  }

  @Test
  @DisplayName("驾驶座没有标记：方向未定时坐进任一端都要确认，方向已知时只有发车端要确认")
  void unmarkedSeatConfirmFollowsTarget() {
    assertTrue(
        CabChange.awaitsSeatConfirm(
            false, true, true, CabSeats.End.HEAD, false, CabSeats.End.NONE));
    assertTrue(
        CabChange.awaitsSeatConfirm(
            false, true, true, CabSeats.End.TAIL, false, CabSeats.End.NONE));
    assertFalse(
        CabChange.awaitsSeatConfirm(false, true, true, CabSeats.End.NONE, false, CabSeats.End.NONE),
        "客室座位不算");
    assertTrue(
        CabChange.awaitsSeatConfirm(
            false, true, true, CabSeats.End.HEAD, false, CabSeats.End.HEAD));
    assertFalse(
        CabChange.awaitsSeatConfirm(false, true, true, CabSeats.End.TAIL, false, CabSeats.End.HEAD),
        "坐到了另一端：不是要坐的那一端，不问确认");
  }
}
