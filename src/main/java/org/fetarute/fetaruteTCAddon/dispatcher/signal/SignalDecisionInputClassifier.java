package org.fetarute.fetaruteTCAddon.dispatcher.signal;

import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.AuthorizationPurpose;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyRequest;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyResource;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.ResourceIntent;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspect;

/** 信号发布前的请求分类与放行白名单。 */
public final class SignalDecisionInputClassifier {

  private SignalDecisionInputClassifier() {}

  /** 根据资源意图和请求来源识别信号判定输入类型。 */
  public static SignalDecisionInputType classify(OccupancyRequest request) {
    return classify(request, DrainClassificationContext.none());
  }

  /** 根据资源意图、请求来源与已认证 drain authority 识别信号判定输入类型。 */
  public static SignalDecisionInputType classify(
      OccupancyRequest request, DrainClassificationContext drainContext) {
    if (request == null) {
      return SignalDecisionInputType.UNKNOWN;
    }
    if (request.directedContext().isPresent()
        && request.directedContext().get().currentIndex() < 0) {
      return SignalDecisionInputType.UNKNOWN;
    }
    int movement = countIntent(request, ResourceIntent.MOVEMENT_REQUIRED);
    int protective = countIntent(request, ResourceIntent.PROTECTIVE_RETAIN);
    int hold = countIntent(request, ResourceIntent.HOLD_ONLY);
    if (movement <= 0) {
      if (hold > 0) {
        return SignalDecisionInputType.HOLD_RETAIN;
      }
      if (protective > 0) {
        return SignalDecisionInputType.PROTECTIVE_RETAIN;
      }
      return request.resourceList().isEmpty()
          ? SignalDecisionInputType.UNKNOWN
          : SignalDecisionInputType.POSITION_RETAIN;
    }
    if (request.purpose() == AuthorizationPurpose.CONFLICT_CLEARING) {
      DrainClassificationContext context =
          drainContext == null ? DrainClassificationContext.none() : drainContext;
      return context.drainThroughEligible()
          ? SignalDecisionInputType.DRAIN_THROUGH
          : SignalDecisionInputType.CONFLICT_CLEARING;
    }
    return SignalDecisionInputType.FORWARD_MOVEMENT;
  }

  /** 判断该输入是否允许把信号提升为放行类 aspect。 */
  public static boolean mayPublishProceed(
      OccupancyRequest request,
      boolean drainLeader,
      boolean drainAuthorityActive,
      boolean pathHardBlockersClear,
      boolean movementInhibited,
      SignalComputationTrace.TokenState tokenState,
      boolean clearingHardStop) {
    if (request == null || !hasMovementRequiredResources(request)) {
      return false;
    }
    if (tokenState == SignalComputationTrace.TokenState.INVALID) {
      return false;
    }
    if (movementInhibited && !clearingHardStop) {
      return false;
    }
    SignalDecisionInputType type = classify(request);
    if (type == SignalDecisionInputType.FORWARD_MOVEMENT) {
      return true;
    }
    if (type == SignalDecisionInputType.CONFLICT_CLEARING) {
      return true;
    }
    if (type == SignalDecisionInputType.DRAIN_THROUGH) {
      return drainLeader && drainAuthorityActive && pathHardBlockersClear;
    }
    return false;
  }

  /** 是否为放行类 signal。 */
  public static boolean isProceedLike(SignalAspect aspect) {
    return aspect == SignalAspect.PROCEED || aspect == SignalAspect.PROCEED_WITH_CAUTION;
  }

  /** 是否包含前向行车必须资源。 */
  public static boolean hasMovementRequiredResources(OccupancyRequest request) {
    return countIntent(request, ResourceIntent.MOVEMENT_REQUIRED) > 0;
  }

  /** DRAIN_THROUGH 分类所需的已认证上下文。 */
  public record DrainClassificationContext(
      boolean drainAuthorityActive,
      boolean drainAuthorityFresh,
      boolean drainAuthorityZoneMatches,
      boolean trainInsideMatchingSingleConflict,
      boolean pathDrainingTowardExit,
      boolean ordinaryDeparture,
      boolean topologyExitHintOnly) {

    public static DrainClassificationContext none() {
      return new DrainClassificationContext(false, false, false, false, false, false, true);
    }

    boolean drainThroughEligible() {
      return drainAuthorityActive
          && drainAuthorityFresh
          && drainAuthorityZoneMatches
          && trainInsideMatchingSingleConflict
          && pathDrainingTowardExit
          && !ordinaryDeparture
          && !topologyExitHintOnly;
    }
  }

  /** 统计指定资源意图数量。 */
  public static int countIntent(OccupancyRequest request, ResourceIntent intent) {
    if (request == null || intent == null) {
      return 0;
    }
    int count = 0;
    for (OccupancyResource resource : request.resourceList()) {
      if (resource != null && request.intentFor(resource) == intent) {
        count++;
      }
    }
    return count;
  }
}
