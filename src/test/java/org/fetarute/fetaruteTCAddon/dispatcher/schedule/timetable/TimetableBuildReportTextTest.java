package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** 报告文案：取消分类、搜索失败的说法、让不掉的残余汇总。 */
class TimetableBuildReportTextTest {

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
  void failureTextNamesTheBusiestResources() {
    TimetableConflictChecker.Report report =
        new TimetableConflictChecker.Report(
            List.of(
                conflict("edge:a~b"),
                conflict("edge:a~b"),
                conflict("junction:j"),
                conflict("single:s")));

    String search = TimetableBuildReportText.describeSearchFailure(120, 240, 32, report);

    assertTrue(search.contains("放宽到 240s 的范围内没有找到"), search);
    assertTrue(search.contains("至多试 32 组"), search);
    assertTrue(search.contains("TRACK edge:a~b×2"), search);
  }

  /** 失败时只列让不掉的残余：先按判决、再按让车点汇总（多的在前），然后给前几条明细，超出的说还有几处。 */
  @Test
  void unabsorbableResidualsAreSummarisedByVerdictAndWaitingPoint() {
    List<ConflictAbsorption.Residual> residuals = new java.util.ArrayList<>();
    for (int i = 0; i < 9; i++) {
      residuals.add(
          residual(
              "L1-" + i, "OP:S:A", ConflictAbsorption.Verdict.NO_WAITING_CAPACITY, "edge:a~b"));
    }
    residuals.add(
        residual("L1-9", "OP:S:B", ConflictAbsorption.Verdict.NO_WAITING_CAPACITY, "edge:c~d"));
    residuals.add(residual("L2-1", "OP:S:C", ConflictAbsorption.Verdict.STUB_TERMINAL, "single:s"));

    List<String> lines = TimetableBuildReportText.describeUnabsorbable(residuals, 0);

    assertEquals("  让不掉的冲突按判决与让车点：", lines.get(0));
    assertEquals("  · 容量 1 端点 1 处：OP:S:C 1", lines.get(1));
    assertEquals("  · 让车点无容量 10 处：OP:S:A 9、OP:S:B 1", lines.get(2));
    assertTrue(lines.get(3).startsWith("  · [让车点无容量] L1-0 等 400s 于 OP:S:A："), lines.get(3));
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
