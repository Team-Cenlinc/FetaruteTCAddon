package org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** {@link SwitcherMovementTopology} 的道岔局部 movement 分类测试。 */
@DisplayName("SwitcherMovementTopology")
class SwitcherMovementTopologyTest {

  private static final String SWITCHER_NODE = "SWITCHER:Towny:-520:77:1390";
  private static final OccupancyResource SWITCHER =
      OccupancyResource.forConflict("switcher:" + SWITCHER_NODE);

  @Test
  void classifiesSameOrderedMovement() {
    SwitcherMovementTopology.Classification result =
        classify(path("A", SWITCHER_NODE, "X"), path("A", SWITCHER_NODE, "X"));

    assertEquals(SwitcherMovementTopology.Relation.SAME_MOVEMENT, result.relation());
    assertEquals(SwitcherMovementTopology.EvidenceReason.EXACT_ORDERED_LEGS, result.reason());
    assertTrue(result.proof().isPresent());
  }

  @Test
  void classifiesMultiBranchMergeWithoutCorridorDirection() {
    SwitcherMovementTopology.Classification result =
        classify(
            path("SURC:S:JBS:1:001", SWITCHER_NODE, "SURC:JBS:CSB:2:001"),
            path("SURC:S:JBS:3:001", SWITCHER_NODE, "SURC:JBS:CSB:2:001"));

    assertEquals(SwitcherMovementTopology.Relation.MERGE, result.relation());
    assertEquals(SwitcherMovementTopology.EvidenceReason.SHARED_EGRESS_LEG, result.reason());
    assertEquals(NodeId.of("SURC:S:JBS:1:001"), result.proof().orElseThrow().firstIngress());
    assertEquals(NodeId.of("SURC:JBS:CSB:2:001"), result.proof().orElseThrow().firstEgress());
  }

  @Test
  void classifiesDivergeHeadOnAndDistinctLocalMovements() {
    assertEquals(
        SwitcherMovementTopology.Relation.DIVERGE,
        classify(path("A", SWITCHER_NODE, "X"), path("A", SWITCHER_NODE, "Y")).relation());
    assertEquals(
        SwitcherMovementTopology.Relation.HEAD_ON,
        classify(path("A", SWITCHER_NODE, "B"), path("B", SWITCHER_NODE, "A")).relation());
    assertEquals(
        SwitcherMovementTopology.Relation.CROSSING,
        classify(path("A", SWITCHER_NODE, "X"), path("B", SWITCHER_NODE, "Y")).relation());
    assertEquals(
        SwitcherMovementTopology.Relation.HEAD_ON,
        classify(path("A", SWITCHER_NODE, "X"), path("B", SWITCHER_NODE, "A")).relation());
  }

  @Test
  void classificationIsSymmetricWhenPlanOrderChanges() {
    Optional<MovementPlanSnapshot> first = path("A", SWITCHER_NODE, "X");
    Optional<MovementPlanSnapshot> second = path("B", SWITCHER_NODE, "X");

    SwitcherMovementTopology.Classification forward = classify(first, second);
    SwitcherMovementTopology.Classification reversed = classify(second, first);

    assertEquals(forward.relation(), reversed.relation());
    assertEquals(forward.reason(), reversed.reason());
    assertEquals(
        forward.proof().orElseThrow().firstIngress(),
        reversed.proof().orElseThrow().secondIngress());
    assertEquals(
        forward.proof().orElseThrow().secondEgress(), reversed.proof().orElseThrow().firstEgress());
  }

  @Test
  void rejectsMissingMismatchedAndMalformedEvidence() {
    assertUnknown(
        SwitcherMovementTopology.classify(
            SWITCHER, Optional.empty(), path("B", SWITCHER_NODE, "X")),
        SwitcherMovementTopology.EvidenceReason.FIRST_PLAN_MISSING);
    assertUnknown(
        SwitcherMovementTopology.classify(
            SWITCHER,
            plan(
                SWITCHER.key(),
                "switcher:other",
                nodes("A", SWITCHER_NODE, "X"),
                nodes("A", SWITCHER_NODE, "X"),
                true),
            path("B", SWITCHER_NODE, "X")),
        SwitcherMovementTopology.EvidenceReason.FIRST_KEY_MISMATCH);
    assertUnknown(
        classify(path("A", "NO-SWITCHER", "X"), path("B", SWITCHER_NODE, "X")),
        SwitcherMovementTopology.EvidenceReason.FIRST_SWITCHER_NOT_ON_PATH);
    assertUnknown(
        classify(path("A", SWITCHER_NODE, "X", SWITCHER_NODE, "Y"), path("B", SWITCHER_NODE, "X")),
        SwitcherMovementTopology.EvidenceReason.FIRST_SWITCHER_REPEATED);
    assertUnknown(
        classify(path(SWITCHER_NODE, "X"), path("B", SWITCHER_NODE, "X")),
        SwitcherMovementTopology.EvidenceReason.FIRST_INGRESS_MISSING);
    assertUnknown(
        classify(path("A", SWITCHER_NODE), path("B", SWITCHER_NODE, "X")),
        SwitcherMovementTopology.EvidenceReason.FIRST_EGRESS_MISSING);
    assertUnknown(
        classify(path("A", SWITCHER_NODE, "A"), path("B", SWITCHER_NODE, "X")),
        SwitcherMovementTopology.EvidenceReason.FIRST_DEGENERATE_MOVEMENT);
    assertUnknown(
        SwitcherMovementTopology.classify(
            SWITCHER, path("A", SWITCHER_NODE, "X"), Optional.empty()),
        SwitcherMovementTopology.EvidenceReason.SECOND_PLAN_MISSING);
    assertUnknown(
        classify(path("A", SWITCHER_NODE, "X"), path(SWITCHER_NODE, "X")),
        SwitcherMovementTopology.EvidenceReason.SECOND_INGRESS_MISSING);
  }

  @Test
  void rejectsSignatureAndDirectedEdgesThatDoNotMatchCanonicalPlan() {
    assertUnknown(
        classify(
            plan(
                SWITCHER.key(),
                SWITCHER.key(),
                nodes("A", SWITCHER_NODE, "X"),
                nodes("A", SWITCHER_NODE, "Y"),
                true),
            path("B", SWITCHER_NODE, "X")),
        SwitcherMovementTopology.EvidenceReason.FIRST_SIGNATURE_PATH_MISMATCH);
    assertUnknown(
        classify(
            plan(
                SWITCHER.key(),
                SWITCHER.key(),
                nodes("A", SWITCHER_NODE, "X"),
                nodes("A", SWITCHER_NODE, "X"),
                false),
            path("B", SWITCHER_NODE, "X")),
        SwitcherMovementTopology.EvidenceReason.FIRST_DIRECTED_EDGE_MISMATCH);
    assertUnknown(
        classify(
            plan(
                SWITCHER.key(),
                SWITCHER.key(),
                nodes("A", SWITCHER_NODE, "X"),
                nodes("A", SWITCHER_NODE, "X"),
                true,
                false),
            path("B", SWITCHER_NODE, "X")),
        SwitcherMovementTopology.EvidenceReason.FIRST_DIRECTED_EDGE_MISMATCH);
  }

  @Test
  void rejectsNonSwitcherConflictBeforeInspectingSignatures() {
    OccupancyResource single = OccupancyResource.forConflict("single:JBS");

    SwitcherMovementTopology.Classification result =
        SwitcherMovementTopology.classify(
            single, path("A", SWITCHER_NODE, "X"), path("B", SWITCHER_NODE, "X"));

    assertUnknown(result, SwitcherMovementTopology.EvidenceReason.NOT_SWITCHER_CONFLICT);
    assertFalse(result.proof().isPresent());
  }

  @Test
  void rejectsNonCanonicalSwitcherPrefixWithoutThrowing() {
    OccupancyResource uppercase = OccupancyResource.forConflict("SWITCHER:" + SWITCHER_NODE);

    SwitcherMovementTopology.Classification result =
        SwitcherMovementTopology.classify(
            uppercase, path("A", SWITCHER_NODE, "X"), path("B", SWITCHER_NODE, "Y"));

    assertUnknown(result, SwitcherMovementTopology.EvidenceReason.NOT_SWITCHER_CONFLICT);
  }

  @Test
  void successfulEvidenceCanOnlyBeIssuedByClassifier() {
    assertTrue(
        Arrays.stream(SwitcherMovementTopology.Proof.class.getDeclaredConstructors())
            .allMatch(constructor -> Modifier.isPrivate(constructor.getModifiers())));
    assertTrue(
        Arrays.stream(SwitcherMovementTopology.Classification.class.getDeclaredConstructors())
            .allMatch(constructor -> Modifier.isPrivate(constructor.getModifiers())));
  }

  private static SwitcherMovementTopology.Classification classify(
      Optional<MovementPlanSnapshot> first, Optional<MovementPlanSnapshot> second) {
    return SwitcherMovementTopology.classify(SWITCHER, first, second);
  }

  private static Optional<MovementPlanSnapshot> path(String... values) {
    List<NodeId> path = nodes(values);
    return plan(SWITCHER.key(), SWITCHER.key(), path, path, true);
  }

  private static Optional<MovementPlanSnapshot> plan(
      String mapKey,
      String signatureKey,
      List<NodeId> expandedPath,
      List<NodeId> signaturePath,
      boolean includeDirectedEdges) {
    return plan(mapKey, signatureKey, expandedPath, signaturePath, includeDirectedEdges, true);
  }

  private static Optional<MovementPlanSnapshot> plan(
      String mapKey,
      String signatureKey,
      List<NodeId> expandedPath,
      List<NodeId> signaturePath,
      boolean includeDirectedEdges,
      boolean validEdgeIds) {
    List<DirectedTraversalContext.DirectedEdge> directedEdges = new ArrayList<>();
    if (includeDirectedEdges) {
      for (int index = 0; index + 1 < expandedPath.size(); index++) {
        NodeId from = expandedPath.get(index);
        NodeId to = expandedPath.get(index + 1);
        EdgeId edgeId =
            validEdgeIds
                ? EdgeId.undirected(from, to)
                : EdgeId.undirected(
                    NodeId.of("WRONG:" + index), NodeId.of("WRONG:" + index + ":NEXT"));
        directedEdges.add(new DirectedTraversalContext.DirectedEdge(edgeId, from, to));
      }
    }
    return Optional.of(
        new MovementPlanSnapshot(
            "test-train",
            Optional.empty(),
            0,
            expandedPath.isEmpty() ? Optional.empty() : Optional.of(expandedPath.get(0)),
            Optional.empty(),
            expandedPath.isEmpty() ? Optional.empty() : Optional.of(expandedPath.get(0)),
            expandedPath.size() < 2 ? Optional.empty() : Optional.of(expandedPath.get(1)),
            new ExpandedPathPlan(
                expandedPath,
                directedEdges,
                Map.of(),
                Map.of(
                    mapKey,
                    new DirectedTraversalContext.SwitcherPathSignature(
                        signatureKey, signaturePath))),
            List.of(SWITCHER),
            1L,
            1L,
            "test-request"));
  }

  private static List<NodeId> nodes(String... values) {
    return Arrays.stream(values).map(NodeId::of).toList();
  }

  private static void assertUnknown(
      SwitcherMovementTopology.Classification result,
      SwitcherMovementTopology.EvidenceReason reason) {
    assertEquals(SwitcherMovementTopology.Relation.UNKNOWN, result.relation());
    assertEquals(reason, result.reason());
    assertFalse(result.proof().isPresent());
  }
}
