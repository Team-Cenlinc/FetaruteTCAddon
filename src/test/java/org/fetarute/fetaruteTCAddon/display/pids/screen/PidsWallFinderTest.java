package org.fetarute.fetaruteTCAddon.display.pids.screen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.Set;
import org.fetarute.fetaruteTCAddon.display.pids.screen.PidsScreen.Position;
import org.junit.jupiter.api.Test;

/** 展示框墙矩形：包含点击的展示框，优先让它在左上角。 */
class PidsWallFinderTest {

  /** 面朝南的一面墙：x 0–3、y 64–65 上都有空展示框。 */
  private static final Set<Position> WALL =
      Set.of(
          new Position(0, 65, 0),
          new Position(1, 65, 0),
          new Position(2, 65, 0),
          new Position(3, 65, 0),
          new Position(0, 64, 0),
          new Position(1, 64, 0),
          new Position(2, 64, 0),
          new Position(3, 64, 0));

  @Test
  void prefersTheClickedFrameAsTopLeft() {
    assertEquals(
        Optional.of(new Position(1, 65, 0)),
        PidsWallFinder.findAnchor(new Position(1, 65, 0), PidsFacing.SOUTH, 1, 3, WALL::contains));
  }

  @Test
  void shiftsLeftAndUpWhenTheClickedFrameIsNearTheEdge() {
    assertEquals(
        Optional.of(new Position(1, 65, 0)),
        PidsWallFinder.findAnchor(new Position(3, 65, 0), PidsFacing.SOUTH, 1, 3, WALL::contains),
        "点在最右一格时整块左移");
    assertEquals(
        Optional.of(new Position(2, 65, 0)),
        PidsWallFinder.findAnchor(new Position(2, 64, 0), PidsFacing.SOUTH, 2, 2, WALL::contains),
        "点在下一行时整块上移");
  }

  @Test
  void followsTheViewersRightOnEveryFacing() {
    // 面朝东的墙，观众的右手是北（-z）
    Set<Position> eastWall =
        Set.of(new Position(5, 70, 10), new Position(5, 70, 9), new Position(5, 70, 8));

    assertEquals(
        Optional.of(new Position(5, 70, 10)),
        PidsWallFinder.findAnchor(
            new Position(5, 70, 8), PidsFacing.EAST, 1, 3, eastWall::contains));
  }

  @Test
  void emptyWhenTheWallIsTooSmall() {
    assertTrue(
        PidsWallFinder.findAnchor(new Position(0, 65, 0), PidsFacing.SOUTH, 1, 5, WALL::contains)
            .isEmpty());
  }
}
