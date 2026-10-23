package org.fetarute.fetaruteTCAddon.dispatcher.sign.action;

import com.bergerkiller.bukkit.tc.controller.MinecartGroup;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.bukkit.World;
import org.fetarute.fetaruteTCAddon.coupler.CoupleService;
import org.fetarute.fetaruteTCAddon.coupler.CouplerService;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeTrainHandle;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.StationStopCoordinator;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.StopAlignment;
import org.fetarute.fetaruteTCAddon.drive.driver.StationStopPoints;

/**
 * 压上站牌之前估计列车会停在站牌之后多远（第一节车厢中心，格），照 AutoStation 对位的同一规则：
 *
 * <ul>
 *   <li>股道上有合适的停车位置标：车头最前端停在标志处，第一节车厢中心在标志之前半个车体长度；
 *   <li>先到联挂站、要等对方挂上来、没有停车位置标：挂上以后整列的中点对准车站牌子（{@link
 *       CoupleService.AwaitedJoin#midpointLeadBlocks}）；
 *   <li>其余：列车中心对准车站牌子，即相邻车厢中心距离之和的一半。
 * </ul>
 *
 * <p>调度据此把速度天花板延长到真正的停车点，停车点离站牌远（例如先到联挂站的车按挂上以后的节数选标志）时不会在站牌处先降速、压上站牌再提速。 停车位置标只读 {@link
 * org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.StopMarkIndex} 的缓存，不阻塞；
 * 还没找过时按没有标志估，压上站牌后由停站刹车动作按实际距离接管。只在服务器主线程使用。
 */
public final class StationStopTailEstimator implements StationStopCoordinator.StopTailEstimator {

  private final StationStopPoints stopPoints;
  private final Supplier<Optional<CouplerService>> coupler;
  private final LongSupplier nowTick;

  /**
   * @param stopPoints 车站停车点（车站轨道与停车位置标）
   * @param coupler 拆挂服务（未初始化时为空）
   * @param nowTick 当前服务器 tick
   */
  public StationStopTailEstimator(
      StationStopPoints stopPoints,
      Supplier<Optional<CouplerService>> coupler,
      LongSupplier nowTick) {
    this.stopPoints = Objects.requireNonNull(stopPoints, "stopPoints");
    this.coupler = Objects.requireNonNull(coupler, "coupler");
    this.nowTick = Objects.requireNonNull(nowTick, "nowTick");
  }

  @Override
  public OptionalDouble tailBlocks(
      RuntimeTrainHandle train, String trainName, UUID routeId, NodeId stopNode) {
    if (!(train.physicalRuntimeIdentity() instanceof MinecartGroup group)
        || !group.isValid()
        || group.isEmpty()) {
      return OptionalDouble.empty();
    }
    World world = group.getWorld();
    if (world == null) {
      return OptionalDouble.empty();
    }
    Optional<CoupleService.AwaitedJoin> awaited =
        routeId == null
            ? Optional.empty()
            : coupler
                .get()
                .flatMap(
                    service -> service.coupling().awaitedJoin(trainName, routeId, stopNode, group));
    OptionalInt preferred =
        awaited.map(join -> OptionalInt.of(join.mergedCars())).orElse(OptionalInt.empty());
    Optional<StationStopPoints.StopPoint> point =
        stopPoints.lookup(
            stopNode,
            world,
            StopAlignment.travel(group),
            group.size(),
            preferred,
            nowTick.getAsLong());
    if (point.isEmpty()) {
      return OptionalDouble.empty();
    }
    if (point.get().reference() == StopAlignment.Reference.HEAD) {
      return OptionalDouble.of(
          Math.max(0.0, point.get().aheadBlocks() - StopAlignment.frontOffsetBlocks(group)));
    }
    if (awaited.isPresent() && awaited.get().mergedCars() > group.size()) {
      return OptionalDouble.of(Math.max(0.0, awaited.get().midpointLeadBlocks()));
    }
    return OptionalDouble.of(StopAlignment.bodyLengthBlocks(group) / 2.0);
  }
}
