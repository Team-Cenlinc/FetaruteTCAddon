package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** 运行计划书解析：段头、注释、组键、跨零点、重叠、坏时刻、加开写法与分页存取。 */
class TimetablePlanBookTest {

  private static TimetablePlanBook.Parsed parse(String... pages) {
    return TimetablePlanBook.parsePages(List.of(pages));
  }

  private static TimetablePlanBook.Issue onlyIssue(TimetablePlanBook.Parsed parsed) {
    assertEquals(1, parsed.issues().size(), () -> parsed.issues().toString());
    return parsed.issues().get(0);
  }

  @Test
  void parsesBandsAndPinsInBookOrder() {
    TimetablePlanBook.Parsed parsed =
        parse(
            """
            [时段]
            WS/WS_Full 06:00-07:00 300
            WS_Full    07:00-09:30 180
            [定点]
            05:32     WS-2C_FullR
            07:15:30  MT-3N_DPExp 加开
            """);

    assertTrue(parsed.ok(), () -> parsed.issues().toString());
    assertEquals(
        List.of(
            new Band(Optional.of("WS"), "WS_Full", 6 * 3600, 7 * 3600, 300),
            new Band(Optional.empty(), "WS_Full", 7 * 3600, 9 * 3600 + 1800, 180)),
        parsed.pattern().bands());
    assertEquals(
        List.of(
            new Pin(5 * 3600 + 32 * 60, "WS-2C_FullR", false),
            new Pin(7 * 3600 + 15 * 60 + 30, "MT-3N_DPExp", true)),
        parsed.pattern().pins());
    assertEquals("WS/WS_Full", parsed.pattern().bands().get(0).key());
  }

  @Test
  void ignoresCommentsBlankLinesAndHeaderCase() {
    TimetablePlanBook.Parsed parsed =
        parse(
            """
            # 工作日
            [BANDS]   # 英文段头不分大小写

            default 06:00 - 07:00 240  # 起止中间可以有空格
            【Pins】
            # 05:00 WS-1
            06:10 WS-1 extra
            06:20 WS-2 +
            """);

    assertTrue(parsed.ok(), () -> parsed.issues().toString());
    assertEquals(1, parsed.pattern().bands().size());
    assertEquals(240, parsed.pattern().bands().get(0).intervalSeconds());
    assertEquals(2, parsed.pattern().pins().size());
    assertTrue(parsed.pattern().pins().stream().allMatch(Pin::extra));
  }

  @Test
  void acceptsTimesPastMidnight() {
    TimetablePlanBook.Parsed parsed =
        parse("[时段]\nWS_Full 23:00-25:30 600\n[定点]\n24:30 WS-1\n47:59:59 WS-2");

    assertTrue(parsed.ok(), () -> parsed.issues().toString());
    Band band = parsed.pattern().bands().get(0);
    assertEquals(23 * 3600, band.fromSecond());
    assertEquals(25 * 3600 + 1800, band.toSecond());
    assertEquals(88_200, parsed.pattern().pins().get(0).secondOfDay());
    assertEquals("24:30", TimetablePlanBook.formatClock(88_200));
    assertEquals("47:59:59", TimetablePlanBook.formatClock(172_799));
  }

  @Test
  void rejectsOverlappingBandsOfSameKeyAndPointsAtBothLines() {
    TimetablePlanBook.Parsed parsed =
        parse(
            "[时段]\nWS_Full 06:00-08:00 300\nWS/WS_Full 07:00-09:00 200",
            "[时段]\nWS_Full 07:30-09:00 180");

    TimetablePlanBook.Issue issue = onlyIssue(parsed);
    assertEquals(2, issue.page());
    assertEquals(2, issue.line());
    assertTrue(issue.reason().contains("第 1 页第 2 行"), issue.reason());
    assertTrue(issue.message().startsWith("第 2 页第 2 行："), issue.message());
    assertTrue(parsed.pattern().isEmpty(), "有错时不给半本计划");
  }

  @Test
  void adjacentBandsDoNotOverlap() {
    assertTrue(parse("[时段]\nWS_Full 06:00-07:00 300\nWS_Full 07:00-08:00 200").ok());
  }

  @Test
  void rejectsBadTimes() {
    assertFalse(parse("[时段]\nWS_Full 06:60-07:00 300").ok());
    assertFalse(parse("[时段]\nWS_Full 48:00-49:00 300").ok());
    assertFalse(parse("[时段]\nWS_Full 6点-7点 300").ok());
    assertFalse(parse("[定点]\n7:5 WS-1").ok());
    assertTrue(TimetablePlanBook.parseClock("7:05").isPresent());
    assertEquals(Optional.of(7 * 3600 + 5 * 60), TimetablePlanBook.parseClock("07：05"));

    TimetablePlanBook.Issue reversed = onlyIssue(parse("[时段]\nWS_Full 08:00-07:00 300"));
    assertTrue(reversed.reason().contains("早于"), reversed.reason());
  }

  @Test
  void rejectsBadIntervalAndGroupKey() {
    assertFalse(parse("[时段]\nWS_Full 06:00-07:00 0").ok());
    assertFalse(parse("[时段]\nWS_Full 06:00-07:00 3分").ok());
    assertFalse(parse("[时段]\n/WS_Full 06:00-07:00 300").ok());
    assertFalse(parse("[时段]\nWS/A/B 06:00-07:00 300").ok());
    assertFalse(parse("[时段]\nWS_Full 06:00-07:00").ok());
  }

  @Test
  void rejectsUnknownExtraMarkAndDuplicatePin() {
    assertTrue(onlyIssue(parse("[定点]\n07:00 WS-1 临时")).reason().contains("加开"));
    TimetablePlanBook.Issue duplicate = onlyIssue(parse("[定点]\n07:00 WS-1\n07:00 WS-1 加开"));
    assertEquals(3, duplicate.line());
    assertTrue(parse("[定点]\n07:00 WS-1\n07:00 WS-2").ok(), "同一时刻不同 route 可以");
  }

  @Test
  void reportsUnknownHeaderOnceAndContentOutsideSections() {
    TimetablePlanBook.Parsed unknown = parse("[时刻]\n07:00 WS-1\n08:00 WS-2");
    TimetablePlanBook.Issue issue = onlyIssue(unknown);
    assertEquals(1, issue.line());
    assertTrue(issue.reason().contains("未知段头"), issue.reason());

    assertTrue(onlyIssue(parse("07:00 WS-1")).reason().contains("段头"));
  }

  @Test
  void emptyBookIsAnIssue() {
    TimetablePlanBook.Issue issue = onlyIssue(parse("# 只有注释\n[时段]\n", ""));
    assertEquals(0, issue.page());
    assertFalse(issue.message().startsWith("第"));
  }

  @Test
  void bodyKeepsPageBoundaries() {
    List<String> pages = List.of("[时段]\nWS_Full 06:00-07:00 300", "[定点]\n05:32 WS-1");
    String body = TimetablePlanBook.body(pages);

    assertEquals(pages, TimetablePlanBook.pages(body));
    assertEquals(TimetablePlanBook.parsePages(pages), TimetablePlanBook.parse(body));
  }
}
