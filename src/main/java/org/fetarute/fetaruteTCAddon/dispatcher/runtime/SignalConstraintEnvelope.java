package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspect;

/**
 * 统一的信号/速度约束结果。
 *
 * <p>SignalAspect 是展示层结果；实际控车应优先参考 envelope 内的 authority end、推荐速度与限制点列表。
 */
public record SignalConstraintEnvelope(
    SignalAspect aspect,
    StopControlMode stopControlMode,
    List<ConstraintPoint> constraints,
    OptionalLong distanceToAuthorityEnd,
    Optional<String> authorityEndResource,
    OptionalDouble targetSpeedBps,
    OptionalDouble recommendedSpeedBps,
    boolean allowLaunch,
    boolean requireFreshAcquire,
    String primaryReason) {

  public SignalConstraintEnvelope {
    aspect = aspect == null ? SignalAspect.STOP : aspect;
    stopControlMode =
        stopControlMode == null ? StopControlMode.BRAKING_TO_PLANNED_STOP : stopControlMode;
    constraints = constraints == null ? List.of() : List.copyOf(constraints);
    distanceToAuthorityEnd =
        distanceToAuthorityEnd == null ? OptionalLong.empty() : distanceToAuthorityEnd;
    authorityEndResource =
        authorityEndResource == null ? Optional.empty() : authorityEndResource.map(String::trim);
    targetSpeedBps = targetSpeedBps == null ? OptionalDouble.empty() : targetSpeedBps;
    recommendedSpeedBps =
        recommendedSpeedBps == null ? OptionalDouble.empty() : recommendedSpeedBps;
    primaryReason =
        primaryReason == null || primaryReason.isBlank() ? "none" : primaryReason.trim();
    Objects.requireNonNull(aspect, "aspect");
  }
}
