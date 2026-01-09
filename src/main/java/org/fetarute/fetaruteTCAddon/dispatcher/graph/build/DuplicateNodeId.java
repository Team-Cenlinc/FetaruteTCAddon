package org.fetarute.fetaruteTCAddon.dispatcher.graph.build;

import java.util.List;
import java.util.Objects;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;

/** 图构建过程中发现的“同一个 nodeId 出现在多个位置”的诊断信息。 */
public record DuplicateNodeId(NodeId nodeId, List<Occurrence> occurrences) {

  public DuplicateNodeId {
    Objects.requireNonNull(nodeId, "nodeId");
    occurrences = occurrences != null ? List.copyOf(occurrences) : List.of();
  }

  public record Occurrence(NodeType nodeType, int x, int y, int z, boolean virtualSign) {
    public Occurrence {
      Objects.requireNonNull(nodeType, "nodeType");
    }
  }
}
