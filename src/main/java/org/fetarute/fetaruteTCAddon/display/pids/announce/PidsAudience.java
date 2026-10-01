package org.fetarute.fetaruteTCAddon.display.pids.announce;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;
import org.fetarute.fetaruteTCAddon.display.pids.screen.PidsScreen;

/**
 * 广播来源：离玩家最近的、已加载的、绑定了车站的站台屏。
 *
 * <p>屏幕只用来认车站，不用来认站台：站台屏两侧不对称，玩家站在这边，最近的屏可能挂在对面。来源屏幕的车站就是玩家所在的车站，
 * 全站的广播都播给他，声源取这块屏。为广播加载区块是不允许的，未加载的屏幕不算。
 */
final class PidsAudience {

  private PidsAudience() {}

  /**
   * 找来源屏幕。
   *
   * @param screens 全部屏幕
   * @param worldId 玩家所在世界
   * @param x 玩家坐标
   * @param y 玩家坐标
   * @param z 玩家坐标
   * @param range 最大距离（方块）
   * @param loaded 屏幕所在区块是否已加载
   * @return 最近的屏幕；范围内没有时为空
   */
  static Optional<PidsScreen> source(
      Collection<PidsScreen> screens,
      UUID worldId,
      double x,
      double y,
      double z,
      double range,
      Predicate<PidsScreen> loaded) {
    PidsScreen best = null;
    double bestDistance = range * range;
    for (PidsScreen screen : screens) {
      if (!screen.worldId().equals(worldId) || screen.station().isEmpty()) {
        continue;
      }
      PidsScreen.Position center = screen.center();
      double dx = center.x() + 0.5 - x;
      double dy = center.y() + 0.5 - y;
      double dz = center.z() + 0.5 - z;
      double distance = dx * dx + dy * dy + dz * dz;
      if (distance <= bestDistance && loaded.test(screen)) {
        best = screen;
        bestDistance = distance;
      }
    }
    return Optional.ofNullable(best);
  }
}
