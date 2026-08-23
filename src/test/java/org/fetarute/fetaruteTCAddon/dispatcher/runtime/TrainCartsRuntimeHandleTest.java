package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.explore.RailBlockPos;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.interlocking.RailFootprintCell;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** {@link TrainCartsRuntimeHandle} 的实时轨道足迹回归测试。 */
@DisplayName("TrainCartsRuntimeHandle 实时轨道足迹")
class TrainCartsRuntimeHandleTest {

  @Test
  @DisplayName("完整 tracked rail block 证据应在 RailPath 不可用时保守覆盖全部方块")
  void completeTrackedRailBlocksProvidePhysicalFootprintWhenRailPathsAreUnavailable() {
    LiveRailFootprintObservation observation =
        TrainCartsRuntimeHandle.trackedRailBlockFootprint(
            List.of(
                new RailBlockPos(502, 74, 996),
                new RailBlockPos(501, 74, 996),
                new RailBlockPos(502, 74, 996)));

    assertTrue(observation.available());
    assertEquals(
        Set.of(new RailFootprintCell(501, 74, 996), new RailFootprintCell(502, 74, 996)),
        observation.cells().orElseThrow());
  }

  @Test
  @DisplayName("缺失 tracked rail block 不能伪造物理清空证明")
  void missingTrackedRailBlocksRemainFailClosed() {
    LiveRailFootprintObservation observation =
        TrainCartsRuntimeHandle.trackedRailBlockFootprint(List.of());

    assertFalse(observation.available());
    assertEquals(
        LiveRailFootprintObservation.FailureReason.FOOTPRINT_EMPTY, observation.failureReason());
  }
}
