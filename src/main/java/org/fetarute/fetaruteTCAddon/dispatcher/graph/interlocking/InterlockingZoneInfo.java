package org.fetarute.fetaruteTCAddon.dispatcher.graph.interlocking;

import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;

/**
 * 两个互不相邻区间因共享物理足迹而形成的联锁区。
 *
 * @param zoneKey 世界内稳定的联锁资源键
 * @param firstEdge 按端点字典序规范化并排序后的第一个区间
 * @param secondEdge 按端点字典序规范化并排序后的第二个区间
 * @param overlapCells 两个区间共享的全部离散坐标
 */
public record InterlockingZoneInfo(
    String zoneKey, EdgeId firstEdge, EdgeId secondEdge, Set<RailFootprintCell> overlapCells) {

  public InterlockingZoneInfo {
    Objects.requireNonNull(zoneKey, "zoneKey");
    Objects.requireNonNull(firstEdge, "firstEdge");
    Objects.requireNonNull(secondEdge, "secondEdge");
    Objects.requireNonNull(overlapCells, "overlapCells");
    firstEdge = canonical(firstEdge);
    secondEdge = canonical(secondEdge);
    if (compareEdges(firstEdge, secondEdge) > 0) {
      EdgeId swap = firstEdge;
      firstEdge = secondEdge;
      secondEdge = swap;
    }
    if (firstEdge.equals(secondEdge)) {
      throw new IllegalArgumentException("联锁区必须由两个不同区间组成");
    }
    overlapCells = Collections.unmodifiableSet(new TreeSet<>(overlapCells));
  }

  static EdgeId canonical(EdgeId edgeId) {
    return EdgeId.undirected(edgeId.a(), edgeId.b());
  }

  static int compareEdges(EdgeId first, EdgeId second) {
    int byFirstEndpoint = first.a().value().compareTo(second.a().value());
    if (byFirstEndpoint != 0) {
      return byFirstEndpoint;
    }
    return first.b().value().compareTo(second.b().value());
  }
}
