package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.bukkit.util.Vector;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailEdge;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.SignRailNode;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.SimpleRailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.ClaimRole;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.DirectedTraversalContext;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.ExpandedPathPlan;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.MovementPlanSnapshot;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyClaim;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyResource;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspect;
import org.junit.jupiter.api.Test;

/**
 * 已授予、仍在车前方的授权：只保留令牌授予、本车仍以硬授权持有、且在行车计划上从当前节点到令牌终点这一段的资源。
 *
 * <p>夹具是一条环线 A —10— B —10— C(道岔) —10— D —10— E —10— A；列车在 A，令牌授予到 D。
 */
class HeldForwardAuthorityTest {

  private static final String TRAIN = "SURC-MT-LO-9304";
  private static final NodeId A = NodeId.of("A");
  private static final NodeId B = NodeId.of("B");
  private static final NodeId C = NodeId.of("SWITCHER:C");
  private static final NodeId D = NodeId.of("D");
  private static final NodeId E = NodeId.of("E");
  private static final OccupancyResource SWITCHER_C =
      OccupancyResource.forConflict("switcher:" + C.value());

  @Test
  void keepsTheGrantedHeldStretchUpToTheAuthorityEnd() {
    Set<OccupancyResource> retained =
        HeldForwardAuthority.resolve(
            token(D, true, granted()), plan(A, B, C, D, E), heldEverything(), loop());

    assertEquals(
        Set.of(node(A), edge(A, B), node(B), edge(B, C), node(C), SWITCHER_C, edge(C, D), node(D)),
        retained);
  }

  /** 只有本车以 MOVEMENT_REQUIRED 持有的才保留；保护性占用、别的车的占用都不算。 */
  @Test
  void onlyMovementRequiredClaimsOfThisTrainCount() {
    List<OccupancyClaim> claims = new ArrayList<>();
    claims.add(claim(node(C), TRAIN, ClaimRole.MOVEMENT_REQUIRED));
    claims.add(claim(edge(C, D), TRAIN, ClaimRole.PROTECTIVE_RETAIN));
    claims.add(claim(node(D), "SURC-DS-LH-5416", ClaimRole.MOVEMENT_REQUIRED));

    assertEquals(
        Set.of(node(C)),
        HeldForwardAuthority.resolve(
            token(D, true, granted()), plan(A, B, C, D, E), claims, loop()));
  }

  /** 环线计划绕回车后：以令牌终点为界，身后的占用不会被当成"前方"留住。 */
  @Test
  void aLoopingPlanNeverReachesBackBehindTheTrain() {
    Set<OccupancyResource> retained =
        HeldForwardAuthority.resolve(
            token(D, true, granted()), plan(C, D, E, A, B), heldEverything(), loop());

    assertEquals(Set.of(node(C), SWITCHER_C, edge(C, D), node(D)), retained);
  }

  /** 证据缺失都不保留：没有令牌、令牌未激活、令牌终点不在本拍计划上（改道）、没有计划。 */
  @Test
  void missingEvidenceRetainsNothing() {
    assertTrue(
        HeldForwardAuthority.resolve(null, plan(A, B, C, D), heldEverything(), loop()).isEmpty());
    assertTrue(
        HeldForwardAuthority.resolve(
                token(D, false, granted()), plan(A, B, C, D), heldEverything(), loop())
            .isEmpty());
    assertTrue(
        HeldForwardAuthority.resolve(
                token(D, true, granted()), plan(A, B, C), heldEverything(), loop())
            .isEmpty());
    assertTrue(
        HeldForwardAuthority.resolve(
                token(D, true, granted()), Optional.empty(), heldEverything(), loop())
            .isEmpty());
  }

  @Test
  void savedFromReleaseIsWhatTheKeepSetWouldHaveDropped() {
    Set<OccupancyResource> retained = Set.of(node(C), edge(C, D), node(D));

    assertEquals(
        List.of(edge(C, D), node(D)),
        HeldForwardAuthority.savedFromRelease(retained, List.of(node(C))));
    assertTrue(HeldForwardAuthority.savedFromRelease(retained, retained).isEmpty());
  }

  /** 诊断行按车去重：同样的内容只输出一次，内容变了或中间断过才再输出。 */
  @Test
  void traceLinesAreEmittedOnlyWhenTheyChange() {
    HeldForwardAuthority.TraceDedup dedup = new HeldForwardAuthority.TraceDedup();
    List<OccupancyResource> saved = List.of(node(D));

    assertTrue(dedup.line(TRAIN, "SIGNAL_TICK", false, saved).isPresent());
    assertTrue(dedup.line(TRAIN, "SIGNAL_TICK", false, saved).isEmpty());
    assertTrue(dedup.line(TRAIN, "SIGNAL_TICK", true, saved).isPresent());
    assertTrue(dedup.line(TRAIN, "SIGNAL_TICK", true, List.of()).isEmpty());
    assertTrue(dedup.line(TRAIN, "SIGNAL_TICK", true, saved).isPresent());
    assertTrue(
        dedup
            .line(TRAIN, "SIGNAL_TICK", false, saved)
            .orElseThrow()
            .startsWith("SMART_FORWARD_AUTHORITY_RETAINED train=" + TRAIN));
  }

  private static List<OccupancyResource> granted() {
    List<OccupancyResource> resources = new ArrayList<>();
    for (NodeId id : List.of(A, B, C, D, E)) {
      resources.add(node(id));
    }
    resources.add(edge(A, B));
    resources.add(edge(B, C));
    resources.add(edge(C, D));
    resources.add(edge(D, E));
    resources.add(edge(E, A));
    resources.add(SWITCHER_C);
    return resources;
  }

  private static List<OccupancyClaim> heldEverything() {
    List<OccupancyClaim> claims = new ArrayList<>();
    for (OccupancyResource resource : granted()) {
      claims.add(claim(resource, TRAIN, ClaimRole.MOVEMENT_REQUIRED));
    }
    return claims;
  }

  private static MovementAuthorizationToken token(
      NodeId end, boolean active, List<OccupancyResource> resources) {
    return new MovementAuthorizationToken(
        TRAIN,
        1L,
        Instant.now(),
        A,
        end,
        Optional.of(end),
        3,
        resources,
        SignalAspect.PROCEED,
        active,
        Optional.empty());
  }

  private static Optional<MovementPlanSnapshot> plan(NodeId... path) {
    List<NodeId> nodes = List.of(path);
    List<DirectedTraversalContext.DirectedEdge> edges = new ArrayList<>();
    for (int index = 0; index + 1 < nodes.size(); index++) {
      edges.add(
          new DirectedTraversalContext.DirectedEdge(
              EdgeId.undirected(nodes.get(index), nodes.get(index + 1)),
              nodes.get(index),
              nodes.get(index + 1)));
    }
    return Optional.of(
        new MovementPlanSnapshot(
            TRAIN,
            Optional.empty(),
            0,
            Optional.of(nodes.get(0)),
            Optional.empty(),
            Optional.of(nodes.get(0)),
            Optional.of(nodes.get(nodes.size() - 1)),
            new ExpandedPathPlan(nodes, edges, Map.of(), Map.of()),
            List.of(),
            0L,
            0L,
            "test"));
  }

  private static OccupancyClaim claim(OccupancyResource resource, String train, ClaimRole role) {
    return new OccupancyClaim(
        resource, train, Optional.empty(), Instant.now(), Duration.ZERO, Optional.empty(), role);
  }

  private static OccupancyResource node(NodeId id) {
    return OccupancyResource.forNode(id);
  }

  private static OccupancyResource edge(NodeId a, NodeId b) {
    return OccupancyResource.forEdge(EdgeId.undirected(a, b));
  }

  private static SimpleRailGraph loop() {
    Map<NodeId, RailNode> nodes = new LinkedHashMap<>();
    for (NodeId id : List.of(A, B, D, E)) {
      nodes.put(
          id,
          new SignRailNode(
              id, NodeType.WAYPOINT, new Vector(), Optional.empty(), Optional.empty()));
    }
    nodes.put(
        C,
        new SignRailNode(C, NodeType.SWITCHER, new Vector(), Optional.empty(), Optional.empty()));
    Map<EdgeId, RailEdge> edges = new LinkedHashMap<>();
    List<NodeId> ring = List.of(A, B, C, D, E, A);
    for (int index = 0; index + 1 < ring.size(); index++) {
      EdgeId id = EdgeId.undirected(ring.get(index), ring.get(index + 1));
      edges.put(
          id,
          new RailEdge(id, ring.get(index), ring.get(index + 1), 10, 8.0, true, Optional.empty()));
    }
    return new SimpleRailGraph(nodes, edges, Set.of());
  }
}
