package org.fetarute.fetaruteTCAddon.dispatcher.runtime.supervisor;

import java.util.OptionalLong;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspect;

/**
 * 单列车智能调度决策。
 *
 * <p>该记录是 Smart Dispatcher 与运行时控车之间的稳定边界。每个动作都必须解释安全原因与预期解锁效果， 便于日志回放与人工审计。
 */
public record DispatchDecision(
    String trainId,
    DispatchAction action,
    SignalAspect targetAspect,
    double targetSpeedBps,
    OptionalLong distanceToCaution,
    OptionalLong distanceToStop,
    String authorityEndReason,
    RiskSource riskSource,
    DispatchEffectClass effectClass,
    String priorityReason,
    String safetyReason,
    String expectedUnblockEffect,
    boolean reversible,
    boolean requiresPostVerification,
    BrakingProfile brakingProfile) {

  public DispatchDecision {
    trainId = trainId == null || trainId.isBlank() ? "-" : trainId.trim();
    action = action == null ? DispatchAction.NO_ACTION : action;
    targetAspect = targetAspect == null ? SignalAspect.STOP : targetAspect;
    targetSpeedBps = Double.isFinite(targetSpeedBps) && targetSpeedBps > 0.0 ? targetSpeedBps : 0.0;
    distanceToCaution = distanceToCaution == null ? OptionalLong.empty() : distanceToCaution;
    distanceToStop = distanceToStop == null ? OptionalLong.empty() : distanceToStop;
    authorityEndReason =
        authorityEndReason == null || authorityEndReason.isBlank()
            ? "none"
            : authorityEndReason.trim();
    riskSource = riskSource == null ? RiskSource.NONE : riskSource;
    effectClass = effectClass == null ? action.effectClass() : effectClass;
    priorityReason =
        priorityReason == null || priorityReason.isBlank() ? "none" : priorityReason.trim();
    safetyReason = safetyReason == null || safetyReason.isBlank() ? "none" : safetyReason.trim();
    expectedUnblockEffect =
        expectedUnblockEffect == null || expectedUnblockEffect.isBlank()
            ? "none"
            : expectedUnblockEffect.trim();
  }
}
