package org.fetarute.fetaruteTCAddon.dispatcher.route;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.UUID;
import org.bukkit.World;
import org.bukkit.util.Vector;
import org.fetarute.fetaruteTCAddon.company.model.Line;
import org.fetarute.fetaruteTCAddon.company.model.Operator;
import org.fetarute.fetaruteTCAddon.company.model.Route;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.company.model.RouteStopPassType;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailEdge;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphService;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.SignRailNode;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.SimpleRailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.control.EdgeOverrideRailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.persist.RailEdgeOverrideRecord;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.query.RailGraphPath;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.query.RailGraphPathFinder;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RoutePathPrewarmer.NodePair;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.ExpandedRoutePlans;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("交路路径记忆预热")
class RoutePathPrewarmerTest {

  private static final UUID WORLD = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
  private static final UUID ROUTE = UUID.fromString("00000000-0000-0000-0000-0000000000c2");
  private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

  private static final NodeId A = NodeId.of("OP:S:AAA:1");
  private static final NodeId M1 = NodeId.of("OP:A:M1:1");
  private static final NodeId B = NodeId.of("OP:S:BBB:1");
  private static final NodeId SW = NodeId.of("OP:A:SW:1");
  private static final NodeId P1 = NodeId.of("OP:S:STA:1");
  private static final NodeId P2 = NodeId.of("OP:S:STA:2");
  private static final NodeId SW2 = NodeId.of("OP:A:SW2:1");
  private static final NodeId M2 = NodeId.of("OP:A:M2:1");
  private static final NodeId C = NodeId.of("OP:S:CCC:1");

  private final Deque<Runnable> background = new ArrayDeque<>();
  private final RailGraphService service = new RailGraphService();
  private final World world = world();

  @Test
  @DisplayName("起讫点对：相邻途经点原样一对，DYNAMIC 展开成候选股道；直接相邻的跳过；按优先级截断")
  void collectPairsExpandsDynamicCandidates() {
    SimpleRailGraph graph = graph();
    List<RouteDefinitionCache.RouteEntry> entries = List.of(dynamicRoute());

    List<NodePair> pairs = RoutePathPrewarmer.collectPairs(entries, graph, 100);

    assertEquals(
        List.of(
            new NodePair(A, B),
            new NodePair(B, P1),
            new NodePair(P1, C),
            new NodePair(B, P2),
            new NodePair(P2, C)),
        pairs);
    assertEquals(
        List.of(new NodePair(A, B), new NodePair(B, P1)),
        RoutePathPrewarmer.collectPairs(entries, graph, 2));

    RouteDefinitionCache.RouteEntry adjacent =
        entry(List.of(A, M1, B), List.of(stop(0, A), stop(1, M1), stop(2, B)));
    assertTrue(RoutePathPrewarmer.collectPairs(List.of(adjacent), graph, 100).isEmpty());
  }

  @Test
  @DisplayName("预热后同样的查询命中记忆，结果与同步搜索逐项相同")
  void prewarmFillsMemoWithSameResults() {
    SimpleRailGraph graph = graph();
    service.putSnapshot(world, graph, NOW);
    RoutePathPrewarmer prewarmer = prewarmer(List.of(dynamicRoute()));
    List<NodePair> pairs = RoutePathPrewarmer.collectPairs(List.of(dynamicRoute()), graph, 100);
    for (NodePair pair : pairs) {
      assertFalse(RailGraphPathFinder.remembers(graph, pair.from(), pair.to()));
    }

    prewarmer.requestPrewarm();
    runBackground();

    RailGraph runtime = service.runtimeQueryGraph(WORLD, NOW).orElseThrow();
    SimpleRailGraph fresh = graph();
    RailGraphPathFinder finder = new RailGraphPathFinder();
    for (NodePair pair : pairs) {
      assertTrue(RailGraphPathFinder.remembers(runtime, pair.from(), pair.to()), pair.toString());
      Optional<RailGraphPath> warmed =
          finder.shortestPath(
              runtime, pair.from(), pair.to(), RailGraphPathFinder.Options.shortestDistance());
      Optional<RailGraphPath> searched =
          finder.shortestPath(
              fresh, pair.from(), pair.to(), RailGraphPathFinder.Options.shortestDistance());
      assertEquals(searched, warmed, pair.toString());
    }
  }

  @Test
  @DisplayName("有覆盖封锁时：控车用的覆盖视图与 ETA 用的底层图各预热一份")
  void prewarmCoversOverrideViewAndPlainGraph() {
    SimpleRailGraph graph = graph();
    service.putSnapshot(world, graph, NOW);
    EdgeId blocked = EdgeId.undirected(SW, P2);
    service.putEdgeOverride(
        new RailEdgeOverrideRecord(
            WORLD,
            blocked,
            OptionalDouble.empty(),
            OptionalDouble.empty(),
            Optional.empty(),
            true,
            Optional.empty(),
            NOW));
    RoutePathPrewarmer prewarmer = prewarmer(List.of(dynamicRoute()));

    prewarmer.requestPrewarm();
    runBackground();

    RailGraph runtime = service.runtimeQueryGraph(WORLD, NOW).orElseThrow();
    assertTrue(runtime instanceof EdgeOverrideRailGraph);
    assertTrue(RailGraphPathFinder.remembers(runtime, A, B));
    assertTrue(RailGraphPathFinder.remembers(graph, A, B));
    assertTrue(
        RailGraphPathFinder.remembers(
            new EdgeOverrideRailGraph(graph, service.edgeOverrides(WORLD), NOW.plusSeconds(5)),
            B,
            P2),
        "运行时稍后新建的覆盖视图，封锁不变就命中");
  }

  @Test
  @DisplayName("换图后旧任务停下，不往新图里写，新一轮接着预热新图")
  void staleJobStopsAfterGraphSwap() {
    SimpleRailGraph first = graph();
    service.putSnapshot(world, first, NOW);
    RoutePathPrewarmer prewarmer = prewarmer(List.of(dynamicRoute()));
    service.addRuntimeGraphListener(prewarmer::requestPrewarm);
    prewarmer.requestPrewarm();
    assertEquals(1, background.size());

    SimpleRailGraph second = graph();
    service.putSnapshot(world, second, NOW);
    assertEquals(2, background.size());
    runBackground();

    assertFalse(RailGraphPathFinder.remembers(first, A, B), "旧一轮没跑就停了");
    assertTrue(RailGraphPathFinder.remembers(second, A, B));
    assertTrue(RailGraphPathFinder.remembers(second, P2, C));
  }

  @Test
  @DisplayName("跑到一半换图：剩下的不再算")
  void jobStopsMidwayWhenGraphReplaced() {
    ChainFixture chain = chain(RoutePathPrewarmer.CHUNK_PAIRS * 2 + 2);
    service.putSnapshot(world, chain.graph(), NOW);
    RoutePathPrewarmer prewarmer = prewarmer(List.of(chain.route()));
    service.addRuntimeGraphListener(prewarmer::requestPrewarm);
    prewarmer.requestPrewarm();

    background.poll().run();
    List<NodePair> pairs =
        RoutePathPrewarmer.collectPairs(List.of(chain.route()), chain.graph(), 10_000);
    assertTrue(pairs.size() > RoutePathPrewarmer.CHUNK_PAIRS);
    NodePair firstPair = pairs.get(0);
    NodePair lastPair = pairs.get(pairs.size() - 1);
    assertTrue(
        RailGraphPathFinder.remembers(chain.graph(), firstPair.from(), firstPair.to()), "第一段算完了");
    assertEquals(1, background.size(), "剩下的重新排队");

    ChainFixture replaced = chain(RoutePathPrewarmer.CHUNK_PAIRS * 2 + 2);
    service.putSnapshot(world, replaced.graph(), NOW);
    background.poll().run();

    assertFalse(
        RailGraphPathFinder.remembers(chain.graph(), lastPair.from(), lastPair.to()), "旧图剩下的不再算");
  }

  @Test
  @DisplayName("没接变化回调时，后台也按快照身份认出图已换掉")
  void snapshotIdentityCheckedInBackground() {
    SimpleRailGraph first = graph();
    service.putSnapshot(world, first, NOW);
    RoutePathPrewarmer prewarmer = prewarmer(List.of(dynamicRoute()));
    prewarmer.requestPrewarm();

    service.putSnapshot(world, graph(), NOW);
    runBackground();

    assertFalse(RailGraphPathFinder.remembers(first, A, B));
  }

  @Test
  @DisplayName("关闭后不再接受请求，排队的一轮也停下")
  void closeStopsWork() {
    SimpleRailGraph graph = graph();
    service.putSnapshot(world, graph, NOW);
    RoutePathPrewarmer prewarmer = prewarmer(List.of(dynamicRoute()));
    prewarmer.requestPrewarm();

    prewarmer.close();
    runBackground();
    prewarmer.requestPrewarm();

    assertTrue(background.isEmpty());
    assertFalse(RailGraphPathFinder.remembers(graph, A, B));
  }

  @Test
  @DisplayName("连续请求合并成一次采集")
  void requestsCoalesce() {
    service.putSnapshot(world, graph(), NOW);
    Deque<Runnable> main = new ArrayDeque<>();
    RouteDefinitionCache routes = mock(RouteDefinitionCache.class);
    when(routes.entries()).thenReturn(List.of(dynamicRoute()));
    RoutePathPrewarmer prewarmer =
        new RoutePathPrewarmer(
            service, routes, background::add, main::add, m -> {}, Clock.fixed(NOW, ZoneOffset.UTC));

    prewarmer.requestPrewarm();
    prewarmer.requestPrewarm();
    prewarmer.requestPrewarm();
    assertEquals(1, main.size());

    main.poll().run();
    prewarmer.requestPrewarm();
    assertEquals(1, main.size(), "采集之后的新请求另排一次");
    assertEquals(1, background.size());
  }

  @Test
  @DisplayName("展开计划节点串：每个途经点到终点一串，遇 DYNAMIC 截在它之前，各交路按起点轮流排、截断")
  void collectPlanNodesStopsBeforeDynamic() {
    List<RouteDefinitionCache.RouteEntry> entries = List.of(dynamicRoute());

    assertEquals(
        List.of(List.of(A, B), List.of(P1, C)), RoutePathPrewarmer.collectPlanNodes(entries, 100));
    assertEquals(List.of(List.of(A, B)), RoutePathPrewarmer.collectPlanNodes(entries, 1));

    RouteDefinitionCache.RouteEntry plain =
        entry(List.of(A, B, C), List.of(stop(0, A), stop(1, B), stop(2, C)));
    assertEquals(
        List.of(List.of(A, B), List.of(A, B, C), List.of(P1, C), List.of(B, C)),
        RoutePathPrewarmer.collectPlanNodes(List.of(dynamicRoute(), plain, dynamicRoute()), 100),
        "按起点下标轮流：先各交路的第 0 串，再各交路的第 1 串；重复的只留一份");
  }

  @Test
  @DisplayName("后台预算展开计划：控车用的图上每串都命中，且已带整段方向判定")
  void prewarmFillsPlanCache() {
    ChainFixture chain = chain(6);
    service.putSnapshot(world, chain.graph(), NOW);
    RoutePathPrewarmer prewarmer = prewarmer(List.of(chain.route()));
    List<List<NodeId>> plans =
        RoutePathPrewarmer.collectPlanNodes(List.of(chain.route()), Integer.MAX_VALUE);
    assertEquals(5, plans.size());
    RailGraph runtime = service.runtimeQueryGraph(WORLD, NOW).orElseThrow();
    for (List<NodeId> nodes : plans) {
      assertFalse(ExpandedRoutePlans.remembers(runtime, nodes));
    }

    prewarmer.requestPrewarm();
    runBackground();

    for (List<NodeId> nodes : plans) {
      assertTrue(ExpandedRoutePlans.remembers(runtime, nodes), nodes.toString());
      assertFalse(ExpandedRoutePlans.prewarm(runtime, nodes), "整段方向判定也已算好");
    }
  }

  @Test
  @DisplayName("有覆盖封锁时：展开计划只按控车用的覆盖视图预算，ETA 用的底层图不算")
  void planPrewarmOnlyForRuntimeView() {
    ChainFixture chain = chain(4);
    service.putSnapshot(world, chain.graph(), NOW);
    List<NodeId> waypoints = chain.route().definition().waypoints();
    EdgeId blocked = EdgeId.undirected(waypoints.get(0), NodeId.of("OP:A:N1:1"));
    service.putEdgeOverride(
        new RailEdgeOverrideRecord(
            WORLD,
            blocked,
            OptionalDouble.empty(),
            OptionalDouble.empty(),
            Optional.empty(),
            true,
            Optional.empty(),
            NOW));
    RoutePathPrewarmer prewarmer = prewarmer(List.of(chain.route()));

    prewarmer.requestPrewarm();
    runBackground();

    RailGraph runtime = service.runtimeQueryGraph(WORLD, NOW).orElseThrow();
    List<NodeId> tail = waypoints.subList(1, waypoints.size());
    assertTrue(ExpandedRoutePlans.remembers(runtime, tail));
    assertFalse(ExpandedRoutePlans.remembers(chain.graph(), tail));
  }

  @Test
  @DisplayName("展开计划分段预算：一段算完重新排队，中途换图则旧图剩下的不再算")
  void planPrewarmRunsInChunksAndStopsOnGraphSwap() {
    ChainFixture chain = chain(RoutePathPrewarmer.CHUNK_PLANS * 2 + 2);
    service.putSnapshot(world, chain.graph(), NOW);
    RoutePathPrewarmer prewarmer = prewarmer(List.of(chain.route()));
    service.addRuntimeGraphListener(prewarmer::requestPrewarm);
    prewarmer.requestPrewarm();
    List<List<NodeId>> plans =
        RoutePathPrewarmer.collectPlanNodes(List.of(chain.route()), Integer.MAX_VALUE);
    assertTrue(plans.size() > RoutePathPrewarmer.CHUNK_PLANS);

    background.poll().run();
    assertTrue(ExpandedRoutePlans.remembers(chain.graph(), plans.get(0)), "第一段算完了");
    assertFalse(ExpandedRoutePlans.remembers(chain.graph(), plans.get(plans.size() - 1)), "后面的还没算");
    assertEquals(1, background.size(), "剩下的重新排队");

    ChainFixture replaced = chain(RoutePathPrewarmer.CHUNK_PLANS * 2 + 2);
    service.putSnapshot(world, replaced.graph(), NOW);
    background.poll().run();

    assertFalse(
        ExpandedRoutePlans.remembers(chain.graph(), plans.get(plans.size() - 1)), "旧图剩下的不再算");
    runBackground();
    assertTrue(ExpandedRoutePlans.remembers(replaced.graph(), plans.get(plans.size() - 1)));
  }

  private RoutePathPrewarmer prewarmer(List<RouteDefinitionCache.RouteEntry> entries) {
    RouteDefinitionCache routes = mock(RouteDefinitionCache.class);
    when(routes.entries()).thenReturn(entries);
    return new RoutePathPrewarmer(
        service, routes, background::add, Runnable::run, m -> {}, Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private void runBackground() {
    while (!background.isEmpty()) {
      background.poll().run();
    }
  }

  /** 途经 A → B → DYNAMIC:OP:S:STA（占位 1 道）→ C。 */
  private static RouteDefinitionCache.RouteEntry dynamicRoute() {
    RouteStop dynamic =
        new RouteStop(
            ROUTE,
            2,
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            RouteStopPassType.STOP,
            Optional.of("DYNAMIC:OP:S:STA"));
    return entry(List.of(A, B, P1, C), List.of(stop(0, A), stop(1, B), dynamic, stop(3, C)));
  }

  /**
   * A–M1–B–SW，SW 分到 STA 的 1、2 道，再经 SW2–M2 到 C。
   *
   * <p>A 与 B、B 与各股道、各股道与 C 都不直接相邻。
   */
  private static SimpleRailGraph graph() {
    Map<NodeId, RailNode> nodes = new HashMap<>();
    for (NodeId id : List.of(A, M1, B, SW, P1, P2, SW2, M2, C)) {
      nodes.put(id, node(id));
    }
    Map<EdgeId, RailEdge> edges = new HashMap<>();
    for (RailEdge edge :
        List.of(
            edge(A, M1, 10),
            edge(M1, B, 10),
            edge(B, SW, 5),
            edge(SW, P1, 5),
            edge(SW, P2, 6),
            edge(P1, SW2, 5),
            edge(P2, SW2, 6),
            edge(SW2, M2, 10),
            edge(M2, C, 10))) {
      edges.put(edge.id(), edge);
    }
    return new SimpleRailGraph(nodes, edges, Set.of());
  }

  private record ChainFixture(SimpleRailGraph graph, RouteDefinitionCache.RouteEntry route) {}

  /** 一条长链，交路每隔一个节点设一个途经点：每对途经点之间隔一个节点。 */
  private static ChainFixture chain(int waypointCount) {
    Map<NodeId, RailNode> nodes = new HashMap<>();
    Map<EdgeId, RailEdge> edges = new HashMap<>();
    List<NodeId> waypoints = new ArrayList<>();
    List<RouteStop> stops = new ArrayList<>();
    NodeId previous = null;
    for (int i = 0; i < waypointCount * 2 - 1; i++) {
      NodeId id = NodeId.of("OP:A:N" + i + ":1");
      nodes.put(id, node(id));
      if (previous != null) {
        RailEdge edge = edge(previous, id, 3);
        edges.put(edge.id(), edge);
      }
      if (i % 2 == 0) {
        stops.add(stop(waypoints.size(), id));
        waypoints.add(id);
      }
      previous = id;
    }
    return new ChainFixture(new SimpleRailGraph(nodes, edges, Set.of()), entry(waypoints, stops));
  }

  private static RouteDefinitionCache.RouteEntry entry(
      List<NodeId> waypoints, List<RouteStop> stops) {
    RouteDefinition definition =
        new RouteDefinition(new RouteId("OP:L:R"), waypoints, Optional.empty());
    return new RouteDefinitionCache.RouteEntry(
        ROUTE,
        definition,
        new RouteDefinitionCache.RouteRecord(
            mock(Operator.class), mock(Line.class), mock(Route.class)),
        stops);
  }

  private static RouteStop stop(int sequence, NodeId node) {
    return new RouteStop(
        ROUTE,
        sequence,
        Optional.empty(),
        Optional.of(node.value()),
        Optional.empty(),
        RouteStopPassType.STOP,
        Optional.empty());
  }

  private static RailNode node(NodeId id) {
    return new SignRailNode(
        id, NodeType.WAYPOINT, new Vector(0, 64, 0), Optional.empty(), Optional.empty());
  }

  private static RailEdge edge(NodeId a, NodeId b, int length) {
    EdgeId id = EdgeId.undirected(a, b);
    return new RailEdge(id, id.a(), id.b(), length, 0.0, true, Optional.empty());
  }

  private static World world() {
    World world = mock(World.class);
    when(world.getUID()).thenReturn(WORLD);
    return world;
  }
}
