package org.fetarute.fetaruteTCAddon.dispatcher.health;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import org.fetarute.fetaruteTCAddon.company.model.RouteOperationType;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchService;

/**
 * 长时间停滞列车的保守清理策略。
 *
 * <p>本类只排序已经由健康监控取证的候选，不读取运行时状态，也不执行销毁。普通等待列车仍交给 Gate Queue；只有恢复链已耗尽、没有新鲜外部
 * blocker、且不属于受控停车的列车才可进入候选集。
 *
 * <p>排序首先保护有玩家乘坐的列车，然后优先回收返库/出库、Depot
 * 相关且尚未接近终点的空车。这样可以在不写线路或站点特判的前提下，让占用关键进路的废弃车先退出，同时把载客列车保留为最后手段。
 */
final class StuckTrainCleanupPolicy {

  private static final double MOVING_SPEED_THRESHOLD_BPT = 0.01;

  private static final Comparator<Candidate> CLEANUP_ORDER =
      Comparator.comparing((Candidate candidate) -> candidate.context().hasPassengers())
          .thenComparingInt(candidate -> operationRank(candidate.context().operationType()))
          .thenComparing(candidate -> !candidate.context().depotRelated())
          .thenComparing(candidate -> candidate.context().nearRouteEnd())
          .thenComparingInt(candidate -> candidate.context().priority())
          .thenComparingInt(candidate -> candidate.context().progressIndex())
          .thenComparing(candidate -> candidate.context().trainName().toLowerCase(Locale.ROOT));

  private StuckTrainCleanupPolicy() {}

  /** 返回当前批次中最适合先清理的一列车。 */
  static Optional<Candidate> select(
      List<Candidate> candidates, Duration emptyThreshold, Duration passengerThreshold) {
    if (candidates == null || candidates.isEmpty()) {
      return Optional.empty();
    }
    return candidates.stream()
        .filter(
            candidate ->
                eligibility(candidate, emptyThreshold, passengerThreshold) == Eligibility.ELIGIBLE)
        .min(CLEANUP_ORDER);
  }

  /**
   * 返回候选的纯策略审查结果。
   *
   * <p>执行前仍必须由 Smart Dispatcher 使用最新运行时 group、速度、乘客与 hold 状态做第二次审查。
   */
  static Eligibility eligibility(
      Candidate candidate, Duration emptyThreshold, Duration passengerThreshold) {
    if (candidate == null || candidate.context().trainName().isBlank()) {
      return Eligibility.CONTEXT_MISSING;
    }
    RuntimeDispatchService.DeadlockTrainContext context = candidate.context();
    if (!candidate.recoveryExhausted()) {
      return Eligibility.RECOVERY_NOT_EXHAUSTED;
    }
    if (context.speedBlocksPerTick() > MOVING_SPEED_THRESHOLD_BPT) {
      return Eligibility.TRAIN_MOVING;
    }
    if (context.dwelling()
        || context.departureGateHeld()
        || context.layoverReady()
        || context.manualHold()) {
      return Eligibility.CONTROLLED_STOP;
    }
    if (candidate.waitingOnLiveBlocker()) {
      return Eligibility.WAITING_ON_LIVE_BLOCKER;
    }
    Duration normalThreshold = nonNegative(emptyThreshold);
    Duration protectedThreshold = max(normalThreshold, nonNegative(passengerThreshold));
    Duration required = context.hasPassengers() ? protectedThreshold : normalThreshold;
    if (candidate.stuckDuration().compareTo(required) < 0) {
      return context.hasPassengers()
          ? Eligibility.PASSENGER_GRACE
          : Eligibility.CLEANUP_THRESHOLD_NOT_REACHED;
    }
    return Eligibility.ELIGIBLE;
  }

  /** 纯策略审查结果；{@link #reason()} 用于稳定的运行时诊断。 */
  enum Eligibility {
    ELIGIBLE("eligible"),
    CONTEXT_MISSING("context-missing"),
    RECOVERY_NOT_EXHAUSTED("recovery-not-exhausted"),
    TRAIN_MOVING("train-moving"),
    CONTROLLED_STOP("controlled-stop"),
    WAITING_ON_LIVE_BLOCKER("waiting-on-live-blocker"),
    PASSENGER_GRACE("passenger-grace"),
    CLEANUP_THRESHOLD_NOT_REACHED("cleanup-threshold-not-reached");

    private final String reason;

    Eligibility(String reason) {
      this.reason = reason;
    }

    String reason() {
      return reason;
    }
  }

  private static int operationRank(RouteOperationType operationType) {
    if (operationType == RouteOperationType.RETURN) {
      return 0;
    }
    if (operationType == RouteOperationType.CREATE) {
      return 1;
    }
    return 2;
  }

  private static Duration nonNegative(Duration duration) {
    return duration == null || duration.isNegative() ? Duration.ZERO : duration;
  }

  private static Duration max(Duration first, Duration second) {
    return first.compareTo(second) >= 0 ? first : second;
  }

  /** 一次健康采样形成的清理候选。 */
  record Candidate(
      RuntimeDispatchService.DeadlockTrainContext context,
      Duration stuckDuration,
      boolean recoveryExhausted,
      boolean waitingOnLiveBlocker) {

    Candidate {
      Objects.requireNonNull(context, "context");
      stuckDuration = nonNegative(stuckDuration);
    }
  }
}
