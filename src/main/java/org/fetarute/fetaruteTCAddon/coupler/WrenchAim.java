package org.fetarute.fetaruteTCAddon.coupler;

import java.util.Optional;
import org.bukkit.util.Vector;

/**
 * 扳手点中了车厢的哪儿：取玩家视线与车厢轴线（车厢中心 ± 半车长，沿车厢自身朝向）的最近点，按它在轴线上的位置分成前端、后端与中段。纯计算。
 *
 * <p>离某一端不到 {@link #END_ZONE_BLOCKS} 格（短车厢按半车长的 {@link #END_ZONE_SHARE} 收窄，中段总留得出来）算点中那一端。
 */
public final class WrenchAim {

  /** 端部的判定范围（格）。 */
  static final double END_ZONE_BLOCKS = 1.5;

  /** 短车厢的端部判定范围占半车长的比例。 */
  static final double END_ZONE_SHARE = 0.45;

  /** 视线与轴线几乎平行时（顺着车厢看）的判定门槛。 */
  private static final double PARALLEL_EPSILON = 1.0e-6;

  private WrenchAim() {}

  /** 点中的位置。 */
  public enum Spot {
    /** 车厢前端（按车厢自身朝向）。 */
    FRONT,
    /** 车厢后端。 */
    REAR,
    /** 车厢中段。 */
    MIDDLE;

    /** 点中的是哪一端；中段为空。 */
    public Optional<CouplerState.End> end() {
      return switch (this) {
        case FRONT -> Optional.of(CouplerState.End.FRONT);
        case REAR -> Optional.of(CouplerState.End.REAR);
        case MIDDLE -> Optional.empty();
      };
    }
  }

  /**
   * 判定点中的位置。
   *
   * @param eye 玩家眼睛位置
   * @param look 玩家视线方向
   * @param center 车厢中心
   * @param forward 车厢自身朝向（不必是单位向量）
   * @param halfLength 半车长（格）
   */
  public static Spot spot(
      Vector eye, Vector look, Vector center, Vector forward, double halfLength) {
    double h = halfLength > 0.0 && Double.isFinite(halfLength) ? halfLength : 0.5;
    double t = axisParameter(eye, look, center, forward);
    t = Math.max(-h, Math.min(h, t));
    double zone = Math.min(END_ZONE_BLOCKS, h * END_ZONE_SHARE);
    if (t >= h - zone) {
      return Spot.FRONT;
    }
    if (t <= -h + zone) {
      return Spot.REAR;
    }
    return Spot.MIDDLE;
  }

  /** 视线与车厢轴线最近点在轴线上的位置（自车厢中心沿朝向量的格数，未截到车长以内）。视线朝后（最近点在眼睛背后）或顺着轴线看时取眼睛在轴线上的投影。 */
  static double axisParameter(Vector eye, Vector look, Vector center, Vector forward) {
    Vector f = unit(forward);
    Vector d = unit(look);
    if (f == null) {
      return 0.0;
    }
    Vector w0 = center.clone().subtract(eye);
    double fromEye = -f.dot(w0);
    if (d == null) {
      return fromEye;
    }
    double b = f.dot(d);
    double denom = 1.0 - b * b;
    if (denom < PARALLEL_EPSILON) {
      return fromEye;
    }
    double dw = f.dot(w0);
    double ew = d.dot(w0);
    double alongRay = (ew - b * dw) / denom;
    if (alongRay < 0.0) {
      return fromEye;
    }
    return (b * ew - dw) / denom;
  }

  private static Vector unit(Vector vector) {
    if (vector == null) {
      return null;
    }
    double length = vector.length();
    if (!(length > 0.0) || !Double.isFinite(length)) {
      return null;
    }
    return vector.clone().multiply(1.0 / length);
  }
}
