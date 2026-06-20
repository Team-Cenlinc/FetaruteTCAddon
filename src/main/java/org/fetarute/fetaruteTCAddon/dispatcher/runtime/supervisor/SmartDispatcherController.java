package org.fetarute.fetaruteTCAddon.dispatcher.runtime.supervisor;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.function.Consumer;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspect;

/**
 * 确定性智能调度监督器。
 *
 * <p>本类不接管 TrainCarts 物理控制，也不绕过 {@code SignalPublicationGate}。它只基于全局快照、前方风险与 blocker graph
 * 输出可追踪的调度建议：提前 CAUTION、优先级排序、stale 清理候选、forward unlock 候选和 destroy 前置审查。所有排序均使用稳定字段，禁止依赖 HashMap
 * 遍历顺序或随机数。
 */
public final class SmartDispatcherController {

  private static final double DEFAULT_STOP_MARGIN_BLOCKS = 4.0;
  private static final double DEFAULT_CAUTION_MARGIN_BLOCKS = 16.0;

  private final Consumer<String> traceLogger;
  private final SmartWaitForPlanner waitForPlanner = new SmartWaitForPlanner();

  /** 创建一个只输出 trace 的监督器。 */
  public SmartDispatcherController(Consumer<String> traceLogger) {
    this.traceLogger = traceLogger != null ? traceLogger : message -> {};
  }

  /**
   * 全局铁路状态摘要。
   *
   * @param capturedAt 快照时间
   * @param trainCount FTA managed train 数量
   * @param runtimeGroupCount 已解析 TrainCarts group 数量
   * @param progressEntryCount FTA progress 条目数量
   * @param occupancyClaimCount 占用 claim 数量
   * @param queueCount 冲突队列数量
   * @param blockerSnapshotCount blocker snapshot 数量
   * @param movementTokenCount movement token 数量
   * @param movementInhibitorCount movement inhibitor 数量
   * @param occupancyVersion 占用版本
   * @param progressVersion 进度版本
   * @param pendingLayoverTicketCount pending layover 票据数量
   * @param backlogTicketCount backlog/queued 票据数量
   * @param trainIds 已排序的列车 ID
   */
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification = "构造器已用不可变 List 规整 trainIds，record accessor 可安全共享。")
  public record GlobalRailwayStateSnapshot(
      Instant capturedAt,
      int trainCount,
      int runtimeGroupCount,
      int progressEntryCount,
      int occupancyClaimCount,
      int queueCount,
      int blockerSnapshotCount,
      int movementTokenCount,
      int movementInhibitorCount,
      long occupancyVersion,
      long progressVersion,
      int pendingLayoverTicketCount,
      int backlogTicketCount,
      List<String> trainIds) {

    public GlobalRailwayStateSnapshot {
      capturedAt = capturedAt == null ? Instant.EPOCH : capturedAt;
      trainCount = Math.max(0, trainCount);
      runtimeGroupCount = Math.max(0, runtimeGroupCount);
      progressEntryCount = Math.max(0, progressEntryCount);
      occupancyClaimCount = Math.max(0, occupancyClaimCount);
      queueCount = Math.max(0, queueCount);
      blockerSnapshotCount = Math.max(0, blockerSnapshotCount);
      movementTokenCount = Math.max(0, movementTokenCount);
      movementInhibitorCount = Math.max(0, movementInhibitorCount);
      pendingLayoverTicketCount = Math.max(0, pendingLayoverTicketCount);
      backlogTicketCount = Math.max(0, backlogTicketCount);
      trainIds = sortedCopy(trainIds);
    }
  }

  /** 单列车优先级输入。 */
  public record PriorityInput(
      String trainId,
      String resourceId,
      boolean insideConflict,
      OptionalLong distanceToConflictExit,
      boolean freshMovementAuthority,
      boolean compatibleSelfClaim,
      double currentSpeedBps,
      Duration waitingDuration,
      int routePriority,
      boolean dwellReadyToDepart,
      int downstreamBlockedTrainCount,
      boolean safeToMove) {

    public PriorityInput {
      trainId = normalize(trainId, "-");
      resourceId = normalize(resourceId, "-");
      distanceToConflictExit =
          distanceToConflictExit == null ? OptionalLong.empty() : distanceToConflictExit;
      currentSpeedBps =
          Double.isFinite(currentSpeedBps) && currentSpeedBps > 0.0 ? currentSpeedBps : 0.0;
      waitingDuration =
          waitingDuration == null || waitingDuration.isNegative() ? Duration.ZERO : waitingDuration;
      routePriority = Math.max(0, routePriority);
      downstreamBlockedTrainCount = Math.max(0, downstreamBlockedTrainCount);
    }
  }

  /** 确定性优先级评分。 */
  public record PriorityScore(
      String trainId, String resourceId, int score, List<String> reasons, boolean safeToMove) {

    public PriorityScore {
      trainId = normalize(trainId, "-");
      resourceId = normalize(resourceId, "-");
      reasons = reasons == null ? List.of() : List.copyOf(reasons);
    }
  }

  /** 同一资源竞争的 winner/loser 输出。 */
  public record PrioritySelection(PriorityScore winner, List<PriorityScore> losers) {
    public PrioritySelection {
      losers = losers == null ? List.of() : List.copyOf(losers);
    }
  }

  /** destroy 前置审查输入。 */
  public record DeadlockDestroyInput(
      String episodeId,
      String trainA,
      String trainB,
      String targetTrain,
      String conflictKey,
      boolean weak,
      boolean allBlockersLiveHard,
      boolean safeDrainCandidate,
      boolean staleReleaseCandidate,
      boolean forwardUnlockCandidate,
      boolean prioritySchedulingCandidate,
      boolean targetResolvedToRuntimeGroup,
      boolean targetRecentlyProgressed,
      boolean targetFtaManagedOrConfirmedOrphan,
      boolean directionAuditRequired,
      String directionAuditReason,
      boolean directionReauditAttempted,
      boolean lastResortDestroy,
      boolean blockingActiveTraffic,
      Duration persisted,
      Duration threshold) {

    public DeadlockDestroyInput {
      episodeId = normalize(episodeId, "-");
      trainA = normalize(trainA, "-");
      trainB = normalize(trainB, "-");
      targetTrain = normalize(targetTrain, "-");
      conflictKey = normalize(conflictKey, "-");
      directionAuditReason = normalize(directionAuditReason, "-");
      persisted = persisted == null || persisted.isNegative() ? Duration.ZERO : persisted;
      threshold = threshold == null || threshold.isNegative() ? Duration.ZERO : threshold;
    }
  }

  /** destroy 前置审查结果。 */
  public record DeadlockDestroyReview(
      boolean allowed,
      String reason,
      boolean requiresPostVerification,
      List<String> rejectedAlternatives) {

    public DeadlockDestroyReview {
      reason = normalize(reason, allowed ? "allowed" : "rejected");
      rejectedAlternatives =
          rejectedAlternatives == null ? List.of() : List.copyOf(rejectedAlternatives);
    }

    public static DeadlockDestroyReview allowed(String reason, List<String> rejectedAlternatives) {
      return new DeadlockDestroyReview(true, reason, true, rejectedAlternatives);
    }

    public static DeadlockDestroyReview rejected(String reason) {
      return new DeadlockDestroyReview(false, reason, false, List.of());
    }
  }

  /** destroy 后验证结果。 */
  public record DestroyVerificationResult(
      String trainId,
      boolean runtimeGroupGone,
      boolean managedStateGone,
      boolean occupancyGone,
      boolean queueGone,
      boolean switcherClaimsGone,
      boolean deadlockGraphGone,
      boolean healthEpisodeClosed) {

    public DestroyVerificationResult {
      trainId = normalize(trainId, "-");
    }

    public boolean passed() {
      return runtimeGroupGone
          && managedStateGone
          && occupancyGone
          && queueGone
          && switcherClaimsGone
          && deadlockGraphGone
          && healthEpisodeClosed;
    }
  }

  /** 记录全局快照 trace。 */
  public void traceGlobalSnapshot(GlobalRailwayStateSnapshot snapshot) {
    if (snapshot == null) {
      return;
    }
    traceLogger.accept(
        "SMART_DISPATCH_GLOBAL_SNAPSHOT"
            + " trains="
            + snapshot.trainCount()
            + " groups="
            + snapshot.runtimeGroupCount()
            + " progress="
            + snapshot.progressEntryCount()
            + " claims="
            + snapshot.occupancyClaimCount()
            + " queues="
            + snapshot.queueCount()
            + " blockerSnapshots="
            + snapshot.blockerSnapshotCount()
            + " tokens="
            + snapshot.movementTokenCount()
            + " inhibitors="
            + snapshot.movementInhibitorCount()
            + " occupancyVersion="
            + snapshot.occupancyVersion()
            + " progressVersion="
            + snapshot.progressVersion()
            + " pendingLayover="
            + snapshot.pendingLayoverTicketCount()
            + " backlog="
            + snapshot.backlogTicketCount()
            + " trainIds="
            + snapshot.trainIds());
  }

  /** 生成 Phase 1.8 minimal forward planner 的只读/可执行计划。 */
  public SmartWaitForPlanner.PlanResult planMinimalForwardUnlock(
      SmartWaitForPlanner.PlannerInput input) {
    return waitForPlanner.plan(input);
  }

  /** 输出 planner 生成的 trace。 */
  public void traceMinimalForwardPlan(SmartWaitForPlanner.PlanResult result) {
    if (result == null) {
      return;
    }
    for (String line : result.traceLines()) {
      traceLogger.accept(line);
    }
  }

  /**
   * 根据前方风险与制动能力输出调度决策。
   *
   * <p>该方法不会因为 stale/unknown 风险直接输出 STOP；只有已经进入紧急停车距离、硬安全边界失败或调用方显式传入 {@code directStopAllowed}
   * 时才返回 {@link DispatchAction#HOLD_AT_SIGNAL}。
   */
  public DispatchDecision decideForwardSignal(ForwardDecisionInput input) {
    Objects.requireNonNull(input, "input");
    ForwardSignalRiskSnapshot risk =
        input.risk() == null ? ForwardSignalRiskSnapshot.none(input.trainId()) : input.risk();
    traceForwardRisk(risk);
    if (risk.riskSource() == RiskSource.ARTIFICIAL_WINDOW_LIMIT) {
      traceLogger.accept(
          "SIGNAL_ARTIFICIAL_WINDOW_IGNORED_FOR_ASPECT train="
              + risk.trainId()
              + " distance="
              + format(risk.nearestPlanningDistance()));
      traceLogger.accept(
          "SIGNAL_CAUTION_SKIPPED train="
              + risk.trainId()
              + " cautionSource="
              + risk.riskSource()
              + " reason=artificial-window-trace-only");
      traceLogger.accept(
          "SIGNAL_CAUTION_REASON train=" + risk.trainId() + " reason=artificial-window-trace-only");
      return noAction(input, risk, "artificial-window-trace-only");
    }
    if (risk.riskSource() == RiskSource.SAME_DIRECTION_FOLLOW) {
      traceLogger.accept(
          "SIGNAL_CAUTION_SKIPPED train="
              + risk.trainId()
              + " cautionSource="
              + risk.riskSource()
              + " reason=same-direction-follow-trace-only");
      traceLogger.accept(
          "SIGNAL_CAUTION_REASON train="
              + risk.trainId()
              + " reason=same-direction-follow-trace-only");
      return noAction(input, risk, "same-direction-follow-trace-only");
    }

    BrakingProfile braking = buildBrakingProfile(input, risk);
    traceBrakingProfile(risk.trainId(), braking);
    traceLogger.accept(
        "SIGNAL_CAUTION_CANDIDATE train="
            + risk.trainId()
            + " source="
            + risk.riskSource()
            + " distance="
            + format(risk.nearestPlanningDistance())
            + " targetSpeed="
            + braking.targetSpeedBps()
            + " freshness="
            + risk.riskFreshness());
    boolean directStopAllowed = input.directStopAllowed() || braking.shouldHardStop();
    if (directStopAllowed) {
      if (input.currentAspect() == SignalAspect.PROCEED) {
        traceLogger.accept(
            "SIGNAL_PROCEED_TO_STOP_WITHOUT_CAUTION train="
                + risk.trainId()
                + " allowed=true reason="
                + input.directStopReason()
                + " source="
                + risk.riskSource());
      }
      traceLogger.accept(
          "SIGNAL_PROCEED_TO_STOP_ALLOWED_REASON train="
              + risk.trainId()
              + " reason="
              + input.directStopReason()
              + " risk="
              + risk.riskSource());
      DispatchDecision decision =
          new DispatchDecision(
              risk.trainId(),
              DispatchAction.HOLD_AT_SIGNAL,
              SignalAspect.STOP,
              0.0,
              OptionalLong.empty(),
              fallbackDistance(risk.nearestStopDistance(), braking),
              input.authorityEndReason(),
              risk.riskSource(),
              DispatchEffectClass.SIGNAL_CONSTRAINT,
              "safety",
              normalize(input.directStopReason(), "direct-stop-allowed"),
              "hold-before-hard-boundary",
              true,
              false,
              braking);
      traceDecision(decision, "SMART_DISPATCH_ACTION_SELECTED");
      return decision;
    }

    if (risk.riskFreshness() != RiskFreshness.LIVE
        && risk.riskSource() != RiskSource.NONE
        && risk.riskSource() != RiskSource.EDGE_SPEED_DROP
        && risk.riskSource() != RiskSource.STATION_STOP
        && risk.riskSource() != RiskSource.TERMINAL_STOP
        && risk.riskSource() != RiskSource.ROUTE_STOP_OR_TERMINAL
        && risk.riskSource() != RiskSource.MOVEMENT_AUTHORITY_PHYSICAL_END) {
      traceCautionRejected(risk, "stale-or-protective-risk");
      DispatchAction action =
          risk.canRelease()
              ? DispatchAction.RELEASE_STALE_RETAIN
              : DispatchAction.RECALCULATE_AUTHORITY;
      DispatchDecision decision =
          new DispatchDecision(
              risk.trainId(),
              action,
              input.currentAspect(),
              input.currentTargetSpeedBps(),
              OptionalLong.empty(),
              OptionalLong.empty(),
              input.authorityEndReason(),
              risk.riskSource(),
              action.effectClass(),
              "stale-risk",
              "stale-or-protective-risk-does-not-stop",
              "refresh-evidence-before-hard-action",
              true,
              false,
              braking);
      traceDecision(decision, "SMART_DISPATCH_ACTION_SELECTED");
      return decision;
    }

    if (braking.shouldApplySpeedLimit()) {
      SignalAspect targetAspect =
          severity(input.currentAspect()) >= severity(SignalAspect.CAUTION)
              ? input.currentAspect()
              : SignalAspect.PROCEED_WITH_CAUTION;
      DispatchAction action =
          targetAspect == SignalAspect.PROCEED_WITH_CAUTION
              ? DispatchAction.PROCEED_WITH_CAUTION
              : DispatchAction.CAUTION_SPEED_LIMIT;
      DispatchDecision decision =
          new DispatchDecision(
              risk.trainId(),
              action,
              targetAspect,
              braking.targetSpeedBps(),
              fallbackDistance(risk.nearestCautionDistance(), braking),
              fallbackDistance(risk.nearestStopDistance(), braking),
              input.authorityEndReason(),
              risk.riskSource(),
              action.effectClass(),
              "braking-anticipation",
              "risk-visible-with-braking-distance",
              "reduce-speed-before-boundary",
              true,
              false,
              braking);
      traceLogger.accept(
          "SIGNAL_CAUTION_ACCEPTED train="
              + risk.trainId()
              + " source="
              + risk.riskSource()
              + " distanceToCaution="
              + format(decision.distanceToCaution())
              + " distanceToStop="
              + format(decision.distanceToStop())
              + " targetSpeed="
              + decision.targetSpeedBps());
      traceLogger.accept(
          "SIGNAL_CAUTION_APPLIED train="
              + risk.trainId()
              + " cautionSource="
              + risk.riskSource()
              + " cautionDistance="
              + format(decision.distanceToCaution())
              + " stopDistance="
              + format(decision.distanceToStop()));
      traceLogger.accept(
          "SIGNAL_CAUTION_REASON train="
              + risk.trainId()
              + " reason=risk-visible-with-braking-distance");
      traceDecision(decision, "SMART_DISPATCH_ACTION_SELECTED");
      return decision;
    }

    traceCautionRejected(risk, braking.targetSpeedReason());
    DispatchDecision decision = noAction(input, risk, "no-risk-inside-planning-envelope");
    traceDecision(decision, "SMART_DISPATCH_ACTION_SELECTED");
    return decision;
  }

  /** 前方风险决策输入。 */
  public record ForwardDecisionInput(
      String trainId,
      ForwardSignalRiskSnapshot risk,
      SignalAspect currentAspect,
      double currentSpeedBps,
      double currentTargetSpeedBps,
      double cautionSpeedBps,
      double decelBps2,
      long planningHorizonBlocks,
      double stopMarginBlocks,
      double cautionMarginBlocks,
      boolean directStopAllowed,
      String directStopReason,
      String authorityEndReason) {

    public ForwardDecisionInput {
      trainId = normalize(trainId, "-");
      currentAspect = currentAspect == null ? SignalAspect.PROCEED : currentAspect;
      currentSpeedBps =
          Double.isFinite(currentSpeedBps) && currentSpeedBps > 0.0 ? currentSpeedBps : 0.0;
      currentTargetSpeedBps =
          Double.isFinite(currentTargetSpeedBps) && currentTargetSpeedBps > 0.0
              ? currentTargetSpeedBps
              : 0.0;
      cautionSpeedBps =
          Double.isFinite(cautionSpeedBps) && cautionSpeedBps > 0.0 ? cautionSpeedBps : 0.0;
      decelBps2 = Double.isFinite(decelBps2) && decelBps2 > 0.0 ? decelBps2 : 1.0;
      planningHorizonBlocks = Math.max(0L, planningHorizonBlocks);
      stopMarginBlocks =
          Double.isFinite(stopMarginBlocks) && stopMarginBlocks >= 0.0
              ? stopMarginBlocks
              : DEFAULT_STOP_MARGIN_BLOCKS;
      cautionMarginBlocks =
          Double.isFinite(cautionMarginBlocks) && cautionMarginBlocks >= 0.0
              ? Math.max(stopMarginBlocks, cautionMarginBlocks)
              : DEFAULT_CAUTION_MARGIN_BLOCKS;
      directStopReason = normalize(directStopReason, "none");
      authorityEndReason = normalize(authorityEndReason, "none");
    }
  }

  /** 计算并 trace 优先级评分。 */
  public PriorityScore scorePriority(PriorityInput input) {
    Objects.requireNonNull(input, "input");
    int score = 0;
    List<String> reasons = new ArrayList<>();
    if (!input.safeToMove()) {
      reasons.add("unsafe-to-move");
      PriorityScore result =
          new PriorityScore(input.trainId(), input.resourceId(), Integer.MIN_VALUE, reasons, false);
      tracePriority(result);
      return result;
    }
    if (input.insideConflict()) {
      score += 10_000;
      reasons.add("inside-conflict");
    }
    if (input.distanceToConflictExit().isPresent()) {
      long distance = input.distanceToConflictExit().getAsLong();
      int distanceScore = (int) Math.max(0L, 2_000L - Math.min(2_000L, distance));
      score += distanceScore;
      reasons.add("near-exit:" + distance);
    }
    if (input.freshMovementAuthority()) {
      score += 1_500;
      reasons.add("fresh-authority");
    }
    if (input.compatibleSelfClaim()) {
      score += 1_200;
      reasons.add("compatible-self-claim");
    }
    if (input.currentSpeedBps() > 0.0) {
      int speedScore = (int) Math.min(900.0, input.currentSpeedBps() * 60.0);
      score += speedScore;
      reasons.add("controlled-speed:" + Math.round(input.currentSpeedBps()));
    }
    long waitSeconds = input.waitingDuration().toSeconds();
    int aging = (int) Math.min(1_200L, Math.max(0L, waitSeconds / 5L) * 10L);
    if (aging > 0) {
      score += aging;
      reasons.add("aging:" + waitSeconds + "s");
      traceLogger.accept(
          "SMART_DISPATCH_STARVATION_AGING train="
              + input.trainId()
              + " seconds="
              + waitSeconds
              + " score="
              + aging);
    }
    if (input.routePriority() > 0) {
      score += input.routePriority() * 50;
      reasons.add("route-priority:" + input.routePriority());
    }
    if (input.dwellReadyToDepart()) {
      score += 400;
      reasons.add("dwell-ready");
    }
    if (input.downstreamBlockedTrainCount() > 0) {
      score += input.downstreamBlockedTrainCount() * 120;
      reasons.add("downstream-blocked:" + input.downstreamBlockedTrainCount());
    }
    PriorityScore result =
        new PriorityScore(input.trainId(), input.resourceId(), score, reasons, input.safeToMove());
    tracePriority(result);
    return result;
  }

  /** 在同一资源的候选列车中选择 winner，排序完全确定。 */
  public PrioritySelection selectPriorityWinner(List<PriorityInput> inputs) {
    if (inputs == null || inputs.isEmpty()) {
      return new PrioritySelection(null, List.of());
    }
    List<PriorityScore> scores =
        inputs.stream()
            .filter(Objects::nonNull)
            .map(this::scorePriority)
            .sorted(
                Comparator.comparingInt(PriorityScore::score)
                    .reversed()
                    .thenComparing(PriorityScore::trainId)
                    .thenComparing(PriorityScore::resourceId))
            .toList();
    if (scores.isEmpty()) {
      return new PrioritySelection(null, List.of());
    }
    PriorityScore winner = scores.get(0);
    List<PriorityScore> losers = scores.size() <= 1 ? List.of() : scores.subList(1, scores.size());
    traceLogger.accept(
        "SMART_DISPATCH_PRIORITY_WINNER train="
            + winner.trainId()
            + " resource="
            + winner.resourceId()
            + " score="
            + winner.score()
            + " reasons="
            + winner.reasons());
    for (PriorityScore loser : losers) {
      traceLogger.accept(
          "SMART_DISPATCH_PRIORITY_LOSER train="
              + loser.trainId()
              + " resource="
              + loser.resourceId()
              + " score="
              + loser.score()
              + " winner="
              + winner.trainId());
    }
    return new PrioritySelection(winner, losers);
  }

  /** 执行 destroy 前置审查。 */
  public DeadlockDestroyReview reviewDestroyCandidate(DeadlockDestroyInput input) {
    Objects.requireNonNull(input, "input");
    traceLogger.accept(
        "DEADLOCK_GRAPH_SNAPSHOT episode="
            + input.episodeId()
            + " trainA="
            + input.trainA()
            + " trainB="
            + input.trainB()
            + " conflict="
            + input.conflictKey()
            + " allBlockersLiveHard="
            + input.allBlockersLiveHard()
            + " directionAuditRequired="
            + input.directionAuditRequired()
            + " directionAuditReason="
            + input.directionAuditReason()
            + " weak="
            + input.weak());
    traceLogger.accept(
        "DEADLOCK_BLOCKER_CHAIN episode="
            + input.episodeId()
            + " chain="
            + input.trainA()
            + "->"
            + input.trainB()
            + "->"
            + input.trainA()
            + " conflict="
            + input.conflictKey());
    if (input.targetResolvedToRuntimeGroup()) {
      traceLogger.accept(
          "DEADLOCK_BLOCKER_ALIAS_RESOLVED episode="
              + input.episodeId()
              + " target="
              + input.targetTrain());
    }
    traceLogger.accept(
        "DEADLOCK_DESTROY_PRECHECK episode="
            + input.episodeId()
            + " target="
            + input.targetTrain()
            + " conflict="
            + input.conflictKey()
            + " weak="
            + input.weak()
            + " persisted="
            + input.persisted().toSeconds()
            + "s threshold="
            + input.threshold().toSeconds()
            + "s directionAuditRequired="
            + input.directionAuditRequired()
            + " directionReauditAttempted="
            + input.directionReauditAttempted()
            + " lastResortDestroy="
            + input.lastResortDestroy()
            + " blockingActiveTraffic="
            + input.blockingActiveTraffic());
    if (input.weak() || input.conflictKey().startsWith("weaker:")) {
      traceLogger.accept(
          "DEADLOCK_CYCLE_REJECTED episode="
              + input.episodeId()
              + " reason=weak-blocker-diagnostic-only");
      return traceDestroyReview(DeadlockDestroyReview.rejected("weak-blocker-diagnostic-only"));
    }
    if (!input.allBlockersLiveHard()) {
      traceLogger.accept(
          "DEADLOCK_STALE_BLOCKER_IGNORED episode="
              + input.episodeId()
              + " reason=blockers-not-all-live-hard");
      traceLogger.accept(
          "DEADLOCK_CYCLE_REJECTED episode="
              + input.episodeId()
              + " reason=blockers-not-all-live-hard");
      return traceDestroyReview(DeadlockDestroyReview.rejected("blockers-not-all-live-hard"));
    }
    if (input.persisted().compareTo(input.threshold()) < 0) {
      return traceDestroyReview(DeadlockDestroyReview.rejected("threshold-not-reached"));
    }
    if (input.directionAuditRequired() && !input.directionReauditAttempted()) {
      traceLogger.accept(
          "DEADLOCK_DIRECTION_AUDIT_REQUIRED episode="
              + input.episodeId()
              + " target="
              + input.targetTrain()
              + " reason="
              + input.directionAuditReason()
              + " reAuditAttempted=false");
      return traceDestroyReview(DeadlockDestroyReview.rejected("direction-reaudit-required"));
    }
    if (input.directionAuditRequired() && !input.lastResortDestroy()) {
      traceLogger.accept(
          "DEADLOCK_DIRECTION_AUDIT_REQUIRED episode="
              + input.episodeId()
              + " target="
              + input.targetTrain()
              + " reason="
              + input.directionAuditReason()
              + " lastResortDestroy=false");
      return traceDestroyReview(DeadlockDestroyReview.rejected("direction-audit-required"));
    }
    if (input.directionAuditRequired() && !input.blockingActiveTraffic()) {
      traceLogger.accept(
          "DEADLOCK_DIRECTION_AUDIT_REQUIRED episode="
              + input.episodeId()
              + " target="
              + input.targetTrain()
              + " reason="
              + input.directionAuditReason()
              + " blockingActiveTraffic=false");
      return traceDestroyReview(
          DeadlockDestroyReview.rejected("direction-audit-active-traffic-not-proven"));
    }
    if (input.safeDrainCandidate()) {
      return traceDestroyReview(DeadlockDestroyReview.rejected("safe-drain-candidate-exists"));
    }
    if (input.staleReleaseCandidate()) {
      return traceDestroyReview(DeadlockDestroyReview.rejected("stale-release-candidate-exists"));
    }
    if (input.forwardUnlockCandidate()) {
      return traceDestroyReview(DeadlockDestroyReview.rejected("forward-unlock-candidate-exists"));
    }
    if (input.prioritySchedulingCandidate()) {
      return traceDestroyReview(
          DeadlockDestroyReview.rejected("priority-scheduling-candidate-exists"));
    }
    if (!input.targetResolvedToRuntimeGroup()) {
      traceLogger.accept(
          "DEADLOCK_BLOCKER_MISSING episode="
              + input.episodeId()
              + " target="
              + input.targetTrain()
              + " reason=target-runtime-group-unresolved");
      return traceDestroyReview(DeadlockDestroyReview.rejected("target-runtime-group-unresolved"));
    }
    if (input.targetRecentlyProgressed()) {
      return traceDestroyReview(DeadlockDestroyReview.rejected("target-recently-progressed"));
    }
    if (!input.targetFtaManagedOrConfirmedOrphan()) {
      return traceDestroyReview(DeadlockDestroyReview.rejected("target-not-managed-or-orphan"));
    }
    traceLogger.accept(
        "DEADLOCK_CYCLE_CONFIRMED episode="
            + input.episodeId()
            + " conflict="
            + input.conflictKey()
            + " target="
            + input.targetTrain());
    return traceDestroyReview(
        DeadlockDestroyReview.allowed(
            input.directionAuditRequired()
                ? "confirmed-live-hard-cycle-last-resort-direction-audit"
                : "confirmed-live-hard-cycle",
            input.directionAuditRequired()
                ? List.of(
                    "direction-reaudit",
                    "safe-drain",
                    "stale-release",
                    "forward-unlock",
                    "priority-scheduling")
                : List.of("safe-drain", "stale-release", "forward-unlock", "priority-scheduling")));
  }

  /** 记录 destroy 后验证结果。 */
  public void traceDestroyVerification(DestroyVerificationResult result) {
    if (result == null) {
      return;
    }
    traceLogger.accept(
        (result.passed() ? "DEADLOCK_DESTROY_VERIFY_PASSED" : "DEADLOCK_DESTROY_VERIFY_FAILED")
            + " train="
            + result.trainId()
            + " runtimeGroupGone="
            + result.runtimeGroupGone()
            + " managedStateGone="
            + result.managedStateGone()
            + " occupancyGone="
            + result.occupancyGone()
            + " queueGone="
            + result.queueGone()
            + " switcherClaimsGone="
            + result.switcherClaimsGone()
            + " deadlockGraphGone="
            + result.deadlockGraphGone()
            + " healthEpisodeClosed="
            + result.healthEpisodeClosed());
    if (!result.passed()) {
      traceLogger.accept("DESTROY_INCOMPLETE train=" + result.trainId());
    }
  }

  private DispatchDecision noAction(
      ForwardDecisionInput input, ForwardSignalRiskSnapshot risk, String reason) {
    return new DispatchDecision(
        risk.trainId(),
        DispatchAction.NO_ACTION,
        input.currentAspect(),
        input.currentTargetSpeedBps(),
        OptionalLong.empty(),
        OptionalLong.empty(),
        input.authorityEndReason(),
        risk.riskSource(),
        DispatchEffectClass.DIAGNOSTIC_ONLY,
        "none",
        reason,
        "none",
        true,
        false,
        null);
  }

  private BrakingProfile buildBrakingProfile(
      ForwardDecisionInput input, ForwardSignalRiskSnapshot risk) {
    OptionalLong distanceOpt = risk.nearestPlanningDistance();
    long distance = distanceOpt.orElse(Long.MAX_VALUE);
    double targetSpeed =
        risk.riskSource() == RiskSource.EDGE_SPEED_DROP && input.currentTargetSpeedBps() > 0.0
            ? Math.min(input.currentTargetSpeedBps(), input.cautionSpeedBps())
            : input.cautionSpeedBps();
    double stopBrakingDistance =
        (input.currentSpeedBps() * input.currentSpeedBps()) / (2.0 * input.decelBps2());
    double cautionBrakingDistance =
        Math.max(
            0.0,
            (input.currentSpeedBps() * input.currentSpeedBps() - targetSpeed * targetSpeed)
                / (2.0 * input.decelBps2()));
    boolean planningVisible = distanceOpt.isPresent() && distance <= input.planningHorizonBlocks();
    boolean shouldHardStop =
        distanceOpt.isPresent() && distance <= stopBrakingDistance + input.stopMarginBlocks();
    boolean trainMoving = input.currentSpeedBps() > 0.0;
    boolean shouldApplySpeedLimit =
        trainMoving
            && planningVisible
            && !shouldHardStop
            && risk.riskSource() != RiskSource.NONE
            && risk.riskFreshness() != RiskFreshness.STALE
            && risk.riskFreshness() != RiskFreshness.PROTECTIVE_ONLY
            && risk.riskFreshness() != RiskFreshness.UNKNOWN;
    if (trainMoving
        && planningVisible
        && !shouldHardStop
        && distance <= cautionBrakingDistance + input.cautionMarginBlocks()) {
      shouldApplySpeedLimit = true;
    }
    String reason =
        shouldHardStop
            ? "inside-stop-distance"
            : shouldApplySpeedLimit ? "inside-planning-horizon" : "outside-planning-horizon";
    return new BrakingProfile(
        input.currentSpeedBps(),
        targetSpeed,
        distanceOpt.orElse(0L),
        shouldHardStop ? stopBrakingDistance : cautionBrakingDistance,
        risk.riskSource(),
        reason,
        shouldApplySpeedLimit,
        shouldHardStop);
  }

  private void traceForwardRisk(ForwardSignalRiskSnapshot risk) {
    traceLogger.accept(
        "SMART_DISPATCH_FORWARD_RISK train="
            + risk.trainId()
            + " source="
            + risk.riskSource()
            + " freshness="
            + risk.riskFreshness()
            + " blockerTrain="
            + risk.blockerTrainId()
            + " blockerResource="
            + risk.blockerResourceId()
            + " stopDistance="
            + format(risk.nearestStopDistance())
            + " cautionDistance="
            + format(risk.nearestCautionDistance())
            + " hardBlockerDistance="
            + format(risk.nearestHardBlockerDistance())
            + " authorityPhysicalEnd="
            + format(risk.authorityPhysicalEndDistance()));
    traceLogger.accept(
        "SIGNAL_FORWARD_RISK_SNAPSHOT train="
            + risk.trainId()
            + " source="
            + risk.riskSource()
            + " nearest="
            + format(risk.nearestPlanningDistance()));
  }

  private void traceBrakingProfile(String trainId, BrakingProfile profile) {
    traceLogger.accept(
        "SIGNAL_BRAKING_PROFILE train="
            + trainId
            + " currentSpeed="
            + profile.currentSpeedBps()
            + " targetSpeed="
            + profile.targetSpeedBps()
            + " distance="
            + profile.distanceToTargetBlocks()
            + " brakingDistance="
            + profile.brakingDistanceBlocks()
            + " source="
            + profile.riskSource()
            + " shouldApplySpeedLimit="
            + profile.shouldApplySpeedLimit()
            + " shouldHardStop="
            + profile.shouldHardStop());
    traceLogger.accept(
        "SIGNAL_TARGET_SPEED train="
            + trainId
            + " targetSpeed="
            + profile.targetSpeedBps()
            + " reason="
            + profile.targetSpeedReason());
    traceLogger.accept(
        "SIGNAL_STOP_DISTANCE train="
            + trainId
            + " distance="
            + profile.distanceToTargetBlocks()
            + " brakingDistance="
            + profile.brakingDistanceBlocks());
    traceLogger.accept(
        "SIGNAL_CAUTION_DISTANCE train="
            + trainId
            + " distance="
            + profile.distanceToTargetBlocks());
  }

  private void traceDecision(DispatchDecision decision, String label) {
    traceLogger.accept(
        label
            + " train="
            + decision.trainId()
            + " action="
            + decision.action()
            + " targetAspect="
            + decision.targetAspect()
            + " targetSpeed="
            + decision.targetSpeedBps()
            + " risk="
            + decision.riskSource()
            + " effectClass="
            + decision.effectClass()
            + " safetyReason="
            + decision.safetyReason()
            + " effect="
            + decision.expectedUnblockEffect()
            + " reversible="
            + decision.reversible()
            + " postVerify="
            + decision.requiresPostVerification());
    traceLogger.accept(
        "SMART_DISPATCH_DECISION train="
            + decision.trainId()
            + " action="
            + decision.action()
            + " distanceToCaution="
            + format(decision.distanceToCaution())
            + " distanceToStop="
            + format(decision.distanceToStop())
            + " authorityEndReason="
            + decision.authorityEndReason());
    traceLogger.accept(
        "SMART_DISPATCH_EFFECT_CLASS train="
            + decision.trainId()
            + " action="
            + decision.action()
            + " effectClass="
            + decision.effectClass());
  }

  private void tracePriority(PriorityScore score) {
    traceLogger.accept(
        "SMART_DISPATCH_PRIORITY_SCORE train="
            + score.trainId()
            + " resource="
            + score.resourceId()
            + " score="
            + score.score()
            + " safeToMove="
            + score.safeToMove()
            + " reasons="
            + score.reasons());
    traceLogger.accept(
        "SMART_DISPATCH_PRIORITY_REASON train=" + score.trainId() + " reasons=" + score.reasons());
  }

  private void traceCautionRejected(ForwardSignalRiskSnapshot risk, String reason) {
    traceLogger.accept(
        "SIGNAL_CAUTION_REJECTED train="
            + risk.trainId()
            + " source="
            + risk.riskSource()
            + " reason="
            + normalize(reason, "none"));
    traceLogger.accept(
        "SIGNAL_CAUTION_SKIPPED train="
            + risk.trainId()
            + " cautionSource="
            + risk.riskSource()
            + " reason="
            + normalize(reason, "none"));
    traceLogger.accept(
        "SIGNAL_CAUTION_REASON train=" + risk.trainId() + " reason=" + normalize(reason, "none"));
    traceLogger.accept(
        "SMART_DISPATCH_ACTION_REJECTED train="
            + risk.trainId()
            + " action=CAUTION_SPEED_LIMIT reason="
            + normalize(reason, "none"));
  }

  private DeadlockDestroyReview traceDestroyReview(DeadlockDestroyReview review) {
    traceLogger.accept(
        (review.allowed() ? "DEADLOCK_DESTROY_CANDIDATE" : "DEADLOCK_DESTROY_SKIPPED")
            + " allowed="
            + review.allowed()
            + " reason="
            + review.reason()
            + " alternatives="
            + review.rejectedAlternatives());
    return review;
  }

  private static int severity(SignalAspect aspect) {
    if (aspect == null) {
      return Integer.MAX_VALUE;
    }
    return switch (aspect) {
      case PROCEED -> 0;
      case PROCEED_WITH_CAUTION -> 1;
      case CAUTION -> 2;
      case STOP -> 3;
    };
  }

  private static String format(OptionalLong value) {
    return value != null && value.isPresent() ? Long.toString(value.getAsLong()) : "-";
  }

  private static OptionalLong fallbackDistance(OptionalLong value, BrakingProfile profile) {
    if (value != null && value.isPresent()) {
      return value;
    }
    if (profile == null || profile.distanceToTargetBlocks() <= 0L) {
      return OptionalLong.empty();
    }
    return OptionalLong.of(profile.distanceToTargetBlocks());
  }

  private static List<String> sortedCopy(List<String> values) {
    if (values == null || values.isEmpty()) {
      return List.of();
    }
    return values.stream()
        .filter(value -> value != null && !value.isBlank())
        .map(String::trim)
        .sorted(String.CASE_INSENSITIVE_ORDER.thenComparing(Comparator.naturalOrder()))
        .toList();
  }

  private static String normalize(String raw, String fallback) {
    if (raw == null || raw.isBlank()) {
      return fallback;
    }
    return raw.trim();
  }
}
