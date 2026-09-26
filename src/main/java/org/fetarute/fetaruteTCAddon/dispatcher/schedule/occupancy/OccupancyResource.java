package org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy;

import java.util.Comparator;
import java.util.Objects;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;

/**
 * 调度占用资源标识。
 *
 * <p>资源以字符串 key 表示，支持 EDGE/NODE/CONFLICT 三类互斥对象。
 */
public record OccupancyResource(ResourceKind kind, String key) {

  /**
   * 资源的确定序：先按 {@link ResourceKind} 的名字，再按 {@link #key()}。
   *
   * <p>与 {@link #equals(Object)} 一致，且与占用管理器启动重建时的排序口径相同。{@code OccupancyResource} 的 hash 含枚举（身份
   * hash），装进 {@code HashSet}/{@code Set.copyOf} 后遍历顺序会随进程变；凡是遍历顺序会影响释放、唤醒或选择先后的资源集合都按它排序。
   */
  public static final Comparator<OccupancyResource> STABLE_ORDER =
      Comparator.comparing((OccupancyResource resource) -> resource.kind().name())
          .thenComparing(OccupancyResource::key);

  public OccupancyResource {
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(key, "key");
    if (key.isBlank()) {
      throw new IllegalArgumentException("resource key 不能为空");
    }
  }

  public static OccupancyResource forEdge(EdgeId edgeId) {
    Objects.requireNonNull(edgeId, "edgeId");
    EdgeId normalized = EdgeId.undirected(edgeId.a(), edgeId.b());
    return new OccupancyResource(
        ResourceKind.EDGE, normalized.a().value() + "~" + normalized.b().value());
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
