package org.fetarute.fetaruteTCAddon.dispatcher.runtime.control;

import java.util.Objects;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspect;

/**
 * 移动授权（Movement Authority）决策器。
 *
 * <p>用于把“前方可用距离”转换为运行许可：当当前速度对应的制动距离超过可用距离时，主动降级信号并下压目标速度，防止冒进。
 */
public final class MovementAuthorityService {

  /** 输入参数：用于评估当前 tick 的授权是否充足。 */
  public record MovementAuthorityInput(
      SignalAspect requestedAspect,
      double currentSpeedBps,
      double decelBps2,
      OptionalLong constraintDistanceBlocks,
      double stopSafetyMarginBlocks,
      double cautionSafetyMarginBlocks) {

    public MovementAuthorityInput {
      Objects.requireNonNull(requestedAspect, "requestedAspect");
      Objects.requireNonNull(constraintDistanceBlocks, "constraintDistanceBlocks");
      if (!Double.isFinite(currentSpeedBps) || currentSpeedBps < 0.0) {
        throw new IllegalArgumentException("currentSpeedBps 必须为非负有限数");
      }
      if (!Double.isFinite(decelBps2) || decelBps2 <= 0.0) {
        throw new IllegalArgumentException("decelBps2 必须为正有限数");
      }
      if (!Double.isFinite(stopSafetyMarginBlocks) || stopSafetyMarginBlocks < 0.0) {
        throw new IllegalArgumentException("stopSafetyMarginBlocks 必须为非负有限数");
      }
      if (!Double.isFinite(cautionSafetyMarginBlocks) || cautionSafetyMarginBlocks < 0.0) {
        throw new IllegalArgumentException("cautionSafetyMarginBlocks 必须为非负有限数");
      }
    }
  }

  /**
   * 输出结果：包含授权后的信号等级与建议限速。
   *
   * @param effectiveAspect 授权后的最严格信号
   * @param authorityDistanceBlocks 本次评估使用的约束距离
   * @param recommendedMaxSpeedBps 基于授权距离推导的建议最大速度（blocks/s）
   * @param restricted 是否因授权不足而发生了降级
   */
  public record MovementAuthorityDecision(
      SignalAspect effectiveAspect,
      OptionalLong authorityDistanceBlocks,
      OptionalDouble recommendedMaxSpeedBps,
      boolean restricted) {

    public MovementAuthorityDecision {
      Objects.requireNonNull(effectiveAspect, "effectiveAspect");
      Objects.requireNonNull(authorityDistanceBlocks, "authorityDistanceBlocks");
      Objects.requireNonNull(recommendedMaxSpeedBps, "recommendedMaxSpeedBps");
    }
  }

  /** 计算移动授权结果。 */
  public MovementAuthorityDecision evaluate(MovementAuthorityInput input) {
    if (input == null) {
      return new MovementAuthorityDecision(
          SignalAspect.STOP, OptionalLong.empty(), OptionalDouble.empty(), false);
    }
    SignalAspect requested = input.requestedAspect();
    OptionalLong constraintDistance = input.constraintDistanceBlocks();
    if (requested == SignalAspect.STOP || constraintDistance.isEmpty()) {
      return new MovementAuthorityDecision(
          requested, constraintDistance, OptionalDouble.empty(), false);
    }

    double currentSpeed = Math.max(0.0, input.currentSpeedBps());
    double decel = Math.max(0.001, input.decelBps2());
    double stopMargin = Math.max(0.0, input.stopSafetyMarginBlocks());
    double cautionMargin = Math.max(stopMargin, input.cautionSafetyMarginBlocks());

    long availableBlocks = Math.max(0L, constraintDistance.getAsLong());
    double brakingDistance = (currentSpeed * currentSpeed) / (2.0 * decel);
    double stopRequiredBlocks = brakingDistance + stopMargin;
    double cautionRequiredBlocks = brakingDistance + cautionMargin;

    double speedEnvelopeDistance = Math.max(0.0, availableBlocks - stopMargin);
    double recommendedMaxSpeed = Math.sqrt(2.0 * decel * speedEnvelopeDistance);
    OptionalDouble recommendedOpt =
        Double.isFinite(recommendedMaxSpeed)
            ? OptionalDouble.of(Math.max(0.0, recommendedMaxSpeed))
            : OptionalDouble.empty();

    SignalAspect degraded = requested;
    if (availableBlocks + 1.0e-6 < stopRequiredBlocks) {
      degraded = SignalAspect.STOP;
    } else if (availableBlocks + 1.0e-6 < cautionRequiredBlocks) {
      degraded = degradeOneStep(requested);
    }

    SignalAspect effective = strictest(requested, degraded);
    boolean restricted = severity(effective) > severity(requested);
    return new MovementAuthorityDecision(effective, constraintDistance, recommendedOpt, restricted);
  }

  private static SignalAspect degradeOneStep(SignalAspect aspect) {
    if (aspect == null) {
      return SignalAspect.STOP;
    }
    return switch (aspect) {
      case PROCEED -> SignalAspect.PROCEED_WITH_CAUTION;
      case PROCEED_WITH_CAUTION -> SignalAspect.CAUTION;
      case CAUTION -> SignalAspect.STOP;
      case STOP -> SignalAspect.STOP;
    };
  }

  private static SignalAspect strictest(SignalAspect first, SignalAspect second) {
    if (severity(second) > severity(first)) {
      return second;
    }
    return first;
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
}
