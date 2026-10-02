package org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.junit.jupiter.api.Test;

/**
 * 道岔排队与岔上列车：实服 2026-09-27 SPB 汇合岔 {@code -566:77:1179} 的互等。
 *
 * <p>MT-LP-6727 车头已越过汇合岔、车身压在岔上，以 HOLD_ONLY 持有道岔节点与道岔冲突资源；MT-LP-7340 还在 WSD:2，要过同一个道岔， 被 6727
 * 压着的节点挡住后先入了道岔排队。6727 要往前开就得把道岔冲突资源升级为 MOVEMENT_REQUIRED，而升级要排队、队头是 7340—— 7340 等 6727 让出节点，6727 等
 * 7340 让出队头，互等到关服。另一半原因：6727 每拍停车保持以 HOLD_ONLY 重新接纳道岔，接纳顺手删了它自己的 排队条目，下一拍再以新的 firstSeen 入队，资历永远是
 * 0，仲裁永远输给 7340。
 */
class SwitcherOccupantQueueTest {

  private static final Instant NOW = Instant.parse("2026-09-27T17:41:59Z");
  private static final String OCCUPANT = "SURC-MT-LP-6727";
  private static final String ENTRANT = "SURC-MT-LP-7340";
  private static final NodeId ENTRY = NodeId.of("SURC:SPB:WSD:2:001");
  private static final NodeId SWITCHER = NodeId.of("SWITCHER:Towny:-566:77:1179");
  private static final NodeId EXIT = NodeId.of("SURC:SPB:JBS:1:002");
  private static final OccupancyResource SWITCHER_CONFLICT =
      OccupancyResource.forConflict("switcher:" + SWITCHER.value());
  private static final OccupancyResource SWITCHER_NODE = OccupancyResource.forNode(SWITCHER);
  private static final OccupancyResource EXIT_EDGE =
      OccupancyResource.forEdge(EdgeId.undirected(SWITCHER, EXIT));
  private static final OccupancyResource ENTRY_EDGE =
      OccupancyResource.forEdge(EdgeId.undirected(ENTRY, SWITCHER));

  /** 岔上的车带着已验证的出清证明，越过岔外先排上的车；不带证明的同一请求照旧排在后面。 */
  @Test
  void verifiedSwitcherOccupantClearsBeforeTheQueuedEntrant() {
    SimpleOccupancyManager manager = manager();
    holdPositionOnSwitcher(manager);
    OccupancyDecision entrant = manager.canEnter(entrantRequest(NOW));
    assertFalse(entrant.allowed(), "岔外的车被岔上车体压着的节点挡住");
    assertEquals(ENTRANT, queueHead(manager, SWITCHER_CONFLICT), "岔外的车先排进了道岔队列");

    OccupancyRequest plain = occupantRequest(NOW.plusSeconds(1));
    OccupancyDecision withoutProof = manager.canEnter(plain);
    assertFalse(withoutProof.allowed(), "没有出清证明：保护性持有不能越过队头");
    assertTrue(withoutProof.reason().startsWith("canenter-queue-blocked"), withoutProof.reason());

    VerifiedSwitcherDrainClaims.Preparation proof =
        VerifiedSwitcherDrainClaims.prepare(
            plain,
            new VerifiedSwitcherDrainClaims.VerificationSnapshot(manager.snapshotClaims(), 1L, 1L));
    assertTrue(proof.authorized(), proof.toString());

    OccupancyDecision withProof = manager.acquire(proof.request());

    assertTrue(withProof.allowed(), withProof.reason());
    assertEquals(ClaimRole.MOVEMENT_REQUIRED, claimOf(manager, SWITCHER_CONFLICT, OCCUPANT).role());
    assertFalse(manager.canEnter(entrantRequest(NOW.plusSeconds(2))).allowed(), "岔外的车照旧等车体让开");
  }

  /**
   * 停车保持以 HOLD_ONLY 接纳道岔不算排到：排队条目与 firstSeen 原样保留；拿到 MOVEMENT_REQUIRED 才出队。
   *
   * <p>岔上车先因前车占着出口节点而入队，岔外车随后排在它后面。停车保持接纳之后，前车让开，岔上车仍是队头、拿到前进授权。以前 HOLD_ONLY 接纳也会
   * 删排队，岔上车这时已经排到了岔外车后面。
   */
  @Test
  void holdAdmissionKeepsQueueSeniorityUntilAuthorityIsAdmitted() {
    SimpleOccupancyManager manager = manager();
    holdPositionOnSwitcher(manager);
    OccupancyResource exitNode = OccupancyResource.forNode(EXIT);
    assertTrue(
        manager
            .acquire(
                new OccupancyRequest(
                    "SURC-MT-LP-4909", Optional.empty(), NOW, List.of(exitNode), Map.of()))
            .allowed());
    assertFalse(manager.canEnter(occupantRequest(NOW)).allowed(), "前车占着出口节点");
    assertFalse(manager.canEnter(entrantRequest(NOW.plusSeconds(1))).allowed());
    assertEquals(OCCUPANT, queueHead(manager, SWITCHER_CONFLICT), "岔上车先入队");
    OccupancyQueueEntry queued = entryOf(manager, SWITCHER_CONFLICT, OCCUPANT).orElseThrow();

    assertTrue(
        manager
            .acquire(
                holdRequest(
                    NOW.plusSeconds(2), Map.of(SWITCHER_CONFLICT, ResourceIntent.HOLD_ONLY)))
            .allowed());

    OccupancyQueueEntry afterHold = entryOf(manager, SWITCHER_CONFLICT, OCCUPANT).orElseThrow();
    assertEquals(queued.firstSeen(), afterHold.firstSeen(), "停车保持不能把排队资历清零");
    assertEquals(queued.enqueueSequence(), afterHold.enqueueSequence());

    manager.releaseResource(exitNode, Optional.of("SURC-MT-LP-4909"));
    OccupancyDecision authority = manager.acquire(occupantRequest(NOW.plusSeconds(3)));

    assertTrue(authority.allowed(), authority.reason());
    assertTrue(entryOf(manager, SWITCHER_CONFLICT, OCCUPANT).isEmpty(), "拿到可执行授权才出队");
    assertEquals(ENTRANT, queueHead(manager, SWITCHER_CONFLICT));
  }

  private static SimpleOccupancyManager manager() {
    return new SimpleOccupancyManager(
        (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
  }

  /** 岔上车体：以 HOLD_ONLY 持有道岔节点、出口边与道岔冲突资源（停车保持的现场）。 */
  private static void holdPositionOnSwitcher(SimpleOccupancyManager manager) {
    assertTrue(
        manager
            .acquire(
                holdRequest(
                    NOW.minusSeconds(5),
                    Map.of(
                        SWITCHER_NODE,
                        ResourceIntent.HOLD_ONLY,
                        EXIT_EDGE,
                        ResourceIntent.HOLD_ONLY,
                        SWITCHER_CONFLICT,
                        ResourceIntent.HOLD_ONLY)))
            .allowed());
  }

  private static OccupancyRequest holdRequest(
      Instant now, Map<OccupancyResource, ResourceIntent> intents) {
    return new OccupancyRequest(
        OCCUPANT,
        Optional.empty(),
        now,
        List.copyOf(intents.keySet()),
        Map.of(),
        Map.of(),
        0,
        AuthorizationPurpose.RUNTIME_MOVE,
        Map.of(),
        intents);
  }

  /** 岔上车的前进请求：当前节点就是道岔，计划从道岔驶向出口。 */
  private static OccupancyRequest occupantRequest(Instant now) {
    List<NodeId> path = List.of(SWITCHER, EXIT);
    return traversal(
        OCCUPANT,
        now,
        path,
        List.of(
            new DirectedTraversalContext.DirectedEdge(
                EdgeId.undirected(SWITCHER, EXIT), SWITCHER, EXIT)),
        List.of(SWITCHER_CONFLICT, SWITCHER_NODE, EXIT_EDGE, OccupancyResource.forNode(EXIT)));
  }

  /** 岔外车的请求：从入口经道岔驶向出口。 */
  private static OccupancyRequest entrantRequest(Instant now) {
    List<NodeId> path = List.of(ENTRY, SWITCHER, EXIT);
    return traversal(
        ENTRANT,
        now,
        path,
        List.of(
            new DirectedTraversalContext.DirectedEdge(
                EdgeId.undirected(ENTRY, SWITCHER), ENTRY, SWITCHER),
            new DirectedTraversalContext.DirectedEdge(
                EdgeId.undirected(SWITCHER, EXIT), SWITCHER, EXIT)),
        List.of(SWITCHER_CONFLICT, ENTRY_EDGE, SWITCHER_NODE, EXIT_EDGE));
  }

  private static OccupancyRequest traversal(
      String train,
      Instant now,
      List<NodeId> path,
      List<DirectedTraversalContext.DirectedEdge> edges,
      List<OccupancyResource> resources) {
    return new OccupancyRequest(
            train,
            Optional.empty(),
            now,
            resources,
            Map.of(),
            Map.of(SWITCHER_CONFLICT.key(), 0),
            0,
            AuthorizationPurpose.RUNTIME_MOVE)
        .withDirectedContext(
            Optional.of(
                new DirectedTraversalContext(
                    train,
                    Optional.empty(),
                    8,
                    Optional.of(path.get(0)),
                    Optional.empty(),
                    Optional.of(path.get(0)),
                    Optional.of(path.get(1)),
                    path,
                    edges,
                    Map.of(),
                    Map.of(
                        SWITCHER_CONFLICT.key(),
                        new DirectedTraversalContext.SwitcherPathSignature(
                            SWITCHER_CONFLICT.key(), path)),
                    "TEST",
                    1L,
                    1L,
                    "spb-merge-" + train,
                    Optional.empty())));
  }

  private static OccupancyClaim claimOf(
      SimpleOccupancyManager manager, OccupancyResource resource, String train) {
    return manager.snapshotClaims().stream()
        .filter(claim -> claim.resource().equals(resource) && claim.trainName().equals(train))
        .findFirst()
        .orElseThrow(() -> new AssertionError(train + " 没有持有 " + resource));
  }

  private static Optional<OccupancyQueueEntry> entryOf(
      SimpleOccupancyManager manager, OccupancyResource resource, String train) {
    return manager.snapshotQueues().stream()
        .filter(snapshot -> snapshot.resource().equals(resource))
        .flatMap(snapshot -> snapshot.entries().stream())
        .filter(entry -> entry.trainName().equals(train))
        .findFirst();
  }

  private static String queueHead(SimpleOccupancyManager manager, OccupancyResource resource) {
    return manager.snapshotQueues().stream()
        .filter(snapshot -> snapshot.resource().equals(resource))
        .flatMap(snapshot -> snapshot.entries().stream())
        .findFirst()
        .map(OccupancyQueueEntry::trainName)
        .orElse("-");
  }
}
