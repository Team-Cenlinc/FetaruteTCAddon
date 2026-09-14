package org.fetarute.fetaruteTCAddon.dispatcher.graph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.bukkit.World;
import org.bukkit.util.Vector;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.interlocking.RailEdgeFootprint;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.interlocking.RailFootprintCell;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.interlocking.RailInterlockingEdgeSignature;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.interlocking.RailInterlockingState;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.persist.RailEdgeRecord;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.persist.RailInterlockingSnapshotRecord;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.persist.RailNodeRecord;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode;
import org.junit.jupiter.api.Test;

class RailGraphServiceTest {

  @Test
  void findWorldIdForPathUsesSingleGraph() {
    NodeId a = NodeId.of("A");
    NodeId b = NodeId.of("B");
    NodeId c = NodeId.of("C");
    RailGraph graph = graphWithEdges(edge(a, b), edge(b, c));

    RailGraphService service = new RailGraphService(world -> graph);
    World world = mock(World.class);
    UUID worldId = UUID.randomUUID();
    when(world.getUID()).thenReturn(worldId);
    when(world.getName()).thenReturn("world");
    service.putSnapshot(world, graph, Instant.now());

    Optional<UUID> found = service.findWorldIdForPath(List.of(a, b, c));

    assertEquals(Optional.of(worldId), found);
  }

  @Test
  void findWorldIdForPathReturnsEmptyWhenEdgesSplitAcrossWorlds() {
    NodeId a = NodeId.of("A");
    NodeId b = NodeId.of("B");
    NodeId c = NodeId.of("C");
    RailGraph graphA = graphWithEdges(edge(a, b));
    RailGraph graphB = graphWithEdges(edge(b, c));

    RailGraphService service = new RailGraphService(world -> graphA);
    World world1 = mock(World.class);
    UUID worldId1 = UUID.randomUUID();
    when(world1.getUID()).thenReturn(worldId1);
    when(world1.getName()).thenReturn("world1");
    service.putSnapshot(world1, graphA, Instant.now());

    World world2 = mock(World.class);
    UUID worldId2 = UUID.randomUUID();
    when(world2.getUID()).thenReturn(worldId2);
    when(world2.getName()).thenReturn("world2");
    service.putSnapshot(world2, graphB, Instant.now());

    Optional<UUID> found = service.findWorldIdForPath(List.of(a, b, c));

    assertTrue(found.isEmpty());
  }

  @Test
  void buildGraphFromRecordsRestoresIndependentSparseSnapshot() {
    UUID worldId = UUID.randomUUID();
    NodeId a = NodeId.of("A");
    NodeId b = NodeId.of("B");
    EdgeId edgeId = EdgeId.undirected(a, b);
    List<RailNodeRecord> nodes = List.of(nodeRecord(worldId, a, 0), nodeRecord(worldId, b, 10));
    List<RailEdgeRecord> edges = List.of(new RailEdgeRecord(worldId, edgeId, 10, 0.0, true));
    RailInterlockingSnapshotRecord snapshot =
        new RailInterlockingSnapshotRecord(
            worldId,
            RailInterlockingSnapshotRecord.CURRENT_FORMAT_VERSION,
            RailInterlockingEdgeSignature.of(Set.of(edgeId)),
            new org.fetarute.fetaruteTCAddon.dispatcher.graph.interlocking.RailInterlockingCoverage(
                1, 1, true),
            Map.of());

    RailGraph graph = RailGraphService.buildGraphFromRecords(nodes, edges, Optional.of(snapshot));

    RailGraphInterlockingSupport support = (RailGraphInterlockingSupport) graph;
    assertTrue(support.interlockingState().coverage().complete());
    assertEquals(1, support.interlockingState().coverage().inputEdgeCount());
  }

  @Test
  void buildGraphFromLegacyRecordsCreatesIncompleteWorldSentinel() {
    UUID worldId = UUID.randomUUID();
    NodeId a = NodeId.of("A");
    NodeId b = NodeId.of("B");
    EdgeId edgeId = EdgeId.undirected(a, b);
    List<RailNodeRecord> nodes = List.of(nodeRecord(worldId, a, 0), nodeRecord(worldId, b, 10));
    List<RailEdgeRecord> edges = List.of(new RailEdgeRecord(worldId, edgeId, 10, 0.0, true));

    RailGraph graph = RailGraphService.buildGraphFromRecords(nodes, edges);

    RailGraphInterlockingSupport support = (RailGraphInterlockingSupport) graph;
    assertTrue(support.interlockingState().available());
    assertFalse(support.interlockingState().coverage().complete());
    assertEquals(1, support.interlockingState().zoneKeysForEdge(edgeId).size());
  }

  @Test
  void rejectsChangedInterlockingProjectionWhileClaimsAreActiveAndKeepsOldSnapshot() {
    UUID worldId = UUID.randomUUID();
    World world = mock(World.class);
    when(world.getUID()).thenReturn(worldId);
    RailGraph oldGraph =
        interlockingGraph(
            worldId, new RailEdgeFootprint(1, true, Set.of(new RailFootprintCell(2, 64, 8))));
    RailGraph changedGraph = interlockingGraph(worldId, new RailEdgeFootprint(0, false, Set.of()));
    RailGraphService service = new RailGraphService(ignored -> oldGraph);
    service.putSnapshot(world, oldGraph, Instant.parse("2026-01-01T00:00:00Z"));
    service.setSnapshotActivationGuard(() -> false);

    assertThrows(
        IllegalStateException.class,
        () -> service.putSnapshot(world, changedGraph, Instant.parse("2026-01-02T00:00:00Z")));

    assertSame(oldGraph, service.getSnapshot(world).orElseThrow().graph());
  }

  @Test
  void staleSnapshotStillGuardsItsLastActivatedInterlockingProjection() {
    UUID worldId = UUID.randomUUID();
    World world = mock(World.class);
    when(world.getUID()).thenReturn(worldId);
    RailGraph oldGraph =
        interlockingGraph(
            worldId, new RailEdgeFootprint(1, true, Set.of(new RailFootprintCell(2, 64, 8))));
    RailGraph changedGraph = interlockingGraph(worldId, new RailEdgeFootprint(0, false, Set.of()));
    RailGraphService service = new RailGraphService(ignored -> oldGraph);
    service.putSnapshot(world, oldGraph, Instant.parse("2026-01-01T00:00:00Z"));
    service.setSnapshotActivationGuard(() -> false);
    service.markStale(
        world,
        new RailGraphService.RailGraphStaleState(
            Instant.parse("2026-01-01T00:00:00Z"), "old", "new", 2, 1, 3));

    assertThrows(
        IllegalStateException.class,
        () -> service.putSnapshot(world, changedGraph, Instant.parse("2026-01-02T00:00:00Z")));

    assertTrue(service.getSnapshot(world).isEmpty());
    assertTrue(service.getStaleState(world).isPresent());
  }

  private static RailEdge edge(NodeId a, NodeId b) {
    return new RailEdge(EdgeId.undirected(a, b), a, b, 10, -1.0, true, Optional.empty());
  }

  private static RailGraph graphWithEdges(RailEdge... edges) {
    List<RailEdge> edgeList = List.of(edges);
    Map<NodeId, RailNode> nodes =
        edgeList.stream()
            .flatMap(edge -> java.util.stream.Stream.of(edge.from(), edge.to()))
            .distinct()
            .collect(java.util.stream.Collectors.toMap(id -> id, RailGraphServiceTest::node));
    return new RailGraph() {
      @Override
      public java.util.Collection<RailNode> nodes() {
        return nodes.values();
      }

      @Override
      public java.util.Collection<RailEdge> edges() {
        return edgeList;
      }

      @Override
      public Optional<RailNode> findNode(NodeId id) {
        return Optional.ofNullable(nodes.get(id));
      }

      @Override
      public Set<RailEdge> edgesFrom(NodeId id) {
        return edgeList.stream()
            .filter(edge -> edge.from().equals(id) || edge.to().equals(id))
            .collect(java.util.stream.Collectors.toSet());
      }

      @Override
      public boolean isBlocked(EdgeId id) {
        return false;
      }
    };
  }

  private static RailNode node(NodeId id) {
    return new RailNode() {
      @Override
      public NodeId id() {
        return id;
      }

      @Override
      public NodeType type() {
        return NodeType.WAYPOINT;
      }

      @Override
      public Vector worldPosition() {
        return new Vector(0, 0, 0);
      }

      @Override
      public Optional<String> trainCartsDestination() {
        return Optional.empty();
      }
    };
  }

  private static RailNodeRecord nodeRecord(UUID worldId, NodeId id, int x) {
    return new RailNodeRecord(
        worldId, id, NodeType.WAYPOINT, x, 64, 0, Optional.empty(), Optional.empty());
  }

  /**
   * 图激活必须报出物理联锁覆盖是否可用，且两条构建路径必须报出**不同**的结果。
   *
   * <p>{@code cellCoverageAvailable()} 是「车体实际压住哪些区间」这条证据链的总闸： 它为假时 {@code
   * RuntimeDispatchService.livePhysicalEdgeCoverage} 一律返回 incomplete， 于是任何以实测覆盖为放行条件的机制（尾部保护释放 /
   * Phase 4）都 fail-closed 到**一个都不放**。
   *
   * <p>而两条路径结果天差地别：完整图构建有逐边足迹，索引可用；从持久化快照重建**按设计只有 Zone、 没有逐边足迹，索引必然为空**——正常重启的服务器走的正是后者。
   *
   * <p>此前运行时没有任何诊断能区分它们：日志里只有构建期特性标志 {@code liveFootprintReverseIndex=true}，
   * 那只说明代码有这个功能，不说明索引真的建起来了。缺了这一行，就可能在一个结构性为空的证据源上 实现 Phase 4，得到一个代码路径俱在、trace 照常输出、却从不触发的机制。
   */
  @Test
  void graphActivationReportsWhetherPhysicalCellCoverageIsActuallyUsable() {
    UUID worldId = UUID.randomUUID();
    World world = mock(World.class);
    when(world.getUID()).thenReturn(worldId);

    // 一：完整图构建（有逐边足迹）——索引应当可用。
    List<String> builtLog = new ArrayList<>();
    RailGraph builtGraph =
        interlockingGraph(
            worldId, new RailEdgeFootprint(1, true, Set.of(new RailFootprintCell(2, 64, 8))));
    new RailGraphService(ignored -> builtGraph, builtLog::add).rebuild(world);
    String builtLine = coverageLine(builtLog);
    assertTrue(builtLine.contains("cellCoverageAvailable=true"), "完整图构建的索引应当可用：" + builtLine);

    // 二：从持久化快照重建——按设计没有逐边足迹，索引必然为空。
    List<String> restoredLog = new ArrayList<>();
    NodeId a = NodeId.of("A");
    NodeId b = NodeId.of("B");
    EdgeId edgeId = EdgeId.undirected(a, b);
    RailInterlockingSnapshotRecord snapshot =
        new RailInterlockingSnapshotRecord(
            worldId,
            RailInterlockingSnapshotRecord.CURRENT_FORMAT_VERSION,
            RailInterlockingEdgeSignature.of(Set.of(edgeId)),
            new org.fetarute.fetaruteTCAddon.dispatcher.graph.interlocking.RailInterlockingCoverage(
                1, 1, true),
            Map.of());
    RailGraph restoredGraph =
        RailGraphService.buildGraphFromRecords(
            List.of(nodeRecord(worldId, a, 0), nodeRecord(worldId, b, 10)),
            List.of(new RailEdgeRecord(worldId, edgeId, 10, 0.0, true)),
            Optional.of(snapshot));
    new RailGraphService(ignored -> restoredGraph, restoredLog::add).rebuild(world);
    String restoredLine = coverageLine(restoredLog);
    assertTrue(
        restoredLine.contains("cellCoverageAvailable=false"),
        "持久化快照重建的索引必然为空，必须如实报告：" + restoredLine);

    // 判别性的关键：两条路径**必须**报出不同结果。只要这一条成立，
    // 这条诊断就确实在区分它该区分的东西，而不是恒真或恒假的摆设。
    assertNotEquals(
        builtLine.contains("cellCoverageAvailable=true"),
        restoredLine.contains("cellCoverageAvailable=true"),
        "两条构建路径必须报出不同的覆盖可用性，否则这条诊断什么都没区分");
  }

  private static String coverageLine(List<String> log) {
    return log.stream()
        .filter(line -> line.startsWith("SMART_INTERLOCKING_COVERAGE"))
        .findFirst()
        .orElseThrow(() -> new AssertionError("图激活没有报告联锁覆盖：\n" + log));
  }

  private static RailGraph interlockingGraph(UUID worldId, RailEdgeFootprint footprint) {
    NodeId a = NodeId.of("A");
    NodeId b = NodeId.of("B");
    RailEdge edge = new RailEdge(EdgeId.undirected(a, b), a, b, 10, 0.0, true, Optional.empty());
    Map<NodeId, RailNode> nodes = Map.of(a, node(a), b, node(b));
    Map<EdgeId, RailEdge> edges = Map.of(edge.id(), edge);
    RailInterlockingState state =
        RailInterlockingState.from(worldId, edges.keySet(), Map.of(edge.id(), footprint));
    return new SimpleRailGraph(nodes, edges, Set.of(), state);
  }

  /**
   * 逐边足迹必须能往返持久化——否则 Phase 4 的地基永远是空的。
   *
   * <p>足迹在图构建时存在，却在写库那一步丢失：`RailEdge` 不带它， `RailInterlockingState.from(...)` 建完索引就把它消费了，而
   * `RailEdgeRecord` 此前没有这个字段。 于是每次从快照恢复图，cell→edge 索引必然为空、{@code cellCoverageAvailable()} 为假，
   * 一切以实测覆盖为放行条件的机制（尾部保护释放 / Phase 4）全部 fail-closed 到**一个都不放**。
   *
   * <p>实服第十二轮实测确认 {@code cellCoverageAvailable=false}， 而 `PROTECTIVE_RETAIN_HOLD` 占全网滞留的
   * **38%**（260 车·分 / 691 车·分，74 分钟一轮）， 且集中在 MT 的 PTK→SPB→JBS/WSD 主走廊上（`S:WSD:2` 出站中位 337 秒）。
   *
   * <p>本用例的判别核心是**带足迹与不带足迹必须得到相反的覆盖可用性**—— 只断言"能读回来"是不够的，那不证明它真的重建出了索引。
   */
  @Test
  void edgeFootprintsSurvivePersistenceAndRebuildTheCellCoverageIndex() {
    UUID worldId = UUID.randomUUID();
    NodeId a = NodeId.of("A");
    NodeId b = NodeId.of("B");
    EdgeId edgeId = EdgeId.undirected(a, b);
    List<RailNodeRecord> nodes = List.of(nodeRecord(worldId, a, 0), nodeRecord(worldId, b, 10));
    RailFootprintCell cell = new RailFootprintCell(2, 64, 8);

    // 一：带足迹的记录 ⇒ 必须走完整构建路径，cell→edge 索引可用。
    RailGraph withFootprint =
        RailGraphService.buildGraphFromRecords(
            nodes,
            List.of(new RailEdgeRecord(worldId, edgeId, 10, 0.0, true, Set.of(cell))),
            Optional.empty());
    RailInterlockingState withState =
        ((RailGraphInterlockingSupport) withFootprint).interlockingState();
    assertTrue(withState.cellCoverageAvailable(), "带足迹持久化回来必须重建出 cell→edge 索引，否则 Phase 4 的地基仍然是空的");
    assertTrue(
        withState.edgesForCell(cell).contains(edgeId),
        "反向索引必须能по方块查回那条边：" + withState.edgesForCell(cell));

    // 二：不带足迹（旧库 / 旧行）⇒ 保持原行为，索引不可用，调用方 fail-closed。
    RailGraph withoutFootprint =
        RailGraphService.buildGraphFromRecords(
            nodes, List.of(new RailEdgeRecord(worldId, edgeId, 10, 0.0, true)), Optional.empty());
    RailInterlockingState withoutState =
        ((RailGraphInterlockingSupport) withoutFootprint).interlockingState();
    assertFalse(withoutState.cellCoverageAvailable(), "没有足迹时必须如实报告索引不可用——缺证据不得当成证据");

    // 判别核心：两条路径必须得到**相反**的结果。只要这条成立，
    // 这次改动就确实在做它该做的事，而不是一个恒真或恒假的摆设。
    assertNotEquals(
        withState.cellCoverageAvailable(),
        withoutState.cellCoverageAvailable(),
        "带足迹与不带足迹必须得到相反的覆盖可用性");
  }

  /** 足迹编解码必须往返一致；坏数据一律 fail-closed 成空集合，绝不抛出。 */
  @Test
  void footprintCodecRoundTripsAndFailsClosedOnGarbage() {
    Set<RailFootprintCell> cells =
        Set.of(new RailFootprintCell(1, 2, 3), new RailFootprintCell(-4, 64, 700));
    String encoded =
        org.fetarute.fetaruteTCAddon.dispatcher.graph.persist.RailEdgeFootprintCodec.encode(cells);
    assertEquals(
        cells,
        org.fetarute.fetaruteTCAddon.dispatcher.graph.persist.RailEdgeFootprintCodec.decode(
            encoded),
        "往返必须一致");
    for (String garbage :
        List.of("", "   ", "not-json", "{}", "[[1,2]]", "[[1,2,3,4]]", "[1,2,3]")) {
      assertTrue(
          org.fetarute
              .fetaruteTCAddon
              .dispatcher
              .graph
              .persist
              .RailEdgeFootprintCodec
              .decode(garbage)
              .isEmpty(),
          "坏数据必须 fail-closed 成空集合而不是抛出：" + garbage);
    }
  }
}
