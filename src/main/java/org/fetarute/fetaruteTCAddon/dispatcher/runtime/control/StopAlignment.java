package org.fetarute.fetaruteTCAddon.dispatcher.runtime.control;

import com.bergerkiller.bukkit.tc.controller.MinecartGroup;
import com.bergerkiller.bukkit.tc.controller.MinecartMember;
import org.bukkit.block.BlockFace;
import org.bukkit.util.Vector;

/**
 * 驾驶员停车对位：列车中心相对停车点的偏移与停车窗口。
 *
 * <p>停车点与 TrainCarts 对位一致：列车中心停在车站牌子的轨道中心；车站股道上有对应节数的停车位置标时，改为车头停在标志的轨道中心。 偏移沿列车前进方向量，越过为正、未到为负。
 *
 * <p>停车窗口的大小见 {@link StopWindow}。
 */
public final class StopAlignment {

  /** 用列车的哪个部位对准停车点。 */
  public enum Reference {
    /** 列车中心（车头与车尾的中点），车站牌子的默认对位。 */
    CENTER,
    /** 车头（第一节车厢），停车位置标的对位。 */
    HEAD
  }

  /** 停车结果：偏移落在停车窗口的哪一段。 */
  public enum Outcome {
    /** 停准。 */
    ACCURATE,
    /** 可开门。 */
    ACCEPTED,
    /** 停短：须前移。 */
    SHORT,
    /** 停过头，仍可开门。 */
    OVERRUN,
    /** 越过太多：越站，本站不停。 */
    SKIPPED
  }

  private StopAlignment() {}

  /**
   * 水平面上 {@code center} 相对 {@code stopPoint} 沿 {@code travel} 方向的偏移。
   *
   * @return {@code travel} 水平分量为零时为 {@code NaN}
   */
  public static double signedOffset(Vector center, Vector stopPoint, Vector travel) {
    if (center == null || stopPoint == null || travel == null) {
      return Double.NaN;
    }
    double tx = travel.getX();
    double tz = travel.getZ();
    double length = Math.sqrt(tx * tx + tz * tz);
    if (!(length > 1.0e-6)) {
      return Double.NaN;
    }
    double dx = center.getX() - stopPoint.getX();
    double dz = center.getZ() - stopPoint.getZ();
    return (dx * tx + dz * tz) / length;
  }

  /**
   * 列车（按 {@code reference} 取中心或车头）相对停车点的偏移。列车总是朝车头方向走：前进方向取车尾指向车头，单节车取它的行进方向。
   *
   * @return 不在同一世界或量不出方向时为 {@code NaN}
   */
  public static double groupOffset(
      MinecartGroup group, java.util.UUID worldId, Vector stopPoint, Reference reference) {
    if (group == null || group.isEmpty() || group.getWorld() == null) {
      return Double.NaN;
    }
    if (!group.getWorld().getUID().equals(worldId)) {
      return Double.NaN;
    }
    Vector point = reference == Reference.HEAD ? head(group) : center(group);
    return signedOffset(point, stopPoint, travel(group));
  }

  /** 车头（第一节车厢）的位置。 */
  public static Vector head(MinecartGroup group) {
    return group.head().getEntity().getLocation().toVector();
  }

  /** 列车中心：车头与车尾的中点。 */
  public static Vector center(MinecartGroup group) {
    Vector headPos = group.head().getEntity().getLocation().toVector();
    Vector tailPos = group.tail().getEntity().getLocation().toVector();
    return headPos.add(tailPos).multiply(0.5);
  }

  /** 列车前进方向：车尾指向车头，单节车取它的行进方向；量不出时为 {@code null}。 */
  public static Vector travel(MinecartGroup group) {
    if (group.size() < 2) {
      BlockFace direction = group.head().getDirection();
      return direction == null ? null : direction.getDirection();
    }
    Vector headPos = group.head().getEntity().getLocation().toVector();
    Vector tailPos = group.tail().getEntity().getLocation().toVector();
    return headPos.subtract(tailPos);
  }

  /** 车身沿轨道的长度（格）：相邻车厢中心的距离之和，弯道上比车头到车尾的直线长；单节车为 0。 */
  public static double bodyLengthBlocks(MinecartGroup group) {
    if (group == null || group.size() < 2) {
      return 0.0;
    }
    double total = 0.0;
    Vector previous = null;
    for (MinecartMember<?> member : group) {
      Vector position = member.getEntity().getLocation().toVector();
      if (previous != null) {
        total += position.distance(previous);
      }
      previous = position;
    }
    return total;
  }

  /** 车头到列车中心的水平距离（格）；单节车为 0。 */
  public static double halfLengthBlocks(MinecartGroup group) {
    if (group == null || group.size() < 2) {
      return 0.0;
    }
    Vector headPos = group.head().getEntity().getLocation().toVector();
    Vector tailPos = group.tail().getEntity().getLocation().toVector();
    double dx = headPos.getX() - tailPos.getX();
    double dz = headPos.getZ() - tailPos.getZ();
    return Math.sqrt(dx * dx + dz * dz) / 2.0;
  }
}
