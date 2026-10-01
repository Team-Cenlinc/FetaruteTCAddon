package org.fetarute.fetaruteTCAddon.display.pids.screen;

import java.util.Optional;
import org.bukkit.block.BlockFace;

/**
 * 屏幕朝向，即展示框面朝的方向。只支持竖直墙面：地面与天花板上的展示框各自还带旋转，组成的矩形与玩家视角不对应。
 *
 * <p>{@link #rightX()} / {@link #rightZ()} 是站在屏幕前看去“向右”一格的方块偏移；行向下即 y 减一。
 */
public enum PidsFacing {
  NORTH(-1, 0),
  SOUTH(1, 0),
  EAST(0, -1),
  WEST(0, 1);

  private final int rightX;
  private final int rightZ;

  PidsFacing(int rightX, int rightZ) {
    this.rightX = rightX;
    this.rightZ = rightZ;
  }

  /** 向右一格的 x 偏移。 */
  public int rightX() {
    return rightX;
  }

  /** 向右一格的 z 偏移。 */
  public int rightZ() {
    return rightZ;
  }

  /** 从 {@code from} 向下 {@code down} 行、向右 {@code right} 列的方块；负数表示向上、向左。 */
  public PidsScreen.Position offset(PidsScreen.Position from, int down, int right) {
    return new PidsScreen.Position(
        from.x() + rightX * right, from.y() - down, from.z() + rightZ * right);
  }

  /** 对应的 Bukkit 朝向。 */
  public BlockFace blockFace() {
    return BlockFace.valueOf(name());
  }

  /** 由展示框朝向换算；地面、天花板与斜向为空。 */
  public static Optional<PidsFacing> of(BlockFace face) {
    if (face == null) {
      return Optional.empty();
    }
    return switch (face) {
      case NORTH -> Optional.of(NORTH);
      case SOUTH -> Optional.of(SOUTH);
      case EAST -> Optional.of(EAST);
      case WEST -> Optional.of(WEST);
      default -> Optional.empty();
    };
  }
}
