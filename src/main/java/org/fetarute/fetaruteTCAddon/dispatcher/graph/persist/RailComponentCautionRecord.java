package org.fetarute.fetaruteTCAddon.dispatcher.graph.persist;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 连通分量运行规则：分量级 CAUTION 速度上限。
 *
 * <p>componentKey 由调度图扫描阶段生成（当前采用“分量内字典序最小 NodeId”），用于持久化配置与运维调参。
 */
public record RailComponentCautionRecord(
    /** 世界 UUID。 */
    UUID worldId,
    /** 连通分量 key（分量内字典序最小 nodeId）。 */
    String componentKey,
    /** CAUTION 速度上限（blocks/s）。 */
    double cautionSpeedBlocksPerSecond,
    /** 更新时间（现实时间）。 */
    Instant updatedAt) {

  public RailComponentCautionRecord {
    Objects.requireNonNull(worldId, "worldId");
    Objects.requireNonNull(componentKey, "componentKey");
    Objects.requireNonNull(updatedAt, "updatedAt");
    if (componentKey.isBlank()) {
      throw new IllegalArgumentException("componentKey 不能为空");
    }
    if (!Double.isFinite(cautionSpeedBlocksPerSecond) || cautionSpeedBlocksPerSecond <= 0.0) {
      throw new IllegalArgumentException("cautionSpeedBlocksPerSecond 必须为正数");
    }
  }
}
