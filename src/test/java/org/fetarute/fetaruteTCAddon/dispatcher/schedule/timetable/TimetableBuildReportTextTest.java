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

  /** "取消班次"一行按原因分类写：出入库缺失合在一起，严格模式取消、端点排队超限各写各的，没有的原因不写。 */
  @Test
  void droppedTripsAreCountedByReason() {
    List<TimetableBuildResult.DroppedTrip> dropped =
        List.of(
            new TimetableBuildResult.DroppedTrip(
                "R1", "05:00", VehicleDutyPlanner.UnassignedReason.NO_CREATE_ACCESS),
            new TimetableBuildResult.DroppedTrip(
                "R1", "23:50", VehicleDutyPlanner.UnassignedReason.NO_RETURN_ACCESS),
            new TimetableBuildResult.DroppedTrip(
                "R2", "08:00", VehicleDutyPlanner.UnassignedReason.CONFLICT_CANCELLED),
            new TimetableBuildResult.DroppedTrip(
                "R2", "09:00", VehicleDutyPlanner.UnassignedReason.CONFLICT_CANCELLED),
            new TimetableBuildResult.DroppedTrip(
                "R2", "10:00", VehicleDutyPlanner.UnassignedReason.STUB_SATURATED));

    assertEquals(
        "5（起终点缺出库/回库线路 2、端点排队或让车累计超限 1、严格模式取消让不掉的 2，详见下方警告）",
        TimetableBuildReportText.describeDroppedCount(dropped));
    assertEquals(
        "1（严格模式取消让不掉的 1，详见下方警告）",
        TimetableBuildReportText.describeDroppedCount(List.of(dropped.get(2))));
  }

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
            TurnaroundTable.fixed(60));

    assertEquals(2, lines.size());
    assertTrue(lines.get(0).startsWith("结构下界: OP:S:X"), lines.get(0));
    assertTrue(
        lines.get(0).contains("110s") && lines.get(0).contains("--turnaround 60"), lines.get(0));
    assertTrue(lines.get(0).contains("下界 55s"), lines.get(0));
    assertTrue(lines.get(1).startsWith("端点串行: OP:S:X 经过 40 次、占用 122%"), lines.get(1));
    assertTrue(lines.get(1).contains("超过 100%"), lines.get(1));
    assertTrue(lines.get(1).contains("2 班偏离网格（延后 1 / 提前 1，最大 +60s）"), lines.get(1));
    assertTrue(lines.get(1).contains("无处等待 1 班；截断 3 班"), lines.get(1));
    assertTrue(
        TimetableBuildReportText.describeTerminals(List.of(), List.of(), TurnaroundTable.fixed(60))
            .isEmpty());
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
        TimetableBuildReportText.describeSearchFailure(
            120, 480, report, List.of(SATURATED), TurnaroundTable.fixed(60));
    String search =
        TimetableBuildReportText.describeSearchFailure(
            120, 480, report, List.of(RELAXED), TurnaroundTable.fixed(60));

    assertTrue(structural.contains("结构上不可能"), structural);
    assertTrue(
        structural.contains("利用率 122%") && structural.contains("--turnaround 60"), structural);
    assertFalse(search.contains("结构上不可能"), search);
    assertTrue(search.contains("放宽到 480s 的范围内没有找到"), search);
    assertTrue(search.contains("TRACK edge:a~b×2"), search);
  }

  /** 失败时只列让不掉的残余：先按判决、再按让车点汇总（多的在前），然后给前几条明细，超出的说还有几处。 */
  @Test
  void unabsorbableResidualsAreSummarisedByVerdictAndWaitingPoint() {
    List<ConflictAbsorption.Residual> residuals = new java.util.ArrayList<>();
    for (int i = 0; i < 9; i++) {
      residuals.add(
          residual(
              "WS-" + i, "SURC:S:CHT", ConflictAbsorption.Verdict.NO_WAITING_CAPACITY, "edge:a~b"));
    }
    residuals.add(
        residual("WS-9", "SURC:S:NTA", ConflictAbsorption.Verdict.NO_WAITING_CAPACITY, "edge:c~d"));
    residuals.add(
        residual("MT-1", "SURC:S:PPK", ConflictAbsorption.Verdict.STUB_TERMINAL, "single:s"));

    List<String> lines = TimetableBuildReportText.describeUnabsorbable(residuals, 0);

    assertEquals("  让不掉的冲突按判决与让车点：", lines.get(0));
    assertEquals("  · 容量 1 端点 1 处：SURC:S:PPK 1", lines.get(1));
    assertEquals("  · 让车点无容量 10 处：SURC:S:CHT 9、SURC:S:NTA 1", lines.get(2));
    assertTrue(lines.get(3).startsWith("  · [让车点无容量] WS-0 等 400s 于 SURC:S:CHT："), lines.get(3));
    assertTrue(lines.get(3).contains("TRACK edge:a~b: T1 [00:00:00–00:00:10]"), lines.get(3));
    assertEquals(
        "  · … 另有 " + (residuals.size() - TimetableBuildResult.CONFLICT_DETAIL_LIMIT) + " 处",
        lines.get(lines.size() - 1));
    assertEquals(3 + TimetableBuildResult.CONFLICT_DETAIL_LIMIT + 1, lines.size());
    assertTrue(TimetableBuildReportText.describeUnabsorbable(List.of(), 0).isEmpty());
  }

  private static ConflictAbsorption.Residual residual(
      String mover, String waitingPoint, ConflictAbsorption.Verdict verdict, String resource) {
    return new ConflictAbsorption.Residual(
        conflict(resource), mover, 400, Optional.of(waitingPoint), 0, verdict);
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
