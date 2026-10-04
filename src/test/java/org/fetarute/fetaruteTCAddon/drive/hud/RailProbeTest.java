package org.fetarute.fetaruteTCAddon.drive.hud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.bukkit.World;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("停车标的轨道探测：共用缓存与主线程预算")
class RailProbeTest {

  private static final double EPS = 1.0e-9;
  private static final double DIAGONAL = 1.0 / Math.sqrt(2.0);
  private static final Vector EAST = new Vector(1.0, 0.0, 0.0);
  private static final Vector STOP = new Vector(100.5, 64.0625, 20.5);

  private final World world = mock(World.class);
  private final List<PolylineCursor> opened = new ArrayList<>();

  /** 每次打开时按朝向给出一条轨道；返回空表示起点读不到轨道。 */
  private Function<Vector, Optional<PolylineCursor>> tracks = this::straight;

  /** 假时钟（纳秒）：默认不走，预算用不完。 */
  private long nanos;

  private final RailProbe probe =
      new RailProbe(
          (world, start, heading) -> {
            Optional<PolylineCursor> cursor = tracks.apply(heading);
            cursor.ifPresent(opened::add);
            return cursor.map(c -> c);
          },
          () -> nanos);

  RailProbeTest() {
    when(world.getUID()).thenReturn(UUID.randomUUID());
  }

  /** 朝 {@code heading} 一侧的 600 格直线轨道。 */
  private Optional<PolylineCursor> straight(Vector heading) {
    return Optional.of(PolylineCursor.bent(STOP, heading, 300.0, heading, 300.0));
  }

  /** 朝东 100 格后转成朝东南的斜向轨道（长站台尽头转弯）。 */
  private Optional<PolylineCursor> bentAt100(Vector heading) {
    return Optional.of(PolylineCursor.bent(STOP, heading, 100.0, new Vector(1.0, 0.0, 1.0), 500.0));
  }

  private StopMarkerGeometry.Placement walk(Vector from, double distance, long tick) {
    return probe.track(world, tick).walk(from, EAST, distance).orElseThrow();
  }

  @Test
  @DisplayName("同一个停车点只采样一次：距离来回抖动不重走")
  void samplesOncePerStopPoint() {
    walk(STOP, 10.0, 0L);
    walk(STOP, 10.3, 5L);
    walk(STOP, 9.8, 10L);
    StopMarkerGeometry.Placement placement = walk(STOP, 10.1, 15L);

    assertEquals(1, opened.size());
    assertEquals(11.0, opened.get(0).advanced, EPS, "最多比要的多走不到一格");
    assertEquals(STOP.getX() + 10.1, placement.position().getX(), EPS);
  }

  @Test
  @DisplayName("400 米长站台：每次刷新最多走预算内的格数，几次后追上，之前从已采样的末端外推")
  void longPlatformIsWalkedInSlices() {
    tracks = this::bentAt100;
    double distance = 200.0;

    StopMarkerGeometry.Placement first = walk(STOP, distance, 0L);
    assertEquals(RailProbe.SLICE_BLOCKS, opened.get(0).advanced, EPS, "第一次只走一片");
    assertEquals(STOP.getX() + distance, first.position().getX(), EPS, "还没走到弯道，沿 +X 外推");

    StopMarkerGeometry.Placement second = walk(STOP, distance, 5L);
    assertEquals(2.0 * RailProbe.SLICE_BLOCKS, opened.get(0).advanced, EPS);
    assertEquals(
        STOP.getZ() + 100.0 * DIAGONAL,
        second.position().getZ(),
        EPS,
        "已过弯道：从末端沿斜向外推，弯后是直的斜线，已与准确位置一致");

    walk(STOP, distance, 10L);
    StopMarkerGeometry.Placement done = walk(STOP, distance, 15L);
    assertEquals(distance, opened.get(0).advanced, EPS);
    assertEquals(STOP.getX() + 100.0 + 100.0 * DIAGONAL, done.position().getX(), EPS);
    assertEquals(STOP.getZ() + 100.0 * DIAGONAL, done.position().getZ(), EPS);
    assertEquals(1, opened.size());
  }

  @Test
  @DisplayName("到期重新采样（道岔可能换了开向）：新的追上之前一直用旧的")
  void refreshKeepsOldUntilNewCatchesUp() {
    double distance = 100.0;
    walk(STOP, distance, 0L);
    walk(STOP, distance, 5L);
    assertEquals(STOP.getX() + distance, walk(STOP, distance, 10L).position().getX(), EPS);

    tracks =
        heading ->
            Optional.of(PolylineCursor.bent(STOP, heading, 50.0, new Vector(1.0, 0.0, 1.0), 500.0));
    StopMarkerGeometry.Placement during = walk(STOP, distance, RailProbe.REFRESH_TICKS);
    assertEquals(2, opened.size(), "到期后重新打开");
    assertEquals(STOP.getZ(), during.position().getZ(), EPS, "新的还没追上，仍用旧的");

    StopMarkerGeometry.Placement after = walk(STOP, distance, RailProbe.REFRESH_TICKS + 5L);
    assertEquals(STOP.getZ() + 50.0 * DIAGONAL, after.position().getZ(), EPS, "追上后换成新的");
  }

  @Test
  @DisplayName("起点读不到轨道：返回空（退回直线估计），隔一会儿才再试")
  void retriesOpeningLater() {
    List<Integer> attempts = new ArrayList<>();
    tracks =
        heading -> {
          attempts.add(1);
          return Optional.empty();
        };

    assertTrue(probe.track(world, 0L).walk(STOP, EAST, 10.0).isEmpty());
    assertTrue(probe.track(world, 5L).walk(STOP, EAST, 10.0).isEmpty());
    assertTrue(probe.track(world, RailProbe.RETRY_TICKS - 1L).walk(STOP, EAST, 10.0).isEmpty());
    assertEquals(1, attempts.size(), "等待期间不碰轨道");

    tracks = this::straight;
    assertEquals(
        STOP.getX() + 10.0,
        probe
            .track(world, RailProbe.RETRY_TICKS)
            .walk(STOP, EAST, 10.0)
            .orElseThrow()
            .position()
            .getX(),
        EPS);
  }

  @Test
  @DisplayName("往回走用另一侧的采样、方向仍朝前；不同停车点各自采样，回到原来的停车点不重走")
  void sidesAndStopPointChange() {
    StopMarkerGeometry.Placement back = walk(STOP, -3.0, 0L);
    assertEquals(STOP.getX() - 3.0, back.position().getX(), EPS);
    assertTrue(back.direction().getX() > 0.0, "标线方向仍朝列车前进一侧");

    walk(STOP, 4.0, 5L);
    walk(STOP, -2.0, 10L);
    walk(STOP.clone().add(new Vector(0.2, 0.0, -0.2)), 4.0, 15L);
    assertEquals(2, opened.size(), "同一块轨道上的停车点两侧各采样一次");

    walk(STOP.clone().add(new Vector(30.0, 0.0, 0.0)), 4.0, 20L);
    assertEquals(3, opened.size(), "换了停车点重新采样");

    walk(STOP, 4.0, 25L);
    assertEquals(3, opened.size(), "各驾驶员、各停车点共用缓存");
  }

  @Test
  @DisplayName("没走完的采样由每 tick 的维护接着走，不必等下一次刷新")
  void tickContinuesUnfinishedSamples() {
    walk(STOP, 200.0, 0L);
    assertEquals(RailProbe.SLICE_BLOCKS, opened.get(0).advanced, EPS);

    probe.tick(1L);
    probe.tick(2L);
    probe.tick(3L);
    assertEquals(200.0, opened.get(0).advanced, EPS);

    assertEquals(STOP.getX() + 200.0, walk(STOP, 200.0, 5L).position().getX(), EPS);
    assertEquals(200.0, opened.get(0).advanced, EPS, "已经走完，查询不再走");
  }

  @Test
  @DisplayName("每 tick 的时间预算各停车点共用：用完后别的停车点这一 tick 不走，下一 tick 接着分")
  void timeBudgetIsSharedAcrossStopPoints() {
    // 每走一格花 0.1 ms：一个 tick 的预算 1 ms 够走 10 格。
    tracks =
        heading -> {
          Optional<PolylineCursor> cursor = straight(heading);
          cursor.ifPresent(c -> c.onAdvance = () -> nanos += 100_000L);
          return cursor;
        };
    Vector other = STOP.clone().add(new Vector(40.0, 0.0, 0.0));

    walk(STOP, 15.0, 0L);
    walk(other, 15.0, 0L);
    assertEquals(10.0, opened.get(0).advanced, EPS, "第一处用完本 tick 的预算");
    assertEquals(0.0, opened.get(1).advanced, EPS, "第二处这一 tick 一格也不走");

    probe.tick(1L);
    assertEquals(15.0, opened.get(0).advanced, EPS);
    assertEquals(5.0, opened.get(1).advanced, EPS, "剩下的预算给第二处");
  }

  @Test
  @DisplayName("重新采样打不开只影响这一侧：另一侧照常打开")
  void refreshFailureDoesNotBlockTheOtherSide() {
    walk(STOP, 10.0, 0L);
    tracks = heading -> heading.getX() > 0.0 ? Optional.empty() : straight(heading);

    walk(STOP, 10.0, RailProbe.REFRESH_TICKS);
    StopMarkerGeometry.Placement back = walk(STOP, -3.0, RailProbe.REFRESH_TICKS + 1L);

    assertEquals(2, opened.size(), "另一侧打开了");
    assertEquals(STOP.getX() - 3.0, back.position().getX(), EPS);
  }

  @Test
  @DisplayName("久未使用的停车点被丢掉")
  void evictsUnusedStopPoints() {
    walk(STOP, 10.0, 0L);
    probe.tick(RailProbe.EVICT_TICKS - 1L);
    assertEquals(1, probe.cachedCount());

    probe.tick(RailProbe.EVICT_TICKS);
    assertEquals(0, probe.cachedCount());
  }

  @Test
  @DisplayName("超过上限不走")
  void refusesBeyondMax() {
    assertTrue(probe.track(world, 0L).walk(STOP, EAST, RailProbe.MAX_BLOCKS + 1.0).isEmpty());
    assertTrue(opened.isEmpty());
  }
}
