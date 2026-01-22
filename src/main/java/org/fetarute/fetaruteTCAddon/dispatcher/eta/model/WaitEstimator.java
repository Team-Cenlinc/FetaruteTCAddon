package org.fetarute.fetaruteTCAddon.dispatcher.eta.model;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.EtaReason;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.HeadwayRule;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyDecision;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyQueueEntry;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyQueueSnapshot;

/**
 * 等待时间估算器。
 *
 * <p>需求点：用“queuePos × intervalSec/批次切换惩罚”估算分钟级 delay，且不依赖 earliestTime。
 *
 * <p>实现策略（MVP+）：
 *
 * <ul>
 *   <li>若 decision.allowed=true：wait=0
 *   <li>若 decision.allowed=false：
 *       <ul>
 *         <li>优先使用占用队列快照定位本车在队列中的位置 queuePos（从 0 开始）
 *         <li>intervalSec 来自 headwayRule（按资源返回 headway）
 *         <li>若方向锁切换（批次切换）可引入固定惩罚 switchPenaltySec
 *       </ul>
 * </ul>
 *
 * <p>注意：此估算故意保守，避免 UI 频繁抖动；仅用于 HUD/PIDS 的“分钟级延误提示”。
 */
public final class WaitEstimator {

  public record WaitEstimate(int waitSec, List<EtaReason> reasons) {
    public WaitEstimate {
      reasons = reasons == null ? List.of() : List.copyOf(reasons);
    }
  }

  private final HeadwayRule headwayRule;
  private final int switchPenaltySec;

  public WaitEstimator(HeadwayRule headwayRule, int switchPenaltySec) {
    this.headwayRule = Objects.requireNonNull(headwayRule, "headwayRule");
    this.switchPenaltySec = Math.max(0, switchPenaltySec);
  }

  /**
   * 估算等待秒数。
   *
   * @param trainName 列车名
   * @param decision canEnter/acquire 的结果
   * @param queueSnapshot 可选：若提供则尝试计算 queuePos
   * @param now 当前时间（用于计算 earliestTime 的差值作为下限保护）
   */
  public WaitEstimate estimate(
      String trainName,
      OccupancyDecision decision,
      Optional<OccupancyQueueSnapshot> queueSnapshot,
      Instant now) {
    if (decision == null || decision.allowed()) {
      return new WaitEstimate(0, List.of());
    }

    int queuePos = queueSnapshot.flatMap(s -> findQueuePos(trainName, s)).orElse(0);
    Duration headway = Duration.ZERO;
    if (queueSnapshot.isPresent()) {
      headway = headwayRule.headwayFor(Optional.empty(), queueSnapshot.get().resource());
    }

    long intervalSec = Math.max(0L, headway.getSeconds());
    long est = queuePos * intervalSec;

    // 批次切换惩罚：在队列非空且不是队头时，假设需要一次方向切换/放行窗口。
    if (queuePos > 0 && switchPenaltySec > 0) {
      est += switchPenaltySec;
    }

    // 用 earliestTime 作为“下限保护”，但不作为主要来源（避免 earliest 抖动）。
    Instant t = now != null ? now : Instant.now();
    long lowerBound = 0L;
    if (decision.earliestTime() != null) {
      lowerBound = Math.max(0L, Duration.between(t, decision.earliestTime()).getSeconds());
    }
    long waitSec = Math.max(est, lowerBound);
    if (waitSec > Integer.MAX_VALUE) {
      waitSec = Integer.MAX_VALUE;
    }

    List<EtaReason> reasons = new ArrayList<>();
    reasons.add(EtaReason.WAIT);
    return new WaitEstimate((int) waitSec, reasons);
  }

  private Optional<Integer> findQueuePos(String trainName, OccupancyQueueSnapshot snapshot) {
    if (trainName == null || trainName.isBlank() || snapshot == null) {
      return Optional.empty();
    }
    List<OccupancyQueueEntry> entries = snapshot.entries();
    for (int i = 0; i < entries.size(); i++) {
      OccupancyQueueEntry e = entries.get(i);
      if (e != null && e.trainName().equalsIgnoreCase(trainName)) {
        return Optional.of(i);
      }
    }
    return Optional.empty();
  }
}
