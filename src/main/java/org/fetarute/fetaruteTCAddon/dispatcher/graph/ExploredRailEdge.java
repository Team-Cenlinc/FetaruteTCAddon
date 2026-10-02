package org.fetarute.fetaruteTCAddon.dispatcher.graph;

import java.util.Objects;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.interlocking.RailEdgeFootprint;

/**
 * 轨道探索阶段生成的区间证据。
 *
 * @param lengthBlocks 全部成功候选中的最短区间长度
 * @param footprint 全部成功候选实际轨迹的物理足迹并集
 */
public record ExploredRailEdge(int lengthBlocks, RailEdgeFootprint footprint) {

  public ExploredRailEdge {
    if (lengthBlocks <= 0) {
      throw new IllegalArgumentException("lengthBlocks 必须为正数");
    }
    Objects.requireNonNull(footprint, "footprint");
  }
}
