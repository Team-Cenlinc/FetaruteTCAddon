package org.fetarute.fetaruteTCAddon.dispatcher.runtime.supervisor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteId;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.DirectedTraversalContext;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.ExpandedPathPlan;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.MovementPlanSnapshot;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyResource;
import org.junit.jupiter.api.Test;

class CanonicalForwardPathEvidenceTest {

  @Test
  void derivesOnlyExactCanonicalDirectedChain() {
    MovementPlanSnapshot plan = movementPlan("train-a", false);

    CanonicalForwardPathEvidence.Derivation derivation =
        CanonicalForwardPathEvidence.derive("train-a", plan);

    assertTrue(derivation.evidence().isPresent());
    CanonicalForwardPathEvidence evidence = derivation.evidence().orElseThrow();
    assertTrue(evidence.proves("TRAIN-A", "A", "B"));
    assertFalse(evidence.proves("train-a", "B", "A"));
    assertTrue(evidence.coversNodeOrEdgeResource("NODE:A"));
    assertTrue(evidence.coversNodeOrEdgeResource("NODE:B"));
    assertTrue(evidence.coversNodeOrEdgeResource("EDGE:A~B"));
    assertFalse(evidence.coversNodeOrEdgeResource("NODE:C"));
    assertFalse(evidence.coversNodeOrEdgeResource("CONFLICT:switcher:B"));
    assertEquals("-", derivation.failureReason());
  }

  @Test
  void rejectsDirectedEdgeWhosePhysicalIdDoesNotMatchItsEndpoints() {
    CanonicalForwardPathEvidence.Derivation derivation =
        CanonicalForwardPathEvidence.derive("train-a", movementPlan("train-a", true));

    assertTrue(derivation.evidence().isEmpty());
    assertEquals("PLAN_DIRECTED_EDGE_MISMATCH", derivation.failureReason());
  }

  @Test
  void rejectsMovementPlanOwnedByAnotherTrain() {
    CanonicalForwardPathEvidence.Derivation derivation =
        CanonicalForwardPathEvidence.derive("train-b", movementPlan("train-a", false));

    assertTrue(derivation.evidence().isEmpty());
    assertEquals("PLAN_TRAIN_MISMATCH", derivation.failureReason());
  }

  @Test
  void rejectsMovementPlanWithoutRouteIdentity() {
    MovementPlanSnapshot plan = movementPlan("train-a", false);
    MovementPlanSnapshot withoutRoute =
        copyWithRouteAndResources(plan, Optional.empty(), plan.movementRequiredResources());

    CanonicalForwardPathEvidence.Derivation derivation =
        CanonicalForwardPathEvidence.derive("train-a", withoutRoute);

    assertTrue(derivation.evidence().isEmpty());
    assertEquals("PLAN_ROUTE_ID_MISSING", derivation.failureReason());
  }

  @Test
  void rejectsMovementWindowOutsideCanonicalPath() {
    MovementPlanSnapshot plan = movementPlan("train-a", false);
    MovementPlanSnapshot malformed =
        copyWithRouteAndResources(
            plan, plan.routeId(), List.of(OccupancyResource.forNode(NodeId.of("X"))));

    CanonicalForwardPathEvidence.Derivation derivation =
        CanonicalForwardPathEvidence.derive("train-a", malformed);

    assertTrue(derivation.evidence().isEmpty());
    assertEquals("PLAN_MOVEMENT_WINDOW_PATH_MISMATCH", derivation.failureReason());
  }

  @Test
  void evidenceHashIgnoresRealtimeVersionsAndRequestIdentity() {
    CanonicalForwardPathEvidence first =
        CanonicalForwardPathEvidence.derive(
                "train-a", movementPlan("train-a", false, 7L, 11L, "request-a"))
            .evidence()
            .orElseThrow();
    CanonicalForwardPathEvidence refreshed =
        CanonicalForwardPathEvidence.derive(
                "train-a", movementPlan("train-a", false, 19L, 23L, "request-b"))
            .evidence()
            .orElseThrow();

    assertEquals(first.evidenceHash(), refreshed.evidenceHash());
  }

  @Test
  void rearResourcesRequireExplicitForwardProgressReleaseEnrichment() {
    CanonicalForwardPathEvidence forwardOnly =
        CanonicalForwardPathEvidence.derive("train-a", movementPlan("train-a", false))
            .evidence()
            .orElseThrow();
    NodeId rear = NodeId.of("REAR");
    NodeId anchor = NodeId.of("A");
    String rearNode = OccupancyResource.forNode(rear).toString();
    String rearEdge = OccupancyResource.forEdge(EdgeId.undirected(rear, anchor)).toString();

    CanonicalForwardPathEvidence enriched =
        forwardOnly.withForwardProgressReleaseResources(
            List.of(rearNode, rearEdge, "CONFLICT:single:untrusted"));

    assertFalse(forwardOnly.coversReleaseResource(rearNode));
    assertFalse(enriched.coversNodeOrEdgeResource(rearNode));
    assertTrue(enriched.coversReleaseResource(rearNode));
    assertTrue(enriched.coversReleaseResource(rearEdge));
    assertFalse(enriched.coversReleaseResource("NODE:UNRELATED"));
    assertFalse(enriched.coversReleaseResource("CONFLICT:single:untrusted"));
    assertEquals("CANONICAL_MOVEMENT_PLAN_WITH_REAR_RETAIN", enriched.evidenceKind());
    assertNotEquals(forwardOnly.evidenceHash(), enriched.evidenceHash());
  }

  private static MovementPlanSnapshot movementPlan(String trainName, boolean mismatchedEdgeId) {
    return movementPlan(trainName, mismatchedEdgeId, 7L, 11L, "request-a");
  }

  private static MovementPlanSnapshot movementPlan(
      String trainName,
      boolean mismatchedEdgeId,
      long occupancyVersion,
      long progressVersion,
      String requestId) {
    NodeId a = NodeId.of("A");
    NodeId b = NodeId.of("B");
    NodeId c = NodeId.of("C");
    EdgeId canonicalEdge = EdgeId.undirected(a, b);
    EdgeId recordedEdge = mismatchedEdgeId ? EdgeId.undirected(a, c) : canonicalEdge;
    return new MovementPlanSnapshot(
        trainName,
        Optional.of(RouteId.of("COMP:OP:LINE:route")),
        0,
        Optional.of(a),
        Optional.of(a),
        Optional.of(a),
        Optional.of(b),
        new ExpandedPathPlan(
            List.of(a, b),
            List.of(new DirectedTraversalContext.DirectedEdge(recordedEdge, a, b)),
            Map.of(),
            Map.of()),
        List.of(OccupancyResource.forEdge(canonicalEdge)),
        occupancyVersion,
        progressVersion,
        requestId);
  }

  private static MovementPlanSnapshot copyWithRouteAndResources(
      MovementPlanSnapshot source,
      Optional<RouteId> routeId,
      List<OccupancyResource> movementRequiredResources) {
    return new MovementPlanSnapshot(
        source.trainKey(),
        routeId,
        source.routeIndex(),
        source.currentNode(),
        source.lastPassedGraphNode(),
        source.effectiveFromNode(),
        source.effectiveToNode(),
        source.expandedPathPlan(),
        movementRequiredResources,
        source.occupancyVersion(),
        source.progressVersion(),
        source.requestId());
  }
}
