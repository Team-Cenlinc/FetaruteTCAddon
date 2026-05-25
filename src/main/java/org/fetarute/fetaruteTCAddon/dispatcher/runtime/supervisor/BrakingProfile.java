package org.fetarute.fetaruteTCAddon.dispatcher.runtime.supervisor;

/**
 * 制动预判结果。
 *
 * <p>调度层使用该结果把“前方将出现风险”转换为提前 CAUTION 与目标限速，而不是等到边界才从 PROCEED 直接跳 STOP。计算是确定性的物理包络，不依赖机器学习或
 * previousAspect 防抖。
 */
public record BrakingProfile(
    double currentSpeedBps,
    double targetSpeedBps,
    long distanceToTargetBlocks,
    double brakingDistanceBlocks,
    RiskSource riskSource,
    String targetSpeedReason,
    boolean shouldApplySpeedLimit,
    boolean shouldHardStop) {

  public BrakingProfile {
    currentSpeedBps =
        Double.isFinite(currentSpeedBps) && currentSpeedBps > 0.0 ? currentSpeedBps : 0.0;
    targetSpeedBps = Double.isFinite(targetSpeedBps) && targetSpeedBps > 0.0 ? targetSpeedBps : 0.0;
    distanceToTargetBlocks = Math.max(0L, distanceToTargetBlocks);
    brakingDistanceBlocks =
        Double.isFinite(brakingDistanceBlocks) && brakingDistanceBlocks > 0.0
            ? brakingDistanceBlocks
            : 0.0;
    riskSource = riskSource == null ? RiskSource.NONE : riskSource;
    targetSpeedReason =
        targetSpeedReason == null || targetSpeedReason.isBlank()
            ? "none"
            : targetSpeedReason.trim();
  }
}
