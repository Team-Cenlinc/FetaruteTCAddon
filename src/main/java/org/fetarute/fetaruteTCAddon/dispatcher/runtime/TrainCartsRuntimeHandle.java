package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import com.bergerkiller.bukkit.tc.controller.MinecartGroup;
import com.bergerkiller.bukkit.tc.controller.MinecartMember;
import com.bergerkiller.bukkit.tc.controller.components.RailJunction;
import com.bergerkiller.bukkit.tc.controller.components.RailState;
import com.bergerkiller.bukkit.tc.properties.TrainProperties;
import com.bergerkiller.bukkit.tc.utils.LauncherConfig;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.block.BlockFace;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * TrainCarts 运行时列车句柄实现。
 *
 * <p>仅封装“是否移动/发车/停车”等控车动作，避免运行时逻辑直接依赖 TrainCarts API。
 */
public final class TrainCartsRuntimeHandle implements RuntimeTrainHandle {

  private final MinecartGroup group;

  public TrainCartsRuntimeHandle(MinecartGroup group) {
    this.group = Objects.requireNonNull(group, "group");
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
   * @return head cart 的实时速度（blocks/tick），若异常返回 0。
   */
  @Override
  public double currentSpeedBlocksPerTick() {
    MinecartMember<?> head = group.head();
    if (head == null) {
      return 0.0;
    }
    double speed = head.getRealSpeed();
    return Double.isFinite(speed) ? Math.abs(speed) : 0.0;
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

  /** 执行紧急停车（不触发目的地逻辑）。 */
  @Override
  public void stop() {
    group.stop(false);
  }

  /**
   * 发车至目标速度。
   *
   * <p>若列车仍在移动则跳过。
   */
  @Override
  public void launch(double targetBlocksPerTick, double accelBlocksPerTickSquared) {
    if (group.isMoving()) {
      return;
    }
    MinecartMember<?> head = group.head();
    if (head == null) {
      return;
    }
    head.getActions().clear();
    LauncherConfig launchConfig = LauncherConfig.createDefault();
    if (accelBlocksPerTickSquared > 0.0) {
      launchConfig.setAcceleration(accelBlocksPerTickSquared);
    }
    Optional<BlockFace> directionOpt =
        resolveLaunchDirectionByTrainCarts(head, group.getProperties());
    if (directionOpt.isPresent()) {
      // 使用“带方向”的 launch，确保在折返/道岔附近发车时与 TrainCarts 寻路方向一致。
      head.getActions().addActionLaunch(directionOpt.get(), launchConfig, targetBlocksPerTick);
      return;
    }
    head.getActions().addActionLaunch(launchConfig, targetBlocksPerTick);
  }

  @Override
  public void accelerateTo(double targetBlocksPerTick, double accelBlocksPerTickSquared) {
    MinecartMember<?> head = group.head();
    if (head == null) {
      return;
    }
    double currentSpeed = currentSpeedBlocksPerTick();
    // 如果当前速度已经接近或超过目标，不需要重新加速
    if (currentSpeed >= targetBlocksPerTick * 0.95) {
      return;
    }
    // 无论是否运动，都尝试添加加速动作以"补充能量"
    LauncherConfig launchConfig = LauncherConfig.createDefault();
    if (accelBlocksPerTickSquared > 0.0) {
      launchConfig.setAcceleration(accelBlocksPerTickSquared);
    }
    head.getActions().addActionLaunch(launchConfig, targetBlocksPerTick);
  }

  @Override
  public void destroy() {
    if (!group.isValid()) {
      return;
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
    group.reverse();
  }

  /**
   * 根据 TrainCarts 当前 destination 的寻路结果推导发车方向。
   *
   * <p>用于解决 waypoint STOP/TERM 停车后折返发车方向错误的问题：调度层只下发 destination，真正寻路由 TrainCarts 完成，因此发车方向也应以
   * TrainCarts 的寻路结果为准。
   */
  private Optional<BlockFace> resolveLaunchDirectionByTrainCarts(
      MinecartMember<?> head, TrainProperties properties) {
    if (head == null || properties == null) {
      return Optional.empty();
    }
    String destination = properties.getDestination();
    if (destination == null || destination.isBlank()) {
      return Optional.empty();
    }
    try {
      RailState railState = head.getRailTracker() != null ? head.getRailTracker().getState() : null;
      if (railState == null || railState.railWorld() == null) {
        return Optional.empty();
      }
      com.bergerkiller.bukkit.tc.TrainCarts trainCarts = group.getTrainCarts();
      if (trainCarts == null || trainCarts.getPathProvider() == null) {
        return Optional.empty();
      }
      com.bergerkiller.bukkit.tc.pathfinding.PathWorld pathWorld =
          trainCarts.getPathProvider().getWorld(railState.railWorld());
      if (pathWorld == null) {
        return Optional.empty();
      }
      com.bergerkiller.bukkit.tc.pathfinding.PathNode currentNode =
          pathWorld.getNodeAtRail(railState.railBlock());
      if (currentNode == null) {
        currentNode = pathWorld.getNodeAtRail(railState.positionBlock());
      }
      if (currentNode == null) {
        return Optional.empty();
      }
      com.bergerkiller.bukkit.tc.pathfinding.PathNode destinationNode =
          pathWorld.getNodeByName(destination.trim());
      if (destinationNode == null) {
        return Optional.empty();
      }
      com.bergerkiller.bukkit.tc.pathfinding.PathConnection[] route =
          currentNode.findRoute(destinationNode);
      if (route == null || route.length == 0) {
        return Optional.empty();
      }
      String junctionName = route[0].junctionName;
      if (junctionName == null || junctionName.isBlank()) {
        return Optional.empty();
      }
      if (railState.railPiece() == null || railState.railPiece().isNone()) {
        return Optional.empty();
      }
      for (RailJunction junction : railState.railPiece().getJunctions()) {
        if (junction == null || junction.name() == null) {
          continue;
        }
        if (!junction.name().equalsIgnoreCase(junctionName)) {
          continue;
        }
        BlockFace face =
            junction.position() != null ? junction.position().getMotionFaceWithSubCardinal() : null;
        if (isHorizontalFace(face)) {
          return Optional.of(face);
        }
        return Optional.empty();
      }
    } catch (Throwable ignored) {
      // 忽略：回退到无方向发车
    }
    return Optional.empty();
  }
}
