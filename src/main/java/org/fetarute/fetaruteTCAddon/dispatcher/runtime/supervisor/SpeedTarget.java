package org.fetarute.fetaruteTCAddon.dispatcher.runtime.supervisor;

import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspect;

/**
 * 调度层给控车层的速度目标。
 *
 * <p>该记录不直接写 TrainCarts；它只把“目标信号、目标速度与原因”结构化，便于最终仍由
 * RuntimeDispatchService/SignalPublicationGate/RuntimeTrainController 落地。
 */
public record SpeedTarget(
    SignalAspect targetAspect,
    double targetSpeedBps,
    String targetSpeedReason,
    boolean shouldApplySpeedLimit,
    boolean shouldHardStop) {

  public SpeedTarget {
    targetAspect = targetAspect == null ? SignalAspect.STOP : targetAspect;
    targetSpeedBps = Double.isFinite(targetSpeedBps) && targetSpeedBps > 0.0 ? targetSpeedBps : 0.0;
    targetSpeedReason =
        targetSpeedReason == null || targetSpeedReason.isBlank()
            ? "none"
            : targetSpeedReason.trim();
  }

  /** 生成一个保持当前限速语义的空目标。 */
  public static SpeedTarget none(SignalAspect aspect) {
    return new SpeedTarget(aspect, 0.0, "none", false, false);
  }
}
