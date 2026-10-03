package org.fetarute.fetaruteTCAddon.drive.hud;

import java.util.Optional;
import org.bukkit.util.Vector;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.StopWindow;

/**
 * 发光停车标的位置与颜色。
 *
 * <p>车站牌子按列车中心对标，停车位置标按车头对标：对准部位停在停车点上算停准。驾驶员坐的位置离对准部位有一段距离
 * （按列车中心对标的长编组约为半个车长），所以停车标画在“停车点沿轨道前移这段距离”处：列车停准时，驾驶员正好在停车标上方。 本类不依赖服务器对象，便于单测。
 */
public final class StopMarkerGeometry {

  /**
   * 停车标的摆放。
   *
   * @param position 停车标中心（轨道面上）
   * @param direction 停车标处轨道的走向（水平单位向量，朝列车前进方向）；标线横跨轨道
   */
  public record Placement(Vector position, Vector direction) {
    public Placement {
      position = position.clone();
      direction = direction.clone();
    }

    @Override
    public Vector position() {
      return position.clone();
    }

    @Override
    public Vector direction() {
      return direction.clone();
    }

    /** 走向的偏航角（Minecraft 约定，度）：0 为朝南（+Z），90 为朝西（-X）。 */
    public float yaw() {
      return (float) Math.toDegrees(Math.atan2(-direction.getX(), direction.getZ()));
    }
  }

  /** 停车标颜色。 */
  public enum Tone {
    /** 还没停准。 */
    APPROACH,
    /** 已在停准范围内。 */
    ON_MARK,
    /** 进站后越过可开门范围。 */
    OVERRUN
  }

  private StopMarkerGeometry() {}

  /**
   * 算出停车标的位置。
   *
   * @param stopPoint 停车点：对准部位应停的位置
   * @param railAxis 停车点处轨道的走向（不分正反）；为空或水平分量为零时按列车走向
   * @param trainTravel 列车前进方向（车尾指向车头）
   * @param reference 列车上对准停车点的部位（列车中心或车头）此刻的位置
   * @param seat 驾驶员的位置
   * @return 列车走向量不出时为空
   */
  public static Optional<Placement> place(
      Vector stopPoint, Vector railAxis, Vector trainTravel, Vector reference, Vector seat) {
    if (stopPoint == null || reference == null || seat == null) {
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
    // 驾驶员在对准部位前方多远（沿列车走向）：停准时驾驶员就在停车点前方这么远。
    double seatAhead =
        (seat.getX() - reference.getX()) * travel.getX()
            + (seat.getZ() - reference.getZ()) * travel.getZ();
    Vector position =
        new Vector(
            stopPoint.getX() + axis.getX() * seatAhead,
            stopPoint.getY(),
            stopPoint.getZ() + axis.getZ() * seatAhead);
    return Optional.of(new Placement(position, axis));
  }

  /**
   * 停车标颜色：与车站提示同一套判定。
   *
   * @param remainingBlocks 列车中心到停车点的距离（越过为负）
   * @param precise 由站台按实际位置量出（进站后）
   * @param window 停车窗口
   */
  public static Tone tone(double remainingBlocks, boolean precise, StopWindow window) {
    if (precise && Math.abs(remainingBlocks) <= window.accurateBlocks()) {
      return Tone.ON_MARK;
    }
    if (precise && remainingBlocks < -window.acceptBlocks()) {
      return Tone.OVERRUN;
    }
    return Tone.APPROACH;
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
