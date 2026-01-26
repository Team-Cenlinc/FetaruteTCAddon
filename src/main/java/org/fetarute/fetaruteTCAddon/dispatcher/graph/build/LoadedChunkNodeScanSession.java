package org.fetarute.fetaruteTCAddon.dispatcher.graph.build;

import com.bergerkiller.bukkit.tc.controller.components.RailPiece;
import com.bergerkiller.bukkit.tc.rails.RailLookup;
import com.bergerkiller.bukkit.tc.rails.RailLookup.TrackedSign;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Sign;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.explore.RailBlockPos;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.persist.RailNodeRecord;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.NodeSignDefinitionParser;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SwitcherSignDefinitionParser;

/**
 * 从已加载区块增量扫描节点牌子，用于控制台 build all 或兜底扫描。
 *
 * <p>会识别：
 *
 * <ul>
 *   <li>本插件节点牌子：waypoint/autostation/depot
 *   <li>TrainCarts 节点牌子：switcher
 * </ul>
 *
 * <p>注意：该扫描不会主动加载区块，因此“未加载区域”的节点不会被发现。
 */
public final class LoadedChunkNodeScanSession {

  private final World world;
  private final UUID worldId;
  private final Chunk[] chunks;
  private final Consumer<String> debugLogger;
  private final DuplicateNodeIdCollector duplicateCollector = new DuplicateNodeIdCollector();

  private int chunkIndex;
  private BlockState[] currentStates;
  private int stateIndex;
  private final Set<Object> scannedTrackedSignKeys = new HashSet<>();
  private int scannedTileEntities;
  private int scannedSigns;

  public LoadedChunkNodeScanSession(World world, Consumer<String> debugLogger) {
    this.world = Objects.requireNonNull(world, "world");
    this.worldId = world.getUID();
    this.chunks = world.getLoadedChunks();
    this.debugLogger = debugLogger != null ? debugLogger : message -> {};
  }

  /**
   * 扫描直到达到 deadline 或扫描完成。
   *
   * @param deadlineNanos 截止时间（System.nanoTime()）
   * @param byNodeId 输出：nodeId → RailNodeRecord（用于去重）
   * @return 本次 step 扫描的 tile entity 数量（用于统计）
   */
  public int step(long deadlineNanos, Map<String, RailNodeRecord> byNodeId) {
    Objects.requireNonNull(byNodeId, "byNodeId");
    int scanned = 0;

    while (System.nanoTime() < deadlineNanos) {
      if (currentStates == null) {
        if (chunkIndex >= chunks.length) {
          return scanned;
        }
        Chunk chunk = chunks[chunkIndex++];
        if (chunk == null) {
          continue;
        }
        currentStates = chunk.getTileEntities();
        stateIndex = 0;
        continue;
      }

      if (stateIndex >= currentStates.length) {
        currentStates = null;
        continue;
      }

      BlockState state = currentStates[stateIndex++];
      scannedTileEntities++;
      scanned++;
      if (!(state instanceof Sign sign)) {
        continue;
      }
      scanTrackedSignsFromSign(sign, byNodeId);
    }

    return scanned;
  }

  private void scanTrackedSignsFromSign(Sign sign, Map<String, RailNodeRecord> byNodeId) {
    if (sign == null) {
      return;
    }
    RailPiece piece = RailLookup.discoverRailPieceFromSign(sign.getBlock());
    if (piece == null || piece.isNone()) {
      return;
    }
    TrackedSign[] signs = RailLookup.discoverSignsAtRailPiece(piece);
    if (signs == null || signs.length == 0) {
      return;
    }
    for (TrackedSign tracked : signs) {
      if (tracked == null) {
        continue;
      }
      Object uniqueKey = tracked.getUniqueKey();
      if (uniqueKey != null && !scannedTrackedSignKeys.add(uniqueKey)) {
        continue;
      }
      scannedSigns++;
      NodeSignDefinitionParser.parse(tracked)
          .or(() -> SwitcherSignDefinitionParser.parse(tracked))
          .ifPresent(
              def -> {
                RailBlockPos anchorPos =
                    resolveRailPosFromTrackedSign(
                        tracked,
                        new RailBlockPos(
                            sign.getLocation().getBlockX(),
                            sign.getLocation().getBlockY(),
                            sign.getLocation().getBlockZ()));
                int x = anchorPos.x();
                int y = anchorPos.y();
                int z = anchorPos.z();
                if (def.nodeType() == NodeType.SWITCHER) {
                  Optional<RailBlockPos> parsed =
                      SwitcherSignDefinitionParser.tryParseRailPos(def.nodeId());
                  if (parsed.isPresent()) {
                    RailBlockPos pos = parsed.get();
                    x = pos.x();
                    y = pos.y();
                    z = pos.z();
                  }
                }
                RailNodeRecord record =
                    new RailNodeRecord(
                        worldId,
                        def.nodeId(),
                        def.nodeType(),
                        x,
                        y,
                        z,
                        def.trainCartsDestination(),
                        def.waypointMetadata());
                duplicateCollector.record(
                    def.nodeId(),
                    new DuplicateNodeId.Occurrence(
                        def.nodeType(), x, y, z, /* virtualSign= */ !tracked.isRealSign()));
                RailNodeRecord existing = byNodeId.put(def.nodeId().value(), record);
                if (existing != null && !existing.equals(record)) {
                  debugLogger.accept(
                      "扫描到重复 nodeId，已用 railPiece.signs() 结果覆盖: node="
                          + def.nodeId().value()
                          + " @ "
                          + world.getName()
                          + " ("
                          + x
                          + ","
                          + y
                          + ","
                          + z
                          + "), existing=("
                          + existing.x()
                          + ","
                          + existing.y()
                          + ","
                          + existing.z()
                          + ")");
                }
              });
    }
  }

  /**
   * 解析节点牌子对应的“锚点轨道坐标”。
   *
   * <p>真实牌子优先使用牌子方块坐标；虚拟牌子（TCC TrackNodeSign）回退为轨道方块坐标。
   */
  private RailBlockPos resolveRailPosFromTrackedSign(TrackedSign tracked, RailBlockPos fallback) {
    if (tracked == null) {
      return fallback;
    }
    if (tracked.isRealSign()) {
      Block signBlock = tracked.signBlock;
      if (signBlock != null) {
        return new RailBlockPos(signBlock.getX(), signBlock.getY(), signBlock.getZ());
      }
    }
    RailPiece rail = tracked.getRail();
    if (rail == null || rail.block() == null) {
      return fallback;
    }
    Block block = rail.block();
    return new RailBlockPos(block.getX(), block.getY(), block.getZ());
  }

  public boolean isDone() {
    return chunkIndex >= chunks.length && currentStates == null;
  }

  public int scannedTileEntities() {
    return scannedTileEntities;
  }

  public int scannedSigns() {
    return scannedSigns;
  }

  public int loadedChunks() {
    return chunks.length;
  }

  public int chunksScanned() {
    return chunkIndex;
  }

  /** 返回本次会话扫描到的重复 nodeId 列表（仅诊断用途）。 */
  public java.util.List<DuplicateNodeId> duplicateNodeIds() {
    return duplicateCollector.duplicates();
  }
}
