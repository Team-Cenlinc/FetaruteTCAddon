package org.fetarute.fetaruteTCAddon.dispatcher.runtime.supervisor;

import java.util.OptionalLong;

/**
 * 单列车前方风险快照。
 *
 * <p>所有距离均为从当前调度起点到风险边界的方块距离。缺失值使用 {@link OptionalLong#empty()}，禁止用 0 或 -1 表示未知，避免 unknown risk
 * 被误当成已经贴边的 hard stop。
 */
public record ForwardSignalRiskSnapshot(
    String trainId,
    OptionalLong nearestHardBlockerDistance,
    OptionalLong nearestCautionDistance,
    OptionalLong nearestStopDistance,
    OptionalLong nearestSpeedDropDistance,
    OptionalLong nearestStationStopDistance,
    OptionalLong nearestTerminalDistance,
    OptionalLong nearestSingleConflictRiskDistance,
    OptionalLong nearestSwitcherRiskDistance,
    OptionalLong authorityPhysicalEndDistance,
    RiskSource riskSource,
    RiskFreshness riskFreshness,
    String blockerTrainId,
    String blockerResourceId,
    boolean canDrain,
    boolean canRelease,
    boolean canWait,
    boolean canUnlock,
    boolean canDestroy) {

  public ForwardSignalRiskSnapshot {
    trainId = normalize(trainId, "-");
    nearestHardBlockerDistance = safe(nearestHardBlockerDistance);
    nearestCautionDistance = safe(nearestCautionDistance);
    nearestStopDistance = safe(nearestStopDistance);
    nearestSpeedDropDistance = safe(nearestSpeedDropDistance);
    nearestStationStopDistance = safe(nearestStationStopDistance);
    nearestTerminalDistance = safe(nearestTerminalDistance);
    nearestSingleConflictRiskDistance = safe(nearestSingleConflictRiskDistance);
    nearestSwitcherRiskDistance = safe(nearestSwitcherRiskDistance);
    authorityPhysicalEndDistance = safe(authorityPhysicalEndDistance);
    riskSource = riskSource == null ? RiskSource.NONE : riskSource;
    riskFreshness = riskFreshness == null ? RiskFreshness.UNKNOWN : riskFreshness;
    blockerTrainId = normalize(blockerTrainId, "-");
    blockerResourceId = normalize(blockerResourceId, "-");
  }

  /** 无风险快照。 */
  public static ForwardSignalRiskSnapshot none(String trainId) {
    return new ForwardSignalRiskSnapshot(
        trainId,
        OptionalLong.empty(),
        OptionalLong.empty(),
        OptionalLong.empty(),
        OptionalLong.empty(),
        OptionalLong.empty(),
        OptionalLong.empty(),
        OptionalLong.empty(),
        OptionalLong.empty(),
        OptionalLong.empty(),
        RiskSource.NONE,
        RiskFreshness.LIVE,
        "-",
        "-",
        false,
        false,
        true,
        false,
        false);
  }

  /** 返回所有 stop/caution 风险中最近的距离。 */
  public OptionalLong nearestPlanningDistance() {
    long min = Long.MAX_VALUE;
    min = min(min, nearestHardBlockerDistance);
    min = min(min, nearestCautionDistance);
    min = min(min, nearestStopDistance);
    min = min(min, nearestSpeedDropDistance);
    min = min(min, nearestStationStopDistance);
    min = min(min, nearestTerminalDistance);
    min = min(min, nearestSingleConflictRiskDistance);
    min = min(min, nearestSwitcherRiskDistance);
    min = min(min, authorityPhysicalEndDistance);
    return min == Long.MAX_VALUE ? OptionalLong.empty() : OptionalLong.of(min);
  }

  private static long min(long current, OptionalLong candidate) {
    return candidate != null && candidate.isPresent()
        ? Math.min(current, candidate.getAsLong())
        : current;
  }

  private static OptionalLong safe(OptionalLong value) {
    if (value == null || value.isEmpty()) {
      return OptionalLong.empty();
    }
    return OptionalLong.of(Math.max(0L, value.getAsLong()));
  }

  private static String normalize(String raw, String fallback) {
    if (raw == null || raw.isBlank()) {
      return fallback;
    }
    return raw.trim();
  }
}
