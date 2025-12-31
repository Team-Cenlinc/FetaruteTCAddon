package org.fetarute.fetaruteTCAddon.dispatcher.graph.explore;

import com.bergerkiller.bukkit.tc.controller.components.RailJunction;
import com.bergerkiller.bukkit.tc.controller.components.RailPath;
import com.bergerkiller.bukkit.tc.controller.components.RailPiece;
import com.bergerkiller.bukkit.tc.controller.components.RailState;
import com.bergerkiller.bukkit.tc.rails.logic.RailLogic;
import com.bergerkiller.bukkit.tc.rails.type.RailType;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;

/**
 * 使用 TrainCarts 的轨道判定与连通信息，作为调度图探索阶段的轨道访问实现。
 *
 * <p>目的：复用 TC 的 {@link RailType} 判定逻辑与 junction 逻辑，以兼容自定义轨道实现（例如 TCCoasters 的长距离/曲线连接）。
 *
 * <p>邻接策略：优先使用 {@link RailType#getJunctions(Block)} + {@link RailType#takeJunction(Block,
 * RailJunction)}；若无法获取 junction，则保守回退为“扫描相邻方块”的轨道遍历（避免依赖已弃用的旧 API）。
 *
 * <p>约束：该实现不会主动加载区块；未加载区块会被视为不可达。
 */
public final class TrainCartsRailBlockAccess implements RailBlockAccess {

  private static final BlockFace[] FALLBACK_NEIGHBOR_FACES = {
    BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST, BlockFace.UP, BlockFace.DOWN
  };

  private final World world;
  private final Map<RailBlockPos, Double> segmentLengthCache = new HashMap<>();

  public TrainCartsRailBlockAccess(World world) {
    this.world = Objects.requireNonNull(world, "world");
  }

  @Override
  public boolean isRail(RailBlockPos pos) {
    if (!world.isChunkLoaded(pos.x() >> 4, pos.z() >> 4)) {
      return false;
    }
    Block block = world.getBlockAt(pos.x(), pos.y(), pos.z());
    RailPiece piece = RailPiece.create(block);
    if (piece == null || piece.isNone()) {
      return false;
    }
    RailType railType = piece.type();
    if (railType == null || railType == RailType.NONE) {
      return false;
    }
    Block railBlock = piece.block();
    return railBlock.getX() == pos.x()
        && railBlock.getY() == pos.y()
        && railBlock.getZ() == pos.z();
  }

  @Override
  public Set<RailBlockPos> neighborCandidates(RailBlockPos pos) {
    if (!world.isChunkLoaded(pos.x() >> 4, pos.z() >> 4)) {
      return Set.of();
    }

    Block block = world.getBlockAt(pos.x(), pos.y(), pos.z());
    RailPiece piece = RailPiece.create(block);
    if (piece == null || piece.isNone()) {
      return Set.of();
    }
    Block railBlock = piece.block();
    if (railBlock.getX() != pos.x() || railBlock.getY() != pos.y() || railBlock.getZ() != pos.z()) {
      return Set.of();
    }

    RailType railType = piece.type();
    if (railType == null || railType == RailType.NONE) {
      return Set.of();
    }

    List<RailJunction> junctions = piece.getJunctions();
    if (junctions != null && !junctions.isEmpty()) {
      Set<RailBlockPos> neighbors = new HashSet<>();
      for (RailJunction junction : junctions) {
        if (junction == null) {
          continue;
        }
        RailState nextState = railType.takeJunction(railBlock, junction);
        if (nextState == null) {
          continue;
        }
        Block nextBlock = nextState.railBlock();
        if (nextBlock == null) {
          continue;
        }
        RailBlockPos neighborPos =
            new RailBlockPos(nextBlock.getX(), nextBlock.getY(), nextBlock.getZ());
        if (!pos.equals(neighborPos)) {
          neighbors.add(neighborPos);
        }
      }
      if (!neighbors.isEmpty()) {
        return Set.copyOf(neighbors);
      }
    }

    Set<RailBlockPos> neighbors = new HashSet<>();
    // 轨道类型未提供 junctions 时的保守回退：仅扫描相邻方块的轨道（不主动跨未加载区块，避免误触发大范围区块加载）。
    for (BlockFace face : FALLBACK_NEIGHBOR_FACES) {
      if (face == null || face == BlockFace.SELF) {
        continue;
      }
      Block candidate = railBlock.getRelative(face);
      int cx = candidate.getX();
      int cz = candidate.getZ();
      if (!world.isChunkLoaded(cx >> 4, cz >> 4)) {
        continue;
      }
      RailPiece neighborPiece = RailPiece.create(candidate);
      if (neighborPiece == null || neighborPiece.isNone()) {
        continue;
      }
      Block neighborBlock = neighborPiece.block();
      RailBlockPos neighborPos =
          new RailBlockPos(neighborBlock.getX(), neighborBlock.getY(), neighborBlock.getZ());
      if (!pos.equals(neighborPos)) {
        neighbors.add(neighborPos);
      }
    }
    return Set.copyOf(neighbors);
  }

  @Override
  public Set<RailBlockPos> neighbors(RailBlockPos pos) {
    Set<RailBlockPos> candidates = neighborCandidates(pos);
    if (candidates.isEmpty()) {
      return Set.of();
    }

    Set<RailBlockPos> neighbors = new HashSet<>();
    for (RailBlockPos candidate : candidates) {
      if (candidate == null) {
        continue;
      }
      int cx = candidate.x();
      int cz = candidate.z();
      if (!world.isChunkLoaded(cx >> 4, cz >> 4)) {
        continue;
      }
      Block candidateBlock = world.getBlockAt(candidate.x(), candidate.y(), candidate.z());
      RailPiece neighborPiece = RailPiece.create(candidateBlock);
      if (neighborPiece == null || neighborPiece.isNone()) {
        continue;
      }
      Block neighborBlock = neighborPiece.block();
      RailBlockPos neighborPos =
          new RailBlockPos(neighborBlock.getX(), neighborBlock.getY(), neighborBlock.getZ());
      if (!pos.equals(neighborPos)) {
        neighbors.add(neighborPos);
      }
    }
    return Set.copyOf(neighbors);
  }

  @Override
  public double stepCost(RailBlockPos from, RailBlockPos to) {
    Objects.requireNonNull(from, "from");
    Objects.requireNonNull(to, "to");
    if (!isRail(from) || !isRail(to)) {
      return 1.0;
    }
    int manhattan =
        Math.abs(from.x() - to.x()) + Math.abs(from.y() - to.y()) + Math.abs(from.z() - to.z());
    if (manhattan > 2) {
      double dx = from.x() - to.x();
      double dy = from.y() - to.y();
      double dz = from.z() - to.z();
      double euclidean = Math.sqrt(dx * dx + dy * dy + dz * dz);
      if (Double.isFinite(euclidean) && euclidean > 0.0) {
        return euclidean;
      }
      return 1.0;
    }
    double a = segmentLength(from);
    double b = segmentLength(to);
    double avg = 0.5 * (a + b);
    if (!Double.isFinite(avg) || avg <= 0.0) {
      return 1.0;
    }
    return avg;
  }

  private double segmentLength(RailBlockPos pos) {
    Double cached = segmentLengthCache.get(pos);
    if (cached != null) {
      return cached;
    }
    double length = 1.0;
    if (world.isChunkLoaded(pos.x() >> 4, pos.z() >> 4)) {
      Block block = world.getBlockAt(pos.x(), pos.y(), pos.z());
      RailPiece piece = RailPiece.create(block);
      if (piece != null && !piece.isNone()) {
        RailState state = RailState.getSpawnState(piece);
        RailLogic logic = state != null ? state.loadRailLogic() : null;
        RailPath path = logic != null ? logic.getPath() : null;
        double total = path != null ? path.getTotalDistance() : 0.0;
        if (Double.isFinite(total) && total > 0.0) {
          length = total;
        }
      }
    }
    segmentLengthCache.put(pos, length);
    return length;
  }
}
