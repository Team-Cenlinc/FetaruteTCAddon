package org.fetarute.fetaruteTCAddon.drive.hud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.util.Vector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("沿轨道的采样")
class TrackProfileTest {

  private static final double EPS = 1.0e-9;
  private static final double DIAGONAL = 1.0 / Math.sqrt(2.0);

  /** 从原点朝 +X 走 5 格后转成朝东南的斜向轨道，共长 105 格。 */
  private static PolylineCursor eastThenDiagonal() {
    return PolylineCursor.bent(
        new Vector(0.0, 64.0, 0.0),
        new Vector(1.0, 0.0, 0.0),
        5.0,
        new Vector(1.0, 0.0, 1.0),
        100.0);
  }

  @Test
  @DisplayName("按预算分几次采样；没采样到的部分从末端按那里的走向直线外推")
  void extendsWithinBudgetAndExtrapolatesBeyond() {
    PolylineCursor cursor = eastThenDiagonal();
    TrackProfile profile = new TrackProfile(cursor, 0L);

    assertEquals(4.0, profile.extend(12.0, 4.0, 0L, () -> true), EPS, "这次只走预算内的 4 格");
    assertFalse(profile.reaches(12.0));
    Vector interim = profile.at(12.0).position();
    assertEquals(12.0, interim.getX(), EPS, "从第 4 格处沿 +X 外推");
    assertEquals(0.0, interim.getZ(), EPS);

    profile.extend(12.0, 64.0, 5L, () -> true);
    assertTrue(profile.reaches(12.0));
    assertEquals(12.0, profile.covered(), EPS, "每次 1 格，走到覆盖目标为止");
    Vector exact = profile.at(12.0).position();
    assertEquals(5.0 + 7.0 * DIAGONAL, exact.getX(), EPS, "斜向段上的位置");
    assertEquals(7.0 * DIAGONAL, exact.getZ(), EPS);
    assertEquals(DIAGONAL, profile.at(12.0).direction().getZ(), EPS);
    assertEquals(2.5, profile.at(2.5).position().getX(), EPS, "采样点之间插值");
    assertEquals(12.0, cursor.advanced, EPS, "已采样的部分不重走");

    profile.extend(8.0, 64.0, 10L, () -> true);
    assertEquals(12.0, cursor.advanced, EPS, "距离变短不再走");
  }

  @Test
  @DisplayName("前方区块没加载时这次不走，过一会儿接着走")
  void blockedAheadRetriesLater() {
    PolylineCursor cursor = eastThenDiagonal();
    cursor.blockedFrom = 3.0;
    TrackProfile profile = new TrackProfile(cursor, 0L);

    profile.extend(10.0, 64.0, 0L, () -> true);
    assertEquals(3.0, profile.covered(), EPS);
    int advances = cursor.advances;

    profile.extend(10.0, 64.0, TrackProfile.BLOCKED_RETRY_TICKS - 1L, () -> true);
    assertEquals(advances, cursor.advances, "等待期间不碰轨道");

    cursor.blockedFrom = Double.POSITIVE_INFINITY;
    profile.extend(10.0, 64.0, TrackProfile.BLOCKED_RETRY_TICKS, () -> true);
    assertEquals(10.0, profile.covered(), EPS, "接着之前的位置走");
  }

  @Test
  @DisplayName("预算用完就停；坡道上外推的部分高度跟着坡度走")
  void stopsWhenOutOfTimeAndExtrapolatesAlongSlope() {
    PolylineCursor cursor =
        new PolylineCursor(
            java.util.List.of(new Vector(0.0, 64.0, 0.0), new Vector(20.0, 65.0, 0.0)));
    TrackProfile profile = new TrackProfile(cursor, 0L);

    assertEquals(0.0, profile.extend(10.0, 64.0, 0L, () -> false), EPS, "没有时间就一步也不走");

    profile.extend(4.0, 64.0, 0L, () -> true);
    double along = 10.0 / Math.sqrt(401.0);
    Vector beyond = profile.at(10.0).position();
    assertEquals(20.0 * along, beyond.getX(), EPS);
    assertEquals(64.0 + along, beyond.getY(), EPS, "不是停在末端高度");
  }

  @Test
  @DisplayName("轨道到头：算作能回答，超出尽头的部分直线外推")
  void endOfTrack() {
    PolylineCursor cursor =
        new PolylineCursor(
            java.util.List.of(new Vector(0.0, 64.0, 0.0), new Vector(0.0, 64.0, -6.0)));
    TrackProfile profile = new TrackProfile(cursor, 0L);

    profile.extend(10.0, 64.0, 0L, () -> true);
    assertTrue(profile.reaches(10.0));
    assertEquals(6.0, profile.covered(), EPS);
    assertEquals(-8.0, profile.at(8.0).position().getZ(), EPS);

    profile.extend(20.0, 64.0, 5L, () -> true);
    assertEquals(6.0, cursor.advanced, EPS, "到头后不再走");
  }
}
