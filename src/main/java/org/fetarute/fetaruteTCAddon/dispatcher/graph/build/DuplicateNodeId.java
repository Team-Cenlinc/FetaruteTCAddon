package org.fetarute.fetaruteTCAddon.dispatcher.graph.build;

import java.util.List;
import java.util.Objects;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;

/** 在探索阶段发现的重复 NodeId（同名节点冲突）。 */
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
