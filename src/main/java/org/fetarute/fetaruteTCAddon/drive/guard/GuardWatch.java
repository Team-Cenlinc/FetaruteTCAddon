package org.fetarute.fetaruteTCAddon.drive.guard;

import org.bukkit.util.Vector;

/** 车掌监视的几何判定：视线、车身方向、站台方向都只看水平分量。不依赖服务器对象。 */
final class GuardWatch {

  /** 出站监视：视线与站台一侧或车后方向的夹角最多这么多度。 */
  static final double DEPARTURE_ANGLE_DEGREES = 60.0;

  private GuardWatch() {}

  /**
   * 关门监视：车掌下到了站台（不在车上）、离自己那节车厢够近、视线沿着车身（朝车头或车尾都行）。
   *
   * @param onTrain 车掌此刻坐在车上
   * @param distance 车掌离自己那节车厢的距离（格）
   * @param radius 最远几格
   * @param look 车掌的视线方向
   * @param axis 车身方向（不分正反）
   * @param maxDegrees 视线与车身的夹角最多几度
   */
  static boolean closingWatch(
      boolean onTrain,
      double distance,
      double radius,
      Vector look,
      Vector axis,
      double maxDegrees) {
    return !onTrain && distance <= radius && alongAxis(look, axis, maxDegrees);
  }

  /**
   * 出站监视：车掌坐在自己的座位上，朝站台一侧或朝车后（沿站台往后）看。
   *
   * @param seated 坐在车掌座位上
   * @param look 视线方向
   * @param platformSide 站台在哪个方向；两侧都是站台或判定不出时为 {@code null}
   * @param rearward 车后方向（车掌所在驾驶室面朝的方向）
   */
  static boolean departureWatch(boolean seated, Vector look, Vector platformSide, Vector rearward) {
    if (!seated) {
      return false;
    }
    return toward(look, rearward, DEPARTURE_ANGLE_DEGREES)
        || (platformSide != null && toward(look, platformSide, DEPARTURE_ANGLE_DEGREES));
  }

  /** 视线水平方向与一条轴（不分正反）的夹角不超过 {@code maxDegrees}；任一方向取不到时不算。 */
  static boolean alongAxis(Vector look, Vector axis, double maxDegrees) {
    double cos = horizontalCos(look, axis);
    return !Double.isNaN(cos) && Math.abs(cos) >= Math.cos(Math.toRadians(maxDegrees));
  }

  /** 视线水平方向与某个方向的夹角不超过 {@code maxDegrees}；任一方向取不到时不算。 */
  static boolean toward(Vector look, Vector direction, double maxDegrees) {
    double cos = horizontalCos(look, direction);
    return !Double.isNaN(cos) && cos >= Math.cos(Math.toRadians(maxDegrees));
  }

  private static double horizontalCos(Vector a, Vector b) {
    if (a == null || b == null) {
      return Double.NaN;
    }
    double al = Math.hypot(a.getX(), a.getZ());
    double bl = Math.hypot(b.getX(), b.getZ());
    if (al < 1.0e-6 || bl < 1.0e-6) {
      return Double.NaN;
    }
    return (a.getX() * b.getX() + a.getZ() * b.getZ()) / (al * bl);
  }
}
