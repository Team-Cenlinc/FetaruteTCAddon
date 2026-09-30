package org.fetarute.fetaruteTCAddon.dispatcher.health;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.fetarute.fetaruteTCAddon.company.model.RouteOperationType;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchService;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.WaitCycleEvidence;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.TrainNameNormalizer;

/**
 * 长时间停滞列车的保守清理策略。
 *
 * <p>本类只排序已经由健康监控取证的候选，不读取运行时状态，也不执行销毁。普通等待列车仍交给 Gate Queue；只有恢复链已耗尽、没有新鲜外部
 * blocker、且不属于受控停车的列车才可进入候选集。
 *
 * <p>排序首先保护有玩家乘坐的列车，然后优先回收返库/出库、Depot
 * 相关且尚未接近终点的空车。这样可以在不写线路或站点特判的前提下，让占用关键进路的废弃车先退出，同时把载客列车保留为最后手段。
 *
 * <p><b>等待环</b>（{@link #waitCycleTargets}）：排队等前车的车永远算"还在等"，本来不进候选；但几列车首尾相接地互等时谁也等不到，
 * 环外又没有别的机制会动它们（互卡处理只认同一单线冲突上的对向配对）。所以本轮没有普通候选时，再看停滞车之间的等待关系：
 * 环上除了"在等"之外全部够格、且只在等环上车辆的车按同一排序清掉一列（载客车在环上时空车先清）。链尾是残留占用、待命车、受控停车或仍在运行的车时不成环， 一律不动——那些由各自的机制收拾。
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
   * 普通候选都不够格时，从停滞车互等形成的环里选一列清理。
   *
   * <p>等待图的节点是本轮全部停满清车阈值、记得在等谁、且静止的候选（载客宽限、恢复观察中的车也算：它们同样卡在环里），边指向它在等的车。
   * 不在环上的车所在的"环"只有它自己，它等的车不会在里面，自然被排除。 能清的只有环上除了"在等"之外全部够格的车，而且它等的每一列都在同一个环里——复审时运行时按同一口径核对（{@code
   * WaitCycleEvidence}）。环上的车同时还在等环外的车（待命车、残留占用）也不妨碍清掉环上另一列：环本身就是死结，环外那条等待另算。
   * 等着环上某列、自己却不在环上的车（环的尾巴）不清：清掉它解不开环；链尾在环外的等待链整条不动。
   *
   * <p>返回全部可清车、按清理顺序排好：运行时复审拒绝了前一列（它的 blocker 快照此刻又多了环外的车等），健康监控接着试下一列， 不会每轮卡在同一列上。
   *
   * @param candidates 本轮健康检查收集到的停滞候选
   * @param emptyThreshold 空车清理阈值
   * @param passengerThreshold 载客车清理阈值
   * @return 环上的可清车（连同各自所在的环），按清理顺序；没有时为空表
   */
  static List<WaitCycle> waitCycleTargets(
      List<Candidate> candidates, Duration emptyThreshold, Duration passengerThreshold) {
    if (candidates == null || candidates.isEmpty()) {
      return List.of();
    }
    Map<String, Candidate> graph = new LinkedHashMap<>();
    for (Candidate candidate : candidates) {
      if (candidate != null
          && !candidate.blockers().isEmpty()
          && candidate.context().speedBlocksPerTick() <= MOVING_SPEED_THRESHOLD_BPT) {
        graph.put(key(candidate.context().trainName()), candidate);
      }
    }
    return graph.values().stream()
        .filter(
            candidate ->
                eligibility(candidate, emptyThreshold, passengerThreshold)
                        == Eligibility.WAITING_ON_LIVE_BLOCKER
                    && eligibility(candidate.withoutWait(), emptyThreshold, passengerThreshold)
                        == Eligibility.ELIGIBLE)
        .map(candidate -> new WaitCycle(candidate, cycleOf(candidate, graph)))
        .filter(
            cycle ->
                WaitCycleEvidence.explainsWait(
                    cycle.target().blockers(), Set.copyOf(cycle.members())))
        .sorted(Comparator.comparing(WaitCycle::target, CLEANUP_ORDER))
        .toList();
  }

  /** 从 {@code from} 出发沿等待关系能不能走到 {@code targetKey}（不算原地）。 */
  private static boolean reaches(Candidate from, String targetKey, Map<String, Candidate> graph) {
    Set<String> seen = new HashSet<>();
    Deque<Candidate> pending = new ArrayDeque<>();
    pending.push(from);
    while (!pending.isEmpty()) {
      for (String blocker : pending.pop().blockers()) {
        String next = key(blocker);
        if (next.equals(targetKey)) {
          return true;
        }
        Candidate nextCandidate = graph.get(next);
        if (nextCandidate != null && seen.add(next)) {
          pending.push(nextCandidate);
        }
      }
    }
    return false;
  }

  /** 与 {@code target} 互相可达的车（它所在的环，含自身），按列车名排序；它不在环上时只有它自己。 */
  private static List<String> cycleOf(Candidate target, Map<String, Candidate> graph) {
    String targetKey = key(target.context().trainName());
    return graph.values().stream()
        .filter(
            candidate ->
                candidate == target
                    || (reaches(target, key(candidate.context().trainName()), graph)
                        && reaches(candidate, targetKey, graph)))
        .map(candidate -> candidate.context().trainName())
        .sorted(Comparator.comparing(name -> name.toLowerCase(Locale.ROOT)))
        .toList();
  }

  /** 与运行时复审同一口径（{@link WaitCycleEvidence}）：大小写与 TrainCarts 拆分别名都归一。 */
  private static String key(String trainName) {
    return TrainNameNormalizer.normalizeKey(trainName);
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

  /**
   * 一次健康采样形成的清理候选。
   *
   * @param blockers 最近一次授权判定里挡住它的列车名（不含自身）；只在排队等前车时有意义
   */
  record Candidate(
      RuntimeDispatchService.DeadlockTrainContext context,
      Duration stuckDuration,
      boolean recoveryExhausted,
      boolean waitingOnLiveBlocker,
      Set<String> blockers) {

    Candidate {
      Objects.requireNonNull(context, "context");
      stuckDuration = nonNegative(stuckDuration);
      blockers = blockers == null ? Set.of() : Set.copyOf(blockers);
    }

    Candidate(
        RuntimeDispatchService.DeadlockTrainContext context,
        Duration stuckDuration,
        boolean recoveryExhausted,
        boolean waitingOnLiveBlocker) {
      this(context, stuckDuration, recoveryExhausted, waitingOnLiveBlocker, Set.of());
    }

    /** 假定它没在等任何车时的同一候选：判它除了"在等"之外够不够格。 */
    private Candidate withoutWait() {
      return new Candidate(context, stuckDuration, recoveryExhausted, false, blockers);
    }
  }

  /**
   * 等待环上选中的一列。
   *
   * @param target 要清理的车
   * @param members 它所在的环（含自身），按列车名排序；它不在环上时只有它自己
   */
  record WaitCycle(Candidate target, List<String> members) {

    WaitCycle {
      Objects.requireNonNull(target, "target");
      members = List.copyOf(members);
    }
  }
}
