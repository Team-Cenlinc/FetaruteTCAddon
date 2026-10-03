package org.fetarute.fetaruteTCAddon.drive.session;

import java.util.OptionalDouble;
import java.util.function.DoubleConsumer;

/**
 * 驾驶期间对 TrainCarts 限速属性的跟踪。
 *
 * <p>接管时限速属性被抬到列车的最高速度（见 {@link TrainPropertyGuard}），之后牌子或命令再改它，就说明线路给出了新的限速，记作“线路限速”。
 * 允许超速时把属性写回接管时的值，免得 TrainCarts 硬截速度，线路限速只作提示；不允许超速时属性保持改动后的值，直接限制车速。 会话结束时应把线路限速留给列车，而不是退回上车前的旧值。
 *
 * <p>限速属性的单位是格/tick，速度上限与显示用格/秒，两者不直接比较。本类不依赖任何服务器对象。
 */
final class SpeedLimitTracker {

  private static final double TICKS_PER_SECOND = 20.0;

  /** 限速属性变化小于它（格/tick）视为没改动。 */
  private static final double CHANGE_EPS_BPT = 1.0e-9;

  private final boolean override;
  private double guardedBpt = Double.NaN;
  private double observedBpt = Double.NaN;

  /**
   * @param override 是否允许超过线路限速
   */
  SpeedLimitTracker(boolean override) {
    this.override = override;
  }

  /** 记下接管后限速属性的值（格/tick）；之后与它不同就算被改动。 */
  void guard(double propertyBpt) {
    this.guardedBpt = propertyBpt;
  }

  /**
   * 处理这一 tick 读到的限速属性。
   *
   * @param propertyBpt 当前限速属性（格/tick）
   * @param maxSpeedBps 列车最高速度（格/秒）
   * @param writeBackBpt 需要把限速属性写回时调用，参数单位为格/tick
   * @return 这一 tick 的速度上限（格/秒）
   */
  double onTick(double propertyBpt, double maxSpeedBps, DoubleConsumer writeBackBpt) {
    if (Double.isFinite(guardedBpt)
        && Double.isFinite(propertyBpt)
        && Math.abs(propertyBpt - guardedBpt) > CHANGE_EPS_BPT) {
      observedBpt = propertyBpt;
      if (override) {
        writeBackBpt.accept(guardedBpt);
      } else {
        guardedBpt = propertyBpt;
      }
    }
    if (override) {
      return maxSpeedBps;
    }
    return Math.min(maxSpeedBps, propertyBpt * TICKS_PER_SECOND);
  }

  /**
   * 动作栏上显示的限速（格/秒）。
   *
   * @param capBps 这一 tick 实际生效的速度上限（格/秒）
   * @return 允许超速时为线路限速，没有时为 {@code NaN}；否则为实际生效的上限
   */
  double displayLimitBps(double capBps) {
    return override ? observedBpt * TICKS_PER_SECOND : capBps;
  }

  /** 接管后线路给出的最近一个限速（格/tick）；会话结束时应还原成它。 */
  OptionalDouble observedLimitBpt() {
    return Double.isFinite(observedBpt) && observedBpt > 0.0
        ? OptionalDouble.of(observedBpt)
        : OptionalDouble.empty();
  }
}
