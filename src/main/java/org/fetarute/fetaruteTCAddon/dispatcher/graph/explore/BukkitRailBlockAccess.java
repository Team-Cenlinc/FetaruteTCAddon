package org.fetarute.fetaruteTCAddon.dispatcher.graph.explore;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Rail;

/** Bukkit 世界的轨道访问实现，用于构建区间长度。 */
public final class BukkitRailBlockAccess implements RailBlockAccess {

  private final World world;

  public BukkitRailBlockAccess(World world) {
    this.world = Objects.requireNonNull(world, "world");
  }

  @Override
  public boolean isRail(RailBlockPos pos) {
    if (!world.isChunkLoaded(pos.x() >> 4, pos.z() >> 4)) {
      return false;
    }
    Block block = world.getBlockAt(pos.x(), pos.y(), pos.z());
    BlockData data = block.getBlockData();
    return data instanceof Rail;
  }

  @Override
  public Set<RailBlockPos> neighbors(RailBlockPos pos) {
    if (!world.isChunkLoaded(pos.x() >> 4, pos.z() >> 4)) {
      return Set.of();
    }
    Block block = world.getBlockAt(pos.x(), pos.y(), pos.z());
    BlockData data = block.getBlockData();
    if (!(data instanceof Rail rail)) {
      return Set.of();
    }
    Set<RailBlockPos> neighbors = new HashSet<>();
    for (Offset offset : offsetsForShape(rail.getShape())) {
      RailBlockPos candidate = pos.offset(offset.dx, offset.dy, offset.dz);
      if (isRail(candidate)) {
        neighbors.add(candidate);
      }
    }
    return Set.copyOf(neighbors);
  }

  private static Set<Offset> offsetsForShape(Rail.Shape shape) {
    if (shape == null) {
      return Set.of();
    }
    return switch (shape) {
      case NORTH_SOUTH -> Set.of(Offset.north(), Offset.south());
      case EAST_WEST -> Set.of(Offset.east(), Offset.west());
      case ASCENDING_EAST -> Set.of(Offset.west(), Offset.eastUp());
      case ASCENDING_WEST -> Set.of(Offset.east(), Offset.westUp());
      case ASCENDING_NORTH -> Set.of(Offset.south(), Offset.northUp());
      case ASCENDING_SOUTH -> Set.of(Offset.north(), Offset.southUp());
      case SOUTH_EAST -> Set.of(Offset.south(), Offset.east());
      case SOUTH_WEST -> Set.of(Offset.south(), Offset.west());
      case NORTH_EAST -> Set.of(Offset.north(), Offset.east());
      case NORTH_WEST -> Set.of(Offset.north(), Offset.west());
    };
  }

  private record Offset(int dx, int dy, int dz) {
    static Offset north() {
      return new Offset(0, 0, -1);
    }

    static Offset south() {
      return new Offset(0, 0, 1);
    }

    static Offset east() {
      return new Offset(1, 0, 0);
    }

    static Offset west() {
      return new Offset(-1, 0, 0);
    }

    static Offset eastUp() {
      return new Offset(1, 1, 0);
    }

    static Offset westUp() {
      return new Offset(-1, 1, 0);
    }

    static Offset northUp() {
      return new Offset(0, 1, -1);
    }

    static Offset southUp() {
      return new Offset(0, 1, 1);
    }
  }
}
