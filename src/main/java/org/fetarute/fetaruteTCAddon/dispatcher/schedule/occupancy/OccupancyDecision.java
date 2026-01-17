package org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 占用判定结果：允许进入或阻塞，并提供阻塞信息。
 *
 * <p>signal 用于给运行时层决策限速/停车；earliestTime 仅作诊断提示。
 */
public record OccupancyDecision(
    boolean allowed, Instant earliestTime, SignalAspect signal, List<OccupancyClaim> blockers) {

  public OccupancyDecision {
    Objects.requireNonNull(earliestTime, "earliestTime");
    Objects.requireNonNull(signal, "signal");
    Objects.requireNonNull(blockers, "blockers");
    blockers = List.copyOf(blockers);
  }
}
