package org.fetarute.fetaruteTCAddon.dispatcher.graph.persist;

import java.util.Objects;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;

/** 持久化的区间记录。 */
public record RailEdgeRecord(
    UUID worldId, EdgeId edgeId, int lengthBlocks, double baseSpeedLimit, boolean bidirectional) {

  public RailEdgeRecord {
    Objects.requireNonNull(worldId, "worldId");
    Objects.requireNonNull(edgeId, "edgeId");
    if (lengthBlocks < 0) {
      throw new IllegalArgumentException("lengthBlocks 不能为负");
    }
  }
}
