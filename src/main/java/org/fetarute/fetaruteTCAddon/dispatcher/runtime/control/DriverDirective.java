package org.fetarute.fetaruteTCAddon.dispatcher.runtime.control;

import java.util.Objects;
import java.util.OptionalLong;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.StopControlMode;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspect;

/**
 * 调度层交给驾驶员的一次控车决定：自动运行下会直接落到 TrainCarts 的那些量。
 *
 * @param aspect 信号
 * @param stopMode STOP 信号的落地模式
 * @param requestedBps 调度层给出的目标速度（格/秒）
 * @param permittedBps 执行层速度曲线处理后的容许速度（格/秒），即自动运行下会写入的限速
 * @param allowLaunch 是否允许静止列车起步（不是停车信号即允许）
 * @param distanceBlocks 到约束点（停车点、授权末端）的距离；没有时为空
 * @param envelope 随距离收紧的速度包络，从下发时的车头量起；没有时为 {@code null}
 */
public record DriverDirective(
    SignalAspect aspect,
    StopControlMode stopMode,
    double requestedBps,
    double permittedBps,
    boolean allowLaunch,
    OptionalLong distanceBlocks,
    SpeedEnvelope envelope) {

  public DriverDirective {
    Objects.requireNonNull(aspect, "aspect");
    stopMode = stopMode == null ? StopControlMode.BRAKING_TO_PLANNED_STOP : stopMode;
    distanceBlocks = distanceBlocks == null ? OptionalLong.empty() : distanceBlocks;
  }

  /** 是否为停车信号。 */
  public boolean isStop() {
    return aspect == SignalAspect.STOP;
  }
}
