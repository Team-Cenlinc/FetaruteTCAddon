package org.fetarute.fetaruteTCAddon.drive.hud;

import com.bergerkiller.bukkit.common.wrappers.BlockData;
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
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.DriverStationStop;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.StopAlignment;
import org.fetarute.fetaruteTCAddon.drive.driver.DriverLink;
import org.fetarute.fetaruteTCAddon.drive.driver.StationStopPoints;
import org.fetarute.fetaruteTCAddon.drive.session.DriveSession;

/**
 * 发光停车标：进站时在驾驶员该停的地方画一道横跨轨道的发光标线。
 *
 * <p>停车点与 TrainCarts 对位一致：车站牌子所在的轨道（列车中心对准），股道上有对应节数的停车位置标时是标志所在的轨道（车头对准）。 站台交来停站后用它量出的停车点，之前按
 * {@link StationStopPoints} 自己找。位置见 {@link StopMarkerGeometry}。
 *
 * <p>标线只在驾驶员本人的客户端渲染（{@link ClientBlockDisplay}，纯数据包，服务器上没有实体）。发光轮廓能透过车体看见。只在服务器主线程使用。
 */
public final class StopMarker {

  /** 离停车点多远开始显示（格）；更远的地方区块未必加载，客户端也看不清。 */
  static final double SHOW_BLOCKS = 160.0;

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

  /**
   * 列车此刻的姿态。
   *
   * @param travel 前进方向（车尾指向车头）
   * @param center 列车中心（车头与车尾的中点）
   * @param head 车头（第一节车厢）
   * @param seat 驾驶员座位的位置
   * @param carriages 节数
   */
  public record Train(Vector travel, Vector center, Vector head, Vector seat, int carriages) {}

  private final StationStopPoints.Lookup stopPoints;
  private final Supplier<View> views;
  private final Function<UUID, Player> players;
  private final Map<UUID, View> shown = new HashMap<>();

  /**
   * @param stopPoints 进站前查停车点
   */
  public StopMarker(StationStopPoints.Lookup stopPoints) {
    this(stopPoints, ClientView::new, Bukkit::getPlayer);
  }

  StopMarker(
      StationStopPoints.Lookup stopPoints, Supplier<View> views, Function<UUID, Player> players) {
    this.stopPoints = Objects.requireNonNull(stopPoints, "stopPoints");
    this.views = Objects.requireNonNull(views, "views");
    this.players = Objects.requireNonNull(players, "players");
  }

  /** 刷新驾驶员的停车标：前方没有要对标的车站、已经停妥或改由 ATO 操纵时撤下（标线留着，再出现时沿用）。 */
  public void update(Player player, DriveSession session, Train train, long nowTick) {
    DriverLink link = session.driverLink();
    Optional<DriverLink.StationTarget> target =
        link == null || !link.controlsPhysically() ? Optional.empty() : link.stationTarget();
    if (target.isEmpty()
        || !target.get().station()
        || Math.abs(target.get().remainingBlocks()) > SHOW_BLOCKS) {
      hide(player);
      return;
    }
    World world = player.getWorld();
    Optional<StationStopPoints.StopPoint> lookup =
        stopPoints.lookup(target.get().node(), world, train.travel(), train.carriages(), nowTick);
    Optional<DriverStationStop> stop =
        link.stationStop().filter(current -> current.node().equals(target.get().node()));
    Vector point;
    StopAlignment.Reference reference;
    UUID worldId;
    if (stop.isPresent()) {
      // 站台已交来停站：用它量出的停车点，与对位判断同一个点。
      point = stop.get().stopPoint();
      reference = stop.get().reference();
      worldId = stop.get().worldId();
    } else if (lookup.isPresent()) {
      point = lookup.get().point();
      reference = lookup.get().reference();
      worldId = lookup.get().worldId();
    } else {
      hide(player);
      return;
    }
    if (!worldId.equals(world.getUID())) {
      hide(player);
      return;
    }
    Vector referencePoint =
        reference == StopAlignment.Reference.HEAD ? train.head() : train.center();
    Optional<StopMarkerGeometry.Placement> placement =
        StopMarkerGeometry.place(
            point,
            lookup.map(StationStopPoints.StopPoint::axis).orElse(null),
            train.travel(),
            referencePoint,
            train.seat());
    if (placement.isEmpty()) {
      hide(player);
      return;
    }
    shown
        .computeIfAbsent(player.getUniqueId(), id -> views.get())
        .show(
            player,
            world,
            placement.get().position().add(new Vector(0.0, LIFT, 0.0)),
            placement.get().direction(),
            StopMarkerGeometry.tone(
                target.get().remainingBlocks(), target.get().precise(), link.stopWindow()));
  }

  /** 撤掉玩家的停车标并丢掉记录。驾驶结束时调用。 */
  public void remove(UUID playerId) {
    View view = shown.remove(playerId);
    if (view != null) {
      view.hide(players.apply(playerId));
    }
  }

  /** 撤掉所有停车标。插件停用时调用。 */
  public void removeAll() {
    for (UUID id : new ArrayList<>(shown.keySet())) {
      remove(id);
    }
  }

  private void hide(Player player) {
    View view = shown.get(player.getUniqueId());
    if (view != null) {
      view.hide(player);
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
