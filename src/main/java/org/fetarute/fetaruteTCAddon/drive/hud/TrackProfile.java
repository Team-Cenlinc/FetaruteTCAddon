package org.fetarute.fetaruteTCAddon.drive.hud;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import org.bukkit.util.Vector;

/**
 * 从停车点沿轨道朝一侧采样出的轨道形状：每走 {@link #STEP_BLOCKS} 格记一个点，查任意距离时在相邻两点间插值。
 *
 * <p>采样可以分几次做完（每次有格数预算），长站台不必在一个 tick 里走完；还没采样到的距离从最后一个点按那里的走向直线外推。 本类不依赖服务器对象，便于单测。只在服务器主线程使用。
 */
final class TrackProfile {

  /** 采样间隔（格）。 */
  static final double STEP_BLOCKS = 1.0;

  /** 前方区块没加载时隔多久再接着走（tick）。 */
  static final long BLOCKED_RETRY_TICKS = 20L;

  private static final double EPS = 1.0e-6;

  /** 沿轨道逐段前进的游标；服务器上包着 TrainCarts 的轨道行走（{@link TrainCartsTrackCursor}）。 */
  interface Cursor {
    /** 当前位置（轨道上）。 */
    Vector position();

    /** 当前位置的轨道走向（朝前进一侧）。 */
    Vector direction();

    /** 往前走 {@code blocks} 格。 */
    Advance advance(double blocks);
  }

  /** 一次前进的结果。 */
  enum Status {
    /** 走完了要求的距离。 */
    MOVED,
    /** 前方区块没加载，这次没走；之后可以接着走。 */
    BLOCKED,
    /** 轨道到头或走不通，停在走到的地方。 */
    ENDED
  }

  /**
   * 一次前进。
   *
   * @param status 结果
   * @param moved 实际走了多少（格）
   */
  record Advance(Status status, double moved) {}

  /** 一个采样点：距起点多远、位置与轨道走向。 */
  private static final class Sample {
    final double distance;
    final double x;
    final double y;
    final double z;
    final double dx;
    final double dy;
    final double dz;

    Sample(double distance, Vector position, Vector direction) {
      this.distance = distance;
      this.x = position.getX();
      this.y = position.getY();
      this.z = position.getZ();
      this.dx = direction.getX();
      this.dy = direction.getY();
      this.dz = direction.getZ();
    }
  }

  private final List<Sample> samples = new ArrayList<>();
  private final long createdAtTick;

  /** 轨道到头后为 {@code null}。 */
  private Cursor cursor;

  private long retryAtTick = Long.MIN_VALUE;

  TrackProfile(Cursor cursor, long nowTick) {
    this.cursor = Objects.requireNonNull(cursor, "cursor");
    this.createdAtTick = nowTick;
    samples.add(new Sample(0.0, cursor.position(), cursor.direction()));
  }

  long createdAtTick() {
    return createdAtTick;
  }

  /** 起点处的轨道走向（朝采样的一侧）。 */
  Vector initialDirection() {
    Sample first = samples.get(0);
    return new Vector(first.dx, first.dy, first.dz);
  }

  /** 已采样到多远（格）。 */
  double covered() {
    return last().distance;
  }

  /** 能准确回答这个距离：已采样到，或轨道已到头。 */
  boolean reaches(double distance) {
    return cursor == null || covered() + EPS >= distance;
  }

  /**
   * 往前采样直到覆盖 {@code target}，这次最多走 {@code budget} 格；前方区块没加载时过一会儿再接着走。
   *
   * @param more 每走一步前问一次还有没有时间（各驾驶员共用的每 tick 预算）
   * @return 这次实际走了多少格
   */
  double extend(double target, double budget, long nowTick, BooleanSupplier more) {
    double walked = 0.0;
    while (cursor != null
        && nowTick >= retryAtTick
        && covered() + EPS < target
        && walked + EPS < budget
        && more.getAsBoolean()) {
      Advance advance = cursor.advance(Math.min(STEP_BLOCKS, budget - walked));
      if (advance.status() == Status.BLOCKED) {
        retryAtTick = nowTick + BLOCKED_RETRY_TICKS;
        break;
      }
      double moved = Math.max(0.0, advance.moved());
      if (moved > EPS) {
        walked += moved;
        samples.add(new Sample(covered() + moved, cursor.position(), cursor.direction()));
      }
      if (advance.status() == Status.ENDED || !(moved > EPS)) {
        // 走不动也按到头处理，免得原地空转。
        cursor = null;
      }
    }
    return walked;
  }

  /** 距起点 {@code distance} 格处的位置与轨道走向；超出已采样的部分从最后一个点按那里的走向（含坡度）直线外推。 */
  StopMarkerGeometry.Placement at(double distance) {
    Sample last = last();
    if (distance >= last.distance) {
      double beyond = distance - last.distance;
      double length = Math.sqrt(last.dx * last.dx + last.dy * last.dy + last.dz * last.dz);
      double scale = length > EPS ? beyond / length : 0.0;
      return new StopMarkerGeometry.Placement(
          new Vector(last.x + last.dx * scale, last.y + last.dy * scale, last.z + last.dz * scale),
          new Vector(last.dx, last.dy, last.dz));
    }
    int low = 0;
    int high = samples.size() - 1;
    while (high - low > 1) {
      int mid = (low + high) >>> 1;
      if (samples.get(mid).distance <= distance) {
        low = mid;
      } else {
        high = mid;
      }
    }
    Sample a = samples.get(low);
    Sample b = samples.get(high);
    double t = Math.max(0.0, (distance - a.distance) / (b.distance - a.distance));
    return new StopMarkerGeometry.Placement(
        new Vector(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t, a.z + (b.z - a.z) * t),
        new Vector(a.dx + (b.dx - a.dx) * t, a.dy + (b.dy - a.dy) * t, a.dz + (b.dz - a.dz) * t));
  }

  private Sample last() {
    return samples.get(samples.size() - 1);
  }
}
