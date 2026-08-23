package org.fetarute.fetaruteTCAddon.dispatcher.graph;

import java.util.Collection;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode;

/** 提供对调度图的只读视图，用于路线规划、ETA 计算以及运行时诊断。 */
public interface RailGraph {

  /**
   * @return 图中全部节点的快照。
   */
  Collection<RailNode> nodes();

  /**
   * @return 图中全部区间的快照。
   */
  Collection<RailEdge> edges();

  /**
   * 按稳定区间标识查询边。
   *
   * <p>默认实现用于兼容轻量测试图；生产快照应覆盖该方法并使用 edge 索引，避免高频调用扫描全图。
   *
   * @param id 无向区间标识
   * @return 命中的边
   */
  default Optional<RailEdge> findEdge(EdgeId id) {
    if (id == null) {
      return Optional.empty();
    }
    EdgeId canonical = EdgeId.undirected(id.a(), id.b());
    return edges().stream()
        .filter(Objects::nonNull)
        .filter(edge -> EdgeId.undirected(edge.id().a(), edge.id().b()).equals(canonical))
        .findFirst();
  }

  /**
   * @return 根据节点 ID 查询节点。
   */
  Optional<RailNode> findNode(NodeId id);

  /**
   * @return 某节点可达的区间集合；若节点不存在返回空集合。
   */
  Set<RailEdge> edgesFrom(NodeId id);

  /**
   * @return 指定区间是否被运行时封锁（施工、故障等）。
   */
  boolean isBlocked(EdgeId id);
}
