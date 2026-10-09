package org.fetarute.fetaruteTCAddon.drive.guard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.fetarute.fetaruteTCAddon.drive.driver.CabChange;
import org.fetarute.fetaruteTCAddon.drive.seat.CabSeatKey;
import org.fetarute.fetaruteTCAddon.drive.seat.CabSeats;
import org.fetarute.fetaruteTCAddon.drive.seat.CabSeats.Departure;
import org.fetarute.fetaruteTCAddon.drive.seat.CabSeats.End;
import org.junit.jupiter.api.Test;

/** 车掌终点站换端：放行前告知不计时，放行后计时，坐进另一头即完成，超时或开车由调用方直接送过去。 */
class GuardCabChangeTest {

  private static final long RESERVE = 400L;

  private static GuardCabChange.Input input(
      Departure departure, boolean predicted, End reserved, End seated, long now) {
    return input(departure, predicted, reserved, seated, now, true);
  }

  private static GuardCabChange.Input input(
      Departure departure, boolean predicted, End reserved, End seated, long now, boolean stopped) {
    return new GuardCabChange.Input(
        true,
        stopped,
        new GuardCabChange.Outlook(departure, predicted),
        reserved,
        seated,
        now,
        RESERVE);
  }

  @Test
  void runningAtTheTailNeedsNothing() {
    GuardCabChange change = new GuardCabChange();
    assertEquals(
        GuardCabChange.Event.NONE,
        change.tick(input(Departure.HEAD, false, End.TAIL, End.TAIL, 0L)));
    assertFalse(change.changing());
  }

  /** 尽头式终点站：放行前已知下一趟由车掌这一端发车，提前告知、不计时；坐进车头端即完成。 */
  @Test
  void aForecastTurnbackIsAnnouncedWithoutATimer() {
    GuardCabChange change = new GuardCabChange();
    assertEquals(
        GuardCabChange.Event.ANNOUNCED,
        change.tick(input(Departure.TAIL, true, End.TAIL, End.TAIL, 0L)));
    assertTrue(change.changing());
    assertEquals(End.HEAD, change.target());
    assertEquals(-1L, change.remainingTicks(0L));
    assertEquals(
        GuardCabChange.Event.NONE,
        change.tick(input(Departure.TAIL, true, End.TAIL, End.NONE, 100_000L)),
        "放行前不会超时");
    assertEquals(
        GuardCabChange.Event.COMPLETED,
        change.tick(input(Departure.TAIL, true, End.TAIL, End.HEAD, 100_001L)));
    assertFalse(change.changing());
  }

  /** 放行时列车调头：车掌预留的座位到了车头端，要在换端时间预留内坐进车尾端。 */
  @Test
  void afterTheReversalTheGuardHasTheReserveToReachTheTail() {
    GuardCabChange change = new GuardCabChange();
    assertEquals(
        GuardCabChange.Event.STARTED,
        change.tick(input(Departure.HEAD, false, End.HEAD, End.HEAD, 1000L)));
    assertEquals(End.TAIL, change.target());
    assertEquals(RESERVE, change.remainingTicks(1000L));
    assertEquals(
        GuardCabChange.Event.NONE,
        change.tick(input(Departure.HEAD, false, End.HEAD, End.NONE, 1000L + RESERVE - 1)));
    assertEquals(
        GuardCabChange.Event.TIMED_OUT,
        change.tick(input(Departure.HEAD, false, End.HEAD, End.NONE, 1000L + RESERVE)));
    change.finish();
    assertEquals(
        GuardCabChange.Event.NONE,
        change.tick(input(Departure.HEAD, false, End.TAIL, End.TAIL, 1000L + RESERVE + 1)),
        "直接送过去后不再报完成");
  }

  /** 人工驾驶的驾驶员先开车了：车掌还没换好，不再等时限，直接送过去。 */
  @Test
  void theTrainStartingCutsTheChangeShort() {
    GuardCabChange change = new GuardCabChange();
    change.tick(input(Departure.HEAD, false, End.HEAD, End.NONE, 0L));
    assertEquals(
        GuardCabChange.Event.TIMED_OUT,
        change.tick(input(Departure.HEAD, false, End.HEAD, End.NONE, 1L, false)));
  }

  @Test
  void anAnnouncementTurnsIntoATimedChangeOnRelease() {
    GuardCabChange change = new GuardCabChange();
    change.tick(input(Departure.TAIL, true, End.TAIL, End.TAIL, 0L));
    // 放行调头后：原来的车尾端成了车头端。
    assertEquals(
        GuardCabChange.Event.STARTED,
        change.tick(input(Departure.HEAD, false, End.HEAD, End.HEAD, 50L)));
    assertEquals(GuardCabChange.Stage.ACTIVE, change.stage());
  }

  /** 预留由调用方改到了另一头（驾驶员换端时一起送）：换端算完成。 */
  @Test
  void aReservationMovedElsewhereCompletesTheChange() {
    GuardCabChange change = new GuardCabChange();
    change.tick(input(Departure.TAIL, true, End.TAIL, End.TAIL, 0L));
    assertEquals(
        GuardCabChange.Event.COMPLETED,
        change.tick(input(Departure.TAIL, true, End.HEAD, End.HEAD, 1L)));
  }

  @Test
  void anUnknownDirectionOrASingleCarCancels() {
    GuardCabChange change = new GuardCabChange();
    change.tick(input(Departure.TAIL, true, End.TAIL, End.TAIL, 0L));
    assertEquals(
        GuardCabChange.Event.CANCELLED,
        change.tick(input(Departure.EITHER, true, End.TAIL, End.TAIL, 1L)));
    assertEquals(
        GuardCabChange.Event.NONE,
        change.tick(input(Departure.EITHER, true, End.TAIL, End.TAIL, 2L)));
    change.tick(input(Departure.TAIL, true, End.TAIL, End.TAIL, 3L));
    assertEquals(
        GuardCabChange.Event.CANCELLED,
        change.tick(
            new GuardCabChange.Input(
                false,
                true,
                new GuardCabChange.Outlook(Departure.TAIL, true),
                End.TAIL,
                End.TAIL,
                4L,
                RESERVE)));
  }

  /** 预计的发车端只在停着时作数：列车开动了，告知作废。 */
  @Test
  void aForecastLapsesOnceTheTrainMoves() {
    GuardCabChange change = new GuardCabChange();
    change.tick(input(Departure.TAIL, true, End.TAIL, End.TAIL, 0L));
    assertEquals(
        GuardCabChange.Event.CANCELLED,
        change.tick(input(Departure.TAIL, true, End.TAIL, End.TAIL, 1L, false)));
  }

  @Test
  void endsFollowTheCarPosition() {
    assertEquals(End.HEAD, GuardCabChange.endOfCar(0, 4));
    assertEquals(End.TAIL, GuardCabChange.endOfCar(3, 4));
    assertEquals(End.NONE, GuardCabChange.endOfCar(2, 4));
    assertEquals(End.HEAD, GuardCabChange.endOfCar(1, 4));
    assertEquals(End.NONE, GuardCabChange.endOfCar(0, 1), "单节车不分两端");
    assertEquals(End.TAIL, GuardCabChange.guardEnd(Departure.HEAD));
    assertEquals(End.HEAD, GuardCabChange.guardEnd(Departure.TAIL));
    assertEquals(End.NONE, GuardCabChange.guardEnd(Departure.EITHER));
    assertEquals(End.HEAD, GuardCabChange.opposite(End.TAIL));
    assertEquals(4, GuardCabChange.targetCar(End.TAIL, 4));
    assertEquals(1, GuardCabChange.targetCar(End.HEAD, 4));
  }

  /** 有驾驶员时跟驾驶员：换端时按他要换到的那一端，不换端时按他坐的车厢，终点站待放行算预计。 */
  @Test
  void theDriverSetsTheDepartureEnd() {
    assertEquals(
        new GuardCabChange.Outlook(Departure.TAIL, true),
        GuardCabChange.fromDriver(CabChange.Stage.ANNOUNCED, false, End.TAIL, true, 0, 4));
    assertEquals(
        new GuardCabChange.Outlook(Departure.EITHER, true),
        GuardCabChange.fromDriver(CabChange.Stage.ANNOUNCED, true, End.NONE, true, 0, 4));
    assertEquals(
        new GuardCabChange.Outlook(Departure.HEAD, false),
        GuardCabChange.fromDriver(CabChange.Stage.ACTIVE, false, End.HEAD, false, 3, 4));
    assertEquals(
        new GuardCabChange.Outlook(Departure.TAIL, true),
        GuardCabChange.fromDriver(CabChange.Stage.IDLE, false, End.NONE, true, 3, 4),
        "驾驶员已提前坐到车尾端");
    assertEquals(
        GuardCabChange.Outlook.RUNNING,
        GuardCabChange.fromDriver(CabChange.Stage.IDLE, false, End.NONE, false, 0, 4));
  }

  /** 换端扣车：从终点站待命起扣，放行后到换端完成为止；人工驾驶不扣。 */
  @Test
  void theHoldLastsFromLayoverUntilTheChangeIsDone() {
    assertTrue(GuardSessionManager.keepCabHold(true, true, false, false), "待命起扣");
    assertTrue(GuardSessionManager.keepCabHold(true, false, true, true), "放行后换端中");
    assertFalse(GuardSessionManager.keepCabHold(true, false, true, false), "换好了");
    assertFalse(GuardSessionManager.keepCabHold(true, false, false, true), "没在待命时扣过就不扣");
    assertFalse(GuardSessionManager.keepCabHold(false, true, true, true), "人工驾驶不扣");
  }

  /** 预留座位：别人坐不进来，车掌本人可以；换端时预留让出来，谁都能坐。 */
  @Test
  void aReservedSeatTurnsAwayEveryoneButTheGuard() {
    UUID guard = UUID.randomUUID();
    UUID other = UUID.randomUUID();
    UUID car = UUID.randomUUID();
    CabSeatKey reserved = new CabSeatKey(car, 1);
    assertTrue(GuardSessionManager.blocksSeat(reserved, false, guard, reserved, other));
    assertTrue(GuardSessionManager.blocksSeat(reserved, false, guard, reserved, null));
    assertFalse(GuardSessionManager.blocksSeat(reserved, false, guard, reserved, guard));
    assertFalse(GuardSessionManager.blocksSeat(reserved, true, guard, reserved, other), "换端中");
    assertFalse(
        GuardSessionManager.blocksSeat(reserved, false, guard, new CabSeatKey(car, 0), other),
        "同一节车的别的座位");
    assertFalse(
        GuardSessionManager.blocksSeat(
            reserved, false, guard, new CabSeatKey(UUID.randomUUID(), 1), other),
        "别的车厢");
  }

  /** 预留座位按车厢位置认端，与没有标记驾驶座时的认定一致（标记过的列车预留座位只在端车上）。 */
  @Test
  void carPositionAgreesWithUnmarkedCabSeats() {
    for (int size = 2; size <= 8; size++) {
      for (int index = 0; index < size; index++) {
        assertEquals(
            CabSeats.unmarked(size).endOf(index, 0),
            GuardCabChange.endOfCar(index, size),
            size + " 节第 " + index);
      }
    }
  }
}
