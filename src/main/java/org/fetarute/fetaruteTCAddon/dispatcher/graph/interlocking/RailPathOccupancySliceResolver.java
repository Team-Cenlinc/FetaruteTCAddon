package org.fetarute.fetaruteTCAddon.dispatcher.graph.interlocking;

import com.bergerkiller.bukkit.tc.controller.components.RailPath;
import java.util.Collection;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.bukkit.util.Vector;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.explore.RailBlockPos;

/**
 * 根据同一条 TrainCarts 路径上的现场位置解析列车实际占用切片。
 *
 * <p>该解析器只处理调用方已经证明“所有样本属于同一 RailPath 与同一轨道原点”的场景。任一样本不能唯一投影时返回空值，避免在自交 TCCoasters
 * 路径上裁错区间并提前释放物理联锁。
 */
public final class RailPathOccupancySliceResolver {

  private RailPathOccupancySliceResolver() {}

  /**
   * 解析同一路径上覆盖全部位置样本并包含两端安全余量的方块切片。
   *
   * @param path 已验证为全部样本共用的路径
   * @param railBlock 路径所属的共同轨道方块原点
   * @param relativePositions 已转换为该轨道方块相对坐标的位置样本
   * @param endPaddingBlocks 切片两端分别增加的非负距离
   * @return 完整且不可变的局部足迹；任一定位证据无效时为空
   */
  public static Optional<Set<RailFootprintCell>> resolve(
      RailPath path,
      RailBlockPos railBlock,
      Collection<Vector> relativePositions,
      double endPaddingBlocks) {
    Objects.requireNonNull(path, "path");
    Objects.requireNonNull(railBlock, "railBlock");
    Objects.requireNonNull(relativePositions, "relativePositions");
    if (path.isEmpty()
        || relativePositions.isEmpty()
        || !Double.isFinite(endPaddingBlocks)
        || endPaddingBlocks < 0.0) {
      return Optional.empty();
    }

    double minimumDistance = Double.POSITIVE_INFINITY;
    double maximumDistance = Double.NEGATIVE_INFINITY;
    for (Vector position : relativePositions) {
      if (position == null) {
        return Optional.empty();
      }
      Optional<RailPathFootprintRasterizer.PathLocation> location =
          RailPathFootprintRasterizer.locate(path, position);
      if (location.isEmpty()) {
        return Optional.empty();
      }
      double distance = location.orElseThrow().distanceFromStart();
      minimumDistance = Math.min(minimumDistance, distance);
      maximumDistance = Math.max(maximumDistance, distance);
    }

    double pathLength = pathLength(path);
    if (!Double.isFinite(pathLength) || pathLength <= 0.0) {
      return Optional.empty();
    }
    double fromDistance = Math.max(0.0, minimumDistance - endPaddingBlocks);
    double toDistance = Math.min(pathLength, maximumDistance + endPaddingBlocks);
    if (fromDistance >= toDistance) {
      return Optional.empty();
    }
    return RailPathFootprintRasterizer.rasterize(path, railBlock, fromDistance, toDistance);
  }

  private static double pathLength(RailPath path) {
    double length = 0.0;
    for (RailPath.Segment segment : path.getSegments()) {
      if (!Double.isFinite(segment.l) || segment.l < 0.0) {
        return Double.NaN;
      }
      length += segment.l;
      if (!Double.isFinite(length)) {
        return Double.NaN;
      }
    }
    return length;
  }
}
