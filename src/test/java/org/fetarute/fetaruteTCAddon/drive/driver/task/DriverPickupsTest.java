package org.fetarute.fetaruteTCAddon.drive.driver.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("始发站与车库接班的判定")
class DriverPickupsTest {

  private static final Instant NOW = Instant.parse("2026-10-03T08:00:00Z");
  private static final UUID TT = UUID.randomUUID();
  private static final TaskKey TRIP = new TaskKey(TT, "R1-001", LocalDate.of(2026, 10, 3));
  private static final TaskKey OTHER_TRIP = new TaskKey(TT, "R1-002", LocalDate.of(2026, 10, 3));

  private final DriverPickups pickups = new DriverPickups();
  private final UUID driver = UUID.randomUUID();

  @Test
  @DisplayName("没人领的班次照常派车；有人领、还没开始接车时交给调用方决定")
  void unclaimedTripsDispatchAndClaimedOnesStartAPickup() {
    assertEquals(DriverPickups.Verdict.DISPATCH, pickups.layover(TRIP, null, "T-1", false, NOW));
    assertEquals(DriverPickups.Verdict.START, pickups.layover(TRIP, driver, "T-1", false, NOW));
    assertEquals(
        DriverPickups.Verdict.DISPATCH,
        pickups.layover(TRIP, driver, "T-1", true, NOW),
        "驾驶员已经在这列车上（例如开着出库走行过来）就直接派");
  }

  @Test
  @DisplayName("接车中：驾驶员上了留给他的车才派；换成别的车或没上车都等到时限")
  void aPickupHoldsUntilTheDriverIsAboardOrTheDeadlinePasses() {
    pickups.start(driver, TRIP, DriverPickups.Kind.TERMINAL, "T-1", "终点站", NOW.plusSeconds(90));

    assertEquals(DriverPickups.Verdict.HOLD, pickups.layover(TRIP, driver, "T-1", false, NOW));
    assertEquals(DriverPickups.Verdict.HOLD, pickups.layover(TRIP, driver, "T-2", true, NOW));
    assertTrue(pickups.awaiting("t-1"), "车名不分大小写");

    assertTrue(pickups.board(driver, "T-1").isPresent());
    assertFalse(pickups.awaiting("T-1"), "驾驶员上车后不再算等人");
    assertEquals(DriverPickups.Verdict.DISPATCH, pickups.layover(TRIP, driver, "T-1", true, NOW));
    assertEquals(
        DriverPickups.Verdict.DISPATCH,
        pickups.layover(TRIP, driver, "T-2", false, NOW.plusSeconds(90)),
        "过了时限谁都能派");
  }

  @Test
  @DisplayName("留给驾驶员的车不派给别的班次，到时限为止")
  void aReservedTrainIsNotGivenToAnotherTrip() {
    pickups.start(driver, TRIP, DriverPickups.Kind.TERMINAL, "T-1", "终点站", NOW.plusSeconds(90));

    assertEquals(DriverPickups.Verdict.HOLD, pickups.layover(OTHER_TRIP, null, "T-1", false, NOW));
    assertEquals(
        DriverPickups.Verdict.HOLD,
        pickups.layover(OTHER_TRIP, UUID.randomUUID(), "T-1", false, NOW));
    assertEquals(
        DriverPickups.Verdict.DISPATCH,
        pickups.layover(OTHER_TRIP, null, "T-1", false, NOW.plusSeconds(91)));
  }

  @Test
  @DisplayName("过时只标一次；过时的接车照常派车，也不再留车")
  void expiryIsReportedOnceAndReleasesTheTrain() {
    pickups.start(driver, TRIP, DriverPickups.Kind.DEPOT, "T-9", "OP:D:DEP:1", NOW.plusSeconds(90));

    assertTrue(pickups.expire(NOW.plusSeconds(89)).isEmpty());
    assertEquals(1, pickups.expire(NOW.plusSeconds(90)).size());
    assertTrue(pickups.expire(NOW.plusSeconds(120)).isEmpty());
    assertEquals(DriverPickups.Stage.EXPIRED, pickups.ofPlayer(driver).orElseThrow().stage());
    assertTrue(pickups.ofTrain("T-9").isEmpty());
    assertFalse(pickups.board(driver, "T-9").isPresent(), "过时后上车不算接车");
    assertEquals(DriverPickups.Verdict.DISPATCH, pickups.layover(TRIP, driver, "T-9", false, NOW));
  }

  @Test
  @DisplayName("剩余秒数向上取整、不为负")
  void secondsLeftRoundsUp() {
    DriverPickups.Pickup pickup =
        pickups.start(
            driver, TRIP, DriverPickups.Kind.TERMINAL, "T-1", "终点站", NOW.plusMillis(1500));

    assertEquals(2L, pickup.secondsLeft(NOW));
    assertEquals(0L, pickup.secondsLeft(NOW.plusSeconds(5)));
  }

  @Test
  @DisplayName("接车时限不晚于票据作废之前：计划发车 + 容差 − 余量；已经过了就立即放行")
  void deadlineStaysWithinTheTicketTolerance() {
    java.time.Duration tolerance = java.time.Duration.ofSeconds(300);

    assertEquals(
        NOW.plusSeconds(90), DriverPickups.deadline(NOW, 90, NOW, tolerance, 30), "容差足够时按等待时长");
    assertEquals(
        NOW.plusSeconds(270),
        DriverPickups.deadline(NOW.plusSeconds(250), 90, NOW, tolerance, 30),
        "等待会越过容差时收紧到容差前");
    assertEquals(
        NOW.plusSeconds(400),
        DriverPickups.deadline(NOW.plusSeconds(400), 90, NOW, tolerance, 30),
        "已经过了容差：立即到期");
    assertEquals(
        NOW.plusSeconds(90), DriverPickups.deadline(NOW, 90, null, tolerance, 30), "没有计划发车时不按容差收紧");
  }

  @Test
  @DisplayName("车库出车：同一班换了一列车（上次出车回滚）就改等新车；已上车或已过时的不再扣")
  void depotVerdicts() {
    assertEquals(DriverPickups.DepotVerdict.START, pickups.depot(driver, TRIP, "T-1"));

    pickups.start(driver, TRIP, DriverPickups.Kind.DEPOT, "T-1", "OP:D:DEP:1", NOW.plusSeconds(90));
    assertEquals(DriverPickups.DepotVerdict.KEEP, pickups.depot(driver, TRIP, "t-1"));
    assertEquals(DriverPickups.DepotVerdict.START, pickups.depot(driver, TRIP, "T-2"));
    assertEquals(DriverPickups.DepotVerdict.START, pickups.depot(driver, OTHER_TRIP, "T-2"));

    pickups.board(driver, "T-1");
    assertEquals(DriverPickups.DepotVerdict.SKIP, pickups.depot(driver, TRIP, "T-2"));

    pickups.start(driver, TRIP, DriverPickups.Kind.TERMINAL, "T-3", "终点站", NOW.plusSeconds(90));
    assertEquals(DriverPickups.DepotVerdict.SKIP, pickups.depot(driver, TRIP, "T-4"));
  }
}
