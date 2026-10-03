package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import com.bergerkiller.bukkit.tc.actions.Action;
import com.bergerkiller.bukkit.tc.controller.MinecartGroup;
import com.bergerkiller.bukkit.tc.controller.MinecartMember;
import com.bergerkiller.bukkit.tc.controller.components.ActionTracker;
import com.bergerkiller.bukkit.tc.controller.components.RailJunction;
import com.bergerkiller.bukkit.tc.controller.components.RailPath;
import com.bergerkiller.bukkit.tc.controller.components.RailState;
import com.bergerkiller.bukkit.tc.controller.components.RailTracker;
import com.bergerkiller.bukkit.tc.properties.TrainProperties;
import com.bergerkiller.bukkit.tc.utils.LauncherConfig;
import com.bergerkiller.bukkit.tc.utils.TrackWalkingPoint;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.fetarute.fetaruteTCAddon.FetaruteTCAddon;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.explore.RailBlockPos;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.interlocking.RailFootprintCell;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.interlocking.RailPathFootprintRasterizer;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.interlocking.RailPathOccupancySliceResolver;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.ControlAuthority;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.DriverInterrupt;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeRegistry;
import org.fetarute.fetaruteTCAddon.utils.LoggerManager;

/**
 * TrainCarts 运行时列车句柄实现。
 *
 * <p>仅封装“是否移动/发车/停车”等控车动作，避免运行时逻辑直接依赖 TrainCarts API。
 */
public final class TrainCartsRuntimeHandle implements RuntimeTrainHandle {

  private static final String ACTION_TAG_LAUNCH = "fta_launch";
  private static final double TICKS_PER_SECOND = 20.0;
  private static final int PATH_NODE_SEARCH_DISTANCE = 64;
  private static final int MAX_LIVE_BODY_WALK_STEPS = 256;
  private static final double MAX_LIVE_BODY_WALK_DISTANCE_BLOCKS = 512.0;
  private static final double LIVE_BODY_WALK_EPSILON = 1.0e-6;

  private final MinecartGroup group;

  /** 列车的物理控制权：驾驶员控制的列车不发车、不清动作队列，停车与销毁改为通知驾驶侧。 */
  private final ControlAuthority authority;

  public TrainCartsRuntimeHandle(MinecartGroup group) {
    this(group, ControlAuthority.pluginLookup());
  }

  /**
   * @param authority 列车的物理控制权
   */
  public TrainCartsRuntimeHandle(MinecartGroup group, ControlAuthority authority) {
    this.group = Objects.requireNonNull(group, "group");
    this.authority = Objects.requireNonNull(authority, "authority");
  }

  private boolean driverControlled() {
    return authority.isDriverControlled(group.getProperties());
  }

  /** 车上有驾驶员（含 ATO）。 */
  private boolean hasDriver() {
    return driverControlled() || authority.hasDriver(group.getProperties().getTrainName());
  }

  /**
   * @return TrainCarts MinecartGroup 是否仍有效。
   */
  @Override
  public boolean isValid() {
    return group.isValid();
  }

  /**
   * @return 列车是否处于移动状态。
   */
  @Override
  public boolean isMoving() {
    return group.isMoving();
  }

  /**
   * 获取 head cart 的实际速度（blocks/tick）。
   *
   * <p>使用实体的物理速度（velocity）而非 TrainCarts 的 getRealSpeed()， 因为后者在 launch 期间会返回目标速度而非实际速度。
   */
  @Override
  public double currentSpeedBlocksPerTick() {
    MinecartMember<?> head = group.head();
    if (head == null) {
      return 0.0;
    }
    org.bukkit.entity.Entity entity =
        head.getEntity() != null ? head.getEntity().getEntity() : null;
    if (entity == null) {
      return 0.0;
    }
    org.bukkit.util.Vector velocity = entity.getVelocity();
    if (velocity == null) {
      return 0.0;
    }
    return velocity.length();
  }

  @Override
  public UUID worldId() {
    if (group.getWorld() == null) {
      return new UUID(0L, 0L);
    }
    return group.getWorld().getUID();
  }

  /**
   * @return TrainProperties，用于读写 tags/destination 等。
   */
  @Override
  public TrainProperties properties() {
    return group.getProperties();
  }

  /**
   * 根据每节车的实时位置与车体长度估算列车总长。
   *
   * <p>相邻 member 中心点距离之和提供实时编组跨度，两端的车体余量按每节车真实的 {@code cartLength} 补足（模型车可以长到十格）， 算法见 {@link
   * PhysicalRailFootprintPolicy#conservativeTrainLengthBlocks}。任一实体位置缺失、跨世界、坐标异常或读不到车体模型时返回缺失，
   * 让列尾防护保持占用，而不是用可能偏小的值放行。
   */
  @Override
  @SuppressFBWarnings(
      value = "BC_UNCONFIRMED_CAST_OF_RETURN_VALUE",
      justification = "TrainCarts 的 MinecartMember#getEntity 泛型契约保证返回该成员对应的 CommonMinecart")
  public OptionalDouble estimatedTrainLengthBlocks() {
    if (!group.isValid()) {
      return OptionalDouble.empty();
    }
    UUID expectedWorldId = null;
    double previousX = 0.0;
    double previousY = 0.0;
    double previousZ = 0.0;
    double observedPathSpan = 0.0;
    List<Double> cartLengths = new ArrayList<>();
    for (MinecartMember<?> member : group) {
      if (member == null
          || member.getEntity() == null
          || member.getProperties() == null
          || member.getProperties().getModel() == null) {
        return OptionalDouble.empty();
      }
      org.bukkit.entity.Entity entity = member.getEntity().getEntity();
      if (entity == null || !entity.isValid()) {
        return OptionalDouble.empty();
      }
      UUID memberWorldId = entity.getWorld().getUID();
      if (expectedWorldId == null) {
        expectedWorldId = memberWorldId;
      } else if (!expectedWorldId.equals(memberWorldId)) {
        return OptionalDouble.empty();
      }
      org.bukkit.Location location = entity.getLocation();
      double x = location.getX();
      double y = location.getY();
      double z = location.getZ();
      if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
        return OptionalDouble.empty();
      }
      if (!cartLengths.isEmpty()) {
        // Minecraft 轨道以方块轴线和坡道为主，L1 距离会对曲线/坡道保持偏大，适合安全释放阈值。
        double gapLength =
            Math.abs(x - previousX) + Math.abs(y - previousY) + Math.abs(z - previousZ);
        if (!Double.isFinite(gapLength)) {
          return OptionalDouble.empty();
        }
        observedPathSpan += gapLength;
        if (!Double.isFinite(observedPathSpan)) {
          return OptionalDouble.empty();
        }
      }
      previousX = x;
      previousY = y;
      previousZ = z;
      cartLengths.add((double) member.getProperties().getModel().getCartLength());
    }
    return PhysicalRailFootprintPolicy.conservativeTrainLengthBlocks(observedPathSpan, cartLengths);
  }

  /**
   * 读取整列从车头到列尾的实时轨道路径足迹。
   *
   * <p>使用 group rail tracker 的 TrackedRail 链，而不是只采样各车厢中心方块；这样会包含车厢之间的曲线、坡道和中间轨道。整列位于同一条长 TCCoasters
   * 路径时，投影每节车公开的前后轮绝对位置，并按真实 cartLength 补足轮轴到车体端部的余量，只离散完整车体覆盖的局部切片；跨多个路径时保守保留 tracker
   * 全部路径，并从每节车中心沿正反方向继续行走半车长与方块边界余量。若 TrainCarts 已提供完整、连续且同世界的 {@code TrackedRail} block 链，但自定义轨道的
   * {@link RailPath} 或 body-walk 无法解析，则退回该链声明的全部实际占用方块；这是更保守的真实现场证据，而非由 route 或实体坐标猜测。任一
   * disconnected 段、跨世界、成员覆盖缺失、物理模型矛盾或 tracker 本身缺失时仍返回 empty。
   */
  @Override
  public Optional<Set<RailFootprintCell>> liveRailFootprintCells() {
    return observeLiveRailFootprint().cells();
  }

  @Override
  @SuppressFBWarnings(
      value = "BC_UNCONFIRMED_CAST_OF_RETURN_VALUE",
      justification = "TrainCarts 的 MinecartMember#getEntity 泛型契约保证返回该成员对应的 CommonMinecart")
  public LiveRailFootprintObservation observeLiveRailFootprint() {
    if (!group.isValid()) {
      return unavailable(LiveRailFootprintObservation.FailureReason.INVALID_GROUP, "group-invalid");
    }
    if (group.getRailTracker() == null) {
      return unavailable(
          LiveRailFootprintObservation.FailureReason.GROUP_RAIL_TRACKER_MISSING,
          "group-rail-tracker-null");
    }
    java.util.List<RailTracker.TrackedRail> liveRailInformation =
        group.getRailTracker().getRailInformation();
    if (liveRailInformation == null || liveRailInformation.isEmpty()) {
      return unavailable(
          LiveRailFootprintObservation.FailureReason.GROUP_RAIL_INFORMATION_EMPTY,
          liveRailInformation == null ? "rail-information-null" : "rail-information-empty");
    }
    java.util.List<RailTracker.TrackedRail> railInformation =
        java.util.List.copyOf(liveRailInformation);
    UUID expectedWorldId = worldId();
    java.util.List<LiveRailPathSample> pathSamples =
        new java.util.ArrayList<>(railInformation.size());
    java.util.List<RailBlockPos> trackedRailBlocks =
        new java.util.ArrayList<>(railInformation.size());
    Set<MinecartMember<?>> observedMembers =
        java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    RailState sharedRailState = null;
    RailPath sharedPath = null;
    RailBlockPos sharedRailBlock = null;
    boolean allOnSamePath = true;
    boolean useTrackedRailBlockFallback = false;
    int trackedRailIndex = 0;
    for (RailTracker.TrackedRail trackedRail : railInformation) {
      if (trackedRail == null) {
        return unavailable(
            LiveRailFootprintObservation.FailureReason.TRACKED_RAIL_INVALID,
            "trackedRail=" + trackedRailIndex + ":null");
      }
      if (trackedRail.disconnected
          || trackedRail.state == null
          || trackedRail.state.railPiece() == null
          || trackedRail.state.railPiece().isNone()) {
        return unavailable(
            LiveRailFootprintObservation.FailureReason.TRACKED_RAIL_INVALID,
            "trackedRail="
                + trackedRailIndex
                + ":disconnected="
                + trackedRail.disconnected
                + ":state="
                + (trackedRail.state == null ? "null" : "present"));
      }
      Block railBlock = trackedRail.state.railBlock();
      if (railBlock == null) {
        return unavailable(
            LiveRailFootprintObservation.FailureReason.RAIL_BLOCK_MISSING,
            "trackedRail=" + trackedRailIndex);
      }
      UUID memberWorldId = railBlock.getWorld().getUID();
      if (!expectedWorldId.equals(memberWorldId)) {
        return unavailable(
            LiveRailFootprintObservation.FailureReason.WORLD_MISMATCH,
            "trackedRail=" + trackedRailIndex + ":world=" + memberWorldId);
      }
      RailBlockPos railBlockPos =
          new RailBlockPos(railBlock.getX(), railBlock.getY(), railBlock.getZ());
      trackedRailBlocks.add(railBlockPos);
      if (trackedRail.member != null) {
        observedMembers.add(trackedRail.member);
      }
      RailPath path = trackedRail.getPath();
      if (path == null || path.isEmpty()) {
        useTrackedRailBlockFallback = true;
        allOnSamePath = false;
        trackedRailIndex++;
        continue;
      }
      pathSamples.add(new LiveRailPathSample(path, railBlockPos));
      if (sharedRailState == null) {
        sharedRailState = trackedRail.state;
        sharedPath = path;
        sharedRailBlock = railBlockPos;
      } else if (!trackedRail.state.isSameRails(sharedRailState)
          || path != sharedPath
          || !railBlockPos.equals(sharedRailBlock)) {
        allOnSamePath = false;
      }
      trackedRailIndex++;
    }
    java.util.List<MinecartMember<?>> members = new java.util.ArrayList<>();
    int memberCount = 0;
    for (MinecartMember<?> member : group) {
      if (member == null || !observedMembers.contains(member)) {
        return unavailable(
            LiveRailFootprintObservation.FailureReason.MEMBER_NOT_OBSERVED,
            "member=" + memberCount + ":" + (member == null ? "null" : "not-in-group-tracker"));
      }
      members.add(member);
      memberCount++;
    }
    if (memberCount <= 0) {
      return unavailable(
          LiveRailFootprintObservation.FailureReason.GROUP_EMPTY, "group-member-count=0");
    }
    java.util.List<LiveCartGeometry> cartGeometries = new java.util.ArrayList<>(members.size());
    for (int memberIndex = 0; memberIndex < members.size(); memberIndex++) {
      MinecartMember<?> member = members.get(memberIndex);
      com.bergerkiller.bukkit.common.entity.type.CommonMinecart<?> commonMinecart =
          member.getEntity();
      org.bukkit.entity.Minecart entity =
          commonMinecart != null ? commonMinecart.getEntity() : null;
      if (commonMinecart == null || entity == null) {
        return unavailable(
            LiveRailFootprintObservation.FailureReason.CART_ENTITY_MISSING,
            "member=" + memberIndex);
      }
      if (!expectedWorldId.equals(entity.getWorld().getUID())) {
        return unavailable(
            LiveRailFootprintObservation.FailureReason.CART_WORLD_MISMATCH,
            "member=" + memberIndex + ":world=" + entity.getWorld().getUID());
      }
      if (member.getWheels() == null) {
        return unavailable(
            LiveRailFootprintObservation.FailureReason.WHEEL_TRACKER_MISSING,
            "member=" + memberIndex);
      }
      if (member.getProperties() == null) {
        return unavailable(
            LiveRailFootprintObservation.FailureReason.CART_PROPERTIES_MISSING,
            "member=" + memberIndex);
      }
      com.bergerkiller.bukkit.tc.attachments.config.AttachmentModel model =
          member.getProperties().getModel();
      if (model == null) {
        return unavailable(
            LiveRailFootprintObservation.FailureReason.CART_MODEL_MISSING, "member=" + memberIndex);
      }
      org.bukkit.util.Vector frontWheel = member.getWheels().front().getAbsolutePosition();
      org.bukkit.util.Vector backWheel = member.getWheels().back().getAbsolutePosition();
      OptionalDouble endPadding =
          PhysicalRailFootprintPolicy.requiredEndPaddingBlocks(
              model.getCartLength(),
              member.getWheels().front().getDistance(),
              member.getWheels().back().getDistance());
      OptionalDouble centerWalkDistance =
          PhysicalRailFootprintPolicy.requiredCenterWalkDistanceBlocks(model.getCartLength());
      if (frontWheel == null || backWheel == null) {
        return unavailable(
            LiveRailFootprintObservation.FailureReason.WHEEL_POSITION_MISSING,
            "member=" + memberIndex);
      }
      if (endPadding.isEmpty() || centerWalkDistance.isEmpty()) {
        return unavailable(
            LiveRailFootprintObservation.FailureReason.CART_GEOMETRY_INVALID,
            "member="
                + memberIndex
                + ":cartLength="
                + model.getCartLength()
                + ":frontWheel="
                + member.getWheels().front().getDistance()
                + ":backWheel="
                + member.getWheels().back().getDistance());
      }
      if (centerWalkDistance.orElseThrow() > MAX_LIVE_BODY_WALK_DISTANCE_BLOCKS) {
        return unavailable(
            LiveRailFootprintObservation.FailureReason.BODY_WALK_DISTANCE_EXCEEDED,
            "member=" + memberIndex + ":distance=" + centerWalkDistance.orElseThrow());
      }
      cartGeometries.add(
          new LiveCartGeometry(
              member,
              frontWheel,
              backWheel,
              endPadding.orElseThrow(),
              centerWalkDistance.orElseThrow()));
    }
    if (useTrackedRailBlockFallback) {
      return trackedRailBlockFootprint(trackedRailBlocks);
    }
    if (allOnSamePath) {
      if (sharedPath == null || sharedRailBlock == null) {
        return unavailable(
            LiveRailFootprintObservation.FailureReason.SHARED_PATH_CONTEXT_MISSING,
            "shared-path-or-block-null");
      }
      java.util.List<org.bukkit.util.Vector> relativeWheelPositions =
          new java.util.ArrayList<>(members.size() * 2);
      double endPaddingBlocks = 0.0;
      for (LiveCartGeometry geometry : cartGeometries) {
        relativeWheelPositions.add(relativeToRailBlock(geometry.frontWheel(), sharedRailBlock));
        relativeWheelPositions.add(relativeToRailBlock(geometry.backWheel(), sharedRailBlock));
        endPaddingBlocks = Math.max(endPaddingBlocks, geometry.endPaddingBlocks());
      }
      Optional<Set<RailFootprintCell>> resolved =
          RailPathOccupancySliceResolver.resolve(
              sharedPath, sharedRailBlock, relativeWheelPositions, endPaddingBlocks);
      return resolved
          .map(LiveRailFootprintObservation::available)
          .orElseGet(() -> trackedRailBlockFootprint(trackedRailBlocks));
    }

    Set<RailFootprintCell> cells = new java.util.TreeSet<>();
    for (LiveRailPathSample sample : pathSamples) {
      Set<RailFootprintCell> pathCells =
          RailPathFootprintRasterizer.rasterize(sample.path(), sample.railBlock());
      if (pathCells.isEmpty()) {
        return trackedRailBlockFootprint(trackedRailBlocks);
      }
      cells.addAll(pathCells);
    }
    for (LiveCartGeometry geometry : cartGeometries) {
      if (!rasterizeMemberBodyFromCenter(
          cells, geometry.member(), geometry.centerWalkDistanceBlocks(), expectedWorldId)) {
        return trackedRailBlockFootprint(trackedRailBlocks);
      }
    }
    return cells.isEmpty()
        ? unavailable(
            LiveRailFootprintObservation.FailureReason.FOOTPRINT_EMPTY, "rasterized-cell-count=0")
        : LiveRailFootprintObservation.available(cells);
  }

  /**
   * 将 TrainCarts 已完整验证的 {@code TrackedRail} 方块链转换为保守物理足迹。
   *
   * <p>调用方必须已确认 group 有效、每条轨道连续且同世界，并且每个 member 均出现于该链。{@code
   * RailTrackerGroup#getRailInformation()} 的上游契约给出的正是整列车 当前占用的全部轨道方块；因此本方法只是在精细路径 ABI
   * 缺失时保留完整方块粒度，不能被实体位置、Route 或不完整 tracker 调用来制造清空证明。
   *
   * @param trackedRailBlocks 已验证的整列轨道方块链
   * @return 非空时的保守方块足迹；缺失链仍返回 fail-closed 结果
   */
  static LiveRailFootprintObservation trackedRailBlockFootprint(
      java.util.Collection<RailBlockPos> trackedRailBlocks) {
    if (trackedRailBlocks == null || trackedRailBlocks.isEmpty()) {
      return unavailable(
          LiveRailFootprintObservation.FailureReason.FOOTPRINT_EMPTY, "tracked-rail-blocks-empty");
    }
    Set<RailFootprintCell> cells = new java.util.TreeSet<>();
    int index = 0;
    for (RailBlockPos railBlock : trackedRailBlocks) {
      if (railBlock == null) {
        return unavailable(
            LiveRailFootprintObservation.FailureReason.TRACKED_RAIL_INVALID,
            "tracked-rail-block=" + index + ":null");
      }
      cells.add(new RailFootprintCell(railBlock.x(), railBlock.y(), railBlock.z()));
      index++;
    }
    return cells.isEmpty()
        ? unavailable(
            LiveRailFootprintObservation.FailureReason.FOOTPRINT_EMPTY, "tracked-rail-blocks-empty")
        : LiveRailFootprintObservation.available(cells);
  }

  private static LiveRailFootprintObservation unavailable(
      LiveRailFootprintObservation.FailureReason reason, String detail) {
    return LiveRailFootprintObservation.unavailable(reason, detail);
  }

  private record LiveRailPathSample(RailPath path, RailBlockPos railBlock) {}

  private record LiveCartGeometry(
      MinecartMember<?> member,
      org.bukkit.util.Vector frontWheel,
      org.bukkit.util.Vector backWheel,
      double endPaddingBlocks,
      double centerWalkDistanceBlocks) {}

  private static org.bukkit.util.Vector relativeToRailBlock(
      org.bukkit.util.Vector absolutePosition, RailBlockPos railBlock) {
    return new org.bukkit.util.Vector(
        absolutePosition.getX() - railBlock.x(),
        absolutePosition.getY() - railBlock.y(),
        absolutePosition.getZ() - railBlock.z());
  }

  private static boolean rasterizeMemberBodyFromCenter(
      Set<RailFootprintCell> cells,
      MinecartMember<?> member,
      double distanceBlocks,
      UUID expectedWorldId) {
    if (member.getRailTracker() == null || member.getRailTracker().getRail() == null) {
      return false;
    }
    RailTracker.TrackedRail centerRail = member.getRailTracker().getRail();
    if (centerRail.disconnected
        || centerRail.state == null
        || centerRail.getPath() == null
        || centerRail.getPath().isEmpty()) {
      return false;
    }
    RailState forwardState = centerRail.state.clone();
    forwardState.initEnterDirection();
    RailState reverseState = centerRail.state.cloneAndInvertMotion();
    reverseState.initEnterDirection();
    TrackWalkingPoint forwardWalker = new TrackWalkingPoint(forwardState);
    TrackWalkingPoint reverseWalker = new TrackWalkingPoint(reverseState);
    if (!centerRail.getPath().equals(forwardWalker.currentRailPath)
        || !centerRail.getPath().equals(reverseWalker.currentRailPath)) {
      return false;
    }
    return rasterizeBodyWalk(cells, forwardWalker, distanceBlocks, expectedWorldId)
        && rasterizeBodyWalk(cells, reverseWalker, distanceBlocks, expectedWorldId);
  }

  private static boolean rasterizeBodyWalk(
      Set<RailFootprintCell> cells,
      TrackWalkingPoint walker,
      double distanceBlocks,
      UUID expectedWorldId) {
    walker.setLoopFilter(true);
    for (int step = 0; step < MAX_LIVE_BODY_WALK_STEPS; step++) {
      if (!rasterizeWalkerPath(cells, walker, expectedWorldId)) {
        return false;
      }
      double remaining = distanceBlocks - walker.movedTotal;
      if (remaining <= LIVE_BODY_WALK_EPSILON) {
        return true;
      }
      boolean movedToNextPath = walker.moveStep(remaining);
      if (!rasterizeWalkerPath(cells, walker, expectedWorldId)) {
        return false;
      }
      if (walker.movedTotal + LIVE_BODY_WALK_EPSILON >= distanceBlocks) {
        return walker.failReason == TrackWalkingPoint.FailReason.NONE
            || walker.failReason == TrackWalkingPoint.FailReason.LIMIT_REACHED;
      }
      if (!movedToNextPath) {
        return false;
      }
    }
    return false;
  }

  private static boolean rasterizeWalkerPath(
      Set<RailFootprintCell> cells, TrackWalkingPoint walker, UUID expectedWorldId) {
    if (walker.currentRailPath == null
        || walker.currentRailPath.isEmpty()
        || walker.state == null
        || walker.state.railBlock() == null
        || !expectedWorldId.equals(walker.state.railBlock().getWorld().getUID())) {
      return false;
    }
    Block railBlock = walker.state.railBlock();
    Set<RailFootprintCell> pathCells =
        RailPathFootprintRasterizer.rasterize(
            walker.currentRailPath,
            new RailBlockPos(railBlock.getX(), railBlock.getY(), railBlock.getZ()));
    if (pathCells.isEmpty()) {
      return false;
    }
    cells.addAll(pathCells);
    return true;
  }

  /** 以 TrainCarts 编组对象身份区分同名 split/link 过渡实例。 */
  @Override
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification = "该接口刻意返回 MinecartGroup 对象身份，用于区分同名但不同物理编组；调用方不会修改该对象")
  public Object physicalRuntimeIdentity() {
    return group;
  }

  /** 执行紧急停车（不触发目的地逻辑）。 */
  @Override
  public void stop() {
    if (driverControlled()) {
      authority.interrupt(group.getProperties(), DriverInterrupt.SERVICE_STOP);
      return;
    }
    group.stop(false);
    MinecartMember<?> head = group.head();
    if (head != null && isLaunching(head)) {
      head.getActions().clear();
    }
  }

  /** 执行闭塞硬 STOP：归零速度并清空整列 TrainCarts 动作队列。 */
  @Override
  public void stopHard() {
    if (driverControlled()) {
      // 驾驶员控制的列车：同样立即停住，但不清动作队列（驾驶控车动作在里面）；驾驶侧同步把车速归零并锁住紧急制动。
      authority.interrupt(group.getProperties(), DriverInterrupt.EMERGENCY_INSTANT);
      group.stop(true);
      return;
    }
    group.stop(true);
    group.getActions().clear();
    for (MinecartMember<?> member : group) {
      if (member != null) {
        member.getActions().clear();
      }
    }
  }

  /**
   * 发车至目标速度。
   *
   * <p>若列车仍在移动则跳过。
   */
  @Override
  public void launch(double targetBlocksPerTick, double accelBlocksPerTickSquared) {
    requestLaunchWithFallback(Optional.empty(), targetBlocksPerTick, accelBlocksPerTickSquared);
  }

  @Override
  public void launchWithFallback(
      Optional<BlockFace> fallbackDirection,
      double targetBlocksPerTick,
      double accelBlocksPerTickSquared) {
    requestLaunchWithFallback(fallbackDirection, targetBlocksPerTick, accelBlocksPerTickSquared);
  }

  @Override
  public boolean requestLaunchWithFallback(
      Optional<BlockFace> fallbackDirection,
      double targetBlocksPerTick,
      double accelBlocksPerTickSquared) {
    if (driverControlled()) {
      // 起步由驾驶员完成。
      return true;
    }
    if (group.isMoving()) {
      return true;
    }
    MinecartMember<?> head = group.head();
    if (head == null) {
      return false;
    }
    if (isLaunching(head)) {
      return true;
    }
    group.getActions().launchReset();
    TrainProperties properties = group.getProperties();
    LoggerManager logger = resolveLoggerManager();
    LaunchDirectionResult directionResult =
        resolveLaunchDirectionByTrainCarts(head, properties, logger);
    Optional<BlockFace> directionOpt = directionResult.face();
    String detail = directionResult.detail();
    if (directionOpt.isEmpty() && fallbackDirection != null && fallbackDirection.isPresent()) {
      directionOpt = fallbackDirection;
      detail = detail + " fallback_graph";
    }
    if (logger != null) {
      String trainName = properties != null ? properties.getTrainName() : "unknown";
      String destination = properties != null ? properties.getDestination() : null;
      String destText = destination == null || destination.isBlank() ? "-" : destination;
      logger.debug(
          "发车判定: train="
              + trainName
              + " dest="
              + destText
              + " dir="
              + (directionOpt.isPresent()
                  ? directionOpt.get().name()
                  : directionResult.directionText())
              + " targetBpt="
              + targetBlocksPerTick
              + " detail="
              + detail);
    }
    // 使用“带方向”的发车，确保在折返/道岔附近发车时与 TrainCarts 寻路方向一致；方向未知时沿当前方向。
    return addLaunchAction(
        head, directionOpt.orElse(null), targetBlocksPerTick, accelBlocksPerTickSquared);
  }

  /**
   * 强制重发列车（用于回退检测后纠正方向）。
   *
   * <p>与 {@link #launchWithFallback} 不同，此方法会立即停车并发车，不检查 isMoving。
   */
  @Override
  public void forceRelaunch(
      org.bukkit.block.BlockFace direction,
      double targetBlocksPerTick,
      double accelBlocksPerTickSquared) {
    if (direction == null || driverControlled()) {
      return;
    }
    MinecartMember<?> head = group.head();
    if (head == null) {
      return;
    }
    // 立即停车：使用 stop(true) 强制清除所有动作并归零速度
    group.stop(true);
    group.getActions().clear();
    head.getActions().clear();

    // 立即发车
    LoggerManager logger = resolveLoggerManager();
    if (logger != null) {
      TrainProperties properties = group.getProperties();
      String trainName = properties != null ? properties.getTrainName() : "unknown";
      String destination = properties != null ? properties.getDestination() : null;
      String destText = destination == null || destination.isBlank() ? "-" : destination;
      logger.debug(
          "强制重发: train="
              + trainName
              + " dest="
              + destText
              + " dir="
              + direction.name()
              + " targetBpt="
              + targetBlocksPerTick);
    }
    addLaunchAction(head, direction, targetBlocksPerTick, accelBlocksPerTickSquared);
  }

  /**
   * 编组或任一车厢的动作队列里，当前动作不是本插件的 launch 即算外来动作。
   *
   * <p>停站等待（AutoStation、waypoint 居中）挂在编组队列，launch 挂在车头队列，两边都要看。
   */
  @Override
  public boolean hasForeignAction() {
    if (isForeignActionQueue(group.getActions())) {
      return true;
    }
    for (MinecartMember<?> member : group) {
      if (member != null && isForeignActionQueue(member.getActions())) {
        return true;
      }
    }
    return false;
  }

  /**
   * 本插件的发车/调速动作是否正在执行。
   *
   * <p>动作经 {@code addGroupAction} 挂在<b>编组</b>队列（归属车头），只看车头自己的队列永远看不到它：已在加速的车会被当成没在加速，
   * 每次补牵引都再排一个动作到队尾。两边都看；清除用车头队列的 {@code clear()}，它会连同编组队列里归属车头的动作一起移除。
   */
  private boolean isLaunching(MinecartMember<?> head) {
    return group.getActions().isCurrentActionTag(ACTION_TAG_LAUNCH)
        || head.getActions().isCurrentActionTag(ACTION_TAG_LAUNCH);
  }

  private static boolean isForeignActionQueue(ActionTracker actions) {
    return actions != null && actions.hasAction() && !actions.isCurrentActionTag(ACTION_TAG_LAUNCH);
  }

  @Override
  public void accelerateTo(double targetBlocksPerTick, double accelBlocksPerTickSquared) {
    if (driverControlled()) {
      return;
    }
    MinecartMember<?> head = group.head();
    if (head == null) {
      return;
    }
    double currentSpeed = currentSpeedBlocksPerTick();
    // 提速与 TrainLaunchManager 的补牵引同一门槛：牵引目标就是限速，低于它就补（编表运行曲线在任何回升处都会重新加速）。
    // 降速只为把 TrainCarts 速度向量重置到目标，留目标 1%（至少 0.005 格/tick）的余量，免得为截速残留的微小差值反复重发。
    boolean slowingDown =
        currentSpeed > targetBlocksPerTick + Math.max(0.005, Math.abs(targetBlocksPerTick) * 0.01);
    if (!slowingDown
        && currentSpeed >= targetBlocksPerTick - TrainLaunchManager.TRACTION_EPSILON_BPT) {
      return;
    }
    if (isLaunching(head)) {
      if (!slowingDown) {
        return;
      }
      head.getActions().clear();
    }
    // 加速按 S 形曲线提速；减速时第一 tick 就落到目标，把 TrainCarts 速度向量重置为目标值（截速不缩短向量）。
    addLaunchAction(head, null, targetBlocksPerTick, accelBlocksPerTickSquared);
  }

  /**
   * 挂上本插件的发车/调速动作。
   *
   * <p>加速度有效时用 {@link CurveLaunchAction}，与编表运行曲线同一条 S 形加速曲线；加速度无效（车种配置缺失）时退回 TrainCarts 默认的
   * launch。两者都打上 {@link #ACTION_TAG_LAUNCH}，供重复下发与外来动作判断识别。
   *
   * @param direction 起动方向；为 {@code null} 时沿当前方向
   * @return TrainCarts 是否接受了动作
   */
  private static boolean addLaunchAction(
      MinecartMember<?> head,
      BlockFace direction,
      double targetBlocksPerTick,
      double accelBlocksPerTickSquared) {
    Action action;
    if (accelBlocksPerTickSquared > 0.0 && Double.isFinite(accelBlocksPerTickSquared)) {
      double accelBps2 = accelBlocksPerTickSquared * TICKS_PER_SECOND * TICKS_PER_SECOND;
      action =
          head.getActions()
              .addGroupAction(new CurveLaunchAction(accelBps2, targetBlocksPerTick, direction));
    } else if (direction != null) {
      action =
          head.getActions()
              .addActionLaunch(direction, LauncherConfig.createDefault(), targetBlocksPerTick);
    } else {
      action =
          head.getActions().addActionLaunch(LauncherConfig.createDefault(), targetBlocksPerTick);
    }
    if (action == null) {
      return false;
    }
    action.addTag(ACTION_TAG_LAUNCH);
    return true;
  }

  @Override
  public void destroy() {
    if (!group.isValid()) {
      return;
    }
    if (hasDriver()) {
      // 先结束驾驶（解除绑定、归还属性），再照常销毁；ATO 下车上的驾驶员同样要先结束。
      authority.interrupt(group.getProperties(), DriverInterrupt.RELEASE_FOR_DESTROY);
    }
    // 避免在 TrainCarts doPhysics / SignTracker 刷新过程中直接 destroy() 导致 members array 出现 dead entity。
    // DSTY 往往在推进点（SignActionEvent）内触发，延迟 1 tick 执行更安全。
    Plugin plugin = null;
    try {
      plugin = JavaPlugin.getProvidingPlugin(TrainCartsRuntimeHandle.class);
    } catch (Throwable ignored) {
      plugin = null;
    }
    if (plugin == null || !plugin.isEnabled()) {
      group.destroy();
      return;
    }
    Bukkit.getScheduler()
        .runTaskLater(
            plugin,
            () -> {
              if (group.isValid()) {
                group.destroy();
              }
            },
            1L);
  }

  @Override
  public void setRouteIndex(int index) {
    if (group.getProperties() != null) {
      TrainTagHelper.writeTag(group.getProperties(), "FTA_ROUTE_INDEX", String.valueOf(index));
    }
  }

  @Override
  public void setRouteId(String routeId) {
    if (group.getProperties() != null) {
      TrainTagHelper.writeTag(group.getProperties(), "FTA_ROUTE_CODE", routeId);
    }
  }

  @Override
  public void setDestination(String destination) {
    if (group.getProperties() != null) {
      group.getProperties().setDestination(destination);
    }
  }

  @Override
  public Optional<BlockFace> forwardDirection() {
    MinecartMember<?> head = group.head();
    if (head == null) {
      return Optional.empty();
    }
    // 用轨道方向字段作为“发车/折返”判定依据；模型朝向（orientationForward）可能与轨道前进方向相反，
    // 会导致错误 reverse（例如站台/车库端头直接掉轨）。
    BlockFace face = head.getDirectionTo();
    if (isCardinalFace(face)) {
      return Optional.of(face);
    }
    face = head.getDirection();
    if (isCardinalFace(face)) {
      return Optional.of(face);
    }
    face = head.getDirectionFrom();
    return isCardinalFace(face) ? Optional.of(face) : Optional.empty();
  }

  @Override
  public Optional<RailState> railState() {
    MinecartMember<?> head = group.head();
    if (head == null) {
      return Optional.empty();
    }
    if (head.getRailTracker() == null) {
      return Optional.empty();
    }
    return Optional.ofNullable(head.getRailTracker().getState());
  }

  private static boolean isCardinalFace(BlockFace face) {
    return face == BlockFace.NORTH
        || face == BlockFace.SOUTH
        || face == BlockFace.EAST
        || face == BlockFace.WEST;
  }

  private static boolean isHorizontalFace(BlockFace face) {
    if (face == null) {
      return false;
    }
    if (face == BlockFace.SELF || face == BlockFace.UP || face == BlockFace.DOWN) {
      return false;
    }
    return face.getModY() == 0;
  }

  @Override
  public void reverse() {
    if (!group.isValid() || group.isMoving()) {
      return;
    }
    if (hasDriver()) {
      authority.interrupt(group.getProperties(), DriverInterrupt.HANDBACK_REQUIRED);
    }
    group.reverse();
  }

  /**
   * 根据 TrainCarts 当前 destination 的寻路结果推导发车方向。
   *
   * <p>用于解决 waypoint STOP/TERM 停车后折返发车方向错误的问题：调度层只下发 destination，真正寻路由 TrainCarts 完成，因此发车方向也应以
   * TrainCarts 的寻路结果为准。
   */
  private LaunchDirectionResult resolveLaunchDirectionByTrainCarts(
      MinecartMember<?> head, TrainProperties properties, LoggerManager logger) {
    if (head == null) {
      return LaunchDirectionResult.failure("head_null");
    }
    if (properties == null) {
      return LaunchDirectionResult.failure("properties_null");
    }
    String destination = properties.getDestination();
    if (destination == null || destination.isBlank()) {
      return LaunchDirectionResult.failure("destination_empty");
    }
    try {
      java.util.List<RailStateCandidate> candidates = collectRailStateCandidates(head);
      if (candidates.isEmpty()) {
        return LaunchDirectionResult.failure("rail_state_missing");
      }
      RailState railState = null;
      String candidateDebug = "-";
      com.bergerkiller.bukkit.tc.TrainCarts trainCarts = group.getTrainCarts();
      if (trainCarts == null || trainCarts.getPathProvider() == null) {
        RailState fallbackState = candidates.get(0).state();
        return resolveLaunchDirectionFallback(
            fallbackState, destination, "path_provider_missing", logger);
      }
      com.bergerkiller.bukkit.tc.pathfinding.PathWorld pathWorld =
          trainCarts.getPathProvider().getWorld(candidates.get(0).state().railWorld());
      if (pathWorld == null) {
        RailState fallbackState = candidates.get(0).state();
        return resolveLaunchDirectionFallback(
            fallbackState, destination, "path_world_missing", logger);
      }
      com.bergerkiller.bukkit.tc.pathfinding.PathNode currentNode = null;
      for (RailStateCandidate candidate : candidates) {
        RailState state = candidate.state();
        if (state == null) {
          continue;
        }
        Block railBlock = state.railBlock();
        Block positionBlock = state.positionBlock();
        String foundDetail = null;
        com.bergerkiller.bukkit.tc.pathfinding.PathNode node =
            railBlock != null ? pathWorld.getNodeAtRail(railBlock) : null;
        if (node == null && positionBlock != null) {
          node = pathWorld.getNodeAtRail(positionBlock);
        }
        if (node == null) {
          // 若当前位置不在 PathNode 上，沿轨道向前/向后寻找最近节点以对齐 TrainCarts debug destination 语义
          PathNodeSearchResult search =
              findNearestPathNode(pathWorld, candidate, railBlock, positionBlock);
          if (search != null) {
            node = search.node();
            foundDetail = candidate.describe(railBlock, positionBlock) + " " + search.detail();
          }
        }
        if (node != null) {
          railState = state;
          currentNode = node;
          candidateDebug =
              foundDetail != null ? foundDetail : candidate.describe(railBlock, positionBlock);
          break;
        }
        if (candidateDebug.equals("-")) {
          candidateDebug = candidate.describe(railBlock, positionBlock);
        } else if (candidateDebug.length() < 200) {
          candidateDebug = candidateDebug + "; " + candidate.describe(railBlock, positionBlock);
        }
      }
      if (currentNode == null || railState == null) {
        RailState fallbackState = candidates.get(0).state();
        return resolveLaunchDirectionFallback(
            fallbackState, destination, "current_node_missing " + candidateDebug, logger);
      }
      com.bergerkiller.bukkit.tc.pathfinding.PathNode destinationNode =
          pathWorld.getNodeByName(destination.trim());
      if (destinationNode == null) {
        return resolveLaunchDirectionFallback(
            railState, destination, "destination_node_missing", logger);
      }
      com.bergerkiller.bukkit.tc.pathfinding.PathConnection[] route =
          currentNode.findRoute(destinationNode);
      if (route == null || route.length == 0) {
        return resolveLaunchDirectionFallback(railState, destination, "route_empty", logger);
      }
      String junctionName = route[0].junctionName;
      if (junctionName == null || junctionName.isBlank()) {
        return resolveLaunchDirectionFallback(railState, destination, "junction_empty", logger);
      }
      if (railState.railPiece() == null || railState.railPiece().isNone()) {
        return resolveLaunchDirectionFallback(railState, destination, "rail_piece_missing", logger);
      }
      StringBuilder available = new StringBuilder();
      for (RailJunction junction : railState.railPiece().getJunctions()) {
        if (junction == null || junction.name() == null) {
          continue;
        }
        if (available.length() < 120) {
          if (available.length() > 0) {
            available.append(',');
          }
          available.append(junction.name());
        }
        if (!junction.name().equalsIgnoreCase(junctionName)) {
          continue;
        }
        BlockFace face =
            junction.position() != null ? junction.position().getMotionFaceWithSubCardinal() : null;
        if (isHorizontalFace(face)) {
          return LaunchDirectionResult.success(
              face, "junction=" + junctionName + " source=" + candidateDebug);
        }
        return resolveLaunchDirectionFallback(
            railState,
            destination,
            "junction_face_invalid=" + junctionName + " source=" + candidateDebug,
            logger);
      }
      return resolveLaunchDirectionFallback(
          railState,
          destination,
          "junction_not_found="
              + junctionName
              + " available="
              + available
              + " source="
              + candidateDebug,
          logger);
    } catch (Throwable ex) {
      if (logger != null) {
        logger.debug(
            "发车方向解析异常: "
                + ex.getClass().getSimpleName()
                + (ex.getMessage() != null ? (":" + ex.getMessage()) : ""));
      }
      return LaunchDirectionResult.failure("exception");
    }
  }

  private java.util.List<RailStateCandidate> collectRailStateCandidates(MinecartMember<?> head) {
    java.util.List<RailStateCandidate> candidates = new java.util.ArrayList<>();
    addRailStateCandidate(candidates, "head", head);
    MinecartMember<?> middle = group != null ? group.middle() : null;
    addRailStateCandidate(candidates, "middle", middle);
    MinecartMember<?> tail = group != null ? group.tail() : null;
    addRailStateCandidate(candidates, "tail", tail);
    return candidates;
  }

  private void addRailStateCandidate(
      java.util.List<RailStateCandidate> candidates, String label, MinecartMember<?> member) {
    if (member == null) {
      return;
    }
    if (!candidates.isEmpty() && candidates.stream().anyMatch(c -> c.member() == member)) {
      return;
    }
    if (member.getRailTracker() == null) {
      return;
    }
    RailState state = member.getRailTracker().getState();
    if (state == null || state.railWorld() == null) {
      return;
    }
    candidates.add(new RailStateCandidate(label, member, state));
  }

  private PathNodeSearchResult findNearestPathNode(
      com.bergerkiller.bukkit.tc.pathfinding.PathWorld pathWorld,
      RailStateCandidate candidate,
      Block railBlock,
      Block positionBlock) {
    if (pathWorld == null || candidate == null) {
      return null;
    }
    Block start = railBlock != null ? railBlock : positionBlock;
    if (start == null) {
      return null;
    }
    Set<BlockFace> directions = resolveSearchDirections(candidate);
    if (directions.isEmpty()) {
      return null;
    }
    for (BlockFace direction : directions) {
      TrackWalkingPoint walker = new TrackWalkingPoint(start, direction);
      walker.setLoopFilter(true);
      if (!walker.moveFull()) {
        continue;
      }
      while (walker.moveFull()) {
        if (walker.movedTotal > PATH_NODE_SEARCH_DISTANCE) {
          break;
        }
        Block block = walker.state != null ? walker.state.railBlock() : null;
        if (block == null) {
          continue;
        }
        com.bergerkiller.bukkit.tc.pathfinding.PathNode node = pathWorld.getNodeAtRail(block);
        if (node != null) {
          return new PathNodeSearchResult(
              node, "track_search " + direction.name() + " dist=" + Math.round(walker.movedTotal));
        }
      }
    }
    return null;
  }

  private Set<BlockFace> resolveSearchDirections(RailStateCandidate candidate) {
    Set<BlockFace> directions = new LinkedHashSet<>();
    if (candidate == null) {
      return directions;
    }
    RailState state = candidate.state();
    if (state != null) {
      try {
        BlockFace enter = state.enterFace();
        if (isHorizontalFace(enter)) {
          directions.add(enter.getOppositeFace());
          directions.add(enter);
        }
      } catch (Throwable ignored) {
        // ignore
      }
    }
    MinecartMember<?> member = candidate.member();
    if (member != null) {
      BlockFace to = member.getDirectionTo();
      if (isHorizontalFace(to)) {
        directions.add(to);
        directions.add(to.getOppositeFace());
      }
      BlockFace from = member.getDirectionFrom();
      if (isHorizontalFace(from)) {
        directions.add(from);
        directions.add(from.getOppositeFace());
      }
      BlockFace dir = member.getDirection();
      if (isHorizontalFace(dir)) {
        directions.add(dir);
        directions.add(dir.getOppositeFace());
      }
    }
    return directions;
  }

  private LaunchDirectionResult resolveLaunchDirectionFallback(
      RailState railState, String destination, String reason, LoggerManager logger) {
    Optional<BlockFace> fallback = resolveDirectionByRegistry(railState, destination, logger);
    if (fallback.isPresent()) {
      return LaunchDirectionResult.success(fallback.get(), "fallback_registry reason=" + reason);
    }
    return LaunchDirectionResult.failure(reason);
  }

  private Optional<BlockFace> resolveDirectionByRegistry(
      RailState railState, String destination, LoggerManager logger) {
    if (railState == null || destination == null || destination.isBlank()) {
      return Optional.empty();
    }
    Plugin plugin;
    try {
      plugin = JavaPlugin.getProvidingPlugin(TrainCartsRuntimeHandle.class);
    } catch (Throwable ignored) {
      return Optional.empty();
    }
    if (!(plugin instanceof FetaruteTCAddon addon)) {
      return Optional.empty();
    }
    SignNodeRegistry registry = addon.getSignNodeRegistry();
    if (registry == null) {
      return Optional.empty();
    }
    NodeId nodeId = NodeId.of(destination.trim());
    Optional<SignNodeRegistry.SignNodeInfo> infoOpt = registry.findByNodeId(nodeId, null);
    if (infoOpt.isEmpty()) {
      return Optional.empty();
    }
    SignNodeRegistry.SignNodeInfo info = infoOpt.get();
    if (railState.railWorld() != null && !railState.railWorld().getUID().equals(info.worldId())) {
      return Optional.empty();
    }
    Block railBlock = railState.railBlock();
    if (railBlock == null) {
      return Optional.empty();
    }
    int dx = info.x() - railBlock.getX();
    int dz = info.z() - railBlock.getZ();
    if (dx == 0 && dz == 0) {
      return Optional.empty();
    }
    BlockFace face =
        Math.abs(dx) >= Math.abs(dz)
            ? (dx > 0 ? BlockFace.EAST : BlockFace.WEST)
            : (dz > 0 ? BlockFace.SOUTH : BlockFace.NORTH);
    if (logger != null) {
      logger.debug(
          "发车方向回退: dest="
              + destination
              + " rail="
              + formatBlock(railBlock)
              + " sign="
              + info.locationText()
              + " face="
              + face.name());
    }
    return Optional.of(face);
  }

  private static String formatBlock(Block block) {
    if (block == null) {
      return "-";
    }
    return block.getWorld().getName()
        + "("
        + block.getX()
        + ","
        + block.getY()
        + ","
        + block.getZ()
        + ")";
  }

  private record LaunchDirectionResult(Optional<BlockFace> face, String detail) {
    static LaunchDirectionResult success(BlockFace face, String detail) {
      return new LaunchDirectionResult(Optional.ofNullable(face), detail != null ? detail : "-");
    }

    static LaunchDirectionResult failure(String detail) {
      return new LaunchDirectionResult(Optional.empty(), detail != null ? detail : "-");
    }

    String directionText() {
      return face.map(BlockFace::name).orElse("-");
    }
  }

  private record PathNodeSearchResult(
      com.bergerkiller.bukkit.tc.pathfinding.PathNode node, String detail) {}

  private record RailStateCandidate(String label, MinecartMember<?> member, RailState state) {
    String describe(Block railBlock, Block positionBlock) {
      return label + "(rb=" + formatBlock(railBlock) + " pb=" + formatBlock(positionBlock) + ")";
    }
  }

  private LoggerManager resolveLoggerManager() {
    try {
      Plugin plugin = JavaPlugin.getProvidingPlugin(TrainCartsRuntimeHandle.class);
      if (plugin instanceof FetaruteTCAddon addon) {
        return addon.getLoggerManager();
      }
    } catch (Throwable ignored) {
      return null;
    }
    return null;
  }
}
