package org.fetarute.fetaruteTCAddon.drive.driver;

import com.bergerkiller.bukkit.tc.controller.components.RailPiece;
import com.bergerkiller.bukkit.tc.controller.components.RailState;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import org.bukkit.World;
import org.bukkit.util.Vector;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.explore.RailBlockPos;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.StopAlignment;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.StopMarkIndex;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.StopMarks;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeRegistry;

/**
 * 列车进站前就要知道的停车点：车站牌子所在的轨道（与 TrainCarts 对位一致），股道上有对应节数的停车位置标时换成标志。
 *
 * <p>站台交来停站后以站台量出的停车点为准；这里供进站前的距离估计与发光停车标使用。车站轨道按节点缓存，标志由 {@link StopMarkIndex} 缓存。只在服务器主线程使用。
 */
public final class StationStopPoints {

  /** 查停车点。 */
  @FunctionalInterface
  public interface Lookup {
    /**
     * @param travel 列车行进方向
     * @param carriages 列车节数
     * @return 车站不在这个世界、区块未加载或找不到车站轨道时为空
     */
    Optional<StopPoint> lookup(
        NodeId node, World world, Vector travel, int carriages, long nowTick);
  }

  /**
   * 停车点。
   *
   * @param worldId 所在世界
   * @param point 停车点（轨道中心）
   * @param forward 停车点处轨道朝列车前进一侧的走向；取不到时为 {@code null}
   * @param reference 用列车的哪个部位对准：车站牌子是列车中心，停车位置标是车头
   * @param aheadBlocks 停车点在车站牌子前方多远（沿列车行进方向，格）；车站牌子本身为 0
   */
  public record StopPoint(
      UUID worldId,
      Vector point,
      Vector forward,
      StopAlignment.Reference reference,
      double aheadBlocks) {
    public StopPoint {
      point = point.clone();
      forward = forward == null ? null : forward.clone();
    }

    @Override
    public Vector point() {
      return point.clone();
    }

    @Override
    public Vector forward() {
      return forward == null ? null : forward.clone();
    }
  }

  /** 找不到车站轨道时，隔多久再找一次（tick）。 */
  private static final long RETRY_TICKS = 40L;

  /** 车站轨道缓存多久（tick）：车站牌子挪了位置时过一会儿就能找到新的。 */
  private static final long CACHE_TICKS = 1200L;

  /** 车站牌子所在的轨道：只记坐标，不留方块对象（方块引用着世界，世界卸载后不该被这里留住）。 */
  private record Station(
      UUID worldId, int x, int y, int z, Vector point, Vector axis, long atTick) {}

  private record Failure(long retryAtTick) {}

  private final Function<NodeId, Optional<SignNodeRegistry.SignNodeInfo>> signs;
  private final Supplier<StopMarkIndex> marks;
  private final Map<NodeId, Station> stations = new HashMap<>();
  private final Map<NodeId, Failure> failures = new HashMap<>();

  /**
   * @param signs 按节点找车站牌子
   * @param marks 停车位置标（插件还没初始化时可为 {@code null}）
   */
  public StationStopPoints(
      Function<NodeId, Optional<SignNodeRegistry.SignNodeInfo>> signs,
      Supplier<StopMarkIndex> marks) {
    this.signs = Objects.requireNonNull(signs, "signs");
    this.marks = Objects.requireNonNull(marks, "marks");
  }

  /** 见 {@link Lookup#lookup}。 */
  public Optional<StopPoint> lookup(
      NodeId node, World world, Vector travel, int carriages, long nowTick) {
    Optional<Station> station = station(node, world, nowTick);
    if (station.isEmpty()) {
      return Optional.empty();
    }
    Station found = station.get();
    StopMarkIndex index = marks.get();
    // 列车可能还在进站前的弯道上：按车站轨道的走向（以列车走向定正反）判前后。只看缓存不阻塞：还没找过时先按没有标志算，后台找完后自动换上。
    Vector forward = StopMarks.orient(found.axis(), travel);
    Optional<StopMarks.Selected> mark =
        index == null
            ? Optional.empty()
            : index.selectCached(
                world.getBlockAt(found.x(), found.y(), found.z()),
                found.point(),
                forward,
                carriages);
    if (mark.isPresent()) {
      return Optional.of(
          new StopPoint(
              found.worldId(),
              mark.get().mark().point(),
              markForward(world, mark.get().mark(), found.point(), forward),
              StopAlignment.Reference.HEAD,
              mark.get().aheadBlocks()));
    }
    return Optional.of(
        new StopPoint(
            found.worldId(), found.point(), forward, StopAlignment.Reference.CENTER, 0.0));
  }

  /**
   * 停车位置标处轨道朝列车前进一侧的走向。标志只认车站牌子前方的，所以按“车站停车点指向标志”定正反：标志在站台尽头的弯道上时，那里的走向可能与车站轨道接近垂直，拿车站走向或列车走向定正反会定反。
   */
  private static Vector markForward(
      World world, StopMarks.Mark mark, Vector stationPoint, Vector stationForward) {
    Vector ahead = mark.point().subtract(stationPoint).setY(0.0);
    Vector axis = railAxis(world, mark.rail());
    return StopMarks.orient(
        axis == null ? stationForward : axis,
        ahead.lengthSquared() > 1.0e-6 ? ahead : stationForward);
  }

  /** 这段轨道的走向（不分正反）；区块未加载或读不到时为 {@code null}。 */
  private static Vector railAxis(World world, RailBlockPos pos) {
    if (!world.isChunkLoaded(pos.x() >> 4, pos.z() >> 4)) {
      return null;
    }
    try {
      RailPiece piece = RailPiece.create(world.getBlockAt(pos.x(), pos.y(), pos.z()));
      RailState state = piece == null || piece.isNone() ? null : RailState.getSpawnState(piece);
      return state == null ? null : state.motionVector();
    } catch (RuntimeException | LinkageError ex) {
      return null;
    }
  }

  private Optional<Station> station(NodeId node, World world, long nowTick) {
    Station cached = stations.get(node);
    if (cached != null && nowTick - cached.atTick() < CACHE_TICKS) {
      return cached.worldId().equals(world.getUID()) ? Optional.of(cached) : Optional.empty();
    }
    Failure failure = failures.get(node);
    if (failure != null && nowTick < failure.retryAtTick()) {
      return Optional.empty();
    }
    failures.remove(node);
    Optional<Station> resolved = resolve(node, world, nowTick);
    if (resolved.isPresent()) {
      stations.put(node, resolved.get());
      failures.remove(node);
    } else {
      failures.put(node, new Failure(nowTick + RETRY_TICKS));
    }
    return resolved;
  }

  /** 按车站牌子找到它所在的轨道（TrainCarts 认的那一段），取轨道中心与走向；TCCoasters 的虚拟牌子按注册位置上的轨道取。区块未加载时找不到。 */
  private Optional<Station> resolve(NodeId node, World world, long nowTick) {
    Optional<SignNodeRegistry.SignNodeInfo> info = signs.apply(node);
    if (info.isEmpty() || !info.get().worldId().equals(world.getUID())) {
      return Optional.empty();
    }
    Optional<RailPiece> found =
        StopMarkIndex.stationRailOf(world, info.get().x(), info.get().y(), info.get().z());
    if (found.isEmpty()) {
      return Optional.empty();
    }
    try {
      RailPiece piece = found.get();
      RailState rail = RailState.getSpawnState(piece);
      if (rail == null) {
        return Optional.empty();
      }
      return Optional.of(
          new Station(
              world.getUID(),
              piece.block().getX(),
              piece.block().getY(),
              piece.block().getZ(),
              rail.positionLocation().toVector(),
              rail.motionVector(),
              nowTick));
    } catch (RuntimeException | LinkageError ex) {
      // TrainCarts 版本不同或轨道正在变化：这次找不到，过一会儿再找。
      return Optional.empty();
    }
  }
}
