package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 一个方向一张规整网格：发车 = 相位 + k × 间隔；同方向多条 route 按 SWRR 切份额。 */
class GroupGridTest {

  private static final UUID RA = TimetableTestFixtures.routeId("RA");
  private static final UUID RZ = TimetableTestFixtures.routeId("RZ");

  @Test
  void slotsAreRegularFromPhaseToHorizon() {
    ServiceGroupClassifier.Direction direction =
        new ServiceGroupClassifier.Direction(
            "OP:S:A", "OP:S:C", List.of(new WeightedTripAllocator.Candidate("RA", 1)), List.of(RA));

    GroupGrid.DirectionGrid grid =
        GroupGrid.of(direction, 600, 80, 3600, (slot, index, assigned) -> true);

    assertEquals(600, grid.intervalSeconds());
    assertEquals(80, grid.phaseSeconds());
    assertEquals(
        List.of(80, 680, 1280, 1880, 2480, 3080),
        grid.slots().stream().map(GroupGrid.Slot::departureSeconds).toList());
    assertTrue(grid.slots().stream().allMatch(slot -> slot.routeId().equals(RA)));
  }

  /** 相位对间隔取模；相位大于窗口时没有格子。 */
  @Test
  void phaseWrapsAndEmptyWindowGivesNoSlots() {
    ServiceGroupClassifier.Direction direction =
        new ServiceGroupClassifier.Direction(
            "OP:S:A", "OP:S:C", List.of(new WeightedTripAllocator.Candidate("RA", 1)), List.of(RA));

    assertEquals(20, GroupGrid.of(direction, 300, 620, 3600, (s, i, a) -> true).phaseSeconds());
    assertTrue(GroupGrid.of(direction, 300, 100, 50, (s, i, a) -> true).slots().isEmpty());
  }

  /** 1:3 的两条 route 在同一方向上按 SWRR 分格：4 格里 RA 1 班、RZ 3 班，且不连开一串。 */
  @Test
  void weightsSplitSlotsWithinTheDirection() {
    ServiceGroupClassifier.Direction direction =
        new ServiceGroupClassifier.Direction(
            "OP:S:A",
            "OP:S:C",
            List.of(
                new WeightedTripAllocator.Candidate("RA", 1),
                new WeightedTripAllocator.Candidate("RZ", 3)),
            List.of(RA, RZ));

    GroupGrid.DirectionGrid grid =
        GroupGrid.of(direction, 300, 0, 2399, (slot, index, assigned) -> true);

    assertEquals(8, grid.slots().size());
    assertEquals(2, grid.slots().stream().filter(slot -> slot.routeId().equals(RA)).count());
    assertEquals(6, grid.slots().stream().filter(slot -> slot.routeId().equals(RZ)).count());
    for (int i = 0; i + 3 < grid.slots().size(); i++) {
      boolean anyRa = false;
      for (int k = i; k < i + 4; k++) {
        anyRa |= grid.slots().get(k).routeId().equals(RA);
      }
      assertTrue(anyRa, "任意连续 4 格里 RA 至少一班");
    }
  }

  /** 可行性否决：某条 route 在窗口末尾跑不完，这一格就不给它，让给别人。 */
  @Test
  void infeasibleCandidateGivesTheSlotAway() {
    ServiceGroupClassifier.Direction direction =
        new ServiceGroupClassifier.Direction(
            "OP:S:A",
            "OP:S:C",
            List.of(
                new WeightedTripAllocator.Candidate("RA", 1),
                new WeightedTripAllocator.Candidate("RZ", 1)),
            List.of(RA, RZ));

    GroupGrid.DirectionGrid grid =
        GroupGrid.of(direction, 300, 0, 1200, (slot, index, assigned) -> index != 0 || slot < 3);

    assertEquals(5, grid.slots().size());
    assertTrue(
        grid.slots().stream()
            .filter(slot -> slot.slot() >= 3)
            .allMatch(slot -> slot.routeId().equals(RZ)));
  }

  /**
   * 分时段时每段的 SWRR 按环上的格号对齐，不跨段连续计数：后段间隔 200、相位 100，第一班 1100 是环上第 5 格，1:1 的两条 route 里归第二条（连续计数会给第一条）。
   */
  @Test
  void eachSegmentAlignsSwrrWithTheRingSlot() {
    ServiceGroupClassifier.Direction direction =
        new ServiceGroupClassifier.Direction(
            "OP:S:A",
            "OP:S:C",
            List.of(
                new WeightedTripAllocator.Candidate("RA", 1),
                new WeightedTripAllocator.Candidate("RZ", 1)),
            List.of(RA, RZ));

    GroupGrid.DirectionGrid grid =
        GroupGrid.ofSegments(
            direction,
            List.of(
                new GroupGrid.Segment(0, 1000, 300, 0),
                new GroupGrid.Segment(1000, 2000, 200, 100)),
            WeightedTripAllocator.unconstrained());

    assertEquals(
        List.of(0, 300, 600, 900, 1100, 1300, 1500, 1700, 1900),
        grid.slots().stream().map(GroupGrid.Slot::departureSeconds).toList());
    assertEquals(
        List.of(RA, RZ, RA, RZ, RZ, RA, RZ, RA, RZ),
        grid.slots().stream().map(GroupGrid.Slot::routeId).toList());
    assertEquals(
        List.of(0, 1, 2, 3, 4, 5, 6, 7, 8),
        grid.slots().stream().map(GroupGrid.Slot::slot).toList());
  }
}
