package org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 占用判定结果：允许进入或阻塞，并提供阻塞信息。
 *
 * <p>signal 用于给运行时层决策限速/停车；earliestTime 仅作诊断提示。
 *
 * <p>当 allowed=true 且 blockers 非空时，通常代表“冲突区放行”的特殊判定。此时 {@code conflictRelease=true}
 * 表示占用管理器已确认这是对向冲突释放，调用方可在不污染 blocker 资源的前提下继续放行。
 */
public record OccupancyDecision(
    boolean allowed,
    Instant earliestTime,
    SignalAspect signal,
    List<OccupancyClaim> blockers,
    boolean conflictRelease,
    String reason) {

  public OccupancyDecision(
      boolean allowed,
      Instant earliestTime,
      SignalAspect signal,
      List<OccupancyClaim> blockers,
      boolean conflictRelease) {
    this(allowed, earliestTime, signal, blockers, conflictRelease, "none");
  }

  public OccupancyDecision(
      boolean allowed, Instant earliestTime, SignalAspect signal, List<OccupancyClaim> blockers) {
    this(allowed, earliestTime, signal, blockers, false, "none");
  }

  public OccupancyDecision {
    Objects.requireNonNull(earliestTime, "earliestTime");
    Objects.requireNonNull(signal, "signal");
    Objects.requireNonNull(blockers, "blockers");
    blockers = List.copyOf(blockers);
    reason = reason == null || reason.isBlank() ? "none" : reason.trim();
  }
}
