package org.fetarute.fetaruteTCAddon.drive.driver.task;

import java.util.Optional;
import org.bukkit.util.Vector;

/**
 * 前往接车：在车头驾驶室旁边找一处站得住的地方，把驾驶员送过去（不直接塞进座位，由驾驶员自己坐下并确认）。
 *
 * <p>本类不依赖服务器对象，便于单测。
 */
public final class PickupSpot {

  /** 离车头横向多远找落脚处（格），先近后远。 */
  private static final double[] SIDE_BLOCKS = {2.0, 1.5, 3.0};

  /** 落脚处的高度在车头所在高度上下各试一格。 */
  private static final int[] HEIGHT_OFFSETS = {0, 1, -1};

  /** 一个方块位置能不能站人：脚下实心，脚和头所在的两格可以通过。 */
  @FunctionalInterface
  public interface Standable {
    boolean test(int x, int y, int z);
  }

  private PickupSpot() {}

  /**
   * 车头两侧找落脚处。
   *
   * @param head 车头车厢的位置
   * @param travel 列车走向（只取水平分量）；为空或水平分量为零时按东西南北四个方向试
   * @return 落脚点（方块底面中心）；都站不住时为空
   */
  public static Optional<Vector> find(Vector head, Vector travel, Standable standable) {
    if (head == null || standable == null) {
      return Optional.empty();
    }
    Vector[] sides = sides(travel);
    int baseY = (int) Math.floor(head.getY());
    for (double distance : SIDE_BLOCKS) {
      for (Vector side : sides) {
        int x = (int) Math.floor(head.getX() + side.getX() * distance);
        int z = (int) Math.floor(head.getZ() + side.getZ() * distance);
        for (int dy : HEIGHT_OFFSETS) {
          int y = baseY + dy;
          if (standable.test(x, y, z)) {
            return Optional.of(new Vector(x + 0.5, y, z + 0.5));
          }
        }
      }
    }
    return Optional.empty();
  }

  /** 与走向垂直的两侧；走向不明时取四个方向。 */
  private static Vector[] sides(Vector travel) {
    if (travel != null) {
      double length = Math.hypot(travel.getX(), travel.getZ());
      if (length > 1.0e-6) {
        double x = travel.getX() / length;
        double z = travel.getZ() / length;
        return new Vector[] {new Vector(-z, 0.0, x), new Vector(z, 0.0, -x)};
      }
    }
    return new Vector[] {
      new Vector(1.0, 0.0, 0.0),
      new Vector(-1.0, 0.0, 0.0),
      new Vector(0.0, 0.0, 1.0),
      new Vector(0.0, 0.0, -1.0)
    };
  }
}
