package org.fetarute.fetaruteTCAddon.display.pids.bulletin;

import static org.fetarute.fetaruteTCAddon.display.pids.bulletin.PidsBulletinFixtures.NOW;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.fetarute.fetaruteTCAddon.display.pids.PidsStationKey;
import org.junit.jupiter.api.Test;

/** 公告的范围、时段与内容版本。 */
class PidsBulletinTest {

  private static final PidsStationKey PPK = new PidsStationKey("SURC", "PPK");
  private static final PidsStationKey HHU = new PidsStationKey("SURC", "HHU");

  @Test
  void codesAreNormalisedToUpperCase() {
    PidsBulletin bulletin =
        PidsBulletinFixtures.bulletin(
            Set.of(" ppk "),
            Set.of("mt", ""),
            PidsBulletin.Level.NORMAL,
            new PidsBulletin.Text(" 标题 ", null),
            new PidsBulletin.Text(null, null),
            Optional.empty(),
            Optional.empty());

    assertEquals("SURC", bulletin.operatorCode());
    assertEquals(Set.of("PPK"), bulletin.stations());
    assertEquals(Set.of("MT"), bulletin.lines());
    assertEquals("标题", bulletin.title().primary());
    assertTrue(bulletin.body().isEmpty());
  }

  @Test
  void aChineseTitleIsRequired() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            PidsBulletinFixtures.bulletin(
                Set.of(),
                Set.of(),
                PidsBulletin.Level.NORMAL,
                new PidsBulletin.Text(" ", "Title"),
                new PidsBulletin.Text("", ""),
                Optional.empty(),
                Optional.empty()));
  }

  /** 车站清单限定车站，线路清单与屏幕显示的线路有交集才算；运营商必须一致。 */
  @Test
  void scopeMatchesOperatorStationsAndLines() {
    PidsBulletin exit = PidsBulletinFixtures.exitClosed();
    PidsBulletin suspended = PidsBulletinFixtures.lineSuspended();

    assertTrue(exit.appliesTo(PPK, List.of()));
    assertFalse(exit.appliesTo(HHU, List.of()), "只在蒲塘桥港");
    assertFalse(exit.appliesTo(new PidsStationKey("OTHER", "PPK"), List.of()), "别的运营商");
    assertTrue(suspended.appliesTo(HHU, List.of("ws", "mt")));
    assertFalse(suspended.appliesTo(HHU, List.of("WS")), "只显示 WS 的屏幕");
    assertFalse(suspended.appliesTo(HHU, List.of()), "屏幕线路未知时不显示限定了线路的公告");
  }

  /** 时段为半开区间：开始时刻起生效，结束时刻起结束。 */
  @Test
  void statusFollowsTheHalfOpenPeriod() {
    Instant start = NOW.plusSeconds(60);
    Instant end = NOW.plusSeconds(120);
    PidsBulletin timed =
        PidsBulletinFixtures.bulletin(
            Set.of(),
            Set.of(),
            PidsBulletin.Level.NORMAL,
            new PidsBulletin.Text("标题", ""),
            new PidsBulletin.Text("", ""),
            Optional.of(start),
            Optional.of(end));

    assertEquals(PidsBulletin.Status.SCHEDULED, timed.status(NOW));
    assertEquals(PidsBulletin.Status.ACTIVE, timed.status(start));
    assertEquals(PidsBulletin.Status.ACTIVE, timed.status(end.minusMillis(1)));
    assertEquals(PidsBulletin.Status.ENDED, timed.status(end));
    assertEquals(PidsBulletin.Status.ACTIVE, PidsBulletinFixtures.exitClosed().status(NOW));
  }

  @Test
  void editingKeepsIdentityAndChangesTheRevision() {
    PidsBulletin original = PidsBulletinFixtures.exitClosed();
    PidsBulletin edited =
        original.edited(
            Set.of("PPK", "HHU"),
            Set.of(),
            PidsBulletin.Level.IMPORTANT,
            original.title(),
            original.body(),
            Optional.empty(),
            Optional.empty(),
            NOW.plusSeconds(5));

    assertEquals(original.id(), edited.id());
    assertEquals(original.createdAt(), edited.createdAt());
    assertTrue(edited.important());
    assertNotEquals(original.revision(), edited.revision());
  }
}
