package org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn;

import java.util.Locale;
import java.util.Objects;

/**
 * 线路可用的 Depot 定义（用于多 Depot 发车与权重分配）。
 *
 * <p>nodeId 允许为 DYNAMIC 规范（如 {@code DYNAMIC:OP:D:DEPOT:[1:3]}）。
 */
public record SpawnDepot(String nodeId, int weight) {

  public SpawnDepot {
    Objects.requireNonNull(nodeId, "nodeId");
    nodeId = nodeId.trim();
    if (nodeId.isBlank()) {
      throw new IllegalArgumentException("nodeId 不能为空");
    }
    if (weight <= 0) {
      throw new IllegalArgumentException("weight 必须为正数");
    }
  }

  /** 返回用于不区分大小写比较的 NodeId key。 */
  public String normalizedKey() {
    return nodeId.toLowerCase(Locale.ROOT);
  }
}
