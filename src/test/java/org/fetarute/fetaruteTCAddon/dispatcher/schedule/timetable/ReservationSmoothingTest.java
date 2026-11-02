package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.company.model.RouteOperationType;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.scope.NeighborTimetable;
import org.junit.jupiter.api.Test;

/**
 * 让车均摊：一班给邻表让车整趟延后之后，同一条 route 的下一班不弹回名义时刻，跟着延后一截（打个折），班距不会一长一短；
 * 再下一班照常按名义时刻——只有真让车的延后往下传，均摊出来的延后不再传。
 *
 * <p>直链 A–B–C，每条边 10 秒。RA 每 300 秒一班；邻表在 296–366 秒间连着跑七趟 A→B，第二班（名义 300）只能让到 366。
 */
class ReservationSmoothingTest {

  private static final String A = "OP:S:A:1";
  private static final String B = "OP:S:B:1";
  private static final String C = "OP:S:C:1";
  private static final UUID RA = TimetableTestFixtures.routeId("RA");
  private static final UUID NB = TimetableTestFixtures.routeId("NB");
  private static final UUID TABLE =
      UUID.nameUUIDFromBytes("smooth".getBytes(StandardCharsets.UTF_8));

  private final RailGraph graph =
      TimetableTestFixtures.chain(List.of(A, B, C), new int[] {100, 100}, new double[] {10, 10});

  @Test
  void aYieldIsSpreadOverTheFollowingTripsOfTheSameRoute() {
    List<TimetableTrip> trips = new ArrayList<>();
    List<VehicleDuty> duties = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      TimetableTrip trip =
          new TimetableTrip(
              id("RA-" + i), TABLE, RA, i, "RA-" + i, 300 * i, Optional.of(id("D" + i)));
      trips.add(trip);
      duties.add(
          new VehicleDuty(
              id("D" + i),
              TABLE,
              i,
              "D" + i,
              "OP:D:DEP:1",
              "OP:D:DEP:1",
              Optional.empty(),
              Optional.empty(),
              List.of(trip.id()),
              300 * i,
              300 * i + 30,
              300 * i + 30,
              VehicleDuty.CloseReason.HORIZON_END));
    }
    TimetableConflictChecker.RouteProfile ra = profile("RA", RA, List.of(A, B, C));
    Timetable provisional =
        new Timetable(
            TABLE,
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            "TT",
            "夹具",
            TimetableStatus.DRAFT,
            ZoneId.of("UTC"),
            0,
            86_400,
            List.of(
                new TimetableRoutePlan(
                    RA,
                    "RA",
                    RouteOperationType.OPERATION,
                    1,
                    ra.stops(),
                    A,
                    C,
                    Optional.empty(),
                    Optional.empty())),
            trips,
            duties,
            Optional.empty(),
            Instant.EPOCH,
            Instant.EPOCH);

    ReservationScheduler.Result result =
        ReservationScheduler.schedule(
            new ReservationScheduler.Input(
                provisional,
                Map.of(RA, ra),
                TimetableConflictChecker.GraphIndex.of(graph),
                0,
                86_400,
                0,
                300,
                300,
                VehicleDutyPlanner.Legs.none(),
                VehicleDutyPlanner.Limits.defaults(),
                Set.of(RA),
                List.of(neighbor()),
                Set.of()));

    int step = 300 / ReservationScheduler.SMOOTH_RECOVERY_SECONDS;
    DutyRetiming.Shift yielded = shiftOf(result, "RA-1");
    assertEquals(DutyRetiming.Shift.Reason.YIELDED, yielded.reason(), result.shifts()::toString);
    int lag = yielded.actualSeconds() - yielded.nominalSeconds();
    assertTrue(lag > step * 2, () -> "要让得够久才看得出均摊: " + lag);

    DutyRetiming.Shift next = shiftOf(result, "RA-2");
    assertEquals(DutyRetiming.Shift.Reason.SMOOTHED, next.reason(), result.shifts()::toString);
    assertEquals(lag - step, next.actualSeconds() - next.nominalSeconds());
    assertTrue(
        result.shifts().stream().noneMatch(shift -> shift.tripId().equals(id("RA-3"))),
        () -> "均摊出来的延后不再往下传: " + result.shifts());
  }

  private DutyRetiming.Shift shiftOf(ReservationScheduler.Result result, String code) {
    UUID trip = id(code);
    return result.shifts().stream()
        .filter(shift -> shift.tripId().equals(trip))
        .findFirst()
        .orElseThrow(() -> new AssertionError(code + " 没有偏离: " + result.shifts()));
  }

  /** 邻表：296–356 秒每 10 秒一趟 A→B，把 A–B 占到 366 秒。 */
  private NeighborTimetable neighbor() {
    TimetableConflictChecker.RouteProfile nb = profile("NB", NB, List.of(A, B));
    List<TimetableConflictChecker.Movement> movements = new ArrayList<>();
    for (int start = 296; start <= 356; start += 10) {
      movements.add(
          new TimetableConflictChecker.Movement("NB-" + start, NB, start, Optional.of("L2")));
    }
    return new NeighborTimetable(
        UUID.nameUUIDFromBytes("L2".getBytes(StandardCharsets.UTF_8)),
        "L2",
        Instant.EPOCH,
        ZoneId.of("UTC"),
        1,
        false,
        false,
        Map.of(NB, nb),
        movements,
        List.of(),
        List.of());
  }

  private TimetableConflictChecker.RouteProfile profile(String code, UUID id, List<String> nodes) {
    RouteDefinition route = TimetableTestFixtures.route(code, nodes);
    List<RouteStop> stops = TimetableTestFixtures.stops(id, nodes.size(), 0);
    TimetableTimingCalculator.TimingResult timing =
        new TimetableTimingCalculator()
            .compute(graph, TimetableTestFixtures.perEdgeSpeedModel(), route, stops, Duration.ZERO);
    assertTrue(timing.ok(), () -> String.valueOf(timing.failure()));
    return new TimetableConflictChecker.RouteProfile(
        id,
        code,
        timing.stops(),
        timing.segments(),
        TimetableConflictChecker.platformsOf(timing.stops(), stops, route.waypoints()));
  }

  private static UUID id(String code) {
    return UUID.nameUUIDFromBytes(code.getBytes(StandardCharsets.UTF_8));
  }
}
