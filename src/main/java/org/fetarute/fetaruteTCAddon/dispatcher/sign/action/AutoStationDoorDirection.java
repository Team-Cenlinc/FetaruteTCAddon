package org.fetarute.fetaruteTCAddon.dispatcher.sign.action;

import java.util.Locale;
import java.util.Optional;
import org.bukkit.block.BlockFace;

/** AutoStation 牌子第 4 行的开门方向。 */
enum AutoStationDoorDirection {
  NONE,
  BOTH,
  NORTH,
  NORTH_EAST,
  EAST,
  SOUTH_EAST,
  SOUTH,
  SOUTH_WEST,
  WEST,
  NORTH_WEST;

  /**
   * 解析牌子第 4 行的开门方向。
   *
   * <p>空行或无法识别的值会回退为 {@link #NONE}，避免影响通过车。
   */
  static AutoStationDoorDirection parse(String raw) {
    if (raw == null) {
      return NONE;
    }
    String token = raw.trim().toUpperCase(Locale.ROOT);
    if (token.isEmpty()) {
      return NONE;
    }
    return switch (token) {
      case "BOTH" -> BOTH;
      case "N", "NORTH" -> NORTH;
      case "NE", "NORTH_EAST", "NORTH-EAST", "NORTHEAST" -> NORTH_EAST;
      case "E", "EAST" -> EAST;
      case "SE", "SOUTH_EAST", "SOUTH-EAST", "SOUTHEAST" -> SOUTH_EAST;
      case "S", "SOUTH" -> SOUTH;
      case "SW", "SOUTH_WEST", "SOUTH-WEST", "SOUTHWEST" -> SOUTH_WEST;
      case "W", "WEST" -> WEST;
      case "NW", "NORTH_WEST", "NORTH-WEST", "NORTHWEST" -> NORTH_WEST;
      case "NONE" -> NONE;
      default -> NONE;
    };
  }

  /**
   * 将方向转成世界方位，用于与列车行进方向推算左右门。
   *
   * <p>BOTH/NONE 会返回空。
   */
  Optional<BlockFace> toBlockFace() {
    return switch (this) {
      case NORTH -> Optional.of(BlockFace.NORTH);
      case NORTH_EAST -> Optional.of(BlockFace.NORTH_EAST);
      case EAST -> Optional.of(BlockFace.EAST);
      case SOUTH_EAST -> Optional.of(BlockFace.SOUTH_EAST);
      case SOUTH -> Optional.of(BlockFace.SOUTH);
      case SOUTH_WEST -> Optional.of(BlockFace.SOUTH_WEST);
      case WEST -> Optional.of(BlockFace.WEST);
      case NORTH_WEST -> Optional.of(BlockFace.NORTH_WEST);
      default -> Optional.empty();
    };
  }
}
