package org.fetarute.fetaruteTCAddon.dispatcher.graph.interlocking;

/**
 * 轨道区间足迹中的一个离散世界方块坐标。
 *
 * <p>坐标按 {@code x -> y -> z} 的顺序比较，使持久化、摘要与诊断输出不依赖集合迭代顺序。
 */
public record RailFootprintCell(int x, int y, int z) implements Comparable<RailFootprintCell> {

  @Override
  public int compareTo(RailFootprintCell other) {
    int byX = Integer.compare(x, other.x);
    if (byX != 0) {
      return byX;
    }
    int byY = Integer.compare(y, other.y);
    if (byY != 0) {
      return byY;
    }
    return Integer.compare(z, other.z);
  }
}
