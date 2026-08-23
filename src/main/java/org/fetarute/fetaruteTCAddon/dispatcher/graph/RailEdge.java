package org.fetarute.fetaruteTCAddon.dispatcher.graph;

import java.util.Objects;
import java.util.Optional;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;

/** 节点之间的纯拓扑区间数据；物理联锁局部几何由世界级稀疏快照独立持有。 */
public record RailEdge(
    EdgeId id,
    NodeId from,
    NodeId to,
    int lengthBlocks,
    double baseSpeedLimit,
    boolean bidirectional,
    Optional<RailEdgeMetadata> metadata) {

  public RailEdge {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(from, "from");
    Objects.requireNonNull(to, "to");
    Objects.requireNonNull(metadata, "metadata");
  }
}
