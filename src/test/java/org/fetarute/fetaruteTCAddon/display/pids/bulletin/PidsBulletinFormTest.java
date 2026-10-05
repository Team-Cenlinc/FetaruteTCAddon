package org.fetarute.fetaruteTCAddon.display.pids.bulletin;

import static org.fetarute.fetaruteTCAddon.display.pids.bulletin.PidsBulletinFixtures.NOW;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 对话框内容的校验：必填、长度、车站与线路、时刻写法与先后。 */
class PidsBulletinFormTest {

  private static final ZoneId ZONE = ZoneOffset.ofHours(8);
  private static final Set<String> STATIONS = Set.of("PPK", "HHU");
  private static final Set<String> LINES = Set.of("MT", "WS");

  private static PidsBulletinForm form(
      String title, String stations, String lines, String starts, String ends) {
    return new PidsBulletinForm(
        PidsBulletinForm.IMPORTANT, title, "", "正文\n第二行", "", stations, lines, starts, ends);
  }

  private static Optional<PidsBulletinForm.Parsed> parse(
      PidsBulletinForm form, List<PidsBulletinForm.Problem> problems) {
    return form.parse(STATIONS::contains, LINES::contains, NOW, ZONE, problems);
  }

  @Test
  void parsesCodesTimesAndLevel() {
    List<PidsBulletinForm.Problem> problems = new ArrayList<>();

    PidsBulletinForm.Parsed parsed =
        parse(form("标题", "ppk，hhu、PPK", "mt ws", "2026-10-05 22:00", "10/06 06:00"), problems)
            .orElseThrow();

    assertTrue(problems.isEmpty(), problems::toString);
    assertEquals(PidsBulletin.Level.IMPORTANT, parsed.level());
    assertEquals(Set.of("PPK", "HHU"), parsed.stations());
    assertEquals(Set.of("MT", "WS"), parsed.lines());
    assertEquals(Optional.of(Instant.parse("2026-10-05T14:00:00Z")), parsed.startsAt());
    assertEquals(Optional.of(Instant.parse("2026-10-05T22:00:00Z")), parsed.endsAt());
    assertEquals("正文\n第二行", parsed.body().primary(), "正文里的换行保留");
  }

  /** 只写时刻表示今天（服务器时区）。 */
  @Test
  void aBareClockMeansToday() {
    List<PidsBulletinForm.Problem> problems = new ArrayList<>();

    PidsBulletinForm.Parsed parsed = parse(form("标题", "", "", "", "23:30"), problems).orElseThrow();

    assertEquals(Optional.of(Instant.parse("2026-10-05T15:30:00Z")), parsed.endsAt());
  }

  /** 年底写不带年份的“01-02”指明年；几天前的日期仍算今年。 */
  @Test
  void shortDatesRollOverToNextYear() {
    Instant december = Instant.parse("2026-12-30T04:00:00Z");
    List<PidsBulletinForm.Problem> problems = new ArrayList<>();

    assertEquals(
        Optional.of(Instant.parse("2027-01-01T22:00:00Z")),
        PidsBulletinForm.time("01-02 06:00", december, ZONE, problems));
    assertEquals(
        Optional.of(Instant.parse("2026-12-27T14:00:00Z")),
        PidsBulletinForm.time("12-27 22:00", december, ZONE, problems));
    assertTrue(problems.isEmpty());
  }

  @Test
  void reportsEveryProblemAtOnce() {
    List<PidsBulletinForm.Problem> problems = new ArrayList<>();
    String longTitle = "长".repeat(PidsBulletinForm.TITLE_MAX + 1);

    Optional<PidsBulletinForm.Parsed> parsed =
        parse(form(longTitle, "PPK, XYZ", "DS", "明天", ""), problems);

    assertTrue(parsed.isEmpty());
    assertEquals(
        List.of(
            new PidsBulletinForm.Problem(
                "too-long",
                Map.of("field", "title", "max", String.valueOf(PidsBulletinForm.TITLE_MAX))),
            new PidsBulletinForm.Problem("unknown-station", Map.of("code", "XYZ")),
            new PidsBulletinForm.Problem("unknown-line", Map.of("code", "DS")),
            new PidsBulletinForm.Problem("bad-time", Map.of("value", "明天"))),
        problems);
  }

  @Test
  void requiresATitleAndAFuturePeriodInOrder() {
    List<PidsBulletinForm.Problem> missing = new ArrayList<>();
    parse(form("", "", "", "", ""), missing);
    assertEquals(List.of(PidsBulletinForm.Problem.of("title-required")), missing);

    List<PidsBulletinForm.Problem> reversed = new ArrayList<>();
    parse(form("标题", "", "", "2026-10-06 06:00", "2026-10-05 22:00"), reversed);
    assertEquals(List.of(PidsBulletinForm.Problem.of("ends-before-starts")), reversed);

    List<PidsBulletinForm.Problem> past = new ArrayList<>();
    parse(form("标题", "", "", "", "2026-10-05 08:00"), past);
    assertEquals(List.of(PidsBulletinForm.Problem.of("ended-already")), past);
  }

  /** 修改公告时填回对话框的内容，再提交一次得到同样的公告内容。 */
  @Test
  void roundTripsAnExistingBulletin() {
    PidsBulletin bulletin =
        PidsBulletinFixtures.bulletin(
            Set.of("PPK"),
            Set.of("MT"),
            PidsBulletin.Level.NORMAL,
            new PidsBulletin.Text("标题", "Title"),
            new PidsBulletin.Text("正文", "Body"),
            Optional.of(Instant.parse("2026-10-05T14:00:00Z")),
            Optional.of(Instant.parse("2026-10-05T22:00:00Z")));
    PidsBulletinForm form = PidsBulletinForm.of(bulletin, ZONE);
    assertEquals("2026-10-05 22:00", form.startsAt());

    PidsBulletinForm.Parsed parsed = parse(form, new ArrayList<>()).orElseThrow();

    assertEquals(bulletin.level(), parsed.level());
    assertEquals(bulletin.title(), parsed.title());
    assertEquals(bulletin.body(), parsed.body());
    assertEquals(bulletin.stations(), parsed.stations());
    assertEquals(bulletin.lines(), parsed.lines());
    assertEquals(bulletin.startsAt(), parsed.startsAt());
    assertEquals(bulletin.endsAt(), parsed.endsAt());
  }
}
