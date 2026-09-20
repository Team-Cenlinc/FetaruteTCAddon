package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** 报告文案：端点两行，以及"结构上不可能"与"范围内没找到"两种失败说法。 */
class TimetableBuildReportTextTest {

  private static final TerminalSerializer.TerminalReport SATURATED =
      new TerminalSerializer.TerminalReport(
          "OP:S:X", 40, 4400, 1.22D, 10, 10, 30, 110, 4, 2.0D, 55, 1, 3);
  private static final TerminalSerializer.TerminalReport RELAXED =
      new TerminalSerializer.TerminalReport(
          "OP:S:X", 10, 1100, 0.31D, 10, 10, 30, 110, 4, 2.0D, 55, 0, 0);

  @Test
  void terminalLinesAreRendered() {
    List<String> lines =
        TimetableBuildReportText.describeTerminals(
            List.of(SATURATED),
            List.of(
                new TimetableBuildResult.TripShift(
                    "RA-002", 100, 160, TerminalSerializer.Shift.Reason.WAIT_FOR_TERMINAL),
                new TimetableBuildResult.TripShift(
                    "RX-001", 300, 80, TerminalSerializer.Shift.Reason.ANCHORED_TO_VEHICLE)),
            60);

    assertEquals(2, lines.size());
    assertTrue(lines.get(0).startsWith("结构下界: OP:S:X"), lines.get(0));
    assertTrue(
        lines.get(0).contains("110s") && lines.get(0).contains("--turnaround 60"), lines.get(0));
    assertTrue(lines.get(0).contains("下界 55s"), lines.get(0));
    assertTrue(lines.get(1).startsWith("端点串行: OP:S:X 经过 40 次、占用 122%"), lines.get(1));
    assertTrue(lines.get(1).contains("超过 100%"), lines.get(1));
    assertTrue(lines.get(1).contains("2 班偏离网格（延后 1 / 提前 1，最大 +60s）"), lines.get(1));
    assertTrue(lines.get(1).contains("无处等待 1 班；截断 3 班"), lines.get(1));
    assertTrue(TimetableBuildReportText.describeTerminals(List.of(), List.of(), 60).isEmpty());
  }

  @Test
  void failureTextDistinguishesStructuralFromSearch() {
    TimetableConflictChecker.Report report =
        new TimetableConflictChecker.Report(
            List.of(
                conflict("edge:a~b"),
                conflict("edge:a~b"),
                conflict("junction:j"),
                conflict("single:s")));

    String structural =
        TimetableBuildReportText.describeSearchFailure(120, 480, report, List.of(SATURATED), 60);
    String search =
        TimetableBuildReportText.describeSearchFailure(120, 480, report, List.of(RELAXED), 60);

    assertTrue(structural.contains("结构上不可能"), structural);
    assertTrue(
        structural.contains("利用率 122%") && structural.contains("--turnaround 60"), structural);
    assertFalse(search.contains("结构上不可能"), search);
    assertTrue(search.contains("放宽到 480s 的范围内没有找到"), search);
    assertTrue(search.contains("TRACK edge:a~b×2"), search);
  }

  private static TimetableConflictChecker.Conflict conflict(String resource) {
    TimetableConflictChecker.Kind kind =
        resource.startsWith("edge")
            ? TimetableConflictChecker.Kind.TRACK
            : resource.startsWith("junction")
                ? TimetableConflictChecker.Kind.JUNCTION
                : TimetableConflictChecker.Kind.SINGLE_LINE;
    return new TimetableConflictChecker.Conflict(
        kind, resource, "T1", "T2", 0, 10, 5, 15, Optional.empty(), Optional.empty());
  }
}
