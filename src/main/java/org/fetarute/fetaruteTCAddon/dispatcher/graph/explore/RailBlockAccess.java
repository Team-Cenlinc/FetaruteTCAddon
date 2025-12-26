package org.fetarute.fetaruteTCAddon.dispatcher.graph.explore;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/** 轨道方块访问抽象，用于在“区间探索”阶段计算边与距离。 */
public interface RailBlockAccess {

  /**
   * @return 指定位置是否是可行驶轨道方块。
   */
  boolean isRail(RailBlockPos pos);

  /**
   * @return 指定轨道方块的连通邻居集合（不含自身）。
   */
  Set<RailBlockPos> neighbors(RailBlockPos pos);

  /**
   * 返回从 from 移动到 to 的“距离代价”（同一单位内可视作米/方块）。
   *
   * <p>默认实现为单位权重，等价于“每步 1 格”；若底层轨道具有几何长度（如 TCCoasters）， 实现可返回更精确的代价以改善距离与 ETA 计算。
   */
  default double stepCost(RailBlockPos from, RailBlockPos to) {
    return 1.0;
  }

  /**
   * 在指定位置附近寻找“最接近”的轨道方块作为节点锚点。
   *
   * <p>用于把“节点牌子方块”映射到可遍历的轨道网络上；若附近没有轨道返回空集合。
   */
  default Set<RailBlockPos> findNearestRailBlocks(RailBlockPos center, int radius) {
    Objects.requireNonNull(center, "center");
    if (radius < 0) {
      throw new IllegalArgumentException("radius 不能为负");
    }

    int best = Integer.MAX_VALUE;
    Set<RailBlockPos> result = new HashSet<>();
    for (int dx = -radius; dx <= radius; dx++) {
      for (int dy = -radius; dy <= radius; dy++) {
        for (int dz = -radius; dz <= radius; dz++) {
          RailBlockPos candidate = center.offset(dx, dy, dz);
          if (!isRail(candidate)) {
            continue;
          }
          int distance = Math.abs(dx) + Math.abs(dy) + Math.abs(dz);
          if (distance < best) {
            best = distance;
            result.clear();
            result.add(candidate);
          } else if (distance == best) {
            result.add(candidate);
          }
        }
      }
    }
    return Set.copyOf(result);
  }
}
