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
}
