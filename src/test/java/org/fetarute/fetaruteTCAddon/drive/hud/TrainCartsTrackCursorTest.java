package org.fetarute.fetaruteTCAddon.drive.hud;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("TrainCarts 轨道游标：前进结果与区块检查")
class TrainCartsTrackCursorTest {

  private static final double EPS = 1.0e-9;

  /** 沿 +X 走的假行走点：{@code left} 格后轨道到头；{@link #moved()} 只报最近一次。 */
  private static final class FakeWalker implements TrainCartsTrackCursor.Walker {
    private double x;
    private double left;
    private double lastMoved;
    private int moves;
    private RuntimeException failure;

    FakeWalker(double left) {
      this.left = left;
    }

    @Override
    public boolean move(double blocks) {
      moves++;
      if (failure != null) {
        throw failure;
      }
      lastMoved = Math.min(blocks, left);
      left -= lastMoved;
      x += lastMoved;
      return lastMoved + 1.0e-9 >= blocks;
    }

    @Override
    public double moved() {
      return lastMoved;
    }

    @Override
    public Vector position() {
      return new Vector(x, 64.0, 0.0);
    }

    @Override
    public Vector direction() {
      return new Vector(1.0, 0.0, 0.0);
    }
  }

  private long tick;
  private final List<Vector> checkedAt = new ArrayList<>();
  private boolean loaded = true;

  private TrainCartsTrackCursor cursor(FakeWalker walker) {
    return new TrainCartsTrackCursor(
        walker,
        (center, radius) -> {
          checkedAt.add(center);
          return loaded;
        },
        () -> tick);
  }

  @Test
  @DisplayName("走满报 MOVED；没走满报 ENDED，走了多少只算这一次")
  void mapsMovesAndEnds() {
    FakeWalker walker = new FakeWalker(2.5);
    TrainCartsTrackCursor cursor = cursor(walker);

    assertEquals(new TrackProfile.Advance(TrackProfile.Status.MOVED, 1.0), cursor.advance(1.0));
    assertEquals(new TrackProfile.Advance(TrackProfile.Status.MOVED, 1.0), cursor.advance(1.0));
    TrackProfile.Advance last = cursor.advance(1.0);
    assertEquals(TrackProfile.Status.ENDED, last.status());
    assertEquals(0.5, last.moved(), EPS, "不是累计的 2.5");
    assertEquals(2.5, cursor.position().getX(), EPS);
  }

  @Test
  @DisplayName("TrainCarts 读取出错按到头处理")
  void errorsEndTheWalk() {
    FakeWalker walker = new FakeWalker(10.0);
    walker.failure = new IllegalStateException("rail changed");

    assertEquals(
        new TrackProfile.Advance(TrackProfile.Status.ENDED, 0.0), cursor(walker).advance(1.0));
  }

  @Test
  @DisplayName("区块没加载时不走")
  void blockedWhenNotLoaded() {
    FakeWalker walker = new FakeWalker(10.0);
    loaded = false;

    assertEquals(
        new TrackProfile.Advance(TrackProfile.Status.BLOCKED, 0.0), cursor(walker).advance(1.0));
    assertEquals(0, walker.moves);
  }

  @Test
  @DisplayName("同一 tick 内确认过的范围不重查；走出范围或换了 tick 再查")
  void verifiesOncePerAreaAndTick() {
    TrainCartsTrackCursor cursor = cursor(new FakeWalker(100.0));

    for (int i = 0; i < 14; i++) {
      cursor.advance(1.0);
    }
    assertEquals(1, checkedAt.size(), "前 14 步都在第一次确认的范围里");

    cursor.advance(1.0);
    assertEquals(2, checkedAt.size(), "走出范围（留出这一步与余量）后重查");
    assertEquals(14.0, checkedAt.get(1).getX(), EPS);

    tick++;
    cursor.advance(1.0);
    assertEquals(3, checkedAt.size(), "区块可能在 tick 之间卸载，换了 tick 就重查");
  }
}
