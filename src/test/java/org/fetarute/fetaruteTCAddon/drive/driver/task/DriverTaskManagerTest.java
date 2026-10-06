package org.fetarute.fetaruteTCAddon.drive.driver.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.fetarute.fetaruteTCAddon.drive.driver.DrivingMode;
import org.fetarute.fetaruteTCAddon.drive.seat.CabSeats;
import org.fetarute.fetaruteTCAddon.drive.seat.SeatBinding;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("驾驶任务的领取与终态")
class DriverTaskManagerTest {

  private static final Instant NOW = Instant.parse("2026-10-03T08:00:00Z");
  private static final UUID TT = UUID.randomUUID();

  private final DriverTaskManager tasks = new DriverTaskManager(null, null);

  private static Player player(String name) {
    Player player = mock(Player.class);
    when(player.getUniqueId()).thenReturn(UUID.randomUUID());
    when(player.getName()).thenReturn(name);
    return player;
  }

  private static TaskBoardEntries.Row row(String trip) {
    return new TaskBoardEntries.Row(
        new TaskKey(TT, trip, LocalDate.of(2026, 10, 3)),
        UUID.randomUUID(),
        "R1",
        3,
        "OP:S:STA:1",
        NOW.plusSeconds(120),
        false,
        false,
        "T-1",
        false);
  }

  @Test
  @DisplayName("一人一个任务、一个车次一名驾驶员；总开关与拥堵保护挡住领取")
  void claimRules() {
    Player a = player("a");
    Player b = player("b");
    assertEquals(
        DriverTaskManager.ClaimOutcome.DISABLED,
        tasks.claim(a, row("X1"), "OP", "STA", "站", DrivingMode.MANUAL, false, NOW));
    assertEquals(
        DriverTaskManager.ClaimOutcome.CLAIMED,
        tasks.claim(a, row("X1"), "OP", "STA", "站", DrivingMode.ATO, true, NOW));
    assertEquals(
        DriverTaskManager.ClaimOutcome.ALREADY_HAS_TASK,
        tasks.claim(a, row("X2"), "OP", "STA", "站", DrivingMode.MANUAL, true, NOW));
    assertEquals(
        DriverTaskManager.ClaimOutcome.TAKEN,
        tasks.claim(b, row("x1"), "OP", "STA", "站", DrivingMode.MANUAL, true, NOW));
    assertTrue(tasks.claimFor(a.getUniqueId(), "t-1").isPresent());
    assertEquals(DrivingMode.ATO, tasks.claimFor(a.getUniqueId(), "T-1").orElseThrow().mode());

    assertTrue(tasks.abandon(a.getUniqueId(), "test"));
    assertEquals(
        DriverTaskManager.ClaimOutcome.CLAIMED,
        tasks.claim(b, row("X1"), "OP", "STA", "站", DrivingMode.MANUAL, true, NOW),
        "放弃后车次可被别人领取");
  }

  @Test
  @DisplayName("驾驶结束按原因定终态，已结束的不再改")
  void sessionEndStates() {
    Player a = player("a");
    tasks.claim(a, row("Y1"), "OP", "STA", "站", DrivingMode.MANUAL, true, NOW);
    tasks.onSessionStarted(a.getUniqueId(), "T-1", 100L);
    assertEquals(DriverTask.State.DRIVING, tasks.taskOf(a.getUniqueId()).orElseThrow().state());
    tasks.fail(a.getUniqueId(), "stuck");
    tasks.onSessionEnded(a.getUniqueId(), DriverTask.State.INTERRUPTED, "WATCHDOG");
    DriverTask task = tasks.taskOf(a.getUniqueId()).orElseThrow();
    assertEquals(DriverTask.State.FAILED, task.state());
    assertEquals("stuck", task.endReason());
  }

  @Test
  @DisplayName("坐进任务列车车头一端才提示确认接班；后半列车提示换座")
  void seatCheck() {
    CabSeats seats = CabSeats.unmarked(6);
    assertEquals(
        DriverTaskManager.SeatCheck.NOT_ON_TRAIN,
        DriverTaskManager.checkSeat(null, "T1", CabSeats.End.NONE, CabSeats.Departure.HEAD));
    assertEquals(
        DriverTaskManager.SeatCheck.NOT_ON_TRAIN,
        check(seats, new SeatBinding("T2", 0, 0), CabSeats.Departure.HEAD));
    assertEquals(
        DriverTaskManager.SeatCheck.CONFIRM,
        check(seats, new SeatBinding("t1", 0, 0), CabSeats.Departure.HEAD));
    assertEquals(
        DriverTaskManager.SeatCheck.WRONG_SEAT,
        check(seats, new SeatBinding("T1", 5, 0), CabSeats.Departure.HEAD));
  }

  @Test
  @DisplayName("终点站折返接车：方向未定时后端车厢也可以坐，后半列车的中间车厢仍不行")
  void eitherEndSeatCheck() {
    CabSeats seats = CabSeats.unmarked(6);
    assertEquals(
        DriverTaskManager.SeatCheck.CONFIRM,
        check(seats, new SeatBinding("T1", 5, 0), CabSeats.Departure.EITHER));
    assertEquals(
        DriverTaskManager.SeatCheck.WRONG_SEAT,
        check(seats, new SeatBinding("T1", 4, 0), CabSeats.Departure.EITHER));
    assertEquals(
        DriverTaskManager.SeatCheck.CONFIRM,
        check(seats, new SeatBinding("T1", 0, 0), CabSeats.Departure.EITHER));
  }

  private static DriverTaskManager.SeatCheck check(
      CabSeats seats, SeatBinding seat, CabSeats.Departure expected) {
    return DriverTaskManager.checkSeat(seat, "T1", seats.endOf(seat), expected);
  }

  @Test
  @DisplayName("按车次找任务；接车等到时限只作废还没开始的任务")
  void taskForTripAndExpireClaim() {
    Player a = player("a");
    tasks.claim(a, row("R1-007"), "OP", "AAA", "A 站", DrivingMode.MANUAL, true, NOW);
    LocalDate date = LocalDate.of(2026, 10, 3);

    assertTrue(tasks.taskForTrip(TT, "r1-007", date).isPresent(), "车次号不分大小写");
    assertTrue(tasks.taskForTrip(TT, "R1-008", date).isEmpty());
    assertTrue(tasks.hasActiveTasks());

    tasks.expireClaim(a.getUniqueId(), "pickup-timeout");

    assertEquals(DriverTask.State.EXPIRED, tasks.taskOf(a.getUniqueId()).orElseThrow().state());
    assertEquals("pickup-timeout", tasks.taskOf(a.getUniqueId()).orElseThrow().endReason());
    assertTrue(tasks.taskForTrip(TT, "R1-007", date).isEmpty());
    assertFalse(tasks.hasActiveTasks());
  }

  @Test
  @DisplayName("接班时的晚点只认这一班：列车还绑着上一班时为空")
  void delayOfTripRequiresTheTaskTrip() {
    LocalDate date = LocalDate.of(2026, 10, 3);
    org.fetarute.fetaruteTCAddon.api.timetable.TimetableApi.TrainAssignment previous =
        assignment("R1-006", date, 200L);
    org.fetarute.fetaruteTCAddon.api.timetable.TimetableApi.TrainAssignment current =
        assignment("R1-007", date, 30L);
    TaskKey key = new TaskKey(TT, "R1-007", date);

    assertTrue(DriverTaskManager.delayOfTrip(previous, key).isEmpty());
    assertEquals(30L, DriverTaskManager.delayOfTrip(current, key).getAsLong());
    assertEquals(200L, DriverTaskManager.delayOfTrip(previous, null).getAsLong(), "没有任务时不核对");
    assertTrue(DriverTaskManager.delayOfTrip(null, key).isEmpty());
  }

  private static org.fetarute.fetaruteTCAddon.api.timetable.TimetableApi.TrainAssignment assignment(
      String trip, LocalDate date, long delay) {
    return new org.fetarute.fetaruteTCAddon.api.timetable.TimetableApi.TrainAssignment(
        "T-1",
        TT,
        trip,
        UUID.randomUUID(),
        java.util.Optional.empty(),
        date,
        NOW,
        0L,
        java.util.Optional.empty(),
        java.util.Optional.empty(),
        java.util.Optional.empty(),
        java.util.OptionalLong.of(delay),
        java.util.Optional.empty(),
        java.util.Optional.empty(),
        java.util.Optional.empty(),
        java.util.OptionalLong.empty());
  }

  private static DriverTaskManager.TaskSpec spec(String trip, int handover) {
    return new DriverTaskManager.TaskSpec(
        new TaskKey(TT, trip, LocalDate.of(2026, 10, 3)),
        "R1",
        "OP",
        "AAA",
        "A 站",
        "OP:S:AAA:1",
        1,
        NOW.plusSeconds(3600),
        null,
        handover,
        handover >= 0 ? "CCC" : "",
        handover >= 0 ? "C 站" : "",
        true,
        "typewriter",
        java.util.Map.of("quest", "q1"));
  }

  @Test
  @DisplayName("插件派任务：带交班站、来源与附加数据；与任务板同一套占用规则")
  void assignCarriesIntervalAndSource() {
    Player a = player("a");
    Player b = player("b");

    assertEquals(
        DriverTaskManager.ClaimOutcome.CLAIMED,
        tasks.assign(a, spec("R1-010", 4), DrivingMode.MANUAL, true, NOW));
    DriverTask task = tasks.taskOf(a.getUniqueId()).orElseThrow();
    assertEquals(4, task.handoverStopSequence());
    assertEquals("C 站", task.handoverStationName());
    assertEquals("typewriter", task.source());
    assertEquals(java.util.Map.of("quest", "q1"), task.metadata());
    assertTrue(task.depotPickup());

    assertEquals(
        DriverTaskManager.ClaimOutcome.TAKEN,
        tasks.assign(b, spec("R1-010", -1), DrivingMode.MANUAL, true, NOW));
    assertEquals(
        DriverTaskManager.ClaimOutcome.ALREADY_HAS_TASK,
        tasks.assign(a, spec("R1-011", -1), DrivingMode.MANUAL, true, NOW));
    assertEquals(
        DriverTaskManager.ClaimOutcome.DISABLED,
        tasks.assign(b, spec("R1-011", -1), DrivingMode.MANUAL, false, NOW));
  }

  @Test
  @DisplayName("外部插件可以拦下领取；没开车就结束的任务只报一次")
  void listenerVetoesClaimsAndHearsUnstartedEndsOnce() {
    java.util.List<String> heard = new java.util.ArrayList<>();
    tasks.setListener(
        new DriverTaskManager.Listener() {
          @Override
          public boolean beforeClaim(DriverTask task) {
            return !task.key().tripCode().equals("R1-099");
          }

          @Override
          public void onUnstartedFinished(DriverTask task) {
            heard.add(task.key().tripCode() + ":" + task.state());
          }
        });
    Player a = player("a");

    assertEquals(
        DriverTaskManager.ClaimOutcome.CANCELLED,
        tasks.assign(a, spec("R1-099", -1), DrivingMode.MANUAL, true, NOW));
    assertTrue(tasks.taskOf(a.getUniqueId()).isEmpty(), "被拦下的不登记");

    tasks.assign(a, spec("R1-012", -1), DrivingMode.MANUAL, true, NOW);
    tasks.abandon(a.getUniqueId(), "api");
    tasks.expireClaim(a.getUniqueId(), "again");

    assertEquals(java.util.List.of("R1-012:ABANDONED"), heard);
  }
}
