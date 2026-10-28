package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
import org.junit.jupiter.api.Test;

/**
 * 闭塞格：图索引带跟车规则时，足迹在区间、道岔、具体股道旁另登记 {@code [要用, 放出]}，闭塞格之间不留 separation。
 *
 * <p>与 {@link BlockingTimesTest} 同一条链与规则：慢车站站停 30 秒，快车只停两端。快车跟慢车按占用格只要晚发 100 秒（D–E 那条边：慢车 130 离开、快车
 * 30 进入），加 separation 10 是 110；按闭塞时间要 131 秒（慢车到 E 才放 B，快车起步就要 B）。
 */
class TimetableBlockingSlotsTest {

  private static final String A = "OP:S:A:1";
  private static final String B = "OP:S:B:1";
  private static final String C = "OP:S:C:1";
  private static final String D = "OP:S:D:1";
  private static final String E = "OP:S:E:1";
  private static final UUID LOC = TimetableTestFixtures.routeId("LOC");
  private static final UUID RAP = TimetableTestFixtures.routeId("RAP");
  private static final TimetableBuildOptions.Following RULES =
      new TimetableBuildOptions.Following(1.0D, 20.0D, 1, 1.0D, Map.of(LOC, 30L, RAP, 30L));
  private static final int SEPARATION = 10;

  private final RailGraph chain =
      TimetableTestFixtures.chain(
          List.of(A, B, C, D, E),
          new int[] {100, 100, 100, 100},
          new double[] {10.0, 10.0, 10.0, 10.0});
  private final TimetableConflictChecker.GraphIndex plain =
      TimetableConflictChecker.GraphIndex.of(chain);
  private final TimetableConflictChecker.GraphIndex blocking =
      plain.withBlocking(RULES, BlockingTimes.Trajectories.NONE);
  private final Map<UUID, TimetableConflictChecker.RouteProfile> profiles =
      Map.of(
          LOC, profile("LOC", List.of(A, B, C, D, E), 30), RAP, profile("RAP", List.of(A, E), 0));

  /** 晚发 120 秒：占用格加 separation 已经排开，闭塞格仍撞上（快车会在路上被慢车压慢），撞的只有闭塞格。 */
  @Test
  void aGapEnoughForOccupationButShortOfTheBlockingTimeIsAConflict() {
    assertTrue(conflicts(plain, 120).isEmpty());

    List<TimetableConflictChecker.Conflict> found = conflicts(blocking, 120);
    assertFalse(found.isEmpty());
    assertTrue(
        found.stream()
            .allMatch(conflict -> TimetableConflictChecker.isBlocking(conflict.resource())));
  }

  /** 闭塞格之间不留 separation：恰好晚发闭塞时间 131 秒就排开，少一秒就撞，separation 不再往上加。 */
  @Test
  void blockingSlotsTouchEndToEndWithoutSeparation() {
    assertTrue(conflicts(blocking, 131).isEmpty());
    assertFalse(conflicts(blocking, 130).isEmpty());
  }

  /**
   * 慢车跟快车：按占用格要晚发 10 秒（A–B 快车 10 离开、慢车 0 进入），闭塞时间 41 秒。separation 放大到 60 时占用口径要 70 秒、比闭塞时间严， 晚发 50
   * 秒就只有占用格撞上：separation 仍是占用口径的余量，闭塞时间不会把它放松。
   */
  @Test
  void occupationWithSeparationStillGovernsWhereItIsStricter() {
    List<TimetableConflictChecker.Movement> movements =
        List.of(movement("RAP-1", RAP, 0), movement("LOC-1", LOC, 50));
    List<TimetableConflictChecker.Conflict> found =
        TimetableConflictChecker.check(blocking, profiles, movements, List.of(), 60).conflicts();

    assertFalse(found.isEmpty());
    assertTrue(
        found.stream()
            .noneMatch(conflict -> TimetableConflictChecker.isBlocking(conflict.resource())));
  }

  /** 后车要等多久：闭塞格只算前车放出，占用格加 separation。 */
  @Test
  void waitSecondsAddsSeparationOnlyOnOccupationSlots() {
    TimetableConflictChecker.Conflict occupation = conflict("edge:x~y");
    TimetableConflictChecker.Conflict blockingSlot =
        conflict("edge:x~y" + TimetableConflictChecker.BLOCKING_SUFFIX);

    assertEquals(25, occupation.waitSeconds(SEPARATION));
    assertEquals(15, blockingSlot.waitSeconds(SEPARATION));
  }

  /** 自己另算闭塞时间的地方（快车被卡、瓶颈报告）用不带闭塞格的索引，足迹里只有占用格。 */
  @Test
  void theIndexWithoutBlockingGivesOccupationSlotsOnly() {
    List<TimetableConflictChecker.Slot> with =
        new TimetableConflictChecker.Footprints(blocking).of(LOC, profiles.get(LOC)).slots();
    List<TimetableConflictChecker.Slot> without =
        new TimetableConflictChecker.Footprints(blocking.withoutBlocking())
            .of(LOC, profiles.get(LOC))
            .slots();

    assertTrue(with.stream().anyMatch(slot -> TimetableConflictChecker.isBlocking(slot.key())));
    assertTrue(without.stream().noneMatch(slot -> TimetableConflictChecker.isBlocking(slot.key())));
    assertEquals(
        new TimetableConflictChecker.Footprints(plain).of(LOC, profiles.get(LOC)).slots(), without);
  }

  private List<TimetableConflictChecker.Conflict> conflicts(
      TimetableConflictChecker.GraphIndex index, int gap) {
    return TimetableConflictChecker.check(
            index,
            profiles,
            List.of(movement("LOC-1", LOC, 0), movement("RAP-1", RAP, gap)),
            List.of(),
            SEPARATION)
        .conflicts();
  }

  private static TimetableConflictChecker.Movement movement(String code, UUID route, int start) {
    return new TimetableConflictChecker.Movement(code, route, start, Optional.empty());
  }

  private static TimetableConflictChecker.Conflict conflict(String resource) {
    return new TimetableConflictChecker.Conflict(
        TimetableConflictChecker.Kind.TRACK,
        resource,
        "A-1",
        "B-1",
        0,
        100,
        85,
        120,
        Optional.empty(),
        Optional.empty());
  }

  private TimetableConflictChecker.RouteProfile profile(
      String code, List<String> waypoints, int dwell) {
    UUID id = TimetableTestFixtures.routeId(code);
    RouteDefinition route = TimetableTestFixtures.route(code, waypoints);
    List<RouteStop> stops = TimetableTestFixtures.stops(id, waypoints.size(), dwell);
    TimetableTimingCalculator.TimingResult timing =
        new TimetableTimingCalculator()
            .compute(chain, TimetableTestFixtures.perEdgeSpeedModel(), route, stops, Duration.ZERO);
    assertTrue(timing.ok(), () -> code + ": " + timing.failure());
    return new TimetableConflictChecker.RouteProfile(
        id,
        code,
        timing.stops(),
        timing.segments(),
        TimetableConflictChecker.platformsOf(timing.stops(), stops, route.waypoints()));
  }
}
