package org.fetarute.fetaruteTCAddon.drive.hud;

import java.util.Objects;
import java.util.Optional;
import org.bukkit.util.Vector;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.StopWindow;

/**
 * 发光停车标的位置与颜色。
 *
 * <p>车站牌子按列车中心对标，停车位置标按车头最前端对标：对准部位停在停车点上算停准。驾驶员坐的位置离对准部位有一段距离
 * （按列车中心对标的长编组约为半个车长），所以停车标画在“停车点沿轨道前移这段距离”处：列车停准时，驾驶员正好在停车标上方。
 * 斜向、弯曲或带坡的站台不能按停车点处的走向直线外推，前移要沿轨道走（{@link Track}）。本类不依赖服务器对象，便于单测。
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

  /** 沿轨道走一段。服务器上按 TrainCarts 的轨道走，轨道读不到时退回 {@link #STRAIGHT}。 */
  @FunctionalInterface
  public interface Track {
    /** 按起点处的走向直线外推、高度不变：轨道读不到时的估计，在斜向或弯曲的轨道上会偏离轨道。 */
    Track STRAIGHT =
        (start, heading, distance) ->
            Optional.of(
                new Placement(
                    new Vector(
                        start.getX() + heading.getX() * distance,
                        start.getY(),
                        start.getZ() + heading.getZ() * distance),
                    heading));

    /**
     * @param start 起点（轨道上）
     * @param heading 起点处算作“前”的一侧（水平单位向量）
     * @param distance 沿轨道走多远（格）；为负时往后走
     * @return 走到的位置与那里轨道朝“前”一侧的走向；轨道断开、区块未加载时为空
     */
    Optional<Placement> walk(Vector start, Vector heading, double distance);
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
   * @param forward 停车点处轨道朝列车前进一侧的走向；为空或水平分量为零时按列车走向
   * @param trainTravel 列车前进方向（车尾指向车头）
   * @param headAheadBlocks 车头（第一节车厢中心）在对准部位前方多远，沿车身量（格）：按车头最前端对准为负的半个车体长度，按列车中心对准为车身长的一半
   * @param head 车头（第一节车厢）此刻的位置
   * @param seat 驾驶员的位置
   * @param track 从停车点沿轨道前移；走不通时按直线估计
   * @return 列车走向量不出时为空
   */
  public static Optional<Placement> place(
      Vector stopPoint,
      Vector forward,
      Vector trainTravel,
      double headAheadBlocks,
      Vector head,
      Vector seat,
      Track track) {
    Objects.requireNonNull(track, "track");
    if (stopPoint == null || head == null || seat == null) {
      return Optional.empty();
    }
    Vector travel = horizontalUnit(trainTravel);
    if (travel == null) {
      return Optional.empty();
    }
    Vector axis = horizontalUnit(forward);
    Vector heading = axis == null ? travel : axis;
    // 驾驶员在对准部位前方多远：沿车身量到车头，再加座位相对车头的一小段。弯曲站台上不能拿头尾连线量，弦比弧短。
    double seatAhead =
        headAheadBlocks
            + (seat.getX() - head.getX()) * travel.getX()
            + (seat.getZ() - head.getZ()) * travel.getZ();
    Placement walked =
        track
            .walk(stopPoint, heading, seatAhead)
            .or(() -> Track.STRAIGHT.walk(stopPoint, heading, seatAhead))
            .orElseThrow();
    // 标线横跨走到处的轨道；那里的走向量不出（竖直轨道）时沿用停车点处的走向。
    Vector direction = horizontalUnit(walked.direction());
    return Optional.of(new Placement(walked.position(), direction == null ? heading : direction));
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
