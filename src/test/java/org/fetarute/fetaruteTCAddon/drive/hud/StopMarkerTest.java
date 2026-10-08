package org.fetarute.fetaruteTCAddon.drive.hud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.World;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.DriverStationStop;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.StopAlignment;
import org.fetarute.fetaruteTCAddon.drive.DriveConfig;
import org.fetarute.fetaruteTCAddon.drive.cab.CabSystems;
import org.fetarute.fetaruteTCAddon.drive.driver.DriverLink;
import org.fetarute.fetaruteTCAddon.drive.driver.DrivingMode;
import org.fetarute.fetaruteTCAddon.drive.driver.StationStopPoints;
import org.fetarute.fetaruteTCAddon.drive.dynamics.DriveMode;
import org.fetarute.fetaruteTCAddon.drive.dynamics.DriveParams;
import org.fetarute.fetaruteTCAddon.drive.dynamics.Notch;
import org.fetarute.fetaruteTCAddon.drive.seat.SeatBinding;
import org.fetarute.fetaruteTCAddon.drive.session.DriveSession;
import org.fetarute.fetaruteTCAddon.drive.setup.PowerSupply;
import org.fetarute.fetaruteTCAddon.drive.setup.SetupTimings;
import org.fetarute.fetaruteTCAddon.drive.setup.TrainSetup;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("发光停车标的显示与撤下")
class StopMarkerTest {

  private static final NodeId NODE = NodeId.of("OP:S:STA:1");

  /** 记下每次显示与撤下。 */
  private static final class FakeView implements StopMarker.View {
    private final List<Vector> shownAt = new ArrayList<>();
    private final List<StopMarkerGeometry.Tone> tones = new ArrayList<>();
    private int hides;

    @Override
    public void show(
        Player player, World world, Vector position, Vector forward, StopMarkerGeometry.Tone tone) {
      shownAt.add(position);
      tones.add(tone);
    }

    @Override
    public void hide(Player player) {
      hides++;
    }
  }

  private final UUID worldId = UUID.randomUUID();
  private final World world = mock(World.class);
  private final Player player = mock(Player.class);
  private final List<FakeView> views = new ArrayList<>();
  private Optional<StationStopPoints.StopPoint> lookup = Optional.empty();
  private final StopMarker marker =
      new StopMarker(
          (node, world, travel, carriages, now) -> lookup,
          () -> {
            FakeView view = new FakeView();
            views.add(view);
            return view;
          },
          id -> player);
  private final DriveSession session = session();
  private final DriverLink link =
      new DriverLink(UUID.randomUUID(), "T1", null, () -> 0.0, () -> 0L);

  StopMarkerTest() {
    when(world.getUID()).thenReturn(worldId);
    when(player.getUniqueId()).thenReturn(UUID.randomUUID());
    when(player.getWorld()).thenReturn(world);
    session.attachDriverLink(link);
  }

  private static DriveSession session() {
    MemoryConfiguration section = new MemoryConfiguration();
    section.set("level", "standard");
    List<ItemStack> hotbar = new ArrayList<>();
    for (int i = 0; i < Notch.SLOT_COUNT; i++) {
      hotbar.add(mock(ItemStack.class));
    }
    return new DriveSession(
        UUID.randomUUID(),
        "Steve",
        new SeatBinding("T1", 0, 0),
        new DriveParams(DriveMode.MU, 1.0, 1.0, 22.0, 0.5),
        DriveConfig.from(section, message -> {}),
        hotbar,
        new TrainSetup(PowerSupply.PTG5, SetupTimings.defaults()),
        CabSystems.disabled());
  }

  private DriverStationStop enterStation() {
    DriverStationStop stop =
        new DriverStationStop(NODE, "站", worldId, new Vector(100.5, 64.0, 0.5), null, false, true);
    link.beginStationStop(stop);
    return stop;
  }

  private void update() {
    // 列车朝 +X 走，车身长 10 格（中心在 x=95.5），车头在 x=100.5，驾驶员在中心前方 4 格（车头后方 1 格）。
    marker.update(
        player,
        session,
        new StopMarker.Train(
            new Vector(1.0, 0.0, 0.0),
            new Vector(100.5, 64.0, 0.5),
            new Vector(99.5, 65.0, 0.5),
            10.0,
            4),
        0L);
  }

  @Test
  @DisplayName("进站后画在驾驶员该停的位置，停准变绿，停妥后撤下")
  void followsTheStationStop() {
    DriverStationStop stop = enterStation();
    stop.updateOffset(-5.0);
    update();
    FakeView view = views.get(0);
    assertEquals(104.5, view.shownAt.get(0).getX(), 1.0e-9);
    assertEquals(0.5, view.shownAt.get(0).getZ(), 1.0e-9);
    assertEquals(StopMarkerGeometry.Tone.APPROACH, view.tones.get(0));

    stop.updateOffset(-1.0);
    update();
    assertEquals(StopMarkerGeometry.Tone.ON_MARK, view.tones.get(1));

    stop.markStopped();
    update();
    assertEquals(1, view.hides, "停妥后撤下");
    assertEquals(1, views.size(), "撤下不丢记录，再出现时沿用同一个标线");
  }

  @Test
  @DisplayName("前移沿驾驶员所在世界的轨道走，标线画在走到的位置")
  void walksAlongTheTrackOfThePlayersWorld() {
    List<World> walkedIn = new ArrayList<>();
    StopMarker onRails =
        new StopMarker(
            (node, world, travel, carriages, now) -> lookup,
            () -> {
              FakeView view = new FakeView();
              views.add(view);
              return view;
            },
            id -> player,
            (world, nowTick) -> {
              walkedIn.add(world);
              return (start, heading, distance) ->
                  Optional.of(
                      new StopMarkerGeometry.Placement(
                          new Vector(start.getX() + 3.0, start.getY(), start.getZ() + 3.0),
                          new Vector(1.0, 0.0, 1.0)));
            });
    enterStation().updateOffset(-5.0);
    onRails.update(
        player,
        session,
        new StopMarker.Train(
            new Vector(1.0, 0.0, 0.0),
            new Vector(100.5, 64.0, 0.5),
            new Vector(99.5, 65.0, 0.5),
            10.0,
            4),
        0L);

    assertEquals(List.of(world), walkedIn);
    Vector shown = views.get(0).shownAt.get(0);
    assertEquals(103.5, shown.getX(), 1.0e-9);
    assertEquals(3.5, shown.getZ(), 1.0e-9);
  }

  @Test
  @DisplayName("每 tick 交给探测接着采样；插件停用时丢掉探测的缓存，单个驾驶员结束时不丢（各驾驶员共用）")
  void tickAndClearReachTheSharedProbe() {
    List<Long> ticks = new ArrayList<>();
    int[] clears = {0};
    StopMarker shared =
        new StopMarker(
            (node, world, travel, carriages, now) -> lookup,
            FakeView::new,
            id -> player,
            new StopMarker.Probe() {
              @Override
              public StopMarkerGeometry.Track track(World world, long nowTick) {
                return StopMarkerGeometry.Track.STRAIGHT;
              }

              @Override
              public void tick(long nowTick) {
                ticks.add(nowTick);
              }

              @Override
              public void clear() {
                clears[0]++;
              }
            });

    shared.tick(7L);
    shared.remove(player.getUniqueId());
    assertEquals(0, clears[0], "一名驾驶员结束不影响别人的采样");
    shared.removeAll();
    assertEquals(List.of(7L), ticks);
    assertEquals(1, clears[0]);
  }

  @Test
  @DisplayName("ATO、换了世界或没有停车点时不画")
  void hiddenWhenNotApplicable() {
    update();
    assertTrue(views.isEmpty(), "没有停车点时不生成标线");

    DriverStationStop stop = enterStation();
    stop.updateOffset(-5.0);
    link.setMode(DrivingMode.ATO);
    update();
    assertTrue(views.isEmpty(), "ATO 下由自动运行对位");

    link.setMode(DrivingMode.MANUAL);
    World other = mock(World.class);
    when(other.getUID()).thenReturn(UUID.randomUUID());
    when(player.getWorld()).thenReturn(other);
    update();
    assertTrue(views.isEmpty(), "停车点在别的世界");
  }

  @Test
  @DisplayName("结束驾驶时撤下并丢掉记录")
  void removeOnSessionEnd() {
    enterStation().updateOffset(-5.0);
    update();
    marker.remove(player.getUniqueId());
    assertEquals(1, views.get(0).hides);
    update();
    assertEquals(2, views.size(), "记录已丢，再画时重新生成");
  }

  @Test
  @DisplayName("进站前：股道上有停车位置标时按车头对准，标线画在标志处后退“车头到驾驶员”的距离")
  void headReferencedBeforeEnteringTheStation() {
    link.updateApproach(
        NODE,
        "station",
        java.util.OptionalDouble.of(30.0),
        java.time.Instant.EPOCH,
        12.0,
        StopAlignment.Reference.HEAD);
    lookup =
        Optional.of(
            new StationStopPoints.StopPoint(
                worldId,
                new Vector(130.5, 64.0, 0.5),
                new Vector(1.0, 0.0, 0.0),
                StopAlignment.Reference.HEAD,
                12.0));
    update();
    assertEquals(129.5, views.get(0).shownAt.get(0).getX(), 1.0e-9);

    lookup =
        Optional.of(
            new StationStopPoints.StopPoint(
                worldId,
                new Vector(118.5, 64.0, 0.5),
                new Vector(1.0, 0.0, 0.0),
                StopAlignment.Reference.CENTER,
                0.0));
    update();
    assertEquals(122.5, views.get(0).shownAt.get(1).getX(), 1.0e-9, "车站牌子按列车中心对准");
  }

  @Test
  @DisplayName("停车位置标对的是车头最前端：第一节车厢中心停在标志后方半个车体长度，标线跟着后退")
  void headReferenceUsesTheFrontEnd() {
    link.updateApproach(
        NODE,
        "station",
        java.util.OptionalDouble.of(30.0),
        java.time.Instant.EPOCH,
        12.0,
        StopAlignment.Reference.HEAD);
    lookup =
        Optional.of(
            new StationStopPoints.StopPoint(
                worldId,
                new Vector(130.5, 64.0, 0.5),
                new Vector(1.0, 0.0, 0.0),
                StopAlignment.Reference.HEAD,
                12.0));
    // 车体长 6 格：车头最前端在第一节车厢中心（x=100.5）前方 3 格；驾驶员在车厢中心后方 1 格。
    marker.update(
        player,
        session,
        new StopMarker.Train(
            new Vector(1.0, 0.0, 0.0),
            new Vector(100.5, 64.0, 0.5),
            new Vector(99.5, 65.0, 0.5),
            10.0,
            4,
            3.0),
        0L);
    assertEquals(126.5, views.get(0).shownAt.get(0).getX(), 1.0e-9);
  }
}
