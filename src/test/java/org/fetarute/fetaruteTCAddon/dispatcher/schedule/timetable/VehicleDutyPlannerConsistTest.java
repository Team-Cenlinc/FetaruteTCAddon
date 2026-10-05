package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.VehicleDutyPlanner.ConsistOption;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.VehicleDutyPlanner.Leg;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.VehicleDutyPlanner.Legs;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.VehicleDutyPlanner.Limits;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.VehicleDutyPlanner.PlannedTrip;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.VehicleDutyPlanner.UnassignedReason;
import org.junit.jupiter.api.Test;

/** 车型跟着车走：开交路按班次份额选车型、按本交路车型的时分接班、只接许可车型、出入库走车型自己的变体。 */
class VehicleDutyPlannerConsistTest {

  private static final UUID TIMETABLE = UUID.randomUUID();
  private static final UUID AB = UUID.randomUUID();
  private static final UUID BA = UUID.randomUUID();
  private static final String A = "OP:S:A:1";
  private static final String B = "OP:S:B:1";
  private static final String DEPOT = "OP:D:DEP:1";

  /** 两站都有两个车型各自的出库与回库段。 */
  private static Legs legs(String... consists) {
    List<Leg> creates = new ArrayList<>();
    List<Leg> returns = new ArrayList<>();
    Map<UUID, String> station = new HashMap<>();
    for (String consist : consists) {
      for (String stop : List.of(A, B)) {
        Leg create =
            new Leg(UUID.randomUUID(), "CRT-" + consist, DEPOT, 60, false, Optional.of(consist));
        Leg ret =
            new Leg(UUID.randomUUID(), "RET-" + consist, DEPOT, 60, false, Optional.of(consist));
        creates.add(create);
        returns.add(ret);
        station.put(create.routeId(), stop);
        station.put(ret.routeId(), stop);
      }
    }
    return Legs.of(creates, returns, station);
  }

  private static PlannedTrip trip(
      int index, UUID route, String from, String to, int departure, ConsistOption... options) {
    return new PlannedTrip(
        UUID.nameUUIDFromBytes(("trip-" + index).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
        route,
        String.format("T%03d", index),
        from,
        to,
        departure,
        100,
        false,
        false,
        "",
        List.of(options));
  }

  private static Map<UUID, Optional<String>> consistByTrip(VehicleDutyPlanner.Result result) {
    Map<UUID, Optional<String>> out = new HashMap<>();
    for (VehicleDuty duty : result.duties()) {
      for (UUID tripId : duty.tripIds()) {
        out.put(tripId, duty.consist());
      }
    }
    return out;
  }

  @Test
  void newDutiesFollowTheTripShare() {
    List<PlannedTrip> trips = new ArrayList<>();
    for (int i = 0; i < 8; i++) {
      trips.add(
          trip(
              i,
              AB,
              A,
              B,
              i * 600,
              new ConsistOption("m6", 3, 100),
              new ConsistOption("m8", 1, 120)));
    }
    VehicleDutyPlanner.Result result =
        VehicleDutyPlanner.plan(
            TIMETABLE, trips, legs("m6", "m8"), new Limits(1, 7200, TurnaroundTable.fixed(30)));

    Map<UUID, Optional<String>> consists = consistByTrip(result);
    List<String> sequence = new ArrayList<>();
    for (PlannedTrip trip : trips) {
      sequence.add(consists.get(trip.tripId()).orElseThrow());
    }
    assertEquals(List.of("m6", "m6", "m8", "m6", "m6", "m6", "m8", "m6"), sequence);
  }

  @Test
  void hostUsesItsOwnConsistRunTime() {
    // A→B 发车 0；B→A 发车 155：6 节 100 s + 折返 30 s 赶得上，8 节 130 s + 30 s 赶不上
    PlannedTrip out =
        trip(0, AB, A, B, 0, new ConsistOption("m6", 1, 100), new ConsistOption("m8", 1, 130));
    PlannedTrip back =
        trip(1, BA, B, A, 155, new ConsistOption("m6", 1, 100), new ConsistOption("m8", 1, 130));
    Limits limits = new Limits(4, 7200, TurnaroundTable.fixed(30), 600);

    VehicleDutyPlanner.Result result =
        VehicleDutyPlanner.plan(TIMETABLE, List.of(out, back), legs("m6", "m8"), limits);
    assertEquals(1, result.duties().size(), "首班出的是 6 节：它跑得快，赶得上回程");
    assertEquals(Optional.of("m6"), result.duties().get(0).consist());

    PlannedTrip slowOut = trip(2, AB, A, B, 0, new ConsistOption("m8", 1, 130));
    VehicleDutyPlanner.Result slow =
        VehicleDutyPlanner.plan(TIMETABLE, List.of(slowOut, back), legs("m6", "m8"), limits);
    assertEquals(2, slow.duties().size(), "8 节到 B 已是 130 + 30 = 160 > 155，回程只能另开一辆");
    assertEquals(160, slow.duties().get(0).returnSecondOfDay(), "回库在 8 节自己的到达 + 折返之后");
  }

  @Test
  void waitingTrainOfTheWrongConsistIsReportedAsMismatch() {
    PlannedTrip toB = trip(0, AB, A, B, 0, new ConsistOption("m6", 1, 100));
    PlannedTrip onlyEight = trip(1, BA, B, A, 200, new ConsistOption("m8", 1, 100));
    // 只有 6 节的出入库段：B 上等着的是 6 节，8 节也出不来
    VehicleDutyPlanner.Result result =
        VehicleDutyPlanner.plan(
            TIMETABLE,
            List.of(toB, onlyEight),
            legs("m6"),
            new Limits(4, 7200, TurnaroundTable.fixed(30), 600));

    assertEquals(1, result.unassigned().size());
    assertEquals(UnassignedReason.CONSIST_MISMATCH, result.unassigned().get(0).reason());
  }

  @Test
  void dutiesUseTheirConsistsOwnLegs() {
    Legs legs = legs("m6", "m8");
    PlannedTrip only = trip(0, AB, A, B, 600, new ConsistOption("m8", 1, 100));
    VehicleDutyPlanner.Result result =
        VehicleDutyPlanner.plan(
            TIMETABLE, List.of(only), legs, new Limits(4, 7200, TurnaroundTable.fixed(30)));

    VehicleDuty duty = result.duties().get(0);
    assertEquals(Optional.of("m8"), duty.consist());
    assertEquals(legs.createLegAt(A, Optional.of("m8")).map(Leg::routeId), duty.createRouteId());
    assertEquals(
        legs.returnLegAt(B, DEPOT, Optional.of("m8")).map(Leg::routeId), duty.returnRouteId());
    assertTrue(legs.createLegAt(A, Optional.empty()).isEmpty(), "区分车型时没有不分车型的出库段，不会被误用");
  }

  @Test
  void trainBlockedByLimitsIsNotAConsistMismatch() {
    // B 上的 8 节车接这一班会超过交路时长上限：它本来就接不了，开不出 6 节车时报缺出库线路，而不是车型不对
    UUID createA8 = UUID.randomUUID();
    UUID returnA8 = UUID.randomUUID();
    UUID returnB8 = UUID.randomUUID();
    UUID returnA6 = UUID.randomUUID();
    Map<UUID, String> station = new HashMap<>();
    station.put(createA8, A);
    station.put(returnA8, A);
    station.put(returnB8, B);
    station.put(returnA6, A);
    Legs legs =
        Legs.of(
            List.of(new Leg(createA8, "CRT-m8", DEPOT, 60, false, Optional.of("m8"))),
            List.of(
                new Leg(returnA8, "RET-m8", DEPOT, 60, false, Optional.of("m8")),
                new Leg(returnB8, "RET-m8", DEPOT, 60, false, Optional.of("m8")),
                new Leg(returnA6, "RET-m6", DEPOT, 60, false, Optional.of("m6"))),
            station);
    PlannedTrip out = trip(0, AB, A, B, 0, new ConsistOption("m8", 1, 100));
    PlannedTrip back = trip(1, BA, B, A, 200, new ConsistOption("m6", 1, 300));

    VehicleDutyPlanner.Result result =
        VehicleDutyPlanner.plan(
            TIMETABLE,
            List.of(out, back),
            legs,
            new Limits(4, 450, TurnaroundTable.fixed(30), 600));

    assertEquals(1, result.unassigned().size());
    assertEquals(UnassignedReason.NO_CREATE_ACCESS, result.unassigned().get(0).reason());
  }

  @Test
  void consistAgnosticLookupsOnlySeeBaseLegs() {
    UUID base = UUID.randomUUID();
    UUID fast = UUID.randomUUID();
    UUID baseCreate = UUID.randomUUID();
    UUID fastCreate = UUID.randomUUID();
    Map<UUID, String> station = new HashMap<>();
    station.put(base, B);
    station.put(fast, B);
    station.put(baseCreate, A);
    station.put(fastCreate, A);
    Legs legs =
        Legs.of(
            List.of(
                new Leg(baseCreate, "CRT", DEPOT, 90, false),
                new Leg(fastCreate, "CRT", DEPOT, 60, false, Optional.of("m6"))),
            List.of(
                new Leg(base, "RET", DEPOT, 90, false),
                new Leg(fast, "RET", DEPOT, 60, false, Optional.of("m6"))),
            station);

    assertEquals(
        Optional.of(base), legs.returnLegAt(B).map(Leg::routeId), "不分车型的查询取基础段（最慢），不取走得短的快车型变体");
    assertEquals(Optional.of(base), legs.returnLegAt(B, DEPOT).map(Leg::routeId));
    assertEquals(Optional.of(baseCreate), legs.createLegAt(A).map(Leg::routeId));
    assertEquals(Optional.of(fast), legs.returnLegAt(B, null, Optional.of("m6")).map(Leg::routeId));
  }
}
