package org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 占用判定结果：允许进入或需等待，并提供阻塞信息。
 *
 * <p>signal 用于把等待时间映射为运行许可信号（Proceed/Caution/Stop）。
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
