package org.fetarute.fetaruteTCAddon.drive.hud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Map;
import org.fetarute.fetaruteTCAddon.drive.driver.CabChange;
import org.fetarute.fetaruteTCAddon.drive.seat.CabSeats;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("侧边栏的折返换端一行")
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
    assertTrue(DriveSidebarRows.cabChangeRow(new CabChange()).isEmpty());
  }

  @Test
  @DisplayName("提前告知时显示要去第几节")
  void announced() {
    CabChange change = new CabChange();
    change.tick(input(true, CabSeats.End.HEAD, 0));

    DriveSidebarRows.Row row = DriveSidebarRows.cabChangeRow(change).orElseThrow();
    assertEquals("drive.sidebar.label.cab-change", row.labelKey());
    assertEquals("drive.sidebar.value.cab-change.announced", row.valueKey());
    assertEquals(Map.of("car", "8"), row.values());
  }

  @Test
  @DisplayName("计时中显示车头端与剩余秒数")
  void active() {
    CabChange change = new CabChange();
    change.tick(input(false, CabSeats.End.TAIL, 0));
    change.tick(input(false, CabSeats.End.NONE, 10));

    DriveSidebarRows.Row row = DriveSidebarRows.cabChangeRow(change).orElseThrow();
    assertEquals("drive.sidebar.value.cab-change.active", row.valueKey());
    assertEquals(Map.of("car", "1", "seconds", "14"), row.values());
  }
}
