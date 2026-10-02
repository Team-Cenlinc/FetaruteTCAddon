package org.fetarute.fetaruteTCAddon.dispatcher.graph.interlocking;

import com.bergerkiller.bukkit.common.bases.IntVector3;
import com.bergerkiller.bukkit.tc.controller.components.RailPath;
import com.bergerkiller.bukkit.tc.utils.BlockIterator;
import java.util.Collections;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.bukkit.util.Vector;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.explore.RailBlockPos;

/**
 * 把 TrainCarts 实际选择的三维 {@link RailPath} 离散为世界方块足迹。
 *
 * <p>实现直接复用 TrainCarts 的逐线段方块遍历，因而会保留曲线、坡道与多段路径的真实形状；不能用两个图节点端点之间的插值替代，否则平交道口与上下分层轨道会得到错误的联锁关系。
 */
public final class RailPathFootprintRasterizer {

  private static final double PROJECTION_TIE_EPSILON = 1.0e-12;
  private static final double MAX_PROJECTION_DISTANCE_SQUARED = 1.0e-4;

  private RailPathFootprintRasterizer() {}

  /**
   * 路径上一个可唯一定位的投影点。
   *
   * @param segmentIndex {@link RailPath#getSegments()} 中的零基下标
   * @param segmentTheta 在线段内由起点指向终点的比例，范围为 {@code [0, 1]}
   * @param distanceFromStart 从整条路径起点开始累计的轨道距离
   */
  public record PathLocation(int segmentIndex, double segmentTheta, double distanceFromStart) {

    /** 创建一个已经通过路径投影验证的位置。 */
    public PathLocation {
      if (segmentIndex < 0) {
        throw new IllegalArgumentException("segmentIndex must be non-negative");
      }
      if (!Double.isFinite(segmentTheta) || segmentTheta < 0.0 || segmentTheta > 1.0) {
        throw new IllegalArgumentException("segmentTheta must be within [0, 1]");
      }
      if (!Double.isFinite(distanceFromStart) || distanceFromStart < 0.0) {
        throw new IllegalArgumentException("distanceFromStart must be finite and non-negative");
      }
    }
  }

  /**
   * 把相对 {@link RailPath.Position} 投影到路径上。
   *
   * <p>{@code Position} 的内部 wheel segment 不是 TrainCarts 公共
   * ABI，因此这里仅读取公开坐标，并要求调用方已经把位置转换为轨道方块相对坐标。绝对坐标或非有限坐标会返回空值。
   *
   * @param path 待定位的 TrainCarts 路径
   * @param relativePosition 相对路径所属轨道方块的位置
   * @return 唯一路径位置；坐标系或证据无效时为空
   */
  public static Optional<PathLocation> locate(RailPath path, RailPath.Position relativePosition) {
    Objects.requireNonNull(path, "path");
    Objects.requireNonNull(relativePosition, "relativePosition");
    if (!relativePosition.relative
        || !isFinite(relativePosition.posX, relativePosition.posY, relativePosition.posZ)) {
      return Optional.empty();
    }
    return locate(
        path, new Vector(relativePosition.posX, relativePosition.posY, relativePosition.posZ));
  }

  /**
   * 把轨道方块内的相对坐标投影到路径上，并返回其线段与累计里程。
   *
   * <p>该接口只使用 TrainCarts 公开的线段投影 ABI。空路径、非有限坐标或无法落入任何有效线段时返回空值，调用方不得据此猜测列车所在区间。
   *
   * @param path 待定位的 TrainCarts 路径
   * @param relativePosition 相对路径所属轨道方块的坐标
   * @return 唯一路径位置；证据无效时为空
   */
  public static Optional<PathLocation> locate(RailPath path, Vector relativePosition) {
    Objects.requireNonNull(path, "path");
    Objects.requireNonNull(relativePosition, "relativePosition");
    if (!isFinite(relativePosition)) {
      return Optional.empty();
    }

    RailPath.Segment[] segments = path.getSegments();
    PathLocation closest = null;
    double closestDistanceSquared = Double.POSITIVE_INFINITY;
    boolean ambiguous = false;
    double distanceFromStart = 0.0;
    for (int index = 0; index < segments.length; index++) {
      RailPath.Segment segment = segments[index];
      if (!Double.isFinite(segment.l) || segment.l < 0.0) {
        return Optional.empty();
      }
      if (segment.isZeroLength()) {
        distanceFromStart += segment.l;
        continue;
      }
      double theta = segment.calcTheta(relativePosition);
      if (!Double.isFinite(theta) || theta < 0.0 || theta > 1.0) {
        distanceFromStart += segment.l;
        continue;
      }
      double distanceSquared = segment.calcDistanceSquared(relativePosition, theta);
      if (!Double.isFinite(distanceSquared)) {
        distanceFromStart += segment.l;
        continue;
      }
      double candidateDistanceFromStart = distanceFromStart + (segment.l * theta);
      if (closest == null || isStrictlyLess(distanceSquared, closestDistanceSquared)) {
        closestDistanceSquared = distanceSquared;
        closest = new PathLocation(index, theta, candidateDistanceFromStart);
        ambiguous = false;
      } else if (nearlyEqual(distanceSquared, closestDistanceSquared)
          && !nearlyEqual(candidateDistanceFromStart, closest.distanceFromStart())) {
        ambiguous = true;
      }
      distanceFromStart += segment.l;
    }
    if (ambiguous || closest == null || closestDistanceSquared > MAX_PROJECTION_DISTANCE_SQUARED) {
      return Optional.empty();
    }
    return Optional.of(closest);
  }

  private static boolean isStrictlyLess(double left, double right) {
    return left < right && !nearlyEqual(left, right);
  }

  private static boolean nearlyEqual(double left, double right) {
    double scale = Math.max(1.0, Math.max(Math.abs(left), Math.abs(right)));
    return Math.abs(left - right) <= PROJECTION_TIE_EPSILON * scale;
  }

  private static boolean isFinite(Vector vector) {
    return isFinite(vector.getX(), vector.getY(), vector.getZ());
  }

  private static boolean isFinite(double x, double y, double z) {
    return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z);
  }

  /**
   * 返回路径经过的全部世界方块，结果按三维坐标稳定排序且不可修改。
   *
   * @param path 当前 {@code TrackWalkingPoint.currentRailPath}
   * @param railBlock 该路径所属的轨道方块
   * @return 路径实际经过的三维方块集合；空路径返回空集合
   */
  public static Set<RailFootprintCell> rasterize(RailPath path, RailBlockPos railBlock) {
    Objects.requireNonNull(path, "path");
    Objects.requireNonNull(railBlock, "railBlock");
    if (path.isEmpty()) {
      return Set.of();
    }

    Set<RailFootprintCell> cells = new TreeSet<>();
    IntVector3 origin = new IntVector3(railBlock.x(), railBlock.y(), railBlock.z());
    for (RailPath.Segment segment : path.getSegments()) {
      BlockIterator iterator = new BlockIterator(origin, segment);
      while (iterator.next()) {
        IntVector3 block = iterator.block();
        cells.add(new RailFootprintCell(block.x, block.y, block.z));
      }
    }
    return Collections.unmodifiableSet(cells);
  }

  /**
   * 把路径累计距离区间离散为世界方块足迹。
   *
   * <p>该方法用于长 TCCoasters {@link RailPath}
   * 的实时占用切片。区间必须完整落在路径内，且起点严格小于终点；任何非有限距离、坏线段或越界都会返回空值，调用方必须按证据不足停止，而不能回退为猜测的短区间。
   *
   * @param path 当前 TrainCarts 路径
   * @param railBlock 该路径所属的轨道方块
   * @param fromDistance 从路径起点累计的切片起点，包含
   * @param toDistance 从路径起点累计的切片终点
   * @return 不可变三维方块集合；区间或路径证据无效时为空
   */
  public static Optional<Set<RailFootprintCell>> rasterize(
      RailPath path, RailBlockPos railBlock, double fromDistance, double toDistance) {
    Objects.requireNonNull(path, "path");
    Objects.requireNonNull(railBlock, "railBlock");
    if (!Double.isFinite(fromDistance)
        || !Double.isFinite(toDistance)
        || fromDistance < 0.0
        || fromDistance >= toDistance
        || path.isEmpty()) {
      return Optional.empty();
    }

    RailPath.Segment[] segments = path.getSegments();
    double totalDistance = 0.0;
    for (RailPath.Segment segment : segments) {
      if (!Double.isFinite(segment.l) || segment.l < 0.0) {
        return Optional.empty();
      }
      totalDistance += segment.l;
      if (!Double.isFinite(totalDistance)) {
        return Optional.empty();
      }
    }
    if (toDistance > totalDistance) {
      return Optional.empty();
    }

    Set<RailFootprintCell> cells = new TreeSet<>();
    IntVector3 origin = new IntVector3(railBlock.x(), railBlock.y(), railBlock.z());
    double segmentStartDistance = 0.0;
    for (RailPath.Segment segment : segments) {
      double segmentEndDistance = segmentStartDistance + segment.l;
      double clippedStartDistance = Math.max(fromDistance, segmentStartDistance);
      double clippedEndDistance = Math.min(toDistance, segmentEndDistance);
      if (clippedStartDistance < clippedEndDistance) {
        double startTheta = (clippedStartDistance - segmentStartDistance) / segment.l;
        double endTheta = (clippedEndDistance - segmentStartDistance) / segment.l;
        rasterizeSegmentRange(cells, origin, segment, startTheta, endTheta);
      }
      segmentStartDistance = segmentEndDistance;
    }
    return cells.isEmpty() ? Optional.empty() : Optional.of(Collections.unmodifiableSet(cells));
  }

  private static void rasterizeSegmentRange(
      Set<RailFootprintCell> cells,
      IntVector3 origin,
      RailPath.Segment segment,
      double startTheta,
      double endTheta) {
    RailPath.Point start = interpolate(segment, startTheta);
    RailPath.Point end = interpolate(segment, endTheta);
    BlockIterator iterator = new BlockIterator(origin, new RailPath.Segment(start, end));
    while (iterator.next()) {
      IntVector3 block = iterator.block();
      cells.add(new RailFootprintCell(block.x, block.y, block.z));
    }
  }

  private static RailPath.Point interpolate(RailPath.Segment segment, double theta) {
    double inverseTheta = 1.0 - theta;
    return new RailPath.Point(
        (segment.p0.x * inverseTheta) + (segment.p1.x * theta),
        (segment.p0.y * inverseTheta) + (segment.p1.y * theta),
        (segment.p0.z * inverseTheta) + (segment.p1.z * theta),
        (segment.p0.up_x * inverseTheta) + (segment.p1.up_x * theta),
        (segment.p0.up_y * inverseTheta) + (segment.p1.up_y * theta),
        (segment.p0.up_z * inverseTheta) + (segment.p1.up_z * theta));
  }
}
