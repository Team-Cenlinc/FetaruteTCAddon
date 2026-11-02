package org.fetarute.fetaruteTCAddon.dispatcher.runtime.control;

import java.util.OptionalLong;
import org.fetarute.fetaruteTCAddon.config.ConfigManager;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.StopControlMode;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspect;

/**
 * 给人看的信号：停车信号在列车沿制动曲线刹向前方停车点、且剩余距离够平稳刹停时显示为 {@link SignalAspect#CAUTION}。
 *
 * <p>延伸授权被拒时列车多半仍握着前方一段授权，控车按 STOP 曲线刹到授权终点前（见 {@code HeldAuthorityBraking}）；这时的意思是“前方停车，现在开始减速”，
 * 对应黄灯，而不是“立即停车”。只改显示：控车灯仍是 STOP，起步许可、STOP 曲线、停车保持都按控车灯走。
 *
 * <p>以下照控车灯显示：硬停与停稳后的保持、没有停车点距离、列车已静止、速度曲线关闭（STOP 当拍停车）、剩余距离不够刹停。 剩余距离与 STOP
 * 速度曲线同一口径：先扣掉提前减速缓冲（{@code speed-curve-early-brake-blocks}），再与按车型减速度的制动距离比。
 */
public final class BrakingSignalDisplay {

  /** 判定列车仍在运动的最低速度（格/秒），与 STOP 曲线把速度判为 0 的阈值（0.001 格/tick）一致。 */
  private static final double MOVING_SPEED_EPSILON_BPS = 0.02;

  private BrakingSignalDisplay() {}

  /**
   * 显示用的信号。
   *
   * @param aspect 控车信号
   * @param stopMode STOP 信号的落地模式
   * @param distanceBlocks 到停车点的距离
   * @param speedBps 当前车速（格/秒）
   * @param decelBps2 车型减速度（格/秒²）
   * @param runtimeSettings 运行时配置：速度曲线开关与提前减速缓冲
   * @return 显示信号；不满足条件时就是 {@code aspect}
   */
  public static SignalAspect displayAspect(
      SignalAspect aspect,
      StopControlMode stopMode,
      OptionalLong distanceBlocks,
      double speedBps,
      double decelBps2,
      ConfigManager.RuntimeSettings runtimeSettings) {
    if (aspect != SignalAspect.STOP
        || runtimeSettings == null
        || !runtimeSettings.speedCurveEnabled()
        || stopMode == StopControlMode.HARD_STOP
        || stopMode == StopControlMode.DWELL_HOLD
        || distanceBlocks == null
        || distanceBlocks.isEmpty()
        || !(speedBps > MOVING_SPEED_EPSILON_BPS)
        || !(decelBps2 > 0.0)) {
      return aspect;
    }
    double brakingDistance = speedBps * speedBps / (2.0 * decelBps2);
    double usableDistance =
        distanceBlocks.getAsLong() - Math.max(0.0, runtimeSettings.speedCurveEarlyBrakeBlocks());
    return usableDistance >= brakingDistance ? SignalAspect.CAUTION : aspect;
  }
}
