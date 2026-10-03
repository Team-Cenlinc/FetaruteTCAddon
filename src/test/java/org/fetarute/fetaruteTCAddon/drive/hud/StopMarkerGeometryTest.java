package org.fetarute.fetaruteTCAddon.drive.hud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    // 列车朝 +X 走，中心在 x=-50，驾驶员在中心前方 9 格；停车点在 x=100。
    StopMarkerGeometry.Placement placement =
        StopMarkerGeometry.place(
                new Vector(100.5, 64.0625, 20.5),
                new Vector(-1.0, 0.0, 0.0),
                new Vector(18.0, 0.0, 0.0),
                new Vector(-50.0, 64.0, 20.5),
                new Vector(-41.0, 65.0, 20.5))
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
                new Vector(-30.0, 64.0, -30.0),
                new Vector(-27.0, 64.0, -27.0))
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
                new Vector(0.0, 64.0, 40.0),
                new Vector(0.0, 64.0, 37.0))
            .orElseThrow();
    assertEquals(-3.0, placement.position().getZ(), EPS);
    assertTrue(
        StopMarkerGeometry.place(
                new Vector(), null, new Vector(0.0, 1.0, 0.0), new Vector(), new Vector())
            .isEmpty());
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
