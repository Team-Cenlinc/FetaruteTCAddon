package org.fetarute.fetaruteTCAddon.drive.driver.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.drive.seat.CabSeats;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("接车记录的发车端")
class DriverPickupsDepartureTest {

  private static final Instant NOW = Instant.parse("2026-10-04T08:00:00Z");
  private static final TaskKey TRIP =
      new TaskKey(UUID.randomUUID(), "R1-001", LocalDate.of(2026, 10, 4));

  private final DriverPickups pickups = new DriverPickups();
  private final UUID driver = UUID.randomUUID();

  @Test
  @DisplayName("没给发车端时：车库出车坐车头，终点站待命车两端都可以")
  void defaultsByKind() {
    assertEquals(
        CabSeats.Departure.HEAD,
        pickups
            .start(driver, TRIP, DriverPickups.Kind.DEPOT, "T-1", "车库", NOW.plusSeconds(90))
            .departure());
    assertEquals(
        CabSeats.Departure.EITHER,
        pickups
            .start(driver, TRIP, DriverPickups.Kind.TERMINAL, "T-1", "终点站", NOW.plusSeconds(90))
            .departure());
  }

  @Test
  @DisplayName("尽头式终点站：记下由车尾端驾驶")
  void keepsGivenDeparture() {
    DriverPickups.Pickup pickup =
        pickups.start(
            driver,
            TRIP,
            DriverPickups.Kind.TERMINAL,
            "T-1",
            "终点站",
            NOW.plusSeconds(90),
            CabSeats.Departure.TAIL);

    assertEquals(CabSeats.Departure.TAIL, pickup.departure());
    assertEquals(CabSeats.Departure.TAIL, pickups.ofTrain("T-1").orElseThrow().departure());
  }

  @Test
  @DisplayName("重新判定发车端：变了才报变化")
  void updatesDeparture() {
    DriverPickups.Pickup pickup =
        pickups.start(
            driver,
            TRIP,
            DriverPickups.Kind.TERMINAL,
            "T-1",
            "终点站",
            NOW.plusSeconds(90),
            CabSeats.Departure.TAIL);

    assertFalse(pickup.updateDeparture(CabSeats.Departure.TAIL));
    assertTrue(pickup.updateDeparture(CabSeats.Departure.HEAD), "居中往回挪、被调头之后");
    assertEquals(CabSeats.Departure.HEAD, pickup.departure());
  }
}
