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
        if (nextState != null) {
          Block nextBlock = nextState.railBlock();
          if (nextBlock != null) {
            RailBlockPos neighborPos =
                new RailBlockPos(nextBlock.getX(), nextBlock.getY(), nextBlock.getZ());
            if (!pos.equals(neighborPos)) {
              neighbors.add(neighborPos);
            }
            continue;
          }
        }

        // takeJunction 失败（可能是目标区块未加载），尝试从 junction 的方向信息预测目标位置
        // 这对于 loadChunks 模式很重要：我们需要知道要加载哪个区块
        RailBlockPos predicted = predictNeighborFromJunction(pos, junction);
        if (predicted != null && !pos.equals(predicted)) {
          neighbors.add(predicted);
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
        // 在 loadChunks 模式下，我们也返回未加载区块中的候选位置
        neighbors.add(new RailBlockPos(candidate.getX(), candidate.getY(), candidate.getZ()));
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

  /**
   * 从 RailJunction 的位置/方向信息预测邻居位置。
   *
   * <p>当 takeJunction 因目标区块未加载而失败时，我们可以尝试从 junction 的运动方向预测目标位置。 这对于 TCCoasters
   * 的长距离轨道段特别有用：即使目标区块未加载，我们仍能知道大致方向。
   *
   * @param currentPos 当前轨道位置
   * @param junction 要预测的 junction
   * @return 预测的邻居位置，如果无法预测则返回 null
   */
  private RailBlockPos predictNeighborFromJunction(RailBlockPos currentPos, RailJunction junction) {
    if (junction == null) {
      return null;
    }

    // 尝试从 junction.position() 获取方向信息
    RailPath.Position junctionPos = junction.position();
    if (junctionPos == null) {
      return null;
    }

    // 获取运动方向向量
    double motionX = junctionPos.motX;
    double motionY = junctionPos.motY;
    double motionZ = junctionPos.motZ;

    // 如果方向向量为零，尝试使用 BlockFace
    if (Math.abs(motionX) < 0.01 && Math.abs(motionY) < 0.01 && Math.abs(motionZ) < 0.01) {
      BlockFace face = junctionPos.getMotionFace();
      if (face != null && face != BlockFace.SELF) {
        motionX = face.getModX();
        motionY = face.getModY();
        motionZ = face.getModZ();
      }
    }

    // 归一化并扩展预测距离
    // 对于普通轨道，预测 1-2 格；对于 TCC 长距离轨道，可能需要更大的预测距离
    double length = Math.sqrt(motionX * motionX + motionY * motionY + motionZ * motionZ);
    if (length < 0.01) {
      return null;
    }

    // 预测几个可能的位置（1格、2格、4格、8格、16格）
    // 返回第一个在不同区块的位置，便于触发区块加载
    int[] distances = {1, 2, 4, 8, 16, 32};
    int currentChunkX = currentPos.x() >> 4;
    int currentChunkZ = currentPos.z() >> 4;

    for (int dist : distances) {
      int predictedX = currentPos.x() + (int) Math.round((motionX / length) * dist);
      int predictedY = currentPos.y() + (int) Math.round((motionY / length) * dist);
      int predictedZ = currentPos.z() + (int) Math.round((motionZ / length) * dist);

      int predictedChunkX = predictedX >> 4;
      int predictedChunkZ = predictedZ >> 4;

      // 如果预测位置在不同区块，返回它（这样可以触发区块加载）
      if (predictedChunkX != currentChunkX || predictedChunkZ != currentChunkZ) {
        return new RailBlockPos(predictedX, predictedY, predictedZ);
      }
    }

    // 如果所有预测都在当前区块内，返回最远的那个
    int dist = distances[distances.length - 1];
    return new RailBlockPos(
        currentPos.x() + (int) Math.round((motionX / length) * dist),
        currentPos.y() + (int) Math.round((motionY / length) * dist),
        currentPos.z() + (int) Math.round((motionZ / length) * dist));
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

  /**
   * 返回 rail 方块的“真实分叉度”（按可达的连通邻居去重计数）。
   *
   * <p>用于更保守地判定“真实分叉”，避免出现以下误判：
   *
   * <ul>
   *   <li>轨道实现返回多个 junction 名称，但它们实际指向同一个下一段轨道（若直接数 junction 名称会虚高）
   *   <li>相邻但不连通的平行轨道（不应被视为分叉）
   * </ul>
   */
  public int junctionCount(RailBlockPos pos) {
    if (pos == null) {
      return 0;
    }
    if (!world.isChunkLoaded(pos.x() >> 4, pos.z() >> 4)) {
      return 0;
    }
    Block block = world.getBlockAt(pos.x(), pos.y(), pos.z());
    RailPiece piece = RailPiece.create(block);
    if (piece == null || piece.isNone()) {
      return 0;
    }
    Block railBlock = piece.block();
    if (railBlock.getX() != pos.x() || railBlock.getY() != pos.y() || railBlock.getZ() != pos.z()) {
      return 0;
    }
    List<RailJunction> junctions = piece.getJunctions();
    if (junctions == null || junctions.isEmpty()) {
      return 0;
    }
    Set<RailBlockPos> targets = new HashSet<>();
    RailType railType = piece.type();
    if (railType == null || railType == RailType.NONE) {
      return 0;
    }
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
      RailBlockPos next = new RailBlockPos(nextBlock.getX(), nextBlock.getY(), nextBlock.getZ());
      if (pos.equals(next)) {
        continue;
      }
      if (!isRail(next)) {
        continue;
      }
      targets.add(next);
    }
    return targets.size();
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
