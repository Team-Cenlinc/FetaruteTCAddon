package org.fetarute.fetaruteTCAddon.dispatcher.runtime.supervisor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.AuthorizationPurpose;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.ClaimRole;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.CorridorDirection;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyRequest;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyResource;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.ResourceIntent;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspectPolicy;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SimpleOccupancyManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** {@link SmartWaitForPlanner} 的 Phase 1.8 最小前向解锁规划测试。 */
@DisplayName("SmartWaitForPlanner minimal forward planner")
class SmartWaitForPlannerTest {

  private final SmartWaitForPlanner planner = new SmartWaitForPlanner();

  @Test
  void mutualCycleSelectsSameDirectionForwardCandidate() {
    SmartWaitForPlanner.PlanResult result =
        planner.plan(
            input(
                enforceSettings(),
                List.of(
                    edge("WS-A", "WS-B", "CONFLICT:single:WS", CorridorDirection.A_TO_B),
                    edge("WS-B", "WS-A", "CONFLICT:single:WS", CorridorDirection.A_TO_B)),
                Map.of(
                    "WS-A", state("WS-A", 20),
                    "WS-B", state("WS-B", 40))));

    assertTrue(result.selectedPlan().isPresent());
    assertTrue(result.selectedPlan().get().simulation().cycleBroken());
    assertTrue(
        result.traceLines().stream()
            .anyMatch(line -> line.contains("SMART_DISPATCH_CYCLE_DETECTED type=MUTUAL")));
    assertTrue(
        result.traceLines().stream()
            .anyMatch(line -> line.contains("SMART_DISPATCH_PLAN_SELECTED")));
  }

  @Test
  void bottleneckTrainBlockingMultipleOthersScoresHigher() {
    SmartWaitForPlanner.PlanResult result =
        planner.plan(
            input(
                enforceSettings(),
                List.of(
                    edge(
                        "follower-1",
                        "leader-small",
                        "CONFLICT:switcher:one",
                        CorridorDirection.A_TO_B),
                    edge(
                        "follower-2",
                        "leader-big",
                        "CONFLICT:switcher:two",
                        CorridorDirection.A_TO_B),
                    edge(
                        "follower-3",
                        "leader-big",
                        "CONFLICT:switcher:three",
                        CorridorDirection.A_TO_B),
                    edge(
                        "follower-4",
                        "leader-big",
                        "CONFLICT:switcher:four",
                        CorridorDirection.A_TO_B)),
                Map.of(
                    "leader-small", state("leader-small", 10),
                    "leader-big", state("leader-big", 10),
                    "follower-1", state("follower-1", 1),
                    "follower-2", state("follower-2", 1),
                    "follower-3", state("follower-3", 1),
                    "follower-4", state("follower-4", 1))));

    assertTrue(result.selectedPlan().isPresent());
    assertEquals("leader-big", result.selectedPlan().get().train());
    assertTrue(
        result.traceLines().stream()
            .anyMatch(line -> line.contains("SMART_DISPATCH_BOTTLENECK_DETECTED")));
  }

  @Test
  void sameLineCascadeIsDetected() {
    SmartWaitForPlanner.PlanResult result =
        planner.plan(
            input(
                enforceSettings(),
                List.of(
                    edge("train-A", "train-B", "CONFLICT:single:line", CorridorDirection.A_TO_B),
                    edge("train-B", "train-C", "CONFLICT:single:line", CorridorDirection.A_TO_B)),
                Map.of(
                    "train-A", state("train-A", "COMP:OP:LINE:1F", 5),
                    "train-B", state("train-B", "COMP:OP:LINE:1F", 10),
                    "train-C", state("train-C", "COMP:OP:LINE:1F", 20))));

    assertTrue(
        result.traceLines().stream()
            .anyMatch(line -> line.contains("SMART_DISPATCH_SAME_LINE_CASCADE_DETECTED")));
  }

  @Test
  void reverseCandidateIsRejected() {
    SmartWaitForPlanner.TrainState reverse = state("WS-B", 10, true, false, false, false, false);
    SmartWaitForPlanner.PlanResult result =
        planner.plan(
            input(
                enforceSettings(),
                List.of(
                    edge("WS-A", "WS-B", "CONFLICT:single:WS", CorridorDirection.A_TO_B),
                    edge("WS-B", "WS-A", "CONFLICT:single:WS", CorridorDirection.A_TO_B)),
                Map.of("WS-A", state("WS-A", 20), "WS-B", reverse)));

    assertTrue(
        result.traceLines().stream()
            .anyMatch(line -> line.contains("SMART_REVERSE_UNLOCK_REJECTED")));
    assertFalse(result.selectedPlan().filter(plan -> plan.train().equals("WS-B")).isPresent());
  }

  @Test
  void unknownDirectionNeedsAuditNotDestroyReview() {
    SmartWaitForPlanner.PlanResult result =
        planner.plan(
            input(
                enforceSettings(),
                List.of(
                    edge("WS-A", "WS-B", "CONFLICT:single:WS", CorridorDirection.UNKNOWN),
                    edge("WS-B", "WS-A", "CONFLICT:single:WS", CorridorDirection.UNKNOWN)),
                Map.of(
                    "WS-A", stateWithoutDirectionEvidence("WS-A", 20),
                    "WS-B", stateWithoutDirectionEvidence("WS-B", 40))));

    assertTrue(result.selectedPlan().isEmpty());
    assertTrue(result.directionAuditNeeded());
    assertTrue(
        result.traceLines().stream()
            .anyMatch(
                line ->
                    line.contains("SMART_NO_SAME_DIRECTION_UNLOCK_PLAN")
                        && line.contains("recommendation=NEED_DIRECTION_AUDIT")));
    assertFalse(
        result.traceLines().stream()
            .anyMatch(line -> line.contains("recommendation=DESTROY_REVIEW")));
  }

  @Test
  void graphOnlySimulationLogsConfidence() {
    SmartWaitForPlanner.PlanResult result =
        planner.plan(
            input(
                enforceSettings(),
                List.of(
                    edge("WS-A", "WS-B", "CONFLICT:single:WS", CorridorDirection.A_TO_B),
                    edge("WS-B", "WS-A", "CONFLICT:single:WS", CorridorDirection.A_TO_B)),
                Map.of("WS-A", state("WS-A", 20), "WS-B", state("WS-B", 40))));

    assertTrue(
        result.traceLines().stream()
            .anyMatch(
                line ->
                    line.contains("SMART_DISPATCH_UNLOCK_SIMULATION")
                        && line.contains("simulationModel=GRAPH_ONLY")
                        && line.contains("confidence=")));
  }

  @Test
  void identicalPlanHasStableThrottleKey() {
    SmartWaitForPlanner.PlannerInput input =
        input(
            enforceSettings(),
            List.of(
                edge("WS-A", "WS-B", "CONFLICT:single:WS", CorridorDirection.A_TO_B),
                edge("WS-B", "WS-A", "CONFLICT:single:WS", CorridorDirection.A_TO_B)),
            Map.of("WS-A", state("WS-A", 20), "WS-B", state("WS-B", 40)));

    assertEquals(planner.plan(input).throttleKey(), planner.plan(input).throttleKey());
  }

  @Test
  void staleLiveBlockerEdgeIsRejectedAndNotCounted() {
    SmartWaitForPlanner.PlanResult result =
        planner.plan(
            input(
                enforceSettings(),
                List.of(
                    edge("WS-A", "WS-B", "CONFLICT:single:WS", CorridorDirection.A_TO_B, 60_000)),
                Map.of("WS-A", state("WS-A", 20), "WS-B", state("WS-B", 40))));

    assertTrue(
        result.traceLines().stream()
            .anyMatch(
                line ->
                    line.contains("SMART_DISPATCH_INPUT_EDGE_REJECTED")
                        && line.contains("reason=STALE_EDGE")
                        && line.contains("ttlMs=10000")));
    assertTrue(
        result.traceLines().stream()
            .anyMatch(
                line ->
                    line.contains("SMART_WAIT_FOR_GRAPH")
                        && line.contains("edges=0")
                        && line.contains("staleEdges=1")));
  }

  @Test
  void plannerInfersDirectionFromRouteContextWhenSnapshotDirectionUnknown() {
    SmartWaitForPlanner.PlanResult result =
        planner.plan(
            input(
                enforceSettings(),
                List.of(
                    edge("WS-A", "WS-B", "CONFLICT:single:WS", CorridorDirection.UNKNOWN),
                    edge("WS-B", "WS-A", "CONFLICT:single:WS", CorridorDirection.UNKNOWN)),
                Map.of("WS-A", state("WS-A", 20), "WS-B", state("WS-B", 40))));

    assertTrue(result.selectedPlan().isPresent());
    assertTrue(
        result.traceLines().stream()
            .anyMatch(
                line ->
                    line.contains("SMART_DISPATCH_DIRECTION_INFERRED")
                        && line.contains("source=RUNTIME_ROUTE_CONTEXT")));
  }

  @Test
  void bottleneckLeaderProducesBoundedSameDirectionCandidate() {
    SmartWaitForPlanner.PlanResult result =
        planner.plan(
            input(
                enforceSettings(),
                List.of(
                    edge(
                        "follower-1", "leader", "CONFLICT:switcher:one", CorridorDirection.UNKNOWN),
                    edge(
                        "follower-2",
                        "leader",
                        "CONFLICT:switcher:two",
                        CorridorDirection.UNKNOWN)),
                Map.of(
                    "leader", state("leader", 30),
                    "follower-1", state("follower-1", 1),
                    "follower-2", state("follower-2", 1))));

    assertTrue(result.selectedPlan().isPresent());
    assertEquals("leader", result.selectedPlan().get().train());
    assertTrue(
        result.traceLines().stream()
            .anyMatch(
                line ->
                    line.contains("SMART_DISPATCH_UNLOCK_CANDIDATE train=leader")
                        && line.contains("cycleId=bottleneck:leader")));
  }

  @Test
  void resourceLimitUsesReservationResourceCountNotReleaseResourceCount() {
    SmartWaitForPlanner.PlanResult result =
        planner.plan(
            input(
                new SmartWaitForPlanner.PlannerSettings(
                    true,
                    SmartDispatcherPlannerMode.ENFORCE_MINIMAL_FORWARD,
                    1,
                    60,
                    10_000,
                    true,
                    false,
                    false,
                    true),
                List.of(
                    edge("follower-1", "leader", "CONFLICT:switcher:one", CorridorDirection.A_TO_B),
                    edge(
                        "follower-2", "leader", "CONFLICT:switcher:two", CorridorDirection.A_TO_B)),
                Map.of(
                    "leader", state("leader", 30),
                    "follower-1", state("follower-1", 1),
                    "follower-2", state("follower-2", 1))));

    assertTrue(result.selectedPlan().isPresent());
    assertEquals(1, result.selectedPlan().get().reservationResourceCount());
    assertEquals(2, result.selectedPlan().get().releaseResourceCount());
    assertFalse(
        result.traceLines().stream()
            .anyMatch(line -> line.contains("RESERVATION_RESOURCE_LIMIT_EXCEEDED")));
  }

  @Test
  void fullRoutePreclaimLogShowsFullRouteAndReservationCounts() {
    SmartWaitForPlanner.TrainState fullRoute =
        state("leader", 30, false, false, true, false, false);
    SmartWaitForPlanner.PlanResult result =
        planner.plan(
            input(
                enforceSettings(),
                List.of(
                    edge("follower-1", "leader", "CONFLICT:switcher:one", CorridorDirection.A_TO_B),
                    edge(
                        "follower-2", "leader", "CONFLICT:switcher:two", CorridorDirection.A_TO_B)),
                Map.of(
                    "leader", fullRoute,
                    "follower-1", state("follower-1", 1),
                    "follower-2", state("follower-2", 1))));

    assertTrue(
        result.traceLines().stream()
            .anyMatch(
                line ->
                    line.contains("SMART_FULL_ROUTE_PRECLAIM_REJECTED")
                        && line.contains("fullRouteResourceCount=2")
                        && line.contains("reservationResourceCount=2")));
  }

  @Test
  void noCandidateReasonIsLoggedForBottleneckAndChain() {
    SmartWaitForPlanner.PlanResult result =
        planner.plan(
            input(
                enforceSettings(),
                List.of(
                    edge(
                        "follower-1", "leader", "CONFLICT:switcher:one", CorridorDirection.UNKNOWN),
                    edge(
                        "follower-2",
                        "leader",
                        "CONFLICT:switcher:two",
                        CorridorDirection.UNKNOWN)),
                Map.of(
                    "leader", stateWithoutDirectionEvidence("leader", 30),
                    "follower-1", state("follower-1", 1),
                    "follower-2", state("follower-2", 1))));

    assertTrue(
        result.traceLines().stream()
            .anyMatch(line -> line.contains("SMART_DISPATCH_NO_CANDIDATE_FOR_BOTTLENECK")));
    assertTrue(
        result.traceLines().stream()
            .anyMatch(line -> line.contains("SMART_DISPATCH_NO_CANDIDATE_FOR_CHAIN")));
  }

  @Test
  void detectedBottleneckAndChainEachEmitTerminalCandidateDecision() {
    SmartWaitForPlanner.PlanResult result =
        planner.plan(
            input(
                enforceSettings(),
                List.of(
                    edge("follower-1", "leader", "CONFLICT:switcher:one", CorridorDirection.A_TO_B),
                    edge(
                        "follower-2", "leader", "CONFLICT:switcher:two", CorridorDirection.A_TO_B)),
                Map.of(
                    "leader", state("leader", 30),
                    "follower-1", state("follower-1", 1),
                    "follower-2", state("follower-2", 1))));

    List<String> decisions =
        result.traceLines().stream()
            .filter(line -> line.contains("SMART_DISPATCH_CANDIDATE_DECISION"))
            .toList();

    assertEquals(2, decisions.size());
    assertTrue(
        decisions.stream()
            .anyMatch(
                line ->
                    line.contains("pattern=BOTTLENECK")
                        && line.contains("leaderTrain=leader")
                        && line.contains("candidateAttempted=true")
                        && line.contains("candidateTrain=leader")
                        && line.contains("rejected=false")
                        && line.contains("reservationResourceCount=2")));
    assertTrue(
        decisions.stream()
            .anyMatch(
                line ->
                    line.contains("pattern=STALLED_LEADER_CHAIN")
                        && line.contains("leaderTrain=leader")
                        && line.contains("sameDirectionSatisfied=true")));
  }

  @Test
  void directionEvidencePrecedesInsufficientDirectionPlanRejection() {
    SmartWaitForPlanner.PlanResult result =
        planner.plan(
            input(
                enforceSettings(),
                List.of(
                    edge("WS-A", "WS-B", "EDGE:WS:1", CorridorDirection.UNKNOWN),
                    edge("WS-B", "WS-A", "NODE:WS:2", CorridorDirection.UNKNOWN)),
                Map.of(
                    "WS-A", stateWithoutDirectionEvidence("WS-A", 20),
                    "WS-B", stateWithoutDirectionEvidence("WS-B", 40))));

    int evidenceIndex = indexOf(result.traceLines(), "SMART_DISPATCH_DIRECTION_EVIDENCE");
    int rejectionIndex =
        indexOf(
            result.traceLines(),
            "SMART_DISPATCH_PLAN_REJECTED train=WS-A",
            "reason=INSUFFICIENT_DIRECTION_EVIDENCE");

    assertTrue(evidenceIndex >= 0);
    assertTrue(rejectionIndex >= 0);
    assertTrue(evidenceIndex < rejectionIndex);
    assertTrue(
        result.traceLines().get(evidenceIndex).contains("resourceKind=EDGE")
            || result.traceLines().get(evidenceIndex).contains("resourceKind=NODE"));
    assertTrue(result.traceLines().get(evidenceIndex).contains("currentNode=WS-"));
    assertTrue(result.traceLines().get(evidenceIndex).contains("nextNode=WS-"));
  }

  @Test
  void unlockReservationRoleReleaseDoesNotClearMovementClaim() {
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    OccupancyResource physical = OccupancyResource.forConflict("single:physical");
    OccupancyResource reservation = OccupancyResource.forConflict("single:reservation");

    manager.acquire(request("train", physical, ResourceIntent.MOVEMENT_REQUIRED));
    manager.acquire(request("train", reservation, ResourceIntent.UNLOCK_RESERVATION));

    int released =
        manager.releaseResourcesByTrainAndRole(
            "train", List.of(physical, reservation), ClaimRole.UNLOCK_RESERVATION);

    assertEquals(1, released);
    assertTrue(manager.getClaim(physical).isPresent());
    assertTrue(manager.getClaim(reservation).isEmpty());
  }

  private static SmartWaitForPlanner.PlannerInput input(
      SmartWaitForPlanner.PlannerSettings settings,
      List<SmartWaitForPlanner.InputEdge> edges,
      Map<String, SmartWaitForPlanner.TrainState> states) {
    return new SmartWaitForPlanner.PlannerInput(
        Instant.parse("2026-01-01T00:00:00Z"), settings, edges, states, Set.of());
  }

  private static SmartWaitForPlanner.PlannerSettings enforceSettings() {
    return new SmartWaitForPlanner.PlannerSettings(
        true,
        SmartDispatcherPlannerMode.ENFORCE_MINIMAL_FORWARD,
        4,
        60,
        10_000,
        true,
        false,
        false,
        true);
  }

  private static SmartWaitForPlanner.InputEdge edge(
      String blocked, String blocker, String resource, CorridorDirection direction) {
    return edge(blocked, blocker, resource, direction, 10);
  }

  private static SmartWaitForPlanner.InputEdge edge(
      String blocked, String blocker, String resource, CorridorDirection direction, long ageMs) {
    return new SmartWaitForPlanner.InputEdge(
        blocked,
        blocker,
        resource,
        resourceKind(resource),
        "HARD_OCCUPANCY",
        "MOVEMENT_REQUIRED",
        "MOVEMENT_REQUIRED",
        "test",
        direction,
        ageMs,
        true);
  }

  private static String resourceKind(String resource) {
    if (resource == null || resource.isBlank() || !resource.contains(":")) {
      return "UNKNOWN";
    }
    return resource.substring(0, resource.indexOf(':')).toUpperCase(java.util.Locale.ROOT);
  }

  private static SmartWaitForPlanner.TrainState state(String train, long stuckSeconds) {
    return state(train, "COMP:OP:LINE:route", stuckSeconds);
  }

  private static SmartWaitForPlanner.TrainState state(
      String train, String routeId, long stuckSeconds) {
    return new SmartWaitForPlanner.TrainState(
        train,
        routeId,
        1,
        train + ":current",
        train + ":next",
        train + ":current",
        CorridorDirection.A_TO_B,
        "RUNTIME_ROUTE_CONTEXT",
        "-",
        stuckSeconds,
        true,
        false,
        false,
        false,
        false,
        false,
        "NONE");
  }

  private static SmartWaitForPlanner.TrainState stateWithoutDirectionEvidence(
      String train, long stuckSeconds) {
    return new SmartWaitForPlanner.TrainState(
        train,
        "COMP:OP:LINE:route",
        1,
        train + ":current",
        train + ":next",
        train + ":current",
        CorridorDirection.UNKNOWN,
        "UNKNOWN",
        "INSUFFICIENT_DIRECTION_EVIDENCE",
        stuckSeconds,
        true,
        false,
        false,
        false,
        false,
        false,
        "NONE");
  }

  private static SmartWaitForPlanner.TrainState state(
      String train,
      long stuckSeconds,
      boolean reverse,
      boolean turnbackBeforeBoundary,
      boolean fullRoute,
      boolean oppositeConflict,
      boolean blocksUnrelated) {
    return new SmartWaitForPlanner.TrainState(
        train,
        "COMP:OP:LINE:route",
        1,
        train + ":current",
        train + ":next",
        train + ":current",
        CorridorDirection.A_TO_B,
        "RUNTIME_ROUTE_CONTEXT",
        "-",
        stuckSeconds,
        true,
        reverse,
        turnbackBeforeBoundary,
        fullRoute,
        oppositeConflict,
        blocksUnrelated,
        "NONE");
  }

  private static OccupancyRequest request(
      String train, OccupancyResource resource, ResourceIntent intent) {
    return new OccupancyRequest(
        train,
        Optional.empty(),
        Instant.parse("2026-01-01T00:00:00Z"),
        List.of(resource),
        Map.of(resource.key(), CorridorDirection.A_TO_B),
        Map.of(),
        0,
        AuthorizationPurpose.RUNTIME_MOVE,
        Map.of(),
        Map.of(resource, intent));
  }

  private static int indexOf(List<String> lines, String... parts) {
    for (int index = 0; index < lines.size(); index++) {
      String line = lines.get(index);
      boolean matches = true;
      for (String part : parts) {
        matches &= line.contains(part);
      }
      if (matches) {
        return index;
      }
    }
    return -1;
  }
}
