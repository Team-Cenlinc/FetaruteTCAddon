package org.fetarute.fetaruteTCAddon.dispatcher.signal;

import java.util.Objects;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyRequest;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspect;

/**
 * 信号周期的最终发布门。
 *
 * <p>AUTHORIZATION、OCCUPANCY、MOVEMENT_AUTHORITY 等阶段只产出候选信号；真正可见的 live aspect
 * 必须经过此门统一收敛。该类不读取上一帧信号来决定安全结果，previous aspect 只能由调用方用于判断是否需要下发更新或记录日志。
 */
public final class SignalPublicationGate {

  public static final String INCIDENT_DRAIN_AUTHORITY_INCONSISTENT = "DRAIN_AUTHORITY_INCONSISTENT";

  private SignalPublicationGate() {}

  /** 发布门输入。 */
  public record Input(
      OccupancyRequest request,
      SignalAspect candidateAspect,
      boolean movementInhibited,
      SignalComputationTrace.TokenState movementTokenState,
      boolean drainLeader,
      boolean drainAuthorityActive,
      boolean drainAuthorityFresh,
      boolean drainAuthorityZoneMatches,
      boolean trainInsideMatchingSingleConflict,
      boolean pathDrainingTowardExit,
      boolean ordinaryDeparture,
      boolean topologyExitHintOnly,
      boolean pathHardBlockersClear,
      String incident) {

    public Input {
      candidateAspect = candidateAspect == null ? SignalAspect.STOP : candidateAspect;
      movementTokenState =
          movementTokenState == null ? SignalComputationTrace.TokenState.NONE : movementTokenState;
      incident = incident == null || incident.isBlank() ? "-" : incident.trim();
    }
  }

  /** 发布门输出。 */
  public record Decision(
      SignalAspect candidateAspect,
      SignalAspect visibleAspect,
      SignalDecisionInputType inputType,
      boolean blocked,
      boolean localOnlyStop,
      String reason) {

    public Decision {
      Objects.requireNonNull(candidateAspect, "candidateAspect");
      Objects.requireNonNull(visibleAspect, "visibleAspect");
      inputType = inputType == null ? SignalDecisionInputType.UNKNOWN : inputType;
      reason = reason == null || reason.isBlank() ? "allowed" : reason.trim();
    }
  }

  /** 将候选信号收敛为最终可见信号。 */
  public static Decision evaluate(Input input) {
    if (input == null) {
      return new Decision(
          SignalAspect.STOP,
          SignalAspect.STOP,
          SignalDecisionInputType.UNKNOWN,
          true,
          false,
          "input-missing");
    }
    SignalAspect candidate = input.candidateAspect();
    SignalDecisionInputClassifier.DrainClassificationContext drainContext =
        new SignalDecisionInputClassifier.DrainClassificationContext(
            input.drainAuthorityActive(),
            input.drainAuthorityFresh(),
            input.drainAuthorityZoneMatches(),
            input.trainInsideMatchingSingleConflict(),
            input.pathDrainingTowardExit(),
            input.ordinaryDeparture(),
            input.topologyExitHintOnly());
    SignalDecisionInputType inputType =
        SignalDecisionInputClassifier.classify(input.request(), drainContext);
    if (candidate == SignalAspect.STOP) {
      return new Decision(candidate, SignalAspect.STOP, inputType, false, false, "candidate-stop");
    }
    BlockResult blockReason = blockReason(input, inputType);
    if (!blockReason.isBlank()) {
      return new Decision(
          candidate,
          SignalAspect.STOP,
          inputType,
          true,
          blockReason.localOnlyStop(),
          blockReason.reason());
    }
    return new Decision(candidate, candidate, inputType, false, false, "allowed");
  }

  private static BlockResult blockReason(Input input, SignalDecisionInputType inputType) {
    OccupancyRequest request = input.request();
    if (request == null) {
      return BlockResult.hard("request-missing");
    }
    if (!SignalDecisionInputClassifier.hasMovementRequiredResources(request)) {
      return BlockResult.hard("movement-required-resources-empty");
    }
    if (request.directedContext().isPresent()
        && request.directedContext().get().currentIndex() < 0) {
      return BlockResult.hard("current-index-invalid");
    }
    if (inputType == SignalDecisionInputType.HOLD_RETAIN
        || inputType == SignalDecisionInputType.PROTECTIVE_RETAIN
        || inputType == SignalDecisionInputType.POSITION_RETAIN
        || inputType == SignalDecisionInputType.UNKNOWN) {
      return BlockResult.hard("input-type-" + inputType.name().toLowerCase(java.util.Locale.ROOT));
    }
    if (input.movementInhibited()) {
      return BlockResult.hard("movement-inhibited");
    }
    if (input.movementTokenState() == SignalComputationTrace.TokenState.INVALID) {
      return BlockResult.hard("movement-token-invalid");
    }
    if (inputType == SignalDecisionInputType.DRAIN_THROUGH) {
      if (!input.drainLeader()) {
        return BlockResult.local("drain-authority-without-leader");
      }
      if (INCIDENT_DRAIN_AUTHORITY_INCONSISTENT.equals(input.incident())) {
        return BlockResult.local("drain-authority-inconsistent");
      }
    }
    if (!SignalDecisionInputClassifier.mayPublishProceed(
        request,
        input.drainLeader(),
        input.drainAuthorityActive(),
        input.pathHardBlockersClear(),
        input.movementInhibited(),
        input.movementTokenState(),
        false)) {
      return BlockResult.hard("publish-proceed-not-allowed");
    }
    return BlockResult.allowed();
  }

  private record BlockResult(String reason, boolean localOnlyStop) {

    static BlockResult allowed() {
      return new BlockResult("", false);
    }

    static BlockResult hard(String reason) {
      return new BlockResult(reason, false);
    }

    static BlockResult local(String reason) {
      return new BlockResult(reason, true);
    }

    boolean isBlank() {
      return reason == null || reason.isBlank();
    }
  }
}
