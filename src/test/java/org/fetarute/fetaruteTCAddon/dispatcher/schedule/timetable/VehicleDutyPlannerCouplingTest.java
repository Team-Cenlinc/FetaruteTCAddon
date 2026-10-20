package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteCouplingDirectives;
import org.junit.jupiter.api.Test;

/**
 * 认加解编的派车：主干在途中解编生出一条子交路去跑支线，回程支线到联挂站并回主干。
 *
 * <p>线网：主干 P→S→Z（在 S 解编），回程 Z→S→P（在 S 联挂）；支线 S→T、回程 T→S。车库只连 P（出入库）。
 */
class VehicleDutyPlannerCouplingTest {

  private static final UUID TIMETABLE = UUID.randomUUID();
  private static final String P = "OP:S:P:1";
  private static final String S = "OP:S:S:1";
  private static final String Z = "OP:S:Z:1";
  private static final String T = "OP:S:T:1";
  private static final String DEPOT = "OP:D:DEP:1";
  private static final String FULL = "A+B";
  private static final String KEPT = "A";
  private static final String BRANCH = "B";

  private static UUID id(String name) {
    return UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
  }

  private static VehicleDutyPlanner.PlannedTrip trip(
      String code, String from, String to, int departure, int duration, String consist) {
    return new VehicleDutyPlanner.PlannedTrip(
        id(code),
        id("route-" + code.replaceAll("@.*", "")),
        code,
        from,
        to,
        departure,
        duration,
        false,
        false,
        "",
        consist == null
            ? List.of()
            : List.of(new VehicleDutyPlanner.ConsistOption(consist, 1, duration)));
  }

  /** P 有出入库，Z 有回库（主干跑完单独收尾时用）；S、T 都没有。 */
  private static VehicleDutyPlanner.Legs legs(boolean returnAtS) {
    VehicleDutyPlanner.Leg create = new VehicleDutyPlanner.Leg(id("CRT"), "CRT", DEPOT, 120);
    VehicleDutyPlanner.Leg backFromP = new VehicleDutyPlanner.Leg(id("RET-P"), "RET-P", DEPOT, 120);
    VehicleDutyPlanner.Leg backFromZ = new VehicleDutyPlanner.Leg(id("RET-Z"), "RET-Z", DEPOT, 400);
    VehicleDutyPlanner.Leg backFromS = new VehicleDutyPlanner.Leg(id("RET-S"), "RET-S", DEPOT, 200);
    Map<String, VehicleDutyPlanner.Leg> returns =
        returnAtS
            ? Map.of(P, backFromP, Z, backFromZ, S, backFromS)
            : Map.of(P, backFromP, Z, backFromZ);
    return VehicleDutyPlanner.Legs.simple(Map.of(P, create), returns);
  }

  private static VehicleDutyPlanner.Limits limits() {
    return new VehicleDutyPlanner.Limits(6, 7200, 60, 600);
  }

  private static VehicleDutyPlanner.Couplings couplings(
      RouteCouplingDirectives.Action action, boolean withBranch, boolean withJoin) {
    Map<UUID, VehicleDutyPlanner.Split> splits =
        Map.of(
            id("R@0"),
            new VehicleDutyPlanner.Split(S, 330, Optional.of(KEPT), Optional.of(BRANCH), action));
    Map<UUID, UUID> pinned = withBranch ? Map.of(id("B@360"), id("R@0")) : Map.of();
    Map<UUID, VehicleDutyPlanner.Join> joins =
        withJoin
            ? Map.of(id("RR@1000"), new VehicleDutyPlanner.Join(S, Optional.of(FULL)))
            : Map.of();
    Map<UUID, UUID> feeders = withJoin ? Map.of(id("BB@900"), id("RR@1000")) : Map.of();
    return new VehicleDutyPlanner.Couplings(
        splits, pinned, joins, feeders, Set.of(FULL, KEPT, BRANCH), Set.of(KEPT, BRANCH));
  }

  /** 主干 R（P→Z，在 S 解编）、支线 B（S→T）、回程支线 BB（T→S）、回程主干 RR（Z→P，在 S 联挂）。 */
  private static List<VehicleDutyPlanner.PlannedTrip> cycle() {
    return List.of(
        trip("R@0", P, Z, 0, 900, FULL),
        trip("B@360", S, T, 360, 300, BRANCH),
        trip("BB@900", T, S, 900, 300, BRANCH),
        trip("RR@1000", Z, P, 1000, 900, KEPT));
  }

  private static VehicleDutyPlanner.Result plan(
      List<VehicleDutyPlanner.PlannedTrip> trips,
      VehicleDutyPlanner.Legs legs,
      VehicleDutyPlanner.Couplings couplings) {
    return VehicleDutyPlanner.plan(
        TIMETABLE,
        trips,
        legs,
        limits(),
        Map.of(),
        Map.of(),
        (trip, candidates, ignored) -> candidates.get(0),
        couplings);
  }

  private static VehicleDuty dutyWith(VehicleDutyPlanner.Result result, String tripCode) {
    return result.duties().stream()
        .filter(duty -> duty.tripIds().contains(id(tripCode)))
        .findFirst()
        .orElseThrow(() -> new AssertionError("没有交路跑 " + tripCode));
  }

  @Test
  void theBranchIsRunByTheCarsDetachedFromTheTrunkAndRejoinsIt() {
    VehicleDutyPlanner.Result result =
        plan(cycle(), legs(false), couplings(RouteCouplingDirectives.Action.GOTO, true, true));

    assertTrue(result.unassigned().isEmpty(), () -> "不该有排不进的班次: " + result.unassigned());
    VehicleDuty trunk = dutyWith(result, "R@0");
    VehicleDuty branch = dutyWith(result, "B@360");
    assertEquals(List.of(id("R@0"), id("RR@1000")), trunk.tripIds(), "主干车跑完去程接回程");
    assertEquals(Optional.of(FULL), trunk.consist(), "主干出车是完整编组");
    assertEquals(List.of(id("B@360"), id("BB@900")), branch.tripIds());
    assertEquals(Optional.of(id("R@0")), branch.coupling().splitFromTripId());
    assertEquals(Optional.of(id("RR@1000")), branch.coupling().joinsTripId());
    assertEquals(S, branch.startDepotNodeId(), "子交路从解编站开始");
    assertEquals(S, branch.endDepotNodeId(), "以联挂结束：终点是联挂站");
    assertTrue(branch.createRouteId().isEmpty() && branch.returnRouteId().isEmpty());
    assertEquals(VehicleDuty.CloseReason.COUPLED, branch.closeReason());
    assertEquals(1, result.spawnedVehicles(), "只有主干那一辆从车库出车");
  }

  @Test
  void aDetachedPartThatReturnsGoesStraightToTheDepot() {
    VehicleDutyPlanner.Result result =
        plan(
            List.of(trip("R@0", P, Z, 0, 900, FULL)),
            legs(true),
            couplings(RouteCouplingDirectives.Action.RETURN, false, false));

    VehicleDuty child =
        result.duties().stream()
            .filter(duty -> duty.coupling().startsBySplit())
            .findFirst()
            .orElseThrow();
    assertTrue(child.tripIds().isEmpty());
    assertEquals(Optional.of(id("RET-S")), child.returnRouteId());
    assertEquals(330, child.plannedStartSecondOfDay());
    assertEquals(330 + 200, child.plannedEndSecondOfDay());
  }

  @Test
  void aDetachedPartThatCannotGetHomeIsReported() {
    VehicleDutyPlanner.Result result =
        plan(
            List.of(trip("R@0", P, Z, 0, 900, FULL)),
            legs(false),
            couplings(RouteCouplingDirectives.Action.IDLE, false, false));

    assertTrue(
        result.unassigned().stream()
            .anyMatch(
                trip ->
                    trip.tripId().equals(id("R@0"))
                        && trip.reason() == VehicleDutyPlanner.UnassignedReason.DECOUPLE_STRANDED));
    assertTrue(result.duties().stream().noneMatch(duty -> duty.coupling().startsBySplit()));
  }

  /** 支线终点回不了库、后面也没有班次接（末班前后）：支线班次退掉，解下的车按解编站与解编时刻回库，不报回不了库。 */
  @Test
  void aBranchTrimmedAwayLeavesTheDetachedPartToReturnFromTheSplitStation() {
    VehicleDutyPlanner.Result result =
        plan(
            List.of(trip("R@0", P, Z, 0, 900, FULL), trip("B@360", S, T, 360, 300, BRANCH)),
            legs(true),
            couplings(RouteCouplingDirectives.Action.GOTO, true, false));

    assertTrue(
        result.unassigned().stream()
            .noneMatch(
                trip -> trip.reason() == VehicleDutyPlanner.UnassignedReason.DECOUPLE_STRANDED),
        () -> "解编站有回库线路：" + result.unassigned());
    assertTrue(
        result.unassigned().stream()
            .anyMatch(
                trip ->
                    trip.tripId().equals(id("B@360"))
                        && trip.reason() == VehicleDutyPlanner.UnassignedReason.NO_RETURN_ACCESS),
        "支线终点 T 回不了库：支线班次退掉");
    VehicleDuty child =
        result.duties().stream()
            .filter(duty -> duty.coupling().startsBySplit())
            .findFirst()
            .orElseThrow();
    assertTrue(child.tripIds().isEmpty());
    assertEquals(Optional.of(id("RET-S")), child.returnRouteId());
    assertEquals(330, child.plannedStartSecondOfDay());
    assertEquals(330 + 200, child.plannedEndSecondOfDay(), "从解编时刻起回库，不是跑完支线之后");
  }

  @Test
  void aJoinWhoseTrunkHasNoVehicleIsDroppedAndTheBranchEndsNormally() {
    // 回程主干从 Z 发车时没车（去程在 Z 收尾回库的时刻晚于回程发车），联挂对不上
    List<VehicleDutyPlanner.PlannedTrip> trips =
        List.of(
            trip("R@0", P, Z, 0, 900, FULL),
            trip("B@360", S, T, 360, 300, BRANCH),
            trip("BB@900", T, S, 900, 300, BRANCH),
            trip("RR@1000", Z, P, 800, 900, KEPT));
    VehicleDutyPlanner.Couplings couplings =
        new VehicleDutyPlanner.Couplings(
            couplings(RouteCouplingDirectives.Action.GOTO, true, false).splits(),
            Map.of(id("B@360"), id("R@0")),
            Map.of(id("RR@1000"), new VehicleDutyPlanner.Join(S, Optional.of(FULL))),
            Map.of(id("BB@900"), id("RR@1000")),
            Set.of(FULL, KEPT, BRANCH),
            Set.of(KEPT, BRANCH));

    VehicleDutyPlanner.Result result = plan(trips, legs(true), couplings);

    assertTrue(
        result.duties().stream().noneMatch(duty -> duty.coupling().endsByJoin()), "主干没派上车，联挂去掉");
    VehicleDuty branch = dutyWith(result, "B@360");
    assertEquals(List.of(id("B@360"), id("BB@900")), branch.tripIds());
    assertEquals(Optional.of(id("RET-S")), branch.returnRouteId(), "回程支线到 S 后照常回库");
  }

  @Test
  void anOrdinaryTripDoesNotTakeAShortConsist() {
    // 主干在 S 解编后到 Z 只剩 A；从 Z 发的普通班次（不写车型）不接短编组，另开交路
    List<VehicleDutyPlanner.PlannedTrip> trips =
        List.of(trip("R@0", P, Z, 0, 900, FULL), trip("X@1000", Z, P, 1000, 900, null));
    VehicleDutyPlanner.Legs legs =
        VehicleDutyPlanner.Legs.simple(
            Map.of(
                P, new VehicleDutyPlanner.Leg(id("CRT"), "CRT", DEPOT, 120),
                Z, new VehicleDutyPlanner.Leg(id("CRT-Z"), "CRT-Z", DEPOT, 120)),
            Map.of(
                P, new VehicleDutyPlanner.Leg(id("RET-P"), "RET-P", DEPOT, 120),
                Z, new VehicleDutyPlanner.Leg(id("RET-Z"), "RET-Z", DEPOT, 400),
                S, new VehicleDutyPlanner.Leg(id("RET-S"), "RET-S", DEPOT, 200)));

    VehicleDutyPlanner.Result result =
        plan(trips, legs, couplings(RouteCouplingDirectives.Action.RETURN, false, false));

    assertFalse(dutyWith(result, "R@0").tripIds().contains(id("X@1000")));
    assertTrue(dutyWith(result, "X@1000").createRouteId().isPresent(), "普通班次另从车库出车");
  }
}
