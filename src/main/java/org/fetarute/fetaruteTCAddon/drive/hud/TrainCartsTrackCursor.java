package org.fetarute.fetaruteTCAddon.drive.hud;

import com.bergerkiller.bukkit.common.utils.WorldUtil;
import com.bergerkiller.bukkit.tc.utils.TrackWalkingPoint;
import java.util.Objects;
import java.util.Optional;
import java.util.function.LongSupplier;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.util.Vector;

/**
 * 包着 TrainCarts {@link TrackWalkingPoint} 的游标：弯道、斜向轨道、坡道与 TCCoasters 轨道都贴着轨道走，道岔按此刻的开向。
 *
 * <p>每走一步前先确认这一步可能读到的区块都已加载（读方块会把区块同步加载进来），没加载就不走、报 {@link TrackProfile.Status#BLOCKED}。确认一次覆盖
 * {@link #VERIFY_RADIUS_BLOCKS} 格，同一 tick 内走出这个范围前不再重查。不开 TrainCarts 的环路过滤：走的距离本来就有上限，而 TCCoasters
 * 的几个节点共用同一块 rail block 时它会误判成环。只在服务器主线程使用。
 */
final class TrainCartsTrackCursor implements TrackProfile.Cursor {

  /** 检查区块时在这一步之外多留的余量（格）：轨道在区块边上时下一段可能在相邻区块。 */
  private static final double CHUNK_MARGIN_BLOCKS = 2.0;

  /** 一次确认已加载的范围（格，方形半径）。 */
  static final double VERIFY_RADIUS_BLOCKS = 16.0;

  /** 沿轨道行走的底层操作；服务器上是 TrainCarts 的 {@link TrackWalkingPoint}。 */
  interface Walker {
    /** 往前走 {@code blocks} 格；走满时为真。 */
    boolean move(double blocks);

    /** 最近一次 {@link #move} 实际走了多少格（不是累计）。 */
    double moved();

    Vector position();

    Vector direction();
  }

  /** 以某点为中心、方形半径若干格内的区块都已加载。 */
  @FunctionalInterface
  interface AreaLoaded {
    boolean test(Vector center, double radius);
  }

  private final Walker walker;
  private final AreaLoaded loaded;
  private final LongSupplier ticks;
  private Vector position;
  private Vector direction;
  private Vector verifiedCenter;
  private long verifiedTick;

  TrainCartsTrackCursor(Walker walker, AreaLoaded loaded, LongSupplier ticks) {
    this.walker = Objects.requireNonNull(walker, "walker");
    this.loaded = Objects.requireNonNull(loaded, "loaded");
    this.ticks = Objects.requireNonNull(ticks, "ticks");
    capture();
  }

  /**
   * 在 {@code start} 处朝 {@code heading} 一侧打开游标，起点吸附到轨道上。
   *
   * @return 起点区块没加载、没有轨道或 TrainCarts 读取出错时为空
   */
  static Optional<TrackProfile.Cursor> open(World world, Vector start, Vector heading) {
    Objects.requireNonNull(world, "world");
    AreaLoaded loaded =
        (center, radius) ->
            WorldUtil.areBlocksLoaded(
                world,
                (int) Math.floor(center.getX()),
                (int) Math.floor(center.getZ()),
                (int) Math.ceil(radius));
    if (!loaded.test(start, CHUNK_MARGIN_BLOCKS)) {
      return Optional.empty();
    }
    try {
      TrackWalkingPoint point = new TrackWalkingPoint(start.toLocation(world), heading.clone());
      if (point.failReason != TrackWalkingPoint.FailReason.NONE) {
        return Optional.empty();
      }
      // 不跳过的话第一次 move 只返回起点；走 0 格把起点吸附到轨道上并取出那里的走向。
      point.skipFirst();
      if (!point.move(0.0)) {
        return Optional.empty();
      }
      return Optional.of(
          new TrainCartsTrackCursor(
              new TrainCartsWalker(point), loaded, () -> Bukkit.getCurrentTick()));
    } catch (RuntimeException | LinkageError ex) {
      // TrainCarts 版本不同或轨道正在变化：这次读不到，由调用方隔一会儿再试。
      return Optional.empty();
    }
  }

  @Override
  public Vector position() {
    return position.clone();
  }

  @Override
  public Vector direction() {
    return direction.clone();
  }

  @Override
  public TrackProfile.Advance advance(double blocks) {
    if (!areaLoaded(blocks + CHUNK_MARGIN_BLOCKS)) {
      return new TrackProfile.Advance(TrackProfile.Status.BLOCKED, 0.0);
    }
    try {
      boolean full = walker.move(blocks);
      double moved = full ? blocks : walker.moved();
      capture();
      return new TrackProfile.Advance(
          full ? TrackProfile.Status.MOVED : TrackProfile.Status.ENDED, moved);
    } catch (RuntimeException | LinkageError ex) {
      return new TrackProfile.Advance(TrackProfile.Status.ENDED, 0.0);
    }
  }

  /** 这一步要读的范围已确认加载：同一 tick 内还在上次确认的范围里就不重查（区块可能在 tick 之间卸载）。 */
  private boolean areaLoaded(double reach) {
    long tick = ticks.getAsLong();
    if (verifiedCenter != null
        && tick == verifiedTick
        && Math.max(
                    Math.abs(position.getX() - verifiedCenter.getX()),
                    Math.abs(position.getZ() - verifiedCenter.getZ()))
                + reach
            <= VERIFY_RADIUS_BLOCKS) {
      return true;
    }
    if (!loaded.test(position, Math.max(VERIFY_RADIUS_BLOCKS, reach))) {
      verifiedCenter = null;
      return false;
    }
    verifiedCenter = position.clone();
    verifiedTick = tick;
    return true;
  }

  /** 记下行走点此刻的位置与走向；之后只读这份记录。 */
  private void capture() {
    position = walker.position();
    direction = walker.direction();
  }

  /** TrainCarts 行走点的包装。 */
  private static final class TrainCartsWalker implements Walker {
    private final TrackWalkingPoint point;

    TrainCartsWalker(TrackWalkingPoint point) {
      this.point = point;
    }

    @Override
    public boolean move(double blocks) {
      return point.move(blocks);
    }

    @Override
    public double moved() {
      return point.moved;
    }

    @Override
    public Vector position() {
      return point.state.positionLocation().toVector();
    }

    @Override
    public Vector direction() {
      return point.state.motionVector();
    }
  }
}
