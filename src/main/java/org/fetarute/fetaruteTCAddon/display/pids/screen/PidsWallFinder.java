package org.fetarute.fetaruteTCAddon.display.pids.screen;

import java.util.Optional;
import java.util.function.Predicate;

/**
 * 在一面展示框墙上找出放得下屏幕的矩形。
 *
 * <p>矩形必须包含玩家点的那个展示框，且其中每个展示框都可用（同一朝向、同一墙面、空的、不属于别的屏幕）。
 * 有多个位置可选时，优先让点的那个展示框落在左上角，再依次尝试它落在第一行更靠右、再下一行……，结果确定、可预期。
 */
public final class PidsWallFinder {

  private PidsWallFinder() {}

  /**
   * @param clicked 玩家点的展示框所在方块
   * @param facing 展示框朝向
   * @param rows 屏幕地图行数
   * @param cols 屏幕地图列数
   * @param available 某个方块上是否有可用的展示框
   * @return 矩形左上角；放不下时为空
   */
  public static Optional<PidsScreen.Position> findAnchor(
      PidsScreen.Position clicked,
      PidsFacing facing,
      int rows,
      int cols,
      Predicate<PidsScreen.Position> available) {
    for (int row = 0; row < rows; row++) {
      for (int col = 0; col < cols; col++) {
        PidsScreen.Position anchor = facing.offset(clicked, -row, -col);
        if (fits(anchor, facing, rows, cols, available)) {
          return Optional.of(anchor);
        }
      }
    }
    return Optional.empty();
  }

  private static boolean fits(
      PidsScreen.Position anchor,
      PidsFacing facing,
      int rows,
      int cols,
      Predicate<PidsScreen.Position> available) {
    for (int row = 0; row < rows; row++) {
      for (int col = 0; col < cols; col++) {
        if (!available.test(facing.offset(anchor, row, col))) {
          return false;
        }
      }
    }
    return true;
  }
}
