package org.fetarute.fetaruteTCAddon.dispatcher.runtime.control;

import com.bergerkiller.bukkit.tc.controller.MinecartGroup;
import com.bergerkiller.bukkit.tc.controller.MinecartMember;
import org.bukkit.block.BlockFace;
import org.bukkit.util.Vector;

/**
 * 驾驶员停车对位：列车中心相对停车点的偏移与停车窗口。
 *
 * <p>停车点与 TrainCarts 对位一致：列车中心停在车站牌子的轨道中心。偏移沿列车前进方向量，越过为正、未到为负。
 */
public final class StopAlignment {

  /** 偏移在这个范围内算停准。 */
  public static final double ACCURATE_BLOCKS = 1.5;

  /** 偏移在这个范围内可以开门；未到更多时须前移，越过更多时防护强制停车。 */
  public static final double ACCEPT_BLOCKS = 4.0;

  /** 停车窗口。 */
  public enum Window {
    ACCURATE,
    ACCEPTED,
    SHORT,
    OVERRUN
  }

  private StopAlignment() {}

  /** 偏移所在的窗口。量不出偏移（{@code NaN}）时按可接受处理，不挡住停站。 */
  public static Window classify(double offsetBlocks) {
    if (Double.isNaN(offsetBlocks)) {
      return Window.ACCEPTED;
    }
    double abs = Math.abs(offsetBlocks);
    if (abs <= ACCURATE_BLOCKS) {
      return Window.ACCURATE;
    }
    if (abs <= ACCEPT_BLOCKS) {
      return Window.ACCEPTED;
    }
    return offsetBlocks < 0.0 ? Window.SHORT : Window.OVERRUN;
  }

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
   * 列车中心相对停车点的偏移。列车总是朝车头方向走：前进方向取车尾指向车头，单节车取它的行进方向。
   *
   * @return 不在同一世界或量不出方向时为 {@code NaN}
   */
  public static double groupOffset(MinecartGroup group, java.util.UUID worldId, Vector stopPoint) {
    if (group == null || group.isEmpty() || group.getWorld() == null) {
      return Double.NaN;
    }
    if (!group.getWorld().getUID().equals(worldId)) {
      return Double.NaN;
    }
    MinecartMember<?> head = group.head();
    MinecartMember<?> tail = group.tail();
    Vector headPos = head.getEntity().getLocation().toVector();
    Vector tailPos = tail.getEntity().getLocation().toVector();
    Vector travel;
    if (head == tail) {
      BlockFace direction = head.getDirection();
      travel = direction == null ? null : direction.getDirection();
    } else {
      travel = headPos.clone().subtract(tailPos);
    }
    Vector center = headPos.clone().add(tailPos).multiply(0.5);
    return signedOffset(center, stopPoint, travel);
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
