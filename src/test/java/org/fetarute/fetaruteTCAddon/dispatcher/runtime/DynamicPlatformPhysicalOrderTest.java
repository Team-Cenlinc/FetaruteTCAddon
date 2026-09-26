package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.bukkit.util.Vector;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailEdge;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.SignRailNode;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.SimpleRailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinitionCache;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteId;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.ClaimRole;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyClaim;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyManager;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyResource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * DYNAMIC 选台的物理先后规则。
 *
 * <p>拓扑按回归骨架里出事的现场建：终端站 {@code A}、两个区间节点、会让站西咽喉 {@code W}、两股道 {@code B:1/B:2}、东咽喉。交路路径点只有 {@code A}
 * 与 DYNAMIC 的 {@code B}，所以 {@code A} 与 {@code W} 之间的所有列车<b>路径点相同</b>，
 * 只有物理位置分得出先后——这正是缺陷能藏住的原因，用例据此把两个量拆开。
 */
class DynamicPlatformPhysicalOrderTest {

  private static final NodeId A = NodeId.of("OP:S:A:1");
  private static final NodeId X1 = NodeId.of("OP:A:B:1:001");
  private static final NodeId X2 = NodeId.of("OP:A:B:1:002");
  private static final NodeId W = NodeId.of("SWITCHER:B:W");
  private static final NodeId B1 = NodeId.of("OP:S:B:1");
  private static final NodeId B2 = NodeId.of("OP:S:B:2");
  private static final NodeId E = NodeId.of("SWITCHER:B:E");
  private static final RouteId ROUTE_ID = RouteId.of("op/l1/shared");
  private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

  private final Map<String, NodeId> positions = new HashMap<>();
  private final List<OccupancyClaim> claims = new ArrayList<>();
  private final List<String> logs = new ArrayList<>();
  private DynamicCapacityWaitRegistry waits;
  private DynamicPlatformAllocator allocator;
  private RouteDefinition route;
  private RailGraph graph;

  @BeforeEach
  void setUp() {
    RouteDefinitionCache routeDefinitions = mock(RouteDefinitionCache.class);
    RouteStop dynamicStop = mock(RouteStop.class);
    when(dynamicStop.notes()).thenReturn(Optional.of("DYNAMIC:OP:S:B:[1:2]"));
    when(routeDefinitions.findStop(ROUTE_ID, 1)).thenReturn(Optional.of(dynamicStop));
    route = mock(RouteDefinition.class);
    when(route.id()).thenReturn(ROUTE_ID);
    when(route.waypoints()).thenReturn(List.of(A, B1));
    OccupancyManager occupancy = mock(OccupancyManager.class);
    when(occupancy.snapshotClaims()).thenReturn(claims);
    waits = new DynamicCapacityWaitRegistry();
    allocator =
        new DynamicPlatformAllocator(
            routeDefinitions,
            occupancy,
            logs::add,
            waits,
            name -> Optional.ofNullable(positions.get(name)));
    graph = passingLoop();
  }

  /** 骨架现场本身：1 道被占，前车停在西咽喉等台，身后的车不能订走唯一的 2 道；前车自己可以。 */
  @Test
  void trainBehindCannotTakeTheLastPlatformFromTheTrainWaitingAhead() {
    occupyB1();
    positions.put("front", W);
    positions.put("rear", X1);
    waits.register("front", ROUTE_ID, 0, 1, stationResources(), W);

    assertBlocked(resolve("rear"));
    assertEquals(B2, selected(resolve("front")));
  }

  /** 裁定留痕：必留 trace 只在裁定变化时输出，后车逐 tick 重评估不会刷屏。 */
  @Test
  void withheldDecisionIsReportedOncePerChange() {
    occupyB1();
    positions.put("front", W);
    positions.put("rear", X1);
    waits.register("front", ROUTE_ID, 0, 1, stationResources(), W);

    resolve("rear");
    resolve("rear");

    List<String> reported =
        logs.stream().filter(line -> line.startsWith("DYNAMIC_PLATFORM_ORDER_WITHHELD")).toList();
    assertEquals(1, reported.size(), () -> "应当恰好一条，实际 " + reported);
    assertTrue(
        reported.get(0).contains("train=rear")
            && reported.get(0).contains("candidate=" + B2.value())
            && reported.get(0).contains("waitingAhead=front")
            && reported.get(0).contains("from=" + X1.value()),
        reported.get(0));
  }

  /**
   * 后车先订到的预订，在前车开始等台后必须撤回——否则一旦后车先被评估（重启后所有车同时重新选台就是这样），预订永久有效。
   *
   * <p>这正是修复前骨架里的时序：后车 tick 0 订走 2 道，前车随后才登记等待。
   */
  @Test
  void reservationTakenFirstByTheTrainBehindIsRevokedOnceTheTrainAheadWaits() {
    occupyB1();
    positions.put("front", W);
    positions.put("rear", X1);
    assertEquals(B2, selected(resolve("rear")));
    assertBlocked(resolve("front"));

    waits.register("front", ROUTE_ID, 0, 1, stationResources(), W);

    assertBlocked(resolve("rear"));
    assertEquals(B2, selected(resolve("front")));
  }

  /**
   * 等待者在请求车<b>身后</b>时不挡。
   *
   * <p>请求车的交路路径点是 {@code A}，若从路径点起算进站路，身后 {@code X1} 上的等待者会落进路径中间，被误判成挡路者——两车就会互相拒绝。所以进站路必须从物理位置
   * {@code X2} 起算。
   */
  @Test
  void waiterBehindTheRequesterDoesNotWithholdAnything() {
    occupyB1();
    positions.put("requester", X2);
    positions.put("behind", X1);
    waits.register("behind", ROUTE_ID, 0, 1, stationResources(), X1);

    assertEquals(B2, selected(resolve("requester")));
  }

  /** 任一方物理位置未知时规则不生效——不拿路径点顶替。 */
  @Test
  void unknownPhysicalPositionOnEitherSideLeavesAllocationUnchanged() {
    occupyB1();
    waits.register("front", ROUTE_ID, 0, 1, stationResources(), W);
    assertEquals(B2, selected(resolve("requester-without-position")));

    allocator.clearAllocations("requester-without-position");
    waits.register("front", ROUTE_ID, 0, 1, stationResources(), null);
    positions.put("rear", X1);
    assertEquals(B2, selected(resolve("rear")));
  }

  /** 等的是别的站，挡在路上也不相干。 */
  @Test
  void waiterForAnotherStationIsIgnored() {
    occupyB1();
    positions.put("rear", X1);
    waits.register(
        "front", ROUTE_ID, 0, 1, List.of(OccupancyResource.forNode(NodeId.of("OP:S:Z:1"))), W);

    assertEquals(B2, selected(resolve("rear")));
  }

  private DynamicResolution<DynamicPlatformAllocator.AllocationResult> resolve(String train) {
    return allocator.resolveAllocation(train, route, 0, graph, A, Optional.empty());
  }

  private static NodeId selected(
      DynamicResolution<DynamicPlatformAllocator.AllocationResult> resolution) {
    assertTrue(resolution.isSelected(), () -> "应当选到站台，实际 " + resolution.reason());
    return resolution.selected().orElseThrow().allocatedNode();
  }

  private static void assertBlocked(
      DynamicResolution<DynamicPlatformAllocator.AllocationResult> resolution) {
    assertTrue(resolution.isBlocked(), () -> "应当被挡住，实际 " + resolution.reason());
    assertEquals("no-available-platform", resolution.reason());
  }

  private void occupyB1() {
    claims.add(
        new OccupancyClaim(
            OccupancyResource.forNode(B1),
            "dweller",
            Optional.empty(),
            NOW,
            Duration.ZERO,
            Optional.empty(),
            ClaimRole.MOVEMENT_REQUIRED));
  }

  private static List<OccupancyResource> stationResources() {
    return List.of(OccupancyResource.forNode(B1), OccupancyResource.forNode(B2));
  }

  private static RailGraph passingLoop() {
    Map<NodeId, RailNode> nodes = new LinkedHashMap<>();
    for (NodeId id : List.of(A, X1, X2, W, B1, B2, E)) {
      NodeType type =
          id.value().startsWith("SWITCHER:")
              ? NodeType.SWITCHER
              : id.value().split(":").length == 4 ? NodeType.STATION : NodeType.WAYPOINT;
      nodes.put(
          id, new SignRailNode(id, type, new Vector(0, 0, 0), Optional.empty(), Optional.empty()));
    }
    Map<EdgeId, RailEdge> edges = new LinkedHashMap<>();
    for (NodeId[] pair :
        List.of(
            new NodeId[] {A, X1},
            new NodeId[] {X1, X2},
            new NodeId[] {X2, W},
            new NodeId[] {W, B1},
            new NodeId[] {W, B2},
            new NodeId[] {B1, E},
            new NodeId[] {B2, E})) {
      EdgeId id = EdgeId.undirected(pair[0], pair[1]);
      edges.put(id, new RailEdge(id, id.a(), id.b(), 30, 0.0, true, Optional.empty()));
    }
    return new SimpleRailGraph(nodes, edges, Set.of());
  }
}
