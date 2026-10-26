package org.fetarute.fetaruteTCAddon.dispatcher.graph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.World;
import org.bukkit.util.Vector;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.control.EdgeOverrideRailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.network.RailNetwork;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.portal.PortalLink;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.portal.PortalLinkRegistry;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode;
import org.junit.jupiter.api.Test;

/**
 * {@link SimpleRailGraph#prewarmIndexes()}、切换前准备（{@link
 * RailGraphService#prepareSnapshot}）与快照激活后的预热：预热后查询不再建索引， 结果与懒加载一致；切换前拼好的跨世界路网只在期间什么都没变时原样发布。
 */
class SimpleRailGraphIndexPrewarmTest {

  private static final NodeId WEST_END = NodeId.of("WEST-END");
  private static final NodeId WEST = NodeId.of("WEST");
  private static final NodeId SWITCHER_A = NodeId.of("SW-A");
  private static final NodeId UPPER = NodeId.of("UPPER");
  private static final NodeId LOWER = NodeId.of("LOWER");
  private static final NodeId SWITCHER_B = NodeId.of("SW-B");
  private static final NodeId EAST = NodeId.of("EAST");

  @Test
  void prewarmBuildsAllIndexesAndLaterCallsReuseThem() {
    SimpleRailGraph graph = passingLoopWithStubs();
    assertFalse(graph.indexesBuilt());

    assertTrue(graph.prewarmIndexes());
    assertTrue(graph.indexesBuilt());
    RailGraphComponentIndex componentIndex = graph.componentIndex();

    for (RailEdge edge : graph.edges()) {
      graph.conflictKeyForEdge(edge.id());
      graph.corridorInfoForEdge(edge.id());
      graph.sectionInfoForEdge(edge.id());
    }
    assertTrue(graph.prewarmIndexes());

    assertSame(componentIndex, graph.componentIndex(), "缓存的索引不重建");
  }

  @Test
  void conflictIndexRejectsAComponentIndexOfAnotherGraph() {
    SimpleRailGraph graph = passingLoopWithStubs();
    RailGraphComponentIndex foreign = line(OVER_STATION, OVER_PORTAL).componentIndex();

    assertThrows(
        IllegalArgumentException.class, () -> RailGraphConflictIndex.fromGraph(graph, foreign));
  }

  @Test
  void cachedComponentIndexAndConflictIndexMatchStandaloneBuilds() {
    SimpleRailGraph graph = passingLoopWithStubs();
    assertTrue(graph.prewarmIndexes());

    assertEquals(
        RailGraphComponentIndex.fromGraph(graph).snapshot(), graph.componentIndex().snapshot());
    assertEquals(
        RailGraphConflictIndex.fromGraph(graph).snapshot(),
        RailGraphConflictIndex.fromGraph(graph, graph.componentIndex()).snapshot());
    for (RailEdge edge : graph.edges()) {
      assertEquals(
          RailGraphConflictIndex.fromGraph(graph).conflictKeyForEdge(edge.id()),
          graph.conflictKeyForEdge(edge.id()));
    }
  }

  @Test
  void prewarmedQueriesMatchLazilyBuiltQueries() {
    SimpleRailGraph prewarmed = passingLoopWithStubs();
    SimpleRailGraph lazy = passingLoopWithStubs();
    assertTrue(prewarmed.prewarmIndexes());

    int sectioned = 0;
    for (RailEdge edge : lazy.edges()) {
      EdgeId id = edge.id();
      EdgeId reversed = new EdgeId(id.b(), id.a());
      assertEquals(lazy.conflictKeyForEdge(id), prewarmed.conflictKeyForEdge(id));
      assertEquals(lazy.conflictKeyForEdge(reversed), prewarmed.conflictKeyForEdge(reversed));
      assertEquals(lazy.corridorInfoForEdge(id), prewarmed.corridorInfoForEdge(id));
      assertEquals(lazy.sectionInfoForEdge(id), prewarmed.sectionInfoForEdge(id));
      assertTrue(prewarmed.conflictKeyForEdge(id).isPresent());
      if (prewarmed.sectionInfoForEdge(id).isPresent()) {
        sectioned++;
      }
    }
    // 两端的桥链有 section，会让环内的区间没有：两种结果都核对到了。
    assertEquals(3, sectioned);
    assertTrue(prewarmed.sectionInfoForEdge(EdgeId.undirected(SWITCHER_A, UPPER)).isEmpty());
  }

  @Test
  void overrideViewAndNetworkViewQueryTheSamePrewarmedSnapshot() {
    SimpleRailGraph graph = passingLoopWithStubs();
    SimpleRailGraph lazy = passingLoopWithStubs();
    assertTrue(graph.prewarmIndexes());
    RailGraphComponentIndex componentIndex = graph.componentIndex();
    UUID worldId = UUID.randomUUID();

    EdgeOverrideRailGraph overrideView = new EdgeOverrideRailGraph(graph, Map.of(), Instant.now());
    RailGraphSectionSupport networkView =
        (RailGraphSectionSupport)
            RailNetwork.build(Map.of(worldId, overrideView), List.of()).view(worldId);

    for (RailGraphSectionSupport view : List.of(overrideView, networkView)) {
      for (RailEdge edge : lazy.edges()) {
        EdgeId id = edge.id();
        assertEquals(lazy.conflictKeyForEdge(id), view.conflictKeyForEdge(id));
        assertEquals(lazy.corridorInfoForEdge(id), view.corridorInfoForEdge(id));
        assertEquals(lazy.sectionInfoForEdge(id), view.sectionInfoForEdge(id));
      }
    }

    assertTrue(graph.indexesBuilt());
    assertSame(componentIndex, graph.componentIndex());
  }

  @Test
  void activatedSnapshotIsPrewarmedOnTheConfiguredExecutor() {
    List<Runnable> queued = new ArrayList<>();
    RailGraphService service = new RailGraphService();
    service.setBackgroundExecutor(queued::add);
    SimpleRailGraph graph = passingLoopWithStubs();

    service.putSnapshot(world(UUID.randomUUID()), graph, Instant.now());

    // 激活只建它本来就要同步建的连通分量索引，冲突与 section 索引交给执行器。
    assertFalse(graph.indexesBuilt());
    assertEquals(1, queued.size());

    queued.forEach(Runnable::run);

    assertTrue(graph.indexesBuilt());
  }

  @Test
  void prewarmSkipsSnapshotReplacedBeforeItRuns() {
    List<Runnable> queued = new ArrayList<>();
    RailGraphService service = new RailGraphService();
    service.setBackgroundExecutor(queued::add);
    World world = world(UUID.randomUUID());
    SimpleRailGraph replaced = passingLoopWithStubs();
    SimpleRailGraph current = passingLoopWithStubs();

    service.putSnapshot(world, replaced, Instant.now());
    service.putSnapshot(world, current, Instant.now());
    queued.forEach(Runnable::run);

    assertFalse(replaced.indexesBuilt());
    assertTrue(current.indexesBuilt());
  }

  @Test
  void rejectedPrewarmLeavesSnapshotActiveAndLazilyQueryable() {
    List<String> log = new ArrayList<>();
    RailGraphService service = new RailGraphService(log::add);
    service.setBackgroundExecutor(
        task -> {
          throw new RejectedExecutionException("disabled");
        });
    UUID worldId = UUID.randomUUID();
    SimpleRailGraph graph = passingLoopWithStubs();

    service.putSnapshot(world(worldId), graph, Instant.now());

    assertSame(graph, service.getSnapshot(worldId).orElseThrow().graph());
    assertFalse(graph.indexesBuilt());
    assertTrue(log.stream().anyMatch(line -> line.contains("预热未排上")));
    EdgeId westStub = EdgeId.undirected(WEST, SWITCHER_A);
    assertEquals(
        passingLoopWithStubs().sectionInfoForEdge(westStub), graph.sectionInfoForEdge(westStub));
    assertFalse(graph.sectionInfoForEdge(westStub).isEmpty());
  }

  @Test
  void preparedSnapshotIsSwitchedWithoutRebuildingItsIndexes() {
    RailGraphService service = new RailGraphService();
    UUID worldId = UUID.randomUUID();
    SimpleRailGraph graph = passingLoopWithStubs();

    RailGraphService.PreparedSnapshot prepared = service.prepareSnapshot(worldId, graph);
    assertTrue(graph.indexesBuilt());
    RailGraphComponentIndex componentIndex = graph.componentIndex();
    assertNull(prepared.network(), "跨世界未开启时不拼路网");

    service.putSnapshot(world(worldId), prepared, Instant.now());

    assertSame(graph, service.getSnapshot(worldId).orElseThrow().graph());
    assertEquals(Optional.of("EAST"), service.componentKey(worldId, WEST_END));
    assertEquals(1, service.componentCount(graph));
    assertSame(componentIndex, graph.componentIndex(), "切换与分量计数都用图上缓存的索引");
  }

  @Test
  void preparedCrossWorldNetworkIsPublishedOnSwitch() {
    CrossWorld fixture = crossWorld();
    SimpleRailGraph next = fixture.overworldWithBranch();

    RailGraphService.PreparedSnapshot prepared =
        fixture.service().prepareSnapshot(fixture.over(), next);
    assertNotNull(prepared.network());
    fixture.service().putSnapshot(fixture.overWorld(), prepared, Instant.now());

    RailGraph runtime = fixture.service().networkGraph(fixture.over());
    assertSame(prepared.network().view(fixture.over()), runtime);
    assertTrue(runtime.findNode(BRANCH).isPresent());
    assertTrue(runtime.findNode(NETHER_STATION).isPresent());
  }

  @Test
  void preparedNetworkIsDroppedWhenOverridesChangedBeforeSwitch() {
    CrossWorld fixture = crossWorld();
    SimpleRailGraph next = fixture.overworldWithBranch();

    RailGraphService.PreparedSnapshot prepared =
        fixture.service().prepareSnapshot(fixture.over(), next);
    fixture
        .service()
        .putEdgeOverride(
            new org.fetarute.fetaruteTCAddon.dispatcher.graph.persist.RailEdgeOverrideRecord(
                fixture.nether(),
                EdgeId.undirected(NETHER_PORTAL, NETHER_STATION),
                OptionalDouble.empty(),
                OptionalDouble.empty(),
                Optional.empty(),
                true,
                Optional.empty(),
                Instant.now()));
    fixture.service().putSnapshot(fixture.overWorld(), prepared, Instant.now());

    RailGraph runtime = fixture.service().networkGraph(fixture.over());
    assertNotSame(prepared.network().view(fixture.over()), runtime);
    assertTrue(runtime.findNode(BRANCH).isPresent());
    assertTrue(runtime.isBlocked(EdgeId.undirected(NETHER_PORTAL, NETHER_STATION)));
  }

  @Test
  void preparedNetworkIsDroppedWhenPortalLinksChangedBeforeSwitch() {
    CrossWorld fixture = crossWorld();
    SimpleRailGraph next = fixture.overworldWithBranch();

    RailGraphService.PreparedSnapshot prepared =
        fixture.service().prepareSnapshot(fixture.over(), next);
    fixture.links().put(portalLink(BRANCH, NETHER_STATION, fixture.over(), fixture.nether()));
    fixture.service().putSnapshot(fixture.overWorld(), prepared, Instant.now());

    RailGraph runtime = fixture.service().networkGraph(fixture.over());
    assertNotSame(prepared.network().view(fixture.over()), runtime);
    assertTrue(runtime.findEdge(EdgeId.undirected(BRANCH, NETHER_STATION)).isPresent(), "按新的连接重建");
  }

  @Test
  void postActivationPrewarmAlsoBuildsTheCrossWorldNetwork() {
    CrossWorld fixture = crossWorld();
    List<Runnable> queued = new ArrayList<>();
    fixture.service().setBackgroundExecutor(queued::add);
    SimpleRailGraph next = fixture.overworldWithBranch();

    fixture.service().putSnapshot(fixture.overWorld(), next, Instant.now());
    // 路网缓存换上新网时会发运行时图变化通知：预热之前不该有。
    AtomicInteger installed = new AtomicInteger();
    fixture.service().addRuntimeGraphListener(installed::incrementAndGet);
    assertEquals(1, queued.size());
    queued.get(0).run();

    assertEquals(1, installed.get(), "预热在后台换上了按新图拼的路网");
    assertTrue(next.indexesBuilt());
    assertTrue(fixture.service().networkGraph(fixture.over()).findNode(BRANCH).isPresent());
    assertEquals(1, installed.get(), "之后的查询直接命中，不再重建");
  }

  /** 西端两段桥链接会让环，东端一段桥链：既有 section 区间，也有环内没有 section 的区间。 */
  private static SimpleRailGraph passingLoopWithStubs() {
    Map<NodeId, RailNode> nodes = new LinkedHashMap<>();
    for (NodeId id : List.of(WEST_END, WEST, UPPER, LOWER, EAST)) {
      nodes.put(id, node(id, NodeType.WAYPOINT, nodes.size()));
    }
    nodes.put(SWITCHER_A, node(SWITCHER_A, NodeType.SWITCHER, nodes.size()));
    nodes.put(SWITCHER_B, node(SWITCHER_B, NodeType.SWITCHER, nodes.size()));
    Map<EdgeId, RailEdge> edges = new LinkedHashMap<>();
    putEdge(edges, WEST_END, WEST);
    putEdge(edges, WEST, SWITCHER_A);
    putEdge(edges, SWITCHER_A, UPPER);
    putEdge(edges, UPPER, SWITCHER_B);
    putEdge(edges, SWITCHER_A, LOWER);
    putEdge(edges, LOWER, SWITCHER_B);
    putEdge(edges, SWITCHER_B, EAST);
    return new SimpleRailGraph(nodes, edges, Set.of());
  }

  private static final NodeId OVER_STATION = NodeId.of("OVER-STATION");
  private static final NodeId OVER_PORTAL = NodeId.of("OVER-PORTAL");
  private static final NodeId BRANCH = NodeId.of("BRANCH");
  private static final NodeId NETHER_PORTAL = NodeId.of("NETHER-PORTAL");
  private static final NodeId NETHER_STATION = NodeId.of("NETHER-STATION");

  /** 主世界与下界各一条线，传送门两端相连；跨世界已开启，两边的旧图都已激活。 */
  private record CrossWorld(
      RailGraphService service, PortalLinkRegistry links, UUID over, UUID nether, World overWorld) {

    /** 主世界的新图：旧线上多接一条支线。 */
    SimpleRailGraph overworldWithBranch() {
      return line(OVER_STATION, OVER_PORTAL, BRANCH);
    }
  }

  private static CrossWorld crossWorld() {
    UUID over = UUID.randomUUID();
    UUID nether = UUID.randomUUID();
    RailGraphService service = new RailGraphService();
    PortalLinkRegistry links = new PortalLinkRegistry();
    links.put(portalLink(OVER_PORTAL, NETHER_PORTAL, over, nether));
    service.configureCrossWorld(true, links);
    World overWorld = world(over);
    service.putSnapshot(overWorld, line(OVER_STATION, OVER_PORTAL), Instant.now());
    service.putSnapshot(world(nether), line(NETHER_PORTAL, NETHER_STATION), Instant.now());
    assertNotNull(service.networkGraph(over));
    return new CrossWorld(service, links, over, nether, overWorld);
  }

  /** 首尾相接的一条线；第三个节点（有的话）接在第一个节点上。 */
  private static SimpleRailGraph line(NodeId first, NodeId second, NodeId... branches) {
    Map<NodeId, RailNode> nodes = new LinkedHashMap<>();
    nodes.put(first, node(first, NodeType.WAYPOINT, 0));
    nodes.put(second, node(second, NodeType.WAYPOINT, 1));
    Map<EdgeId, RailEdge> edges = new LinkedHashMap<>();
    putEdge(edges, first, second);
    for (NodeId branch : branches) {
      nodes.put(branch, node(branch, NodeType.WAYPOINT, nodes.size()));
      putEdge(edges, first, branch);
    }
    return new SimpleRailGraph(nodes, edges, Set.of());
  }

  private static PortalLink portalLink(NodeId from, NodeId to, UUID fromWorld, UUID toWorld) {
    return new PortalLink(
        fromWorld, from, toWorld, to, PortalLink.Source.MANUAL, 4.0, Instant.EPOCH);
  }

  private static World world(UUID worldId) {
    World world = mock(World.class);
    when(world.getUID()).thenReturn(worldId);
    when(world.getName()).thenReturn("world");
    return world;
  }

  private static SignRailNode node(NodeId id, NodeType type, double x) {
    return new SignRailNode(id, type, new Vector(x, 64.0, 0.0), Optional.empty(), Optional.empty());
  }

  private static void putEdge(Map<EdgeId, RailEdge> edges, NodeId from, NodeId to) {
    EdgeId id = EdgeId.undirected(from, to);
    edges.put(id, new RailEdge(id, from, to, 10, 8.0, true, Optional.empty()));
  }
}
