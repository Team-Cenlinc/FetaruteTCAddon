package org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.junit.jupiter.api.Test;

class VerifiedSwitcherDrainClaimsTest {

  private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

  @Test
  void preparesAndResolvesPhysicalSwitcherDrainAcrossRouteIndexes() {
    for (int routeIndex : List.of(0, 8, 37)) {
      Fixture fixture = fixture(routeIndex);

      VerifiedSwitcherDrainClaims.Preparation preparation =
          VerifiedSwitcherDrainClaims.prepare(
              fixture.request(), snapshot(claim(fixture.switcherNode(), "inside~a")));

      assertTrue(preparation.authorized(), "routeIndex=" + routeIndex);
      assertEquals(
          AuthorizationPurpose.CONFLICT_CLEARING,
          preparation.request().purpose(),
          "routeIndex=" + routeIndex);
      assertEquals(Set.of(fixture.switcherConflict()), preparation.verifiedConflicts());
      assertEquals(
          Set.of(fixture.switcherConflict()),
          VerifiedSwitcherDrainClaims.resolve(
              preparation.request(), snapshot(claim(fixture.switcherNode(), "inside~b"))));
    }
  }

  @Test
  void preparationFailsClosedForApproachSoftNodeAndExternalExitFootprint() {
    Fixture fixture = fixture(4);
    OccupancyClaim selfNode = claim(fixture.switcherNode(), "inside");

    VerifiedSwitcherDrainClaims.Preparation approaching =
        VerifiedSwitcherDrainClaims.prepare(approachingRequest(fixture), snapshot(selfNode));
    OccupancyRequest softNode =
        fixture
            .request()
            .withResourceIntents(Map.of(fixture.switcherNode(), ResourceIntent.HOLD_ONLY));
    VerifiedSwitcherDrainClaims.Preparation soft =
        VerifiedSwitcherDrainClaims.prepare(softNode, snapshot(selfNode));
    OccupancyClaim externalExit = claim(fixture.exitEdge(), "entrant");
    VerifiedSwitcherDrainClaims.Preparation blocked =
        VerifiedSwitcherDrainClaims.prepare(fixture.request(), snapshot(selfNode, externalExit));
    OccupancyResource otherSwitcher = OccupancyResource.forConflict("switcher:SWITCHER:TEST:OTHER");
    OccupancyClaim otherSwitcherClaim = claim(otherSwitcher, "other-entrant");
    VerifiedSwitcherDrainClaims.Preparation otherSwitcherBlocked =
        VerifiedSwitcherDrainClaims.prepare(
            withAdditionalResource(fixture.request(), otherSwitcher),
            snapshot(selfNode, otherSwitcherClaim));

    assertFalse(approaching.authorized());
    assertEquals(AuthorizationPurpose.RUNTIME_MOVE, approaching.request().purpose());
    assertFalse(soft.authorized());
    assertEquals(AuthorizationPurpose.RUNTIME_MOVE, soft.request().purpose());
    assertFalse(blocked.authorized());
    assertEquals(Optional.of(externalExit), blocked.hardBlocker());
    assertEquals(AuthorizationPurpose.RUNTIME_MOVE, blocked.request().purpose());
    assertFalse(otherSwitcherBlocked.authorized());
    assertEquals(Optional.of(otherSwitcherClaim), otherSwitcherBlocked.hardBlocker());
    assertEquals(
        Optional.of(externalExit),
        DrainPathHardBlockers.firstExternalClaim(
            fixture.request(), List.of(externalExit), Set.of(fixture.exitEdge())));
    OccupancyClaim externalNode = claim(fixture.switcherNode(), "external-node");
    assertEquals(
        Optional.of(externalNode),
        DrainPathHardBlockers.firstExternalClaim(
            fixture.request(), List.of(externalNode), Set.of(fixture.switcherNode())));
  }

  @Test
  void preparationIgnoresExternalUnlockReservationOnPhysicalDrainPath() {
    Fixture fixture = fixture(5);
    OccupancyClaim selfNode = claim(fixture.switcherNode(), "inside");
    OccupancyClaim softReservation =
        claim(fixture.exitEdge(), "waiting-entrant", ClaimRole.UNLOCK_RESERVATION);

    VerifiedSwitcherDrainClaims.Preparation preparation =
        VerifiedSwitcherDrainClaims.prepare(fixture.request(), snapshot(selfNode, softReservation));

    assertTrue(preparation.authorized(), preparation.toString());
    assertEquals(Optional.empty(), preparation.hardBlocker());
  }

  @Test
  void physicalInterlockingZoneCannotBeIgnoredBySwitcherDrainProof() {
    OccupancyResource zone = OccupancyResource.forConflict("interlocking:physical-crossing");
    OccupancyRequest request =
        new OccupancyRequest("inside", Optional.empty(), NOW, List.of(zone), Map.of());
    OccupancyClaim external = claim(zone, "crossing-train");

    assertEquals(
        Optional.of(external),
        DrainPathHardBlockers.firstExternalClaim(request, List.of(external), Set.of(zone)));
  }

  @Test
  void preparationRejectsMalformedOrOmittedHardExitEdge() {
    Fixture fixture = fixture(6);
    int routeIndex = fixture.request().directedContext().orElseThrow().currentIndex();
    List<NodeId> path = List.of(fixture.switcher(), fixture.exit());
    EdgeId forgedEdgeId =
        EdgeId.undirected(fixture.switcher(), NodeId.of("FORGED-EXIT:" + routeIndex));
    OccupancyRequest malformedEdge =
        fixture
            .request()
            .withDirectedContext(
                Optional.of(
                    directedContext(
                        "inside",
                        routeIndex,
                        fixture.switcher(),
                        fixture.exit(),
                        path,
                        List.of(
                            new DirectedTraversalContext.DirectedEdge(
                                forgedEdgeId, fixture.switcher(), fixture.exit())),
                        Map.of(
                            fixture.switcherConflict().key(),
                            new DirectedTraversalContext.SwitcherPathSignature(
                                fixture.switcherConflict().key(), path)))));
    OccupancyRequest omittedExitEdge = withoutResource(fixture.request(), fixture.exitEdge());
    VerifiedSwitcherDrainClaims.VerificationSnapshot current =
        snapshot(claim(fixture.switcherNode(), "inside"));

    assertFalse(VerifiedSwitcherDrainClaims.prepare(malformedEdge, current).authorized());
    assertFalse(VerifiedSwitcherDrainClaims.prepare(omittedExitEdge, current).authorized());
  }

  @Test
  void resolverReturnsOnlyExactPlannedAbstractSwitcherClaim() {
    Fixture fixture = fixture(12);
    OccupancyRequest prepared =
        VerifiedSwitcherDrainClaims.prepare(
                fixture.request(), snapshot(claim(fixture.switcherNode(), "inside")))
            .request();
    OccupancyResource unplannedSwitcher =
        OccupancyResource.forConflict("switcher:SWITCHER:TEST:UNPLANNED");
    OccupancyResource singleConflict = OccupancyResource.forConflict("single:test:A~B");
    OccupancyRequest forgedMultiHint =
        prepared.withConflictReleaseHints(
            AuthorizationPurpose.CONFLICT_CLEARING,
            Map.of(
                fixture.switcherConflict().key(),
                ConflictReleaseHint.verifiedSwitcherOccupant(
                    fixture.switcherConflict().key(), "planned"),
                unplannedSwitcher.key(),
                ConflictReleaseHint.verifiedSwitcherOccupant(
                    unplannedSwitcher.key(), "unplanned")));

    Set<OccupancyResource> resolved =
        VerifiedSwitcherDrainClaims.resolve(
            forgedMultiHint, snapshot(claim(fixture.switcherNode(), "inside")));
    VerifiedSwitcherDrainClaims.VerificationSnapshot lateSameKeyClaim =
        new VerifiedSwitcherDrainClaims.VerificationSnapshot(
            List.of(
                claim(fixture.switcherNode(), "inside"),
                claim(fixture.switcherConflict(), "late-entrant")),
            2L,
            1L);

    assertEquals(Set.of(fixture.switcherConflict()), resolved);
    assertEquals(
        Set.of(fixture.switcherConflict()),
        VerifiedSwitcherDrainClaims.resolve(prepared, lateSameKeyClaim));
    assertFalse(resolved.contains(fixture.switcherNode()));
    assertFalse(resolved.contains(fixture.exitEdge()));
    assertFalse(resolved.contains(unplannedSwitcher));
    assertFalse(resolved.contains(singleConflict));
  }

  @Test
  void resolverRejectsMissingUnboundAndNonOccupantEvidence() {
    Fixture fixture = fixture(21);
    VerifiedSwitcherDrainClaims.Preparation stalePreparation =
        VerifiedSwitcherDrainClaims.prepare(
            fixture.request(),
            new VerifiedSwitcherDrainClaims.VerificationSnapshot(
                List.of(claim(fixture.switcherNode(), "inside")), 2L, 1L));
    OccupancyRequest prepared =
        VerifiedSwitcherDrainClaims.prepare(
                fixture.request(), snapshot(claim(fixture.switcherNode(), "inside")))
            .request();
    OccupancyRequest missingPlan = prepared.withDirectedContext(Optional.empty());
    OccupancyRequest unboundPlan = prepared.withDirectedOccupancyVersion(-1L);
    OccupancyRequest ordinaryMove =
        fixture.request().withConflictClearingEvidence(prepared.conflictReleaseHints());
    OccupancyRequest wrongEvidenceKind =
        prepared.withConflictReleaseHints(
            AuthorizationPurpose.CONFLICT_CLEARING,
            Map.of(
                fixture.switcherConflict().key(),
                ConflictReleaseHint.verifiedDrainAuthority(
                    fixture.switcherConflict().key(), "wrong-kind")));
    VerifiedSwitcherDrainClaims.VerificationSnapshot currentSnapshot =
        snapshot(claim(fixture.switcherNode(), "inside"));
    VerifiedSwitcherDrainClaims.VerificationSnapshot staleProgressSnapshot =
        new VerifiedSwitcherDrainClaims.VerificationSnapshot(currentSnapshot.claims(), 2L, 2L);

    assertFalse(stalePreparation.authorized());
    assertEquals(AuthorizationPurpose.RUNTIME_MOVE, stalePreparation.request().purpose());
    assertTrue(VerifiedSwitcherDrainClaims.resolve(prepared, staleProgressSnapshot).isEmpty());
    assertTrue(VerifiedSwitcherDrainClaims.resolve(missingPlan, currentSnapshot).isEmpty());
    assertTrue(VerifiedSwitcherDrainClaims.resolve(unboundPlan, currentSnapshot).isEmpty());
    assertTrue(VerifiedSwitcherDrainClaims.resolve(ordinaryMove, currentSnapshot).isEmpty());
    assertTrue(VerifiedSwitcherDrainClaims.resolve(wrongEvidenceKind, currentSnapshot).isEmpty());
  }

  private static Fixture fixture(int routeIndex) {
    NodeId switcher = NodeId.of("SWITCHER:TEST:" + routeIndex);
    NodeId exit = NodeId.of("EXIT:" + routeIndex);
    EdgeId exitEdgeId = EdgeId.undirected(switcher, exit);
    OccupancyResource conflict = OccupancyResource.forConflict("switcher:" + switcher.value());
    OccupancyResource node = OccupancyResource.forNode(switcher);
    OccupancyResource edge = OccupancyResource.forEdge(exitEdgeId);
    List<NodeId> path = List.of(switcher, exit);
    OccupancyRequest request =
        new OccupancyRequest(
                "inside",
                Optional.empty(),
                NOW,
                List.of(conflict, node, edge, OccupancyResource.forNode(exit)),
                Map.of(),
                Map.of(conflict.key(), 0),
                0,
                AuthorizationPurpose.RUNTIME_MOVE)
            .withDirectedContext(
                Optional.of(
                    directedContext(
                        "inside",
                        routeIndex,
                        switcher,
                        exit,
                        path,
                        List.of(
                            new DirectedTraversalContext.DirectedEdge(exitEdgeId, switcher, exit)),
                        Map.of(
                            conflict.key(),
                            new DirectedTraversalContext.SwitcherPathSignature(
                                conflict.key(), path)))));
    return new Fixture(request, switcher, exit, conflict, node, edge);
  }

  private static OccupancyRequest approachingRequest(Fixture fixture) {
    NodeId entry =
        NodeId.of("ENTRY:" + fixture.request().directedContext().orElseThrow().currentIndex());
    EdgeId entryEdgeId = EdgeId.undirected(entry, fixture.switcher());
    EdgeId exitEdgeId = EdgeId.undirected(fixture.switcher(), fixture.exit());
    List<NodeId> path = List.of(entry, fixture.switcher(), fixture.exit());
    return fixture
        .request()
        .withDirectedContext(
            Optional.of(
                directedContext(
                    "inside",
                    fixture.request().directedContext().orElseThrow().currentIndex(),
                    entry,
                    fixture.switcher(),
                    path,
                    List.of(
                        new DirectedTraversalContext.DirectedEdge(
                            entryEdgeId, entry, fixture.switcher()),
                        new DirectedTraversalContext.DirectedEdge(
                            exitEdgeId, fixture.switcher(), fixture.exit())),
                    Map.of(
                        fixture.switcherConflict().key(),
                        new DirectedTraversalContext.SwitcherPathSignature(
                            fixture.switcherConflict().key(), path)))));
  }

  private static OccupancyRequest withAdditionalResource(
      OccupancyRequest request, OccupancyResource resource) {
    List<OccupancyResource> resources = new ArrayList<>(request.resourceList());
    resources.add(resource);
    return new OccupancyRequest(
        request.trainName(),
        request.routeId(),
        request.now(),
        resources,
        request.corridorDirections(),
        request.conflictEntryOrders(),
        request.priority(),
        request.purpose(),
        request.conflictReleaseHints(),
        request.resourceIntents(),
        request.directedContext());
  }

  private static OccupancyRequest withoutResource(
      OccupancyRequest request, OccupancyResource resource) {
    List<OccupancyResource> resources =
        request.resourceList().stream().filter(candidate -> !resource.equals(candidate)).toList();
    return new OccupancyRequest(
        request.trainName(),
        request.routeId(),
        request.now(),
        resources,
        request.corridorDirections(),
        request.conflictEntryOrders(),
        request.priority(),
        request.purpose(),
        request.conflictReleaseHints(),
        request.resourceIntents(),
        request.directedContext());
  }

  private static DirectedTraversalContext directedContext(
      String trainName,
      int routeIndex,
      NodeId current,
      NodeId effectiveTo,
      List<NodeId> path,
      List<DirectedTraversalContext.DirectedEdge> edges,
      Map<String, DirectedTraversalContext.SwitcherPathSignature> signatures) {
    return new DirectedTraversalContext(
        trainName,
        Optional.empty(),
        routeIndex,
        Optional.of(current),
        Optional.empty(),
        Optional.of(current),
        Optional.of(effectiveTo),
        path,
        edges,
        Map.of(),
        signatures,
        "TEST",
        1L,
        1L,
        "generic-switcher-" + routeIndex,
        Optional.empty());
  }

  private static OccupancyClaim claim(OccupancyResource resource, String trainName) {
    return claim(resource, trainName, ClaimRole.HOLD_ONLY);
  }

  private static OccupancyClaim claim(
      OccupancyResource resource, String trainName, ClaimRole role) {
    return new OccupancyClaim(
        resource, trainName, Optional.empty(), NOW, Duration.ZERO, Optional.empty(), role);
  }

  private static VerifiedSwitcherDrainClaims.VerificationSnapshot snapshot(
      OccupancyClaim... claims) {
    return new VerifiedSwitcherDrainClaims.VerificationSnapshot(List.of(claims), 1L, 1L);
  }

  private record Fixture(
      OccupancyRequest request,
      NodeId switcher,
      NodeId exit,
      OccupancyResource switcherConflict,
      OccupancyResource switcherNode,
      OccupancyResource exitEdge) {}
}
