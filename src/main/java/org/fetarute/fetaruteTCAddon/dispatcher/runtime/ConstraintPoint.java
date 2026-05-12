package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.BlockerRelation;

/**
 * 信号约束 envelope 中的单个限制点。
 *
 * <p>限制点只描述“为什么要降级/限速”和“限制发生在多远处”，不直接执行控车。运行时最终选择所有限制中的最低安全速度。
 */
public record ConstraintPoint(
    ConstraintType type,
    BlockerRelation relation,
    OptionalLong distanceBlocks,
    OptionalDouble targetSpeedBps,
    Optional<String> ownerTrain,
    Optional<String> resourceKey) {

  public ConstraintPoint {
    type = Objects.requireNonNull(type, "type");
    relation = relation == null ? BlockerRelation.HARD_OCCUPANCY : relation;
    distanceBlocks = distanceBlocks == null ? OptionalLong.empty() : distanceBlocks;
    targetSpeedBps = targetSpeedBps == null ? OptionalDouble.empty() : targetSpeedBps;
    ownerTrain = ownerTrain == null ? Optional.empty() : ownerTrain.map(String::trim);
    resourceKey = resourceKey == null ? Optional.empty() : resourceKey.map(String::trim);
  }
}
