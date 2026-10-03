package org.fetarute.fetaruteTCAddon.drive.driver;

import org.bukkit.block.BlockFace;
import org.bukkit.util.Vector;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.DriverStationStop;

/** 停站时驾驶员应开哪一侧的门（按驾驶员面朝的方向算左右）。 */
public enum DriverDoorSide {
  /** 本站不开门。 */
  NONE,
  LEFT,
  RIGHT,
  BOTH,
  /** 判定不出站台在哪一侧：开哪侧都算。 */
  ANY;

  /**
   * 本站应开的门。
   *
   * @param cabFacing 驾驶员面朝的水平方向；取不到时为 {@code null}
   */
  public static DriverDoorSide required(DriverStationStop stop, Vector cabFacing) {
    if (!stop.doorsRequired()) {
      return NONE;
    }
    if (stop.bothSides()) {
      return BOTH;
    }
    return stop.platformFace().map(face -> of(cabFacing, face)).orElse(ANY);
  }

  /** 面朝 {@code facing} 时，站台在 {@code platformFace} 方位是左手边还是右手边。 */
  static DriverDoorSide of(Vector facing, BlockFace platformFace) {
    if (facing == null || platformFace == null) {
      return ANY;
    }
    double fx = facing.getX();
    double fz = facing.getZ();
    if (Math.abs(fx) < 1.0e-6 && Math.abs(fz) < 1.0e-6) {
      return ANY;
    }
    // 世界坐标 x 向东、z 向南：面朝 (fx, fz) 时左手边是 (fz, -fx)。
    double dot = fz * platformFace.getModX() - fx * platformFace.getModZ();
    if (Math.abs(dot) < 1.0e-6) {
      return ANY;
    }
    return dot > 0.0 ? LEFT : RIGHT;
  }

  /** 站台侧的门是否都已打开。 */
  public boolean satisfied(boolean leftOpen, boolean rightOpen) {
    return switch (this) {
      case NONE -> true;
      case LEFT -> leftOpen;
      case RIGHT -> rightOpen;
      case BOTH -> leftOpen && rightOpen;
      case ANY -> leftOpen || rightOpen;
    };
  }

  /** 是否开了非站台侧的门。 */
  public boolean wrong(boolean leftOpen, boolean rightOpen) {
    return switch (this) {
      case NONE -> leftOpen || rightOpen;
      case LEFT -> rightOpen;
      case RIGHT -> leftOpen;
      case BOTH, ANY -> false;
    };
  }
}
