package org.fetarute.fetaruteTCAddon.drive.hud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.bukkit.util.Vector;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.StopWindow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("发光停车标的位置与颜色")
class StopMarkerGeometryTest {

  private static final double EPS = 1.0e-9;

  @Test
  @DisplayName("画在停车点沿轨道前移“驾驶员到列车中心”这段距离处")
  void placedWhereTheSeatShouldStop() {
    // 列车朝 +X 走，车头在列车中心前方 8 格（沿车身），驾驶员在车头前方 1 格；停车点在 x=100。
    StopMarkerGeometry.Placement placement =
        StopMarkerGeometry.place(
                new Vector(100.5, 64.0625, 20.5),
                new Vector(1.0, 0.0, 0.0),
                new Vector(18.0, 0.0, 0.0),
                8.0,
                new Vector(-42.0, 64.0, 20.5),
                new Vector(-41.0, 65.0, 20.5),
                StopMarkerGeometry.Track.STRAIGHT)
            .orElseThrow();
    assertEquals(109.5, placement.position().getX(), EPS);
    assertEquals(64.0625, placement.position().getY(), EPS, "高度取停车点");
    assertEquals(20.5, placement.position().getZ(), EPS);
    assertEquals(-90.0f, placement.yaw(), 1.0e-4f, "朝 +X 为 -90 度");
  }

  @Test
  @DisplayName("进站前列车还在弯道上：按停车点处轨道的走向摆，不按列车此刻的走向")
  void usesTheRailAxisAtTheStopPoint() {
    // 列车此刻朝东北走，站台轨道南北向（列车将朝 +Z 进站）。
    StopMarkerGeometry.Placement placement =
        StopMarkerGeometry.place(
                new Vector(0.5, 64.0, 0.5),
                new Vector(0.0, 0.0, 1.0),
                new Vector(1.0, 0.0, 1.0),
                0.0,
                new Vector(-30.0, 64.0, -30.0),
                new Vector(-27.0, 64.0, -27.0),
                StopMarkerGeometry.Track.STRAIGHT)
            .orElseThrow();
    double ahead = Math.sqrt(18.0);
    assertEquals(0.5, placement.position().getX(), EPS);
    assertEquals(0.5 + ahead, placement.position().getZ(), EPS);
    assertEquals(0.0f, placement.yaw(), 1.0e-4f);
  }

  @Test
  @DisplayName("没有轨道走向时按列车走向；列车走向量不出时不画")
  void fallsBackToTrainTravel() {
    StopMarkerGeometry.Placement placement =
        StopMarkerGeometry.place(
                new Vector(0.0, 64.0, 0.0),
                null,
                new Vector(0.0, 0.0, -2.0),
                0.0,
                new Vector(0.0, 64.0, 40.0),
                new Vector(0.0, 64.0, 37.0),
                StopMarkerGeometry.Track.STRAIGHT)
            .orElseThrow();
    assertEquals(-3.0, placement.position().getZ(), EPS);
    assertTrue(
        StopMarkerGeometry.place(
                new Vector(),
                null,
                new Vector(0.0, 1.0, 0.0),
                0.0,
                new Vector(),
                new Vector(),
                StopMarkerGeometry.Track.STRAIGHT)
            .isEmpty());
  }

  @Test
  @DisplayName("沿轨道前移：停车点之后轨道转成斜向时，标线落在轨道上并横跨那里的轨道")
  void walksAlongTheTrack() {
    // 停车点处轨道朝 +X，往前 2 格起转成朝东南的斜向轨道；驾驶员在列车中心前方 6 格（车头 5 格 + 座位 1 格）。
    List<Object[]> calls = new ArrayList<>();
    StopMarkerGeometry.Track diagonal =
        (start, heading, distance) -> {
          calls.add(new Object[] {start, heading, distance});
          double along = (distance - 2.0) / Math.sqrt(2.0);
          return Optional.of(
              new StopMarkerGeometry.Placement(
                  new Vector(start.getX() + 2.0 + along, 65.0, start.getZ() + along),
                  new Vector(1.0, 0.0, 1.0)));
        };
    StopMarkerGeometry.Placement placement =
        StopMarkerGeometry.place(
                new Vector(100.5, 64.0625, 20.5),
                new Vector(1.0, 0.0, 0.0),
                new Vector(10.0, 0.0, 0.0),
                5.0,
                new Vector(95.0, 64.0, 20.5),
                new Vector(96.0, 65.0, 20.5),
                diagonal)
            .orElseThrow();

    assertEquals(1, calls.size());
    assertEquals(new Vector(100.5, 64.0625, 20.5), calls.get(0)[0], "从停车点起走");
    assertEquals(new Vector(1.0, 0.0, 0.0), calls.get(0)[1], "朝列车前进一侧");
    assertEquals(6.0, (double) calls.get(0)[2], EPS);
    double along = 4.0 / Math.sqrt(2.0);
    assertEquals(102.5 + along, placement.position().getX(), EPS, "不是直线外推的 106.5");
    assertEquals(20.5 + along, placement.position().getZ(), EPS);
    assertEquals(65.0, placement.position().getY(), EPS, "高度随轨道");
    assertEquals(-45.0f, placement.yaw(), 1.0e-4f, "横跨斜向轨道");
  }

  @Test
  @DisplayName("驾驶员在对准部位后方时往回走；轨道走不通时退回直线估计")
  void walksBackwardAndFallsBackToStraight() {
    List<Double> distances = new ArrayList<>();
    StopMarkerGeometry.Placement placement =
        StopMarkerGeometry.place(
                new Vector(0.5, 64.0, 0.5),
                new Vector(0.0, 0.0, 1.0),
                new Vector(0.0, 0.0, 1.0),
                0.0,
                new Vector(0.5, 64.0, -10.0),
                new Vector(0.5, 64.0, -13.0),
                (start, heading, distance) -> {
                  distances.add(distance);
                  return Optional.empty();
                })
            .orElseThrow();
    assertEquals(List.of(-3.0), distances);
    assertEquals(-2.5, placement.position().getZ(), EPS);
    assertEquals(0.0f, placement.yaw(), 1.0e-4f, "仍朝列车前进方向");
  }

  @Test
  @DisplayName("前移沿车身量：弯曲站台上车头离中心的车身长度比头尾连线长，按车身长度走")
  void seatDistanceIsMeasuredAlongTheBody() {
    // 长编组停在弯道上：此刻头尾连线朝 +X，但车头沿车身在中心前方 100 格，驾驶员在车头后方 1 格。
    List<Double> distances = new ArrayList<>();
    StopMarkerGeometry.place(
        new Vector(0.5, 64.0, 0.5),
        new Vector(1.0, 0.0, 0.0),
        new Vector(1.0, 0.0, 0.0),
        100.0,
        new Vector(84.0, 64.0, 40.0),
        new Vector(83.0, 64.0, 40.0),
        (start, heading, distance) -> {
          distances.add(distance);
          return Optional.empty();
        });
    assertEquals(List.of(99.0), distances, "不是头尾连线的投影");
  }

  @Test
  @DisplayName("停车点处走向已定好正反：与列车走向接近垂直时照用，不按列车走向翻转")
  void forwardIsNotFlippedByTrainTravel() {
    // 停车位置标在站台尽头的弯道上，那里朝 +Z；列车此刻朝 +X 略偏北。
    List<Vector> headings = new ArrayList<>();
    StopMarkerGeometry.place(
        new Vector(0.5, 64.0, 0.5),
        new Vector(0.0, 0.0, 1.0),
        new Vector(1.0, 0.0, -0.05),
        0.0,
        new Vector(-10.0, 64.0, 0.5),
        new Vector(-9.0, 64.0, 0.5),
        (start, heading, distance) -> {
          headings.add(heading);
          return Optional.empty();
        });
    assertEquals(List.of(new Vector(0.0, 0.0, 1.0)), headings);
  }

  @Test
  @DisplayName("颜色：进站后停准为绿，进站后越过可开门范围为红，其余为黄")
  void tone() {
    StopWindow window = StopWindow.DEFAULTS;
    double accurate = window.accurateBlocks();
    double accept = window.acceptBlocks();
    assertEquals(StopMarkerGeometry.Tone.APPROACH, StopMarkerGeometry.tone(40.0, false, window));
    assertEquals(
        StopMarkerGeometry.Tone.APPROACH,
        StopMarkerGeometry.tone(accurate / 2.0, false, window),
        "估计值不判停准");
    assertEquals(
        StopMarkerGeometry.Tone.APPROACH,
        StopMarkerGeometry.tone(-accept - 3.0, false, window),
        "估计值不判越过");
    assertEquals(StopMarkerGeometry.Tone.ON_MARK, StopMarkerGeometry.tone(-accurate, true, window));
    assertEquals(StopMarkerGeometry.Tone.APPROACH, StopMarkerGeometry.tone(-accept, true, window));
    assertEquals(
        StopMarkerGeometry.Tone.OVERRUN, StopMarkerGeometry.tone(-accept - 0.5, true, window));
  }
}
