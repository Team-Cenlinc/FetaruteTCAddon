package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class PhysicalRailFootprintPolicyTest {

  @Test
  void longCartWithCenteredWheelsKeepsItsHalfBodyAndBoundaryPadding() {
    assertEquals(
        6.0,
        PhysicalRailFootprintPolicy.requiredEndPaddingBlocks(10.0, 0.0, 0.0).orElseThrow(),
        1.0e-12);
  }

  @Test
  void wheelsAtBodyEndsStillKeepOneBlockBoundaryPadding() {
    assertEquals(
        1.0,
        PhysicalRailFootprintPolicy.requiredEndPaddingBlocks(10.0, 5.0, 5.0).orElseThrow(),
        1.0e-12);
  }

  @Test
  void longCartCenterWalkCoversHalfBodyAndBoundaryPadding() {
    assertEquals(
        6.0,
        PhysicalRailFootprintPolicy.requiredCenterWalkDistanceBlocks(10.0).orElseThrow(),
        1.0e-12);
  }

  @Test
  void inconsistentOrNonFinitePhysicalModelFailsClosed() {
    assertTrue(PhysicalRailFootprintPolicy.requiredEndPaddingBlocks(2.0, 1.1, 0.0).isEmpty());
    assertTrue(
        PhysicalRailFootprintPolicy.requiredEndPaddingBlocks(Double.NaN, 0.0, 0.0).isEmpty());
    assertTrue(
        PhysicalRailFootprintPolicy.requiredCenterWalkDistanceBlocks(Double.POSITIVE_INFINITY)
            .isEmpty());
  }

  /**
   * 长模型车：两端按真实半车长补足，估算不小于实际车体。
   *
   * <p>几何照实服 MT 的 TrainCarts 存档 {@code SUR100_test}：三节车体 10.0 / 9.6 / 9.95 格，车钩间隙 0.5 格， 直线上相邻中心距
   * 10.3 与 10.275，实际车体 30.55 格。以前两端合计只补两格，估成约 23 格，列尾六七格落在保护之外。
   */
  @Test
  void longModelCartsAreNotUnderestimated() {
    double actualBody = 10.0 + 9.6 + 9.95 + 2 * 0.5;

    double estimate =
        PhysicalRailFootprintPolicy.conservativeTrainLengthBlocks(
                10.3 + 10.275, List.of(10.0, 9.6, 9.95))
            .orElseThrow();

    assertTrue(estimate >= actualBody, () -> "estimate=" + estimate + " actual=" + actualBody);
    assertEquals(20.575 + (5.0 + 1.0) + (4.975 + 1.0) + 2 * 0.25, estimate, 1.0e-9);
  }

  /** 原版矿车（车体不到一格）仍按每节两格托底。 */
  @Test
  void shortVanillaCartsKeepThePerMemberFloor() {
    double estimate =
        PhysicalRailFootprintPolicy.conservativeTrainLengthBlocks(0.0, List.of(0.98, 0.98, 0.98))
            .orElseThrow();

    assertEquals(6.0, estimate, 1.0e-9);
  }

  /** 读不到可信的跨度或车体长度时返回空：调用方按车长未知处理，保留全部后向路径。 */
  @Test
  void invalidSpanOrCartLengthFailsClosed() {
    assertTrue(
        PhysicalRailFootprintPolicy.conservativeTrainLengthBlocks(10.0, List.of()).isEmpty());
    assertTrue(
        PhysicalRailFootprintPolicy.conservativeTrainLengthBlocks(Double.NaN, List.of(10.0))
            .isEmpty());
    assertTrue(
        PhysicalRailFootprintPolicy.conservativeTrainLengthBlocks(-1.0, List.of(10.0)).isEmpty());
    assertTrue(
        PhysicalRailFootprintPolicy.conservativeTrainLengthBlocks(
                10.0, Arrays.asList(10.0, Double.NaN))
            .isEmpty());
    assertTrue(
        PhysicalRailFootprintPolicy.conservativeTrainLengthBlocks(10.0, Arrays.asList(10.0, null))
            .isEmpty());
  }
}
