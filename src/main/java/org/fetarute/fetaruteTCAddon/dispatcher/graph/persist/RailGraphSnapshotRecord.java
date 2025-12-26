package org.fetarute.fetaruteTCAddon.dispatcher.graph.persist;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** 持久化的调度图快照元信息（每世界一条）。 */
public record RailGraphSnapshotRecord(
    UUID worldId, Instant builtAt, int nodeCount, int edgeCount, String nodeSignature) {

  public RailGraphSnapshotRecord {
    Objects.requireNonNull(worldId, "worldId");
    Objects.requireNonNull(builtAt, "builtAt");
    nodeSignature = nodeSignature == null ? "" : nodeSignature;
    if (nodeCount < 0) {
      throw new IllegalArgumentException("nodeCount 不能为负");
    }
    if (edgeCount < 0) {
      throw new IllegalArgumentException("edgeCount 不能为负");
    }
  }
}
