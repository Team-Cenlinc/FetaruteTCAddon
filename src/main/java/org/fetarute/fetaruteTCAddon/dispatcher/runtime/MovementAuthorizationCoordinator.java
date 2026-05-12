package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyDecision;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyManager;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyRequest;
import org.fetarute.fetaruteTCAddon.dispatcher.signal.SignalComputationTrace;

/**
 * 普通移动授权协调器。
 *
 * <p>该组件只封装“canEnter/preview 判定 -> hard blocker 评估 -> acquire -> acquire 结果评估”的授权顺序。它不写 TrainCarts
 * destination，不控车，不推进 routeIndex，也不选择 DYNAMIC 站台。
 */
final class MovementAuthorizationCoordinator {

  private final OccupancyManager occupancyManager;
  private final Consumer<String> debugLogger;

  MovementAuthorizationCoordinator(
      OccupancyManager occupancyManager, Consumer<String> debugLogger) {
    this.occupancyManager = Objects.requireNonNull(occupancyManager, "occupancyManager");
    this.debugLogger = debugLogger != null ? debugLogger : message -> {};
  }

  /** 执行普通移动授权。 */
  AuthorizationResult authorize(AuthorizationRequest request) {
    Objects.requireNonNull(request, "request");
    OccupancyDecision previewDecision =
        request.previewDecision().orElseGet(() -> occupancyManager.canEnter(request.request()));
    trace(request, previewDecision, request.previewScope());
    ProceedEvaluation previewEvaluation =
        request
            .evaluator()
            .evaluate(request.trainName(), previewDecision, request.now(), request.previewScope());
    if (!previewEvaluation.proceedAllowed()) {
      return AuthorizationResult.blocked(previewDecision, previewEvaluation, false);
    }

    OccupancyDecision acquired = occupancyManager.acquire(request.request());
    trace(request, acquired, request.acquireScope());
    ProceedEvaluation acquireEvaluation =
        request
            .evaluator()
            .evaluate(request.trainName(), acquired, request.now(), request.acquireScope());
    if (!acquireEvaluation.proceedAllowed()) {
      debugLogger.accept(
          "移动授权 acquire 阻塞: train="
              + request.trainName()
              + " signal="
              + acquired.signal()
              + " blockers="
              + acquired.blockers().size());
      return AuthorizationResult.blocked(acquired, acquireEvaluation, true);
    }
    return AuthorizationResult.allowed(acquired, acquireEvaluation);
  }

  /** 授权请求。 */
  record AuthorizationRequest(
      String trainName,
      OccupancyRequest request,
      Optional<OccupancyDecision> previewDecision,
      Instant now,
      String previewScope,
      String acquireScope,
      ProceedEvaluator evaluator) {
    AuthorizationRequest {
      if (trainName == null || trainName.isBlank()) {
        throw new IllegalArgumentException("trainName must not be blank");
      }
      Objects.requireNonNull(request, "request");
      previewDecision = previewDecision == null ? Optional.empty() : previewDecision;
      now = now == null ? Instant.now() : now;
      previewScope =
          previewScope == null || previewScope.isBlank() ? "movement-preview" : previewScope;
      acquireScope =
          acquireScope == null || acquireScope.isBlank() ? "movement-acquire" : acquireScope;
      Objects.requireNonNull(evaluator, "evaluator");
    }
  }

  /** 授权结果。 */
  record AuthorizationResult(
      OccupancyDecision decision,
      ProceedEvaluation proceedEvaluation,
      boolean acquireAttempted,
      boolean acquired) {
    private static AuthorizationResult allowed(
        OccupancyDecision decision, ProceedEvaluation proceedEvaluation) {
      return new AuthorizationResult(decision, proceedEvaluation, true, true);
    }

    private static AuthorizationResult blocked(
        OccupancyDecision decision, ProceedEvaluation proceedEvaluation, boolean acquireAttempted) {
      return new AuthorizationResult(decision, proceedEvaluation, acquireAttempted, false);
    }

    boolean proceedAllowed() {
      return proceedEvaluation != null && proceedEvaluation.proceedAllowed();
    }

    boolean rawAllowed() {
      return proceedEvaluation != null && proceedEvaluation.rawAllowed();
    }

    boolean hardBlockerBypass() {
      return proceedEvaluation != null && proceedEvaluation.hardBlockerBypass();
    }
  }

  /** 统一放行评估函数，由 Dispatcher 注入既有 hard blocker 语义。 */
  @FunctionalInterface
  interface ProceedEvaluator {
    ProceedEvaluation evaluate(
        String trainName, OccupancyDecision decision, Instant now, String scope);
  }

  /** 放行评估结果。 */
  record ProceedEvaluation(boolean proceedAllowed, boolean rawAllowed, boolean hardBlockerBypass) {}

  private void trace(AuthorizationRequest request, OccupancyDecision decision, String scope) {
    SignalComputationTrace.emit(
        SignalComputationTrace.builder(
                request.trainName(),
                request.trainName(),
                sourceForScope(scope),
                decision == null ? null : decision.signal())
            .primaryReason("movement-authorization:" + scope)
            .request(request.request())
            .decision(decision, request.request()),
        debugLogger);
  }

  private static SignalComputationTrace.Source sourceForScope(String scope) {
    if (scope != null && scope.contains("progress")) {
      return SignalComputationTrace.Source.PROGRESS_TRIGGER;
    }
    if (scope != null && scope.contains("departure")) {
      return SignalComputationTrace.Source.DEPARTURE_GATE;
    }
    if (scope != null && scope.contains("health")) {
      return SignalComputationTrace.Source.HEALTH;
    }
    return SignalComputationTrace.Source.AUTHORIZATION;
  }
}
