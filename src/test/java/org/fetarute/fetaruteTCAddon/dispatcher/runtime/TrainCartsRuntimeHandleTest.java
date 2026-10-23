package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import org.bukkit.block.BlockFace;
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

  /** 车头不在寻路节点上：不折返的一头里取走到节点加寻路距离最短的那个方向，寻不到路的一头不算。 */
  @Test
  @DisplayName("车头不在寻路节点上：不折返的一头里取总距离最短的")
  void launchHeadsForTheNearestNodeOnTheShortestOnwardRoute() {
    TrainCartsRuntimeHandle.SearchedRoute ahead = route(BlockFace.EAST, 23.0, 180.0, false);
    TrainCartsRuntimeHandle.SearchedRoute behind = route(BlockFace.WEST, 42.0, 300.0, false);
    TrainCartsRuntimeHandle.SearchedRoute lost =
        new TrainCartsRuntimeHandle.SearchedRoute(
            BlockFace.NORTH, 5.0, OptionalDouble.empty(), false);

    assertEquals(
        Optional.of(BlockFace.EAST),
        TrainCartsRuntimeHandle.pickSearchedDirection(List.of(behind, lost, ahead)));
    assertTrue(
        TrainCartsRuntimeHandle.pickSearchedDirection(List.of(lost)).isEmpty(), "哪头都寻不到路：交给回退判定");
  }

  /** 停在站台上、站点节点在车头身后（车身底下），前方 64 格内没有节点：只找到身后那个，从它去目的地要原路穿过车身往前——往前开，不往后开。 */
  @Test
  @DisplayName("只找到身后要折返的节点：往它的反方向开")
  void aNodeBehindWhoseRouteTurnsBackSendsTheTrainForward() {
    assertEquals(
        Optional.of(BlockFace.NORTH),
        TrainCartsRuntimeHandle.pickSearchedDirection(
            List.of(route(BlockFace.SOUTH, 9.0, 87.0, true))));
    assertEquals(
        Optional.of(BlockFace.NORTH),
        TrainCartsRuntimeHandle.pickSearchedDirection(
            List.of(
                route(BlockFace.SOUTH, 9.0, 87.0, true),
                route(BlockFace.NORTH, 60.0, 30.0, false))),
        "前方也找到不折返的节点：照常往前");
  }

  /** 走进节点经过的接口：直轨上是身后那头，进道岔时是进岔那头（往北的岔线不是进来的接口）。 */
  @Test
  @DisplayName("走进节点经过的接口取朝外方向与行进方向最相反的那个")
  void theEnteredJunctionIsTheOneFacingAgainstTheMotion() {
    List<TrainCartsRuntimeHandle.JunctionFace> straight =
        List.of(
            new TrainCartsRuntimeHandle.JunctionFace("n", 0, -1),
            new TrainCartsRuntimeHandle.JunctionFace("s", 0, 1));
    assertEquals(Optional.of("n"), TrainCartsRuntimeHandle.enteredThrough(straight, 0, 1), "往南走进来");

    List<TrainCartsRuntimeHandle.JunctionFace> switcher =
        List.of(
            new TrainCartsRuntimeHandle.JunctionFace("w", -1, 0),
            new TrainCartsRuntimeHandle.JunctionFace("e", 1, 0),
            new TrainCartsRuntimeHandle.JunctionFace("n", 0, -1));
    assertEquals(Optional.of("w"), TrainCartsRuntimeHandle.enteredThrough(switcher, 1, 0), "往东进岔");
    assertTrue(TrainCartsRuntimeHandle.enteredThrough(straight, 0, 0).isEmpty(), "没有行进方向：读不出");
  }

  private static TrainCartsRuntimeHandle.SearchedRoute route(
      BlockFace direction, double walked, double routed, boolean reverses) {
    return new TrainCartsRuntimeHandle.SearchedRoute(
        direction, walked, OptionalDouble.of(routed), reverses);
  }
}
