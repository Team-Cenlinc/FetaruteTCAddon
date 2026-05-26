package org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy;

import java.util.Locale;
import java.util.Objects;

/**
 * Advisory lookahead 只读风险。
 *
 * <p>该对象只描述“前方可能需要黄灯提示”的事实，不代表当前 hard authority 被拒绝，也不得触发 acquire、queue 或 release。
 */
public record AdvisoryRisk(
    OccupancyResource resource, OccupancyClaim claim, AdvisoryRiskSource source, String reason) {

  public AdvisoryRisk {
    Objects.requireNonNull(resource, "resource");
    Objects.requireNonNull(claim, "claim");
    Objects.requireNonNull(source, "source");
    reason =
        reason == null || reason.isBlank() ? source.name().toLowerCase(Locale.ROOT) : reason.trim();
  }
}
