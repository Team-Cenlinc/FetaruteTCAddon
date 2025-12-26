package org.fetarute.fetaruteTCAddon.dispatcher.graph.explore;

import java.util.Objects;

/** 表示轨道方块的离散坐标（x,y,z）。 */
public record RailBlockPos(int x, int y, int z) {

  public RailBlockPos {
    // record 保留：用于未来扩展（例如 chunk 限制）
  }

  public RailBlockPos offset(int dx, int dy, int dz) {
    return new RailBlockPos(x + dx, y + dy, z + dz);
  }

  public int manhattanDistanceTo(RailBlockPos other) {
    Objects.requireNonNull(other, "other");
    return Math.abs(x - other.x) + Math.abs(y - other.y) + Math.abs(z - other.z);
  }
}
