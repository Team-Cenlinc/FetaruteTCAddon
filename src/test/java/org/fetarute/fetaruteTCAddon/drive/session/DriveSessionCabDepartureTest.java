package org.fetarute.fetaruteTCAddon.drive.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.drive.driver.DriverSchedule;
import org.fetarute.fetaruteTCAddon.drive.driver.DrivingMode;
import org.fetarute.fetaruteTCAddon.drive.driver.task.DriverTask;
import org.fetarute.fetaruteTCAddon.drive.driver.task.TaskKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("换端时取的计划发车时刻")
class DriveSessionCabDepartureTest {

  private static final Instant ARRIVED = Instant.parse("2026-10-04T22:03:48Z");
  private static final Instant NEXT_TRIP = Instant.parse("2026-10-04T22:07:40Z");
  private static final Instant TASK_START = Instant.parse("2026-10-04T21:57:00Z");

  private static DriverTask task(boolean driving) {
    DriverTask task =
        new DriverTask(
            UUID.randomUUID(),
            "a",
            new TaskKey(UUID.randomUUID(), "MT-001", LocalDate.of(2026, 10, 4)),
            "MT",
            "SURC",
            "HHU",
            "H 站",
            "SURC:S:HHU:1",
            0,
            TASK_START,
            DrivingMode.MANUAL,
            Instant.EPOCH);
    if (driving) {
      task.start("T-1", 1L);
    }
    return task;
  }

  private static Optional<DriverSchedule> terminal() {
    return Optional.of(new DriverSchedule(true, ARRIVED, OptionalLong.of(0L)));
  }

  @Test
  @DisplayName("终点站待命取列车的下一趟，不取刚跑完那一趟的终到")
  void layoverUsesNextTrip() {
    assertEquals(
        NEXT_TRIP,
        DriveSessionManager.cabDeparture(
            true, () -> Optional.of(NEXT_TRIP), Optional.of(task(true)), terminal()));
    assertEquals(
        NEXT_TRIP,
        DriveSessionManager.cabDeparture(
            true, () -> Optional.of(NEXT_TRIP), Optional.empty(), terminal()));
  }

  @Test
  @DisplayName("终点站待命查不到下一趟：只认已领还没开始的车次，否则不明")
  void layoverWithoutNextTrip() {
    assertEquals(
        TASK_START,
        DriveSessionManager.cabDeparture(
            true, Optional::empty, Optional.of(task(false)), terminal()));
    assertNull(
        DriveSessionManager.cabDeparture(
            true, Optional::empty, Optional.of(task(true)), terminal()));
    assertNull(
        DriveSessionManager.cabDeparture(true, Optional::empty, Optional.empty(), terminal()));
  }

  @Test
  @DisplayName("不在待命：所领车次的起点发车，否则表定本站发车，不查交路")
  void notLayover() {
    assertEquals(
        TASK_START,
        DriveSessionManager.cabDeparture(
            false,
            () -> {
              throw new AssertionError("不在待命时不查交路");
            },
            Optional.of(task(true)),
            terminal()));
    assertEquals(
        ARRIVED,
        DriveSessionManager.cabDeparture(
            false,
            () -> {
              throw new AssertionError("不在待命时不查交路");
            },
            Optional.empty(),
            terminal()));
  }
}
