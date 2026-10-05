package org.fetarute.fetaruteTCAddon.drive.hud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.drive.driver.CabChange;
import org.fetarute.fetaruteTCAddon.drive.driver.DriverNextTrip;
import org.fetarute.fetaruteTCAddon.drive.seat.CabSeats;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("侧边栏的折返换端与下一趟")
class DriveSidebarCabChangeRowTest {

  private static final Instant T0 = Instant.parse("2026-10-04T08:00:00Z");

  private static CabChange.Input input(boolean preRelease, CabSeats.End seat, long atSecond) {
    return new CabChange.Input(
        true,
        preRelease,
        CabSeats.Departure.TAIL,
        seat,
        !preRelease,
        T0.plusSeconds(atSecond),
        24L,
        null,
        false,
        0L,
        8);
  }

  @Test
  @DisplayName("不换端时没有这一行")
  void absentWhenIdle() {
    assertTrue(DriveSidebarRows.cabChangeRow(new CabChange(), false).isEmpty());
  }

  @Test
  @DisplayName("提前告知时显示要去第几节")
  void announced() {
    CabChange change = new CabChange();
    change.tick(input(true, CabSeats.End.HEAD, 0));

    DriveSidebarRows.Row row = DriveSidebarRows.cabChangeRow(change, false).orElseThrow();
    assertEquals("drive.sidebar.label.cab-change", row.labelKey());
    assertEquals("drive.sidebar.value.cab-change.announced", row.valueKey());
    assertEquals(Map.of("car", "8"), row.values());
  }

  @Test
  @DisplayName("等确认座位时改为请确认座位")
  void confirmSeat() {
    CabChange change = new CabChange();
    change.tick(input(true, CabSeats.End.HEAD, 0));

    DriveSidebarRows.Row row = DriveSidebarRows.cabChangeRow(change, true).orElseThrow();
    assertEquals("drive.sidebar.value.cab-change.confirm", row.valueKey());
  }

  @Test
  @DisplayName("接续的下一趟：开往哪里、几点发车；查不到终点站时写车次")
  void nextTrip() {
    UUID id = UUID.randomUUID();
    DriveSidebarRows.Row row =
        DriveSidebarRows.nextTripRow(new DriverNextTrip(id, "MT-086", "壑湖", T0));
    assertEquals("drive.sidebar.label.next-trip", row.labelKey());
    assertEquals("drive.sidebar.value.next-trip", row.valueKey());
    assertEquals("壑湖", row.values().get("destination"));
    assertEquals("MT-086", row.values().get("trip"));
    assertTrue(row.values().containsKey("time"));

    assertEquals(
        "drive.sidebar.value.next-trip-code",
        DriveSidebarRows.nextTripRow(new DriverNextTrip(id, "MT-086", "", T0)).valueKey());
  }

  @Test
  @DisplayName("计时中显示车头端与剩余秒数")
  void active() {
    CabChange change = new CabChange();
    change.tick(input(false, CabSeats.End.TAIL, 0));
    change.tick(input(false, CabSeats.End.NONE, 10));

    DriveSidebarRows.Row row = DriveSidebarRows.cabChangeRow(change, false).orElseThrow();
    assertEquals("drive.sidebar.value.cab-change.active", row.valueKey());
    assertEquals(Map.of("car", "1", "seconds", "14"), row.values());
  }
}
