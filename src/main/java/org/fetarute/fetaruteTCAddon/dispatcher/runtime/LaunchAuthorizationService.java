package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyClaim;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyDecision;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyManager;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyPreviewSupport;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyRequest;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyResource;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.ResourceKind;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspect;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.TrainNameNormalizer;

/**
 * 闭塞出发授权服务。
 *
 * <p>这是运行时发车授权的统一入口：所有 Depot 出库、Station 出站、Layover 复用以及后续 Linked-Route 出发点都应通过这里完成 {@code
 * canEnter/preview -> hard blocker 抑制 -> acquire -> 控车动作} 的顺序。 调用方可以把 TrainCarts 相关动作作为 {@link
 * LaunchActions} 传入，使授权判定和实际控车保持隔离，便于单元测试和后续扩展。
 *
 * <p>Linked-Route 本轮仅预留接入点；本服务不解析 linked route，也不改写 route 规划。
 */
public final class LaunchAuthorizationService {

  /** 占用判定观察者：用于运行时刷新 blocker 诊断快照。 */
  @FunctionalInterface
  public interface DecisionObserver {

    /**
     * 观察一次占用判定。
     *
     * @param trainName 列车名
     * @param decision 占用判定结果
     * @param now 判定时间
     * @param scope 调用路径
     */
    void observe(String trainName, OccupancyDecision decision, Instant now, String scope);
  }

  /** 授权通过/拒绝后的实际控车动作。 */
  public interface LaunchActions {

    /** 无动作实现，用于只做判定或只写占用的场景。 */
    static LaunchActions none() {
      return new LaunchActions() {};
    }

    /** 授权被拒绝时保持 STOP 或维持等待状态。 */
    default void holdStop(AuthorizationResult result) {}

    /** 授权并 acquire 成功后写入下一跳 destination。 */
    default void writeDestination(AuthorizationResult result) {}

    /** destination 写入后发车、刷新信号或执行其它放行动作。 */
    default void launchOrProceed(AuthorizationResult result) {}

    /** 放行动作完成后刷新受影响列车信号。 */
    default void refreshRelated(AuthorizationResult result) {}
  }

  /**
   * 一次出发授权计划。
   *
   * @param request 占用请求
   * @param scope 诊断作用域
   * @param preview 是否使用只读 preview 判定
   * @param acquire 授权通过后是否立即写入占用
   * @param checkYield 是否执行优先级让行预检查
   * @param requireCleanBlockers 是否要求 allowed=true 时 blocker 必须为空；Depot spawn 前应启用
   * @param actions 授权后动作
   */
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification = "LaunchActions 是调用方注入的一次性控车动作句柄，服务只按顺序调用，不复制或持久化。")
  public record AuthorizationPlan(
      OccupancyRequest request,
      String scope,
      boolean preview,
      boolean acquire,
      boolean checkYield,
      boolean requireCleanBlockers,
      LaunchActions actions) {

    public AuthorizationPlan {
      Objects.requireNonNull(request, "request");
      scope = scope == null || scope.isBlank() ? "launch" : scope.trim();
      actions = actions == null ? LaunchActions.none() : actions;
    }
  }

  /**
   * 授权结果。
   *
   * @param allowed 是否最终允许出发
   * @param rawAllowed 占用层原始 allowed
   * @param hardBlockerBypass 是否因 NODE/EDGE hard blocker 抑制了原始放行
   * @param cleanBlockerBypass 是否因要求 clean blocker 抑制了原始放行
   * @param yielded 是否因优先级让行被拒绝
   * @param acquireAttempted 是否已经尝试 acquire
   * @param acquired acquire 是否成功
   * @param decision canEnter/preview 判定
   * @param acquireDecision acquire 判定
   * @param scope 诊断作用域
   */
  public record AuthorizationResult(
      boolean allowed,
      boolean rawAllowed,
      boolean hardBlockerBypass,
      boolean cleanBlockerBypass,
      boolean yielded,
      boolean acquireAttempted,
      boolean acquired,
      OccupancyDecision decision,
      OccupancyDecision acquireDecision,
      String scope) {

    public AuthorizationResult {
      scope = scope == null || scope.isBlank() ? "launch" : scope.trim();
    }

    /** 返回最能代表当前结果的占用判定。 */
    public OccupancyDecision effectiveDecision() {
      return acquireDecision != null ? acquireDecision : decision;
    }

    /** 返回当前结果对应的信号。 */
    public SignalAspect signal() {
      OccupancyDecision effective = effectiveDecision();
      return effective == null ? SignalAspect.STOP : effective.signal();
    }

    /** 返回当前结果对应的 blocker 列表。 */
    public List<OccupancyClaim> blockers() {
      OccupancyDecision effective = effectiveDecision();
      return effective == null ? List.of() : effective.blockers();
    }

    /** 当前结果是否来自冲突区释放。 */
    public boolean conflictRelease() {
      OccupancyDecision effective = effectiveDecision();
      return effective != null && effective.conflictRelease();
    }
  }

  private final OccupancyManager occupancyManager;
  private final DecisionObserver decisionObserver;
  private final Consumer<String> debugLogger;

  public LaunchAuthorizationService(
      OccupancyManager occupancyManager,
      DecisionObserver decisionObserver,
      Consumer<String> debugLogger) {
    this.occupancyManager = Objects.requireNonNull(occupancyManager, "occupancyManager");
    this.decisionObserver = decisionObserver;
    this.debugLogger = debugLogger != null ? debugLogger : message -> {};
  }

  /**
   * 执行一次完整授权计划。
   *
   * <p>该方法只保证顺序，不直接依赖 TrainCarts。调用方通过 {@link LaunchActions} 注入 destination、launch、hold STOP
   * 与信号刷新动作。
   */
  public AuthorizationResult authorize(AuthorizationPlan plan) {
    Objects.requireNonNull(plan, "plan");
    OccupancyRequest request = plan.request();
    if (plan.checkYield() && occupancyManager.shouldYield(request)) {
      AuthorizationResult result =
          new AuthorizationResult(
              false, false, false, false, true, false, false, null, null, plan.scope());
      debugLogger.accept(
          "出发授权让行: train="
              + request.trainName()
              + " scope="
              + plan.scope()
              + " priority="
              + request.priority());
      plan.actions().holdStop(result);
      return result;
    }

    OccupancyDecision decision =
        plan.preview() ? previewDecision(request) : occupancyManager.canEnter(request);
    AuthorizationResult result =
        evaluateDecision(
            request.trainName(),
            decision,
            request.now(),
            plan.scope(),
            plan.requireCleanBlockers(),
            false,
            false);
    if (!result.allowed()) {
      plan.actions().holdStop(result);
      return result;
    }

    if (plan.acquire()) {
      OccupancyDecision acquireDecision = occupancyManager.acquire(request);
      result =
          evaluateDecision(
              request.trainName(),
              acquireDecision,
              request.now(),
              plan.scope() + "-acquire",
              plan.requireCleanBlockers(),
              true,
              acquireDecision.allowed());
      if (!result.allowed()) {
        plan.actions().holdStop(result);
        return result;
      }
    }

    plan.actions().writeDestination(result);
    plan.actions().launchOrProceed(result);
    plan.actions().refreshRelated(result);
    return result;
  }

  /**
   * 统一评估一次占用判定是否可继续放行。
   *
   * <p>该方法会执行 blocker 诊断观察、hard blocker 抑制与冲突区放行日志，供 RuntimeDispatchService 的 signal/progress
   * 旧路径逐步迁移时复用同一套语义。
   */
  public AuthorizationResult evaluateDecision(
      String trainName,
      OccupancyDecision decision,
      Instant now,
      String scope,
      boolean requireCleanBlockers) {
    return evaluateDecision(trainName, decision, now, scope, requireCleanBlockers, false, false);
  }

  private AuthorizationResult evaluateDecision(
      String trainName,
      OccupancyDecision decision,
      Instant now,
      String scope,
      boolean requireCleanBlockers,
      boolean acquireAttempted,
      boolean acquired) {
    String resolvedScope = scope == null || scope.isBlank() ? "launch" : scope.trim();
    if (decision == null) {
      return new AuthorizationResult(
          false, false, false, false, false, acquireAttempted, false, null, null, resolvedScope);
    }
    Instant observedAt = now != null ? now : decision.earliestTime();
    if (decisionObserver != null && (!acquireAttempted || !decision.blockers().isEmpty())) {
      decisionObserver.observe(trainName, decision, observedAt, resolvedScope);
    }
    boolean rawAllowed = decision.allowed();
    boolean hardBlockerBypass =
        rawAllowed
            && !decision.conflictRelease()
            && hasHardBlockersForTrain(trainName, decision.blockers());
    boolean cleanBlockerBypass =
        rawAllowed && requireCleanBlockers && !decision.blockers().isEmpty();
    boolean allowed = rawAllowed && !hardBlockerBypass && !cleanBlockerBypass;
    if (hardBlockerBypass) {
      debugLogger.accept(
          "出发授权抑制: train="
              + trainName
              + " scope="
              + resolvedScope
              + " reason=hard_blockers blockers="
              + summarizeBlockers(decision.blockers()));
    } else if (cleanBlockerBypass) {
      debugLogger.accept(
          "出发授权抑制: train="
              + trainName
              + " scope="
              + resolvedScope
              + " reason=dirty_blockers blockers="
              + summarizeBlockers(decision.blockers()));
    } else if (allowed && decision.conflictRelease() && !decision.blockers().isEmpty()) {
      debugLogger.accept(
          "出发授权冲突释放: train="
              + trainName
              + " scope="
              + resolvedScope
              + " blockers="
              + summarizeBlockers(decision.blockers()));
    }
    return new AuthorizationResult(
        allowed,
        rawAllowed,
        hardBlockerBypass,
        cleanBlockerBypass,
        false,
        acquireAttempted,
        acquireAttempted && acquired && allowed,
        acquireAttempted ? null : decision,
        acquireAttempted ? decision : null,
        resolvedScope);
  }

  private OccupancyDecision previewDecision(OccupancyRequest request) {
    if (occupancyManager instanceof OccupancyPreviewSupport preview) {
      return preview.canEnterPreview(request);
    }
    return occupancyManager.canEnter(request);
  }

  /**
   * 判断 blocker 集合中是否存在不能被冲突区释放绕过的硬占用。
   *
   * <p>NODE/EDGE blocker 代表前方真实列车或实体位置，不能因为 {@code allowed=true} 的边界判定直接放行；CONFLICT blocker
   * 则交给占用层的队列/死锁释放语义处理。
   */
  public static boolean hasHardBlockersForTrain(String trainName, List<OccupancyClaim> blockers) {
    if (blockers == null || blockers.isEmpty()) {
      return false;
    }
    for (OccupancyClaim blocker : blockers) {
      if (blocker == null || blocker.resource() == null) {
        return true;
      }
      if (TrainNameNormalizer.sameLogicalTrain(blocker.trainName(), trainName)) {
        continue;
      }
      ResourceKind kind = blocker.resource().kind();
      if (kind == ResourceKind.NODE || kind == ResourceKind.EDGE) {
        return true;
      }
    }
    return false;
  }

  private static String summarizeBlockers(List<OccupancyClaim> blockers) {
    if (blockers == null || blockers.isEmpty()) {
      return "-";
    }
    return blockers.stream()
        .filter(Objects::nonNull)
        .limit(3)
        .map(LaunchAuthorizationService::formatBlocker)
        .collect(java.util.stream.Collectors.joining(","));
  }

  private static String formatBlocker(OccupancyClaim claim) {
    if (claim == null || claim.resource() == null) {
      return "unknown";
    }
    OccupancyResource resource = claim.resource();
    String owner =
        claim.trainName() == null || claim.trainName().isBlank() ? "-" : claim.trainName();
    return resource.kind().name() + ":" + resource.key() + "@" + owner;
  }
}
