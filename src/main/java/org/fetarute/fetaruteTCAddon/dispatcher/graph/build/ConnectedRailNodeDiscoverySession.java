package org.fetarute.fetaruteTCAddon.dispatcher.graph.build;

import com.bergerkiller.bukkit.tc.controller.components.RailPiece;
import com.bergerkiller.bukkit.tc.rails.RailLookup.TrackedSign;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.block.Block;
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
 *   <li>TrainCarts 节点牌子：switcher（用于把道岔牌子纳入图）
 *   <li>自动 switcher：当某轨道方块的邻居数量 ≥ 3 时生成 {@link NodeType#SWITCHER} 节点
 * </ul>
 *
 * <p>该会话默认不会主动加载区块；未加载区块会被视为不可达，因此探索范围等同于“已加载且连通”的轨道区域。
 *
 * <p>当启用 {@link ChunkLoadOptions} 后，会在“沿轨道扩张”的过程中按需异步加载相邻区块（不会随便扩张）。
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

  private ChunkLoadOptions chunkLoadOptions = ChunkLoadOptions.disabled();
  private int chunkBudgetRemaining;

  private final ArrayDeque<Long> chunkLoadQueue = new ArrayDeque<>();
  private final Set<Long> pendingLoadChunkKeys = new HashSet<>();
  private final Set<Long> processedChunkKeys = new HashSet<>();
  private final Set<Long> blockedChunkKeys = new HashSet<>();
  private final Set<Long> failedChunkKeys = new HashSet<>();
  private final Map<Long, CompletableFuture<Chunk>> inFlightChunkLoads = new HashMap<>();
  private final Map<Long, Set<RailBlockPos>> pendingRailCandidatesByChunk = new HashMap<>();

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
      Consumer<String> debugLogger,
      ChunkLoadOptions chunkLoadOptions) {
    this.world = Objects.requireNonNull(world, "world");
    this.worldId = world.getUID();
    this.access = Objects.requireNonNull(access, "access");
    this.debugLogger = debugLogger != null ? debugLogger : message -> {};
    beginChunkLoading(chunkLoadOptions);

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

  /**
   * 启用/刷新“沿轨道扩张时允许加载的区块配额”。
   *
   * <p>用途：build continue 会复用同一个 {@link ConnectedRailNodeDiscoverySession}；每次继续时需要注入新的 chunk
   * 配额与并发上限。
   *
   * <p>注意：当 options.disabled 时，本会话不会尝试加载任何区块，未加载区块会被视为不可达，从而导致探索结果天然“可能缺失”。
   */
  public void beginChunkLoading(ChunkLoadOptions options) {
    this.chunkLoadOptions = options != null ? options : ChunkLoadOptions.disabled();
    this.chunkBudgetRemaining = this.chunkLoadOptions.maxChunks();
    if (!this.chunkLoadOptions.enabled()) {
      return;
    }
    scheduleBlockedChunks();
  }

  private void scheduleBlockedChunks() {
    if (!chunkLoadOptions.enabled() || chunkBudgetRemaining <= 0 || blockedChunkKeys.isEmpty()) {
      return;
    }
    for (Iterator<Long> iterator = blockedChunkKeys.iterator(); iterator.hasNext(); ) {
      long key = iterator.next();
      if (chunkBudgetRemaining <= 0) {
        return;
      }
      if (!pendingRailCandidatesByChunk.containsKey(key)) {
        iterator.remove();
        continue;
      }
      int chunkX = chunkX(key);
      int chunkZ = chunkZ(key);
      if (world.isChunkLoaded(chunkX, chunkZ)) {
        iterator.remove();
        processLoadedChunk(key, chunkX, chunkZ);
        continue;
      }
      iterator.remove();
      scheduleChunkLoad(key);
    }
  }

  /**
   * 推进一次“沿轨道扩张 + 增量扫描”的探索步骤，直到达到 deadline 或队列耗尽。
   *
   * <p>该方法设计为被主线程每 tick 调用：以时间片推进任务避免一次性卡服（time-sliced execution）。
   *
   * <p>输出写入到 {@code byNodeId} 以去重：当同一 nodeId 被扫描到多次时，会保留“更可信”的记录（例如 switcher 牌子覆盖自动 switcher）。
   *
   * @param deadlineNanos 截止时间（System.nanoTime）
   * @param byNodeId 输出：nodeId → RailNodeRecord（用于聚合/去重）
   * @return 本次 step 推进的工作量计数（用于统计与 status 展示）
   */
  public int step(long deadlineNanos, Map<String, RailNodeRecord> byNodeId) {
    Objects.requireNonNull(byNodeId, "byNodeId");
    int progressed = 0;
    while (System.nanoTime() < deadlineNanos) {
      progressed += stepChunkLoads();
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

      if (junctionCount(current) >= 3) {
        // 自动 switcher 仅用于把“关键分叉位置”纳入图：这里必须基于“真实连通邻居数”判断。
        // 不要用 RailPiece#getJunctions() 这类偏“路径段”的概念；否则在 TCCoasters/曲线轨道下会产生大量误判（ghost switcher）。
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

      if (!chunkLoadOptions.enabled()) {
        Set<RailBlockPos> neighbors = access.neighbors(current);
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
        continue;
      }

      Set<RailBlockPos> candidates = access.neighborCandidates(current);
      for (RailBlockPos candidate : candidates) {
        if (candidate == null) {
          continue;
        }
        int chunkX = candidate.x() >> 4;
        int chunkZ = candidate.z() >> 4;
        if (world.isChunkLoaded(chunkX, chunkZ)) {
          queueRailCandidate(candidate);
          continue;
        }
        long key = chunkKey(chunkX, chunkZ);
        pendingRailCandidatesByChunk
            .computeIfAbsent(key, ignored -> new HashSet<>())
            .add(candidate);
        scheduleChunkLoad(key);
      }
    }

    return progressed;
  }

  private int stepChunkLoads() {
    if (!chunkLoadOptions.enabled()) {
      return 0;
    }

    int progressed = 0;
    progressed += completeChunkLoads();
    progressed += startChunkLoads();
    return progressed;
  }

  private int completeChunkLoads() {
    if (inFlightChunkLoads.isEmpty()) {
      return 0;
    }
    int completed = 0;
    for (Iterator<Map.Entry<Long, CompletableFuture<Chunk>>> iterator =
            inFlightChunkLoads.entrySet().iterator();
        iterator.hasNext(); ) {
      Map.Entry<Long, CompletableFuture<Chunk>> entry = iterator.next();
      CompletableFuture<Chunk> future = entry.getValue();
      if (future == null || !future.isDone()) {
        continue;
      }

      long key = entry.getKey();
      iterator.remove();
      pendingLoadChunkKeys.remove(key);

      Chunk chunk;
      try {
        chunk = future.join();
      } catch (Throwable ex) {
        failedChunkKeys.add(key);
        pendingRailCandidatesByChunk.remove(key);
        debugLogger.accept(
            "区块异步加载失败: world="
                + world.getName()
                + " chunk=("
                + chunkX(key)
                + ","
                + chunkZ(key)
                + ")");
        continue;
      }

      if (chunk == null) {
        failedChunkKeys.add(key);
        pendingRailCandidatesByChunk.remove(key);
        continue;
      }

      processLoadedChunk(key, chunkX(key), chunkZ(key));
      completed++;
    }
    return completed;
  }

  private void processLoadedChunk(long key, int chunkX, int chunkZ) {
    processedChunkKeys.add(key);
    blockedChunkKeys.remove(key);
    queueChunk(chunkX, chunkZ);
    Set<RailBlockPos> candidates = pendingRailCandidatesByChunk.remove(key);
    if (candidates == null || candidates.isEmpty()) {
      return;
    }
    for (RailBlockPos candidate : candidates) {
      queueRailCandidate(candidate);
    }
  }

  private int startChunkLoads() {
    if (chunkLoadQueue.isEmpty()) {
      return 0;
    }
    if (inFlightChunkLoads.size() >= chunkLoadOptions.maxConcurrentLoads()) {
      return 0;
    }

    int started = 0;
    while (inFlightChunkLoads.size() < chunkLoadOptions.maxConcurrentLoads()) {
      Long key = chunkLoadQueue.poll();
      if (key == null) {
        break;
      }
      pendingLoadChunkKeys.remove(key);
      int chunkX = chunkX(key);
      int chunkZ = chunkZ(key);
      if (world.isChunkLoaded(chunkX, chunkZ)) {
        chunkBudgetRemaining++;
        processLoadedChunk(key, chunkX, chunkZ);
        continue;
      }
      CompletableFuture<Chunk> future = world.getChunkAtAsync(chunkX, chunkZ, false, false);
      inFlightChunkLoads.put(key, future);
      started++;
    }
    return started;
  }

  private void scheduleChunkLoad(long key) {
    if (!chunkLoadOptions.enabled()) {
      return;
    }
    if (processedChunkKeys.contains(key) || failedChunkKeys.contains(key)) {
      return;
    }
    if (pendingLoadChunkKeys.contains(key) || inFlightChunkLoads.containsKey(key)) {
      return;
    }
    if (chunkBudgetRemaining <= 0) {
      blockedChunkKeys.add(key);
      return;
    }
    chunkBudgetRemaining--;
    pendingLoadChunkKeys.add(key);
    chunkLoadQueue.add(key);
  }

  private void queueRailCandidate(RailBlockPos candidate) {
    if (candidate == null) {
      return;
    }
    if (!world.isChunkLoaded(candidate.x() >> 4, candidate.z() >> 4)) {
      return;
    }
    RailPiece piece =
        RailPiece.create(world.getBlockAt(candidate.x(), candidate.y(), candidate.z()));
    if (piece == null || piece.isNone()) {
      return;
    }
    Block railBlock = piece.block();
    RailBlockPos anchor = new RailBlockPos(railBlock.getX(), railBlock.getY(), railBlock.getZ());
    if (visitedRails.add(anchor)) {
      railQueue.add(anchor);
      queueChunk(anchor.x() >> 4, anchor.z() >> 4);
    }
  }

  private int junctionCount(RailBlockPos railPos) {
    if (railPos == null) {
      return 0;
    }
    return access.neighbors(railPos).size();
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

  /**
   * @return 是否完全完成：所有可达轨道已处理完毕，且没有“待加载的 blocked chunk”。
   */
  public boolean isDone() {
    return isIdle() && blockedChunkKeys.isEmpty();
  }

  /**
   * @return 是否暂停：队列已空但仍有 blocked chunk 等待配额加载（可用 /fta graph continue 续跑）。
   */
  public boolean isPaused() {
    return isIdle() && !blockedChunkKeys.isEmpty();
  }

  private boolean isIdle() {
    return railQueue.isEmpty()
        && chunkQueue.isEmpty()
        && currentStates == null
        && chunkLoadQueue.isEmpty()
        && inFlightChunkLoads.isEmpty();
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

  /**
   * @return 仍待加载的 chunk 数量（blocked）。
   */
  public int pendingChunksToLoad() {
    return blockedChunkKeys.size();
  }

  public int inFlightChunks() {
    return inFlightChunkLoads.size();
  }

  public int failedChunks() {
    return failedChunkKeys.size();
  }

  private static long chunkKey(int chunkX, int chunkZ) {
    return (((long) chunkX) << 32) ^ (chunkZ & 0xffffffffL);
  }

  private static int chunkX(long key) {
    return (int) (key >> 32);
  }

  private static int chunkZ(long key) {
    return (int) key;
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
