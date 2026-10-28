package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.company.model.RouteOperationType;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.plan.Pin;
import org.junit.jupiter.api.Test;

/** 定点班次套到网格上：替换最近格位、前后平滑一半偏差、加开、窗口外忽略、挪动顺着车辆接续传到换不了车的折返站对面。 */
class PinnedSlotsTest {

  private static final int START = 6 * 3600;
  private static final UUID ROUTE = UUID.nameUUIDFromBytes(new byte[] {7});
  private static final ServiceGroupClassifier.Direction DIRECTION =
      new ServiceGroupClassifier.Direction(
          "A", "B", List.of(new WeightedTripAllocator.Candidate("R1", 1)), List.of(ROUTE));
  private static final UUID BACK = UUID.nameUUIDFromBytes(new byte[] {8});
  private static final ServiceGroupClassifier.Direction BACK_DIRECTION =
      new ServiceGroupClassifier.Direction(
          "B", "A", List.of(new WeightedTripAllocator.Candidate("R2", 1)), List.of(BACK));
  private static final List<ServiceGroupClassifier.Group> GROUPS =
      List.of(new ServiceGroupClassifier.Group("g", List.of(DIRECTION), List.of()));
  private static final List<TimetableRoutePlan> PLANS = List.of(plan(ROUTE, "R1", "A", "B"));

  @Test
  void replacesNearestSlotAndSmoothsNeighbours() {
    // 格位 0/300/600/900；定点 +660（离 600 最近，偏 +60）：600 → 660，300 → 330，900 → 930。
    List<Integer> times = apply(new Pin(START + 660, "R1", false));
    assertEquals(List.of(0, 330, 660, 930), times);
  }

  @Test
  void extraAddsATripWithoutTouchingTheGrid() {
    List<Integer> times = apply(new Pin(START + 450, "R1", true));
    assertEquals(List.of(0, 300, 450, 600, 900), times);
  }

  @Test
  void outsideTheWindowIsIgnoredWithANote() {
    List<String> notes = new ArrayList<>();
    List<Integer> times = apply(notes, new Pin(START - 60, "R1", false));
    assertEquals(List.of(0, 300, 600, 900), times);
    assertTrue(notes.get(0).contains("忽略"));
  }

  @Test
  void smoothingNeverCrossesThePin() {
    // 定点 +480（离 600 最近，偏 −120）：前一班 300 往前挪 60 到 240，后一班 900 往前挪 60 到 840。
    List<Integer> times = apply(new Pin(START + 480, "R1", false));
    assertEquals(List.of(0, 240, 480, 840), times);
  }

  /**
   * 往返两个方向，去程终点 B 换不了车（没有出入库），回程终点 A 能换。去程全程 100 秒、折返 20 秒，回程比去程晚 330 秒发， 车在 B 等 210 秒；闲置上限 300 秒。
   *
   * <p>定点把去程 600 挪到 460（偏 −140），车在 B 就绪提前到 580，原来接的回程 930 要等 350 秒、超过上限：回程只挪到够得着的 880（−50）。
   * 平滑的两班（300→230、900→830）就绪提前 70 秒，等 280 秒仍在上限内，它们接的回程不挪。回程到 A 能换车，不再往下传。
   */
  @Test
  void theContinuationAtATerminalWithoutDepotMovesJustEnoughToStayWithinIdle() {
    List<String> notes = new ArrayList<>();
    List<TimetableBuilder.Placed> placed = new ArrayList<>();
    for (int i = 0; i < 4; i++) {
      placed.add(
          new TimetableBuilder.Placed(new GroupGrid.Slot(i * 300, ROUTE, 0, i), DIRECTION, false));
      placed.add(
          new TimetableBuilder.Placed(
              new GroupGrid.Slot(i * 300 + 330, BACK, 0, i), BACK_DIRECTION, false));
    }
    PinnedSlots.Turns turns =
        new PinnedSlots.Turns(
            Map.of(ROUTE, "A", BACK, "B"),
            Map.of(ROUTE, "B", BACK, "A"),
            Map.of(ROUTE, "p", BACK, "p"),
            Map.of(ROUTE, 20, BACK, 20),
            Set.of(BACK),
            300);

    List<TimetableBuilder.Placed> out =
        PinnedSlots.apply(
            placed,
            options(new Pin(START + 460, "R1", false)),
            List.of(
                new ServiceGroupClassifier.Group(
                    "g", List.of(DIRECTION, BACK_DIRECTION), List.of())),
            List.of(PLANS.get(0), plan(BACK, "R2", "B", "A")),
            Map.of(ROUTE, 0, BACK, 1),
            Map.of(ROUTE, 100, BACK, 100),
            turns,
            notes);

    assertEquals(List.of(0, 230, 460, 830), times(out, ROUTE));
    assertEquals(List.of(330, 630, 880, 1230), times(out, BACK));
    assertTrue(notes.stream().anyMatch(note -> note.contains("传到 1 班")));
  }

  private static List<Integer> times(List<TimetableBuilder.Placed> out, UUID route) {
    return out.stream()
        .filter(item -> item.slot().routeId().equals(route))
        .map(item -> item.slot().departureSeconds())
        .sorted()
        .toList();
  }

  private static TimetableRoutePlan plan(UUID route, String code, String from, String to) {
    return new TimetableRoutePlan(
        route,
        code,
        RouteOperationType.OPERATION,
        1,
        List.of(),
        from,
        to,
        Optional.empty(),
        Optional.empty());
  }

  private static TimetableBuildOptions options(Pin... pins) {
    return new TimetableBuildOptions(
            START,
            START + 2 * 3600,
            Duration.ofSeconds(300),
            Duration.ofSeconds(20),
            VehicleDutyPlanner.Limits.defaults(),
            "",
            ZoneOffset.UTC)
        .withSchedule(new ServiceSchedule(Map.of(), List.of(pins)));
  }

  private static List<Integer> apply(Pin... pins) {
    return apply(new ArrayList<>(), pins);
  }

  private static List<Integer> apply(List<String> notes, Pin... pins) {
    List<TimetableBuilder.Placed> placed = new ArrayList<>();
    for (int i = 0; i < 4; i++) {
      placed.add(
          new TimetableBuilder.Placed(new GroupGrid.Slot(i * 300, ROUTE, 0, i), DIRECTION, false));
    }
    return PinnedSlots.apply(
            placed,
            options(pins),
            GROUPS,
            PLANS,
            Map.of(ROUTE, 0),
            Map.of(ROUTE, 100),
            PinnedSlots.Turns.NONE,
            notes)
        .stream()
        .sorted(Comparator.comparingInt(item -> item.slot().departureSeconds()))
        .map(item -> item.slot().departureSeconds())
        .toList();
  }
}
