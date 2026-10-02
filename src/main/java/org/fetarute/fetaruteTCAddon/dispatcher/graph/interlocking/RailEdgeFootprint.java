package org.fetarute.fetaruteTCAddon.dispatcher.graph.interlocking;

import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * 一个图区间占据的离散轨道方块集合。
 *
 * @param formatVersion 足迹格式版本；版本 {@code 0} 表示尚无可参与联锁计算的格式
 * @param complete 是否已完整探索该区间足迹
 * @param cells 区间实际覆盖的方块坐标
 */
public record RailEdgeFootprint(int formatVersion, boolean complete, Set<RailFootprintCell> cells) {

  /** 当前运行时能够解释的足迹格式。 */
  public static final int CURRENT_FORMAT_VERSION = 1;

  public RailEdgeFootprint {
    Objects.requireNonNull(cells, "cells");
    cells = Collections.unmodifiableSet(new TreeSet<>(cells));
  }

  /** 返回该足迹是否具备参与联锁区构建所需的完整、受支持数据。 */
  public boolean participatesInInterlocking() {
    return complete && formatVersion == CURRENT_FORMAT_VERSION && !cells.isEmpty();
  }
}
