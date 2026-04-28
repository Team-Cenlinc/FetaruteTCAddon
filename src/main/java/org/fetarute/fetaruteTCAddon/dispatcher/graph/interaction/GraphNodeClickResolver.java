package org.fetarute.fetaruteTCAddon.dispatcher.graph.interaction;

import com.bergerkiller.bukkit.tc.controller.components.RailPiece;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Sign;
import org.fetarute.fetaruteTCAddon.FetaruteTCAddon;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.explore.RailBlockPos;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.explore.TrainCartsRailBlockAccess;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.NodeSignDefinitionParser;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeRegistry;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SwitcherSignDefinitionParser;

/**
 * 将玩家点击的牌子或轨道方块解析为调度图节点。
 *
 * <p>调试棍、限速设置棍都需要一致的选点体验：直接点节点牌子时优先读 registry，点击轨道时按 TrainCarts
 * 轨道锚点反查附近节点牌子。这里集中这套规则，避免写入类工具与只读诊断工具出现不同的节点识别结果。
 */
public final class GraphNodeClickResolver {

  private static final int RAIL_SIGN_SCAN_RADIUS = 2;

  private final FetaruteTCAddon plugin;
  private final SignNodeRegistry registry;

  public GraphNodeClickResolver(FetaruteTCAddon plugin, SignNodeRegistry registry) {
    this.plugin = java.util.Objects.requireNonNull(plugin, "plugin");
    this.registry = java.util.Objects.requireNonNull(registry, "registry");
  }

  /** 解析“牌子或轨道”对应的节点定义。 */
  public Optional<SignNodeDefinition> resolveNodeFromBlock(Block clicked) {
    if (clicked == null) {
      return Optional.empty();
    }

    Optional<SignNodeDefinition> signOpt = resolveNodeFromSignBlock(clicked);
    if (signOpt.isPresent()) {
      return signOpt;
    }
    return resolveNodeFromRailBlock(clicked);
  }

  /** 优先通过 registry/牌子解析节点定义。 */
  private Optional<SignNodeDefinition> resolveNodeFromSignBlock(Block block) {
    if (block == null) {
      return Optional.empty();
    }
    return registry.get(block).or(() -> parseSignBlock(block));
  }

  /** 解析可见牌子（含 TrainCarts switcher 牌子）。 */
  private Optional<SignNodeDefinition> parseSignBlock(Block block) {
    if (block == null) {
      return Optional.empty();
    }
    BlockState state = block.getState();
    if (!(state instanceof Sign sign)) {
      return Optional.empty();
    }
    return NodeSignDefinitionParser.parse(sign).or(() -> SwitcherSignDefinitionParser.parse(sign));
  }

  /**
   * 从轨道方块反推其对应的节点牌子。
   *
   * <p>用于支持“点击轨道也能获取节点信息”的交互。
   */
  private Optional<SignNodeDefinition> resolveNodeFromRailBlock(Block railBlock) {
    if (railBlock == null) {
      return Optional.empty();
    }
    RailPiece piece = RailPiece.create(railBlock);
    if (piece == null || piece.isNone()) {
      return Optional.empty();
    }

    TrainCartsRailBlockAccess access = new TrainCartsRailBlockAccess(railBlock.getWorld());
    Block canonical = piece.block();
    RailBlockPos railPos = new RailBlockPos(canonical.getX(), canonical.getY(), canonical.getZ());
    if (!access.isRail(railPos)) {
      return Optional.empty();
    }

    List<Block> candidates = new ArrayList<>();
    for (int dx = -RAIL_SIGN_SCAN_RADIUS; dx <= RAIL_SIGN_SCAN_RADIUS; dx++) {
      for (int dy = -RAIL_SIGN_SCAN_RADIUS; dy <= RAIL_SIGN_SCAN_RADIUS; dy++) {
        for (int dz = -RAIL_SIGN_SCAN_RADIUS; dz <= RAIL_SIGN_SCAN_RADIUS; dz++) {
          candidates.add(railBlock.getRelative(dx, dy, dz));
        }
      }
    }

    SignNodeDefinition best = null;
    int bestDistance = Integer.MAX_VALUE;
    int anchorRadius = plugin.getConfigManager().current().graphSettings().signAnchorSearchRadius();
    for (Block candidate : candidates) {
      Optional<SignNodeDefinition> defOpt = resolveNodeFromSignBlock(candidate);
      if (defOpt.isEmpty()) {
        continue;
      }
      Set<RailBlockPos> anchors =
          access.findNearestRailBlocks(
              new RailBlockPos(candidate.getX(), candidate.getY(), candidate.getZ()), anchorRadius);
      if (!anchors.contains(railPos)) {
        continue;
      }
      int distance =
          Math.abs(candidate.getX() - railBlock.getX())
              + Math.abs(candidate.getY() - railBlock.getY())
              + Math.abs(candidate.getZ() - railBlock.getZ());
      if (best == null || distance < bestDistance) {
        best = defOpt.get();
        bestDistance = distance;
      }
    }
    return Optional.ofNullable(best);
  }
}
