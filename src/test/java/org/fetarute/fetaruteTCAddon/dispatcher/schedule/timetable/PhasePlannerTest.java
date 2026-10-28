package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 相位报告：往返对余数取哪一端、共用起点上的合成间隔。 */
class PhasePlannerTest {

  private static final UUID RA = TimetableTestFixtures.routeId("RA");
  private static final UUID RS = TimetableTestFixtures.routeId("RS");
  private static final UUID RT = TimetableTestFixtures.routeId("RT");

  private static ServiceGroupClassifier.Direction direction(
      String from, String to, String code, UUID id) {
    return new ServiceGroupClassifier.Direction(
        from, to, List.of(new WeightedTripAllocator.Candidate(code, 1)), List.of(id));
  }

  /** 余数按正向算：含终到正线折返点 route 的方向是正向，走行与折返取那条 route 自己的。 */
  @Test
  void residueIsTakenAtTheMainlineTurnbackEnd() {
    ServiceGroupClassifier.Group group =
        new ServiceGroupClassifier.Group(
            "default",
            List.of(
                direction("OP:S:A", "OP:S:C", "RA", RA),
                new ServiceGroupClassifier.Direction(
                    "OP:S:C",
                    "OP:S:A",
                    List.of(
                        new WeightedTripAllocator.Candidate("RS", 1),
                        new WeightedTripAllocator.Candidate("RT", 1)),
                    List.of(RS, RT))),
            List.of());

    List<PhasePlanner.Residue> residues =
        PhasePlanner.residues(
            List.of(group),
            Map.of("default", 600),
            Map.of(RA, 500, RS, 400, RT, 300),
            TurnaroundTable.fixed(30),
            Set.of(RS));

    assertEquals(1, residues.size());
    PhasePlanner.Residue residue = residues.get(0);
    assertEquals("OP:S:C→OP:S:A", residue.forwardKey());
    assertEquals(400, residue.runSeconds());
    assertEquals(30, residue.turnaroundSeconds());
    assertEquals((400 + 30) % 600, residue.residue());
  }

  /** 交错报告：按起点站台组叠加各子网格的发车，给出最小/中位/最大相邻间隔；只有一条发车的起点不列。 */
  @Test
  void interleaveReportSummarisesGapsPerOrigin() {
    ServiceGroupClassifier.Direction ac = direction("OP:S:A", "OP:S:C", "RA", RA);
    ServiceGroupClassifier.Direction ab = direction("OP:S:A", "OP:S:B", "RS", RS);
    ServiceGroupClassifier.Direction ca = direction("OP:S:C", "OP:S:A", "RT", RT);
    List<GroupGrid.DirectionGrid> grids =
        List.of(
            GroupGrid.of(ac, 600, 0, 1800, (s, i, a) -> true),
            GroupGrid.of(ab, 600, 200, 1800, (s, i, a) -> true),
            GroupGrid.of(ca, 5000, 0, 1800, (s, i, a) -> true));

    List<PhasePlanner.Interleave> report = PhasePlanner.interleaves(grids);

    assertEquals(1, report.size());
    PhasePlanner.Interleave a = report.get(0);
    assertEquals("OP:S:A", a.originGroup());
    assertEquals(7, a.departures());
    assertEquals(200, a.minGap());
    assertEquals(400, a.maxGap());
  }
}
