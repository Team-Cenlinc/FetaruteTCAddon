package org.fetarute.fetaruteTCAddon.dispatcher.graph.persist;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;

/**
 * 区间运维覆盖记录：用于在“图快照（rail_edges）”之上叠加人工控制（限速/封锁/临时管制等）。
 *
 * <p>设计原则：
 *
 * <ul>
 *   <li>rail_edges 仍是“拓扑/距离快照”，会被 /fta graph build 反复覆盖
 *   <li>override 独立存储，避免 build/merge 误删运维配置
 *   <li>运行时以「manual + TTL」叠加得到 effective 状态
 * </ul>
 */
public record RailEdgeOverrideRecord(
    UUID worldId,
    EdgeId edgeId,
    OptionalDouble speedLimitBlocksPerSecond,
    OptionalDouble tempSpeedLimitBlocksPerSecond,
    Optional<Instant> tempSpeedLimitUntil,
    boolean blockedManual,
    Optional<Instant> blockedUntil,
    Instant updatedAt) {

  public RailEdgeOverrideRecord {
    Objects.requireNonNull(worldId, "worldId");
    Objects.requireNonNull(edgeId, "edgeId");
    Objects.requireNonNull(speedLimitBlocksPerSecond, "speedLimitBlocksPerSecond");
    Objects.requireNonNull(tempSpeedLimitBlocksPerSecond, "tempSpeedLimitBlocksPerSecond");
    Objects.requireNonNull(tempSpeedLimitUntil, "tempSpeedLimitUntil");
    Objects.requireNonNull(blockedUntil, "blockedUntil");
    Objects.requireNonNull(updatedAt, "updatedAt");

    if (speedLimitBlocksPerSecond.isPresent()) {
      double speed = speedLimitBlocksPerSecond.getAsDouble();
      if (!Double.isFinite(speed) || speed <= 0.0) {
        throw new IllegalArgumentException("speedLimitBlocksPerSecond 必须为正数");
      }
    }
    if (tempSpeedLimitBlocksPerSecond.isPresent()) {
      double speed = tempSpeedLimitBlocksPerSecond.getAsDouble();
      if (!Double.isFinite(speed) || speed <= 0.0) {
        throw new IllegalArgumentException("tempSpeedLimitBlocksPerSecond 必须为正数");
      }
      if (tempSpeedLimitUntil.isEmpty()) {
        throw new IllegalArgumentException("tempSpeedLimitUntil 不能为空（临时限速必须带 TTL）");
      }
    }
  }

  /** 返回该覆盖记录是否不包含任何有效控制字段（可用于判断是否应删除行）。 */
  public boolean isEmpty() {
    return speedLimitBlocksPerSecond.isEmpty()
        && tempSpeedLimitBlocksPerSecond.isEmpty()
        && !blockedManual
        && blockedUntil.isEmpty();
  }

  /** 返回临时限速是否仍在生效。 */
  public boolean isTempSpeedActive(Instant now) {
    Objects.requireNonNull(now, "now");
    return tempSpeedLimitBlocksPerSecond.isPresent()
        && tempSpeedLimitUntil.map(now::isBefore).orElse(false);
  }

  /** 返回 TTL 封锁是否仍在生效。 */
  public boolean isBlockedTtlActive(Instant now) {
    Objects.requireNonNull(now, "now");
    return blockedUntil.map(now::isBefore).orElse(false);
  }

  /** 返回封锁是否生效（manual + TTL 叠加）。 */
  public boolean isBlockedEffective(Instant now) {
    Objects.requireNonNull(now, "now");
    return blockedManual || isBlockedTtlActive(now);
  }
}
