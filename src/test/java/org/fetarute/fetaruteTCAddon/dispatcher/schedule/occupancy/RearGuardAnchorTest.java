package org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.SignRailNode;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.SimpleRailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteId;
import org.junit.jupiter.api.Test;

/**
 * 身后保护钉在实际走过的股道上。
 *
 * <p>现场：{@code A — W —(B:1 / B:2)— E — N}，列车从 B:2 出站、车头已到 E。运行时把当前路径点（B:2）改写成车头节点 E，传进构建器的序列是
 * {@code [A, E, N]}、索引 1。不钉时后向路径只能按 A→E 的最短路重建：等长时平局落在 B:1，
 * 不等长时短的那条永远赢——两种情况都会把保护留在列车从没走过的股道上，而实际股道上的 claim 在缩减时被释放。
 */
class RearGuardAnchorTest {

  private static final NodeId A = NodeId.of("OP:S:A:1");
  private static final NodeId W = NodeId.of("SWITCHER:B:W");
  private static final NodeId B1 = NodeId.of("OP:S:B:1");
  private static final NodeId B2 = NodeId.of("OP:S:B:2");
  private static final NodeId E = NodeId.of("SWITCHER:B:E");
  private static final NodeId N = NodeId.of("OP:S:C:1");
  private static final Optional<RouteId> ROUTE = Optional.of(RouteId.of("OP:L1:R1"));

  /** 路径点 B:2 已被改写成车头所在的 E。 */
  private static final List<NodeId> OVERRIDDEN = List.of(A, E, N);

  private static final OccupancyRequestBuilder.RearGuardAnchor ARRIVED_AT_B2 =
      (train, index) -> index == 1 ? Optional.of(B2) : Optional.empty();

  /** 改动前的行为，作为对照钉住：等长平局落在 1 道。 */
  @Test
  void withoutAnchorTheTieFallsOnTheTrackNeverUsed() {
    OccupancyRequest request = rearGuard(builder(loop(10, 10)), OVERRIDDEN);

    assertCovers(request, B1);
    assertDoesNotTouch(request, B2);
  }

  @Test
  void anchorPinsTheRearGuardThroughTheTrackActuallyUsed() {
    OccupancyRequest request =
        rearGuard(builder(loop(10, 10)).withRearGuardAnchor(ARRIVED_AT_B2), OVERRIDDEN);

    assertCovers(request, B2);
    assertDoesNotTouch(request, B1);
    assertEquals(
        ResourceIntent.PROTECTIVE_RETAIN,
        request.intentFor(OccupancyResource.forEdge(EdgeId.undirected(B2, E))));
  }

  /** 1 道更短时，不钉则短的那条永远赢——与平局规则无关。钉了就走实际股道。 */
  @Test
  void shorterTrackNoLongerWinsOnceAnchored() {
    RailGraph shorterTrackOne = loop(5, 10);

    assertCovers(rearGuard(builder(shorterTrackOne), OVERRIDDEN), B1);
    OccupancyRequest pinned =
        rearGuard(builder(shorterTrackOne).withRearGuardAnchor(ARRIVED_AT_B2), OVERRIDDEN);
    assertCovers(pinned, B2);
    assertDoesNotTouch(pinned, B1);
  }

  /** 路径点没有被改写（车头就在到达站台上）时，钉点是 no-op。 */
  @Test
  void anchorEqualToTheHeadChangesNothing() {
    List<NodeId> notOverridden = List.of(A, B2, N);

    assertEquals(
        rearGuard(builder(loop(10, 10)), notOverridden).resourceList(),
        rearGuard(builder(loop(10, 10)).withRearGuardAnchor(ARRIVED_AT_B2), notOverridden)
            .resourceList());
  }

  /** 锚点对不上这个索引（没有到达证据）时按"未知"处理，退回原行为。 */
  @Test
  void anchorForAnotherIndexIsIgnored() {
    OccupancyRequestBuilder.RearGuardAnchor otherIndexOnly =
        (train, index) -> index == 7 ? Optional.of(B2) : Optional.empty();

    assertEquals(
        rearGuard(builder(loop(10, 10)), OVERRIDDEN).resourceList(),
        rearGuard(builder(loop(10, 10)).withRearGuardAnchor(otherIndexOnly), OVERRIDDEN)
            .resourceList());
  }

  /** 经锚点展开会走回头路（锚点在车头之前方）时不钉，退回原序列并留痕。 */
  @Test
  void pinnedPathThatWouldDoubleBackFallsBackToTheOriginalRearGuard() {
    List<String> logs = new ArrayList<>();
    OccupancyRequestBuilder.RearGuardAnchor aheadOfHead =
        (train, index) -> index == 1 ? Optional.of(N) : Optional.empty();

    OccupancyRequest request =
        rearGuard(builder(loop(10, 10), logs::add).withRearGuardAnchor(aheadOfHead), OVERRIDDEN);

    assertEquals(
        rearGuard(builder(loop(10, 10)), OVERRIDDEN).resourceList(), request.resourceList());
    assertTrue(logs.stream().anyMatch(line -> line.startsWith("rear-guard 钉点回退")), logs.toString());
  }

  /** 两个 wither 任意顺序组合都不丢设置。 */
  @Test
  void withersKeepEachOthersSettings() {
    OccupancyRequest anchoredThenExit =
        rearGuard(
            builder(loop(10, 10))
                .withRearGuardAnchor(ARRIVED_AT_B2)
                .withMinimumConflictExitDistanceBlocks(7L),
            OVERRIDDEN);
    OccupancyRequest exitThenAnchored =
        rearGuard(
            builder(loop(10, 10))
                .withMinimumConflictExitDistanceBlocks(7L)
                .withRearGuardAnchor(ARRIVED_AT_B2),
            OVERRIDDEN);

    assertCovers(anchoredThenExit, B2);
    assertDoesNotTouch(anchoredThenExit, B1);
    assertCovers(exitThenAnchored, B2);
    assertDoesNotTouch(exitThenAnchored, B1);
  }

  /** 行进请求里的身后保护走同一个解析，同样被钉住。 */
  @Test
  void movementRequestRearGuardIsPinnedToo() {
    OccupancyRequest movement =
        builder(loop(10, 10))
            .withRearGuardAnchor(ARRIVED_AT_B2)
            .buildFromNodes("T", ROUTE, OVERRIDDEN, 1, Instant.EPOCH, 0)
            .orElseThrow();

    assertTrue(
        movement.resourceList().contains(OccupancyResource.forNode(B2)), movement.toString());
    assertDoesNotTouch(movement, B1);
  }

  private static OccupancyRequest rearGuard(OccupancyRequestBuilder builder, List<NodeId> nodes) {
    return builder.buildRearGuardRequestFromNodes("T", ROUTE, nodes, 1, Instant.EPOCH, 0);
  }

  private static OccupancyRequestBuilder builder(RailGraph graph) {
    return builder(graph, message -> {});
  }

  /** 身后保护 1 条边 + 车长 5：车头在 E 时覆盖 E←股道←W 两条边。 */
  private static OccupancyRequestBuilder builder(
      RailGraph graph, java.util.function.Consumer<String> logger) {
    return new OccupancyRequestBuilder(graph, 1, 0, 1, 0, 0L, 1, 5L, logger);
  }

  private static void assertCovers(OccupancyRequest request, NodeId track) {
    List<OccupancyResource> resources = request.resourceList();
    assertTrue(resources.contains(OccupancyResource.forNode(track)), resources.toString());
    assertTrue(
        resources.contains(OccupancyResource.forEdge(EdgeId.undirected(track, E))),
        resources.toString());
    assertTrue(
        resources.contains(OccupancyResource.forEdge(EdgeId.undirected(W, track))),
        resources.toString());
  }

  private static void assertDoesNotTouch(OccupancyRequest request, NodeId track) {
    List<OccupancyResource> resources = request.resourceList();
    assertFalse(resources.contains(OccupancyResource.forNode(track)), resources.toString());
    assertFalse(
        resources.contains(OccupancyResource.forEdge(EdgeId.undirected(track, E))),
        resources.toString());
    assertFalse(
        resources.contains(OccupancyResource.forEdge(EdgeId.undirected(W, track))),
        resources.toString());
  }

  /** {@code trackOneLength} 同时用于 1 道两侧的边，2 道两侧的边为 {@code trackTwoLength}。 */
  private static RailGraph loop(int trackOneLength, int trackTwoLength) {
    Map<NodeId, RailNode> nodes = new LinkedHashMap<>();
    int x = 0;
    for (NodeId id : List.of(A, W, B1, B2, E, N)) {
      NodeType type = id.value().startsWith("SWITCHER:") ? NodeType.SWITCHER : NodeType.STATION;
      nodes.put(
          id,
          new SignRailNode(
              id, type, new Vector(x += 10, 64, 0), Optional.empty(), Optional.empty()));
    }
    Map<EdgeId, RailEdge> edges = new LinkedHashMap<>();
    addEdge(edges, A, W, 10);
    addEdge(edges, W, B1, trackOneLength);
    addEdge(edges, B1, E, trackOneLength);
    addEdge(edges, W, B2, trackTwoLength);
    addEdge(edges, B2, E, trackTwoLength);
    addEdge(edges, E, N, 10);
    return new SimpleRailGraph(nodes, edges, Set.of());
  }

  private static void addEdge(Map<EdgeId, RailEdge> edges, NodeId a, NodeId b, int length) {
    EdgeId id = EdgeId.undirected(a, b);
    edges.put(id, new RailEdge(id, id.a(), id.b(), length, 8.0, true, Optional.empty()));
  }
}
