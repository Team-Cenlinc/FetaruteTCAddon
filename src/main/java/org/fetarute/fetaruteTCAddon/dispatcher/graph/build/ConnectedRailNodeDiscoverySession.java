package org.fetarute.fetaruteTCAddon.dispatcher.graph.build;

import com.bergerkiller.bukkit.tc.controller.components.RailPiece;
import com.bergerkiller.bukkit.tc.rails.RailLookup.TrackedSign;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.block.Sign;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.explore.RailBlockAccess;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.explore.RailBlockPos;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.persist.RailNodeRecord;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.NodeSignDefinitionParser;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SwitcherSignDefinitionParser;

/**
 * 从一组起始轨道锚点出发，沿轨道连通性扩展，并在触达的区块内增量扫描节点牌子。
 *
 * <p>节点来源：
 *
 * <ul>
 *   <li>本插件节点牌子：waypoint/autostation/depot
 *   <li>TrainCarts 节点牌子：switcher/tag（用于把道岔牌子纳入图）
 *   <li>自动 switcher：当某轨道方块的邻居数量 ≥ 3 时生成 {@link NodeType#SWITCHER} 节点
 * </ul>
 *
 * <p>该会话不会主动加载区块；未加载区块会被视为不可达，因此探索范围等同于“已加载且连通”的轨道区域。
 */
public final class ConnectedRailNodeDiscoverySession {

  private static final int NODE_SIGN_ANCHOR_RADIUS = 6;

  private final World world;
  private final UUID worldId;
  private final RailBlockAccess access;
  private final Consumer<String> debugLogger;

  private final ArrayDeque<RailBlockPos> railQueue = new ArrayDeque<>();
  private final Set<RailBlockPos> visitedRails = new HashSet<>();

  private final ArrayDeque<Chunk> chunkQueue = new ArrayDeque<>();
  private final Set<Long> queuedChunkKeys = new HashSet<>();

  private BlockState[] currentStates;
  private int stateIndex;
  private final Set<RailBlockPos> scannedSignBlocks = new HashSet<>();
  private final Set<Object> scannedVirtualSignKeys = new HashSet<>();

  private long processedRailSteps;
  private int scannedTileEntities;
  private int scannedSigns;

  public ConnectedRailNodeDiscoverySession(
      World world,
      Set<RailBlockPos> seedRails,
      RailBlockAccess access,
      Consumer<String> debugLogger) {
    this.world = Objects.requireNonNull(world, "world");
    this.worldId = world.getUID();
    this.access = Objects.requireNonNull(access, "access");
    this.debugLogger = debugLogger != null ? debugLogger : message -> {};

    for (RailBlockPos seed : seedRails) {
      if (seed == null) {
        continue;
      }
      if (!access.isRail(seed)) {
        continue;
      }
      if (visitedRails.add(seed)) {
        railQueue.add(seed);
        queueChunk(seed.x() >> 4, seed.z() >> 4);
      }
    }
  }

  public int step(long deadlineNanos, Map<String, RailNodeRecord> byNodeId) {
    Objects.requireNonNull(byNodeId, "byNodeId");
    int progressed = 0;
    while (System.nanoTime() < deadlineNanos) {
      if (currentStates != null) {
        if (stateIndex >= currentStates.length) {
          currentStates = null;
          stateIndex = 0;
          continue;
        }
        BlockState state = currentStates[stateIndex++];
        scannedTileEntities++;
        progressed++;
        if (!(state instanceof Sign sign)) {
          continue;
        }
        RailBlockPos signPos =
            new RailBlockPos(
                sign.getLocation().getBlockX(),
                sign.getLocation().getBlockY(),
                sign.getLocation().getBlockZ());
        if (!scannedSignBlocks.add(signPos)) {
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
                      RailBlockPos pos = railPos.get();
                      x = pos.x();
                      y = pos.y();
                      z = pos.z();
                    }
                  } else {
                    RailBlockPos anchor = resolveAnchor(signPos);
                    x = anchor.x();
                    y = anchor.y();
                    z = anchor.z();
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
        continue;
      }

      if (!chunkQueue.isEmpty()) {
        Chunk chunk = chunkQueue.poll();
        if (chunk == null) {
          continue;
        }
        currentStates = chunk.getTileEntities();
        stateIndex = 0;
        continue;
      }

      RailBlockPos current = railQueue.poll();
      if (current == null) {
        return progressed;
      }
      processedRailSteps++;
      progressed++;

      scanSignsFromRailPiece(current, byNodeId);

      Set<RailBlockPos> neighbors = access.neighbors(current);
      if (neighbors.size() >= 3) {
        var nodeId = SwitcherSignDefinitionParser.nodeIdForRail(world.getName(), current);
        byNodeId.putIfAbsent(
            nodeId.value(),
            new RailNodeRecord(
                worldId,
                nodeId,
                NodeType.SWITCHER,
                current.x(),
                current.y(),
                current.z(),
                Optional.empty(),
                Optional.empty()));
      }

      for (RailBlockPos neighbor : neighbors) {
        if (neighbor == null) {
          continue;
        }
        if (!access.isRail(neighbor)) {
          continue;
        }
        if (visitedRails.add(neighbor)) {
          railQueue.add(neighbor);
          queueChunk(neighbor.x() >> 4, neighbor.z() >> 4);
        }
      }
    }

    return progressed;
  }

  private void queueChunk(int chunkX, int chunkZ) {
    if (!world.isChunkLoaded(chunkX, chunkZ)) {
      return;
    }
    long key = (((long) chunkX) << 32) ^ (chunkZ & 0xffffffffL);
    if (!queuedChunkKeys.add(key)) {
      return;
    }
    chunkQueue.add(world.getChunkAt(chunkX, chunkZ));
  }

  public boolean isDone() {
    return railQueue.isEmpty() && chunkQueue.isEmpty() && currentStates == null;
  }

  public Set<RailBlockPos> visitedRails() {
    return Set.copyOf(visitedRails);
  }

  public int visitedRailBlocks() {
    return visitedRails.size();
  }

  public int queueSize() {
    return railQueue.size();
  }

  public long processedRailSteps() {
    return processedRailSteps;
  }

  public int scannedTileEntities() {
    return scannedTileEntities;
  }

  public int scannedSigns() {
    return scannedSigns;
  }

  public int scannedChunks() {
    return queuedChunkKeys.size();
  }

  private void scanSignsFromRailPiece(RailBlockPos railPos, Map<String, RailNodeRecord> byNodeId) {
    if (railPos == null) {
      return;
    }
    if (!world.isChunkLoaded(railPos.x() >> 4, railPos.z() >> 4)) {
      return;
    }

    RailPiece piece = RailPiece.create(world.getBlockAt(railPos.x(), railPos.y(), railPos.z()));
    if (piece == null || piece.isNone()) {
      return;
    }

    TrackedSign[] signs = piece.signs();
    if (signs == null || signs.length == 0) {
      return;
    }

    for (TrackedSign tracked : signs) {
      if (tracked == null) {
        continue;
      }

      // TCCoasters 的 TrackNodeSign 是虚拟牌子（TrackedFakeSign），不会作为 tile entity 被 chunk 扫描发现；
      // 因此这里只额外处理“非真实牌子”的情况，避免与上面的 tile entity 扫描重复。
      if (tracked.isRealSign()) {
        continue;
      }
      Object uniqueKey = tracked.getUniqueKey();
      if (uniqueKey != null && !scannedVirtualSignKeys.add(uniqueKey)) {
        continue;
      }
      scannedSigns++;
      NodeSignDefinitionParser.parse(tracked)
          .or(() -> SwitcherSignDefinitionParser.parse(tracked))
          .ifPresent(
              def -> {
                int x = railPos.x();
                int y = railPos.y();
                int z = railPos.z();
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
  }

  private RailBlockPos resolveAnchor(RailBlockPos signPos) {
    Set<RailBlockPos> anchors = access.findNearestRailBlocks(signPos, NODE_SIGN_ANCHOR_RADIUS);
    if (anchors.isEmpty()) {
      return signPos;
    }
    RailBlockPos best = null;
    for (RailBlockPos candidate : anchors) {
      if (best == null) {
        best = candidate;
        continue;
      }
      if (candidate.x() < best.x()
          || (candidate.x() == best.x() && candidate.y() < best.y())
          || (candidate.x() == best.x() && candidate.y() == best.y() && candidate.z() < best.z())) {
        best = candidate;
      }
    }
    return best != null ? best : signPos;
  }
}
