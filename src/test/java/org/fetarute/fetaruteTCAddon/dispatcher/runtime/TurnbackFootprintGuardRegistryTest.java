package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.OptionalDouble;
import java.util.Set;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyResource;
import org.junit.jupiter.api.Test;

class TurnbackFootprintGuardRegistryTest {

  @Test
  void backtrackAndJitterDoNotIncreaseForwardClearance() {
    TurnbackFootprintGuardRegistry registry = new TurnbackFootprintGuardRegistry();
    OccupancyResource oldApproach = OccupancyResource.forConflict("old-approach");
    registry.register(
        "Train-A",
        NodeId.of("TERM"),
        Set.of(oldApproach),
        forwardPath("TERM", 5.0, "THROAT-1", 5.0, "THROAT-2", 5.0, "EXIT"),
        OptionalDouble.of(8.0),
        1);

    assertTrue(registry.observeProgress("TRAIN-A", NodeId.of("THROAT-1")).isEmpty());
    assertTrue(registry.observeProgress("TRAIN-A", NodeId.of("TERM")).isEmpty());
    assertTrue(registry.observeProgress("TRAIN-A", NodeId.of("THROAT-1")).isEmpty());
    assertTrue(registry.observeProgress("TRAIN-A", NodeId.of("THROAT-2")).isEmpty());

    TurnbackFootprintGuardRegistry.Release release =
        registry.observeProgress("TRAIN-A", NodeId.of("EXIT")).orElseThrow();
    assertEquals(Set.of(oldApproach), release.resources());
    assertTrue(registry.protectedResources("TRAIN-A").isEmpty());
  }

  @Test
  void longTrainDoesNotReleaseInsideShortStationThroat() {
    TurnbackFootprintGuardRegistry registry = new TurnbackFootprintGuardRegistry();
    OccupancyResource crossing = OccupancyResource.forConflict("level-crossing");
    registry.register(
        "long-train",
        NodeId.of("TERM"),
        Set.of(crossing),
        forwardPath("TERM", 3.0, "PLATFORM-END", 3.0, "THROAT", 12.0, "CLEAR"),
        OptionalDouble.of(10.0),
        1);

    assertTrue(registry.observeProgress("long-train", NodeId.of("PLATFORM-END")).isEmpty());
    assertTrue(registry.observeProgress("long-train", NodeId.of("THROAT")).isEmpty());
    assertEquals(Set.of(crossing), registry.protectedResources("long-train"));

    assertEquals(
        Set.of(crossing),
        registry.observeProgress("long-train", NodeId.of("CLEAR")).orElseThrow().resources());
  }

  @Test
  void skippedObservedNodesFailRetainWithoutDirectedSegmentEvidence() {
    TurnbackFootprintGuardRegistry registry = new TurnbackFootprintGuardRegistry();
    OccupancyResource oldApproach = OccupancyResource.forNode(NodeId.of("TERM"));
    registry.register(
        "skip",
        NodeId.of("TERM"),
        Set.of(oldApproach),
        forwardPath("TERM", 4.0, "A", 5.0, "B", 6.0, "C"),
        OptionalDouble.of(5.0),
        1);

    assertTrue(registry.observeProgress("skip", NodeId.of("C")).isEmpty());
    assertTrue(registry.observeProgress("skip", NodeId.of("A")).isEmpty());
    assertEquals(Set.of(oldApproach), registry.protectedResources("skip"));
  }

  @Test
  void unavailableLengthAndLegacyBoundaryRegistrationFailRetain() {
    TurnbackFootprintGuardRegistry registry = new TurnbackFootprintGuardRegistry();
    OccupancyResource unknownLength = OccupancyResource.forConflict("unknown-length");
    OccupancyResource legacy = OccupancyResource.forConflict("legacy");
    registry.register(
        "unknown",
        NodeId.of("TERM"),
        Set.of(unknownLength),
        forwardPath("TERM", 20.0, "CLEAR"),
        OptionalDouble.empty(),
        1);
    registry.register("legacy", NodeId.of("TERM"), Set.of(legacy), 1);

    assertTrue(registry.observeProgress("unknown", NodeId.of("CLEAR")).isEmpty());
    assertTrue(registry.observeProgress("legacy", NodeId.of("CLEAR")).isEmpty());
    assertEquals(Set.of(unknownLength), registry.protectedResources("unknown"));
    assertEquals(Set.of(legacy), registry.protectedResources("legacy"));
  }

  @Test
  void laterValidPlanReleasesItsOwnFootprintWithoutWeakeningEarlierFailRetain() {
    TurnbackFootprintGuardRegistry registry = new TurnbackFootprintGuardRegistry();
    OccupancyResource unknown = OccupancyResource.forConflict("unknown-old-crossing");
    OccupancyResource later = OccupancyResource.forConflict("later-crossing");
    registry.register(
        "retry",
        NodeId.of("TERM"),
        Set.of(unknown),
        forwardPath("TERM", 20.0, "CLEAR"),
        OptionalDouble.empty(),
        1);
    registry.register(
        "retry",
        NodeId.of("TERM"),
        Set.of(later),
        forwardPath("TERM", 10.0, "MID", 10.0, "CLEAR"),
        OptionalDouble.of(2.0),
        1);

    assertTrue(registry.observeProgress("retry", NodeId.of("MID")).isEmpty());
    assertEquals(
        Set.of(later),
        registry.observeProgress("retry", NodeId.of("CLEAR")).orElseThrow().resources());
    assertEquals(Set.of(unknown), registry.protectedResources("retry"));
  }

  @Test
  void newerTurnbackSealsOlderTraversalEpoch() {
    TurnbackFootprintGuardRegistry registry = new TurnbackFootprintGuardRegistry();
    OccupancyResource first = OccupancyResource.forConflict("first-crossing");
    OccupancyResource second = OccupancyResource.forConflict("second-crossing");
    registry.register(
        "multi-turn",
        NodeId.of("TERM-A"),
        Set.of(first),
        forwardPath("TERM-A", 10.0, "MID-A", 10.0, "CLEAR-A"),
        OptionalDouble.of(2.0),
        1);
    registry.register(
        "multi-turn",
        NodeId.of("TERM-B"),
        Set.of(second),
        forwardPath("TERM-B", 10.0, "MID-B", 10.0, "CLEAR-B"),
        OptionalDouble.of(2.0),
        1);

    assertTrue(registry.observeProgress("multi-turn", NodeId.of("MID-B")).isEmpty());
    assertEquals(
        Set.of(second),
        registry.observeProgress("multi-turn", NodeId.of("CLEAR-B")).orElseThrow().resources());
    assertEquals(Set.of(first), registry.protectedResources("multi-turn"));
    assertTrue(registry.observeProgress("multi-turn", NodeId.of("CLEAR-A")).isEmpty());
    assertTrue(registry.contains("multi-turn"));
  }

  @Test
  void branchRejoinNodeCannotAdvanceSealedOlderEpoch() {
    TurnbackFootprintGuardRegistry registry = new TurnbackFootprintGuardRegistry();
    OccupancyResource oldThroat = OccupancyResource.forConflict("old-throat");
    OccupancyResource newThroat = OccupancyResource.forConflict("new-throat");
    registry.register(
        "branch-rejoin",
        NodeId.of("A"),
        Set.of(oldThroat),
        forwardPath("A", 10.0, "B", 10.0, "C", 10.0, "D"),
        25.0);
    assertTrue(registry.observeProgress("branch-rejoin", NodeId.of("B")).isEmpty());

    registry.register(
        "branch-rejoin",
        NodeId.of("B"),
        Set.of(newThroat),
        forwardPath("B", 10.0, "X", 10.0, "C", 10.0, "Y"),
        15.0);
    assertTrue(registry.observeProgress("branch-rejoin", NodeId.of("X")).isEmpty());
    assertEquals(
        Set.of(newThroat),
        registry.observeProgress("branch-rejoin", NodeId.of("C")).orElseThrow().resources());
    assertEquals(Set.of(oldThroat), registry.protectedResources("branch-rejoin"));
  }

  @Test
  void sharedResourceRemainsProtectedWhenOlderEpochIsSealed() {
    TurnbackFootprintGuardRegistry registry = new TurnbackFootprintGuardRegistry();
    OccupancyResource shared = OccupancyResource.forConflict("shared-throat");
    OccupancyResource first = OccupancyResource.forConflict("first-only");
    OccupancyResource second = OccupancyResource.forConflict("second-only");
    registry.register(
        "shared",
        NodeId.of("TERM-A"),
        Set.of(shared, first),
        forwardPath("TERM-A", 10.0, "MID-A", 10.0, "CLEAR-A"),
        OptionalDouble.of(2.0),
        1);
    registry.register(
        "shared",
        NodeId.of("TERM-B"),
        Set.of(shared, second),
        forwardPath("TERM-B", 10.0, "MID-B", 10.0, "CLEAR-B"),
        OptionalDouble.of(2.0),
        1);

    assertTrue(registry.observeProgress("shared", NodeId.of("MID-B")).isEmpty());
    assertEquals(
        Set.of(second),
        registry.observeProgress("shared", NodeId.of("CLEAR-B")).orElseThrow().resources());
    assertEquals(Set.of(shared, first), registry.protectedResources("shared"));

    assertTrue(registry.observeProgress("shared", NodeId.of("CLEAR-A")).isEmpty());
    assertEquals(Set.of(shared, first), registry.protectedResources("shared"));
  }

  @Test
  void routeLoopAfterRequiredClearanceDoesNotInvalidateEarlierEvidence() {
    TurnbackFootprintGuardRegistry registry = new TurnbackFootprintGuardRegistry();
    OccupancyResource oldApproach = OccupancyResource.forConflict("old-approach");
    registry.register(
        "loop-route",
        NodeId.of("TERM"),
        Set.of(oldApproach),
        forwardPath("TERM", 8.0, "A", 8.0, "B", 8.0, "A"),
        OptionalDouble.of(6.0),
        1);

    assertTrue(registry.observeProgress("loop-route", NodeId.of("A")).isEmpty());
    assertEquals(
        Set.of(oldApproach),
        registry.observeProgress("loop-route", NodeId.of("B")).orElseThrow().resources());
  }

  @Test
  void renamePreservesProgressAndMissingSourceIsNoOp() {
    TurnbackFootprintGuardRegistry registry = new TurnbackFootprintGuardRegistry();
    OccupancyResource oldApproach = OccupancyResource.forNode(NodeId.of("TERM"));
    registry.register(
        "old",
        NodeId.of("TERM"),
        Set.of(oldApproach),
        forwardPath("TERM", 5.0, "A", 5.0, "B", 5.0, "C"),
        OptionalDouble.of(8.0),
        1);
    assertTrue(registry.observeProgress("old", NodeId.of("A")).isEmpty());
    assertTrue(registry.observeProgress("old", NodeId.of("B")).isEmpty());

    assertTrue(registry.rename("old", "new"));
    assertTrue(registry.protectedResources("old").isEmpty());
    assertEquals(Set.of(oldApproach), registry.protectedResources("new"));
    assertEquals(
        Set.of(oldApproach),
        registry.observeProgress("new", NodeId.of("C")).orElseThrow().resources());
    assertTrue(registry.rename("missing", "another"));
  }

  @Test
  void renameCollisionRestoresSourceGuard() {
    TurnbackFootprintGuardRegistry registry = new TurnbackFootprintGuardRegistry();
    OccupancyResource oldResource = OccupancyResource.forConflict("old");
    OccupancyResource newResource = OccupancyResource.forConflict("new");
    registry.register(
        "old",
        NodeId.of("A"),
        Set.of(oldResource),
        forwardPath("A", 5.0, "B"),
        OptionalDouble.of(2.0),
        1);
    registry.register(
        "new",
        NodeId.of("X"),
        Set.of(newResource),
        forwardPath("X", 5.0, "Y"),
        OptionalDouble.of(2.0),
        1);

    assertFalse(registry.rename("old", "new"));
    assertEquals(Set.of(oldResource), registry.protectedResources("old"));
    assertEquals(Set.of(newResource), registry.protectedResources("new"));
  }

  // ---------------------------------------------------------------- 有序路线到达的后备释放
  //
  // 2026-09-29 生产服：41 次折返发车里有 2 次（都是从 NTA:1 站台出的 WS-2N）守卫从未完成——节点事件漏了
  // 一个就被封存，而封存的 epoch 没有任何后备，旧进站 footprint 一直留到这辆车销毁，五个站以外的车还在
  // 挡着 NTA 咽喉，后车进站被扣满 180 秒发车许可锁的安全超时。后备证据是"路线下标有序到达的路径点"：
  // 下标单调，沿登记前进路径累计距离达到（车长 + 车尾保护边距 + 余量）才算车尾一定离开了旧进路。

  @Test
  void sealedEpochIsReleasedByOrderedRouteProgressBeyondTheClearance() {
    TurnbackFootprintGuardRegistry registry = new TurnbackFootprintGuardRegistry();
    OccupancyResource oldApproach = OccupancyResource.forConflict("old-approach");
    registry.register(
        "far",
        NodeId.of("TERM"),
        Set.of(oldApproach),
        forwardPath("TERM", 10.0, "A", 10.0, "B", 10.0, "C", 30.0, "STATION"),
        OptionalDouble.of(6.0),
        1,
        routeEvidence(0, "TERM", "C", "STATION"));

    // 漏了 A、B 两个节点事件：第一个观察到的节点是 C，跳点，封存
    assertTrue(registry.observeProgress("far", NodeId.of("C")).isEmpty());
    assertTrue(registry.observeProgress("far", NodeId.of("STATION")).isEmpty(), "封存后连续证据不可能再成立");
    assertEquals(Set.of(oldApproach), registry.protectedResources("far"));

    // 路线下标 1 = C，累计 30 < 车长 6 + 边距 10 + 余量 32
    assertTrue(registry.observeRouteArrival("far", 1, NodeId.of("C")).isEmpty());
    // 路线下标 2 = STATION，累计 60 ≥ 48
    assertEquals(
        Set.of(oldApproach),
        registry.observeRouteArrival("far", 2, NodeId.of("STATION")).orElseThrow().resources());
    assertTrue(registry.protectedResources("far").isEmpty());
    assertFalse(registry.contains("far"));
  }

  @Test
  void routeArrivalThatDoesNotMatchTheRegisteredIndexAndNodeNeverReleases() {
    TurnbackFootprintGuardRegistry registry = new TurnbackFootprintGuardRegistry();
    OccupancyResource oldApproach = OccupancyResource.forConflict("old-approach");
    registry.register(
        "mismatch",
        NodeId.of("TERM"),
        Set.of(oldApproach),
        forwardPath("TERM", 10.0, "A", 10.0, "B", 10.0, "C", 30.0, "STATION"),
        OptionalDouble.of(6.0),
        1,
        routeEvidence(0, "TERM", "C", "STATION"));

    assertTrue(registry.observeRouteArrival("mismatch", 2, NodeId.of("OTHER")).isEmpty());
    // 远处的节点出现在更早的下标上：下标是有序证据，节点名相同也不能借用
    assertTrue(registry.observeRouteArrival("mismatch", 1, NodeId.of("STATION")).isEmpty());
    assertTrue(registry.observeRouteArrival("mismatch", 9, NodeId.of("STATION")).isEmpty());
    assertEquals(Set.of(oldApproach), registry.protectedResources("mismatch"));
  }

  @Test
  void epochWithoutRouteEvidenceKeepsFailRetainingOnRouteArrival() {
    TurnbackFootprintGuardRegistry registry = new TurnbackFootprintGuardRegistry();
    OccupancyResource legacy = OccupancyResource.forConflict("legacy");
    registry.register(
        "no-evidence",
        NodeId.of("TERM"),
        Set.of(legacy),
        forwardPath("TERM", 10.0, "A", 10.0, "B", 10.0, "C", 30.0, "STATION"),
        OptionalDouble.of(6.0),
        1);

    assertTrue(registry.observeRouteArrival("no-evidence", 2, NodeId.of("STATION")).isEmpty());
    assertEquals(Set.of(legacy), registry.protectedResources("no-evidence"));
  }

  @Test
  void unknownTrainLengthStillFailRetainsEvenWithRouteEvidence() {
    TurnbackFootprintGuardRegistry registry = new TurnbackFootprintGuardRegistry();
    OccupancyResource unknown = OccupancyResource.forConflict("unknown-length");
    registry.register(
        "unknown-length",
        NodeId.of("TERM"),
        Set.of(unknown),
        forwardPath("TERM", 10.0, "A", 10.0, "B", 500.0, "STATION"),
        OptionalDouble.empty(),
        1,
        routeEvidence(0, "TERM", "STATION"));

    assertTrue(
        registry.observeRouteArrival("unknown-length", 1, NodeId.of("STATION")).isEmpty(),
        "车长未知就没有阈值可比，仍然 fail-retain");
    assertEquals(Set.of(unknown), registry.protectedResources("unknown-length"));
  }

  @Test
  void epochWithoutAPlanBecauseOfARevisitStillGetsTheFarEvidence() {
    TurnbackFootprintGuardRegistry registry = new TurnbackFootprintGuardRegistry();
    OccupancyResource oldApproach = OccupancyResource.forConflict("old-approach");
    // A→TERM 回到已经走过的节点，累计 8 还没到车长 6 + 边距 8：连续路径计划建不起来
    registry.register(
        "revisit",
        NodeId.of("TERM"),
        Set.of(oldApproach),
        forwardPath("TERM", 8.0, "A", 8.0, "TERM", 8.0, "B", 40.0, "STATION"),
        OptionalDouble.of(6.0),
        1,
        routeEvidence(0, "TERM", "STATION"));

    assertTrue(registry.observeProgress("revisit", NodeId.of("STATION")).isEmpty());
    assertEquals(
        Set.of(oldApproach),
        registry.observeRouteArrival("revisit", 1, NodeId.of("STATION")).orElseThrow().resources());
  }

  @Test
  void activeEpochWhoseNodeEventsWereAllMissedIsAlsoReleasedByFarRouteProgress() {
    TurnbackFootprintGuardRegistry registry = new TurnbackFootprintGuardRegistry();
    OccupancyResource oldApproach = OccupancyResource.forConflict("old-approach");
    registry.register(
        "silent",
        NodeId.of("TERM"),
        Set.of(oldApproach),
        forwardPath("TERM", 10.0, "A", 10.0, "B", 10.0, "C", 30.0, "STATION"),
        OptionalDouble.of(6.0),
        1,
        routeEvidence(0, "TERM", "STATION"));

    // 一个节点事件也没收到，epoch 仍是 active——但列车已经按序到了 60 格外的车站
    assertEquals(
        Set.of(oldApproach),
        registry.observeRouteArrival("silent", 1, NodeId.of("STATION")).orElseThrow().resources());
  }

  @Test
  void farRouteProgressDoesNotReleaseResourcesAnotherEpochStillProtects() {
    TurnbackFootprintGuardRegistry registry = new TurnbackFootprintGuardRegistry();
    OccupancyResource shared = OccupancyResource.forConflict("shared-throat");
    OccupancyResource first = OccupancyResource.forConflict("first-only");
    OccupancyResource second = OccupancyResource.forConflict("second-only");
    registry.register(
        "two-epochs",
        NodeId.of("TERM-A"),
        Set.of(shared, first),
        forwardPath("TERM-A", 10.0, "A1", 10.0, "A2", 60.0, "STATION-A"),
        OptionalDouble.of(2.0),
        1,
        routeEvidence(0, "TERM-A", "STATION-A"));
    registry.register(
        "two-epochs",
        NodeId.of("TERM-B"),
        Set.of(shared, second),
        forwardPath("TERM-B", 10.0, "B1", 10.0, "B2", 10.0, "B3"),
        OptionalDouble.of(2.0),
        1);

    assertEquals(
        Set.of(first),
        registry
            .observeRouteArrival("two-epochs", 1, NodeId.of("STATION-A"))
            .orElseThrow()
            .resources());
    assertEquals(Set.of(shared, second), registry.protectedResources("two-epochs"));
  }

  @Test
  void diagnosticsNameTheSealThePlanlessRegistrationAndTheFarRelease() {
    java.util.List<String> messages = new java.util.ArrayList<>();
    TurnbackFootprintGuardRegistry registry = new TurnbackFootprintGuardRegistry(messages::add);
    OccupancyResource oldApproach = OccupancyResource.forConflict("old-approach");
    registry.register(
        "diag",
        NodeId.of("TERM"),
        Set.of(oldApproach),
        forwardPath("TERM", 10.0, "A", 10.0, "B", 10.0, "C", 30.0, "STATION"),
        OptionalDouble.of(6.0),
        1,
        routeEvidence(0, "TERM", "STATION"));
    registry.register(
        "diag-nolen",
        NodeId.of("TERM"),
        Set.of(OccupancyResource.forConflict("x")),
        forwardPath("TERM", 10.0, "A"),
        OptionalDouble.empty(),
        1,
        noRouteEvidence());

    registry.observeProgress("diag", NodeId.of("C"));
    registry.observeRouteArrival("diag", 1, NodeId.of("STATION"));

    assertTrue(
        messages.stream()
            .anyMatch(
                m -> m.startsWith("TURNBACK_FOOTPRINT_GUARD_NO_PLAN") && m.contains("diag-nolen")),
        messages.toString());
    assertTrue(
        messages.stream()
            .anyMatch(
                m ->
                    m.startsWith("TURNBACK_FOOTPRINT_GUARD_SEALED")
                        && m.contains("train=diag")
                        && m.contains("observed=C")),
        messages.toString());
    assertTrue(
        messages.stream()
            .anyMatch(
                m ->
                    m.startsWith("TURNBACK_FOOTPRINT_GUARD_FAR_CLEAR")
                        && m.contains("train=diag")
                        && m.contains("routeIndex=1")),
        messages.toString());
  }

  private static TurnbackFootprintGuardRegistry.RouteEvidence noRouteEvidence() {
    return TurnbackFootprintGuardRegistry.RouteEvidence.none();
  }

  /** 路线节点序列（下标 0 起）与折返所在的下标；后备释放只认这些有序下标上的节点。 */
  private static TurnbackFootprintGuardRegistry.RouteEvidence routeEvidence(
      int startIndex, String... routeNodes) {
    return new TurnbackFootprintGuardRegistry.RouteEvidence(
        java.util.Arrays.stream(routeNodes).map(NodeId::of).toList(), startIndex);
  }

  private static List<TurnbackFootprintGuardRegistry.ForwardPathEdge> forwardPath(
      Object... nodeAndLengthValues) {
    if (nodeAndLengthValues.length < 3 || nodeAndLengthValues.length % 2 == 0) {
      throw new IllegalArgumentException("expected node, length, node, ...");
    }
    java.util.ArrayList<TurnbackFootprintGuardRegistry.ForwardPathEdge> edges =
        new java.util.ArrayList<>();
    for (int i = 0; i + 2 < nodeAndLengthValues.length; i += 2) {
      edges.add(
          new TurnbackFootprintGuardRegistry.ForwardPathEdge(
              NodeId.of((String) nodeAndLengthValues[i]),
              NodeId.of((String) nodeAndLengthValues[i + 2]),
              (Double) nodeAndLengthValues[i + 1]));
    }
    return List.copyOf(edges);
  }
}
