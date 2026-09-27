package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bergerkiller.bukkit.tc.controller.components.RailState;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.util.Vector;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailEdge;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.SignRailNode;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.SimpleRailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchTestFixtures.FakeTrain;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.ClaimRole;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyClaim;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyRequest;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyRequestBuilder;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyResource;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspect;
import org.junit.jupiter.api.Test;

/**
 * 延伸授权被拒时能否沿已持有授权刹停的判据。
 *
 * <p>夹具：A —100— B —50— C —50— D，节点沿 x 轴、坐标与边长一致。上一拍授予的窗口到 C（token 终点），本拍向 D 延伸被拒；车头在 A→B 上 x=30。
 * 停车余量 8：停车点离车头 150 − 30 − 8 = 112 格。
 */
class HeldAuthorityBrakingTest {

  private static final String TRAIN = "SURC-MT-LO-6900";
  private static final NodeId A = NodeId.of("SURC:OFL:MLU:2:001");
  private static final NodeId B = NodeId.of("SURC:OFL:MLU:2:002");
  private static final NodeId C = NodeId.of("SURC:OFL:MLU:2:003");
  private static final NodeId D = NodeId.of("SURC:S:OFL:2");
  private static final double STOP_MARGIN = 8.0;

  private final SimpleRailGraph graph = chain();
  private final OccupancyRequest refused =
      new OccupancyRequestBuilder(graph, 3, 0, 0, 0)
          .buildContextFromNodes(TRAIN, Optional.empty(), List.of(A, D), 0, Instant.now(), 0)
          .orElseThrow()
          .request();
  private final List<OccupancyResource> heldWindow =
      List.of(edge(A, B), node(B), edge(B, C), node(C));

  @Test
  void movingTrainBrakesToTheHeldAuthorityEndMeasuredFromTheHead() {
    HeldAuthorityBraking.Decision decision =
        resolve(activeToken(C, heldWindow), null, movementClaims(heldWindow), head(30.0));

    HeldAuthorityBraking.Plan plan = decision.plan().orElseThrow(() -> fail(decision));
    assertEquals(112L, plan.stopDistanceBlocks());
    assertEquals(C, plan.endNode());
    assertEquals(Set.copyOf(heldWindow), plan.retainedResources());
    assertEquals(":braking=held-authority", decision.detailSuffix());
  }

  /** 越过 route 节点要由推进点改写 destination，刹车途中不做——停在它前面。 */
  @Test
  void brakingStopsShortOfTheNextRouteNode() {
    HeldAuthorityBraking.Decision decision =
        resolve(activeToken(C, heldWindow), B, movementClaims(heldWindow), head(30.0));

    HeldAuthorityBraking.Plan plan = decision.plan().orElseThrow(() -> fail(decision));
    assertEquals(62L, plan.stopDistanceBlocks());
    assertEquals(B, plan.endNode());
  }

  /** 静止列车没有要刹的速度，停车保持照旧；明细不加后缀，停着的停因与旧版一致。 */
  @Test
  void stationaryTrainKeepsTheOldHold() {
    FakeTrain stationary = new FakeTrain(UUID.randomUUID(), null, false, 0.0);
    stationary.railState = head(30.0).railState;

    HeldAuthorityBraking.Decision decision =
        resolve(activeToken(C, heldWindow), null, movementClaims(heldWindow), stationary);

    assertInstant(decision, "stationary");
    assertEquals("", decision.detailSuffix());
  }

  @Test
  void inactiveTokenIsNotAHeldAuthority() {
    MovementAuthorizationToken pending =
        new MovementAuthorizationToken(
            TRAIN, 1L, Instant.now(), A, D, Optional.of(C), 2, heldWindow, SignalAspect.PROCEED);

    HeldAuthorityBraking.Decision decision =
        resolve(pending, null, movementClaims(heldWindow), head(30.0));

    assertInstant(decision, "no-active-authority");
    assertEquals(":braking=instant:no-active-authority", decision.detailSuffix());
  }

  /** 账本里前方有一段不再以 MOVEMENT_REQUIRED 持有：不能证明那段路仍归本车。 */
  @Test
  void authorityPartlyReleasedFromTheLedgerFallsBackToInstantStop() {
    List<OccupancyClaim> claims = new ArrayList<>(movementClaims(heldWindow));
    claims.removeIf(claim -> claim.resource().equals(edge(B, C)));
    claims.add(claim(edge(B, C), ClaimRole.PROTECTIVE_RETAIN));

    assertInstant(
        resolve(activeToken(C, heldWindow), null, claims, head(30.0)), "authority-released");
  }

  @Test
  void pathToTheEndMustLieInsideTheToken() {
    List<OccupancyResource> withoutB = List.of(edge(A, B), edge(B, C), node(C));

    assertInstant(
        resolve(activeToken(C, withoutB), null, movementClaims(withoutB), head(30.0)),
        "path-outside-authority");
  }

  @Test
  void authorityEndNotAheadOnThePlanFallsBack() {
    assertInstant(
        resolve(activeToken(A, heldWindow), null, movementClaims(heldWindow), head(30.0)),
        "authority-end-not-ahead");
  }

  @Test
  void unknownHeadPositionFallsBack() {
    FakeTrain blind = head(30.0);
    blind.railState = Optional.empty();

    assertInstant(
        resolve(activeToken(C, heldWindow), null, movementClaims(heldWindow), blind),
        "head-position-unknown");
  }

  /** 插值被钳到首条边末端：车头越过 B 多少无从判断。 */
  @Test
  void headClampedAtTheEdgeEndFallsBack() {
    assertInstant(
        resolve(activeToken(C, heldWindow), null, movementClaims(heldWindow), head(120.0)),
        "head-position-unknown");
  }

  @Test
  void headAlreadyWithinTheStopMarginFallsBack() {
    assertInstant(
        resolve(activeToken(C, heldWindow), B, movementClaims(heldWindow), head(95.0)),
        "authority-end-reached");
  }

  private HeldAuthorityBraking.Decision resolve(
      MovementAuthorizationToken token,
      NodeId nextRouteNode,
      List<OccupancyClaim> claims,
      FakeTrain train) {
    return HeldAuthorityBraking.resolve(
        token, refused, A, nextRouteNode, claims, graph, train, STOP_MARGIN);
  }

  private static void assertInstant(HeldAuthorityBraking.Decision decision, String reason) {
    assertTrue(decision.plan().isEmpty(), "应当退回当拍停车，实际给出了刹车计划");
    assertEquals(reason, decision.reason());
    assertTrue(decision.retainedResources().isEmpty());
    assertTrue(decision.stopDistanceBlocks().isEmpty());
  }

  private static AssertionError fail(HeldAuthorityBraking.Decision decision) {
    return new AssertionError("应当沿已持有授权刹车，实际退回当拍停车：" + decision.reason());
  }

  private static MovementAuthorizationToken activeToken(
      NodeId end, List<OccupancyResource> resources) {
    return new MovementAuthorizationToken(
            TRAIN, 1L, Instant.now(), A, D, Optional.of(end), 2, resources, SignalAspect.PROCEED)
        .activate("SURC:S:OFL:2");
  }

  private static List<OccupancyClaim> movementClaims(List<OccupancyResource> resources) {
    return resources.stream()
        .map(resource -> claim(resource, ClaimRole.MOVEMENT_REQUIRED))
        .toList();
  }

  private static OccupancyClaim claim(OccupancyResource resource, ClaimRole role) {
    return new OccupancyClaim(
        resource, TRAIN, Optional.empty(), Instant.now(), Duration.ZERO, Optional.empty(), role);
  }

  /** 以 10 bps 行进、车头在 x 处的列车。 */
  private static FakeTrain head(double x) {
    FakeTrain train = new FakeTrain(UUID.randomUUID(), null, true, 0.5);
    RailState railState = mock(RailState.class);
    when(railState.positionLocation()).thenReturn(new Location(null, x, 64.0, 0.0));
    train.railState = Optional.of(railState);
    return train;
  }

  private static OccupancyResource edge(NodeId from, NodeId to) {
    return OccupancyResource.forEdge(EdgeId.undirected(from, to));
  }

  private static OccupancyResource node(NodeId id) {
    return OccupancyResource.forNode(id);
  }

  private static SimpleRailGraph chain() {
    Map<NodeId, RailNode> nodes = new LinkedHashMap<>();
    nodes.put(A, railNode(A, 0.0));
    nodes.put(B, railNode(B, 100.0));
    nodes.put(C, railNode(C, 150.0));
    nodes.put(D, railNode(D, 200.0));
    Map<EdgeId, RailEdge> edges = new LinkedHashMap<>();
    putEdge(edges, A, B, 100);
    putEdge(edges, B, C, 50);
    putEdge(edges, C, D, 50);
    return new SimpleRailGraph(nodes, edges, Set.of());
  }

  private static RailNode railNode(NodeId id, double x) {
    return new SignRailNode(
        id, NodeType.WAYPOINT, new Vector(x, 64.0, 0.0), Optional.empty(), Optional.empty());
  }

  private static void putEdge(Map<EdgeId, RailEdge> edges, NodeId from, NodeId to, int length) {
    EdgeId id = EdgeId.undirected(from, to);
    edges.put(id, new RailEdge(id, from, to, length, -1.0, true, Optional.empty()));
  }
}
