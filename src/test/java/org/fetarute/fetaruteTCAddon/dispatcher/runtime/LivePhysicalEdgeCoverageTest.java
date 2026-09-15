package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyResource;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * 车体实际覆盖查询：Phase 4 判据的来源。
 *
 * <p>要把自持尾部保护的回收从 CONFLICT 扩到 NODE/EDGE，必须能证明"车体不在该资源上"。 CONFLICT 是抽象互斥键，放了不会撞车；NODE/EDGE
 * 对应物理空间，车体还压着时释放就是 co-occupancy。
 *
 * <p>本用例钉住的核心是**缺证据时的语义**：查询失败必须返回 incomplete，而 {@code covers()} 在 incomplete 时**一律返回
 * true**——让调用方保守处理。"没覆盖"与"无从判断"分不开， 正是本项目反复栽跟头的那类缺陷（见 dispatch-disproven-hypotheses #12/#13）。
 */
class LivePhysicalEdgeCoverageTest {

  private static final OccupancyResource SOME_EDGE =
      OccupancyResource.forEdge(
          org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId.undirected(
              NodeId.of("OP:S:ALFA:1"), NodeId.of("OP:S:BRAVO:1")));

  @Test
  void missingLiveFootprintYieldsIncompleteAndTreatsEverythingAsCovered() {
    RuntimeDispatchService service = TestServices.minimal(new ArrayList<>());

    RuntimeDispatchService.LivePhysicalEdgeCoverage coverage =
        service.livePhysicalEdgeCoverage(null, Mockito.mock(RailGraph.class));

    assertFalse(coverage.complete(), "读不到现场足迹时必须是 incomplete");
    assertTrue(coverage.covers(SOME_EDGE), "无从判断时必须当作'压着'，否则就是把缺证据当成了证据");
    assertTrue(coverage.resources().isEmpty());
  }

  @Test
  void graphWithoutInterlockingSupportIsAlsoIncomplete() {
    RuntimeDispatchService service = TestServices.minimal(new ArrayList<>());
    RuntimeTrainHandle train = Mockito.mock(RuntimeTrainHandle.class);
    Mockito.when(train.observeLiveRailFootprint())
        .thenReturn(
            LiveRailFootprintObservation.available(
                java.util.Set.of(
                    new org.fetarute
                        .fetaruteTCAddon
                        .dispatcher
                        .graph
                        .interlocking
                        .RailFootprintCell(1, 64, 1))));

    RuntimeDispatchService.LivePhysicalEdgeCoverage coverage =
        service.livePhysicalEdgeCoverage(train, Mockito.mock(RailGraph.class));

    assertFalse(coverage.complete(), "图不带联锁能力时同样无从判断");
    assertTrue(coverage.covers(SOME_EDGE));
  }

  private static final NodeId A = NodeId.of("OP:S:ALFA:1");
  private static final NodeId B = NodeId.of("OP:S:BRAVO:1");
  private static final org.fetarute.fetaruteTCAddon.dispatcher.graph.interlocking.RailFootprintCell
      CELL_ON_EDGE =
          new org.fetarute.fetaruteTCAddon.dispatcher.graph.interlocking.RailFootprintCell(
              2, 64, 8);

  /**
   * 端点节点必须**一并**进入覆盖集合。
   *
   * <p>这是 Phase 4 的 NODE 半边赖以安全的那一步，而方向是关键：节点是被**加进**覆盖集合的，
   * 因此只会让"资源不在覆盖集合里才放行"更严。反过来推（"不是任何已覆盖区间的端点就算已离开"） 依赖光栅化无缝隙这一未验证前提，推错就是在车压着的节点上解除保护——共占红线。
   */
  @Test
  void coveredEdgeAlsoMarksBothEndpointNodesAsCovered() {
    RuntimeDispatchService service = TestServices.minimal(new ArrayList<>());
    RuntimeTrainHandle train = trainOn(CELL_ON_EDGE);

    RuntimeDispatchService.LivePhysicalEdgeCoverage coverage =
        service.livePhysicalEdgeCoverage(train, interlockingGraph());

    assertTrue(coverage.complete(), "有足迹又有 cell 索引时应当能判断");
    assertTrue(coverage.covers(SOME_EDGE), "车体所在区间必须算覆盖");
    assertTrue(coverage.covers(OccupancyResource.forNode(A)), "区间端点 A 必须一并算覆盖");
    assertTrue(coverage.covers(OccupancyResource.forNode(B)), "区间端点 B 必须一并算覆盖");
  }

  /**
   * 一条边都定位不到时必须 incomplete，而不是"complete 且什么都没覆盖"。
   *
   * <p>后者会让 Phase 4 把这辆车持有的**每一个**尾部保护都判成"已离开"并全部释放—— 而真实含义是"不知道车在哪"。这正是本项目反复栽的那类缺陷：把缺证据当成证据。
   */
  @Test
  void cellsThatMapToNoEdgeYieldIncompleteRatherThanEmptyCoverage() {
    RuntimeDispatchService service = TestServices.minimal(new ArrayList<>());
    RuntimeTrainHandle train =
        trainOn(
            new org.fetarute.fetaruteTCAddon.dispatcher.graph.interlocking.RailFootprintCell(
                999, 64, 999));

    RuntimeDispatchService.LivePhysicalEdgeCoverage coverage =
        service.livePhysicalEdgeCoverage(train, interlockingGraph());

    assertFalse(coverage.complete(), "定位不到任何区间 = 无从判断，不是'哪儿都没压着'");
    assertTrue(coverage.covers(SOME_EDGE), "无从判断时一律当作压着");
  }

  private static RuntimeTrainHandle trainOn(
      org.fetarute.fetaruteTCAddon.dispatcher.graph.interlocking.RailFootprintCell cell) {
    RuntimeTrainHandle train = Mockito.mock(RuntimeTrainHandle.class);
    Mockito.when(train.observeLiveRailFootprint())
        .thenReturn(LiveRailFootprintObservation.available(java.util.Set.of(cell)));
    return train;
  }

  private static RailGraph interlockingGraph() {
    org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId edgeId =
        org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId.undirected(A, B);
    org.fetarute.fetaruteTCAddon.dispatcher.graph.RailEdge edge =
        new org.fetarute.fetaruteTCAddon.dispatcher.graph.RailEdge(
            edgeId, A, B, 10, 0.0, true, java.util.Optional.empty());
    org.fetarute.fetaruteTCAddon.dispatcher.graph.interlocking.RailInterlockingState state =
        org.fetarute.fetaruteTCAddon.dispatcher.graph.interlocking.RailInterlockingState.from(
            java.util.UUID.randomUUID(),
            java.util.Set.of(edgeId),
            java.util.Map.of(
                edgeId,
                new org.fetarute.fetaruteTCAddon.dispatcher.graph.interlocking.RailEdgeFootprint(
                    1, true, java.util.Set.of(CELL_ON_EDGE))));
    return new org.fetarute.fetaruteTCAddon.dispatcher.graph.SimpleRailGraph(
        java.util.Map.of(A, waypoint(A), B, waypoint(B)),
        java.util.Map.of(edgeId, edge),
        java.util.Set.of(),
        state);
  }

  private static org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode waypoint(NodeId id) {
    return new org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode() {
      @Override
      public NodeId id() {
        return id;
      }

      @Override
      public org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType type() {
        return org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType.WAYPOINT;
      }

      @Override
      public org.bukkit.util.Vector worldPosition() {
        return new org.bukkit.util.Vector(0, 64, 0);
      }

      @Override
      public java.util.Optional<String> trainCartsDestination() {
        return java.util.Optional.empty();
      }
    };
  }
}
