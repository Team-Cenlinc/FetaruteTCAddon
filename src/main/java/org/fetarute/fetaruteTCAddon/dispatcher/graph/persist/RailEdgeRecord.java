package org.fetarute.fetaruteTCAddon.dispatcher.graph.persist;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.interlocking.RailFootprintCell;

/** 持久化的纯拓扑区间记录；普通轨道方块不会写入 Edge 行。 */
public record RailEdgeRecord(
    UUID worldId,
    EdgeId edgeId,
    int lengthBlocks,
    double baseSpeedLimit,
    boolean bidirectional,
    Set<RailFootprintCell> footprintCells) {

  /**
   * 不带足迹的构造：保留给测试与不关心足迹的调用点。
   *
   * <p>**空足迹表示"这条记录没有足迹"，不表示"这条边确实没有足迹"**—— 调用方若用它覆盖库里已有的好数据，会把 cell→edge 索引清空，
   * 进而让一切以实测覆盖为放行条件的机制永久 fail-closed。写库前务必确认覆盖可用。
   */
  public RailEdgeRecord(
      UUID worldId, EdgeId edgeId, int lengthBlocks, double baseSpeedLimit, boolean bidirectional) {
    this(worldId, edgeId, lengthBlocks, baseSpeedLimit, bidirectional, Set.of());
  }

  public RailEdgeRecord {
    footprintCells = footprintCells == null ? Set.of() : Set.copyOf(footprintCells);
    Objects.requireNonNull(worldId, "worldId");
    Objects.requireNonNull(edgeId, "edgeId");
    if (lengthBlocks < 0) {
      throw new IllegalArgumentException("lengthBlocks 不能为负");
    }
  }
}
