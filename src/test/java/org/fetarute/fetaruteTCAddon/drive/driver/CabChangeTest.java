package org.fetarute.fetaruteTCAddon.drive.driver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicInteger;
import org.fetarute.fetaruteTCAddon.drive.seat.CabSeats;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("折返换端的状态推进")
class CabChangeTest {

  private static final Instant T0 = Instant.parse("2026-10-04T08:00:00Z");
  private static final long RESERVE = 24L;
  private static final long BRAKE_TEST = 30L;

  private final CabChange change = new CabChange();

  /** 放行后（不在待命）的一拍。 */
  private static CabChange.Input after(
      CabSeats.End seat, boolean released, long atSecond, Instant planned) {
    return new CabChange.Input(
        true,
        false,
        CabSeats.Departure.EITHER,
        seat,
        released,
        T0.plusSeconds(atSecond),
        RESERVE,
        planned,
        true,
        BRAKE_TEST,
        6);
  }

  /** 尽头式待命、放行前的一拍。 */
  private static CabChange.Input before(CabSeats.End seat, long atSecond, Instant planned) {
    return new CabChange.Input(
        true,
        true,
        CabSeats.Departure.TAIL,
        seat,
        false,
        T0.plusSeconds(atSecond),
        RESERVE,
        planned,
        true,
        BRAKE_TEST,
        6);
  }

  @Test
  @DisplayName("制动试验判定：simulation 级且距计划发车不少于预留加试验时间才重做")
  void brakeTestRule() {
    assertTrue(CabChange.brakeTestRequired(true, OptionalLong.of(54L), 24L, 30L));
    assertFalse(CabChange.brakeTestRequired(true, OptionalLong.of(53L), 24L, 30L));
    assertFalse(
        CabChange.brakeTestRequired(false, OptionalLong.of(600L), 24L, 30L), "standard 级不做");
    assertFalse(CabChange.brakeTestRequired(true, OptionalLong.empty(), 24L, 30L), "计划发车不明时不要求");
  }

  @Test
  @DisplayName("准备时间不足：距计划发车不到时间预留")
  void shortOfTimeRule() {
    assertTrue(CabChange.shortOfTime(OptionalLong.of(23L), 24L));
    assertTrue(CabChange.shortOfTime(OptionalLong.of(-10L), 24L), "已过计划发车");
    assertFalse(CabChange.shortOfTime(OptionalLong.of(24L), 24L));
    assertFalse(CabChange.shortOfTime(OptionalLong.empty(), 24L));
  }

  @Test
  @DisplayName("放行后坐在车尾端：开始计时，离座不算完成，坐进车头端完成")
  void startsAfterReleaseAndCompletesAtHead() {
    Instant planned = T0.plusSeconds(120);

    assertEquals(CabChange.Event.STARTED, change.tick(after(CabSeats.End.TAIL, true, 0, planned)));
    assertTrue(change.holding());
    assertTrue(change.allowsLeavingSeat());
    assertEquals(CabSeats.End.HEAD, change.target());
    assertEquals(1, change.targetCar());
    assertEquals(RESERVE, change.secondsLeft());
    assertTrue(change.brakeTestRequired(), "距发车 120 秒 ≥ 24 + 30");
    assertFalse(change.shortOfTime());

    assertEquals(CabChange.Event.NONE, change.tick(after(CabSeats.End.NONE, true, 5, planned)));
    assertEquals(RESERVE - 5, change.secondsLeft());
    assertEquals(CabChange.Event.NONE, change.tick(after(CabSeats.End.TAIL, true, 6, planned)));

    assertEquals(
        CabChange.Event.COMPLETED, change.tick(after(CabSeats.End.HEAD, true, 12, planned)));
    assertEquals(CabChange.Stage.IDLE, change.stage());
    assertFalse(change.holding());
    assertFalse(change.allowsLeavingSeat(), "完成后离座按离岗处理");
    assertTrue(change.brakeTestRequired(), "完成后仍能读到开始时定下的结论");
  }

  @Test
  @DisplayName("时间预留用完还没坐进车头端：超时")
  void timesOut() {
    change.tick(after(CabSeats.End.TAIL, true, 0, null));

    assertEquals(
        CabChange.Event.NONE, change.tick(after(CabSeats.End.NONE, true, RESERVE - 1, null)));
    assertEquals(
        CabChange.Event.TIMED_OUT, change.tick(after(CabSeats.End.NONE, true, RESERVE, null)));
    assertEquals(CabChange.Stage.IDLE, change.stage());
    assertFalse(change.brakeTestRequired(), "计划发车不明时不要求重做");
  }

  @Test
  @DisplayName("时间紧：报准备时间不足，且不要求重做制动试验")
  void shortOfTimeAtStart() {
    change.tick(after(CabSeats.End.TAIL, true, 0, T0.plusSeconds(10)));

    assertTrue(change.shortOfTime());
    assertFalse(change.brakeTestRequired());
    assertEquals(OptionalLong.of(10L), change.remainingAtStart());
  }

  @Test
  @DisplayName("没拿到行车许可、或坐在车头端、或离座时都不开始换端")
  void noStartWithoutReason() {
    assertEquals(CabChange.Event.NONE, change.tick(after(CabSeats.End.TAIL, false, 0, null)));
    assertEquals(CabChange.Event.NONE, change.tick(after(CabSeats.End.HEAD, true, 1, null)));
    assertEquals(CabChange.Event.NONE, change.tick(after(CabSeats.End.NONE, true, 2, null)));
    assertEquals(CabChange.Stage.IDLE, change.stage());
  }

  @Test
  @DisplayName("尽头式待命：放行前提前告知，坐进车尾端即完成，不计时")
  void announcesBeforeRelease() {
    Instant planned = T0.plusSeconds(300);

    assertEquals(CabChange.Event.ANNOUNCED, change.tick(before(CabSeats.End.HEAD, 0, planned)));
    assertEquals(CabChange.Stage.ANNOUNCED, change.stage());
    assertFalse(change.holding(), "放行前不计时、不封锁");
    assertTrue(change.allowsLeavingSeat());
    assertEquals(CabSeats.End.TAIL, change.target());
    assertEquals(6, change.targetCar());
    assertEquals(-1L, change.secondsLeft());

    assertEquals(CabChange.Event.NONE, change.tick(before(CabSeats.End.NONE, 200, planned)));
    assertEquals(CabChange.Event.COMPLETED, change.tick(before(CabSeats.End.TAIL, 400, planned)));
    assertEquals(CabChange.Stage.IDLE, change.stage());

    // 放行调头之后，原来的车尾端成了车头端：不再要求换端。
    assertEquals(CabChange.Event.NONE, change.tick(after(CabSeats.End.HEAD, true, 401, planned)));
  }

  @Test
  @DisplayName("每次待命只提前告知一次：坐对之后再离座按离岗处理")
  void announcesOncePerLayover() {
    change.tick(before(CabSeats.End.HEAD, 0, null));
    change.tick(before(CabSeats.End.TAIL, 10, null));

    assertEquals(CabChange.Event.NONE, change.tick(before(CabSeats.End.NONE, 20, null)));
    assertFalse(change.allowsLeavingSeat());
  }

  @Test
  @DisplayName("一开始就坐在车尾端（按接车提示坐对了）：不提前告知")
  void alreadySeatedAtTail() {
    assertEquals(CabChange.Event.NONE, change.tick(before(CabSeats.End.TAIL, 0, null)));
    assertEquals(CabChange.Event.NONE, change.tick(before(CabSeats.End.NONE, 5, null)));
    assertEquals(CabChange.Stage.IDLE, change.stage());
  }

  @Test
  @DisplayName("提前告知后没换过去就放行：转入计时换端，目标改为车头端")
  void announcedThenReleased() {
    Instant planned = T0.plusSeconds(60);
    change.tick(before(CabSeats.End.HEAD, 0, planned));

    assertEquals(CabChange.Event.STARTED, change.tick(after(CabSeats.End.NONE, true, 50, planned)));
    assertTrue(change.holding());
    assertEquals(CabSeats.End.HEAD, change.target());
    assertTrue(change.shortOfTime(), "放行时距发车只剩 10 秒");
    assertFalse(change.brakeTestRequired(), "放行时重新判定");
  }

  @Test
  @DisplayName("方向未定的待命不提前告知")
  void unknownDirectionIsNotAnnounced() {
    CabChange.Input input =
        new CabChange.Input(
            true,
            true,
            CabSeats.Departure.EITHER,
            CabSeats.End.HEAD,
            false,
            T0,
            RESERVE,
            null,
            true,
            BRAKE_TEST,
            6);

    assertEquals(CabChange.Event.NONE, change.tick(input));
    assertEquals(CabChange.Stage.IDLE, change.stage());
  }

  @Test
  @DisplayName("不再适用（列车动了、改为 ATO）时取消")
  void cancelsWhenNotApplicable() {
    change.tick(after(CabSeats.End.TAIL, true, 0, null));
    CabChange.Input moving =
        new CabChange.Input(
            false,
            false,
            CabSeats.Departure.EITHER,
            CabSeats.End.NONE,
            true,
            T0.plusSeconds(1),
            0L,
            null,
            false,
            0L,
            6);

    assertEquals(CabChange.Event.CANCELLED, change.tick(moving));
    assertEquals(CabChange.Event.NONE, change.tick(moving));
  }

  @Test
  @DisplayName("预计发车端同一次待命只算一次，放行后清掉")
  void predictionIsCachedPerLayover() {
    AtomicInteger calls = new AtomicInteger();

    assertEquals(
        CabSeats.Departure.TAIL,
        change.prediction(
            true,
            () -> {
              calls.incrementAndGet();
              return CabSeats.Departure.TAIL;
            }));
    change.prediction(true, () -> CabSeats.Departure.HEAD);
    assertEquals(1, calls.get());
    assertEquals(
        CabSeats.Departure.EITHER, change.prediction(false, () -> CabSeats.Departure.TAIL));
    assertEquals(CabSeats.Departure.HEAD, change.prediction(true, () -> CabSeats.Departure.HEAD));
  }
}
