package org.fetarute.fetaruteTCAddon.drive.hud;

import java.util.Optional;
import org.bukkit.util.Vector;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.StopAlignment;

/**
 * 发光停车标的位置与颜色。
 *
 * <p>车站按列车中心对标：列车中心停在车站牌子的轨道中心（停车点）上算停准。驾驶员坐的位置离列车中心有一段距离（长编组时约为半个车长），
 * 所以停车标画在“停车点沿轨道前移这段距离”处：列车停准时，驾驶员正好在停车标上方。 本类不依赖服务器对象，便于单测。
 */
public final class StopMarkerGeometry {

  /**
   * 停车标的摆放。
   *
   * @param position 停车标中心（轨道面上）
   * @param yaw 停车标朝向（Minecraft 偏航角，度）：沿轨道走向，标线横跨轨道
   */
  public record Placement(Vector position, float yaw) {
    public Placement {
      position = position.clone();
    }

    @Override
    public Vector position() {
      return position.clone();
    }
  }

  /** 停车标颜色。 */
  public enum Tone {
    /** 还没停准。 */
    APPROACH,
    /** 已在停准范围内。 */
    ON_MARK,
    /** 越过可开门范围。 */
    OVERRUN
  }

  private StopMarkerGeometry() {}

  /**
   * 算出停车标的位置。
   *
   * @param stopPoint 停车点：列车中心应停的位置
   * @param railAxis 停车点处轨道的走向（不分正反）；为空或水平分量为零时按列车走向
   * @param trainTravel 列车前进方向（车尾指向车头）
   * @param trainCenter 列车中心（车头与车尾的中点）
   * @param seat 驾驶员的位置
   * @return 列车走向量不出时为空
   */
  public static Optional<Placement> place(
      Vector stopPoint, Vector railAxis, Vector trainTravel, Vector trainCenter, Vector seat) {
    if (stopPoint == null || trainCenter == null || seat == null) {
      return Optional.empty();
    }
    Vector travel = horizontalUnit(trainTravel);
    if (travel == null) {
      return Optional.empty();
    }
    Vector axis = horizontalUnit(railAxis);
    if (axis == null) {
      axis = travel;
    } else if (axis.dot(travel) < 0.0) {
      axis.multiply(-1.0);
    }
    // 驾驶员在列车中心前方多远（沿列车走向）：停准时驾驶员就在停车点前方这么远。
    double seatAhead =
        (seat.getX() - trainCenter.getX()) * travel.getX()
            + (seat.getZ() - trainCenter.getZ()) * travel.getZ();
    Vector position =
        new Vector(
            stopPoint.getX() + axis.getX() * seatAhead,
            stopPoint.getY(),
            stopPoint.getZ() + axis.getZ() * seatAhead);
    return Optional.of(new Placement(position, yaw(axis)));
  }

  /**
   * 停车标颜色：与车站提示同一套判定。
   *
   * @param remainingBlocks 列车中心到停车点的距离（越过为负）
   * @param precise 由站台按实际位置量出（进站后）
   */
  public static Tone tone(double remainingBlocks, boolean precise) {
    if (precise && Math.abs(remainingBlocks) <= StopAlignment.accurateBlocks()) {
      return Tone.ON_MARK;
    }
    if (remainingBlocks < -StopAlignment.acceptBlocks()) {
      return Tone.OVERRUN;
    }
    return Tone.APPROACH;
  }

  /** 水平方向的偏航角：0 为朝南（+Z），90 为朝西（-X）。 */
  static float yaw(Vector direction) {
    return (float) Math.toDegrees(Math.atan2(-direction.getX(), direction.getZ()));
  }

  private static Vector horizontalUnit(Vector vector) {
    if (vector == null) {
      return null;
    }
    double x = vector.getX();
    double z = vector.getZ();
    double length = Math.sqrt(x * x + z * z);
    if (!(length > 1.0e-6)) {
      return null;
    }
    return new Vector(x / length, 0.0, z / length);
  }
}
