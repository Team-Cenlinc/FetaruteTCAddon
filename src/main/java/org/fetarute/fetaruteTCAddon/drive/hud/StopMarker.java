package org.fetarute.fetaruteTCAddon.drive.hud;

import com.bergerkiller.bukkit.common.wrappers.BlockData;
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
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.DriverStationStop;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeRegistry;
import org.fetarute.fetaruteTCAddon.drive.driver.DriverLink;
import org.fetarute.fetaruteTCAddon.drive.session.DriveSession;

/**
 * 发光停车标：进站时在驾驶员该停的地方画一道横跨轨道的发光标线。
 *
 * <p>停车点取车站牌子所在的轨道（与 TrainCarts 对位一致）：站台交来停站后用它量出的停车点，之前按牌子找到轨道自己算。 位置见 {@link StopMarkerGeometry}。
 *
 * <p>标线只在驾驶员本人的客户端渲染（{@link ClientBlockDisplay}，纯数据包，服务器上没有实体）。发光轮廓能透过车体看见。只在服务器主线程使用。
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

  /** 移动时客户端插值的 tick 数。 */
  private static final int MOVE_TICKS = 2;

  /** 一名驾驶员客户端上的标线。 */
  interface View {
    /** 显示或挪到这个位置（标线底面中心），横跨 {@code forward} 方向的轨道。 */
    void show(
        Player player, World world, Vector position, Vector forward, StopMarkerGeometry.Tone tone);

    /** 从客户端撤掉；没显示时什么也不做。玩家已离线时为 {@code null}。 */
    void hide(Player player);
  }

  private final Function<NodeId, Optional<SignNodeRegistry.SignNodeInfo>> signs;
  private final Supplier<View> views;
  private final Function<UUID, Player> players;
  private final Map<UUID, State> states = new HashMap<>();

  /** 停车点所在的轨道：位置与走向。 */
  private record Anchor(NodeId node, UUID worldId, Vector point, Vector axis) {}

  private static final class State {
    private Anchor anchor;
    private NodeId failedNode;
    private long retryAtTick;
    private View view;
  }

  /**
   * @param signs 按节点找车站牌子
   */
  public StopMarker(Function<NodeId, Optional<SignNodeRegistry.SignNodeInfo>> signs) {
    this(signs, ClientView::new, Bukkit::getPlayer);
  }

  StopMarker(
      Function<NodeId, Optional<SignNodeRegistry.SignNodeInfo>> signs,
      Supplier<View> views,
      Function<UUID, Player> players) {
    this.signs = Objects.requireNonNull(signs, "signs");
    this.views = Objects.requireNonNull(views, "views");
    this.players = Objects.requireNonNull(players, "players");
  }

  /**
   * 刷新驾驶员的停车标：前方没有要对标的车站、已经停妥或改由 ATO 操纵时撤下（记录留着，再出现时不必重新找轨道）。
   *
   * @param travel 列车前进方向（车尾指向车头）
   * @param center 列车中心（车头与车尾的中点）
   * @param seat 驾驶员座位的位置
   */
  public void update(
      Player player,
      DriveSession session,
      Vector travel,
      Vector center,
      Vector seat,
      long nowTick) {
    DriverLink link = session.driverLink();
    Optional<DriverLink.StationTarget> target =
        link == null || !link.controlsPhysically() ? Optional.empty() : link.stationTarget();
    State state = states.computeIfAbsent(player.getUniqueId(), id -> new State());
    if (target.isEmpty()
        || !target.get().station()
        || Math.abs(target.get().remainingBlocks()) > SHOW_BLOCKS) {
      hide(player, state);
      return;
    }
    World world = player.getWorld();
    Optional<Anchor> anchor = anchor(state, target.get().node(), link, world, nowTick);
    if (anchor.isEmpty() || !anchor.get().worldId().equals(world.getUID())) {
      hide(player, state);
      return;
    }
    Optional<StopMarkerGeometry.Placement> placement =
        StopMarkerGeometry.place(anchor.get().point(), anchor.get().axis(), travel, center, seat);
    if (placement.isEmpty()) {
      hide(player, state);
      return;
    }
    if (state.view == null) {
      state.view = views.get();
    }
    state.view.show(
        player,
        world,
        placement.get().position().add(new Vector(0.0, LIFT, 0.0)),
        placement.get().direction(),
        StopMarkerGeometry.tone(
            target.get().remainingBlocks(), target.get().precise(), link.stopWindow()));
  }

  /** 撤掉玩家的停车标并丢掉记录。驾驶结束时调用。 */
  public void remove(UUID playerId) {
    State state = states.remove(playerId);
    if (state != null) {
      hide(players.apply(playerId), state);
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

  private static void hide(Player player, State state) {
    if (state.view != null) {
      state.view.hide(player);
    }
  }

  /** 用只发给驾驶员的方块展示实体画标线。 */
  private static final class ClientView implements View {
    private final ClientBlockDisplay display =
        new ClientBlockDisplay(
            new Vector(WIDTH, HEIGHT, DEPTH), (float) (SHOW_BLOCKS / 64.0), MOVE_TICKS);
    private StopMarkerGeometry.Tone tone;

    @Override
    public void show(
        Player player, World world, Vector position, Vector forward, StopMarkerGeometry.Tone next) {
      if (next != tone) {
        display.setAppearance(BlockData.fromMaterial(material(next)), color(next).asRGB());
        tone = next;
      }
      display.setForward(forward);
      display.sync(player, world, position);
    }

    @Override
    public void hide(Player player) {
      display.destroy(player);
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
}
