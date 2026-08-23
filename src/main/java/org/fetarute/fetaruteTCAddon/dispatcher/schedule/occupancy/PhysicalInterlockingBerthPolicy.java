package org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy;

import java.util.OptionalDouble;
import java.util.OptionalLong;

/**
 * 物理联锁出口泊位距离策略。
 *
 * <p>该策略只负责把运行时车长证据转换为保守整数距离，并与已配置的停车净空组合。它不读取 Route、图或列车实体，因此普通轨道与 TCCoasters
 * 共用同一语义；调用方必须把缺失结果解释为无法证明出口泊位，而不是回退固定边数。
 */
public final class PhysicalInterlockingBerthPolicy {

  private PhysicalInterlockingBerthPolicy() {}

  /**
   * 把浮点车长证据向上取整为保守方块距离。
   *
   * @param estimatedTrainLengthBlocks 运行时估算的整列车长度
   * @return 有效正数的向上取整结果；缺失、非有限或非正数返回 empty
   */
  public static OptionalLong conservativeTrainLengthBlocks(
      OptionalDouble estimatedTrainLengthBlocks) {
    if (estimatedTrainLengthBlocks == null || estimatedTrainLengthBlocks.isEmpty()) {
      return OptionalLong.empty();
    }
    double value = estimatedTrainLengthBlocks.getAsDouble();
    if (!Double.isFinite(value) || value <= 0.0) {
      return OptionalLong.empty();
    }
    return OptionalLong.of(
        value >= Long.MAX_VALUE ? Long.MAX_VALUE : Math.max(1L, (long) Math.ceil(value)));
  }

  /**
   * 计算列车完全驶出联锁后仍需持有的最小泊位距离。
   *
   * @param estimatedTrainLengthBlocks 运行时估算的整列车长度
   * @param stopClearanceBlocks 配置的停车净空距离
   * @return 车长与净空的饱和和；车长证据无效时返回 empty
   */
  public static OptionalLong requiredExitBerthBlocks(
      OptionalDouble estimatedTrainLengthBlocks, long stopClearanceBlocks) {
    if (stopClearanceBlocks < 0L) {
      throw new IllegalArgumentException("stopClearanceBlocks 必须为非负数");
    }
    OptionalLong trainLength = conservativeTrainLengthBlocks(estimatedTrainLengthBlocks);
    if (trainLength.isEmpty()) {
      return OptionalLong.empty();
    }
    long lengthBlocks = trainLength.getAsLong();
    return OptionalLong.of(
        lengthBlocks > Long.MAX_VALUE - stopClearanceBlocks
            ? Long.MAX_VALUE
            : lengthBlocks + stopClearanceBlocks);
  }
}
