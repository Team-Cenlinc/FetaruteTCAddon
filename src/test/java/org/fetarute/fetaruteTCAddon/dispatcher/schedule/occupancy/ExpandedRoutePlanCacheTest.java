package org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.SplittableRandom;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.SimpleRailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.control.EdgeOverrideRailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.persist.RailEdgeOverrideRecord;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("展开计划共用缓存")
class ExpandedRoutePlanCacheTest {

  private static final Instant NOW = Instant.parse("2026-10-20T10:00:00Z");
  private static final RouteId ROUTE = RouteId.of("SYN:R");
  private static final UUID WORLD = UUID.fromString("00000000-0000-0000-0000-0000000000e1");

  @Test
  @DisplayName("共用计划与现算逐项相同：各交路、各位置、停在途经点与在区间里，第二次取用命中缓存")
  void sharedPlanMatchesFreshComputation() {
    SyntheticMetroLine line = SyntheticMetroLine.build(8, 4, 11L);
    for (List<NodeId> route :
        List.of(line.upRoute, line.downRoute, line.turnbackRoute, line.depotRoute)) {
      for (int ci = 0; ci + 1 < route.size(); ci++) {
        for (List<NodeId> movement : movementVariants(line, route, ci)) {
          OccupancyRequestBuilder builder = lookaheadBuilder(line.graph, 60L);
          Built first = build(builder, movement, route, ci);
          Built second = build(builder, movement, route, ci);
          Built fresh = build(builder.withoutPlanCache(), movement, route, ci);
          assertSameBuild(first, fresh);
          assertSameBuild(second, fresh);
          ExpandedRoutePlan cached =
              ExpandedRoutePlans.cachedPlan(line.graph, suffix(movement, ci)).orElseThrow();
          cached.assertSameAs(builder.computePlan(suffix(movement, ci)));
        }
      }
    }
  }

  @Test
  @DisplayName("折返切分日志随列车与用途重新输出：命中缓存时与现算逐条相同")
  void turnbackSplitLogsReplayedPerRequest() {
    SyntheticMetroLine line = SyntheticMetroLine.build(6, 3, 5L);
    List<NodeId> route = line.turnbackRoute;
    int ci = line.stations - 2;
    OccupancyRequestBuilder builder = lookaheadBuilder(line.graph, 60L);
    Built warm = build(builder, route, route, ci);
    assertTrue(
        warm.log().stream().anyMatch(line1 -> line1.startsWith("SMART_ROUTE_TURNBACK_BOUNDARY")),
        warm.log().toString());

    List<String> otherTrainLog = new ArrayList<>();
    builder
        .withDebugLogger(otherTrainLog::add)
        .buildContextFromNodesWithDirectionContext(
            "OTHER",
            Optional.of(ROUTE),
            route,
            route,
            ci,
            NOW,
            0,
            AuthorizationPurpose.UNLOCK_RESERVATION);
    List<String> freshLog = new ArrayList<>();
    builder
        .withoutPlanCache()
        .withDebugLogger(freshLog::add)
        .buildContextFromNodesWithDirectionContext(
            "OTHER",
            Optional.of(ROUTE),
            route,
            route,
            ci,
            NOW,
            0,
            AuthorizationPurpose.UNLOCK_RESERVATION);
    assertEquals(freshLog, otherTrainLog);
    assertTrue(otherTrainLog.stream().anyMatch(entry -> entry.contains("train=OTHER")));
    assertTrue(otherTrainLog.stream().anyMatch(entry -> entry.contains("UNLOCK_RESERVATION")));
  }

  @Test
  @DisplayName("键覆盖实际节点串：DYNAMIC 选台改写与当前节点改写各自换一份计划")
  void rewrittenNodesUseTheirOwnPlans() {
    SyntheticMetroLine line = SyntheticMetroLine.build(6, 3, 3L);
    List<NodeId> route = line.upRoute;
    int ci = 1;
    OccupancyRequestBuilder builder = lookaheadBuilder(line.graph, 60L);
    build(builder, route, route, ci);
    assertTrue(ExpandedRoutePlans.remembers(line.graph, suffix(route, ci)));

    List<NodeId> dynamic = new ArrayList<>(route);
    dynamic.set(ci + 2, SyntheticMetroLine.p2(ci + 2));
    assertFalse(ExpandedRoutePlans.remembers(line.graph, suffix(dynamic, ci)));
    Built rewritten = build(builder, dynamic, dynamic, ci);
    assertTrue(ExpandedRoutePlans.remembers(line.graph, suffix(dynamic, ci)));
    assertSameBuild(rewritten, build(builder.withoutPlanCache(), dynamic, dynamic, ci));
    assertNotEquals(
        ExpandedRoutePlans.cachedPlan(line.graph, suffix(route, ci)).orElseThrow().splitPath(),
        ExpandedRoutePlans.cachedPlan(line.graph, suffix(dynamic, ci)).orElseThrow().splitPath());

    List<NodeId> moving = new ArrayList<>(route);
    moving.set(ci, line.upInterval(ci, 2));
    assertFalse(ExpandedRoutePlans.remembers(line.graph, suffix(moving, ci)));
    Built movingBuild = build(builder, moving, route, ci);
    assertTrue(ExpandedRoutePlans.remembers(line.graph, suffix(moving, ci)));
    assertSameBuild(movingBuild, build(builder.withoutPlanCache(), moving, route, ci));
    assertEquals(
        line.upInterval(ci, 2),
        movingBuild.context().orElseThrow().pathNodes().get(0),
        "实时窗口从改写后的当前节点起算");
  }

  @Test
  @DisplayName("覆盖封锁换键：封锁不同各算一份，封锁相同的新视图命中")
  void blockedEdgesChangeTheKey() {
    SyntheticMetroLine line = SyntheticMetroLine.build(6, 3, 9L);
    List<NodeId> route = line.upRoute;
    int ci = 0;
    EdgeId blocked = EdgeId.undirected(line.upInterval(2, 1), line.upInterval(2, 2));
    RailGraph open = new EdgeOverrideRailGraph(line.graph, Map.of(), NOW);
    RailGraph closed =
        new EdgeOverrideRailGraph(line.graph, Map.of(blocked, blockRecord(blocked)), NOW);

    Built openBuild = build(lookaheadBuilder(open, 60L), route, route, ci);
    assertTrue(ExpandedRoutePlans.remembers(open, suffix(route, ci)));
    assertTrue(ExpandedRoutePlans.remembers(line.graph, suffix(route, ci)), "无封锁的视图与底层图同一份");
    assertFalse(ExpandedRoutePlans.remembers(closed, suffix(route, ci)));

    Built closedBuild = build(lookaheadBuilder(closed, 60L), route, route, ci);
    assertSameBuild(
        closedBuild, build(lookaheadBuilder(closed, 60L).withoutPlanCache(), route, route, ci));
    assertNotEquals(
        openBuild.context().orElseThrow().directedContext().orElseThrow().expandedPathNodes(),
        closedBuild.context().orElseThrow().directedContext().orElseThrow().expandedPathNodes(),
        "封锁后绕行，展开路径不同");

    RailGraph closedLater =
        new EdgeOverrideRailGraph(
            line.graph, Map.of(blocked, blockRecord(blocked)), NOW.plusSeconds(30));
    assertTrue(ExpandedRoutePlans.remembers(closedLater, suffix(route, ci)), "稍后新建、封锁相同的视图命中");
  }

  @Test
  @DisplayName("换图换键：内容相同的新快照不共用旧快照的计划")
  void rebuiltGraphStartsEmpty() {
    SyntheticMetroLine first = SyntheticMetroLine.build(5, 2, 1L);
    SyntheticMetroLine second = SyntheticMetroLine.build(5, 2, 1L);
    List<NodeId> route = first.upRoute;
    Built before = build(lookaheadBuilder(first.graph, 60L), route, route, 0);
    assertTrue(ExpandedRoutePlans.remembers(first.graph, suffix(route, 0)));
    assertFalse(ExpandedRoutePlans.remembers(second.graph, suffix(route, 0)));

    Built after = build(lookaheadBuilder(second.graph, 60L), route, route, 0);
    assertTrue(ExpandedRoutePlans.remembers(second.graph, suffix(route, 0)));
    assertSameBuild(before, after);
  }

  @Test
  @DisplayName("后台预算之后命中：计划与整段方向判定已在缓存里，申请直接取用")
  void prewarmedPlanIsHit() {
    SyntheticMetroLine line = SyntheticMetroLine.build(7, 3, 4L);
    List<NodeId> route = line.downRoute;
    RailGraph view = new EdgeOverrideRailGraph(line.graph, Map.of(), NOW);
    for (int ci = 0; ci + 1 < route.size(); ci++) {
      assertTrue(ExpandedRoutePlans.prewarm(view, suffix(route, ci)));
      assertFalse(ExpandedRoutePlans.prewarm(view, suffix(route, ci)), "已有则不再算");
    }
    for (int ci = 0; ci + 1 < route.size(); ci++) {
      ExpandedRoutePlan warmed = ExpandedRoutePlans.cachedPlan(line.graph, suffix(route, ci)).get();
      assertTrue(warmed.hasDirections());
      OccupancyRequestBuilder builder = lookaheadBuilder(line.graph, 60L);
      Built shared = build(builder, route, route, ci);
      assertSame(
          warmed, ExpandedRoutePlans.cachedPlan(line.graph, suffix(route, ci)).orElseThrow());
      assertSameBuild(shared, build(builder.withoutPlanCache(), route, route, ci));
    }
  }

  @Test
  @DisplayName("授权窗口截断与现算逐项相同：随机速度、减速度、车长、车头位置与 DYNAMIC 边界")
  void windowsMatchFreshUnderRandomRuntimeInputs() {
    SyntheticMetroLine line = SyntheticMetroLine.build(10, 5, 21L);
    List<List<NodeId>> routes =
        List.of(line.upRoute, line.downRoute, line.turnbackRoute, line.depotRoute);
    SplittableRandom random = new SplittableRandom(20261020L);
    for (int round = 0; round < 240; round++) {
      List<NodeId> route = routes.get(random.nextInt(routes.size()));
      int end = 2 + random.nextInt(route.size() - 1);
      List<NodeId> bounded = route.subList(0, end);
      int ci = random.nextInt(bounded.size() - 1);
      List<List<NodeId>> variants = movementVariants(line, bounded, ci);
      List<NodeId> movement = variants.get(random.nextInt(variants.size()));
      double speed = random.nextInt(4) == 0 ? 0.0 : random.nextDouble() * 12.0;
      double decel = 0.4 + random.nextDouble() * 1.6;
      long trainLength = 16L + random.nextInt(180);
      long headProgress = random.nextInt(60);
      long margin = 12L;
      long minDistance =
          speed <= 1.0e-6
              ? margin + headProgress
              : (long) Math.ceil(speed * speed / (2.0 * decel) + margin) + headProgress;
      RailGraph view = new EdgeOverrideRailGraph(line.graph, Map.of(), NOW);
      OccupancyRequestBuilder hard =
          new OccupancyRequestBuilder(view, 1, 0, 0, 3, minDistance, 8, message -> {})
              .withMinimumConflictExitDistanceBlocks(trainLength + margin)
              .withTerminalDepotBerthBlocks(trainLength);
      OccupancyRequestBuilder lookahead = lookaheadBuilder(view, trainLength);
      for (OccupancyRequestBuilder builder : List.of(hard, lookahead)) {
        Built shared = build(builder, movement, bounded, ci);
        Built again = build(builder, movement, bounded, ci);
        Built fresh = build(builder.withoutPlanCache(), movement, bounded, ci);
        assertSameBuild(shared, fresh);
        assertSameBuild(again, fresh);
      }
    }
  }

  @Test
  @DisplayName("逐次比对能抓住不一致：缓存里被换成别的计划时抛 AssertionError")
  void verificationCatchesCorruptedPlan() {
    SyntheticMetroLine line = SyntheticMetroLine.build(5, 2, 8L);
    List<NodeId> route = line.upRoute;
    OccupancyRequestBuilder builder = lookaheadBuilder(line.graph, 60L);
    List<NodeId> wrongNodes = suffix(line.downRoute, 0);
    ExpandedRoutePlan wrong = builder.computePlan(wrongNodes);
    ExpandedRoutePlans.put(line.graph, suffix(route, 0), wrong);
    boolean previous = ExpandedRoutePlans.verifying();
    ExpandedRoutePlans.verifyEveryUse(true);
    try {
      assertThrows(AssertionError.class, () -> build(builder, route, route, 0));
    } finally {
      ExpandedRoutePlans.verifyEveryUse(previous);
    }
  }

  @Test
  @DisplayName("不可共用的图实现每次现算，从不进缓存")
  void unsupportedGraphIsNeverCached() {
    SyntheticMetroLine line = SyntheticMetroLine.build(5, 2, 2L);
    RailGraph wrapper = new DelegatingGraph(line.graph);
    List<NodeId> route = line.upRoute;
    OccupancyRequestBuilder builder = lookaheadBuilder(wrapper, 60L);
    Built shared = build(builder, route, route, 0);
    assertFalse(ExpandedRoutePlans.remembers(wrapper, suffix(route, 0)));
    assertTrue(ExpandedRoutePlans.cachedPlan(wrapper, suffix(route, 0)).isEmpty());
    assertFalse(ExpandedRoutePlans.prewarm(wrapper, suffix(route, 0)));
    assertSameBuild(shared, build(builder.withoutPlanCache(), route, route, 0));
  }

  private static List<List<NodeId>> movementVariants(
      SyntheticMetroLine line, List<NodeId> route, int ci) {
    List<List<NodeId>> variants = new ArrayList<>();
    variants.add(route);
    List<NodeId> leg =
        new OccupancyRequestBuilder(line.graph, 1, 0, 0, 0)
            .computePlan(List.of(route.get(ci), route.get(ci + 1)))
            .expanded();
    for (int index = 1; index + 1 < leg.size(); index += Math.max(1, (leg.size() - 2) / 2)) {
      List<NodeId> moved = new ArrayList<>(route);
      moved.set(ci, leg.get(index));
      variants.add(List.copyOf(moved));
    }
    return variants;
  }

  private static OccupancyRequestBuilder lookaheadBuilder(RailGraph graph, long rearDistance) {
    return new OccupancyRequestBuilder(graph, 3, 2, 1, 3, 12L, 12, rearDistance, message -> {})
        .withRearGuardAnchor((train, index) -> Optional.empty());
  }

  private static List<NodeId> suffix(List<NodeId> nodes, int from) {
    return new ArrayList<>(nodes.subList(from, nodes.size()));
  }

  private static RailEdgeOverrideRecord blockRecord(EdgeId edge) {
    return new RailEdgeOverrideRecord(
        WORLD,
        edge,
        OptionalDouble.empty(),
        OptionalDouble.empty(),
        Optional.empty(),
        true,
        Optional.empty(),
        NOW);
  }

  private record Built(Optional<OccupancyRequestContext> context, List<String> log) {}

  private static Built build(
      OccupancyRequestBuilder builder, List<NodeId> nodes, List<NodeId> direction, int ci) {
    List<String> log = new ArrayList<>();
    Optional<OccupancyRequestContext> context =
        builder
            .withDebugLogger(log::add)
            .buildContextFromNodesWithDirectionContext(
                "T1",
                Optional.of(ROUTE),
                nodes,
                direction,
                ci,
                NOW,
                0,
                AuthorizationPurpose.RUNTIME_MOVE);
    return new Built(context, List.copyOf(log));
  }

  private static void assertSameBuild(Built shared, Built fresh) {
    ExpandedRoutePlans.assertSameBuild(
        shared.context(), fresh.context(), shared.log(), fresh.log());
  }

  /** 原样转发的图包装：不是可共用的图实现。 */
  private static final class DelegatingGraph implements RailGraph {
    private final SimpleRailGraph delegate;

    private DelegatingGraph(SimpleRailGraph delegate) {
      this.delegate = delegate;
    }

    @Override
    public java.util.Collection<org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode> nodes() {
      return delegate.nodes();
    }

    @Override
    public java.util.Collection<org.fetarute.fetaruteTCAddon.dispatcher.graph.RailEdge> edges() {
      return delegate.edges();
    }

    @Override
    public Optional<org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode> findNode(NodeId id) {
      return delegate.findNode(id);
    }

    @Override
    public java.util.Set<org.fetarute.fetaruteTCAddon.dispatcher.graph.RailEdge> edgesFrom(
        NodeId id) {
      return delegate.edgesFrom(id);
    }

    @Override
    public boolean isBlocked(EdgeId id) {
      return delegate.isBlocked(id);
    }
  }
}
