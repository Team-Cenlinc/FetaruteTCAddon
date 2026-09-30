package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import java.util.List;
import java.util.OptionalDouble;

/** 根据 TrainCarts 车体与轮轴几何计算实时轨道足迹的保守端部余量。 */
public final class PhysicalRailFootprintPolicy {

  private static final double BLOCK_BOUNDARY_PADDING = 1.0;

  /** 每个车钩连接处的曲线弦长余量：相邻两节车中心在曲线上的直线距离短于轨道弧长。 */
  private static final double CURVE_PADDING_PER_GAP_BLOCKS = 0.25;

  /** 每节车的最小长度托底：原版矿车模型不到一格，仍按两格算。 */
  private static final double MIN_LENGTH_PER_MEMBER_BLOCKS = 2.0;

  private static final double DISTANCE_EPSILON = 1.0e-9;

  private PhysicalRailFootprintPolicy() {}

  /**
   * 计算从前后轮投影继续延伸到完整车体端部所需的对称余量。
   *
   * <p>TrainCarts 允许把 {@code cartLength}
   * 配置为大于普通矿车的值，也允许轮距小于车体长度。因此不能用固定一格代替车体端部；结果在真实轮轴到端部距离之外再保留一格边界余量，覆盖方块离散与模型边界误差。任何非有限、负数或轮轴超出半车长的矛盾模型都返回空值。
   *
   * @param cartLength 完整物理车体长度
   * @param frontWheelDistance 前轮到车体中心的路径距离
   * @param backWheelDistance 后轮到车体中心的路径距离
   * @return 每一端需要增加的保守距离；模型证据无效时为空
   */
  public static OptionalDouble requiredEndPaddingBlocks(
      double cartLength, double frontWheelDistance, double backWheelDistance) {
    if (!Double.isFinite(cartLength)
        || !Double.isFinite(frontWheelDistance)
        || !Double.isFinite(backWheelDistance)
        || cartLength < 0.0
        || frontWheelDistance < 0.0
        || backWheelDistance < 0.0) {
      return OptionalDouble.empty();
    }
    double halfLength = cartLength / 2.0;
    if (frontWheelDistance > halfLength + DISTANCE_EPSILON
        || backWheelDistance > halfLength + DISTANCE_EPSILON) {
      return OptionalDouble.empty();
    }
    double endOverhang = Math.max(halfLength - frontWheelDistance, halfLength - backWheelDistance);
    double padding = BLOCK_BOUNDARY_PADDING + Math.max(0.0, endOverhang);
    return Double.isFinite(padding) ? OptionalDouble.of(padding) : OptionalDouble.empty();
  }

  /**
   * 计算从车体中心分别向轨道两端行走到完整车体边界所需的距离。
   *
   * @param cartLength 完整物理车体长度
   * @return 半车长加一格离散边界余量；证据无效时为空
   */
  public static OptionalDouble requiredCenterWalkDistanceBlocks(double cartLength) {
    if (!Double.isFinite(cartLength) || cartLength < 0.0) {
      return OptionalDouble.empty();
    }
    double distance = (cartLength / 2.0) + BLOCK_BOUNDARY_PADDING;
    return Double.isFinite(distance) ? OptionalDouble.of(distance) : OptionalDouble.empty();
  }

  /**
   * 由各节车中心的实测跨度与每节车的车体长度，算整列车长的保守估计。
   *
   * <p>中心点跨度只量到首尾两节车的中心，两端各还有半个车体。TrainCarts 的模型车可以长到十格，不能用固定余量代替： 若两端合计只补两格，三节约十格的车（车体 30.5
   * 格）会被估成约 23 格，列尾有六七格落在保护之外。每端改按 {@link #requiredCenterWalkDistanceBlocks}（半车长 +
   * 一格边界余量），每个连接处再加曲线弦长余量； 另以车体长度之和与每节两格两者中的较大者托底（曲线上中心跨度可能偏短）。
   *
   * @param centerSpanBlocks 相邻两节车中心距离之和（L1 距离，曲线/坡道上偏大）
   * @param cartLengths 每节车的完整车体长度，按编组从头到尾
   * @return 保守车长；跨度或任一车体长度无效、或没有车时为空（调用方按车长未知处理，不缩短防护）
   */
  public static OptionalDouble conservativeTrainLengthBlocks(
      double centerSpanBlocks, List<Double> cartLengths) {
    if (!Double.isFinite(centerSpanBlocks)
        || centerSpanBlocks < 0.0
        || cartLengths == null
        || cartLengths.isEmpty()) {
      return OptionalDouble.empty();
    }
    double totalCartLength = 0.0;
    for (Double cartLength : cartLengths) {
      if (cartLength == null || !Double.isFinite(cartLength) || cartLength < 0.0) {
        return OptionalDouble.empty();
      }
      totalCartLength += cartLength;
    }
    OptionalDouble headEnd = requiredCenterWalkDistanceBlocks(cartLengths.get(0));
    OptionalDouble tailEnd =
        requiredCenterWalkDistanceBlocks(cartLengths.get(cartLengths.size() - 1));
    if (headEnd.isEmpty() || tailEnd.isEmpty()) {
      return OptionalDouble.empty();
    }
    int gaps = cartLengths.size() - 1;
    double positionEstimate =
        centerSpanBlocks
            + headEnd.getAsDouble()
            + tailEnd.getAsDouble()
            + gaps * CURVE_PADDING_PER_GAP_BLOCKS;
    double floor = Math.max(totalCartLength, cartLengths.size() * MIN_LENGTH_PER_MEMBER_BLOCKS);
    double estimate = Math.max(positionEstimate, floor);
    return Double.isFinite(estimate) && estimate > 0.0
        ? OptionalDouble.of(estimate)
        : OptionalDouble.empty();
  }
}
