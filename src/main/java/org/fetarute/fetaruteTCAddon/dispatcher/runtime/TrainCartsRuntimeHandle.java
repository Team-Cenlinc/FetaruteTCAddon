package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import com.bergerkiller.bukkit.tc.controller.MinecartGroup;
import com.bergerkiller.bukkit.tc.controller.MinecartMember;
import com.bergerkiller.bukkit.tc.controller.components.RailJunction;
import com.bergerkiller.bukkit.tc.controller.components.RailState;
import com.bergerkiller.bukkit.tc.properties.TrainProperties;
import com.bergerkiller.bukkit.tc.utils.LauncherConfig;
import com.bergerkiller.bukkit.tc.utils.TrackWalkingPoint;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.fetarute.fetaruteTCAddon.FetaruteTCAddon;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeRegistry;
import org.fetarute.fetaruteTCAddon.utils.LoggerManager;

/**
 * TrainCarts 运行时列车句柄实现。
 *
 * <p>仅封装“是否移动/发车/停车”等控车动作，避免运行时逻辑直接依赖 TrainCarts API。
 */
public final class TrainCartsRuntimeHandle implements RuntimeTrainHandle {

  private static final String ACTION_TAG_LAUNCH = "fta_launch";
  private static final int PATH_NODE_SEARCH_DISTANCE = 64;

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
    launchWithFallback(Optional.empty(), targetBlocksPerTick, accelBlocksPerTickSquared);
  }

  @Override
  public void launchWithFallback(
      Optional<BlockFace> fallbackDirection,
      double targetBlocksPerTick,
      double accelBlocksPerTickSquared) {
    if (group.isMoving()) {
      return;
    }
    MinecartMember<?> head = group.head();
    if (head == null) {
      return;
    }
    if (head.getActions().isCurrentActionTag(ACTION_TAG_LAUNCH)) {
      return;
    }
    group.getActions().launchReset();
    LauncherConfig launchConfig = LauncherConfig.createDefault();
    if (accelBlocksPerTickSquared > 0.0) {
      launchConfig.setAcceleration(accelBlocksPerTickSquared);
    }
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
    if (directionOpt.isPresent()) {
      // 使用“带方向”的 launch，确保在折返/道岔附近发车时与 TrainCarts 寻路方向一致。
      var action =
          head.getActions().addActionLaunch(directionOpt.get(), launchConfig, targetBlocksPerTick);
      if (action != null) {
        action.addTag(ACTION_TAG_LAUNCH);
      }
      return;
    }
    var action = head.getActions().addActionLaunch(launchConfig, targetBlocksPerTick);
    if (action != null) {
      action.addTag(ACTION_TAG_LAUNCH);
    }
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
    if (direction == null) {
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
    LauncherConfig launchConfig = LauncherConfig.createDefault();
    if (accelBlocksPerTickSquared > 0.0) {
      launchConfig.setAcceleration(accelBlocksPerTickSquared);
    }
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
    var action = head.getActions().addActionLaunch(direction, launchConfig, targetBlocksPerTick);
    if (action != null) {
      action.addTag(ACTION_TAG_LAUNCH);
    }
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
    if (head.getActions().isCurrentActionTag(ACTION_TAG_LAUNCH)) {
      return;
    }
    // 无论是否运动，都尝试添加加速动作以"补充能量"
    LauncherConfig launchConfig = LauncherConfig.createDefault();
    if (accelBlocksPerTickSquared > 0.0) {
      launchConfig.setAcceleration(accelBlocksPerTickSquared);
    }
    var action = head.getActions().addActionLaunch(launchConfig, targetBlocksPerTick);
    if (action != null) {
      action.addTag(ACTION_TAG_LAUNCH);
    }
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
