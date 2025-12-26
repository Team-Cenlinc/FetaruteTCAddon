package org.fetarute.fetaruteTCAddon.dispatcher.graph.build;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import org.bukkit.Chunk;
import org.bukkit.World;
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
 *   <li>TrainCarts 节点牌子：switcher/tag
 * </ul>
 *
 * <p>注意：该扫描不会主动加载区块，因此“未加载区域”的节点不会被发现。
 */
public final class LoadedChunkNodeScanSession {

  private final World world;
  private final UUID worldId;
  private final Chunk[] chunks;
  private final Consumer<String> debugLogger;

  private int chunkIndex;
  private BlockState[] currentStates;
  private int stateIndex;
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
      scannedSigns++;
      NodeSignDefinitionParser.parse(sign)
          .or(() -> SwitcherSignDefinitionParser.parse(sign))
          .ifPresent(
              def -> {
                int x = sign.getLocation().getBlockX();
                int y = sign.getLocation().getBlockY();
                int z = sign.getLocation().getBlockZ();
                if (def.nodeType() == NodeType.SWITCHER) {
                  Optional<RailBlockPos> railPos =
                      SwitcherSignDefinitionParser.tryParseRailPos(def.nodeId());
                  if (railPos.isPresent()) {
                    var pos = railPos.get();
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
                RailNodeRecord existing = byNodeId.get(def.nodeId().value());
                if (existing == null) {
                  byNodeId.put(def.nodeId().value(), record);
                  return;
                }
                if (existing.nodeType() == NodeType.SWITCHER
                    && def.nodeType() == NodeType.SWITCHER
                    && existing.trainCartsDestination().isEmpty()
                    && def.trainCartsDestination().isPresent()) {
                  byNodeId.put(def.nodeId().value(), record);
                  return;
                }
                if (!existing.equals(record)) {
                  debugLogger.accept(
                      "扫描到重复 nodeId，已忽略: node="
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

    return scanned;
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
}
