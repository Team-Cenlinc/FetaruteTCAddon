package org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy;

import java.util.Objects;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;

/**
 * 调度占用资源标识。
 *
 * <p>资源以字符串 key 表示，支持 EDGE/NODE/CONFLICT 三类互斥对象。
 */
public record OccupancyResource(ResourceKind kind, String key) {

  public OccupancyResource {
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(key, "key");
    if (key.isBlank()) {
      throw new IllegalArgumentException("resource key 不能为空");
    }
  }

  public static OccupancyResource forEdge(EdgeId edgeId) {
    Objects.requireNonNull(edgeId, "edgeId");
    return new OccupancyResource(ResourceKind.EDGE, edgeId.a().value() + "~" + edgeId.b().value());
  }

  public static OccupancyResource forNode(NodeId nodeId) {
    Objects.requireNonNull(nodeId, "nodeId");
    return new OccupancyResource(ResourceKind.NODE, nodeId.value());
  }

  public static OccupancyResource forConflict(String conflictId) {
    Objects.requireNonNull(conflictId, "conflictId");
    String normalized = conflictId.trim();
    if (normalized.isEmpty()) {
      throw new IllegalArgumentException("conflictId 不能为空");
    }
    return new OccupancyResource(ResourceKind.CONFLICT, normalized);
  }

  @Override
  public String toString() {
    return kind + ":" + key;
  }
}
