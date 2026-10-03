package org.fetarute.fetaruteTCAddon.drive.hud;

import com.bergerkiller.bukkit.tc.controller.MinecartGroup;
import com.bergerkiller.bukkit.tc.controller.components.RailPiece;
import com.bergerkiller.bukkit.tc.controller.components.RailState;
import com.bergerkiller.bukkit.tc.rails.RailLookup;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.DriverStationStop;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.StopAlignment;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeRegistry;
import org.fetarute.fetaruteTCAddon.drive.driver.DriverLink;
import org.fetarute.fetaruteTCAddon.drive.session.DriveSession;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

/**
 * 发光停车标：进站时在驾驶员该停的地方画一道横跨轨道的发光标线。
 *
 * <p>停车点取车站牌子所在的轨道（与 TrainCarts 对位一致）：站台交来停站后用它量出的停车点，之前按牌子找到轨道自己算。 位置见 {@link StopMarkerGeometry}。
 *
 * <p>标线是一个不存档的方块展示实体，默认对所有人隐藏，只对驾驶员本人显示，服务器不会把它发给其他玩家的客户端； 没有碰撞，也不参与实体运算。发光轮廓能透过车体看见。只在服务器主线程使用。
 */
public final class StopMarker {

  /** 离停车点多远开始显示（格）；更远的地方区块未必加载，客户端也看不清。 */
  static final double SHOW_BLOCKS = 160.0;

  /** 找不到停车点所在轨道时，隔多久再找一次（tick）。 */
  private static final long RETRY_TICKS = 40L;

  /** 标线尺寸（格）：横跨轨道的宽度、厚度、沿轨道的深度。 */
  private static final float WIDTH = 3.0f;

  private static final float HEIGHT = 0.1f;
  private static final float DEPTH = 0.3f;

  /** 标线底面比停车点抬高一点，免得与轨道贴图重叠闪烁。 */
  private static final double LIFT = 0.02;

  /** 位置变化小于这个距离（格）不挪动。 */
  private static final double MOVE_EPSILON_SQUARED = 0.05 * 0.05;

  private final Plugin plugin;
  private final Function<NodeId, Optional<SignNodeRegistry.SignNodeInfo>> signs;
  private final Map<UUID, State> states = new HashMap<>();

  /** 停车点所在的轨道：位置与走向。 */
  private record Anchor(NodeId node, UUID worldId, Vector point, Vector axis) {}

  private static final class State {
    private Anchor anchor;
    private NodeId failedNode;
    private long retryAtTick;
    private BlockDisplay display;
    private StopMarkerGeometry.Tone tone;
  }

  /**
   * @param plugin 用于按玩家显示实体
   * @param signs 按节点找车站牌子
   */
  public StopMarker(
      Plugin plugin, Function<NodeId, Optional<SignNodeRegistry.SignNodeInfo>> signs) {
    this.plugin = Objects.requireNonNull(plugin, "plugin");
    this.signs = Objects.requireNonNull(signs, "signs");
  }

  /** 刷新驾驶员的停车标：前方没有要对标的车站、已经停妥或改由 ATO 操纵时撤掉。 */
  public void update(Player player, DriveSession session, MinecartGroup group, long nowTick) {
    DriverLink link = session.driverLink();
    Optional<DriverLink.StationTarget> target =
        link == null || !link.controlsPhysically() ? Optional.empty() : link.stationTarget();
    if (target.isEmpty()
        || !target.get().station()
        || Math.abs(target.get().remainingBlocks()) > SHOW_BLOCKS
        || group == null
        || group.isEmpty()) {
      remove(player.getUniqueId());
      return;
    }
    State state = states.computeIfAbsent(player.getUniqueId(), id -> new State());
    World world = player.getWorld();
    Optional<Anchor> anchor = anchor(state, target.get().node(), link, world, nowTick);
    if (anchor.isEmpty() || !anchor.get().worldId().equals(world.getUID())) {
      discard(state);
      return;
    }
    Optional<StopMarkerGeometry.Placement> placement =
        StopMarkerGeometry.place(
            anchor.get().point(),
            anchor.get().axis(),
            StopAlignment.travel(group),
            StopAlignment.center(group),
            player.getLocation().toVector());
    if (placement.isEmpty()) {
      discard(state);
      return;
    }
    Vector position = placement.get().position();
    Location location =
        new Location(
            world,
            position.getX(),
            position.getY() + LIFT,
            position.getZ(),
            placement.get().yaw(),
            0.0f);
    show(
        player,
        state,
        location,
        StopMarkerGeometry.tone(target.get().remainingBlocks(), target.get().precise()));
  }

  /** 撤掉玩家的停车标。 */
  public void remove(UUID playerId) {
    State state = states.remove(playerId);
    if (state != null) {
      discard(state);
    }
  }

  /** 撤掉所有停车标。插件停用时调用。 */
  public void removeAll() {
    for (UUID id : new ArrayList<>(states.keySet())) {
      remove(id);
    }
  }

  private Optional<Anchor> anchor(
      State state, NodeId node, DriverLink link, World world, long nowTick) {
    Optional<DriverStationStop> stop = link.stationStop();
    if (stop.isPresent() && stop.get().node().equals(node)) {
      // 站台已交来停站：用它量出的停车点，与对位判断同一个点；走向沿用之前按轨道找到的。
      Vector axis =
          state.anchor != null && state.anchor.node().equals(node) ? state.anchor.axis() : null;
      return Optional.of(new Anchor(node, stop.get().worldId(), stop.get().stopPoint(), axis));
    }
    if (state.anchor != null && state.anchor.node().equals(node)) {
      return Optional.of(state.anchor);
    }
    if (node.equals(state.failedNode) && nowTick < state.retryAtTick) {
      return Optional.empty();
    }
    Optional<Anchor> resolved = resolve(node, world);
    if (resolved.isPresent()) {
      state.anchor = resolved.get();
      state.failedNode = null;
    } else {
      state.failedNode = node;
      state.retryAtTick = nowTick + RETRY_TICKS;
    }
    return resolved;
  }

  /** 按车站牌子找到它所在的轨道（TrainCarts 认的那一段），取轨道中心与走向。区块未加载时找不到。 */
  private Optional<Anchor> resolve(NodeId node, World world) {
    Optional<SignNodeRegistry.SignNodeInfo> info = signs.apply(node);
    if (info.isEmpty() || !info.get().worldId().equals(world.getUID())) {
      return Optional.empty();
    }
    int x = info.get().x();
    int z = info.get().z();
    if (!world.isChunkLoaded(x >> 4, z >> 4)) {
      return Optional.empty();
    }
    try {
      Block sign = world.getBlockAt(x, info.get().y(), z);
      RailPiece piece = RailLookup.discoverRailPieceFromSign(sign);
      if (piece == null || piece.isNone()) {
        return Optional.empty();
      }
      RailState rail = RailState.getSpawnState(piece);
      if (rail == null) {
        return Optional.empty();
      }
      return Optional.of(
          new Anchor(
              node, world.getUID(), rail.positionLocation().toVector(), rail.motionVector()));
    } catch (RuntimeException | LinkageError ex) {
      // TrainCarts 版本不同或轨道正在变化：这次不画，过一会儿再找。
      return Optional.empty();
    }
  }

  private void show(Player player, State state, Location location, StopMarkerGeometry.Tone tone) {
    BlockDisplay display = state.display;
    if (display != null
        && (!display.isValid() || !display.getWorld().equals(location.getWorld()))) {
      discard(state);
      display = null;
    }
    if (display == null) {
      World world = location.getWorld();
      if (!world.isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)) {
        return;
      }
      display = world.spawn(location, BlockDisplay.class, created -> configure(created, tone));
      player.showEntity(plugin, display);
      state.display = display;
      state.tone = tone;
      return;
    }
    Location current = display.getLocation();
    if (current.distanceSquared(location) > MOVE_EPSILON_SQUARED
        || Math.abs(current.getYaw() - location.getYaw()) > 1.0f) {
      display.teleport(location);
    }
    if (tone != state.tone) {
      display.setBlock(material(tone).createBlockData());
      display.setGlowColorOverride(color(tone));
      state.tone = tone;
    }
  }

  private static void configure(BlockDisplay display, StopMarkerGeometry.Tone tone) {
    // 先隐藏再进入世界：其他玩家的客户端从头到尾收不到这个实体。
    display.setVisibleByDefault(false);
    display.setPersistent(false);
    display.setBlock(material(tone).createBlockData());
    display.setGlowing(true);
    display.setGlowColorOverride(color(tone));
    display.setBrightness(new Display.Brightness(15, 15));
    display.setShadowRadius(0.0f);
    // 展示实体默认只在 64 格内渲染；放大到显示距离。
    display.setViewRange((float) (SHOW_BLOCKS / 64.0));
    display.setTeleportDuration(2);
    display.setTransformation(
        new Transformation(
            new Vector3f(-WIDTH / 2.0f, 0.0f, -DEPTH / 2.0f),
            new AxisAngle4f(),
            new Vector3f(WIDTH, HEIGHT, DEPTH),
            new AxisAngle4f()));
  }

  private static void discard(State state) {
    if (state.display != null) {
      state.display.remove();
      state.display = null;
    }
    state.tone = null;
  }

  private static Material material(StopMarkerGeometry.Tone tone) {
    return switch (tone) {
      case APPROACH -> Material.YELLOW_CONCRETE;
      case ON_MARK -> Material.LIME_CONCRETE;
      case OVERRUN -> Material.RED_CONCRETE;
    };
  }

  private static Color color(StopMarkerGeometry.Tone tone) {
    return switch (tone) {
      case APPROACH -> Color.YELLOW;
      case ON_MARK -> Color.LIME;
      case OVERRUN -> Color.RED;
    };
  }
}
