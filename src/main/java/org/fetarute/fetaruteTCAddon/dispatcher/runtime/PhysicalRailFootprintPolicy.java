package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import java.util.OptionalDouble;

/** 根据 TrainCarts 车体与轮轴几何计算实时轨道足迹的保守端部余量。 */
public final class PhysicalRailFootprintPolicy {

  private static final double BLOCK_BOUNDARY_PADDING = 1.0;
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
}
